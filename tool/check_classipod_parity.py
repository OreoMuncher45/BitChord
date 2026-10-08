#!/usr/bin/env python3
"""Parity checker: every BitChord workflow must exist in the Classipod theme.

Static audit over the classipod sources plus their wiring (MainActivity,
Settings, AppSettings). Exits non-zero on any gap so CI catches regressions.

Usage: python3 tool/check_classipod_parity.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CLS = ROOT / "app/src/main/java/com/music/bitchord/ui/classipod"
APP = ROOT / "app/src/main/java/com/music/bitchord"

CHECKS = [  # list of (name, file, required patterns — ALL must match)
    # (name, file, required patterns — ALL must match)
    ("shell/status-bar", CLS / "ClassipodRoot.kt", ["ClassipodStatusBar", "BatteryGlyph"]),
    ("shell/click-wheel rotary", CLS / "ClassipodRoot.kt", ["angleOf", "stepsPerTurn", "onStep"]),
    ("shell/wheel zones", CLS / "ClassipodRoot.kt", ["WheelZone", "MENU", "CENTER", "onPlayPause"]),
    ("shell/touch select", CLS / "ClassipodRoot.kt", ["detectTapGestures"]),
    ("shell/real click sound", CLS / "ClassipodClicks.kt", ["SoundPool", "ipod_click"]),
    ("theme/palette 1:1", CLS / "ClassipodTheme.kt", ["0xFFF2F2F2", "0xFF121418", "COLORWAYS"]),
    ("menu/tree root", CLS / "ClassipodHost.kt", ["Listen Now", "Up Next", "Now Playing"]),
    ("menu/flow entry", CLS / "ClassipodHost.kt", ["FlowHome", "flowStatus"]),
    ("menu/search", CLS / "ClassipodHost.kt", ["ClassipodPage.Search", "onQueryChange"]),
    ("menu/library browse", CLS / "ClassipodHost.kt", ["pushArtists", "pushAlbums", "Liked Songs"]),
    ("menu/playlists open", CLS / "ClassipodHost.kt", ["pendingDetail", "openDetail"]),
    ("menu/downloads", CLS / "ClassipodHost.kt", ["getDownloadedSongs"]),
    ("menu/up-next jump", CLS / "ClassipodHost.kt", ["queue.drop", "seekTo(at"]),
    ("menu/sleep options", CLS / "ClassipodHost.kt", ["SleepTimer.start", "startAfterTrack", "SleepTimer.cancel"]),
    ("menu/shuffle+repeat", CLS / "ClassipodHost.kt", ["setShuffleEnabled", "setRepeatMode", "REPEAT_MODE_ONE"]),
    ("menu/quality ceilings", CLS / "ClassipodHost.kt", ["setAudioQualityWifi", "setAudioQualityCellular", "qualityPage"]),
    ("menu/sources toggles", CLS / "ClassipodHost.kt", ["setEnabled", "sourcesPage"]),
    ("menu/interface+about", CLS / "ClassipodHost.kt", ["interfacePage", "aboutPage", "setAppUi"]),
    ("menu/colorway+clicks+speed", CLS / "ClassipodHost.kt", ["setClassipodColorway", "setClassipodClicks", "setClassipodWheelSteps"]),
    ("now-playing/artwork+meta", CLS / "ClassipodNowPlaying.kt", ["AsyncImage", "albumName"]),
    ("now-playing/progress+seek", CLS / "ClassipodNowPlaying.kt", ["onSeekFraction", "formatMs"]),
    ("now-playing/quality badge", CLS / "ClassipodNowPlaying.kt", ["qualityLine", "discordAudioQualityLine"]),
    ("now-playing/like", CLS / "ClassipodNowPlaying.kt", ["onToggleLike", "Loved"]),
    ("now-playing/volume wheel", CLS / "ClassipodNowPlaying.kt", ["onVolume"]),
    ("flow/moods all six", CLS / "ClassipodFlow.kt", ["FlowMood.entries"]),
    ("flow/discovery+memory", CLS / "ClassipodFlow.kt", ["draftDiscovery", "draftMemory"]),
    ("flow/genres", CLS / "ClassipodFlow.kt", ["FLOW_TUNER_GENRES", "draftExcluded"]),
    ("flow/apply batch", CLS / "ClassipodFlow.kt", ["dirty", "onApply"]),
    ("flow/new mix+save", CLS / "ClassipodFlow.kt", ["onNewMix", "onSave"]),
    # No keyboard on iPod: saves auto-name (My Flow / Flow · mood) instead of
    # the dialog. Assert the rule, not the dialog.
    ("flow/save auto-names", CLS / "ClassipodHost.kt", ["My Flow", "Flow · ${flowMood.label}"]),
    ("host/activity hook", APP / "MainActivity.kt", ["ClassipodHost", "AppUi.CLASSIPOD"]),
    ("host/flow+menu actions", APP / "MainActivity.kt", ["onStartFlow", "openSongMenu"]),
    ("settings/toggle both ways", APP / "ui/screens/SettingsSheet.kt", ["setAppUi", "AppUi.entries"]),
    ("settings/persisted", APP / "data/settings/AppSettings.kt", ["KEY_APP_UI", "KEY_CLASSIPOD_COLORWAY", "KEY_CLASSIPOD_CLICKS", "KEY_CLASSIPOD_WHEEL_STEPS"]),
]

failures = []
for name, path, patterns in CHECKS:
    try:
        text = path.read_text()
    except FileNotFoundError:
        failures.append(f"{name}: MISSING FILE {path}")
        continue
    missing = [p for p in patterns if p not in text]
    if missing:
        failures.append(f"{name}: missing {missing}")
    else:
        print(f"ok   {name}")

print()
if failures:
    print(f"{len(failures)} GAPS:")
    for f in failures:
        print(f"  FAIL {f}")
    sys.exit(1)
print(f"ALL {len(CHECKS)} CHECKS GREEN — every workflow is ported.")
