# Physical phone verification — 2026-10-09

Build: 1.1.2-preview, version code 7. Device: POCO 22101320G (redwood), Android 14, MIUI/HyperOS, Russian locale. The existing app was updated in place with its development signing key; no app-data reset was performed.

## Reported naming failure

The phone's library contained 994 audio entries, including two books with 287 files: `Audiobooks/Лунь/` (43 chapters) and `Audiobooks/Сумерки Богов/` (244 chapters). A filename such as `01 - lun - glava001m.mp3` had been interpreted as an author/title pair, replacing the meaningful folder name with `glava001m` and presenting `lun` as its author.

Named book folders now take precedence over chapter filename guesses. Transliteration markers `glava`, `glav`, and `chast` are recognized for chapter/part labels. Chapter-only album tags cannot supply the book title. Old online metadata is checked against the corrected identity before use, and its cache version triggers a retry. Restored Media3 queues receive corrected book/chapter titles and artwork without restarting or seeking.

The first real Лунь file has neither an embedded author nor album-artist value. An author therefore cannot be recovered from that file's tags; the filename prefix is not treated as an author. Public catalog matching remains optional and provider-dependent.

## Checks completed

- 42 JVM tests passed, including 20 audiobook regressions. Android lint reported 0 errors and 25 warnings. Debug, Android test, and optimized performance APKs built successfully.
- `PhoneLibraryTest` passed against the actual phone library. It verified both book titles and chapter counts, chapter 25 numbering, the Лунь Continue button, its 43-item queue, and the saved resume position.
- Two frames 700 ms apart confirmed visible movement during playback: 157 sampled book-cover pixels and 296 sampled record pixels changed beyond a small RGB tolerance. Both paused comparisons had 0 changed samples. Cropped app-only images were inspected locally under ignored `screenshots/device-fix/`.
- `AudiobookPlaybackTest` passed on the physical phone with isolated short WAV fixtures. It verified natural chapter advance with music crossfade enabled, chapter completion marks, independent 3.7-second and 2.3-second bookmarks after switching to music, each book's own Continue button, book-only queues with shuffle/repeat off, generated player cover and author, the sleep timer UI, a 15-minute preset, timed stopping, chapter-boundary stopping, and preservation of finished-book status.
- Temporary fixture files, rows, play events, metadata, and bookmarks were removed. An interrupted initial fixture run was recovered by identifying only its timestamped QA paths. The successful real-library test restored the original bookmarks exactly; relevant playback/cache state was kept in test-process memory rather than exported.
- MIUI deferred background ActivityScenario launches. The real phone fixture test supports an explicit opt-in `phoneLibrary=true` launcher path; opening MyMusic with a normal ADB launcher intent allowed the checks to finish. This is a test-launch limitation, not an observed app crash.
- The final R8-optimized performance APK was installed in place and completed a cold launch. The installed package reported version 1.1.2-preview / code 7. The final bookshelf showed two books, the corrected Лунь title, the original chapter 2 / 0:03 bookmark, 11 h 38 min remaining, and matching shelf/mini-player artwork. Playback remained paused. A MediaStore query found no remaining timestamped QA Story or Music/QA fixture files.

## Scope

The record rotates only during music playback; the active book cover sways and the book illustration turns pages only during audiobook playback. Their clock updates at most 30 times per second and suspends on pause, background lifecycle, or disposal. No CPU, battery, or frame-time benchmark is claimed.

No full private database or preferences archive was exported. Online author/cover lookup success was not demonstrated on this phone; locally generated covers remain available when catalog providers fail. Earlier emulator player/navigation checks are recorded in `audiobooks-validation.md`.
