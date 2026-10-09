# MyMusic

An offline-first Android music player built with Kotlin, Jetpack Compose, Media3, and Room. Minimum Android version: 10 (API 29).

## Build and install

1. Open the repository in Android Studio with JDK 17+ and Android SDK 35.
2. Sync Gradle, then run the `app` configuration on an Android device.
3. Grant audio access when prompted so the library can scan local files.

From a terminal, run `./gradlew :app:assembleDebug` (or `gradlew.bat :app:assembleDebug` on Windows). The APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

For checking scrolling and animation performance, use `./gradlew :app:assemblePerformance`. This creates `app/build/outputs/apk/performance/app-performance.apk` with R8 optimization and resource shrinking, without debugger overhead. It uses the same development signing key as the debug build and can update it in place.

Metadata lookup is optional and uses MusicBrainz, Cover Art Archive, and LRCLIB. No API credentials are required.

Audiobook metadata uses [Open Library Search](https://openlibrary.org/dev/docs/api/search) and [Google Books](https://developers.google.com/books/docs/v1/reference/volumes/list) as a fallback. Title and author must match before online data is accepted. Book descriptions and covers are cached locally; embedded covers take priority. Provider availability and catalog coverage vary, and failed lookups leave local names intact. The refresh button on the bookshelf retries lookups.

## Audiobooks

- Put each book in a separate folder under `Audiobooks/` or `Аудиокниги/`. Each folder becomes one book on the shelf, with its own cover, author, duration, chapter list, and **Continue listening** button. CD/Disc/Part subfolders remain inside their parent book. Files in the root folder use album/author tags or a shared filename prefix to group chapters. Files are indexed in place; the app does not move or rename your audio files.
- Chapters sort numerically (`1`, `2`, `10`), recognize `01_02`, `Chapter 1 Part 2`, and `Глава 1 Часть 2`, and display readable chapter/part labels. Completing a chapter automatically starts the next chapter of the same book. Book playback disables shuffle, repeat, random music continuation, and crossfade.
- Every book saves its own chapter and position, including when switching to music or another book. **Continue listening** resumes directly from each book card; tapping a chapter starts that chapter from the beginning. Finished books can be replayed. The most recently listened unfinished book is highlighted at the top of the shelf.
- Naturally completed chapters receive a **Listened** checkmark; the mark can also be toggled manually without changing the saved listening position. Book cards and the book folder show the remaining listening time. Skipping ahead does not mark skipped chapters as listened.
- The audiobook player has a sleep timer for 10–90 minutes or the end of the current chapter, plus 30-second skip controls. Its output-routing button is replaced by the timer. The book title, author, and chapter are carried into player metadata. Author/writer tags and `Author - Book` names or `Audiobooks/Author/Book` paths fill missing author information.
- Missing artwork gets a cached illustration from seven palettes and five designs. The same cover is used on the shelf, in the minimized and full players, and in playback metadata. Covers are generated on a background dispatcher; transitions and press animations stop after completing.
- The bookshelf has a warm reading-room style, generated book covers when artwork is missing, search by title/author, and source links for fetched metadata. The music home has a vinyl and waveform theme. A short vinyl opening animation respects Android's disabled-animation setting and is skipped on activity recreation.
- Existing libraries migrate without clearing tracks or playlists, then rescan once to record source folders and filenames.
- Chapter filenames such as `01 - lun - glava001m.mp3` cannot override a meaningful book folder with a guessed title or author. Old cached metadata is checked against the corrected book identity; restored playback queues also receive the corrected title and cover.
- The home-screen record rotates during music playback. The active book cover sways, and the open-book illustration turns its pages during audiobook playback. Motion is limited to 30 updates per second and stops on pause, in the background, or when its screen is hidden; Android's disabled-animation setting is respected.

## Library and playback

- MediaStore scans local tracks; Media3 provides background playback, audio focus, a media notification, and lock-screen controls.
- The library has search, a lossless filter, a mini-player, an expanded player, a queue, and an output-device picker. Swipe up on the mini-player, down on the full player, or sideways on album art. Long-press the mini-player to open the queue.
- **Smart play** starts an automatically generated queue using local listening data. Search playback continues with random local music. Both queues refill in the playback service.
- Home shows independently expandable All Tracks and Artists sections. Tracks can be sorted by name, date added, artist, duration, or album, with an optional lossless filter. Audiobooks have their own bottom navigation tab.
- Tap a song row, including its title or artist credits, to play it. Artist and album links in song metadata are available in the full-screen player. Albums use MediaStore album IDs and album tags; explicit collaboration credits appear under each credited artist.
- In the full-screen player, tap the title or album name to open the album, or tap an artist credit to open that artist. Back returns to the player. Tapping the minimized player's artwork or metadata opens the full player.
- The last queue, track, and position are restored on launch, paused until you press play. Listening stats show the top tracks and artists for the last week or month.
- Playlists support adding songs, long-press drag reordering, and sideways swipe removal. A local `playlists-backup.json` is rewritten when playlists change, with a second copy in `Downloads/MyMusic` that survives uninstall. Settings can export or restore it through Android's document picker.
- The duplicate finder computes SHA-256 over file contents and groups exact matches. Optional automatic cleanup keeps one copy, prioritizing the playing track and playlist references. Android asks for confirmation before deleting files owned by other apps.
- The home-screen widget displays current track information and artwork with previous, play/pause, and next buttons.

## Settings and metadata

Embedded and local metadata take priority. MusicBrainz and Cover Art Archive can fill missing metadata and artwork; results are cached in Room and local storage. LRCLIB supplies lyrics; timestamped lines auto-scroll and can be tapped to seek. Online matches are not guaranteed.

The player uses a glass-style seek rail with a decorative rhythm pattern. Lyrics follow the playback clock and support per-track timing adjustment when a source is offset. Listening statistics are available in Settings.

The UI has English and Russian resources. Settings lets you choose the system language, English, or Russian. Appearance settings provide custom accent/background colors and Editorial, Modern, Rounded, and Mono fonts. Settings also contains a sleep timer, crossfade duration, playlist backup controls, and duplicate scanning.

The equalizer and BassBoost use Android's audio effects on the active Media3 audio session. Availability and band count vary by device.

## Verification and limits

`./gradlew :app:testDebugUnitTest` runs 42 local tests for smart mixes, random continuation, album/artist grouping, sorting, lyric timing, duplicate retention, indexed browsing, full-player navigation, progress rendering, book grouping, author extraction, chapter numbering, metadata matching, remaining time, listened marks, and independent book positions. Both original Android instrumentation tests passed on the API 34 emulator: they exercise normal player navigation and seeking, automatic chapter advance, two separate resume buttons after music playback, listened marks, the sleep timer UI, timed stopping, stopping at a chapter boundary, and preservation of completed-book status. Fixtures are removed and the previous queue restored afterward. The updated version has also been checked on a physical Android 14 phone with its actual library, including book titles, resume positions, and moving/paused artwork. Android lint reports zero errors. See `design/audiobooks-validation.md` and `design/phone-library-validation.md` for current checks and limits; earlier physical-device validation is recorded in `design/device-validation.md`.

Download the installable APK from [GitHub Releases](https://github.com/dantefq/mymusic-android/releases). Preview APKs are development builds signed with the development key; they are not production releases.

Output routing depends on devices and routes offered by Android. SHA-256 duplicate detection identifies byte-identical files, so different encodings of the same audio are not reported as duplicates. Artist splitting recognizes explicit credit separators such as `feat.`, `ft.`, and semicolons; ambiguous punctuation in artist names is preserved.

Approved redesign references are in [design/](design/).
