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