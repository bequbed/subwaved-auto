package com.powerpoppalace.subwaveauto.playback

import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.powerpoppalace.subwaveauto.art.ArtDiagnostics
import com.powerpoppalace.subwaveauto.art.ArtMode
import com.powerpoppalace.subwaveauto.art.ArtNormalizer
import com.powerpoppalace.subwaveauto.art.ArtworkStore
import com.powerpoppalace.subwaveauto.art.planArtFields
import com.powerpoppalace.subwaveauto.net.NowPlaying
import com.powerpoppalace.subwaveauto.net.StationApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Poll cadence for `/api/now-playing` while playing — 5 s, same as the web/RN players. */
private const val POLL_INTERVAL_MS = 5_000L

/**
 * `MediaMetadata.artworkData` cap (§1: "artworkData capped 1 MB"). This sits BELOW
 * [StationApi.MAX_ART_BYTES] (2 MB download cap): an image the API returns but that
 * exceeds this cap is skipped — metadata still updates, just without artwork.
 */
internal const val MAX_METADATA_ART_BYTES = 1024 * 1024

/** Artwork byte-cache size (§1: "artwork cache ≤ 8 entries (LRU)"). */
internal const val MAX_ART_CACHE_ENTRIES = 8

/** Recent now-playing snapshots kept for ICY → art/album matching (LRU). */
internal const val MAX_SNAPSHOT_CACHE_ENTRIES = 8

/**
 * How many times the inline artwork fetch may fail for ONE cover URL before the
 * push latches it as applied anyway. Latching stops the 5 s poll re-fetching a
 * hopeless URL forever; a small retry budget rescues the common transient case —
 * the connect-in-a-parking-garage push whose art fetch timed out while the
 * stream itself recovered.
 */
internal const val MAX_ART_FETCH_ATTEMPTS = 3

/**
 * Normalised identity key matching an ICY StreamTitle to a cached `/api/now-playing`
 * snapshot: case- and whitespace-insensitive `artist|title`. Pure, JVM-tested.
 */
internal fun icyKey(title: String?, artist: String?): String =
    "${artist.orEmpty().trim().lowercase()}|${title.orEmpty().trim().lowercase()}"

/**
 * Parse an ICY `StreamTitle` — Icecast relays what Liquidsoap set, conventionally
 * `"Artist - Title"`. Split on the FIRST `" - "` (an artist containing the
 * separator is rarer than a title containing it); no separator → the whole
 * string is the title (station idents, news segments). Null when blank. Pure.
 */
internal fun parseIcyStreamTitle(raw: String?): Pair<String?, String?>? {
    if (raw == null) return null
    // Split BEFORE trimming the whole string, then trim the halves — otherwise a
    // degenerate " - " collapses to "-" and reads as a junk one-dash title.
    val idx = raw.indexOf(" - ")
    if (idx < 0) {
        val title = raw.trim().takeIf { it.isNotEmpty() } ?: return null
        return Pair(null, title)
    }
    val artist = raw.substring(0, idx).trim().takeIf { it.isNotEmpty() }
    val title = raw.substring(idx + 3).trim().takeIf { it.isNotEmpty() }
    return if (artist == null && title == null) null else Pair(artist, title)
}

/**
 * The (title, artist) identity tuple used for change detection — a metadata push
 * happens only when this tuple changes (album/art alone never trigger one).
 * Pure function, unit-tested on the plain JVM.
 */
internal fun nowPlayingTuple(np: NowPlaying): Pair<String?, String?> = np.title to np.artist

/**
 * Case/whitespace-insensitive identity equality between two (title, artist)
 * tuples, via [icyKey] — the SAME tolerance the snapshot matcher applies. The
 * poll's art-upgrade gate needs this rather than `==`: after an ICY snapshot
 * miss, [LiveMetadata.lastTuple] holds the ICY-parsed casing while the poll
 * carries the API's casing; exact equality would block the art recovery for the
 * whole track over pure formatting drift. Different TRACKS still never match —
 * that protection (don't let a live-edge snapshot repaint a lagged playback
 * position) is what the gate is for. Pure, JVM-tested.
 */
internal fun sameIdentity(a: Pair<String?, String?>?, b: Pair<String?, String?>?): Boolean =
    a != null && b != null && icyKey(a.first, a.second) == icyKey(b.first, b.second)

