# JamesDSP in BitChord

This directory vendors the DSP engine from **JamesDSP / libjamesdsp**, the
cross-platform audio effect library behind Rootless JamesDSP.

- Rootless JamesDSP: https://github.com/ThePBone/RootlessJamesDSP (the wrapper
  app is GPL-3.0)
- libjamesdsp upstream: https://github.com/ThePBone/JamesDSPManager
  (`extensions` branch, commit `1b9f89dbd4b332a07724beb41d58bc2ffcb05395`)
- Engine license: GNU General Public License **v2.0**. The upstream license
  text is in `LICENSE` in this directory.
- Compatibility note: BitChord is GPLv3, and GPLv2-only code cannot simply be
  combined into a GPLv3 work. This engine is present in early-access test
  artifacts while that gate is resolved; see `OVERHAUL_PLAN.md` Workstream A1.
  Do not treat the integration as cleared for a public release.

## What is vendored

`libjamesdsp/` is the engine source tree copied from Rootless JamesDSP's
`app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp`, together
with the adjacent `cpthread.c` / `cpthread.h`. `jni/JamesDspJni.cpp` is
BitChord's own small JNI shim. It does not include Rootless JamesDSP's
AudioRecord capture, AudioEffect session wrapper, UI, or live-programming
front end.

BitChord's Kotlin-side integration (`JamesDspProcessor.kt`, `JamesDspNative.kt`
and the `JamesDspSheet` controls) is original code, not copied from Rootless
JamesDSP. It feeds the engine the Float32 stream BitChord already decodes,
scoped to BitChord's own two ExoPlayer instances for crossfade.

## Why it is here

JamesDSP is run in-process instead of as a rooted system effect. That keeps the
effect limited to BitChord's playback, works on unrooted devices, and avoids
capturing other apps. The trade is that JamesDSP's live-prog editor, convolver
file picker and Viper DDC profiles are not exposed in this first integration;
the engine still contains those code paths, but BitChord's v1 UI drives bass
boost, stereo widening, tube drive, reverb, pre-level trim and the limiter.

## Updating

Re-copy the `jdsp` directory from the libjamesdsp commit above, then re-apply
nothing: the JNI shim only uses the public `jdsp_header.h` API. Keep the
`-Wno-incompatible-*` flags in `CMakeLists.txt` unless a newer engine revision
has fixed the old C function-pointer declarations for Clang 18.
