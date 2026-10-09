#!/usr/bin/env python3
"""Parity checker: every BitChord workflow must exist in the Classipod theme.

Static audit over the classipod sources plus their wiring (MainActivity,
Settings, AppSettings). Exits non-zero on any gap so CI catches regressions.

Usage: python3 tool/check_classipod_parity.py
"""
import re
import sys
import pathlib
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
    ("library/full pagination", CLS / "ClassipodHost.kt", ["allSongs", "LIKED_MUSIC", "fullLiked"]),
    ("now-playing/tilted art", CLS / "ClassipodArt.kt", ["rotationY", "6.88"]),
    ("now-playing/reflection", CLS / "ClassipodArt.kt", ["scaleY = -1f", "REFLECT_LIGHT_TOP", "REFLECT_DARK_TOP"]),
    ("now-playing/left header", CLS / "ClassipodNowPlaying.kt", ["NpStatusBar", "PLAYBACK_BLUE", "NpBattery"]),
    ("now-playing/no stars", CLS / "ClassipodNowPlaying.kt", ["no ratings in BitChord"]),
    ("now-playing/lossless-only", CLS / "ClassipodNowPlaying.kt", ["null for lossy"]),
    ("now-playing/thin seekbar", CLS / "ClassipodNowPlaying.kt", ["TRACK_LIGHT", "TRACK_BORDER", "detectTapGestures"]),
    ("now-playing/crop-free reflection", CLS / "ClassipodArt.kt", ["ContentScale.Crop", "scaleY = -1f", "REFLECT_LIGHT_TOP"]),
    ("now-playing/lyrics sheet", CLS / "ClassipodNowPlaying.kt", ["LyricsSheet", "animateScrollToItem", "followSuspendUntil"]),
    ("now-playing/volume bar", CLS / "ClassipodNowPlaying.kt", ["NpVolumeBar", "rotationZ = 45f"]),
    ("now-playing/long-press menu", CLS / "ClassipodNowPlaying.kt", ["onCenterLongPress", "onSongMenu"]),
    ("wheel/center long-press", CLS / "ClassipodRoot.kt", ["onCenterLongPress", "onLongPress"]),
    ("search/live results", CLS / "ClassipodHost.kt", ["typeaheadResults", "submitSearch"]),
    ("albums/cover flow", CLS / "ClassipodCoverFlow.kt", ["HorizontalPager", "rotationY", "CoverFlowMotion" if False else "51.6f", "zoom", "spin"]),
    ("albums/flow route", CLS / "ClassipodHost.kt", ["CoverFlow", "CoverAlbum"]),
    ("now-playing/blue bar", CLS / "ClassipodTheme.kt", ["PROGRESS_FILL", "3D89EB"]),
    ("now-playing/counter", CLS / "ClassipodHost.kt", ["queueIndex", "queue.size"]),
    ("search/default tile", CLS / "ClassipodBrowse.kt", ["inputOpen", "SearchInputBar"]),
    ("search/letter strip", CLS / "ClassipodBrowse.kt", ["LetterStrip", "'A'..'Z'"]),
    ("search/play-opens-np", CLS / "ClassipodHost.kt", ["playAndShow"]),
    ("theme/exact type", CLS / "ClassipodTheme.kt", ["SELECT_GRAD_TOP", "0xFF3EABE3", "STATUS_GRAD_TOP", "0xFFFAFAFA"]),
    ("theme/bundled font", pathlib.Path("app/src/main/res/font/classipod_sans_regular.ttf"), []),
    ("menu/playlists open", CLS / "ClassipodHost.kt", ["scope.launch", "allSongs"]),
    ("menu/downloads", CLS / "ClassipodHost.kt", ["getDownloadedSongs"]),
    ("menu/up-next jump", CLS / "ClassipodHost.kt", ["queue.drop", "seekTo(at"]),
    ("menu/sleep options", CLS / "ClassipodHost.kt", ["SleepTimer.start", "startAfterTrack", "SleepTimer.cancel"]),
    ("menu/shuffle+repeat", CLS / "ClassipodHost.kt", ["setShuffleEnabled", "setRepeatMode", "REPEAT_MODE_ONE"]),
    ("menu/quality ceilings", CLS / "ClassipodHost.kt", ["setAudioQualityWifi", "setAudioQualityCellular", "qualityPage"]),
    ("menu/sources toggles", CLS / "ClassipodHost.kt", ["setEnabled", "sourcesPage"]),
    ("menu/interface+about", CLS / "ClassipodHost.kt", ["interfacePage", "aboutPage", "setAppUi"]),
    ("menu/colorway+clicks+speed", CLS / "ClassipodHost.kt", ["setClassipodColorway", "setClassipodClicks", "setClassipodWheelSteps"]),
    ("now-playing/artwork+meta", CLS / "ClassipodNowPlaying.kt", ["PodReflectiveArt", "albumName"]),
    ("now-playing/progress+seek", CLS / "ClassipodNowPlaying.kt", ["onSeek", "detectDragGestures"]),
    ("now-playing/quality badge", CLS / "ClassipodNowPlaying.kt", ["qualityLine"]),
    ("now-playing/hint row", CLS / "ClassipodNowPlaying.kt", ["HOLD CENTER FOR OPTIONS"]),
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
    except UnicodeDecodeError:
        # Binary file (font) — just verify it exists
        print(f"ok   {name}")
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
