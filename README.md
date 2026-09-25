# MyMusic

An offline-first Android music player built with Kotlin, Jetpack Compose, Media3, and Room. Minimum Android version: 10 (API 29).

## Build and install

1. Open the repository in Android Studio with JDK 17+ and Android SDK 35.
2. Sync Gradle, then run the `app` configuration on an Android device.
3. Grant audio access when prompted so the library can scan local files.

From a terminal, run `./gradlew :app:assembleDebug` (or `gradlew.bat :app:assembleDebug` on Windows). The APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

Spotify is optional. Enter an access token or your own app client ID and secret in Settings. Credentials are stored on the device and are never committed to the repository.

## Library and playback

- MediaStore scans local tracks; Media3 provides background playback, audio focus, a media notification, and lock-screen controls.
- The library has search, a lossless filter, a mini-player, an expanded player, a queue, and an output-device picker. Swipe up on the mini-player, down on the full player, or sideways on album art. Long-press the mini-player to open the queue.
- **For You** ranks tracks using local play counts, recently added music, and genres. Genres are cached in Room. Listening stats show the top tracks and artists for the last week or month.
- Playlists support adding songs, long-press drag reordering, and sideways swipe removal. A local `playlists-backup.json` is rewritten when playlists change, with a second copy in `Downloads/MyMusic` that survives uninstall. Settings can export or restore it through Android's document picker.
- The duplicate finder computes SHA-256 over file contents and groups exact matches. Android asks for confirmation before deleting a file.
- The home-screen widget displays current track information and artwork with previous, play/pause, and next buttons.

## Settings and metadata

Enter a Spotify access token, or a Spotify client ID and secret, in **Settings**. The app validates the credentials against Spotify and saves them with EncryptedSharedPreferences. No Gradle or local properties edit is needed. Spotify supplies matched title, artist, artwork, and the artist's primary genre. Tap **Fetch genres** in the Genres tab to enrich uncached local tracks. LRCLIB supplies lyrics independently of Spotify; timestamped lines auto-scroll and can be tapped to seek.

The UI has English and Russian resources. Settings lets you choose the system language, English, or Russian. Settings also contains a sleep timer, crossfade duration, playlist backup controls, and duplicate scanning.

## FLAC conversion

On a WAV or ALAC track, open the player and tap **Convert to verified FLAC**. The converter accepts mono/stereo integer PCM at 16 or 24 bits. ALAC conversion requires a device codec that extracts ALAC and outputs the original integer bit depth. Other formats, floating-point WAV, multichannel audio, and decoders that reduce bit depth are rejected. Conversion compares SHA-256 of the decoded PCM with the generated FLAC's decoded PCM before publishing it in `Music/MyMusic`. This verifies the PCM delivered by the device ALAC decoder; it does not independently verify the ALAC bitstream.

The equalizer and BassBoost use Android's audio effects on the active Media3 audio session. Availability and band count vary by device.

## Verification and limits

`./gradlew :app:testDebugUnitTest` runs local tests. The debug app has also been installed and smoke-tested on a Samsung Galaxy A51 (Android 13). Spotify enrichment requires user-provided credentials and internet access; lyrics require an LRCLIB match. Output routing depends on devices and routes offered by Android. SHA-256 duplicate detection identifies byte-identical files, so two different encodings of the same audio are not reported as duplicates.
