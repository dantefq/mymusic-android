# MyMusic

An offline-first Android music player built with Kotlin, Jetpack Compose, Media3, and Room. Minimum Android version: 10 (API 29).

## Build and install

1. Open the repository in Android Studio with JDK 17+ and Android SDK 35.
2. Sync Gradle, then run the `app` configuration on an Android device.
3. Grant audio access when prompted so the library can scan local files.

From a terminal, run `./gradlew :app:assembleDebug` (or `gradlew.bat :app:assembleDebug` on Windows). The APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

Metadata lookup is optional and uses MusicBrainz, Cover Art Archive, and LRCLIB. No API credentials are required.

## Library and playback

- MediaStore scans local tracks; Media3 provides background playback, audio focus, a media notification, and lock-screen controls.
- The library has search, a lossless filter, a mini-player, an expanded player, a queue, and an output-device picker. Swipe up on the mini-player, down on the full player, or sideways on album art. Long-press the mini-player to open the queue.
- **Smart play** starts an automatically generated queue using local listening data. Search playback continues with random local music. Both queues refill in the playback service.
- Home shows independently expandable All Tracks, Artists, and Genres sections. Tracks can be sorted by name, date added, artist, duration, or album, with an optional lossless filter.
- Track options open album and artist pages. Albums use MediaStore album IDs and album tags; explicit collaboration credits appear under each credited artist.
- The last queue, track, and position are restored on launch, paused until you press play. Listening stats show the top tracks and artists for the last week or month.
- Playlists support adding songs, long-press drag reordering, and sideways swipe removal. A local `playlists-backup.json` is rewritten when playlists change, with a second copy in `Downloads/MyMusic` that survives uninstall. Settings can export or restore it through Android's document picker.
- The duplicate finder computes SHA-256 over file contents and groups exact matches. Android asks for confirmation before deleting a file.
- The home-screen widget displays current track information and artwork with previous, play/pause, and next buttons.

## Settings and metadata

Embedded and local metadata take priority. MusicBrainz and Cover Art Archive can fill missing metadata and artwork; results are cached in Room and local storage. LRCLIB supplies lyrics; timestamped lines auto-scroll and can be tapped to seek. Online matches are not guaranteed.

The UI has English and Russian resources. Settings lets you choose the system language, English, or Russian. Appearance settings provide custom accent/background colors and Editorial, Modern, Rounded, and Mono fonts. Settings also contains a sleep timer, crossfade duration, playlist backup controls, and duplicate scanning.

The equalizer and BassBoost use Android's audio effects on the active Media3 audio session. Availability and band count vary by device.

## Verification and limits

`./gradlew :app:testDebugUnitTest` runs local tests for smart mixes, random continuation, album/artist grouping, and sorting. The current redesign builds and passes all seven unit tests. Physical-device verification of this redesign is pending; an earlier version was smoke-tested on a Samsung Galaxy A51 (Android 13).

Output routing depends on devices and routes offered by Android. SHA-256 duplicate detection identifies byte-identical files, so different encodings of the same audio are not reported as duplicates. Artist splitting recognizes explicit credit separators such as `feat.`, `ft.`, and semicolons; ambiguous punctuation in artist names is preserved.

Approved redesign references are in [design/](design/).
