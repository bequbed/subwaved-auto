package com.powerpoppalace.subwaveauto.playback

import com.powerpoppalace.subwaveauto.net.NowPlaying
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM tests for LiveMetadata's pure logic: the (title, artist) change-detection
 * tuple and the LRU artwork byte cache. No Android/media3 types involved.
 */
class LiveMetadataLogicTest {

    private fun np(
        title: String? = "Starry Eyes",
        artist: String? = "The Records",
        album: String? = "Shades in Bed",
        artUrl: String? = "https://cdn.example.com/covers/abc.jpg",
    ) = NowPlaying(title = title, artist = artist, album = album, artUrl = artUrl, streamOnline = true)

    // --- nowPlayingTuple: change detection ---

    @Test
    fun sameTitleAndArtist_tupleUnchanged() {
        assertEquals(nowPlayingTuple(np()), nowPlayingTuple(np()))
    }

    @Test
    fun albumOrArtChangeAlone_tupleUnchanged() {
        // Album/art alone never trigger a metadata push — only title+artist identity.
        assertEquals(
            nowPlayingTuple(np(album = "Shades in Bed", artUrl = "https://a/1.jpg")),
            nowPlayingTuple(np(album = "Crashes", artUrl = "https://a/2.jpg")),
        )
    }

    @Test
    fun titleChange_tupleChanges() {
        assertNotEquals(nowPlayingTuple(np(title = "Teenarama")), nowPlayingTuple(np()))
    }

    @Test
    fun artistChange_tupleChanges() {
        assertNotEquals(nowPlayingTuple(np(artist = "The Rubinoos")), nowPlayingTuple(np()))
    }

    @Test
    fun nullFields_stillComparable() {
        val sparse = np(title = null, artist = null)
        assertEquals(nowPlayingTuple(sparse), nowPlayingTuple(sparse.copy()))
        assertNotEquals(nowPlayingTuple(sparse), nowPlayingTuple(np()))
    }

    // --- ArtCache: LRU, max 8 entries ---

    private fun art(vararg bytes: Byte): CachedArt = CachedArt(bytes, contentUri = null)

    @Test
    fun artCache_missReturnsNull_hitReturnsBytes() {
        val cache = ArtCache(MAX_ART_CACHE_ENTRIES)
        assertNull(cache.get("https://a/missing.jpg"))
        val cached = CachedArt(byteArrayOf(1, 2, 3), "content://x.art/v1/abc")
        cache.put("https://a/1.jpg", cached)
        assertArrayEquals(byteArrayOf(1, 2, 3), cache.get("https://a/1.jpg")?.bytes)
        assertEquals("content://x.art/v1/abc", cache.get("https://a/1.jpg")?.contentUri)
    }

    @Test
    fun artCache_evictsEldestBeyondEightEntries() {
        val cache = ArtCache(MAX_ART_CACHE_ENTRIES)
        for (i in 1..9) cache.put("https://a/$i.jpg", art(i.toByte()))
        assertEquals(8, cache.size())
        assertNull(cache.get("https://a/1.jpg")) // eldest evicted
        assertArrayEquals(byteArrayOf(9), cache.get("https://a/9.jpg")?.bytes)
    }

    @Test
    fun artCache_accessOrder_recentlyReadEntrySurvivesEviction() {
        val cache = ArtCache(MAX_ART_CACHE_ENTRIES)
        for (i in 1..8) cache.put("https://a/$i.jpg", art(i.toByte()))
        cache.get("https://a/1.jpg") // touch the eldest → now most-recently-used
        cache.put("https://a/9.jpg", art(9))
        assertArrayEquals(byteArrayOf(1), cache.get("https://a/1.jpg")?.bytes) // survived
        assertNull(cache.get("https://a/2.jpg")) // the true LRU entry was evicted
        assertEquals(8, cache.size())
    }

    // --- caps sanity (§1 contract values) ---

    @Test
    fun metadataArtCap_isOneMegabyte_belowStationApiDownloadCap() {
        assertEquals(1024 * 1024, MAX_METADATA_ART_BYTES)
        assertTrue(MAX_METADATA_ART_BYTES < com.powerpoppalace.subwaveauto.net.StationApi.MAX_ART_BYTES)
    }

    // --- parseIcyStreamTitle: ICY StreamTitle → (artist, title) ---

    @Test
    fun icyParse_artistDashTitle_splitsOnFirstSeparator() {
        assertEquals(Pair("The Records", "Starry Eyes"), parseIcyStreamTitle("The Records - Starry Eyes"))
    }

    @Test
    fun icyParse_titleContainingSeparator_keepsItInTheTitle() {
        // Split on the FIRST " - ": everything after belongs to the title.
        assertEquals(
            Pair("Fountains of Wayne", "Stacy's Mom - Single Version"),
            parseIcyStreamTitle("Fountains of Wayne - Stacy's Mom - Single Version"),
        )
    }

