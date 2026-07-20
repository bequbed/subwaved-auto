package com.powerpoppalace.subwaveauto.net

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Plain-JVM MockWebServer suite for [StationApi].
 *
 * NOTE: relies on the real `org.json` being on the test classpath
 * (`testImplementation("org.json:json:...")` in app/build.gradle.kts) — the Android
 * SDK stub jar returns default values and would break parsing under test.
 */
class StationApiTest {

    private lateinit var server: MockWebServer
    private lateinit var base: String
    private lateinit var api: StationApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        base = server.url("/").toString().trimEnd('/')
        api = StationApi(base)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // --- nowPlaying ---

    @Test
    fun nowPlaying_happyPath_allFields() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "title": "Starry Eyes",
                  "artist": "The Records",
                  "album": "Shades in Bed",
                  "art": "https://cdn.example.com/covers/abc.jpg",
                  "cover": "https://cdn.example.com/covers/fallback.jpg",
                  "streamOnline": true,
                  "listeners": 3,
                  "djLine": "That was The Records...",
                  "stream": {"mount": "/stream.mp3", "format": "mp3", "bitrate": 192}
                }
                """.trimIndent()
            )
        )

        val np = api.nowPlaying()
        assertNotNull(np)
        np!!
        assertEquals("Starry Eyes", np.title)
        assertEquals("The Records", np.artist)
        assertEquals("Shades in Bed", np.album)
        assertEquals("https://cdn.example.com/covers/abc.jpg", np.artUrl)
        assertTrue(np.streamOnline)

        val recorded = server.takeRequest()
        assertEquals("/api/now-playing", recorded.path)
    }

    @Test
    fun nowPlaying_missingOptionalFields_defaults() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"title": "Lone Track"}"""))

        val np = api.nowPlaying()
        assertNotNull(np)
        np!!
        assertEquals("Lone Track", np.title)
        assertNull(np.artist)
        assertNull(np.album)
        assertNull(np.artUrl)
        assertTrue("streamOnline must default true when absent", np.streamOnline)
    }

    @Test
    fun nowPlaying_coverOnly_usedAsArtUrl() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"title": "T", "artist": "A", "cover": "https://cdn.example.com/c.png"}"""
            )
        )

        val np = api.nowPlaying()
        assertEquals("https://cdn.example.com/c.png", np?.artUrl)
    }

    @Test
    fun nowPlaying_relativeArt_resolvedUnderApiPrefix() = runBlocking {
        // Controller-relative paths are publicly served under /api (Caddy strips
        // the prefix); resolving against the origin root 404s.
        server.enqueue(MockResponse().setBody("""{"title": "T", "art": "/cover/123"}"""))

        val np = api.nowPlaying()
        assertEquals("$base/api/cover/123", np?.artUrl)
    }

    @Test
    fun nowPlaying_realPayloadShape_nestedTrackAndRelativeCover() = runBlocking {
        // Regression: the LIVE station nests the track under `nowPlaying` and emits
        // a relative top-level `cover` (verified against radio.powerpoppalace.com
        // 2026-07-02). Parsing this as top-level-only froze the AA screen on the
        // initial "SUB/WAVE / Live broadcast" metadata.
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "nowPlaying": {"title": "D-a-a-ance", "artist": "The Lambrettas",
                                 "album": "Power Pop Pandemonium", "year": 1980},
                  "cover": "/cover/ShFLea6YM7f6tUfv78NL8B",
                  "streamOnline": true,
                  "listeners": 3,
                  "dj": {"station": "Power Pop Palace", "logoLink": "https://powerpoppalace.com"},
                  "stream": {"mount": "/stream.mp3", "format": "mp3"}
                }
                """.trimIndent()
            )
        )

        val np = api.nowPlaying()
        requireNotNull(np)
        assertEquals("D-a-a-ance", np.title)
        assertEquals("The Lambrettas", np.artist)
        assertEquals("Power Pop Pandemonium", np.album)
        assertEquals("$base/api/cover/ShFLea6YM7f6tUfv78NL8B", np.artUrl)
        assertEquals(true, np.streamOnline)
        assertEquals("Power Pop Palace", np.stationName)
    }

    @Test
    fun nowPlaying_noArtField_subsonicIdDerivesCoverUrl() = runBlocking {
        // v0.6.1 regression (S24U field report, radio.plexservernz.org): CURRENT
        // SUB/WAVE payloads carry NO art/cover field at all — clients derive
        // `{base}/api/cover/<subsonic_id>` themselves (upstream
        // app/src/hooks/useNowPlayingInfo.ts). Without this fallback a stock
        // station never shows artwork anywhere.
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "nowPlaying": {"title": "Mr. Roboto", "artist": "Styx",
                                 "album": "Kilroy Was Here", "subsonic_id": "aBc123XyZ"},
                  "streamOnline": true,
                  "dj": {"station": "Basement Transmission"}
                }
                """.trimIndent()
            )
        )

        val np = api.nowPlaying()
        requireNotNull(np)
        assertEquals("Mr. Roboto", np.title)
        assertEquals("$base/api/cover/aBc123XyZ", np.artUrl)
        assertEquals("Basement Transmission", np.stationName)
    }

    @Test
    fun nowPlaying_explicitArtWinsOverSubsonicId() = runBlocking {
        // An explicit art/cover field (older stations) must keep winning over the
        // derived cover URL.
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "nowPlaying": {"title": "T", "subsonic_id": "id9"},
                  "cover": "/cover/explicit"
                }
                """.trimIndent()
            )
        )

        val np = api.nowPlaying()
        assertEquals("$base/api/cover/explicit", np?.artUrl)
    }

    @Test
    fun nowPlaying_nestedCoverField_usedAsArtUrl() = runBlocking {
        // Art fields nested inside `nowPlaying` are honored too.
        server.enqueue(
            MockResponse().setBody(
                """{"nowPlaying": {"title": "T", "cover": "https://cdn.example.com/n.jpg"}}"""
            )
        )

        val np = api.nowPlaying()
        assertEquals("https://cdn.example.com/n.jpg", np?.artUrl)
    }

    @Test
    fun coverUrlForId_encodesAndBuilds() {
        assertEquals(
            "https://radio.example.com/api/cover/a%20b",
            StationApi.coverUrlForId("a b", "https://radio.example.com"),
        )
        assertEquals(null, StationApi.coverUrlForId(null, "https://radio.example.com"))
        assertEquals(null, StationApi.coverUrlForId("", "https://radio.example.com"))
        assertEquals(null, StationApi.coverUrlForId("x", "not a url"))
    }

    @Test
    fun nowPlaying_nestedTrackWinsOverTopLevel() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"title": "stale-top-level", "nowPlaying": {"title": "Nested Wins", "artist": "A"}}"""
            )
        )

        val np = api.nowPlaying()
        assertEquals("Nested Wins", np?.title)
        assertEquals("A", np?.artist)
    }

    @Test
    fun nowPlaying_nestedStreamOnlineFalse_mapped() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"title": "T", "stream": {"online": false}}""")
        )

        val np = api.nowPlaying()
        assertNotNull(np)
        assertFalse(np!!.streamOnline)
    }

    @Test
    fun nowPlaying_topLevelStreamOnlineFalse_mapped() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"title": "T", "streamOnline": false}"""))

        assertFalse(api.nowPlaying()!!.streamOnline)
    }

    @Test
    fun nowPlaying_http500_returnsNull() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertNull(api.nowPlaying())
    }

    @Test
    fun nowPlaying_malformedJson_returnsNull() = runBlocking {
        server.enqueue(MockResponse().setBody("this is not { json"))
        assertNull(api.nowPlaying())
    }

    @Test
    fun nowPlaying_emptyBody_returnsNull() = runBlocking {
        server.enqueue(MockResponse().setBody(""))
        assertNull(api.nowPlaying())
    }

    @Test
    fun nowPlaying_timeout_returnsNull() = runBlocking {
        // Short-timeout client injected so the test stays fast; server never responds.
        val shortClient = OkHttpClient.Builder()
            .callTimeout(500, TimeUnit.MILLISECONDS)
            .build()
        val impatient = StationApi(base, shortClient)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        assertNull(impatient.nowPlaying())
    }

    @Test
    fun nowPlaying_serverUnreachable_returnsNull() = runBlocking {
        val dead = StationApi("http://127.0.0.1:${server.port}")
        server.shutdown()
        assertNull(dead.nowPlaying())
    }

    // --- fetchArt ---

    @Test
    fun nowPlaying_djNameAndListeners_parsed() = runBlocking {
        // v0.8 phone-UI enrichment: dj.name + top-level listeners.
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "nowPlaying": {"title": "T", "artist": "A"},
                  "listeners": 4,
                  "dj": {"name": "Frequency", "station": "Basement Transmission"}
                }
                """.trimIndent()
            )
        )

        val np = api.nowPlaying()
        assertEquals("Frequency", np?.djName)
        assertEquals(4, np?.listeners)
        assertEquals("Basement Transmission", np?.stationName)
    }

    // --- v0.8 song requests ---

    @Test
    fun postRequest_happyPath_sendsJsonAndParsesReply() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"success": true, "pending": true, "requestId": "abc-123", "message": "Got it"}"""
            )
        )

        val r = api.postRequest("play some rush", "Jenil")
        requireNotNull(r)
        assertTrue(r.success)
        assertTrue(r.pending)
        assertEquals("abc-123", r.id)
        assertEquals("Got it", r.message)

        val recorded = server.takeRequest()
        assertEquals("/api/request", recorded.path)
        val sent = recorded.body.readUtf8()
        assertTrue(sent.contains("\"text\":\"play some rush\""))
        assertTrue(sent.contains("\"name\":\"Jenil\""))
    }

    @Test
    fun postRequest_rateLimited_surfacesServerMessage() = runBlocking {
        // 429/503 bodies carry a human line — it must reach the UI, not vanish
        // into a generic error.
        server.enqueue(
            MockResponse().setResponseCode(429).setBody(
                """{"success": false, "message": "Easy there — try again in 30s."}"""
            )
        )

        val r = api.postRequest("x", null)
        assertEquals(false, r?.success)
        assertEquals("Easy there — try again in 30s.", r?.message)
    }

    @Test
    fun postRequest_serverUnreachable_returnsNull() = runBlocking {
        val dead = StationApi("http://127.0.0.1:${server.port}")
        server.shutdown()
        assertEquals(null, dead.postRequest("x", null))
    }

    @Test
    fun pollRequest_resolvedAck_parsed() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"success": true, "status": "resolved", "ack": "Queued: Mr. Roboto"}"""
            )
        )

        val r = api.pollRequest("abc-123")
        assertEquals("Queued: Mr. Roboto", r?.message)
        assertEquals(false, r?.pending)
        assertEquals("/api/request/abc-123", server.takeRequest().path)
    }

    @Test
    fun parseRequestResult_ackPreferredOverMessage() {
        val r = StationApi.parseRequestResult("""{"success": true, "ack": "A", "message": "B"}""")
        assertEquals("A", r?.message)
    }

    @Test
    fun parseRequestResult_pendingStatusStringCounts() {
        val r = StationApi.parseRequestResult("""{"success": true, "status": "pending", "id": "x"}""")
        assertEquals(true, r?.pending)
        assertEquals("x", r?.id)
    }

    @Test
    fun parseRequestResult_garbage_returnsNull() {
        assertEquals(null, StationApi.parseRequestResult("not json"))
    }

    // --- v0.12.1 schedule / On air / Up next ---

    @Test
    fun nowPlaying_activeShowName_parsed() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "nowPlaying": {"title": "T"},
                  "activeShow": {"name": "Late Night Basement", "persona": {"id": "p1", "name": "Frequency"}}
                }
                """.trimIndent()
            )
        )
        assertEquals("Late Night Basement", api.nowPlaying()?.showName)
    }

    @Test
    fun nowPlaying_activeShowFromNestedShapes_parsed() = runBlocking {
        // The live station (radio.plexservernz.org 2026-07-20) has NO top-level
        // activeShow — it nests the show under context.activeShow / dj.activeShow
        // (and session.show). "On air" must resolve from those, not only the
        // top-level block, or a scheduled show never surfaces.
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "nowPlaying": {"title": "Leave Me Alone", "artist": "Michael Jackson"},
                  "context": {"activeShow": {"id": "s_f378dd", "name": "First Light"}},
                  "dj": {"name": "Saffron", "station": "Basement Transmission",
                         "activeShow": {"name": "First Light"}},
                  "session": {"kind": "show", "show": "First Light"}
                }
                """.trimIndent()
            )
        )
        assertEquals("First Light", api.nowPlaying()?.showName)
    }

    @Test
    fun nowPlaying_autoDjNullActiveShow_showNameNull() = runBlocking {
        // Auto-DJ stations (session.kind == "auto") carry no show at all — every
        // activeShow slot is null and session.show is null. "On air" must stay
        // null so the line hides rather than inventing a show.
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "nowPlaying": {"title": "T"},
                  "activeShow": null,
                  "context": {"show": "drive-time", "activeShow": null},
                  "dj": {"name": "Rizzy", "activeShow": null},
                  "session": {"kind": "auto", "show": null}
                }
                """.trimIndent()
            )
        )
        assertEquals(null, api.nowPlaying()?.showName)
    }

    @Test
    fun schedule_parsesShowsGridAndTimezone() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "shows": [{"id": "s1", "name": "Morning Drive"}, {"id": "s2", "name": "Night Owls"}],
                  "schedule": {"0": ["s1", null, "s2"], "1": [null, null, null]},
                  "timezone": "Pacific/Auckland"
                }
                """.trimIndent()
            )
        )
        val s = api.schedule()
        requireNotNull(s)
        assertEquals("Morning Drive", s.showNames["s1"])
        assertEquals("s1", s.grid[0]?.get(0))
        assertEquals(null, s.grid[0]?.get(1))
        assertEquals("s2", s.grid[0]?.get(2))
        assertEquals("Pacific/Auckland", s.timezone)
        // The grid lives in /api/state, not a dedicated /api/schedule route.
        assertEquals("/api/state", server.takeRequest().path)
    }

    @Test
    fun parseSchedule_garbage_returnsNull() {
        assertEquals(null, StationApi.parseSchedule("nope"))
    }

    @Test
    fun upNext_skipsSameShowRunAndNulls() {
        // Sun 10:00-12:00 is one 3-hour block of s1; s2 starts at 14:00 with a
        // freeform gap between — "next" must be s2 at 14, not the s1 slots.
        val info = StationApi.ScheduleInfo(
            showNames = mapOf("s1" to "Block Party", "s2" to "Drive Time"),
            grid = mapOf(0 to List(24) { h -> when (h) { 10, 11, 12 -> "s1"; 14 -> "s2"; else -> null } }),
            timezone = null,
        )
        val next = StationApi.upNext(info, dayOfWeek = 0, hour = 10)
        requireNotNull(next)
        assertEquals("Drive Time", next.name)
        assertEquals(0, next.dayOffset)
        assertEquals(14, next.hour)
    }

    @Test
    fun upNext_wrapsToNextDay() {
        val info = StationApi.ScheduleInfo(
            showNames = mapOf("s1" to "Morning Drive"),
            grid = mapOf(
                6 to List(24) { null },
                0 to List(24) { h -> if (h == 6) "s1" else null },
            ),
            timezone = null,
        )
        // Saturday 23:00 → next is Sunday 06:00, one day ahead.
        val next = StationApi.upNext(info, dayOfWeek = 6, hour = 23)
        requireNotNull(next)
        assertEquals("Morning Drive", next.name)
        assertEquals(1, next.dayOffset)
        assertEquals(6, next.hour)
    }

    @Test
    fun upNext_emptyGrid_isNull() {
        val info = StationApi.ScheduleInfo(emptyMap(), emptyMap(), null)
        assertEquals(null, StationApi.upNext(info, 0, 0))
    }

    @Test
    fun upNext_unknownShowId_skipped() {
        // A grid id with no matching shows entry must not surface a nameless "next".
        val info = StationApi.ScheduleInfo(
            showNames = mapOf("known" to "Known Show"),
            grid = mapOf(0 to List(24) { h -> when (h) { 2 -> "ghost"; 4 -> "known"; else -> null } }),
            timezone = null,
        )
        val next = StationApi.upNext(info, 0, 0)
        assertEquals("Known Show", next?.name)
        assertEquals(4, next?.hour)
    }

    // --- v0.11 likes ---

    @Test
    fun likeState_parsesEnabledLikedCount() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"enabled": true, "songId": "abc", "liked": false, "count": 12}"""
            )
        )
        val s = api.likeState()
        requireNotNull(s)
        assertTrue(s.enabled)
        assertFalse(s.liked)
        assertEquals(12, s.count)
        assertEquals("abc", s.songId)
        assertEquals("/api/like", server.takeRequest().path)
    }

    @Test
    fun like_postsSongIdAndParsesUpdatedCount() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"ok": true, "songId": "abc", "liked": true, "alreadyLiked": false, "count": 13}"""
            )
        )
        val s = api.like("abc")
        requireNotNull(s)
        assertTrue(s.liked)
        assertEquals(13, s.count)

        val recorded = server.takeRequest()
        assertEquals("/api/like", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"songId\":\"abc\""))
    }

    @Test
    fun like_nullSongId_sendsEmptyBodyObject() = runBlocking {
        // The AA button likes whatever's on air — no songId sent.
        server.enqueue(MockResponse().setBody("""{"ok": true, "liked": true, "count": 1}"""))
        api.like(null)
        val sent = server.takeRequest().body.readUtf8()
        assertFalse("no songId key when null", sent.contains("songId"))
    }

    @Test
    fun like_disabledStation_403_returnsDisabledState() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody("""{"error": "Likes are disabled on this station"}""")
        )
        val s = api.like("abc")
        assertEquals(false, s?.enabled)
    }

    @Test
    fun parseLikeState_postSuccessOmittingEnabled_readsEnabledTrue() {
        val s = StationApi.parseLikeState("""{"ok": true, "liked": true, "count": 5}""")
        assertEquals(true, s?.enabled)
        assertEquals(5, s?.count)
    }

    @Test
    fun parseLikeState_garbage_returnsNull() {
        assertEquals(null, StationApi.parseLikeState("nope"))
    }

    @Test
    fun fetchArt_happyPath_returnsBytesAndMime() = runBlocking {
        val bytes = byteArrayOf(0x50, 0x4E, 0x47, 1, 2, 3, 4, 5)
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(bytes))
                .setHeader("Content-Type", "image/png")
        )

        val got = api.fetchArt("$base/cover/1")
        assertNotNull(got)
        assertArrayEquals(bytes, got!!.bytes)
        assertEquals("image/png", got.mimeType)
    }

    @Test
    fun fetchArt_http404_returnsNull() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(api.fetchArt("$base/cover/missing"))
    }

    @Test
    fun fetchArt_emptyBody_returnsNull() = runBlocking {
        server.enqueue(MockResponse().setBody(""))
        assertNull(api.fetchArt("$base/cover/empty"))
    }

    @Test
    fun fetchArt_oversizedBody_returnsNull() = runBlocking {
        // 2 MB + 1 byte — just over StationApi.MAX_ART_BYTES.
        val big = ByteArray((StationApi.MAX_ART_BYTES + 1).toInt())
        server.enqueue(MockResponse().setBody(Buffer().write(big)))

        assertNull(api.fetchArt("$base/cover/huge"))
    }

    @Test
    fun fetchArt_malformedUrl_returnsNull() = runBlocking {
        assertNull(api.fetchArt("not a url at all"))
    }

    @Test
    fun fetchArt_jsonErrorBody_returnsNull() = runBlocking {
        // Navidrome answers a MISSING cover with HTTP 200 + a Subsonic JSON error
        // envelope; older controllers relay it verbatim. Those bytes must never
        // become artworkData — an undecodable blob renders the track artless.
        server.enqueue(
            MockResponse()
                .setBody("""{"subsonic-response":{"status":"failed","error":{"code":70,"message":"Artwork not found"}}}""")
                .setHeader("Content-Type", "application/json"),
        )
        assertNull(api.fetchArt("$base/cover/missing-art"))
    }

    @Test
    fun fetchArt_octetStreamAndMissingType_acceptedWithNullMime() = runBlocking {
        // Some servers omit Content-Type or say octet-stream for valid images —
        // only an EXPLICIT non-image type is rejected. The generic type must NOT
        // ride along as a trusted MIME (downstream storage would mislabel it).
        val bytes = byteArrayOf(0x50, 0x4E, 0x47, 9, 9)
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(bytes))
                .setHeader("Content-Type", "application/octet-stream"),
        )
        val got = api.fetchArt("$base/cover/octet")
        assertArrayEquals(bytes, got!!.bytes)
        assertNull(got.mimeType)
    }

    // --- isLikelyImageContentType (pure) ---

    @Test
    fun imageContentType_acceptsImagesGenericAndAbsent() {
        assertTrue(isLikelyImageContentType("image/webp"))
        assertTrue(isLikelyImageContentType("IMAGE/JPEG; charset=binary")) // case + params
        assertTrue(isLikelyImageContentType("application/octet-stream"))
        assertTrue(isLikelyImageContentType(null))
        assertTrue(isLikelyImageContentType(""))
    }

    @Test
    fun imageContentType_rejectsDeclaredNonImages() {
        assertFalse(isLikelyImageContentType("application/json"))
        assertFalse(isLikelyImageContentType("application/json; charset=utf-8"))
        assertFalse(isLikelyImageContentType("text/html"))
        assertFalse(isLikelyImageContentType("text/plain; charset=utf-8"))
    }

    // --- streamUrl ---

    @Test
    fun streamUrl_cacheBust_appendsTimestamp() {
        val url = api.streamUrl(cacheBust = true)
        assertTrue(
            "expected $url to match {base}/stream.mp3?t=<ms>",
            Regex("^" + Regex.escape("$base/stream.mp3") + "\\?t=\\d+$").matches(url)
        )
    }

    @Test
    fun streamUrl_default_isCacheBusted() {
        assertTrue(api.streamUrl().contains("/stream.mp3?t="))
    }

    @Test
    fun streamUrl_noCacheBust_plain() {
        assertEquals("$base/stream.mp3", api.streamUrl(cacheBust = false))
    }
}