/**
 * Whether a push that WANTED inline art should latch [LiveMetadata.lastAppliedArtUrl]
 * afterwards. Latching means "done with this URL — stop re-applying it every poll
 * tick"; not latching leaves the URL unapplied so the next tick retries the fetch.
 * Latch on success, on no-URL, on a DEFINITIVE failure (oversized cover — retrying
 * re-downloads megabytes for the same answer), or once the per-URL retry budget
 * [MAX_ART_FETCH_ATTEMPTS] is spent (AA already holds the artworkUri from the
 * first push, so a working head unit can still render the cover). Pure, JVM-tested.
 */
internal fun shouldLatchArt(
    wantedUrl: String?,
    gotBytes: Boolean,
    definitive: Boolean,
    failuresSoFar: Int,
): Boolean =
    wantedUrl == null || gotBytes || definitive || failuresSoFar >= MAX_ART_FETCH_ATTEMPTS

/** What a metadata push should (re)write onto the live item. See [planMetaPush]. */
internal data class MetaPlan(val setIdentity: Boolean, val setArt: Boolean) {
    val isNoop: Boolean get() = !setIdentity && !setArt
}

/**
 * Pure decision for what a push should touch — separating IDENTITY (title/artist,
 * the change-detection tuple) from ART/ALBUM (the enrichment). Split so the two
 * evolve independently:
 *  - identity rewrites only when the tuple changes (the ICY/poll trigger);
 *  - art rewrites whenever we have AUTHORITATIVE art data (`artKnown`) whose URL
 *    differs from what's currently applied — which covers three cases the old
 *    "one tuple, art bundled in" logic couldn't: (a) re-applying art for the SAME
 *    track after a transient fetch miss or a late snapshot (poll enrichment while
 *    ICY owns identity), (b) clearing art when a track genuinely has none
 *    (newArtUrl null while something is applied), and (c) NOT clearing art on an
 *    ICY snapshot-miss (`artKnown=false` → art untouched, carried forward).
 * Unit-tested on the plain JVM.
 */
internal fun planMetaPush(
    newTuple: Pair<String?, String?>,
    lastTuple: Pair<String?, String?>?,
    artKnown: Boolean,
    newArtUrl: String?,
    lastAppliedArtUrl: String?,
): MetaPlan = MetaPlan(
    setIdentity = newTuple != lastTuple,
    setArt = artKnown && newArtUrl != lastAppliedArtUrl,
)

/**
 * The artist line as shown on car screens: `"<artist> • <station>"`. The artist
 * line is the one spot EVERY Android Auto head unit (and BT/AVRCP display)
 * renders, so the station branding rides there; the semantic
 * [MediaMetadata.station] field is set too, for units that show it separately.
 * Pure function, unit-tested on the plain JVM.
 */
internal fun displayArtist(artist: String?, station: String?): String? = when {
    artist != null && station != null -> "$artist • $station"
    artist != null -> artist
    station != null -> station
    else -> null
}

/**
 * One cached cover, ready to publish either way: normalized inline bytes for
 * `artworkData`, plus the [ArtworkStore] content URI (as a String — JVM-test
 * friendly) for `artworkUri`. `contentUri` is null when the store write failed;
 * the push then goes bytes-only rather than falling back to the remote URL.
 */
internal class CachedArt(val bytes: ByteArray, val contentUri: String?)

/**
 * Tiny LRU cache keyed by artwork URL. LinkedHashMap in access order + an
 * eldest-entry cap — no external deps. Synchronized because puts happen from IO
 * pollers while (hypothetically) reads could race; cheap at 8 entries.
 */
internal class ArtCache(private val maxEntries: Int) {
    private val map = object : LinkedHashMap<String, CachedArt>(maxEntries + 1, 1f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedArt>): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(url: String): CachedArt? = map[url]

    @Synchronized
    fun put(url: String, art: CachedArt) {
        map[url] = art
    }

    @Synchronized
    fun size(): Int = map.size

    @Synchronized
    fun keys(): Set<String> = map.keys.toSet()
}

/**
 * Tiny LRU of recent `/api/now-playing` snapshots, keyed by [icyKey]. The 5s poll
 * keeps it warm with the LIVE-EDGE track; when an ICY title event fires at the
 * (possibly lagged) PLAYBACK position, the matching snapshot supplies the album +
 * artwork the bare `StreamTitle` string can't carry. 8 entries ≈ half an hour of
 * airtime — comfortably deeper than any realistic client lag. Synchronized for the
 * same poller/reader races as [ArtCache].
 */
