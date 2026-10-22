# Smart Mix and personal library — 2026-10-21

Version 0.2, version code 9. No database migration or data reset is required. Existing heart IDs in the `favorites` preferences are used directly by the new liked-tracks screen.

## Selection

The previous selector gave repeatedly played tracks up to 20 extra weight points and rewarded their artists as well. Background refill excluded only the current media ID, so previously selected and still queued songs could return immediately.

`SmartMixStore` now shares a mutex and persisted rotation between the activity and playback service. Selection reserves up to 24 unique music tracks per batch. The used-ID set survives new button presses, activity restarts, and service recreation. Queued/current tracks are blocked during refill; a fresh button press also blocks the active song when alternatives exist. A new cycle begins only after the eligible unused pool is exhausted. This is a rotation of selections: rerolling a queue consumes its reservations even if every queued song was not heard.

Within a cycle, recently played music receives a cooldown, infrequently played songs receive more discovery weight, and likes add only a 1.35 multiplier. The last two principal artists are avoided when alternatives exist, including `feat.` collaborations. The recent ID tail reduces immediate repeats at cycle boundaries. Unknown artists do not force all untagged songs into one artificial artist group. Books are excluded, and repeated IDs cannot create duplicate queue entries.

Mix playback disables shuffle and repeat. Background refill checks that the active queue has not changed while its batch was computed. Paused queues are not refilled. Empty results for an unchanged tiny-library queue back off for 30 seconds, while chapter sleep-timer and other service work continue normally.

## Interface and data

- A larger Smart Mix card uses finite orbit/waveform motion, press feedback, and a disabled busy state. No decorative animation runs indefinitely while idle.
- Home shortcuts open Liked tracks and Listening. Likes are observed through a registered preference listener, preserve previous favorites, and can be removed from the collection. A finite heart animation confirms changes.
- Bottom Library navigation resets the page, collection/player origin, search text, section expansion and list scroll, including repeated taps on an already visible library. Root navigation remains available in the full player and secondary pages.
- Listening insights aggregate music starts by local calendar day for a week or month, rank tracks and credited artists, and show active days, longest day streak, peak starting hour, and the share of tracks absent from earlier available 90-day history. Missing tracks, books, and future events are excluded. The app does not infer listened minutes from track duration.
- Mix weights/artist keys are computed once per batch; candidate membership uses sets. Mix selection, preference writes, and insight aggregation run off the main thread. Charts and player motion read animation state during drawing or graphics-layer updates. Lists remain lazy; artwork uses the existing cache.

## Verification

- 60 JVM tests pass, including 12 Smart Mix tests, 5 insight tests, and root-navigation reset tests. Cases cover full rotations across multiple fresh starts, cycle boundaries, current/queued exclusions, strong listening-history bias, moderate likes, artist/collaboration spacing, zero-to-four-track libraries, stale IDs, books, and a 10,000-track input. These tests are correctness checks, not phone CPU/battery benchmarks.
- `SmartMixFlowTest` passed on the API 34 Medium Phone emulator using 60 temporary WAV files. It verified disjoint 24-track starts, background refill with pending queue exclusions and no premature cycle repetition, shuffle/repeat reset, a player heart appearing in the liked collection, removal, week/month insights, and bottom Library navigation from the full player, statistics and search. Screenshots of home, player, likes and insights were inspected under ignored `screenshots/smart-mix/`.
- All 60 JVM tests pass on the final sources. Debug, instrumentation and optimized performance APKs build successfully. Android lint: 0 errors, 26 warnings, chiefly existing platform, deprecation, localization and resource notices.
- The final Russian `SmartMixFlowTest` and `DevicePlaybackTest` both passed together on API 34. The new test additionally checked returning to the hero after scrolling the expanded library and repeated bottom Library taps. The player test verified real audio-clock progress, accessible seeking, artist/album links and Back behavior. Each credited artist now has a dedicated accessibility action. `AudiobookPlaybackTest` separately passed on the same final playback/navigation implementation, covering chapter handoff, independent resumes, completion marks and sleep timers.
- Initial fixed-delay player assertions were replaced with waiting for actual decoder progress. Artist navigation uses its dedicated accessibility action rather than a fractional coordinate inside an annotated string. Scroll assertions wait for IME dismissal and actual scrolling. These changes preserve the checks while avoiding timing assumptions on the software-rendered emulator.
- The original AVD became unresponsive to ADB during a repeat run. Final checks used a separate userdata image under ignored `build/emulator-qa/`, software GPU rendering, and no saved snapshot. Existing AVD data was not wiped. Screenshots in both English and Russian were inspected; large Russian titles have explicit line heights and the two home shortcut cards reserve equal title space.
- Release artifact: `build/releases/MyMusic-0.2.apk` (version code 9). SHA-256: `7bb882afcbe59566843b72c2814b5c0e07e2fbba2f683c6b905fd31ba9ff5259`.
- The final optimized APK was installed in place and completed a cold launch on the isolated API 34 emulator. Its process remained running; the installed package reported 0.2 / code 9. The Russian UI tree contained Умный микс, Любимые треки, Статистика and Медиатека. Final Russian screenshots confirmed the heading line spacing and equal shortcut-card heights. The temporary QA emulator was stopped after verification.

No physical phone was connected for this update. No claim of measured frame rate, battery life or broad Android-version coverage is made.
