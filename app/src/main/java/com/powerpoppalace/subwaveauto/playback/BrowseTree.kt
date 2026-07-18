package com.powerpoppalace.subwaveauto.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.powerpoppalace.subwaveauto.net.StationApi

// §1 contract values (ANDROID_AUTO_PLAN.md) — BrowseTree owns them; PlaybackService
// (same package) and WP4's UI reference them from here.
const val ROOT_ID = "subwave_root"
const val LIVE_ITEM_ID = "subwave_live"

/** v0.8: mediaId prefix for the synthetic "send this search as a request" item. */
internal const val REQUEST_ITEM_PREFIX = "subwave_request:"

/** v0.10: custom session command behind the one-tap AA "More like this" button. */
internal const val ACTION_MORE_LIKE_THIS = "com.powerpoppalace.subwaveauto.MORE_LIKE_THIS"

/** The canned request the button submits — upstream SUB/WAVE has a dedicated
 *  fast path for exactly this phrase (no LLM round-trip). */
internal const val MORE_LIKE_THIS_TEXT = "more like this"

/**
 * The fully-formed live [MediaItem], per the §1 invariants: mediaId [LIVE_ITEM_ID],
 * a FRESH cache-busted stream URI on every call (never reuse an old `?t=`), explicit
 * MP3 mime type (skip content sniffing), radio-station metadata.
 *
 * Pure string/builder work — no I/O. Shared by [BrowseTree]'s callbacks and by
 * PlaybackService's live-edge reload path (its `freshLiveItem()` delegates here).
 */
internal fun liveMediaItem(api: StationApi): MediaItem =
    MediaItem.Builder()
        .setMediaId(LIVE_ITEM_ID)
        .setUri(api.streamUrl())
        .setMimeType(MimeTypes.AUDIO_MPEG)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("SUB/WAVE")
                .setArtist("Live broadcast")
                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build(),
        )
        .build()

/**
 * Android Auto browse tree (ANDROID_AUTO_PLAN.md §2 WP3): a browsable root
 * ([ROOT_ID], "SUB/WAVE") containing exactly one playable item ([LIVE_ITEM_ID],
 * the live stream).
 *
 * All callbacks are fast and non-blocking — pure builder work, no network. Browse
 * artwork is deliberately skipped (AA shows the app icon by default); live cover
 * art arrives later via [LiveMetadata] once playback starts.
 *
 * Base-URL change: [api] is a mutable `var` (deliberate, minimal deviation from
 * §1's `private val`) — the session callback is fixed at session-build time, so the
 * service can't swap in a new BrowseTree on a URL change without rebuilding the
 * whole session. Instead PlaybackService's StationPrefs listener assigns the
 * rebuilt StationApi here (one line), and every subsequent callback resolves
 * against the new station. Only ever mutated from the main thread (the prefs
 * listener), same thread the session callbacks arrive on.
 */
@OptIn(UnstableApi::class)
class BrowseTree(var api: StationApi) : MediaLibrarySession.Callback {

    /**
     * v0.8 song requests from the car: invoked with the listener's free-text
     * query when a voice search ("Hey Google, play X on SUB/WAVE Auto") or an
     * AA search-result tap reaches the session. PlaybackService wires this to
     * `POST /api/request` — the live stream keeps playing and the DJ answers
     * ON AIR, so the driver never touches the screen. Assigned once at service
     * startup (main thread, same as [api]).
     */
    var onSongRequest: ((String) -> Unit)? = null

