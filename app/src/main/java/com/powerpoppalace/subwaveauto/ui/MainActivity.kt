package com.powerpoppalace.subwaveauto.ui

import android.Manifest
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
import com.powerpoppalace.subwaveauto.net.StationApi
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

    /**
     * v0.8.1: pending MEDIA_PLAY_FROM_SEARCH voice launch ("play X on SUB/WAVE
     * Auto" deep-linked by Assistant/Gemini). Consumed by MainScreen once the
     * controller connects: playback starts, and a non-blank query is submitted
     * to the station as a song request — mirroring the in-car voice path.
     * The value distinguishes "launch with a query" from "no voice launch";
     * a blank query (bare "play some music") still starts playback.
     */
    private val voiceLaunch = mutableStateOf<VoiceLaunch?>(null)

    /** One captured voice launch; [query] null when the assistant sent none. */
    class VoiceLaunch(val query: String?)

    /**
     * v0.10: launched via the "Request a song" launcher shortcut — MainScreen
     * opens the speech dialog immediately, making a voice request ONE tap from
     * the home screen (transcript then auto-sends after the countdown).
     */
    private val micLaunch = mutableStateOf(false)

    private fun captureVoiceLaunch(intent: Intent?) {
        when (intent?.action) {
            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> {
                val query = intent.getStringExtra(SearchManager.QUERY)?.trim()?.takeIf { it.isNotEmpty() }
                voiceLaunch.value = VoiceLaunch(query)
            }
            ACTION_VOICE_REQUEST -> micLaunch.value = true
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        captureVoiceLaunch(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureVoiceLaunch(intent)

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
                    MainScreen(
                        controller = controller.value,
                        voiceLaunch = voiceLaunch.value,
                        onVoiceLaunchConsumed = { voiceLaunch.value = null },
                        micLaunch = micLaunch.value,
                        onMicLaunchConsumed = { micLaunch.value = false },
                    )
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

        /** v0.10: intent action fired by the "Request a song" launcher shortcut. */
        const val ACTION_VOICE_REQUEST = "com.powerpoppalace.subwaveauto.VOICE_REQUEST"
    }
}

/** Snapshot-state mirror of the controller's playback state for Compose. */
private class PlayerUiState {
    var title by mutableStateOf<String?>(null)
    var artist by mutableStateOf<String?>(null)
    var isPlaying by mutableStateOf(false)

    /** v0.8: current cover decoded from the session's artworkData (≤320 px JPEG). */
    var artwork by mutableStateOf<ImageBitmap?>(null)

    /** Bytes behind [artwork] — identity check so repaints skip the re-decode. */
    var artworkBytes: ByteArray? = null
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
                // v0.8 cover on the phone screen: decode the session's inline
                // bytes (the normalizer caps them at 320 px, so this is a cheap
                // decode), skipping when the bytes haven't changed.
                val bytes = controller.mediaMetadata.artworkData
                if (!bytes.contentEquals(state.artworkBytes)) {
                    state.artworkBytes = bytes
                    state.artwork = bytes?.let {
                        BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
                    }
                }
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
private fun MainScreen(
    controller: MediaController?,
    voiceLaunch: MainActivity.VoiceLaunch? = null,
    onVoiceLaunchConsumed: () -> Unit = {},
    micLaunch: Boolean = false,
    onMicLaunchConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val player = rememberPlayerUiState(controller)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    NotificationPermissionRequest()

    // v0.8.1: a MEDIA_PLAY_FROM_SEARCH voice launch. Waits for the controller
    // (both keys re-run the effect), then: start playback, and submit a
    // non-blank query as a song request — the DJ answers on air, and the
    // snackbar echoes the server's reply.
    val voiceRequestFallback = stringResource(R.string.request_sent)
    LaunchedEffect(controller, voiceLaunch) {
        val launch = voiceLaunch ?: return@LaunchedEffect
        val c = controller ?: return@LaunchedEffect
        onVoiceLaunchConsumed()
        c.play()
        val query = launch.query ?: return@LaunchedEffect
        val reply = StationApi(StationPrefs.baseUrl(context))
            .postRequest(query, StationPrefs.listenerName(context))
        snackbarHostState.showSnackbar(reply?.message ?: voiceRequestFallback)
    }

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

            Spacer(Modifier.height(24.dp))

            // v0.8: cover art, straight from the session metadata the car renders.
            player.artwork?.let { art ->
                Image(
                    bitmap = art,
                    contentDescription = null,
                    modifier = Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(16.dp)),
                )
                Spacer(Modifier.height(16.dp))
            }

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

            // v0.8: DJ persona + listener count, polled from /api/now-playing
            // while playing (the session metadata doesn't carry them).
            StationInfoLine(isPlaying = player.isPlaying)

            // v0.11: like the on-air track (heart + live count).
            LikeButton(isPlaying = player.isPlaying, trackKey = player.title to player.artist)

            Spacer(Modifier.height(32.dp))

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

            Spacer(Modifier.height(32.dp))

            // v0.8: song requests — the box the Discord folks asked for. In the
            // car, "Hey Google, play <anything> on SUB/WAVE Auto" does the same.
            RequestCard(autoMic = micLaunch, onAutoMicConsumed = onMicLaunchConsumed)

            Spacer(Modifier.height(40.dp))

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
            // v0.8: post-save connection test — a typo'd address used to fail
            // SILENTLY (URL shape was all we checked). Now a successful save
            // pings /api/now-playing and reports the station it found (or a
            // clear failure) right under the field.
            var connStatus by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
            val connFailText = stringResource(R.string.conn_fail)
            val connTestingText = stringResource(R.string.conn_testing)
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
                        connStatus = Pair(true, connTestingText)
                        scope.launch {
                            val np = StationApi(stored).nowPlaying()
                            connStatus = if (np == null) {
                                Pair(false, connFailText)
                            } else {
                                val station = np.stationName ?: "station"
                                val track = listOfNotNull(np.artist, np.title).joinToString(" — ")
                                Pair(true, "✓ $station" + if (track.isNotEmpty()) " · $track" else "")
                            }
                        }
                    } else {
                        urlError = true
                        connStatus = null
                    }
                }) {
                    Text(stringResource(R.string.save))
                }
            }
            connStatus?.let { (ok, text) ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(48.dp))

            // Android Auto sideload checklist (v0.8: numbered steps, left-aligned)
            Text(
                text = stringResource(R.string.aa_unknown_sources_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
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
 * v0.8: "DJ Frequency · 3 listening" under the artist line. The session
 * metadata can't carry these, so this polls `/api/now-playing` every 15 s
 * while playing (same endpoint the service polls anyway — the station already
 * serves it per-listener every 5 s, so this adds nothing meaningful). Hidden
 * entirely while paused or when the payload lacks both fields.
 */
@Composable
private fun StationInfoLine(isPlaying: Boolean) {
    val context = LocalContext.current
    var line by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            line = null
            return@LaunchedEffect
        }
        while (true) {
            val np = StationApi(StationPrefs.baseUrl(context)).nowPlaying()
            line = np?.let {
                listOfNotNull(
                    it.djName?.let { d -> "DJ $d" },
                    it.listeners?.let { n -> if (n == 1) "1 listening" else "$n listening" },
                ).joinToString(" · ").takeIf { s -> s.isNotEmpty() }
            }
            delay(15_000)
        }
    }
    line?.let {
        Spacer(Modifier.height(6.dp))
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * v0.11: "Like this song" — a heart with the live like-count, backed by the
 * station's `/api/like`. Loads state on entry and whenever the track changes
 * ([trackKey]); a tap likes the on-air song and reflects the server's updated
 * count. Hidden entirely when the station has likes disabled or nothing is
 * likeable (a DJ break). Best-effort: any network hiccup just hides it.
 */
@Composable
private fun LikeButton(isPlaying: Boolean, trackKey: Pair<String?, String?>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<StationApi.LikeState?>(null) }
    var busy by remember { mutableStateOf(false) }

    // (Re)load on track change while playing. Paused → clear (avoid a stale heart).
    LaunchedEffect(isPlaying, trackKey) {
        state = if (isPlaying) StationApi(StationPrefs.baseUrl(context)).likeState() else null
    }

    val s = state
    if (s == null || !s.enabled || s.songId == null) return

    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            enabled = !busy && !s.liked,
            onClick = {
                busy = true
                scope.launch {
                    try {
                        val updated = StationApi(StationPrefs.baseUrl(context)).like(s.songId)
                        if (updated != null) state = updated
                    } finally {
                        busy = false
                    }
                }
            },
        ) {
            // Filled heart once liked; outline until then. Emoji dodges an
            // icon-pack dependency, matching the 🎤 button's approach.
            Text(if (s.liked) "❤️" else "🤍")
        }
        Spacer(Modifier.size(10.dp))
        Text(
            text = if (s.count == 1) "1 like" else "${s.count} likes",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * v0.8: song-request box (the Discord ask). Plain HTTP to `POST /api/request` —
 * works whether or not playback is running. Shows the server's human reply
 * (the DJ's ack once resolved; rate-limit / requests-closed messages read
 * as-is), polling the request id briefly while the background resolver works.
 * The name persists (StationPrefs) and doubles as the name attached to
 * in-car voice requests.
 */
@Composable
private fun RequestCard(
    autoMic: Boolean = false,
    onAutoMicConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(StationPrefs.listenerName(context)) }
    var text by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    // v0.9: seconds left on the auto-send countdown, or null when idle. Armed
    // by a successful voice transcription; Cancel (or editing to empty) disarms.
    var countdown by remember { mutableStateOf<Int?>(null) }
    val offlineText = stringResource(R.string.request_offline)
    val sentText = stringResource(R.string.request_sent)
    val voiceUnavailableText = stringResource(R.string.voice_unavailable)

    // One shared submit path for the Send button and the countdown expiry.
    fun send() {
        val body = text.trim()
        if (body.isEmpty() || sending) return
        countdown = null
        StationPrefs.setListenerName(context, name)
        sending = true
        status = null
        scope.launch {
            try {
                val api = StationApi(StationPrefs.baseUrl(context))
                // Elvis-bind so `current` is non-null by declaration —
                // the smart cast from a plain null-check doesn't carry
                // into the var's inferred type.
                var current: StationApi.RequestResult = api.postRequest(body, name) ?: run {
                    status = offlineText
                    return@launch
                }
                status = current.message ?: sentText
                // Brief poll while the background resolver works, so
                // the DJ's real ack ("Queued: …") replaces the generic
                // "got it". Bounded — the on-air answer is the real UX.
                var polls = 0
                while (current.pending && polls < 10) {
                    val id = current.id ?: break
                    delay(3_000)
                    val next = api.pollRequest(id) ?: break
                    next.message?.let { status = it }
                    current = next
                    polls++
                }
                if (current.success) text = ""
            } finally {
                sending = false
            }
        }
    }

    // v0.9 countdown ticker: arms when countdown becomes non-null, ticks down
    // once a second, fires send() at zero. Setting countdown = null (Cancel)
    // stops it; a re-arm while active just continues from the new value.
    LaunchedEffect(countdown != null) {
        while (countdown != null) {
            val c = countdown ?: break
            if (c <= 0) {
                countdown = null
                send()
                break
            }
            delay(1_000)
            countdown = countdown?.minus(1)
        }
    }

    // v0.9 one-tap voice request: system speech recognizer (no audio
    // permission needed — the OS-provided dialog records) → transcript into
    // the box → auto-send after the visible countdown.
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val transcript = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()?.trim()
        if (!transcript.isNullOrEmpty()) {
            text = transcript.take(StationApi.MAX_REQUEST_TEXT)
            status = null
            countdown = VOICE_AUTO_SEND_SECONDS
        }
    }

    fun launchSpeech() {
        try {
            speechLauncher.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(
                        RecognizerIntent.EXTRA_PROMPT,
                        context.getString(R.string.request_hint),
                    )
                },
            )
        } catch (_: ActivityNotFoundException) {
            status = voiceUnavailableText
        }
    }

    // v0.10: launched via the "Request a song" home-screen shortcut — go
    // straight into the speech dialog. One tap → speak → countdown → sent.
    LaunchedEffect(autoMic) {
        if (autoMic) {
            onAutoMicConsumed()
            launchSpeech()
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.request_section_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it.take(StationApi.MAX_REQUEST_TEXT)
                    // Typing over a pending voice transcript implies the user
                    // wants control back — disarm the auto-send.
                    countdown = null
                },
                label = { Text(stringResource(R.string.request_hint)) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            // 🎤 one-tap voice request (v0.9). Button+emoji on purpose:
            // no icon-pack dependency for one glyph in a spartan UI.
            Button(
                enabled = !sending,
                onClick = { launchSpeech() },
            ) {
                Text(stringResource(R.string.request_mic))
            }
        }
        // Countdown bar: visible arming state + the escape hatch the feedback
        // asked for ("allowing time to cancel if needed").
        countdown?.let { c ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.request_sending_in, c),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { countdown = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(StationPrefs.LISTENER_NAME_MAX) },
                label = { Text(stringResource(R.string.request_name_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            Button(
                enabled = !sending && text.isNotBlank(),
                onClick = { send() },
            ) {
                Text(stringResource(if (sending) R.string.request_sending else R.string.request_send))
            }
        }
        status?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** v0.9: seconds a voice transcript waits (visibly, cancellable) before auto-send. */
private const val VOICE_AUTO_SEND_SECONDS = 5

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