    @Test
    fun icyParse_noSeparator_wholeStringIsTitle() {
        assertEquals(Pair(null, "News Flash"), parseIcyStreamTitle("News Flash"))
    }

    @Test
    fun icyParse_blankAndNull_returnNull() {
        assertNull(parseIcyStreamTitle(null))
        assertNull(parseIcyStreamTitle(""))
        assertNull(parseIcyStreamTitle("   "))
        assertNull(parseIcyStreamTitle(" - ")) // separator with empty halves
    }

    @Test
    fun icyParse_trimsWhitespaceAroundBothHalves() {
        assertEquals(Pair("Nick Lowe", "Cruel to Be Kind"), parseIcyStreamTitle("  Nick Lowe -   Cruel to Be Kind  "))
    }

    // --- icyKey + SnapshotCache: ICY → cached snapshot matching ---

    @Test
    fun icyKey_caseAndWhitespaceInsensitive() {
        assertEquals(icyKey("Starry Eyes", "The Records"), icyKey("  starry eyes ", "THE RECORDS"))
        assertNotEquals(icyKey("Starry Eyes", "The Records"), icyKey("Teenarama", "The Records"))
    }

    @Test
    fun snapshotCache_exactTupleMatch_returnsSnapshotWithArt() {
        val cache = SnapshotCache(MAX_SNAPSHOT_CACHE_ENTRIES)
        cache.put(np())
        val hit = cache.find("starry eyes", "the records") // ICY casing differs
        assertEquals("https://cdn.example.com/covers/abc.jpg", hit?.artUrl)
        assertEquals("Shades in Bed", hit?.album)
    }

    @Test
    fun snapshotCache_titleOnlyFallback_whenIcyArtistFormattingDrifts() {
        val cache = SnapshotCache(MAX_SNAPSHOT_CACHE_ENTRIES)
        cache.put(np())
        // ICY said "Records, The" — exact tuple misses, title-only fallback hits.
        assertEquals("Shades in Bed", cache.find("Starry Eyes", "Records, The")?.album)
        assertNull(cache.find("Some Other Song", "Records, The"))
    }

    @Test
    fun snapshotCache_evictsEldestBeyondCap() {
        val cache = SnapshotCache(MAX_SNAPSHOT_CACHE_ENTRIES)
        for (i in 1..9) cache.put(np(title = "Song $i", artist = "Artist $i"))
        assertEquals(8, cache.size())
        assertNull(cache.find("Song 1", "Artist 1")) // eldest evicted
        assertEquals("Shades in Bed", cache.find("Song 9", "Artist 9")?.album)
    }

    @Test
    fun snapshotCache_latest_isMostRecentlyTouched() {
        val cache = SnapshotCache(MAX_SNAPSHOT_CACHE_ENTRIES)
        cache.put(np(title = "First"))
        cache.put(np(title = "Second"))
        assertEquals("Second", cache.latest()?.title)
    }

    // --- planMetaPush: identity vs art gating (the AA-artwork fix) ---

    private val T1 = "Starry Eyes" to "The Records"
    private val T2 = "Teenarama" to "The Records"

    @Test
    fun plan_identityChange_setsIdentity_andArtWhenKnown() {
        val p = planMetaPush(T2, T1, artKnown = true, newArtUrl = "a/2.jpg", lastAppliedArtUrl = "a/1.jpg")
        assertTrue(p.setIdentity)
        assertTrue(p.setArt)
        assertTrue(!p.isNoop)
    }

    @Test
    fun plan_sameTuple_sameArt_isNoop() {
        val p = planMetaPush(T1, T1, artKnown = true, newArtUrl = "a/1.jpg", lastAppliedArtUrl = "a/1.jpg")
        assertTrue(!p.setIdentity)
        assertTrue(!p.setArt)
        assertTrue(p.isNoop)
    }

    @Test
    fun plan_icyMiss_neverTouchesArt_evenOnIdentityChange() {
        // artKnown=false (snapshot miss) → art carried forward, never blanked.
        val p = planMetaPush(T2, T1, artKnown = false, newArtUrl = null, lastAppliedArtUrl = "a/1.jpg")
        assertTrue(p.setIdentity)
        assertTrue(!p.setArt)
    }

    @Test
    fun plan_pollEnrichment_sameTuple_newArt_setsArtOnly() {
        // ICY owns identity; the poll upgrades art for the CURRENT track (recovers
        // an ICY-miss cover or a transient fetch failure) without moving identity.
        val p = planMetaPush(T1, T1, artKnown = true, newArtUrl = "a/1.jpg", lastAppliedArtUrl = null)
        assertTrue(!p.setIdentity)
        assertTrue(p.setArt)
    }

