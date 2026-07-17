package com.powerpoppalace.subwaveauto.art

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-wide artwork-pipeline snapshot for the hidden phone-UI diagnostics
 * panel (v0.5). Written from the playback service's push path and from
 * [ArtworkProvider]; read by MainActivity on a slow poll. Plain @Volatile
 * strings — losing an update under a race costs nothing, this is a debug
 * readout, not state.
 *
 * The panel exists so an affected user (the Pixel artwork reports) can tell us
 * in one screenshot which stage dies: fetch, decode, store, push — or whether
 * gearhead simply never asks the provider for the published URI.
 */
internal object ArtDiagnostics {

    @Volatile var lastFetch: String = "—"
    @Volatile var lastNormalize: String = "—"
    @Volatile var lastStore: String = "—"
    @Volatile var lastPush: String = "—"
    @Volatile var lastGrant: String = "—"
    @Volatile var lastProviderOpen: String = "never"

    private fun now(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    fun recordFetch(url: String, ok: Boolean, sizeBytes: Int, mime: String?) {
        lastFetch = if (ok) {
            "[${now()}] $sizeBytes B ${mime ?: "?"} ← ${url.takeLast(48)}"
        } else {
            "[${now()}] FAILED ← ${url.takeLast(48)}"
        }
    }

    fun recordNormalize(inBytes: Int, result: NormalizedArt?) {
        lastNormalize = if (result != null) {
            "[${now()}] $inBytes B → ${result.width}x${result.height} jpeg ${result.bytes.size} B"
        } else {
            "[${now()}] decode FAILED — using original bytes"
        }
    }

    fun recordStore(hash: String?, uri: String?) {
        lastStore = if (hash != null) {
            "[${now()}] ${hash.take(12)}… → ${uri ?: "(no uri)"}"
        } else {
            "[${now()}] store FAILED — publishing without content URI"
        }
    }

    fun recordPush(mode: ArtMode, uri: String?, dataBytes: Int?) {
        lastPush = "[${now()}] mode=${mode.prefValue} uri=${uri ?: "null"} data=${dataBytes?.let { "$it B" } ?: "null"}"
    }

    /** v0.6: which packages got an explicit read grant on the pushed art URI. */
    fun recordGrant(packages: List<String>) {
        lastGrant = if (packages.isEmpty()) {
            "[${now()}] none granted"
        } else {
            // Strip the common prefixes so the line fits a phone screen.
            "[${now()}] " + packages.joinToString(" ") {
                it.removePrefix("com.google.android.").removePrefix("com.android.")
            }
        }
    }

    fun recordProviderOpen(caller: String?, path: String, ok: Boolean) {
        lastProviderOpen =
            "[${now()}] ${caller ?: "unknown"} → $path ${if (ok) "OK" else "NOT FOUND"}"
    }

    /** The whole panel as monospace-friendly text. */
    fun render(): String = buildString {
        appendLine("fetch:     $lastFetch")
        appendLine("normalize: $lastNormalize")
        appendLine("store:     $lastStore")
        appendLine("push:      $lastPush")
        appendLine("grant:     $lastGrant")
        append("provider:  $lastProviderOpen")
    }
}
