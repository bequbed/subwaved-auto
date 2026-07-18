package com.powerpoppalace.subwaveauto.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.powerpoppalace.subwaveauto.R
import com.powerpoppalace.subwaveauto.art.ArtDiagnostics
import com.powerpoppalace.subwaveauto.art.ArtMode
import com.powerpoppalace.subwaveauto.net.UpdateCheck
import com.powerpoppalace.subwaveauto.net.UpdateInfo
import com.powerpoppalace.subwaveauto.net.isNewerVersion
import com.powerpoppalace.subwaveauto.prefs.StationPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Minimal phone UI (WP4, plan §2) — deliberately spartan: the car is the product,
 * this screen is a remote. One Compose screen with a now-playing readout, a big
 * Play/Pause toggle, the station base-URL editor, and the Android Auto
 * unknown-sources hint.
 *
 * Playback itself lives in [com.powerpoppalace.subwaveauto.playback.PlaybackService];
 * this activity is only a [MediaController] onto that session, so phone UI, AA,
 * Bluetooth, and the media notification all drive the same player.
 */
class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null

    /** Compose-observable handle; null until the session connects (or if it fails). */
    private val controller = mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val token = SessionToken(this, ComponentName(this, PLAYBACK_SERVICE_CLASS))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                controller.value = try {
                    future.get()
                } catch (_: Exception) {
                    // Session connection failed (service missing/crashed). UI stays
                    // in the disconnected state; nothing to crash over.
                    null
                }
            },
            ContextCompat.getMainExecutor(this),
        )

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(controller.value)
                }
            }
        }
    }

    override fun onDestroy() {
        controller.value = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onDestroy()
    }

    private companion object {
        /**
         * String-based ComponentName on purpose: WP2's PlaybackService is authored
         * in parallel, so we avoid a compile-time dependency on the class reference.
         * The name is a §1 shared contract (package root + playback/PlaybackService).
         */
        const val PLAYBACK_SERVICE_CLASS =
            "com.powerpoppalace.subwaveauto.playback.PlaybackService"
    }
}

/** Snapshot-state mirror of the controller's playback state for Compose. */
private class PlayerUiState {
    var title by mutableStateOf<String?>(null)
    var artist by mutableStateOf<String?>(null)
    var isPlaying by mutableStateOf(false)
}

/**
 * Observes [controller] via a [Player.Listener] and mirrors title/artist/isPlaying
 * into Compose state. Listener is removed when the controller changes or the
 * composition leaves.
 */
@Composable
private fun rememberPlayerUiState(controller: MediaController?): PlayerUiState {
    val state = remember { PlayerUiState() }
    DisposableEffect(controller) {
        if (controller == null) {
            state.title = null
            state.artist = null
            state.isPlaying = false
            onDispose {}
        } else {
            fun sync() {
                state.title = controller.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
                state.artist = controller.mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() }
                state.isPlaying = controller.isPlaying
            }

            val listener = object : Player.Listener {
                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = sync()
                override fun onIsPlayingChanged(isPlaying: Boolean) = sync()
                override fun onPlaybackStateChanged(playbackState: Int) = sync()
            }
            sync()
            controller.addListener(listener)
            onDispose { controller.removeListener(listener) }
        }
    }
    return state
}