    @Test
    fun plan_trackWithNoCover_clearsAppliedArt_thenNoops() {
        // Authoritative "no art" (artUrl null) while a cover is applied → clear it.
        val p = planMetaPush(T2, T1, artKnown = true, newArtUrl = null, lastAppliedArtUrl = "a/1.jpg")
        assertTrue(p.setArt)
        // ...and once cleared, a repeat is a no-op (null == null).
        val q = planMetaPush(T2, T2, artKnown = true, newArtUrl = null, lastAppliedArtUrl = null)
        assertTrue(!q.setArt)
    }

    // --- sameIdentity: the poll's art-upgrade gate (icyKey tolerance) ---

    @Test
    fun sameIdentity_toleratesCaseAndWhitespaceDrift() {
        // ICY pushed "the records - starry eyes"; the API snapshot carries canonical
        // casing. The art-upgrade gate must still see them as the same track.
        assertTrue(sameIdentity("Starry Eyes" to "The Records", "  starry eyes " to "THE RECORDS"))
    }

    @Test
    fun sameIdentity_differentTracksNeverMatch() {
        // The gate's protection: a live-edge snapshot for the NEXT track must not
        // repaint identity/art while playback still lags on the previous one.
        assertTrue(!sameIdentity("Teenarama" to "The Records", "Starry Eyes" to "The Records"))
    }

    @Test
    fun sameIdentity_nullSidesNeverMatch() {
        assertTrue(!sameIdentity(null, "Starry Eyes" to "The Records"))
        assertTrue(!sameIdentity("Starry Eyes" to "The Records", null))
        assertTrue(!sameIdentity(null, null))
    }

    // --- shouldLatchArt: bounded retry of a failed inline art fetch ---

    @Test
    fun latch_onSuccess_onClear_onDefinitive() {
        assertTrue(shouldLatchArt("a/1.jpg", gotBytes = true, definitive = true, failuresSoFar = 0))
        assertTrue(shouldLatchArt(null, gotBytes = false, definitive = false, failuresSoFar = 0)) // cleared cover
        // Oversized cover: bytes refused deterministically — retrying re-downloads
        // megabytes for the same answer, so latch immediately (AA has the URI).
        assertTrue(shouldLatchArt("a/big.jpg", gotBytes = false, definitive = true, failuresSoFar = 1))
    }

    @Test
    fun transientFailure_retriesUntilBudgetSpent() {
        // Failures 1 and 2 leave the URL unlatched → the next poll tick retries.
        assertTrue(!shouldLatchArt("a/1.jpg", gotBytes = false, definitive = false, failuresSoFar = 1))
        assertTrue(!shouldLatchArt("a/1.jpg", gotBytes = false, definitive = false, failuresSoFar = 2))
        // The MAX_ART_FETCH_ATTEMPTS-th failure gives up and latches.
        assertTrue(shouldLatchArt("a/1.jpg", gotBytes = false, definitive = false, failuresSoFar = MAX_ART_FETCH_ATTEMPTS))
    }

    // --- displayArtist: station branding on the artist line ---

    @Test
    fun displayArtist_artistAndStation_joinedWithBullet() {
        assertEquals(
            "Nikki and the Corvettes • Power Pop Palace",
            displayArtist("Nikki and the Corvettes", "Power Pop Palace"),
        )
    }

    @Test
    fun displayArtist_partialAndEmptyInputs() {
        assertEquals("Just Artist", displayArtist("Just Artist", null))
        assertEquals("Power Pop Palace", displayArtist(null, "Power Pop Palace"))
        assertEquals(null, displayArtist(null, null))
    }

    // --- composeCarSubtitle: the AA/BT second line (v0.12.5) ---

    @Test
    fun composeCarSubtitle_albumBandAndShow() {
        assertEquals(
            "Somewhere in Time • Iron Maiden • On air: The Home Run",
            composeCarSubtitle("Somewhere in Time", "Iron Maiden", "Basement Transmission", "The Home Run"),
        )
    }

    @Test
    fun composeCarSubtitle_albumMissing_bandAndShow() {
        assertEquals(
            "Iron Maiden • On air: The Home Run",
            composeCarSubtitle(null, "Iron Maiden", "Basement Transmission", "The Home Run"),
        )
    }

    @Test
    fun composeCarSubtitle_noShow_albumAndBand() {
        assertEquals(
            "Somewhere in Time • Iron Maiden",
            composeCarSubtitle("Somewhere in Time", "Iron Maiden", "Basement Transmission", null),
        )
    }

    @Test
    fun composeCarSubtitle_nothingKnown_fallsBackToStation() {
        assertEquals(
            "Basement Transmission",
            composeCarSubtitle(null, null, "Basement Transmission", null),
        )
        assertEquals(null, composeCarSubtitle(null, null, null, null))
    }
}
