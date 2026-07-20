package com.powerpoppalace.subwaveauto.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.session.CommandButton
import com.powerpoppalace.subwaveauto.net.StationApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the AA-critical resolution seam (ANDROID_AUTO_PLAN.md §2 WP3 step 4):
 * Android Auto sends bare mediaIds with NO URI — every requested item must resolve
 * to the fully-formed live item (fresh `?t=` cache-busted stream URI, explicit
 * AUDIO_MPEG mime, [LIVE_ITEM_ID]).
 *
 * [BrowseTree.resolveMediaItems] is exactly the list `onAddMediaItems` returns
 * (that callback is a one-line delegate); testing it directly avoids constructing
 * a real MediaSession/ControllerInfo (neither is constructible in a unit test).
 *
 * Uses a real [StationApi] against a fake base URL — no network is touched:
 * `streamUrl()` is pure string building.
 *
 * Robolectric because media3's MediaItem.Builder needs a real android.net.Uri
 * (the mockable android.jar throws). SDK pinned to 34 — Robolectric 4.14.1
 * doesn't emulate this module's compileSdk 36.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowseTreeTest {

    private val base = "https://station.example.com"
    private val api = StationApi(base)
    private val tree = BrowseTree(api)

    private fun assertIsFullyFormedLiveItem(item: MediaItem, notBeforeMs: Long, notAfterMs: Long) {
        assertEquals(LIVE_ITEM_ID, item.mediaId)

        val config = item.localConfiguration
        assertNotNull("resolved item must carry a stream URI", config)
        val uri = config!!.uri.toString()
        assertTrue("URI must target the station stream: $uri", uri.startsWith("$base/stream.mp3?t="))
        val t = uri.substringAfter("?t=")
        assertTrue("cache-buster must be non-empty", t.isNotEmpty())
        val tMs = t.toLong() // throws (fails the test) if not numeric
        assertTrue("cache-buster must be FRESH (minted at resolve time)", tMs in notBeforeMs..notAfterMs)

        assertEquals(MimeTypes.AUDIO_MPEG, config.mimeType)

        val meta = item.mediaMetadata
        assertEquals(MediaMetadata.MEDIA_TYPE_RADIO_STATION, meta.mediaType)
        assertEquals(true, meta.isPlayable)
        assertEquals(false, meta.isBrowsable)
        assertEquals("SUB/WAVE", meta.title.toString())
    }

    // --- liveMediaItem: the single shared builder ---

    @Test
    fun liveMediaItem_isFullyFormed() {
        val before = System.currentTimeMillis()
        val item = liveMediaItem(api)
        val after = System.currentTimeMillis()
        assertIsFullyFormedLiveItem(item, before, after)
    }

    @Test
    fun liveMediaItem_mintsAFreshCacheBusterPerCall() {
        val first = liveMediaItem(api).localConfiguration!!.uri.toString()
        Thread.sleep(2) // currentTimeMillis granularity
        val second = liveMediaItem(api).localConfiguration!!.uri.toString()
        assertFalse("consecutive resolves must never reuse an old ?t=", first == second)
    }

    // --- v0.8 song requests from the car (voice search / AA search-result tap) ---

    @Test
    fun resolve_voiceSearchQuery_firesSongRequestAndStillPlaysLive() {
        var fired: String? = null
        tree.onSongRequest = { fired = it }
        val voiceItem = MediaItem.Builder()
            .setMediaId("some_assistant_id")
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder().setSearchQuery("play some rush").build(),
            )
            .build()

        val before = System.currentTimeMillis()
        val resolved = tree.resolveMediaItems(listOf(voiceItem))
        val after = System.currentTimeMillis()

        assertEquals("play some rush", fired)
        // The request rides along — playback must STILL resolve to the live stream.
        assertEquals(1, resolved.size)
        assertIsFullyFormedLiveItem(resolved[0], before, after)
    }

    @Test
    fun resolve_requestPrefixedMediaId_firesSongRequest() {
        var fired: String? = null
        tree.onSongRequest = { fired = it }
        val searchTap = MediaItem.Builder().setMediaId(REQUEST_ITEM_PREFIX + "mr roboto").build()

        val resolved = tree.resolveMediaItems(listOf(searchTap))

        assertEquals("mr roboto", fired)
        assertEquals(LIVE_ITEM_ID, resolved[0].mediaId)
    }

    @Test
    fun resolve_plainLiveItem_firesNoRequest() {
        var fired: String? = null
        tree.onSongRequest = { fired = it }
        tree.resolveMediaItems(listOf(MediaItem.Builder().setMediaId(LIVE_ITEM_ID).build()))
        assertEquals(null, fired)
    }

    @Test
    fun resolve_blankSearchQuery_firesNoRequest() {
        // "Play SUB/WAVE Auto" (app name only) can arrive as a blank query —
        // that's a plain play command, not a request.
        var fired: String? = null
        tree.onSongRequest = { fired = it }
        val item = MediaItem.Builder()
            .setMediaId("x")
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery("  ").build())
            .build()
        tree.resolveMediaItems(listOf(item))
        assertEquals(null, fired)
    }

    // --- v0.10 one-tap "More like this" (custom AA command) ---

    @Test
    fun handleCustomAction_moreLikeThis_firesCannedRequest() {
        var fired: String? = null
        tree.onSongRequest = { fired = it }
        assertTrue(tree.handleCustomAction(ACTION_MORE_LIKE_THIS))
        assertEquals(MORE_LIKE_THIS_TEXT, fired)
    }

    @Test
    fun handleCustomAction_unknownAction_notHandled() {
        var fired: String? = null
        tree.onSongRequest = { fired = it }
        assertFalse(tree.handleCustomAction("some.other.action"))
        assertEquals(null, fired)
    }

    @Test
    fun requestButton_normalState_carriesTheSessionCommand() {
        val button = tree.requestButton(sent = false)
        assertEquals(ACTION_MORE_LIKE_THIS, button.sessionCommand?.customAction)
        assertEquals("More like this", button.displayName.toString())
        assertEquals(CommandButton.ICON_SHUFFLE_STAR, button.icon)
        assertTrue(button.isEnabled)
    }

    @Test
    fun requestButton_sentState_disabledConfirmation() {
        // v0.10.1 tap feedback: the flashed state must read as a confirmation
        // and not accept a second tap while it's up.
        val button = tree.requestButton(sent = true)
        assertEquals("Request sent", button.displayName.toString())
        assertEquals(CommandButton.ICON_CHECK_CIRCLE_FILLED, button.icon)
        assertFalse(button.isEnabled)
    }

    // --- v0.11 Like (heart) custom AA button ---

    @Test
    fun handleCustomAction_like_firesOnLike() {
        var liked = false
        var requested: String? = null
        tree.onLike = { liked = true }
        tree.onSongRequest = { requested = it }
        assertTrue(tree.handleCustomAction(ACTION_LIKE))
        assertTrue(liked)
        assertEquals("like must not fire a song request", null, requested)
    }

    @Test
    fun likeButton_states() {
        val idle = tree.likeButton(flashed = false)
        assertEquals(ACTION_LIKE, idle.sessionCommand?.customAction)
        assertEquals("Like this song", idle.displayName.toString())
        assertEquals(CommandButton.ICON_HEART_UNFILLED, idle.icon)
        assertTrue(idle.isEnabled)

        val flashed = tree.likeButton(flashed = true)
        assertEquals("Liked", flashed.displayName.toString())
        assertEquals(CommandButton.ICON_CHECK_CIRCLE_FILLED, flashed.icon)
        assertFalse(flashed.isEnabled)
    }

    @Test
    fun customLayout_hasBothButtonsInOrder() {
        val layout = tree.customLayout(requestSent = false, likeFlashed = false)
        assertEquals(2, layout.size)
        assertEquals(ACTION_MORE_LIKE_THIS, layout[0].sessionCommand?.customAction)
        assertEquals(ACTION_LIKE, layout[1].sessionCommand?.customAction)
    }

    @Test
    fun requestItemFor_playableCardCarryingTheQuery() {
        val item = tree.requestItemFor("mr roboto")
        assertEquals(REQUEST_ITEM_PREFIX + "mr roboto", item.mediaId)
        assertEquals(true, item.mediaMetadata.isPlayable)
        assertEquals(false, item.mediaMetadata.isBrowsable)
        assertTrue(item.mediaMetadata.title.toString().contains("mr roboto"))
    }

    // --- v0.12 station presets ---

    @Test
    fun rootChildren_noPresets_isSingleLiveItem() {
        // Backward compatible: no saved stations → the one live item.
        val children = tree.rootChildren()
        assertEquals(1, children.size)
        assertEquals(LIVE_ITEM_ID, children[0].mediaId)
    }

    @Test
    fun rootChildren_withPresets_listsThem() {
        tree.stations = {
            listOf(
                com.powerpoppalace.subwaveauto.prefs.StationPreset("One", "https://one.example.com"),
                com.powerpoppalace.subwaveauto.prefs.StationPreset("Two", "https://two.example.com"),
            )
        }
        val children = tree.rootChildren()
        assertEquals(2, children.size)
        assertEquals(STATION_ITEM_PREFIX + "https://one.example.com", children[0].mediaId)
        assertEquals("One", children[0].mediaMetadata.title.toString())
        assertEquals(true, children[1].mediaMetadata.isPlayable)
    }

    @Test
    fun resolve_stationItem_switchesStationAndPlaysThatUrl() {
        var picked: String? = null
        tree.onSelectStation = { picked = it }
        val item = MediaItem.Builder()
            .setMediaId(STATION_ITEM_PREFIX + "https://two.example.com")
            .build()

        val resolved = tree.resolveMediaItems(listOf(item))

        // The pick switched the active station...
        assertEquals("https://two.example.com", picked)
        // ...and the resolved item streams THAT station immediately.
        assertEquals(LIVE_ITEM_ID, resolved[0].mediaId)
        val uri = resolved[0].localConfiguration!!.uri.toString()
        assertTrue("must stream the picked station: $uri", uri.startsWith("https://two.example.com/stream.mp3?t="))
    }

    @Test
    fun stationUrlFor_extractsUrl_orNull() {
        val stationItem = MediaItem.Builder().setMediaId(STATION_ITEM_PREFIX + "https://x.example.com").build()
        assertEquals("https://x.example.com", tree.stationUrlFor(stationItem))
        assertEquals(null, tree.stationUrlFor(MediaItem.Builder().setMediaId(LIVE_ITEM_ID).build()))
    }

    // --- onAddMediaItems resolution (via its delegate resolveMediaItems) ---

    @Test
    fun resolve_liveItemId_returnsFullyFormedLiveItem() {
        val requested = MediaItem.Builder().setMediaId(LIVE_ITEM_ID).build() // bare id, no URI — what AA sends
        val before = System.currentTimeMillis()
        val resolved = tree.resolveMediaItems(listOf(requested))
        val after = System.currentTimeMillis()
        assertEquals(1, resolved.size)
        assertIsFullyFormedLiveItem(resolved[0], before, after)
    }

    @Test
    fun resolve_unknownBareMediaId_stillResolvesToLiveItem() {
        // ANY requested id maps to the live item — there is only one thing to play.
        val requested = MediaItem.Builder().setMediaId("some_unknown_id").build()
        val before = System.currentTimeMillis()
        val resolved = tree.resolveMediaItems(listOf(requested))
        val after = System.currentTimeMillis()
        assertEquals(1, resolved.size)
        assertIsFullyFormedLiveItem(resolved[0], before, after)
    }

    @Test
    fun resolve_usesTheCurrentApi_afterBaseUrlSwap() {
        val swapped = BrowseTree(StationApi("https://old.example.com"))
        swapped.api = StationApi(base) // what PlaybackService's prefs listener does
        val resolved = swapped.resolveMediaItems(listOf(MediaItem.Builder().setMediaId(LIVE_ITEM_ID).build()))
        assertTrue(resolved[0].localConfiguration!!.uri.toString().startsWith("$base/stream.mp3?t="))
    }

    // --- browse-tree items ---

    @Test
    fun rootItem_isBrowsableNotPlayable() {
        val root = tree.rootItem()
        assertEquals(ROOT_ID, root.mediaId)
        assertEquals("SUB/WAVE", root.mediaMetadata.title.toString())
        assertEquals(true, root.mediaMetadata.isBrowsable)
        assertEquals(false, root.mediaMetadata.isPlayable)
    }

    @Test
    fun browseLiveItem_isTheLiveItemWithSubtitle() {
        val before = System.currentTimeMillis()
        val item = tree.browseLiveItem()
        val after = System.currentTimeMillis()
        assertIsFullyFormedLiveItem(item, before, after)
        assertEquals("Live radio", item.mediaMetadata.subtitle.toString())
    }
}
