# Artwork diagnostics (v0.5)

Field runbook for "album art doesn't show in my car" reports. Written for the
v0.5 Pixel investigation; keep using it for any future artwork report.

## Background — why v0.5 changed artwork delivery

Android Auto's documented artwork contract wants a **local `content://` URI**
(or a session bitmap). Through v0.4 the app published the cover's **remote
HTTPS URL** as `artworkUri`; older gearhead (Android Auto app) builds
permissively fetched it, newer builds — which reach Pixel phones first —
prefer the URI route and reject the unsupported scheme, so the card rendered
artless even though the inline bitmap bytes were right there in the session
metadata (media3 1.8 publishes BOTH, verified against `LegacyConversions`).

v0.5 therefore:

- normalizes every fetched cover to a ≤320 px baseline JPEG
  (`art/ArtNormalizer.kt`),
- persists it content-hash-keyed on disk (`art/ArtworkStore.kt`),
- serves it through a read-only exported provider (`art/ArtworkProvider.kt`,
  `content://com.powerpoppalace.subwaveauto.art/v1/<sha256>`),
- publishes that content URI as `artworkUri`, keeping the inline
  `artworkData` bytes for the notification and Bluetooth.

## v0.6 — explicit URI grants (Samsung / S24 reports)

v0.5's `content://` URI relies on the provider being exported and
world-readable. Field reports (S24 Ultra, v0.5.0) showed builds where the
card still rendered artless: those gearhead/OEM builds resolve the metadata
art URI under a URI-*grant* check and, when the open is denied, do **not**
fall back to the inline bitmap. v0.6 therefore:

- sets `android:grantUriPermissions="true"` on the provider, and
- explicitly `grantUriPermission(...)`s each pushed cover URI (read-only) to
  every connected session controller plus the known art consumers
  (`com.google.android.projection.gearhead`, `com.android.systemui`,
  `com.android.bluetooth`) *before* the URI is published.

The panel gained a `grant:` line showing which packages were granted on the
last push — `gearhead` should appear there whenever Android Auto is
connected. `grant: … gearhead` **plus** `provider: never` after a track
change means that build ignores the URI route entirely (flip to
**Bytes only** mode and report).

## The hidden diagnostics panel

Open the phone app and **tap the version line five times**. The panel shows:

1. **Artwork mode switch** — which artwork fields ride the session metadata.
   Applies from the next track change:
   - **Local URI + bytes (default)** — production (v0.5 behavior).
   - **Local URI only** — proves gearhead's content-URI route in isolation.
   - **Bytes only** — proves gearhead's session-bitmap route in isolation.
   - **Remote URL + bytes (v0.4)** — reproduces the old behavior for A/B.
2. **Pipeline readout** (updates every 2 s): fetch → normalize → store →
   push → provider. One screenshot pinpoints the failing stage.

### Interpreting a user's mode sweep

| Observation | Meaning |
| --- | --- |
| v0.4 mode fails, default works | Confirmed: gearhead was choosing the invalid HTTPS URI. Fix holds. |
| Bytes-only fails, Local-URI-only works | Their gearhead build requires the local-URI contract. |
| Provider line shows `com.google.android.projection.gearhead → … OK` but no art on glass | Gearhead fetched the file but won't render it — decode/render regression on their build; capture logs. |
| Push line shows a content URI but the provider line stays `never` | Gearhead ignored the artwork URI entirely — capture `dumpsys media_session`. |
| Fetch/normalize lines show failures | Problem is upstream of AA (network, station cover endpoint). |

## adb collection (ask an affected user)

While playing, with art expected on screen:

```bash
adb shell getprop ro.build.version.release
adb shell dumpsys package com.google.android.projection.gearhead | grep -E "versionName"
adb shell dumpsys package com.powerpoppalace.subwaveauto | grep -E "versionName"
adb shell dumpsys media_session > media_session.txt
```

In the SUB/WAVE session block of `media_session.txt`, look for
`android.media.metadata.ALBUM_ART_URI` / `ART_URI` / `DISPLAY_ICON_URI`
(should be the `content://…art/v1/<hash>` URI on v0.5) and whether a bitmap
(`ALBUM_ART` / `DISPLAY_ICON`) is listed.

- content URI + bitmap present, still artless → gearhead render issue; grab logs.
- URI present, bitmap absent → bitmap bridge/decode issue on that device.
- neither → the metadata push never reached the platform session.

Full log capture around one track change:

```bash
adb logcat -c
# play through one track change
adb logcat -d -v threadtime > subwave-art-logcat.txt
```

Search for: `MediaSessionLegacyStub`, `Failed to load bitmap`,
`BitmapFactory`, `TransactionTooLargeException`, `SecurityException`,
`FileNotFoundException`, `gearhead`.

## Quick local sanity check (any adb-connected phone)

The provider is exported, so its plumbing can be probed directly once a track
has pushed art (hash visible in the diagnostics panel's store line):

```bash
adb shell content read --uri content://com.powerpoppalace.subwaveauto.art/v1/<sha256> > cover.jpg
```

A valid JPEG lands in `cover.jpg`; `FileNotFoundException` means the store
evicted it or the hash is wrong.
