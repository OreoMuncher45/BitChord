# Apple Music UI port — working plan

Status as of the end of the 2026-10-18/19 session. Nothing below is started.

## Where things stand

`main` is **v2.1.17 exactly**, plus one thing: Convx's Liquid Glass engine,
ported as source. Tagged and released as **v2.2.0-convx**.

Everything from the earlier v2.2.0–v2.2.6 line was reverted. That line was a
hand-rolled Apple Music shell (1,298 lines) that wrapped the existing screens
instead of porting Convx, and shipped twice with dead controls. It is preserved
on the `archive/am-shell-2.2.x` branch and should not be revived.

Reported on-device: the Convx engine looks better and runs smoother than what it
replaced. `GlassPool.kt` is why — it pools backdrop layers instead of
re-capturing per surface, and it was one of twelve files that were missing
before the port.

**There is no Apple Music shell yet.** The app is the v2.1.17 UI with Convx
glass underneath. That is the whole gap.

## Reference material

- Apple screenshots: `/mnt/gdrive/apple-music-ui/` (official marketing images;
  `manifest.tsv` maps each file to its source URL)
  - `apple-music/iphone/apple-music-iphone-01.png` — Home and the tab bar
  - `apple-music/iphone/apple-music-iphone-03.png` — Now Playing
- Convx source: `/home/ubuntu/convx-repo` (GPL-3.0)

## The target

Now Playing, per `apple-music-iphone-03.png`, top to bottom:

1. Grabber pill
2. Small artwork + title/artist, with star and `•••` circular buttons right
3. Full-bleed blurred artwork behind everything
4. Large left-aligned lyrics — current line solid, neighbours faded
5. Translate pill floating right
6. Progress bar: elapsed left, `Sing` centred, remaining right
7. Rewind / Pause / Forward
8. Volume slider with speaker icons
9. Bottom row: lyrics · AirPlay · queue

Home, per `apple-music-iphone-01.png`: large title, avatar top-right, section
headers with a `>` chevron when tappable, tall hero cards, square album rows.

Tab bar: capsule with Home / New / Radio / Library, and **Search as a separate
circular button outside the capsule**. Selected tab is red icon *and* red label.

## Open decision — icon set

Convx ships lookalike drawables and the user has rejected them as not exact.
Apple's own set is **SF Symbols** (`play.fill`, `pause.fill`, `backward.fill`,
`forward.fill`, `star.fill`, `ellipsis`, `speaker.wave.1-3.fill`,
`quote.bubble`, `list.bullet`, `airplayaudio`, `dot.radiowaves.left.and.right`).
Coverage is complete — there is no gap.

**Blocker:** SF Symbols' licence permits use in applications built for Apple
platforms, not redistribution inside an Android APK. The user is aware and is
deciding. Do not assume it is approved; confirm before shipping it.

This is why step 1 puts every icon behind one indirection — it makes the choice
a one-file swap instead of a rewrite.

## Steps

### 1. `PlayerIcons` indirection
One object mapping each Now Playing control to a drawable. ~15 call sites
across `PlayerControls.kt`, `NowPlayingScreen.kt`, `LandscapePlayer.kt`,
`AudioOutputSheet.kt`, `ArtworkMeshBackdrop.kt`. Safe set to start.

### 2. Apple icon swap
Swap the transport, volume, queue, lyrics, star and ellipsis set through
`PlayerIcons`. No layout changes.

### 3. Artwork zoom
`ArtworkMeshBackdrop.kt` is the **blurred background layer — leave it alone.**
The visible artwork is a separate element sized in `NowPlayingScreen.kt`.
Scale it to Apple's crop: scaled past the frame so it crops and bleeds behind
the header and controls, rather than fitting inside. A transform multiplier
plus overflow. Low risk, no re-rendering changes.

### 4. Held-menu Liquid Glass + morph
`app/.../components/HeldContextMenu.kt` (344 lines) — currently paints a flat
`plate` colour with `RoundedCornerShape(20.dp)` / `16.dp`. No glass.