internal class SnapshotCache(private val maxEntries: Int) {
    private val map = object : LinkedHashMap<String, NowPlaying>(maxEntries + 1, 1f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NowPlaying>): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun put(np: NowPlaying) {
        map[icyKey(np.title, np.artist)] = np
    }

    /** Exact artist|title match, falling back to title-only (ICY artist formatting drift). */
    @Synchronized
    fun find(title: String?, artist: String?): NowPlaying? {
        map[icyKey(title, artist)]?.let { return it }
        val t = title?.trim()?.lowercase() ?: return null
        if (t.isEmpty()) return null
        return map.values.lastOrNull { it.title?.trim()?.lowercase() == t }
    }

    /** Most-recently-touched snapshot — used only as a stationName fallback. */
    @Synchronized
    fun latest(): NowPlaying? = map.values.lastOrNull()

    @Synchronized
    fun size(): Int = map.size
}

/**
 * Live now-playing → [MediaMetadata] sync (ANDROID_AUTO_PLAN.md §2 WP3).
 *
 * While the player is playing, polls `{base}/api/now-playing` every [POLL_INTERVAL_MS]
 * and, when the (title, artist) tuple changes, pushes fresh metadata (title, artist,
 * album, cover art) onto the live media item. AA, Bluetooth/AVRCP, and the media
 * notification all render from the session's metadata, so one push updates them all.
 *
 * Threading: polling + artwork fetch run on IO (inside [StationApi]); the player is
 * ONLY touched via `withContext(Dispatchers.Main.immediate)` — media3's threading
 * contract (all [Player] access on the application main thread).
 *
 * Base-URL change: [api] is a mutable `var` (deliberate, minimal deviation from §1's
 * `private val`, mirroring [BrowseTree.api]) — PlaybackService's StationPrefs listener
 * assigns the rebuilt StationApi; the next poll targets the new station. Only ever
 * mutated from the main thread.
 *
 * @param player the service's live-edge ForwardingPlayer (never the raw ExoPlayer).
 * @param scope  service-owned main-thread scope; cancelled in the service's onDestroy,
 *               which also kills any in-flight poll.
 */
