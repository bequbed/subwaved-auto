package com.powerpoppalace.subwaveauto.net

/**
 * Now-playing snapshot from `GET {base}/api/now-playing`.
 *
 * Field mapping (see ANDROID_AUTO_PLAN.md §1 / WP1):
 * - [artUrl] is the absolute `art` URL, falling back to `cover`; relative URLs are
 *   resolved against the station base URL defensively.
 * - [streamOnline] falls back to the nested `stream.online` field and defaults to
 *   `true` when absent.
 */
data class NowPlaying(
    val title: String?, val artist: String?, val album: String?,
    val artUrl: String?,          // absolute URL: `art` field, falling back to `cover`
    val streamOnline: Boolean,
    /** Station display name (`dj.station` in the payload), e.g. "Power Pop Palace". */
    val stationName: String? = null,
    /** DJ persona display name (`dj.name`), e.g. "Frequency". v0.8 phone UI. */
    val djName: String? = null,
    /** Current listener count (top-level `listeners`), or null when absent. */
    val listeners: Int? = null,
)