    /**
     * Extract the request text a controller attached to a play command, or null:
     * either a voice search riding [MediaItem.requestMetadata]'s searchQuery, or
     * the [REQUEST_ITEM_PREFIX] mediaId minted by [onGetSearchResult]. Pure.
     */
    internal fun requestTextFor(item: MediaItem): String? {
        item.requestMetadata.searchQuery?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (item.mediaId.startsWith(REQUEST_ITEM_PREFIX)) {
            return item.mediaId.removePrefix(REQUEST_ITEM_PREFIX).trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    /**
     * v0.10: the one-tap "More like this" button shown on the Android Auto
     * now-playing screen (custom media command). One press submits
     * [MORE_LIKE_THIS_TEXT] as a listener request against the current track —
     * the only kind of one-tap request the AA platform allows (free-text needs
     * voice/search, which the system assistant owns).
     */
    internal fun moreLikeThisButton(): CommandButton =
        CommandButton.Builder(CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName("More like this")
            .setSessionCommand(SessionCommand(ACTION_MORE_LIKE_THIS, Bundle.EMPTY))
            .build()

    /** Pure seam for [onCustomCommand]: true when [action] was ours and fired. */
    internal fun handleCustomAction(action: String): Boolean {
        if (action == ACTION_MORE_LIKE_THIS) {
            onSongRequest?.invoke(MORE_LIKE_THIS_TEXT)
            return true
        }
        return false
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            .buildUpon()
            .add(SessionCommand(ACTION_MORE_LIKE_THIS, Bundle.EMPTY))
            .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(commands)
            .setCustomLayout(ImmutableList.of(moreLikeThisButton()))
            .build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> =
        if (handleCustomAction(customCommand.customAction)) {
            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        } else {
            super.onCustomCommand(session, controller, customCommand, args)
        }

    /** The single search "result": a playable card that submits the query as a request. */
    internal fun requestItemFor(query: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(REQUEST_ITEM_PREFIX + query)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Request: \"$query\"")
                    .setSubtitle("Send to the DJ — keeps playing live")
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build(),
            )
            .build()

    /** Browsable root — not playable, contains the single live item. */
    internal fun rootItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("SUB/WAVE")
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build(),
            )
            .build()

    /** The live item as shown in the browse list — [liveMediaItem] plus the "Live radio" subtitle. */
    internal fun browseLiveItem(): MediaItem {
        val item = liveMediaItem(api)
        return item.buildUpon()
            .setMediaMetadata(
                item.mediaMetadata.buildUpon()
                    .setSubtitle("Live radio")
                    .build(),
            )
            .build()
    }

    /**
     * The resolution seam behind [onAddMediaItems] (kept `internal` so plain unit
     * tests exercise it without constructing a MediaSession/ControllerInfo):
     * controllers — AA always — send bare mediaIds with NO URI, so EVERY requested
     * item maps to the fully-formed live item with a fresh cache-busted stream URI.
     *
     * v0.8: before mapping, any attached request text (voice searchQuery or a
     * search-result [REQUEST_ITEM_PREFIX] id) is forwarded to [onSongRequest] —
     * the "play X on SUB/WAVE Auto" verbal request path. Playback of the live
     * stream continues either way; the request rides along.
     */
    internal fun resolveMediaItems(requested: List<MediaItem>): MutableList<MediaItem> {
        requested.firstNotNullOfOrNull { requestTextFor(it) }?.let { text ->
            onSongRequest?.invoke(text)
        }
        return requested.map { liveMediaItem(api) }.toMutableList()
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        when (parentId) {
            ROOT_ID -> Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.of(browseLiveItem()), params),
            )
            // The live item is a leaf — an (unexpected) children request yields an empty list.
            LIVE_ITEM_ID -> Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.of(), params),
            )
            else -> Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
        }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        when (mediaId) {
            LIVE_ITEM_ID -> Futures.immediateFuture(LibraryResult.ofItem(browseLiveItem(), null))
            ROOT_ID -> Futures.immediateFuture(LibraryResult.ofItem(rootItem(), null))
            else -> Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
        }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> =
        Futures.immediateFuture(resolveMediaItems(mediaItems))

    /**
     * v0.8: AA's search box. The station is one live stream — there is nothing
     * to search — so the single "result" is a card that sends the query to the
     * DJ as a song request when tapped ([REQUEST_ITEM_PREFIX] → resolved by
     * [resolveMediaItems], which fires the request and keeps the live stream).
     */
    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        val count = if (query.isBlank()) 0 else 1
        session.notifySearchResultChanged(browser, query, count, params)
        return Futures.immediateFuture(LibraryResult.ofVoid(params))
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val items = if (query.isBlank() || page > 0) {
            ImmutableList.of<MediaItem>()
        } else {
            ImmutableList.of(requestItemFor(query.trim()))
        }
        return Futures.immediateFuture(LibraryResult.ofItemList(items, params))
    }
}