internal class LiveMetadata(
    private val player: Player,
    var api: StationApi,
    private val scope: CoroutineScope,
    /**
     * v0.5: disk store behind [com.powerpoppalace.subwaveauto.art.ArtworkProvider] —
     * the content:// URI Android Auto's artwork contract requires. Nullable only
     * for plain-JVM tests of this class's pure helpers.
     */
    private val artStore: ArtworkStore? = null,
    /**
     * v0.5: active artwork publish mode, read fresh at every push so the hidden
     * diagnostics switch takes effect at the next track change without a restart.
     */
    private val artMode: () -> ArtMode = { ArtMode.CONTENT_PLUS_DATA },
    /**
     * v0.6: called with every `content://` artwork URI JUST BEFORE it is
     * published onto the session, so the service can grant read access to the
     * controller processes (gearhead & co.) that will resolve it. Some
     * gearhead/OEM builds deny a plain exported-provider read of a metadata
     * URI and then render artless without falling back to the inline bitmap —
     * the explicit grant closes that route. No-op default for plain-JVM tests.
     */
    private val grantArtUri: (Uri) -> Unit = {},
) {

    /** Active poll loop, non-null only while playing. */
    private var pollJob: Job? = null

    /**
     * Last (title, artist) tuple applied to the player. Reset to null whenever
     * playback stops so a restart re-pushes metadata even if the song is unchanged.
     */
    private var lastTuple: Pair<String?, String?>? = null

    /**
     * Art URL last applied to the live item (the `artworkUri` set + the URL the
     * `artworkData` bytes came from), or null when art is cleared/unknown. Tracks
     * art independently of the identity tuple so the poll can re-apply a cover for
     * the CURRENT track after a transient fetch miss or a late-arriving snapshot,
     * without re-pushing identity. Reset on stop alongside [lastTuple].
     */
    private var lastAppliedArtUrl: String? = null

    /**
     * Transient-failure tally for the ONE cover URL currently being retried (see
     * [shouldLatchArt]). Reset when the URL changes or a fetch succeeds. Main-
     * thread confined like the rest of the push state.
     */
    private var artFailUrl: String? = null
    private var artFailures = 0

    private val artCache = ArtCache(MAX_ART_CACHE_ENTRIES)

    /** Live-edge snapshots for ICY matching — see [SnapshotCache]. */
    private val snapshots = SnapshotCache(MAX_SNAPSHOT_CACHE_ENTRIES)

    /**
     * True once THIS connection has delivered an ICY title event. From then on ICY
     * owns metadata pushes (it fires at the PLAYBACK position, so it stays correct
     * however far the buffer lags the live edge) and the poll only keeps the
     * snapshot cache warm. Reset on stop: Icecast replays the current StreamTitle
     * on (re)connect, so a fresh session re-proves ICY before the poll stands
     * down — a station/proxy that strips ICY degrades to exactly today's polling.
     */
    private var icySeen = false

    /** Last raw StreamTitle handled — ExoPlayer may surface repeats; push once. */
    private var lastIcyRaw: String? = null

    /** Poll ONLY while playing — no network spin while paused/stopped. */
    private val playListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startPolling() else stopPolling()
        }
    }

    /** Call from the service's onCreate (main thread). */
    fun start() {
        player.addListener(playListener)
        if (player.isPlaying) startPolling()
    }

    /** Call from the service's onDestroy (main thread). Safe to call repeatedly. */
    fun stop() {
        player.removeListener(playListener)
        stopPolling()
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                pollOnce()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        // Playback stopped → forget the last tuple + applied art so a restart
        // re-pushes metadata, and drop the ICY session state so the next connection
        // re-proves ICY (see icySeen) instead of trusting a dead signal.
        lastTuple = null
        lastAppliedArtUrl = null
        artFailUrl = null
        artFailures = 0
        icySeen = false
        lastIcyRaw = null
    }

    /**
     * ICY `StreamTitle` event from the player (PlaybackService's onMetadata
     * listener, main thread). ExoPlayer emits it at the PRESENTATION time of the
     * stream bytes it rode in on — i.e. when the listener actually HEARS the
     * transition — so unlike the live-edge poll it is immune to buffer lag
     * (accumulated short pauses, rebuffers, resume drift). The cached live-edge
     * snapshot supplies album + artwork; a cache miss (app just launched mid-track
     * with an unusual ICY format) pushes the parsed title/artist bare rather than
     * showing the wrong song.
     */
    fun onIcyStreamTitle(raw: String?) {
        val parsed = parseIcyStreamTitle(raw) ?: return
        if (raw == lastIcyRaw) return
        lastIcyRaw = raw
        icySeen = true
        val (artist, title) = parsed
        val snap = snapshots.find(title, artist)
        // artKnown=true only when a snapshot supplied album+art. On a MISS we push
        // the parsed title/artist but leave art UNTOUCHED (artKnown=false) — never
        // blank a probably-correct cover for a track we couldn't enrich. The poll,
        // which keeps warming the snapshot cache, upgrades the art once the matching
        // snapshot arrives (see pollOnce).
        val np = snap ?: NowPlaying(
            title = title, artist = artist, album = null, artUrl = null,
            streamOnline = true, stationName = snapshots.latest()?.stationName,
        )
        // Same main-thread scope as the poller; planMetaPush's dedupe keeps the
        // brief poll/ICY handover window (before the FIRST event flips icySeen)
        // from double-pushing.
        scope.launch { pushMeta(np, artKnown = snap != null) }
    }

    /**
     * One poll tick. Never throws (short of cancellation): a down station, malformed
     * payload, or failed artwork fetch keeps the LAST metadata and tries again next
     * tick. Always warms the snapshot cache with the live-edge track; only DRIVES
     * the metadata push while no ICY signal has been seen on this connection —
     * once ICY proves itself, pushes follow the playback position instead.
     */
    private suspend fun pollOnce() {
        val np = try {
            api.nowPlaying() // internally on Dispatchers.IO; null on any error
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        } ?: return // station briefly down → keep last metadata, never crash the loop

        snapshots.put(np)
        if (!icySeen) {
            // No ICY on this connection → the poll drives everything (identity + art).
            pushMeta(np, artKnown = true)
            return
        }
        // ICY owns IDENTITY (it's playback-position accurate). The poll still keeps
        // the snapshot cache warm AND upgrades ART for whatever is CURRENTLY showing:
        // if the live-edge snapshot matches the displayed track and offers a cover we
        // haven't applied yet, apply it. This recovers art after an ICY snapshot-miss
        // (the cover the bare StreamTitle couldn't carry) and after a transient
        // fetch failure — without ever moving identity off the ICY timing. The match
        // is icyKey-tolerant (sameIdentity), NOT `==`: after a snapshot miss lastTuple
        // carries the ICY string's casing/whitespace, and exact equality against the
        // API's casing would lock the whole track out of art recovery.
        if (sameIdentity(nowPlayingTuple(np), lastTuple) && np.artUrl != null && np.artUrl != lastAppliedArtUrl) {
            pushMeta(np, artKnown = true)
        }
    }

    /**
     * Apply one now-playing snapshot to the live media item. Identity (title/artist)
     * and art/album are gated independently by [planMetaPush]: identity rewrites on
     * a tuple change, art rewrites when authoritative (`artKnown`) and the cover URL
     * differs from what's applied. Shared by the poll and the ICY path.
     *
     * @param artKnown whether `np` carries authoritative album/art. False on an ICY
     *   snapshot-miss — art is then left untouched (carried forward), never blanked.
     */
    private suspend fun pushMeta(np: NowPlaying, artKnown: Boolean): Unit =
        // Pin the ENTIRE body to the main thread: the class scope is already
        // Dispatchers.Main, but confining it explicitly makes every read/write of
        // lastTuple / lastAppliedArtUrl single-threaded and race-free regardless of
        // caller. fetchArtCached still offloads its network work to IO internally —
        // that inner suspension is the one interleave point, handled below.
        withContext(Dispatchers.Main.immediate) {
            val tuple = nowPlayingTuple(np)
            val plannedLast = lastTuple
            val plan = planMetaPush(tuple, plannedLast, artKnown, np.artUrl, lastAppliedArtUrl)
            if (plan.isNoop) return@withContext

            // Artwork (fetchArtCached hops to IO and back), ONLY when we're
            // (re)writing art. A null result — fetch failed, >1MB, 404 — publishes
            // artless this push; the bounded retry below re-fetches on later ticks.
            // This is the sole suspension point.
            val fetch = if (plan.setArt && np.artUrl != null) fetchArtCached(np.artUrl) else null
            val art = fetch?.art?.bytes

            // Supersession guard: the art fetch suspended, so a NEWER identity (an
            // ICY track change applied by another push) may have landed meanwhile.
            // If identity has moved to a DIFFERENT track than this push is for, we're
            // stale — bail rather than resurrect an old track or paint its cover onto
            // the new one. `lastTuple == tuple` means our own identity is already
            // current (a same-track art refresh), so proceed. When we didn't fetch
            // (no suspension) lastTuple is unchanged, so this never false-bails.
            if (lastTuple != plannedLast && lastTuple != tuple) return@withContext
            if (player.mediaItemCount == 0) return@withContext

            val current = player.getMediaItemAt(0)
            // buildUpon() copies the current metadata, so any field we DON'T touch is
            // carried forward untouched — this is what preserves art on an ICY miss.
            val meta = current.mediaMetadata.buildUpon() // keep MEDIA_TYPE_RADIO_STATION / isPlayable
            if (plan.setIdentity) {
                meta.setTitle(np.title ?: np.stationName ?: "SUB/WAVE")
                    // Station branding rides the artist line — the one field every AA
                    // unit and BT display renders (see displayArtist).
                    .setArtist(displayArtist(np.artist, np.stationName) ?: "Live broadcast")
                    .setStation(np.stationName)
            }
            if (plan.setArt) {
                // v0.5: artworkUri is now the LOCAL content:// URI served by
                // ArtworkProvider — Android Auto's documented artwork contract.
                // (v0.4 set the remote HTTPS URL here; older gearhead builds
                // permissively fetched it, newer ones — Pixels first — prefer the
                // URI route and reject the scheme, rendering artless.) artworkData
                // stays as the immediate/offline bitmap for the notification +
                // BT/AVRCP. planArtFields maps the active (normally production)
                // mode to the exact field combination; a track with no cover
                // clears BOTH so it doesn't keep the last one.
                val mode = artMode()
                val fields = planArtFields(mode, fetch?.art?.contentUri, np.artUrl, art != null)
                val artworkUri =
                    if (np.artUrl == null) null
                    else fields.uri?.let { runCatching { Uri.parse(it) }.getOrNull() }
                // v0.6: grant controller processes read access BEFORE the URI is
                // visible in session metadata, so gearhead's first load attempt
                // can never race the grant.
                if (artworkUri != null && artworkUri.scheme == "content") grantArtUri(artworkUri)
                val artworkData = if (fields.includeData) art else null
                meta.setAlbumTitle(np.album)
                    .setArtworkData(artworkData, if (artworkData != null) MediaMetadata.PICTURE_TYPE_FRONT_COVER else null)
                    .setArtworkUri(artworkUri)
                ArtDiagnostics.recordPush(mode, artworkUri?.toString(), artworkData?.size)
            }
            // Same URI, new metadata → media3 keeps playback uninterrupted (the URI
            // is unchanged, so replaceMediaItem is a metadata-only update).
            // FALLBACK (plan §4 risk table): if device QA (WP6 matrix #4) hears an
            // audio glitch on this path, switch to the ForwardingPlayer-metadata
            // pattern instead — override getMediaMetadata() on the service's
            // LiveEdgePlayer and emit onMediaMetadataChanged — and document which shipped.
            player.replaceMediaItem(
                0,
                current.buildUpon().setMediaMetadata(meta.build()).build(),
            )

            // Commit state on Main (past the guard, so never for a superseded push).
            if (plan.setIdentity) lastTuple = tuple
            if (plan.setArt) {
                // Latch means "done with this URL — stop re-applying it every tick".
                // Success, a cleared cover (null URL), a definitive can't-inline
                // (oversized), or a spent retry budget latch immediately; a TRANSIENT
                // fetch failure does NOT — later poll ticks retry it (bounded, see
                // shouldLatchArt). The old one-shot latch left a track artless for
                // its whole runtime when the push raced a flaky connection (the
                // parking-garage connect) and media3's own artworkUri load failed on
                // the same dead network moment.
                if (np.artUrl != artFailUrl) {
                    artFailUrl = np.artUrl
                    artFailures = 0
                }
                if (fetch != null && fetch.art == null && !fetch.definitive) artFailures++
                if (shouldLatchArt(np.artUrl, art != null, fetch?.definitive == true, artFailures)) {
                    lastAppliedArtUrl = np.artUrl
                    artFailUrl = null
                    artFailures = 0
                }
            }
        }

    /**
     * Art fetch outcome. Null [art] with [definitive] = true means the cover can
     * never be used (oversized AND undecodable) — a retry would re-download
     * megabytes for the same answer. Null art with definitive = false is transient
     * (network error, non-image body) and worth a bounded retry — see
     * [shouldLatchArt].
     */
    private class ArtFetchResult(val art: CachedArt?, val definitive: Boolean)

    /**
     * One publishable cover for [url]: LRU-cached; fetched via [StationApi.fetchArt]
     * (2 MB download cap + image content-type guard), then NORMALIZED (≤320 px
     * baseline JPEG — one small asset feeds both artworkData and the provider
     * file) and persisted to [artStore] so the content:// URI resolves before the
     * push publishes it. Normalization failure falls back to the original bytes;
     * a failed store write yields a bytes-only [CachedArt] (never the remote URL).
     */
    private suspend fun fetchArtCached(url: String): ArtFetchResult {
        artCache.get(url)?.let { return ArtFetchResult(it, definitive = true) }
        val fetched = api.fetchArt(url)
        if (fetched == null) {
            ArtDiagnostics.recordFetch(url, ok = false, sizeBytes = 0, mime = null)
            return ArtFetchResult(null, definitive = false)
        }
        ArtDiagnostics.recordFetch(url, ok = true, sizeBytes = fetched.bytes.size, mime = fetched.mimeType)
        // Normalize on IO — BitmapFactory work has no business on Main.
        val normalized = withContext(Dispatchers.IO) { ArtNormalizer.normalize(fetched.bytes) }
        ArtDiagnostics.recordNormalize(fetched.bytes.size, normalized)
        val publishBytes = normalized?.bytes ?: fetched.bytes
        val publishMime = if (normalized != null) "image/jpeg" else fetched.mimeType
        // Undecodable AND too big to inline → nothing usable, ever. (Normalized
        // covers are ~10-40 KB, so this only fires on genuinely broken responses.)
        if (publishBytes.size > MAX_METADATA_ART_BYTES) return ArtFetchResult(null, definitive = true)
        val entry = withContext(Dispatchers.IO) { artStore?.put(publishBytes, publishMime) }
        val contentUri = entry?.let { artStore?.uriFor(it)?.toString() }
        ArtDiagnostics.recordStore(entry?.hash, contentUri)
        val cached = CachedArt(publishBytes, contentUri)
        artCache.put(url, cached)
        return ArtFetchResult(cached, definitive = true)
    }
}
