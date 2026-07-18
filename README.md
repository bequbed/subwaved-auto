# SUB/WAVE Auto

Sideloaded **Android Auto companion app** for SUB/WAVE: one live station,
play/pause only, with live title/artist/cover metadata and station branding on
the car screen. Deliberately **not** Play-distributed — it exists to get the
station into Android Auto (and onto Bluetooth AVRCP displays) without the Play
Store, using AA's developer "Unknown sources" toggle.

## Relation to SUB/WAVE

A companion to [SUB/WAVE](https://github.com/perminder-klair/subwave) — the
self-hosted AI-DJ internet radio station. This is an **independent
Kotlin/Gradle project** with zero coupling to SUB/WAVE's Expo mobile app
(different package id; no shared code or
build) and **zero server-side changes**: it consumes any station's existing
public API (`/stream.mp3`, `/api/now-playing`). Point it at your own SUB/WAVE
instance via the in-app station URL.

Developed as a top-level subproject inside a station operator's fork and
extracted here as a standalone repo (MIT, matching upstream). Offered for
adoption — it would drop into the main repo as a subdirectory unchanged, and a
Play Store release under the project's own account would give every SUB/WAVE
operator Android Auto without the sideload steps.

## Download a prebuilt APK

**[⬇ Releases page](https://github.com/bequbed/subwaved-auto/releases)** —
each version is built by CI and attached there as a ready-to-sideload APK.
Install steps: [docs/SIDELOAD_RUNBOOK.md](docs/SIDELOAD_RUNBOOK.md).

From v0.7.0, release builds are signed with a stable key (kept in CI
secrets), so newer versions install straight over older ones — no
uninstall needed. Upgrading from a pre-0.7 build requires one final
uninstall/reinstall to switch onto the stable key. The app checks the
Releases page on launch and shows a tap-to-download line when a newer
version exists.

Releases are cut automatically: bump `versionName` in
`app/build.gradle.kts`, push, and CI builds, signs, and publishes the
matching `vX.Y.Z` Release with no further steps.

Development builds (every push to a working branch) also land in
[`dist/`](dist/) on that branch; those may be debug-signed — prefer the
Releases page.

## Song requests (v0.8)

Requests go to the station's `POST /api/request` and the AI DJ answers **on
air** — no screen time needed while driving:

- **In the car (voice):** "Hey Google, play *Mr. Roboto* on SUB/WAVE Auto" —
  the live stream keeps playing and the request is submitted. AA's search box
  works too: type anything, tap the single "Request: …" result.
- **On the phone:** the "Request a song" box shows the DJ's reply in-app.
  The optional name you set there persists and rides along with in-car
  voice requests (otherwise the station shows "anon").
- **One-tap voice (v0.9):** the 🎤 button next to the request box opens
  system speech input; the transcript auto-sends after a visible 5-second
  countdown with a Cancel button.
- **Steering-wheel voice button:** Android reserves it for the system
  assistant — apps can't capture it directly. It still works indirectly:
  press it and say "play *song* on SUB/WAVE Auto".

## Quickstart

```
scripts\build-release.ps1   # or scripts/build-release.sh
scripts\install.ps1         # or scripts/install.sh
```

Then the one-time phone setup (USB debugging, AA unknown sources, force-stop
AA): **[docs/SIDELOAD_RUNBOOK.md](docs/SIDELOAD_RUNBOOK.md)** — the full
operator path from build to "visible in the car".

## Testing

- **[docs/ANDROID_AUTO_TESTING.md](docs/ANDROID_AUTO_TESTING.md)** — the
  10-row acceptance matrix with results.
- **[docs/DHU_NOTES.md](docs/DHU_NOTES.md)** — testing AA at the desk with the
  Desktop Head Unit (install facts, launch order, gotchas).
- **[docs/ARTWORK_DIAGNOSTICS.md](docs/ARTWORK_DIAGNOSTICS.md)** — field
  runbook for "no album art in the car" reports: the hidden diagnostics panel
  (tap the version line 5×), the artwork-mode A/B switch, and the adb
  collection steps.

## Signing

**[docs/SIGNING.md](docs/SIGNING.md)** — release keystore, the
same-signature upgrade rule, and backup instructions. Back the keystore up;
it exists nowhere else.

## Architecture (~20 files, deliberately boring)

- `playback/PlaybackService.kt` — `MediaLibraryService` + media3 ExoPlayer; one
  session serves AA, Bluetooth, the notification, and the phone UI.
- **Live-edge rule** — a `ForwardingPlayer` intercepts play after a stale pause
  (> 30 s, or IDLE/ENDED) and reloads a fresh cache-busted stream item instead
  of resuming a stale buffer; it also strips all seek/skip commands.
- `playback/BrowseTree.kt` — the AA browse tree: root → one playable live item
  (`onAddMediaItems` resolves bare mediaIds to a cache-busted stream URI).
- `playback/LiveMetadata.kt` — polls `/api/now-playing` every 5 s while
  playing; swaps title/artist/album/cover into the session with no audio gap.
- `net/StationApi.kt` (+ `prefs/StationPrefs.kt`) — HTTP client for the
  station API; user-editable base URL (https-required except private hosts).
- `ui/MainActivity.kt` — minimal Compose phone UI: play/pause, now-playing,
  base-URL setting, and the AA unknown-sources hint.

Full design, contracts, and work-package history: **`ANDROID_AUTO_PLAN.md`**
at the repo root.

## v1 scope cuts (see plan §5)

One station only (a single configurable base URL — no multi-station browse
tree), no recurring specials, no Play Store distribution, no CarPlay, no voice
"Hey Google" actions, no Android Automotive OS.
