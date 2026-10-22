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
- Keep `versionCode 75 / versionName 2.2.13` until the overhaul is ready for a
  release decision. Do not bump or publish without being asked.
- No emulator/adb on this machine. User verifies APKs on a real device.
- Build locally with the QEMU workaround described under **Build commands**.
  Restoring qemu 4.2.1 breaks the NDK build.

## Current baseline

- v2.2.13 is already released. Do not redo icon, halo, nav, Qobuz, Octave, or
  method-size work as part of the overhaul.
- `BitChordApp` method-size headroom is only 1,338 bytes after extracting
  `QobuzUnlockHost`, `WebSessionHost`, and `DiscordLoginHost`. Any sizable new
  UI must go in its own composable/file, not inline into `BitChordApp`.
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
     - Now Playing three-dot/menu.
     - Settings, separately, under Playback beside Equalizer.
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

### A1. Feasibility and license gate

- Upstream engine: `james34602/JamesDSPManager`, commit
  `5aed6a4a9a4cb109e2d8246e103bb1578cb83cfb` (verified at plan creation).
- Native API already exposes interleaved Float32 processing:
  `JamesDSPInit`, `JamesDSPGlobalMemoryAllocation`,
  `JamesDSPGlobalMemoryDeallocation`, `JamesDSPGetMutexStatus`,
  `JamesDSPSetSampleRate`, and `processFloatMultiplexd`.
- RootlessJamesDSP proves the API can run on Android without root, but its
  system-wide internal-capture service is **not** what we need. We only need its
  native wrapper/API pattern.
- `Main/LICENSE` in JamesDSPManager is GNU GPL v2. BitChord is GPLv3. Before a
  public release, resolve compatibility. Practical options:
  - confirm the relevant source is effectively GPLv2-or-later;
  - keep the engine in a clearly separable optional component with complete
    corresponding source and license notices;
  - or choose a GPLv3-compatible engine implementation.
  Do not silently vendor it without a license notice. This is a release gate,
  not a reason to skip local development.

### A2. Vendor native sources

Preferred layout:

```text
app/src/main/cpp/jamesdsp/
  libjamesdsp/                 # pinned upstream engine sources
  jamesdsp_jni.cpp             # our JNI bridge
  CMakeLists.txt               # or add sources to app/src/main/cpp/CMakeLists.txt
  LICENSE.libjamesdsp          # GPLv2 text
  NOTICE.md                    # origin, commit, modifications
```

- Keep the engine source in-tree/subtree so CI does not depend on recursive
  submodules. Source size is about 5.4 MB.
- Do not overwrite existing `bitchord_analysis`, `bitchord_dsd`, or
  `bitchord_dst` targets. Add a separate `bitchord_jamesdsp` target and link it.
- Compile as C with `-O2`/`-std=gnu11`; preserve 16 KB page alignment with
  `-Wl,-z,max-page-size=16384`.
- Include `armeabi-v7a`, `arm64-v8a`, and `x86_64` (project split set).
- Add license/notice to the app's open-source notices if present.

### A3. JNI and Kotlin bridge

Create a narrow bridge, not a copy of RootlessJamesDSP:

- `app/src/main/cpp/jamesdsp/jamesdsp_jni.cpp`
  - `nativeCreate(maxFrames, sampleRate) -> Long`
  - `nativeDestroy(handle)`
  - `nativeConfigure(handle, sampleRate, channels, maxFrames)`
  - `nativeProcess(handle, FloatArray, frameCount, channels)` in place, or a
    direct-buffer variant to avoid array copies.
  - configuration setters for the controls we expose.
- `app/src/main/java/com/music/bitchord/playback/jamesdsp/JamesDspEngine.kt`
  - owns the native handle.
  - serializes configuration plus `process` calls.
  - bypasses cleanly when disabled or handle is invalid.
- Prefer `GetPrimitiveArrayCritical`/direct `ByteBuffer` over copying a
  `FloatArray` every audio block. `AudioBlock.samples` is interleaved Float32.
- One engine instance per player (A/B), because crossfade keeps two players
  alive. Both must be configured and freed with the service.
- Do not call JNI config setters from the audio thread while processing. Post
  changes to the audio thread or guard with the engine mutex.

