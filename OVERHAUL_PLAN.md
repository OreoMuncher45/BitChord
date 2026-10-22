# BitChord Overhaul Plan

Read this file at the start of every fresh/compaction turn before touching code.
It is the working memory for the current overhaul. Keep it current: move items
between **Next**, **In progress**, **Done**, and **Blocked**, and record decisions
that would otherwise be lost.

Last updated: 2026-10-22
Branch: `convx-fresh`
Latest commit at plan creation: `bd5c83bc fix(icon): use inverted badge`
Release baseline: `v2.2.13-convx`, versionCode `75`, versionName `2.2.13`
Release URL: https://github.com/OreoMuncher45/BitChord/releases/tag/v2.2.13-convx

## Non-negotiables

- Work only in our fork: `/home/ubuntu/BitChordClean`,
  `https://github.com/OreoMuncher45/BitChord.git`.
- Never modify upstream `kushagrasinghx/BitChord`. The only upstream work is
  PR #1002.
- Keep the current package/application id. Do not rename it for Gemini.
- Do not touch `bitchord-lossless-shitass`.
- Do not restore the removed player glyph halo/radio discs. `LocalPlayerInkGlow`
  is for text shadows only.
- Test builds currently carry `versionCode 77 / versionName 2.2.15` so they
  install over the last delivered build (`76 / 2.2.14`). Do not tag or publish
  while the release gate is closed. Bump again only for a new test artifact.
- No emulator/adb on this machine. User verifies APKs on a real device.
- Build locally with the QEMU workaround described under **Build commands**.
  Restoring qemu 4.2.1 breaks the NDK build.

## Current baseline

- v2.2.13 is already released. Do not redo icon, halo, nav, Qobuz, Octave, or
  method-size work as part of the overhaul.
- `BitChordApp` is effectively at the JVM 64 KB method limit. Extracting
  `QobuzUnlockHost`, `WebSessionHost`, and `DiscordLoginHost` bought 1,338 bytes
  at the time, but later feature work consumed most of it. The JamesDSP Settings
  entry fits; adding the player-menu state/host did not and failed compilation
  with `MethodTooLargeException`. Any new UI must go in its own composable/file,
  and the player shell needs extracting before another root-level sheet is
  added.
- Custom audio is already in-process Float32:
  `PrecisionAudioSink` -> `DspChain` -> built-in processors. This is the correct
  insertion point for JamesDSP; no root and no internal audio capture are needed
  for BitChord's own playback.
- Gemini's manifest filter and query handling already exist. This is not a
  greenfield feature; it needs verification and possibly an AppFunctions fallback.
- Unit baseline: 1,123 tests, 16 pre-existing failures (`GeniusTest`,
  `LocaleStringsTest`). Lint has 283 pre-existing errors. Compare against the
  baseline; do not treat inherited failures as regressions.
- Main `Android Release APK` CI fails on the pre-existing prod keystore. Use the
  tag `Flow Debug APK` workflow for testable APKs.

## Target features

1. **JamesDSP, rootless for BitChord only**
   - Include the open-source JamesDSP engine (`libjamesdsp`) inside BitChord.
   - Process only BitChord's decoded PCM. Do not use root, Magisk, internal
     audio capture, or system-wide `AudioEffect` hooking.
   - Entry points:
     - Settings, under Playback beside Equalizer: implemented and shipping in
       the next test artifact.
     - Now Playing three-dot/menu: deferred until `BitChordApp` is split. It
       is at the JVM method-size limit; adding this host and state overflowed
       it. Do not inline it back.
   - Ship an actual control screen. Do not make this a dead toggle or a link that
     opens another app.

2. **Hide Flow on Home**
   - New persisted setting.
   - When off, the synthetic Flow shelf/card is not added to Home.
   - Quick Picks remains visible and functional regardless of this setting.
   - Flow implementation itself stays intact; this only hides its Home entry.

3. **Home ordering and top picks**
   - Move Quick Picks to the top of Home, beside Flow.
   - Allow reordering of the remaining Home shelves/topics.
   - Flow toggle must never remove Quick Picks.
   - Add a carousel/pager presentation where the previous card "folds" away
     rather than hard-cutting.

4. **Gemini playback**
   - `"play <song>"` should start the best matching song.
   - Empty `"play music"` should resume existing playback.
   - Pause/skip should work through the existing media session.
   - If Gemini does not call `MEDIA_PLAY_FROM_SEARCH`, add an AppFunctions path
     only after confirming that route is needed.
5. **Blocked artists**
   - Hidden artists must not appear in search, FLOW, Home shelves,
     recommendations, autoplay, or queue seeding.
   - Settings gets a Blocked Artists manager with unblock.
   - The artist page's overflow menu can hide the artist directly.
6. **Artist page: stats and overflow**
   - Spotify monthly listeners and subscriber count visible on the artist page.
   - Three-dot menu offers Albums and Singles & EPs, and Hide artist.
7. **AI music transparency and strict mode**
   - Subtle AI badge in Now Playing when YouTube marks a track as Gen AI.
   - Strict setting to never play AI music (skip and filter once detected).
8. **Android Auto queue order**
   - Starting a playlist from Android Auto must play the playlist in order;
     the second track must not be replaced by an autoplay recommendation.
9. **Autoplay after the queue ends**
   - User toggle for appending similar songs when the last track finishes.