- Swap `plate` for the ported `Modifier.liquidGlass(...)`.
- Morph the container out of the recorded press origin.

**Menu contents, options and callbacks stay exactly as they are.** Only the
surface changes.

The origin is already recorded: `sharedUi/.../components/LongPressOrigin.kt`
has `HoldCapture` (position + `recordedAt`) behind `longPressMenuClickable`,
already wired into Home, Common and shelf items. The morph needs no new
capture machinery — the existing `cardShape` 20dp → `menuShape` 16dp pair is
what animates.

Route this through the same pooled backdrop path as the rest of the glass. A
menu drawing its own backdrop would undo the smoothness the pool provides.

### 5. Tests
Every control gets an interaction test. `ProfileGestureTest` shows the pattern:
the decision that can be pulled out of Compose is extracted and tested as plain
logic so it runs on any host.

### 6. Build
`:app:compileDevDebugKotlin` → unit tests → `verify_ui_parity.py --strict` →
CI `Flow Debug APK` (the four-ABI minified build) → attach APKs to the release.

Attach APKs and publish the release in the same session as the build. Two
earlier releases shipped with nothing attached, or as a draft nobody could see.

## Known environment constraints

- **Native builds cannot run on this host.** The NDK's x86_64 clang segfaults
  under qemu on this aarch64 machine. Reproduces on an unmodified tree. The
  APK build is CI's job — `Flow Debug APK` is the workflow that produces the
  release assets.
- **Robolectric cannot run on this host either.** It loads Conscrypt's native
  library by JNI and Conscrypt publishes no linux-aarch64 build. Those tests are
  excluded on aarch64 via `build.gradle.kts` and run for real on CI's x86_64.
- The `Android Release APK` (signed prod) workflow fails on the repo's
  keystore — `Tag number over 30 is not supported`. Pre-existing; also fails on
  v2.2.4. Blocks every signed prod build until fixed separately.
- 16 unit tests fail on a clean tree: `GeniusTest` and `LocaleStringsTest`. This
  is the baseline, not a regression.

## Rules for this work

- A screen is not done until it compiles **and** has an interaction test that
  taps its controls.
- Never report something as working on the strength of "it compiled". That is
  how a dead account button, a dead account menu and an entire invisible glass
  engine were each reported as shipped.
- Convx-derived code is GPL-3.0 and BitChord is GPL-3.0. `NOTICE` records the
  vendoring; keep it accurate as more is ported.

## Untracked leftovers

24 Convx drawables and 3 PNGs were copied into `res/` and are wired to nothing.
They are placeholders for step 2. Delete them if the icon set changes.

## Still open, not planned

- Home screen, tab bar, Radio as its own destination, Library, Search
- The press-and-hold menu question is answered above; the other menus were not
  investigated
## Now Playing restyle — exact measurements (authoritative, from the owner)

Units: W = screen width; 1% W = 10.8px on the owner's 1080px-wide screen. All Y
from the top of the screen. Positions are %-of-WIDTH, never %-of-height.

1) ALBUM ART (banner): scale ×1.234 uniform, transform-origin 50% 0 (top
   centre), then translateY +1.6% W (~17px). On a wrapper so it multiplies with
   the existing sleeve animation, not replaces it. Full-width art becomes
   123.4% W, ~11.7% W cropped each side. Bottom fade/mask scales with the art.
2) TITLE ROW: title/artist font ×0.86. Title top Y = 122.7% W (1325px). Artist
   line bottom Y = 132.2% W (1428px). Star/ellipsis diameter 8.0% W (86px,
   ×0.84), gap between them 4.0% W, vertical centre Y = 127.5% W (1377px),
   ellipsis right edge at 92% W. Left margin 8.4% W.
3) PROGRESS: centre Y = 142.5% W (1539px, current). Thickness 1.7% W (18px,
   from 20). Spans 8%–92% W. Time labels centre Y = 148.0% W (1595px, +9px).
