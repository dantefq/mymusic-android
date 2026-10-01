# Device validation — 2026-10-01

Tested on the connected Samsung Galaxy A51 (SM-A515F), Android 13, Russian locale. The updated, R8-optimized `performance` APK is installed. Playback was left paused after testing.

## Functional checks

- 22 JVM tests passed; Android lint reports zero errors (35 warnings).
- `DevicePlaybackTest` passed on the phone. It checks playback and paused seeks, clock synchronization, duplicate fixtures, song-title and artist-credit taps in the general list, opening the full player from the mini-player, full-player artist/album navigation, Back, and accessible seeking. Temporary tracks are removed and the previous queue restored.
- Repeated the list, mini-player, artist, album, and Back interactions manually through ADB in the optimized build using existing library tracks. The library returned to its three original songs after fixture cleanup.
- Repeated full-player open/close six times in the optimized build. No application crash was recorded during these checks.

## Rendering measurements

ADB `dumpsys gfxinfo` snapshots are saved locally under the ignored `screenshots/device-test/` directory. Measurements concern this phone and these scenarios, rather than large-library performance.

| Scenario | Frames / sample duration | Observations |
| --- | --- | --- |
| Steady full-player playback before pixel-based progress invalidation | 1,543 / 25.70 s (~60/s) | Median 25 ms, p99 25 ms |
| Steady full-player playback afterward | 169 / 17.46 s (~9.7/s) | Median 26 ms, p99 42 ms |
| Six paused full-player open/close cycles afterward | 366 / 9.33 s | 8 reported janky frames (2.19%); p99 89 ms |

The progress fix cuts steady redraw frequency by about 84%; it does not demonstrate faster individual GPU frames. Android reported many deadline misses during sparse playback redraws, and occasional transition spikes remain. The idle-playback counter is not comparable to the transition counter. Broader animation profiling and large-library testing remain useful.

The changes also move library indexing, sorting, queue preparation, and queue serialization off the UI thread, preserve the underlying layout while the full player opens, and remove repeated artwork fallback drawing and abrupt size animation.