### A4. Playback integration

- Add `JamesDspProcessor : FloatAudioProcessor` in
  `app/src/main/java/com/music/bitchord/playback/`.
- Add it to
  `app/src/main/java/com/music/bitchord/playback/audio/DspChain.kt` as the last
  effect before loudness:
  `sing -> spatial -> equalizer -> transition -> jamesDsp -> loudness`.
  This keeps BitChord's loudness limiter final and avoids bypassing the existing
  precision path.
- Create `jamesDspA` and `jamesDspB` in `PlaybackService`, pass them through
  `silenceSkippingRenderers(...)` and `DspChain(...)`, exactly like the
  equalizer pair.
- On `configure(sampleRate, channelCount)`, reconfigure the native engine.
- On `flush()`/`reset()`, flush/reset native state so seeks and track changes do
  not carry reverb/convolver tails.
- Bypass for compressed/passthrough formats, matching the existing processors.
- Observe `AppSettings` in `PlaybackService` and push config changes to both
  engines.
- Measure CPU and underruns on device; convolver/reverb are the expensive paths.

### A5. Settings and control UI

Create a dedicated screen, not inline code in `BitChordApp`:

- `app/src/main/java/com/music/bitchord/ui/screens/JamesDspScreen.kt`
- Add `showJamesDsp`/route handling in `MainActivity` near the Equalizer route.
- Add `onOpenJamesDsp` to `SettingsScreen` (Playback group, beside Equalizer).
- Add an optional row to `SongActionsSheet`:
  `onOpenJamesDsp: (() -> Unit)? = null`, shown when the menu is from Now
  Playing. Wire it in `MainActivity`'s player call site, close the sheet, open
  the screen.
- Do not put this only behind Equalizer; the user asked for two separate entry
  points.

MVP controls, in this order:

1. Master enable/disable and a clear bypass state.
2. Output limiter threshold/release and post gain.
3. Graphic/parametric EQ with reset and a small preset list.
4. Bass boost and stereo enhancement.
5. Crossfeed and reverb.
6. Convolver impulse-response import, with file picker and progress/error state.
7. Advanced compander/VDC/liveprog only after the basics are stable.

Persistence:

- Add app-only settings in `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt`:
  `jamesDspEnabled`, `jamesDspConfig` (JSON or typed keys), and preset name.
- Include them in backup/restore and settings search.
- Default off. Never process audio just because the screen was opened.

### A6. JamesDSP acceptance criteria

- Native libraries build for all three ABIs and load on device.
- With JamesDSP off, output is bit-identical to the pre-change precision path
  (same Unity passthrough contract as the other processors).
- With JamesDSP on, EQ/gain changes are audible and survive track changes,
  seeks, crossfades, and app restart.
- Both crossfade players process independently; no doubled audio, dropouts, or
  one-sided effects during a handoff.
- Now Playing menu and Settings both open the same screen.
- No root/Magisk/internal-capture permission is requested.

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
- Next version number/name after the overhaul. Decided for test builds:
  76 / 2.2.14. Revisit only if the user wants a different public release number.
- AI label field shape is undocumented. Detection is best-effort against the
  player overlay; it must fail open and be re-checked when YouTube changes it.
- Spotify monthly listeners require parsing the public artist page; no official
  API field exists.

## Progress log

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
- 2026-10-22: Install note. `dev` flavour is applicationId `com.dev.bitchord`
  ("BitChord Next"), `prod` is `com.music.bitchord`. Dev debug APKs are signed
  with the *building machine's* `~/.android/debug.keystore`, so a build from a
  different machine (or CI) has a different signature and Android refuses to
  update over it with "conflicts with an existing package". That is a signing
  mismatch, not a versionCode problem — uninstall the old package once, or keep
  every build on one machine/keystore. No `keystore.properties` exists on the
  build server, so `prod` release builds are unsigned and cannot be shipped.

## What a future session must not forget

1. Read Workstreams A-D before touching JamesDSP, Home, or Gemini; the user
   asked for the plan file to be the refresher every compaction.
2. JamesDSP is unstarted. It is the biggest and riskiest item (native engine,
   licensing, integration with both players). Do not claim it is done.
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