@Composable
private fun MainScreen(controller: MediaController?) {
    val context = LocalContext.current
    val player = rememberPlayerUiState(controller)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    NotificationPermissionRequest()

    var urlInput by rememberSaveable { mutableStateOf(StationPrefs.baseUrl(context)) }
    var urlError by rememberSaveable { mutableStateOf(false) }
    val savedMessage = stringResource(R.string.station_url_saved)

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
            )

            Spacer(Modifier.height(40.dp))

            // Now playing
            Text(
                text = player.title ?: stringResource(R.string.now_playing_empty),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = player.artist ?: stringResource(R.string.now_playing_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(40.dp))

            // Big Play/Pause toggle. play() on a fresh (idle) controller is enough:
            // the service's ForwardingPlayer resolves a fresh cache-busted live item
            // and prepares it (plan §2 WP2 live-edge rule).
            Button(
                onClick = {
                    val c = controller ?: return@Button
                    if (c.isPlaying) c.pause() else c.play()
                },
                enabled = controller != null,
                modifier = Modifier.size(width = 220.dp, height = 80.dp),
            ) {
                Text(
                    text = stringResource(if (player.isPlaying) R.string.pause else R.string.play),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            if (controller == null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.connecting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(48.dp))

            // Station base URL
            OutlinedTextField(
                value = urlInput,
                onValueChange = {
                    urlInput = it
                    urlError = false
                },
                label = { Text(stringResource(R.string.station_url_label)) },
                singleLine = true,
                isError = urlError,
                supportingText = if (urlError) {
                    { Text(stringResource(R.string.station_url_error)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    // StationPrefs.setBaseUrl silently ignores invalid input, so
                    // detect acceptance by re-reading: on success the stored value
                    // equals the normalized (trimmed, no trailing '/') input —
                    // including the "re-saved the current URL" case, which is fine.
                    StationPrefs.setBaseUrl(context, urlInput)
                    val normalizedInput = urlInput.trim().trimEnd('/')
                    val stored = StationPrefs.baseUrl(context)
                    if (normalizedInput.isNotEmpty() && stored == normalizedInput) {
                        urlError = false
                        urlInput = stored
                        scope.launch { snackbarHostState.showSnackbar(savedMessage) }
                    } else {
                        urlError = true
                    }
                }) {
                    Text(stringResource(R.string.save))
                }
            }

            Spacer(Modifier.height(48.dp))

            // Android Auto sideload hint
            Text(
                text = stringResource(R.string.aa_unknown_sources_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            // App version — the first question in any field report ("which version
            // are you on?"), answerable without digging through Android settings.
            // Sideload distribution has no update channel, so this is the only
            // fast way to tell a stale install from a real bug.
            // v0.5: five taps here toggles the hidden artwork-diagnostics panel
            // (the field tool for the Pixel artwork reports).
            var versionTaps by rememberSaveable { mutableStateOf(0) }
            var diagnosticsVisible by rememberSaveable { mutableStateOf(false) }
            appVersionName(context)?.let { version ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.app_version, version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable {
                        versionTaps++
                        if (versionTaps >= 5) {
                            versionTaps = 0
                            diagnosticsVisible = !diagnosticsVisible
                        }
                    },
                )
            }

            UpdateNotice()

            if (diagnosticsVisible) {
                Spacer(Modifier.height(16.dp))
                ArtDiagnosticsPanel()
            }
        }
    }
}

/**
 * "Update available" line under the version (v0.7, plan Tier-1 item 3) — the
 * only update channel a sideloaded app has. Checks the repo's GitHub Releases
 * once per screen entry; renders NOTHING unless a strictly newer release
 * exists (offline / no releases / malformed tag all stay silent — best-effort
 * only). Tapping opens the release page in the browser to download the APK.
 */
@Composable
private fun UpdateNotice() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    LaunchedEffect(Unit) {
        val current = appVersionName(context) ?: return@LaunchedEffect
        val latest = UpdateCheck.latestRelease() ?: return@LaunchedEffect
        if (isNewerVersion(latest.tag, current)) update = latest
    }
    update?.let { u ->
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.update_available, u.tag),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable {
                runCatching { uriHandler.openUri(u.url) }
            },
        )
    }
}

/**
 * Hidden artwork-diagnostics panel (v0.5) — reached by tapping the version line
 * five times. Two jobs, both for diagnosing artless Android Auto reports
 * remotely: (1) switch the artwork PUBLISH MODE (which combination of
 * content:// URI / inline bytes / remote URL rides the session metadata) so an
 * affected user can tell us which route their gearhead build honors, and
 * (2) show the live pipeline readout (fetch → normalize → store → push →
 * provider) so one screenshot pinpoints the failing stage. Debug-only surface:
 * literals instead of string resources on purpose.
 */
@Composable
private fun ArtDiagnosticsPanel() {
    val context = LocalContext.current
    var mode by rememberSaveable { mutableStateOf(ArtMode.fromPref(StationPrefs.artMode(context)).prefValue) }
    var readout by remember { mutableStateOf(ArtDiagnostics.render()) }
    LaunchedEffect(Unit) {
        while (true) {
            readout = ArtDiagnostics.render()
            delay(2_000)
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Artwork diagnostics", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        ArtMode.entries.forEach { m ->
            Text(
                text = (if (m.prefValue == mode) "● " else "○ ") + m.label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        StationPrefs.setArtMode(context, m.prefValue)
                        mode = m.prefValue
                    }
                    .padding(vertical = 6.dp),
            )
        }
        Text(
            text = "Mode applies from the next track change.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = readout,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * The installed versionName via PackageManager (no BuildConfig dependency —
 * the buildConfig feature isn't enabled). Null only if the package manager
 * can't see our own package, which should never happen.
 */
private fun appVersionName(context: Context): String? = try {
    @Suppress("DEPRECATION") // getPackageInfo(String, Int): fine for our own package
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
} catch (_: Exception) {
    null
}

/**
 * Asks for POST_NOTIFICATIONS once on first launch (API 33+ — without it the media
 * notification is invisible on Android 13+). The "asked" flag lives in its own
 * "ui" prefs file (NOT StationPrefs' "station" file). Denial is respected: no nagging.
 */
@Composable
private fun NotificationPermissionRequest() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* denial accepted — never re-prompt */ }

    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences(UI_PREFS_FILE, Context.MODE_PRIVATE)
        val alreadyAsked = prefs.getBoolean(KEY_NOTIF_PERMISSION_ASKED, false)
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!alreadyAsked && !granted) {
            prefs.edit().putBoolean(KEY_NOTIF_PERMISSION_ASKED, true).apply()
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private const val UI_PREFS_FILE = "ui"
private const val KEY_NOTIF_PERMISSION_ASKED = "notifPermissionAsked"
