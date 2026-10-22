# Widget and collapsed-player update (0.2.1)

## Scope

- One collapsed-player metadata/artwork tree during rapid skips, keyed by the current media ID so old text/artwork nodes are discarded. A 140 ms layer fade replaces overlapping slide transitions. Track identity follows the media-item transition payload.
- Collapsed playback clock: 500 ms polling; full player: 100 ms polling. Player events still update seeks and pause immediately. No display-frame callback for the audio-position clock.
- The transport uses `playWhenReady` and ended/idle state, so a tap during buffering can pause immediately. Decorative movement still follows actual playback.
- Playback movement waits between vsync callbacks and suspends with lifecycle/pause. Opening reveal shortened from 1450 to 700 ms. Underlying library drawing is suppressed while the full player covers it.
- Audiobook chapter lookup is indexed. Metadata uses per-item replacement; repeated snapshot requests are coalesced before copying/encoding the queue. Shutdown flushes the final queue before player release.
- Bookmark flow conflates notifications and decodes on IO. Periodic playback checkpoints run every five seconds; player events and shutdown preserve the final position. Abrupt process termination can lose up to five seconds.
- Widget rendering coalesces adjacent events over 32 ms and persists its small display snapshot on IO. Image decoding is bounded to 192 pixels and cached separately from transport updates. A native Chronometer avoids bitmap/UI rebuilds every second.
- Widget controls use a serialized MediaController connection and wait for the requested service state. Media3 releases pending commands before unbinding; see [official connection lifecycle documentation](https://developer.android.com/media/media3/session/connect-to-media-app).

## Measurement setup

Physical target: POCO 22101320G (redwood), Android 14, serial 14a47822; existing library contained 1730 songs. Optimized/R8 variant is used for frame and CPU comparisons; debug builds are only used for native functional tests. Reports are local ignored artifacts under `screenshots/performance/`.

Baseline on installed 0.2: four Library → Audiobooks → Playlists → Library navigation cycles yielded 87 frames / 3 missed deadlines (3.45%), p50 8 ms, p95 48 ms. A separate approximately ten-second home/mini-player playback sample yielded 331 frames / zero missed deadlines, p95 10 ms; steady `top` samples were 38.8% and 36.8% of one CPU core. These brief flows did not reproduce every reported intermittent lag. They do not establish a universal performance gain.

An isolated read-only API 34 emulator is used for the real AppWidgetHost and playback regressions. Its SwiftShader frame timings are not treated as phone-performance evidence. The widget host allocates/deletes only its own host ID, without changing the user's launcher layout. Test fixtures and relevant playback state are restored after the tests.

MIUI rejected installation of the separate test package on the physical phone. Main-APK updates succeeded. Physical-phone manual checks and emulator instrumentation must be reported separately.

The collapsed-player playback sample after the UI/clock changes had 342 frames / one missed deadline (0.29%), p50 7 ms, p95 11 ms, p99 14 ms; steady CPU samples were 29.0% and 29.8% of one core. The same 12 navigation taps yielded 83 frames / one missed deadline (1.20%), p95 25 ms. PSS snapshots were 139,971 KB before and 141,501 KB after; these samples do not demonstrate a memory reduction. Measurements are single short runs on the restored music queue, not a benchmark guarantee. The final persistence coalescing correction needs no changes to the measured collapsed-player rendering path.

Native widget lifecycle checks allow Android to retain a paused session through System UI bindings. Passive refresh must remain paused; when no service is retained it must also remain absent. The isolated host does not force-stop a process containing the test's in-memory restoration state.

## Native regression results

The final API 34 run passed all three scenarios in 88.451 seconds (`screenshots/performance/final-native-validation.txt`):

- `PlayerWidgetTest.realWidgetControlsArtworkResizeAndResume`: actual play/pause, rapid toggle ordering, next, independent red/blue artwork and metadata updates, persisted position, resume, compact/expanded resizing. Both layout screenshots were visually inspected.
- `DevicePlaybackTest.clockSeekPauseAndVerifiedDuplicateFiles`: four next/previous cycles through the collapsed player, current title, actual play/pause, opening the full player, artist/album return navigation, paused seeking, clock/lyrics, and duplicate-file verification.
- `AudiobookPlaybackTest.eachBookResumesAfterMusicAndChaptersAdvanceInOrder`: separate book bookmarks after switching to music, automatic chapter handoff, listened marks, and both sleep-timer modes.

UiAutomation's accessibility cache retained old node text/action labels even while the rendered button and Media3 state were correct. The test clears that cache on API 33+ before reading the tree; assertions still verify real service playback and current UI labels. The transport also exposes its action label on the clickable parent, independently of its animated decorative glyph.

Final local checks: 64 JVM tests passed; Android lint reports zero errors and 30 warnings. `git diff --check` is clean. Debug-only widget harness code is confined to the debug source set.

Final optimized artifact: `build/releases/MyMusic-0.2.1.apk`, version 0.2.1 / code 10, 4,952,094 bytes, SHA-256 `e54070006ec09d55095418c9b23637204b360c1a917866bba5336fed7f71c123`. The optimized manifest contains no debug harness and does not mark the app debuggable. Installation with `adb install -r` and on-device ART speed compilation both succeeded on the POCO; the installed package reports version 0.2.1 / code 10. Launch completed successfully (single cold-start sample: 946 ms; this is not a startup benchmark).