10. **Transport polish**
    - Play, pause, next, previous, and AirPlay/cast controls should feel
      smoother at the edges: no hard icon swaps, no jump on press, consistent
      touch targets, and a clean disabled/first-track/last-track state.

## Workstream A: JamesDSP rootless engine

**Status 2026-10-22:** implemented, wired into the DSP chain, and shipped in
the fork-signed CI artifact for build 77 / 2.2.15 (run `39864080918`). Device
audio verification is still pending. No tag and no public release: the licence
gate in A1 is still open, so this remains an early-access test build only.

### A1. Why this architecture

BitChord already decodes every stream to interleaved Float32 before Media3's
AudioTrack sink (`PrecisionAudioSink -> DspChain`). JamesDSP therefore runs
**in-process on BitChord's own playback**, not through AudioRecord, root,
Magisk, or a system `AudioEffect`. That gives exactly the scope asked for:
BitChord's music, no other app's audio, no capture permission, no root.

The trade is deliberate: this is not Rootless JamesDSP's system-wide capture
service. It is the same engine fed by a different source. v1 therefore exposes
the effects that fit a per-track music chain (bass, width, tube, reverb,
pre-gain, limiter); the convolver file picker, Viper DDC profiles and live-prog
editor are not exposed yet.

### A2. License gate (open, blocks public release)

- Vendored engine: `ThePBone/JamesDSPManager`, branch `extensions`, commit
  `1b9f89dbd4b332a07724beb41d58bc2ffcb05395`, taken via Rootless JamesDSP.
- The engine's `Main/LICENSE` is **GPLv2** text. BitChord is GPLv3. GPLv2-only
  and GPLv3 are not compatible for a combined work, so this cannot be treated
  as cleared for a public release just because Rootless JamesDSP ships it.
- The engine source carries no per-file "or later" grant. Do not assume one.
- Options before public release, in order of preference:
  1. Obtain a written GPLv2-or-later / GPLv3 permission from the engine's
     copyright holders (ThePBone / james34602 and contributors).
  2. Replace the engine with a GPLv3-compatible implementation, keeping the
     same `JamesDspProcessor` interface so the UI and chain do not change.
  3. Keep JamesDSP as a separately distributed optional component/process with
     arm's-length communication and its own complete source offer. This is
     much more work and worse latency; do not pretend it is a one-line fix.
- Until one of those is done: artifact-only builds for the paying early-access
  group, no GitHub Release, no version tag. `NOTICE` and
  `app/src/main/cpp/jamesdsp/NOTICE.md` record the origin and the unresolved
  version question rather than hiding it.

### A3. Vendored layout

```text
app/src/main/cpp/jamesdsp/
  libjamesdsp/          # pinned engine sources (84 .c files, ~5.3 MB)
  cpthread.c/.h         # engine's adjacent platform shim
  jni/JamesDspJni.cpp   # BitChord JNI shim, public jdsp_header API only
  CMakeLists.txt        # static engine + one shared JNI library
  LICENSE               # upstream GPLv2 text
  NOTICE.md             # origin, commit, scope, update instructions
```

- `jamesdsp` is a static library; `bitchord_jamesdsp` is the only shared
  library and contains the JNI shim. The APK does not need a second .so lookup.
- CMake adds it with `add_subdirectory(jamesdsp)`; existing
  `bitchord_analysis`, `bitchord_dsd`, and `bitchord_dst` targets are untouched.
- Compile flags: `-O3 -ffast-math -std=gnu11`, 16 KB max page size.
- Clang 18 rejects the engine's pre-standard function-pointer declarations.
  The build pins `-Wno-incompatible-function-pointer-types` and the related
  old-C warnings. These are source compatibility flags, not DSP changes.
- ABI set follows the app: `armeabi-v7a`, `arm64-v8a`, `x86_64`.

### A4. JNI and Kotlin bridge

Native entry points in `JamesDspJni.cpp`:

- `nativeCreate(sampleRate, maxBlockFrames) -> Long`
- `nativeDestroy(handle)`
- `nativeApply(handle, bassEnabled, bassDb, stereoEnabled, stereoLevel,
  tubeEnabled, tubeLevel, reverbPreset, preGainDb, limiterEnabled)`
- `nativeProcess(handle, inputFloatArray, outputFloatArray, frames)`

Implementation notes:

- `JamesDSPGlobalMemoryAllocation()` is called once via `std::call_once`
  (EEL's global tables), not once per engine. It is never torn down while the
  process lives; that is safer than starting/stopping EEL during a crossfade.
- The engine is stereo-interleaved. Two channels copy straight in/out. Mono is
  duplicated to L/R and averaged back. More than two channels bypasses
  JamesDSP rather than silently downmixing; the output stage still owns that.
- One engine per player (A/B) because crossfade keeps two renders alive. They
  are independent; sharing one would corrupt filter/reverb state.
- `nativeApply` only touches effects whose settings changed. Reverb preset
  changes rebuild its network, so they must not happen per block.
- `JamesDspProcessor` publishes an immutable `JamesDspConfig` through `@Volatile`;
  only the audio thread touches the handle. Configuration is applied at a block
  boundary and throttled to 20 Hz while a slider is moving, so a drag cannot
  rebuild reverb on every 5 ms buffer.
- `flush()` / `reset()` destroy and recreate the handle. libjamesdsp has no
  reset entry point, and recreating is the only reliable way to drop a reverb
  tail on a seek. The next `process()` recreates it.
- `JamesDspNative.available` lazily loads `libbitchord_jamesdsp`; the service
  warms it on the settings thread when the switch is turned on, not on the
  audio thread. A failed load logs once and bypasses.
- Native function names match the Kotlin object
  `com.music.bitchord.playback.audio.JamesDspNative`.

### A5. Playback integration

`DspChain` order is now:

```text
AudioBlock -> Sing -> Spatial -> Equalizer -> JamesDSP
           -> TransitionFilter -> Loudness -> AudioBlock
```

- JamesDSP sits after the listener's equalizer and before the per-handoff
  transition filter. The transition filter must have the last word on a
  crossfade; loudness stays last so it levels the whole shaped signal.
- `PlaybackService` owns `jamesDspProcessorA` / `jamesDspProcessorB` and passes
  them through `buildPlayer` -> `silenceSkippingRenderers` -> `DspChain`, exactly
  like the equalizer pair. All four player-construction sites
  (initial A/B and float-output rebuild A/B) are wired.
- The settings collector combines the seven flows into one `JamesDspConfig`
  and updates both processors. Both sides of a crossfade therefore hear the
  same settings.
- With JamesDSP off, `process()` returns before touching a sample, preserving
  the existing exactness contract.

### A6. Settings and UI

- Persisted in `AppSettings`: `jamesDspEnabled`, `jamesDspBassGainDb`,
  `jamesDspStereoLevel`, `jamesDspTubeLevel`, `jamesDspReverbPreset`,
  `jamesDspPreGainDb`, `jamesDspLimiter`. Defaults: off, all effects neutral,
  limiter on.
- `JamesDspSheet` (modal bottom sheet, separate file) has the master switch,
  Bass boost 0-15 dB, Stereo width 0-100, Tube warmth 0-100, Pre-level trim
  -15..+15 dB, Reverb choices (Off / Room / Hall / Large hall / Plate / Long),
  limiter switch and Reset. The UI is disabled with an explanatory line when
  the native library is unavailable.
- Entry point for this test build: Settings -> Playback -> JamesDSP (beside
  Equalizer).
- The player ⋯ menu entry is **deferred**, not forgotten. `BitChordApp` is at
  the JVM 64 KB method limit: adding a visibility state, a host call and a
  `SongActionsSheet` callback pushed it over and failed compilation. Adding it
  back requires extracting the player shell / song-actions host first; do not
  inline it into `BitChordApp`. The `JamesDspSheet` itself is self-contained and
  can be hosted from a smaller composable later.
- JamesDSP strings are present as English fallbacks in the base strings and all
  15 locale files. They are not translated yet. `LocaleStringsTest` still fails
  on the pre-existing 113 missing keys from earlier features (16-failure unit
  baseline: 15 locale tests + `GeniusTest`), not on the JamesDSP keys.

### A7. Acceptance criteria and test steps

- [x] All three ABIs configure, compile and package `libbitchord_jamesdsp.so`.
- [ ] With JamesDSP off, precision passthrough/exactness tests still pass and
      no native library is loaded by the audio thread.
- [ ] With it on, every control is audible and survives track change, seek,
      crossfade, app background/restart.
- [ ] Both crossfade sides process independently: no doubled audio, no one-sided
      effect, no dropout at handoff.
- [ ] Mono file plays without channel duplication artefacts; >2 channels
      bypass cleanly.
- [ ] CPU/underruns measured on device; reverb and bass boost are the likely
      hot paths.
- [x] No root, capture or new permission in the manifest.
- [ ] Toggle off returns to the unprocessed path without leaving a tail.

Build:

```bash
./gradlew :app:assembleDevDebug   # local native + Kotlin check
./gradlew :app:testDevDebugUnitTest
gh workflow run flow-debug-apk.yml --ref convx-fresh   # installable artifact
```

The artifact is the CI build signed with `CN=BitChord Next Fork`; a local debug
APK cannot update an installed CI build because the signing keys differ.

### A8. Known risks

- GPLv2/GPLv3 compatibility remains the release blocker; see A1.
- Two engine instances plus their reverb state cost memory; measure on low-RAM
  devices.
- Reverb preset changes allocate/reconfigure inside the engine. They are rare
  and applied at a block boundary, but a very slow device may hear a click.
- Clang 18 compatibility flags suppress real old-C warning classes. Keep them
  scoped to this target, not the whole CMake project.
- The engine's `-ffast-math` is intentional for FFT/convolution speed, but it
  means no bit-exact expectation applies while JamesDSP is enabled.


## Workstream B: Hide Flow on Home

Files:

- `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt`
- `app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt`
- `app/src/main/java/com/music/bitchord/MainActivity.kt`
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/HomeScreen.kt`
  only if an empty-top-area case needs handling.

Steps:

1. Add `homeFlowVisible = MutableStateFlow(true)` plus getter/setter/prefs key
   and backup entry.
2. Add a switch under Settings -> Appearance or a new Home section. Include
   search keywords `flow`, `home`, `hide`.
3. In `MainActivity`, compute `homeWithFlow` only when
   `flowStatus.unlocked && homeFlowVisible`.
4. Keep the Flow screen route and state; this setting hides the Home entry only.
5. Verify Quick Picks is untouched when Flow is hidden.

Acceptance:

- Toggle off -> Flow card/shelf disappears from Home immediately.
- Quick Picks remains.
- Toggle on -> Flow returns without restart if unlocked.
- Setting survives restart and backup/restore.

## Workstream C: Home top picks, carousel, and shelf ordering

### C1. Extract Quick Picks from the feed

- Quick Picks is currently a server shelf titled `Quick picks` and localized by
  `localizeShelfTitle` in `HomeScreen.kt`.
- Add a stable `homeShelfKey(title)` helper (non-composable) covering the known
  aliases: `Quick picks`, `Lựa chọn nhanh`, `クイック ミックス`, etc.
- Build `HomePresentation` in `MainActivity` or a small helper:
  - `flow` if enabled/unlocked.
  - `quickPicks` extracted from the feed.
  - `shelves` remaining, ordered by user preference.
- Remove Quick Picks from its old position so it is not drawn twice.
- Empty/missing Quick Picks: keep the top slot's layout stable with a skeleton
  or omit only Quick Picks, never Flow's slot.

### C2. Top-picks carousel

- Add `HomeTopPicks` in `HomeScreen.kt` (or a new `HomeTopPicks.kt` to keep
  `HomeScreen.kt` manageable).
- Two pinned cards at the top: Flow and Quick Picks.
- Published interaction: horizontal pager/carousel. The previous card folds
  behind the incoming one with `rotationY`, scale, and alpha (no hard cut).
- Flow hidden -> Quick Picks fills the primary slot and remains usable.
- Tap Flow -> existing `showFlow = true` route.
- Tap Quick Picks -> expand/open its items. Do not make it a dead card.
- Respect `reduceAnimation`: disable fold transforms, use a plain pager.
- Keep the current home title, PullToRefresh, skeleton, and load-more behavior.
- Do not regress Recents' special leading layout behavior.

**Open interpretation:** "carousel style that folds the previous choice" may
mean the two top cards fold into each other, or the individual song cards fold
as they scroll. This plan implements the top-card interpretation first. Confirm
before polishing the animation.

### C3. Reorder topics

- Persist `homeShelfOrder: List<String>` in `AppSettings`.
- Reorder only the remaining shelves; Flow and Quick Picks stay pinned.
- Add Settings -> Home -> Reorder sections, using drag handles and a Reset
  action. Keep it a separate composable to preserve `BitChordApp` method size.
- Unknown/new server shelves append in server order.
- Removed shelves are dropped from the saved order without disturbing the rest.
- Reordering must not break shelf keys, duplicate shelves, or `loadMore`.

Acceptance:

- Quick Picks is at the top beside Flow, not duplicated below.
- Flow off leaves Quick Picks in place.
- User order persists across refresh, restart, and backup/restore.
- New shelves still appear.
- Both phone and wide layouts render without clipping.

## Workstream D: Gemini playback

### Already implemented

- `AndroidManifest.xml` declares
  `android.media.action.MEDIA_PLAY_FROM_SEARCH` on `MainActivity`.
- `MusicLink.consume()` parses `SearchManager.QUERY`.
- Empty query -> `LinkRequest.Resume`; non-empty -> `LinkRequest.Search(play = true)`.
- `MainActivity` handles `Search`: searches `SearchFilter.SONGS`, plays the top
  track through `playRadio`, otherwise opens Search with the query.
- `onNewIntent` calls `MusicLink.consume(intent)`.
- `PlaybackService` is a `MediaLibraryService` with a `MediaSession`, so
  pause/skip should already work.

### Steps

1. Test existing behavior on device:
   - "pause" / "next" while playing;
   - "play Blinding Lights";
   - "play music" with an existing queue;
   - cold start from Gemini.
2. Fix only observed gaps. Likely candidates:
   - no matching track -> keep a clear user-facing result;
   - query normalization ("play X by Y") if the raw phrase hurts search;
   - ensure the media session is active before the intent is served.
3. Do **not** rename the package.
4. If Gemini never sends `MEDIA_PLAY_FROM_SEARCH`, evaluate AppFunctions as a
   separate follow-up. Add it only if the manifest/intent path demonstrably does
   not work on the target device/Android version.

Acceptance:

- Gemini can start a named song and resume music.
- Lock-screen/media-session pause and skip still work.
- No app rename and no new account requirement.

## Workstream E: Blocked artists

Files:

- `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt`
- `app/src/main/java/com/music/bitchord/ui/MainViewModel.kt`
- `app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt`
- new `app/src/main/java/com/music/bitchord/ui/screens/BlockedArtistsSection.kt`
- `app/src/main/java/com/music/bitchord/ui/player/NowPlayingScreen.kt` only for the
  artist menu entry point.

Storage:

- Persist `blocked_artists` as a `StringSet`.
- One entry per act: `<identity>\t<display name>`.
- Identity is `id:<channelId>` when the row/artist page exposed one, otherwise
  `name:<lowercased collapsed name>`.
- Check both the lead `artistId`, every `Song.artists` entry, and the raw
  byline split on `, / & / x / and / feat / ft / with`.
- Export/import already carries string sets, so backups include the list.

Filter points (all must use one helper, never hand-rolled name checks):

1. Search: `published()` and `loadMoreSearchResults()` drop blocked songs and
   blocked artist browse rows before caching/publishing.
2. Home: drop blocked `ShelfItem`s from shelves and drop shelves left empty.
3. FLOW: filter the generated queue and every continuation; re-fill if the mix
   would be shorter than the minimum.
4. Detail pages: filter recommended songs on artist/album pages and blocked
   artist cards. A directly opened blocked page still renders so it can be
   unhidden.
5. Autoplay/queue: never append a blocked candidate; skip a blocked track if it
   is already in a restored queue.
6. Recents/history: hide blocked rows on the Home recents shelf.

Settings UI:

- New "Blocked artists" row under Library or Appearance with the current count.
- Opens a manager listing each hidden act, with Unblock per row and a search
  field.
- Empty state explains that hiding is also available from the artist page.
- Keep this in its own file to protect `BitChordApp` method-size headroom.

Acceptance:

- Hiding an artist from its page removes its songs from search, FLOW, Home and
  autoplay immediately.
- A collaboration is hidden when any credited artist is hidden.
- Unblocking restores normal results without restart.
- The list survives restart and backup/restore.

## Workstream F: Artist page stats and overflow menu

Files:

- `app/src/main/java/com/music/bitchord/ui/screens/DetailScreen.kt`
- `app/src/main/java/com/music/bitchord/MainActivity.kt`
- `sharedUi/.../NowPlayingScreen.kt` if the same menu is reused there.

Steps:

1. Move the subscriber / monthly-listener chips out of the About block and into
   the artist header, directly under the name and above the action row.
   Keep a compact wrap so long localized labels do not clip.
2. Add a three-dot button to the artist header (top-right of the header content,
   not the global app bar).
3. Menu items:
   - **Albums** — scroll to the Albums carousel.
   - **Singles & EPs** — scroll to that carousel.
   - **Hide artist** — `AppSettings.blockArtist(page.browseId, page.title)`,
     then pop the page.
4. Shelf lookup must tolerate localized titles: match a stable key from a small
   alias set rather than exact English text.
5. Hide the menu entirely for non-artist pages.

Acceptance:

- Stats are visible without scrolling to the bottom.
- Albums/Singles jump to the right carousel, including localized UI.
- Hide artist removes the page and the artist from results immediately.
- No layout overlap with the status bar, back button, or logo.

## Workstream G: Spotify artist stats

Files:

- `app/src/main/java/com/music/bitchord/data/spotify/SpotifyLibrary.kt`
- new `app/src/main/java/com/music/bitchord/data/spotify/SpotifyArtistStats.kt`
- `app/src/main/java/com/music/bitchord/ui/screens/DetailScreen.kt`
- `MainActivity.kt` wiring.

Data source:

- Spotify's Web API artist object does not expose monthly listeners. Do not
  invent one.
- Use the public artist page (`open.spotify.com/artist/<id>`) and read the
  embedded monthly-listeners figure; the page also states followers.
- Cache per artist id for the session with a short TTL; the artist page should
  not fetch on every recomposition.
- If scraping fails, fall back to the existing YouTube counts and show nothing
  extra. Never block the page on this request.

Steps:

1. Resolve a Spotify artist id by exact name through the existing token/client
   credentials flow.
2. Fetch and parse monthly listeners + followers.
3. Publish `SpotifyArtistStats(monthlyListeners, followers)` through
   `MainViewModel` keyed by browse id.
4. Render a Spotify row above the YouTube chips, labelled so the two sources are
   not confused.
5. Add a Settings toggle to hide Spotify stats if the listener does not use
   Spotify.

Acceptance:

- A matched artist shows monthly listeners and followers.
- An unmatched artist shows only YouTube counts, with no error UI.
- No Spotify credentials are logged or exported.
- The page is usable while stats are loading.

## Workstream H: AI music badge and strict mode

Files:

- `shared/.../data/model/Models.kt`
- `shared/.../data/innertube/InnertubeParser.kt`
- `shared/.../data/YtMusicRepository.kt`
- `app/.../ui/MainViewModel.kt`
- `sharedUi/.../ui/player/NowPlayingScreen.kt`
- `app/.../data/settings/AppSettings.kt`
- `app/.../ui/screens/SettingsSheet.kt`

What YouTube exposes:

- The Oct 2026 label is an action on the player overlay, rendered beside the
  like/dislike buttons; tapping it opens "How this was made".
- Partner values are `Fully Gen AI`, `Partly Gen AI`, `No Gen AI`.
- No stable, documented public field exists yet. Parse defensively:
  - read `playerOverlays.playerOverlayRenderer.actions`;
  - recognise an AI action by any of: an AI icon type, a text/accessibility run
    equal to `AI` / `Made with AI` / `Gen AI`, or a body containing
    `altered or fully generated using AI`;
  - classify `Fully` vs `Partly` when the text says so, otherwise `UNKNOWN`.
- Also scan row badges (`badges`, `subtitleBadges`, `thumbnailBadges`) for the
  same markers so list filtering works where YouTube sends it.
- If YouTube sends nothing, treat the track as unknown — never guess from the
  artist name, title, or genre.

Live-API finding (2026-10-22):

- Probed `WEB_REMIX` search, `next`, and the song menu for known AI acts
  (Xania Monet, Blue Monroe). **No AI marker was present anywhere.** The only
  watch-page hits were feature flags (`enable_ai_disclosure_for_*`), not a
  per-video label. Strict mode therefore had nothing to match, which is why it
  appeared broken.
- Decision: the reliable mechanism is a listener-maintained AI-act list, using
  the same identity format as `blockedArtists` (`id:` then normalised `name:`).
  `AppSettings.aiArtists` persists it; the artist page's overflow menu toggles
  it ("Mark artist as AI-generated" / "Remove AI mark"); an artist carrying the
  mark shows a small `AI-generated` chip on their page and the Now Playing chip.
  YouTube's own disclosure remains parsed as a bonus for the day it appears.
- Strict mode (`blockAiMusic`) now means: filter rows and queue items where
  `isSongAi(song)` is true, which covers both YouTube's label and the list.
  `PlaybackService` also enforces it on `onMediaItemTransition`, so a queue
  built before an artist was marked cannot slip through on next/radio playback.


Model:

- `enum class GenAiDisclosure { NONE, PARTLY, FULLY, UNKNOWN }`
- `Song.genAi: GenAiDisclosure = NONE` (default is "not stated", shown as no
  badge).
- Keep a small persisted `aiDetected` video-id set in AppSettings so a track
  detected once stays filtered even when the overlay is unavailable later.

Now Playing:

- Subtle glass chip `AI` under the artist line. `FULLY` and `PARTLY` both show
  the chip; tapping opens a short explanation sheet.
- Do not add another large button; the user asked for subtle.

Strict mode:

- Setting `blockAiMusic` (default off).
- Before a track starts, check `Song.genAi`; when it is `FULLY`/`PARTLY`, skip
  to the next queue item and surface a short toast/snackbar.
- When the current metadata lookup reveals AI for the playing track, behave the
  same way rather than waiting for the next track.
- Filter AI-marked rows out of search, FLOW, Home and autoplay when strict mode
  is on.
- Unknown is allowed in strict mode (no reliable data to act on), but once a
  video id is learned it is remembered.

Acceptance:

- An AI-labelled track shows the chip in Now Playing.
- Strict mode on skips that track and never appends it again.
- Strict mode off only labels; playback is unchanged.
- No badge is shown for unlabelled tracks.
- All detection is fail-open: a parser miss degrades to "no badge".

## Workstream I: Android Auto queue order

Files:

- `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt`
- `app/src/main/java/com/music/bitchord/playback/QueueCoordinator.kt`
- `app/src/main/java/com/music/bitchord/playback/Autoplay.kt`
- `app/src/main/java/com/music/bitchord/playback/PlayerQueue.kt`

Observed bug:

- In-app playback of a playlist is correct.
- Started from Android Auto, the second playlist track is replaced by an
  autoplay item (reported with "Time Bomb").
- This points to the service seeding a radio/autoplay queue when the media
  session starts, instead of receiving the tapped playlist context.

Steps:

1. Trace the Auto `onPlayFromMediaId` / `onAddQueueItem` path and compare the
   queue built there with the in-app `playFrom` path.
2. Make the Auto path carry the playlist browse id and full ordered track list;
   set `QueueTier.CONTEXT` on every playlist item.
3. Do not call `Autoplay` or build `RDAMVM` when the request has a playlist
   context.
4. Ensure `queueEntryId` assignment preserves order and that the first
   `onSkipToNext` moves to index 1, not to an autoplay append.
5. Add a regression test asserting the first three entries after an Auto-style
   start match the playlist order.

Acceptance:

- Start a playlist from Android Auto: track 2 is the playlist's track 2.
- Autoplay only begins after the last playlist item.
- In-app behavior is unchanged.

## Workstream J: Autoplay similar songs toggle

Files:

- `app/.../data/settings/AppSettings.kt`
- `app/.../playback/Autoplay.kt`
- `app/.../playback/PlaybackService.kt`
- `app/.../ui/screens/SettingsSheet.kt`

Steps:

1. Add `autoplaySimilarSongs` (default on, matching current behavior).
2. When off, stop at the end of the queue; do not fetch `next` or append
   `QueueTier.AUTOPLAY` items.
3. When on, keep the current behavior and honor blocked artists / strict AI
   filtering in the candidates.
4. Wire the switch into the existing Playback settings group.
5. Do not remove the explicit "Start radio" action; this only governs automatic
   continuation.

Acceptance:

- Off: playback stops after the last queued track.
- On: similar songs continue as today.
- The choice survives restart and backup/restore.

## Workstream K: Transport control polish

Files:

- `sharedUi/.../ui/player/NowPlayingScreen.kt`
- `app/.../ui/player/` transport composables and any mini-player bar
- `app/.../ui/classipod/ClassipodNowPlaying.kt`
- `sharedUi/.../ui/components/` where shared transport buttons live

Scope:

- Play/pause, previous, next, and the AirPlay/cast button should feel smoother at
  their edges. This is polish, not new behavior.
- Unify press feedback: same ripple/press scale and haptic contract on every
  transport control, including the mini player and Car/Classipod surfaces.
- Crossfade the play/pause icon instead of hard-swapping the glyph.
- Disable or dim previous at the start and next at the end when there is no
  queue continuation; restore automatically when autoplay is on.
- Make the touch targets at least 48dp even where the visual circle is smaller.
- AirPlay/cast: no layout jump when the button appears/disappears, and the same
  edge padding as the neighbouring controls.
- Respect `reduceAnimation` for any icon crossfade or press scale.

Acceptance:

- Rapid tapping play/pause never leaves the icon out of sync with playback.
- Next/previous do not visibly jump when the queue boundary is reached.
- AirPlay/cast aligns with the other controls at every window size.



## Build commands

Local debug build (QEMU workaround):

```bash
cd /home/ubuntu/BitChordClean
./gradlew :app:assembleDevDebug
```

Unit tests:

```bash
./gradlew :app:testDevDebugUnitTest
```

Desktop release compile (CI parity):

```bash
./gradlew :desktopApp:compileKotlin
```

CI artifacts:

- Use the tag `Flow Debug APK` workflow.
- Main `Android Release APK` is expected to fail on the prod keystore.
- Current tag workflow succeeds for Flow Debug and desktop Release.

## Execution order

1. Create this plan and keep it updated. **Done.**
2. Flow visibility toggle. Low risk, isolates Home behavior.
3. Blocked-artist storage/filter (Workstream E).
4. Artist page stats and overflow menu (Workstreams F/G).
5. Android Auto queue order (Workstream I).
6. Autoplay toggle (Workstream J).
7. AI badge and strict mode (Workstream H).
8. Transport polish (Workstream K).
9. Home key extraction, Quick Picks extraction, top-picks layout, ordering.
10. Gemini device verification and small fixes.
11. JamesDSP native feasibility spike: compile engine, one JNI call, prove
    passthrough and audible EQ on device.
12. JamesDSP playback integration (two players, DspChain, settings).
13. JamesDSP Compose screen and two entry points.
14. Full regression pass, APK build, device verification.
15. Only then decide version/tag/release.

## Open decisions

- Quick Picks top-card carousel semantics (top cards vs individual song cards).
  Decided 2026-10-22: **side-by-side** — the Flow tile sits to the left of the
  same row, with the Quick Picks carousel continuing to its right. Hiding Flow
  must leave the Quick Picks carousel untouched and starting at the left edge.
- Where reorder UI lives: Settings -> Home vs inline Home edit mode. Plan assumes
  Settings.
- JamesDSP license path for public GPLv3 distribution.
- How much of JamesDSP to expose in v1 (master/limiter/EQ vs full convolver/VDC).
- Whether built-in BitChord EQ/spatial should be mutually exclusive with JamesDSP
  to avoid double processing. Default plan: leave both available, document the
  order, and let the user disable one.
- Next version number/name after the overhaul. Current test build:
  **77 / 2.2.15**. The public release number is still undecided; revisit only
  when the release gate opens.
- AI label field shape is undocumented. Detection is best-effort against the
  player overlay; it must fail open and be re-checked when YouTube changes it.
- Spotify monthly listeners require parsing the public artist page; no official
  API field exists.

## Progress log

- 2026-10-22 (JamesDSP): vendored libjamesdsp (`extensions`
  `1b9f89dbd4b332a07724beb41d58bc2ffcb05395`) under
  `app/src/main/cpp/jamesdsp`, added a minimal JNI shim (`JamesDspJni.cpp`) and
  a static-engine/one-shared-library CMake target. `add_subdirectory` keeps the
  existing native targets untouched. The first build failed on a missing
  `../cpthread.h` and then on Clang 18's function-pointer diagnostics; both are
  fixed by copying the adjacent cpthread files and scoping compatibility flags
  to the engine target.
- 2026-10-22 (JamesDSP): added `JamesDspProcessor` / `JamesDspNative`, one
  engine per crossfade player, immutable `JamesDspConfig` publishing, 20 Hz
  parameter throttle, mono duplicate/average, >2-channel bypass, and
  destroy/recreate on flush. Wired through `DspChain` after the equalizer and
  before transition/loudness, and through all four `buildPlayer` sites.
- 2026-10-22 (JamesDSP): added persisted settings (master, bass, stereo, tube,
  reverb preset, pre-gain, limiter), `JamesDspSheet`, and a Settings -> Playback
  row beside Equalizer. Strings added to the base and all 15 locale files.
  `NOTICE` and the vendored `NOTICE.md` record the GPLv2 origin and the
  unresolved GPLv3 compatibility question.
- 2026-10-22 (JamesDSP): a player ⋯ menu entry was attempted and removed. It
  pushed `BitChordApp` over the JVM 64 KB method limit (the compile failed with
  `MethodTooLargeException`). Settings remains the entry point for this build;
  the player entry needs the shell refactor described in A6.
- 2026-10-22: Plan created from the current fork state. v2.2.13 remains the
  released baseline. No overhaul code changes yet.
- 2026-10-22: Added Workstreams E-J for blocked artists, artist stats/menu,
  Spotify stats, AI labels/strict mode, Android Auto queue order, and the
  autoplay toggle. Started Workstream E in `AppSettings`.
- 2026-10-22: Customisation hub (Now Playing colours, Home order + Flow switch,
  Library sort, Theme shell), Spotify artist chips, AI chip + strict mode,
  transport glyph smoothing, `ArtistBlocks` `@JvmName` fix. First successful
  `:app:assembleDevDebug`.
- 2026-10-22: Version bumped 75/2.2.13 -> 76/2.2.14 at the user's request so the
  test APK is a distinct build. Built and delivered as
  `BitChord-v2.2.14-code76-arm64.apk`.
- 2026-10-22: Version bumped 76/2.2.14 -> **77/2.2.15** for the AI-mark and
  transport-smoothness test build. Still artifact-only; release gate closed.
- 2026-10-22: AI strict mode was found to be a no-op against live YouTube data:
  no AI marker exists in search/next/song-menu payloads for known AI acts.
  Added `AppSettings.aiArtists` + artist-page "Mark as AI-generated" toggle,
  Now Playing chip from the mark, and transition-time skipping in
  `PlaybackService` so existing queues/radios cannot bypass strict mode.
- 2026-10-22: Added `transportSmoothness` (0-1, default 0.55) and a slider under
  Customization -> Now Playing. Play/pause/skip are now filled rounded polygons
  drawn by `TransportGlyphs.kt` instead of the sharp skip drawables. Matched the
  old drawable footprint so the transport does not shrink.
- 2026-10-22: Home top row now pins Quick Picks first, with the Flow tile as its
  leading card; the reorder list no longer offers Quick Picks. Fixed the
  `isQuickPicksShelf` visibility and `aiArtists` restore errors.
- 2026-10-22: Full unit suite after JamesDSP: 1123 tests, 16 failed, exactly the
  pre-existing baseline (15 locale-key tests + `GeniusTest`); no new failures.
  `:app:assembleDevDebug` passed after the notice/locale sync.
- 2026-10-22: JamesDSP CI artifact verified. Pushed `18ebe18f`; `Flow Debug APK`
  run `39864080918` succeeded in 12m21s and produced four ABI-split release
  APKs. `aapt2` reports `com.dev.bitchord`, `77 / 2.2.15`; `apksigner` reports
  `CN=BitChord Next Fork`; `libbitchord_jamesdsp.so` is present for arm64,
  armeabi-v7a and x86_64; the arm64 APK passes 16 KB `zipalign`. No tag or
  GitHub Release was created. Draft releases were removed; published releases
  and their APK assets were left intact.

- 2026-10-22: Install note. `dev` flavour is applicationId `com.dev.bitchord`
  ("BitChord Next"), `prod` is `com.music.bitchord`. Dev debug APKs are signed
  with the *building machine's* `~/.android/debug.keystore`, so a build from a
  different machine (or CI) has a different signature and Android refuses to
  update over it with "conflicts with an existing package". That is a signing
  mismatch, not a versionCode problem — uninstall the old package once, or keep
  every build on one machine/keystore. No `keystore.properties` exists on the
  build server, so `prod` release builds are unsigned and cannot be shipped.

## Release gate (do not break this)

- **Nothing is published until the paying early-access testers stress test a
  build and the user says go.** No GitHub Release, no version tag.
- The only sanctioned way to produce a *installable-over-the-old-app* APK is the
  `Flow Debug APK` workflow (`workflow_dispatch`, artifact only). It builds
  `:app:assembleDevRelease` and signs with the fork's stable key from the
  `ANDROID_KEYSTORE_B64` secret — certificate `CN=BitChord Next Fork`.
  2026-10-22 verification: run `39864080918` (commit `18ebe18f`) built
  `com.dev.bitchord`, `versionCode 77`, `versionName 2.2.15`, signed by
  `CN=BitChord Next Fork` (SHA-256
  `385af3b4b3f5b4357b7bb271fd71b267fad4d536be87e44f2057b0c2de8971ce`).
  The arm64 APK contains `lib/arm64-v8a/libbitchord_jamesdsp.so`; the universal
  APK contains all three ABI libraries; 16 KB `zipalign` verification passed.
  Local arm64 copy: `BitChordNext-2.2.15-code77-forkkey-arm64.apk`, SHA-256
  `3099c8726724d9c1e4b10bff0560d2528f0686e9b86a211ff35500c417ff4b10`.
- Local `:app:assembleDevDebug` builds on the server are signed with the
  *server's auto-generated* `~/.android/debug.keystore`. That is a different key,
  so a debug APK **cannot** be installed over a CI-built app: Android reports
  "App not installed as package conflicts with an existing package". This is a
  signature mismatch and no versionCode bump can fix it. Do not hand the user a
  debug APK when the installed app came from CI.
- Pushing a `v*.*.*` tag fires both `flow-debug-apk.yml` and `release.yml`, so
  tagging publishes. Do not tag while the release gate above is closed.

## What a future session must not forget

1. Read Workstreams A-D before touching JamesDSP, Home, or Gemini; the user
   asked for the plan file to be the refresher every compaction.
2. JamesDSP is now implemented in the chain and has a real control sheet, but
   its **GPLv2/GPLv3 compatibility is unresolved**. It is in the next
   early-access artifact only. Do not public-release or tag it until the
   licence path in Workstream A is closed; do not call the integration finished
   and do not remove the notice.
3. Gemini "play X" is already implemented (`MEDIA_PLAY_FROM_SEARCH` manifest
   filter + `MusicLink.consume` -> `LinkRequest.Search(play = true)`); empty
   query resumes. Verify on device before writing anything new.
4. Hide Flow on Home exists (`AppSettings.homeFlowVisible`, switch in
   Customisation -> Home). The remaining gap is the *top row layout*: Quick
   Picks beside Flow, carousel that folds the previous card.
5. Home topic reordering exists as Settings -> Customisation -> Home up/down
   arrows (`AppSettings.homeShelfOrder`). Inline drag-and-drop was not built.
6. Disable Flow ALREADY EXISTS and is in the shipped test build: Settings ->
   Appearance -> "Show Flow on Home" (`SettingsSheet.kt` ~line 869), and again in
   Settings -> Customisation -> Home. It writes `AppSettings.homeFlowVisible` and
   `MainActivity` gates only the synthetic Flow shelf on it (see `homeWithFlow`
   ~line 861), so Quick Picks and every other shelf stay. Do not rebuild this
   from scratch; the user just needs to be told where it is. The user has asked
   for it three times, so surface it prominently in Settings.