4) CONTROLS (row centre Y = 167.0% W, unchanged): pause 7.4% W × 9.9% W
   (80×107px; bars 3.0% W, gap 1.4% W; ×0.60 of current), centred at 50% W.
   Rewind/forward 9.8% W × 5.6% W each (105×60px, ×0.95), centres 23.4% W
   (253px) and 76.5% W (826px) — each moves inward ~33px.
5) VOLUME: row centre Y = 189.5% W (2047px, +25px). Bar thickness 1.6% W
   (17px). Track 14.5%–83.3% W. Speakers at 8.9% W and 91% W.
6) BOTTOM ROW: centre Y = 200.5% W (2161px, +7px). Icon size unchanged
   (~5.4% W). Lyrics centre X = 21.5% W (232px), queue centre X = 78.2% W
   (845px), centre icon at 50% — outer icons move inward ~63px.

DO NOT MODIFY: status bar, grabber, "Playing from…", Opus/Automix lines,
lyric-preview row, device label. Info lines overlay the art's lower fade
(z above art). Title moving down leaves only ~13px to the lyric-preview row —
reduce its spacing or hide if cramped. VERIFY at 1080px wide, every Y ±3px.

## Playback bug report — one user, SM-S928B, Android 16, build 2.2.2

Symptom: selecting a song does not auto-play (no buffering shown either); track
switches land paused and must be resumed manually. Log preserved by the owner,
key line: `id=TFTnJzWRTJ8`, `substitution=true request=Lossless`.

### What the log proves

1. **Track start took ~23 SECONDS, all of it in the resolver, none of it
   "buffering".** `TIMING track selected` 22:21:45.9 → `first audio` at
   22:22:08.8 (22936ms). The cost is two 8s `PlayerRequestTimeoutException`s
   on the same VISIONOS client (45.9→53.8, →62.0), then "no playable clients",
   then a THIRD attempt on the same client succeeding at 20.4s elapsed. The
   resolver retried the same client into the same timeout twice before the
   failover that worked.
2. **"Doesn't show buffering" is explained: nothing is buffering.** The 20s is
   resolve time, before any media item reaches the player — the player has no
   buffering state to report, so the UI correctly shows nothing. The UI needs
   a resolving state, or the resolve needs to stop taking 20s. Both, ideally.
3. **Queue warm-up was cancelled**: `queue warm-up skipped TFTnJzWRTJ8:
   StandaloneCoroutine was cancelled` — so the prefetch of this track never
   ran, guaranteeing a cold resolve.
4. **The track-switch path looks different from the first-selection path.** At
   22:36:20 (the "switch song" case): `audio switch applying 'Naal Nachna
   (From "Dhurandhar")' (P4UnrmPFPA4)` — the LOSSLESS SUBSTITUTION flow, active
   because request=Lossless — then decoder init and AudioTrack within 122ms,
   **but no `TIMING first audio` line ever follows**, plus two tidal searches
   cancelled mid-flight. Prepared but never started = lands paused = the
   "must resume" symptom. Prime suspect: the audio-switch/substitution path
   does not carry playWhenReady.
5. Tidal addon degraded (`1 of 8 instances live`) — irrelevant to the main bug;
   it only slows the lossless chase.

### Fixes to investigate (next session, in order of expected payoff)

1. The audio-switch/substitution path carrying `playWhenReady` into the
   prepared source — the 22:36 event is the smoking gun.
2. Resolver failover: stop retrying the same VISIONOS client into the same 8s
   timeout twice; fail over to the next client on first timeout.
3. A resolving state in the Now Playing UI for the pre-player gap.
4. Why queue warm-up is being cancelled on selection.

## SpotiFLAC glass study — cloned, analysed (MIT, /home/ubuntu/spotiflac)

Owner request: copy SpotiFLAC's navbar + their "10x smoother" liquid glass as a
v2 engine option with a customisable intensity setting. Complaint driving it:
our glass janks during the player sheet's swipe up/down on low-end devices.

