package com.powerpoppalace.subwaveauto.prefs

import android.content.Context
import android.content.SharedPreferences
import java.net.URI

/**
 * Station base-URL preference (SharedPreferences file `station`, key `baseUrl`).
 *
 * SECURITY (ANDROID_AUTO_PLAN.md §1): the base URL feeds the stream URI and artwork
 * fetches, so `https://` is required — plain `http://` is accepted ONLY for
 * private/dev hosts (localhost, 127.*, 10.*, 172.16-31.*, 192.168.*). Don't let a
 * captive portal downgrade the transport.
 */
object StationPrefs {
    const val DEFAULT_BASE_URL = "https://radio.powerpoppalace.com"

    internal const val PREFS_FILE = "station"
    internal const val KEY_BASE_URL = "baseUrl"

    /** v0.5 artwork publish mode (hidden diagnostics switch). Stored as the
     *  ArtMode.prefValue string; unknown values read back as production. */
    internal const val KEY_ART_MODE = "artMode"

    /** v0.8 listener name attached to song requests ("name" in POST /api/request). */
    internal const val KEY_LISTENER_NAME = "listenerName"

    /** Server-side cap on the request name (upstream REQUEST_NAME_MAX). */
    const val LISTENER_NAME_MAX = 40

    /**
     * Strong references to registered listeners. SharedPreferences holds its
     * listeners in a WeakHashMap, so without this list they would be GC'd and
     * silently stop firing. Listeners live for the process lifetime (the only
     * caller is the playback service's base-URL-change hook) — never unregistered.
     */
    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** Current station base URL (no trailing slash), or [DEFAULT_BASE_URL] if unset. */
    fun baseUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL

    /**
     * Persist a new base URL. Input is normalized via [normalizeBaseUrl]; values
     * that fail normalization (bad scheme, plain http on a public host, garbage)
     * are silently rejected — the stored value is left unchanged.
     */
    fun setBaseUrl(ctx: Context, url: String) {
        val normalized = normalizeBaseUrl(url) ?: return
        prefs(ctx).edit().putString(KEY_BASE_URL, normalized).apply()
    }

    /**
     * Raw artwork-mode pref value (null/garbage tolerated — readers map it via
     * ArtMode.fromPref, which falls back to production). Read fresh per metadata
     * push, so changes apply from the next track without a service restart.
     */
    fun artMode(ctx: Context): String? = prefs(ctx).getString(KEY_ART_MODE, null)

    /** Persist an artwork mode (the caller passes a known ArtMode.prefValue). */
    fun setArtMode(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY_ART_MODE, value).apply()
    }

    /** Listener name for song requests, or "" when unset (server shows "anon"). */
    fun listenerName(ctx: Context): String =
        prefs(ctx).getString(KEY_LISTENER_NAME, null).orEmpty()

    /** Persist the request name (trimmed + capped to the server's limit). */
    fun setListenerName(ctx: Context, name: String) {
        prefs(ctx).edit().putString(KEY_LISTENER_NAME, name.trim().take(LISTENER_NAME_MAX)).apply()
    }

    /**
     * Invoke [onChange] whenever the stored base URL changes (WP3's hook: stop
     * playback, rebuild StationApi, reset the media item). The wrapped listener is
     * strongly referenced by [listeners] — see the note there.
     */
    fun registerListener(ctx: Context, onChange: () -> Unit) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_BASE_URL) onChange()
        }
        listeners.add(listener)
        prefs(ctx).registerOnSharedPreferenceChangeListener(listener)
    }
}

/**
 * Pure normalization/validation for a station base URL — kept Context-free so it is
 * unit-testable on the plain JVM.
 *
 * Rules:
 * - trim whitespace, strip trailing `/`
 * - scheme must be `https`, OR `http` when the host is a private/dev host:
 *   localhost, 127.*, 10.*, 172.16.* – 172.31.*, 192.168.*
 * - anything unparseable, schemeless, hostless, or otherwise off-spec → null (rejected)
 */
internal fun normalizeBaseUrl(raw: String): String? {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isEmpty()) return null
    val uri = try {
        URI(trimmed)
    } catch (_: Exception) {
        return null
    }
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.lowercase() ?: return null
    if (host.isEmpty()) return null
    return when (scheme) {
        "https" -> trimmed
        "http" -> if (isPrivateHost(host)) trimmed else null
        else -> null
    }
}

/**
 * localhost / loopback / RFC-1918 hosts where plain http is tolerated for dev use.
 * The numeric ranges only match genuine dotted-quad IPv4 literals — a DNS name like
 * `192.168.evil.com` must NOT slip through on a string-prefix check.
 */
private fun isPrivateHost(host: String): Boolean {
    if (host == "localhost") return true
    val octets = host.split(".")
    if (octets.size != 4) return false
    val nums = octets.map { it.toIntOrNull() ?: return false }
    if (nums.any { it !in 0..255 }) return false
    return when {
        nums[0] == 127 -> true
        nums[0] == 10 -> true
        nums[0] == 192 && nums[1] == 168 -> true
        nums[0] == 172 && nums[1] in 16..31 -> true
        else -> false
    }
}
