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

No local Android toolchain needed — every push to this branch is built and
signed by CI. Grab the latest straight off the branch:

- [subwave-auto-0.6.1.apk](https://github.com/bequbed/subwaved-auto/raw/claude/android-auto-album-art-9s0h4u/dist/subwave-auto-0.6.1.apk) — derives the cover-art URL from `subsonic_id` when the station payload has no explicit art field
- [subwave-auto-0.6.0.apk](https://github.com/bequbed/subwaved-auto/raw/claude/android-auto-album-art-9s0h4u/dist/subwave-auto-0.6.0.apk) — grants Android Auto explicit read access to the cover-art content URI

Each build replaces the previous one's signing key (CI uses a throwaway debug
key per run), so installing a new version over an old one needs an uninstall
first — see [docs/SIGNING.md](docs/SIGNING.md) if you want stable in-place
updates instead. Install steps: [docs/SIDELOAD_RUNBOOK.md](docs/SIDELOAD_RUNBOOK.md).

These aren't on the repo's **Releases** page — this session's GitHub access
can push branch commits but not tags, and has no Releases-write scope. The
`.github/workflows/build-apk.yml` workflow is ready for it though: pushing a
`v0.6.0/v0.6.1`-style tag (`git tag v0.6.1 <commit> && git push origin
v0.6.1`) from a machine with normal push access will build that commit and
publish it as a Release with the APK attached automatically.

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
