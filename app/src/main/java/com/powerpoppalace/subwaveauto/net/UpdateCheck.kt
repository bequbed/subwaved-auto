package com.powerpoppalace.subwaveauto.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Parse a version string ("0.7.0", "v0.7.0", "0.7") into numeric segments.
 * Trailing non-digits per segment are dropped ("1.0-rc" → [1, 0]); a segment
 * with no leading digits at all (or an empty string) is unparseable → null.
 * Pure, JVM-tested.
 */
internal fun parseVersion(v: String): List<Int>? {
    val cleaned = v.trim().removePrefix("v").removePrefix("V")
    if (cleaned.isEmpty()) return null
    val segments = cleaned.split('.').map { seg -> seg.takeWhile { it.isDigit() } }
    if (segments.any { it.isEmpty() }) return null
    return segments.map { it.toInt() }
}

/**
 * True when [latest] (a release tag like "v0.7.0") is strictly newer than
 * [current] (the installed versionName). Missing segments compare as 0
 * ("0.7" == "0.7.0"); anything unparseable is NOT newer — never nag over a
 * malformed tag. Pure, JVM-tested.
 */
internal fun isNewerVersion(latest: String, current: String): Boolean {
    val l = parseVersion(latest) ?: return false
    val c = parseVersion(current) ?: return false
    for (i in 0 until maxOf(l.size, c.size)) {
        val a = l.getOrElse(i) { 0 }
        val b = c.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return false
}

/** Latest published release: its tag ("v0.7.0") and browser download page. */
internal class UpdateInfo(val tag: String, val url: String)

/**
 * "Is there a newer APK?" check against this repo's GitHub Releases page —
 * the only update channel a sideloaded app has (v0.7, plan Tier-1 item 3).
 *
 * One unauthenticated GET per app launch to the public releases/latest
 * endpoint (rate limit 60/h per IP — a launch-time check can't get near it).
 * EVERY failure path returns null: offline, rate-limited, repo renamed, no
 * releases published yet (404). The phone UI shows nothing in that case —
 * an update hint is strictly best-effort and must never degrade the app.
 */
internal object UpdateCheck {

    /** Repo whose Releases page is the update channel. */
    private const val RELEASES_API_URL =
        "https://api.github.com/repos/bequbed/subwaved-auto/releases/latest"
    private const val RELEASES_PAGE_URL =
        "https://github.com/bequbed/subwaved-auto/releases"

    /** Own client, tight timeout — this races app launch, never blocks it. */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().callTimeout(6, TimeUnit.SECONDS).build()
    }

    /** The latest release, or null on ANY failure (including "no releases yet"). */
    suspend fun latestRelease(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(RELEASES_API_URL)
                .header("Accept", "application/vnd.github+json")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string()
                if (body.isNullOrBlank()) return@withContext null
                val o = JSONObject(body)
                val tag = o.optString("tag_name").takeIf { it.isNotBlank() }
                    ?: return@withContext null
                val url = o.optString("html_url").takeIf { it.isNotBlank() }
                    ?: RELEASES_PAGE_URL
                UpdateInfo(tag, url)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }
    }
}