### The hard finding: nothing copies, three things port

SpotiFLAC is **Flutter**. Their navbar is not glass at all — it is Material 3
Expressive's `M3ENavigationBar` (springy pill, themed background). Their glass
(`MornyeLiquidBackdrop`, package `liquid_glass_easy` v5) is a separate bounded
lens. Zero lines move into Kotlin/Compose. What ports:

1. **Their glass parameters** — 1:1 onto our `GlassEffectConfig`:
   clarity→vibrancy, blurSigma→blurRadius, refractionDepth/Width→
   lensHeight/lensAmount, dispersion→chromaticAberration,
   highlightIntensity→highlightOpacity, progress(effect scale)→an intensity
   multiplier. An intensity setting is therefore a straight multiplier at the
   `LocalGlassEffectConfig` provider site in MainActivity.

2. **Shader warmup** (`MornyeGlassWarmup`): their shaders load and a detached
   scene rasterises once, scheduled AFTER first-frame rasterised and away from
   entrance/scroll, never competing with animation. Ours (AGSL
   `RuntimeShader`s, `RuntimeShaderCache.kt`) compile lazily on first use —
   the first-swipe jank. Port: build every shader once at startup.

3. **Interaction gating**: their lens takes `interaction: false` while a
   gesture runs, or scales the effect by `progress`. Our ported engine already
   HAS the machinery and nothing drives it: `LocalAppBackdropFrozen`,
   `BackdropFreeze`, `NavTransitionFreeze` are declared and never provided.
   Drive them during the player sheet swipe — the swipe jank fix.

### Why theirs is smoother, in one sentence

Their lens never captures the screen — it is procedural over compositor
content; ours re-records the page layer and re-blurs real content per surface,
per frame, throughout the sheet transition.

### Build order (next session)

1. Glass intensity setting (0–100% slider; also the low-end performance lever).
2. Shader warmup at startup.
3. Freeze the backdrop during the sheet swipe (drive the existing locals).
4. Navbar alternative as a Settings option — lowest value; our
   FloatingTabBar already does the springy pill their navbar has.
5. NOTICE: add SpotiFLAC (MIT, zarzet 2026), `liquid_glass_easy`, `mornye`.

### License note

SpotiFLAC is MIT — techniques and design port legally with attribution;
their Dart code itself is not being copied.

## SpotiFLAC extension registry — investigation complete, integration plan

Owner request: add https://raw.githubusercontent.com/spotiflacapp/spotiflac-extension/main/registry.json
as a fourth backup source ("BitChord Backup Addon"), Tidal and Qobuz especially,
configured to STREAM rather than download.

### What the ecosystem actually is

- `registry.json`: 12 extensions, each with `download_url` (a `.sflx`), `sha256`,
  `min_app_version`. Tidal = `tidal-web` v1.2.9, Qobuz = `qobuz-web` v1.2.23.
- `.sflx` = a ZIP of `manifest.json` + `index.js`. Nothing else.
- **Full readable sources exist**, Apache-2.0: `/home/ubuntu/spotiflac-ext-src/sources/<id>/`
  (built by `scripts/build_packages.py`). Mornye (the runner app) is MIT.
  Both permissive → compatible with our GPL-3.0 with NOTICE attribution.
- Mornye's runtime is NOT QuickJS — it is a Go/Rust backend (Goja) whose host
  bridge is `rust_backend/crates/extensions/src/prelude.js` (763 lines).
- BitChord's own module system (`data/sources/module/`, QuickJsExecutor +
  ModuleManager) is a port of Convx's ModuleManager — same lineage, different
  host API. It evaluates an IIFE into `__spine_mod` and calls exactly two
  exports: `searchTracks` and `getTrackStreamUrl`.

### The host contract a .sflx needs (the gap to close)

The extension's top level calls `registerExtension({...})` — BitChord's executor
would throw on load today. Required host surface, from prelude.js + the tidal
extension source:

1. `globalThis.registerExtension(value)` — stores the export object.
2. **`globalThis.session = { signedFetch(method, path, body, headers) }`** — the
   core. Per manifest: baseUrl `https://api.zarz.moe/v2`, scheme `ZARZ-HMAC-V1`,
   header prefix `X-Zarz-`, 300s time window, endpoints
   bootstrap/challenge/exchange/refresh. Mornye implements the handshake
   host-side (crate `extensions/src/environment.rs` + `auth/`); the extension
   never sees keys. Flow used for streaming: `POST /tickets` → ticket id →
   `POST /dl/tid` with `X-Zarz-Ticket` → playback URL(s)/manifest.
3. `globalThis.storage` (get/set/remove) and `globalThis.credentials`
   (get/store/remove/has) — prefs-backed, per-extension namespacing.
4. Network `fetch` gated by the manifest's `permissions.network` host allowlist
   (tidal.com, resources.tidal.com, *.audio.tidal.com, *.manifest.tidal.com,
   api.zarz.moe, …).
5. `log` (info/warn/error), and `file.downloadSegments` — only genuinely needed
   for real downloads; NOT needed if we only stream (see below).
6. Manifest fields we must honour: `type` (metadata_provider /
   download_provider), `qualityOptions`, `requiredRuntimeFeatures`
   (`signedSession@3`, `sessionGrant@1`, `downloadSegments@1`), `minAppVersion`.

### How "stream instead of download" works

The tidal/qobuz extensions acquire playback URLs INSIDE their `download()`
export (signedJSON → /dl/tid → manifest with apiURL + mirrors). For streaming
we call the same export and hand the returned URL/manifest to the player as a
stream, never invoking `file.downloadSegments`. That makes `downloadSegments@1`
unreachable in our build — acceptable; the feature check should then be
softened to "warn if missing" rather than refuse to load.

### Build order (subagent-shaped, disjoint files)

1. **Registry loader** (new file(s) under `data/sources/addon/`): fetch
   registry.json, download `.sflx`, sha256-verify, unzip, parse manifest,
   expose for install. UI: a fourth backup source entry — "BitChord Backup
   Addon" — in the Sources settings, listing registry extensions.
2. **Extension host in QuickJsExecutor**: prelude adaptation — define the
   globals above BEFORE evaluating index.js; after evaluation read
   `globalThis.extension` instead of `__spine_mod`. Bridge `session.signedFetch`
   to a Kotlin implementation of the zarz HMAC scheme (read Mornye
   `environment.rs` + `auth/` first — the exact signing recipe lives there).
   Keep our existing two-export modules working: the prelude is additive.
3. **Source integration**: a new `SflxSource` joining the existing fallback
   chain (see `data/sources/SourceRegistry.kt`, `TidalSource.kt`,
   `QobuzQueueSource.kt`, `AddonOrderTest` / `AddonPolicyTest` for the
   ordering/policy rules). Stream path: `download()` → feed URL to player.
   Search path: `searchTracks`. Skip `downloadSegments`.
4. **Settings + policy**: enable/disable per extension; the existing
   substitution chain ("sources: substitution=true request=Lossless") treats
   the new source as the fourth rung.
5. **NOTICE**: SpotiFLAC-Extension (Apache-2.0) + Mornye (MIT) attribution.

### Risks / honest notes

- The zarz.moe broker is a third-party service. "Consistently always works" is
  the broker's reliability, not something our code can guarantee — we inherit
  an upstream dependency and should degrade gracefully when it is down
  (mirror URLs exist in the extension config as fallbacks).
- Legal: we are running Apache-2.0 code under GPL-3.0 with attribution — clean.
  The STREAMING of the content itself is exactly what the extensions were
  built to enable; no new exposure beyond what BitChord already does with its
  existing Tidal/Qobuz addons.
- Version gates: registry entries carry `min_app_version` (e.g. 4.9.1) —
  honour or ignore deliberately, don't crash on mismatch.
