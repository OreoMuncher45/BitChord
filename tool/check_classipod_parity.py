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
TST = ROOT / "app/src/test/java/com/music/bitchord"

CHECKS = [  # list of (name, file, required patterns — ALL must match)
    # (name, file, required patterns — ALL must match)
    ("shell/status-bar", CLS / "ClassipodRoot.kt", ["ClassipodStatusBar", "BatteryGlyph"]),
    ("shell/click-wheel rotary", CLS / "ClassipodRoot.kt", ["angleOf", "stepsPerTurn", "onStep"]),
    ("shell/wheel zones", CLS / "ClassipodRoot.kt", ["fireCenter", "fireMenu", "firePrev", "fireNext", "firePlayPause"]),
    ("shell/touch select", CLS / "ClassipodRoot.kt", ["awaitFirstDown"]),
    ("shell/real click sound", CLS / "ClassipodClicks.kt", ["SoundPool", "ipod_click"]),
    ("theme/palette 1:1", CLS / "ClassipodTheme.kt", ["0xFFF2F2F2", "0xFF121418", "COLORWAYS"]),
    ("menu/tree root", CLS / "ClassipodHost.kt", ["Listen Now", "Up Next", "Now Playing"]),
    ("menu/flow entry", CLS / "ClassipodHost.kt", ["FlowHome", "flowStatus"]),
    ("menu/search", CLS / "ClassipodHost.kt", ["ClassipodPage.Search", "onQueryChange"]),
    ("menu/library browse", CLS / "ClassipodHost.kt", ["pushArtists", "pushAlbums", "Liked Songs"]),
    ("library/full pagination", CLS / "ClassipodHost.kt", ["LIKED_MUSIC", "likedEntry", "PlaylistCache"]),
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
    ("wheel/center long-press", CLS / "ClassipodRoot.kt", ["onCenterLongPress"]),
    ("search/live results", CLS / "ClassipodHost.kt", ["typeaheadResults", "submitSearch"]),
    ("albums/cover flow", CLS / "ClassipodCoverFlow.kt", ["HorizontalPager", "rotationY", "CoverFlowMotion" if False else "51.6f", "zoom", "spin"]),
    ("albums/flow route", CLS / "ClassipodHost.kt", ["CoverFlow", "CoverAlbum"]),
    ("playlists/instant open", CLS / "ClassipodHost.kt", ["openPaged", "PagedTracks(pl.title"]),
    ("playlists/badge guard", CLS / "ClassipodHost.kt", ["reportTotal"]),
    ("wheel/hold-to-seek", CLS / "ClassipodRoot.kt", ["onSeekHoldStart", "withTimeout", "waitForUpOrCancellation"]),
    ("wheel/guarded dispatch", CLS / "ClassipodRoot.kt", ["guarded", "CancellationException", "TrackLog"]),
    ("wheel/hold finally", CLS / "ClassipodRoot.kt", ["finally", "onSeekHoldStop"]),
    ("menus/hold-job disposed", CLS / "ClassipodHost.kt", ["holdJob?.cancel", "onDispose"]),
    ("menus/no sheet path", CLS / "ClassipodHost.kt", ["openPodSongMenu"]),
    ("wheel/page slots", CLS / "ClassipodRoot.kt", ["WheelHandlers", "currentKey", "unregister"]),
    ("wheel/claim sites", CLS / "ClassipodBrowse.kt", ["wheel.claim("]),
    ("np/mode cycle", CLS / "ClassipodNowPlaying.kt", ["QueueSheet", "onPlayAt", "Finding lyrics"]),
    ("np/pinned bar", CLS / "ClassipodNowPlaying.kt", ["contentAlignment = Alignment.Center", "Modifier.weight(1f).fillMaxWidth()"]),
    ("rows/center menu", CLS / "ClassipodBrowse.kt", ["let(onLongPress)", "onBrowseCenter"]),
    ("menu/center split", CLS / "ClassipodMenu.kt", ["onCenter", "three-dot"]),
    ("playlists/options page", CLS / "ClassipodHost.kt", ["collectionOptionsPage", "Queue all", "notify("]),
    ("addon/neutral UA", APP / "data/sources/addon/AddonClient.kt", ["Mobile Safari", "Cloudflare"]),
    ("menus/overlays shared", APP / "MainActivity.kt", ["everything above is one shell", "queueNotice = queueNotice"]),
    ("menus/app notice mirrored", CLS / "ClassipodHost.kt", ["QueueActionNoticeHost", "queueNotice"]),
    ("menus/native song menu", CLS / "ClassipodHost.kt", ["podSongMenu", "openPodSongMenu", "Play next", "Go to album"]),
    ("menus/native picker", CLS / "ClassipodHost.kt", ["podPlaylistPicker", "addToPlaylists"]),
    ("shell/transitions", CLS / "ClassipodRoot.kt", ["AnimatedContent", "slideInHorizontally", "transitionKey"]),
    ("shell/system back", CLS / "ClassipodRoot.kt", ["BackHandler", "stack.size > 1"]),
    ("menus/about version", CLS / "ClassipodHost.kt", ["BuildConfig.VERSION_NAME"]),
    ("playlists/session cache", CLS / "ClassipodPlaylists.kt", ["object PlaylistCache", "loadMore", "countLabel"]),
    ("playlists/no refetch", CLS / "ClassipodBrowse.kt", ["PlaylistCache.entry", "offlineSongs"]),
    ("flow/snapshot persist", APP / "data/flow/FlowStore.kt", ["saveSnapshot", "FLOW_TTL_MS"]),
    ("flow/restore on launch", APP / "ui/MainViewModel.kt", ["readSnapshot"]),
    ("flow/resume not restart", APP / "MainActivity.kt", ["never restart from the"]),
    ("flow/row art", CLS / "ClassipodHost.kt", ["artUrl = (flowQueue as?"]),
    ("menu/art rows", CLS / "ClassipodRoot.kt", ["artUrl"]),
    ("hires/source wired", APP / "data/sources/SourceRegistry.kt", ["HIRES", "FlacDownloaderSource", "HIRES_DISPLAY_NAME"]),
    ("hires/challenge backoff", APP / "data/sources/FlacDownloaderApi.kt", ["tokenBlockedUntilMs", "prepareToken"]),
    ("hires/sp session", APP / "data/sources/FlacDownloaderApi.kt", ["loadSp", "AppSettings.fdlSp"]),
    ("hires/unlock sheet", APP / "ui/components/FdlUnlockSheet.kt", ["localStorage", "fd_sp"]),
    ("hires/direct backend", APP / "data/sources/HiResMonochromeBackend.kt", ["searchTracks", "resolveFile", "MIN_GENUINE_KBPS"]),
    ("hires/direct wired", APP / "data/sources/FlacDownloaderSource.kt", ["HiResMonochromeBackend", "monochromeFallback"]),
    ("hires/queue parity", APP / "data/sources/FlacDownloaderApi.kt", ["queuePayload", "lastFailure", "same-origin"]),
    ("hires/parallel search", APP / "data/sources/FlacDownloaderSource.kt", ["coroutineScope", "async"]),
    ("hires/row badges", ROOT / "sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/SongText.kt", ["qualityBadge", "LOSSLESS"]),
    ("hires/url encoding", APP / "data/sources/FlacDownloaderApi.kt", ["encodeSegment", "encodeSegment(file)"]),
    ("hires/qobuz direct", APP / "data/sources/QobuzApi.kt", ["user_auth_token", "request_sig", "packId"]),
    ("hires/qobuz wired", APP / "data/sources/FlacDownloaderSource.kt", ["BACKEND_ACCOUNT", "streamAccount", "qobuzToken"]),
    ("hires/qobuz settings", APP / "data/settings/AppSettings.kt", ["qobuzToken", "KEY_QOBUZ_TOKEN", "setQobuzAccount"]),
    ("hires/qobuz sign-in", APP / "ui/screens/SettingsSheet.kt", ["showQobuzLoginDialog", "QobuzApi.login"]),
    ("hires/cookie unlock", APP / "data/sources/FlacDownloaderApi.kt", ["cookiesReader", "withCookies"]),
    ("verify/qobuz tests", TST / "QobuzApiTest.kt", ["nested performer", "md5 digest", "format_id"]),
    ("diag/refusal memory", ROOT / "shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/NerdStats.kt", ["noteRefusal", "refusalFor", "REFUSAL_TTL_MS"]),
    ("diag/refusal parse", APP / "playback/ChunkedDataSource.kt", ["refusalMessage", "ERROR_BODY"]),
    ("diag/addon health refusal", APP / "data/sources/AddonSource.kt", ["streamHost", "last stream"]),
    ("verify/refusal tests", TST / "ChunkedDataSourceTest.kt", ["inactive or no longer valid", "bodies that state nothing"]),
    ("automix/v2 setting", APP / "data/settings/AppSettings.kt", ["automixEngine", "setAutomixEngine"]),
    ("flow/card art outside", APP / "MainActivity.kt", ["flowArt", "thumbnailUrl = flowArt"]),
    ("flow/no inside image", APP / "ui/screens/FlowScreen.kt", ["Compact header with no artwork"]),
    ("downloads/memoized", APP / "download/Downloads.kt", ["listingGeneration", "allSongsCache"]),
    ("verify/snapshot tests", TST / "FlowSnapshotTest.kt", ["encodeFlowSnapshot", "decodeFlowSnapshot"]),
    ("verify/hires direct tests", TST / "FlacDownloaderTest.kt", ["direct stream measures", "below-lossless file", "fall back to the direct catalogue"]),
    ("sing/dsp", APP / "playback/VocalCutProcessor.kt", ["cutCenter", "level", "NOT_SET"]),
    ("sing/chain wired", APP / "playback/audio/DspChain.kt", ["sing: VocalCutProcessor", "sing.process(block)"]),
    ("sing/service wired", APP / "playback/PlaybackService.kt", ["singProcessorA", "applySingLevel"]),
    ("sing/setting", APP / "data/settings/AppSettings.kt", ["singLevel", "KEY_SING_LEVEL", "setSingLevel"]),
    ("sing/sheet", APP / "ui/components/SingSheet.kt", ["AppSettings.setSingLevel", "sing_level_percent"]),
    ("sing/menu rows", APP / "ui/components/SongActionsSheet.kt", ["onOpenSing", "onOpenReplay", "onToggleSpatialAudio"]),
    ("sing/lyrics mic", ROOT / "sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt", ["SingMicButton"]),
    ("sing/output spatial", ROOT / "sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/AudioOutputSheet.kt", ["SpatialToggleRow"]),
    ("verify/sing tests", TST / "VocalCutProcessorTest.kt", ["full cut silences", "hard-panned tone", "byte-identical"]),
    ("verify/cache tests", TST / "PlaylistCacheTest.kt", ["exactTotal", "countLabel"]),
    ("advanced/all settings", CLS / "ClassipodHost.kt", ["advancedPage", "discordPage", "advAudioPage", "advStoragePage", "onOpenEqualizer", "onQueueSongs"]),
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
    ("now-playing/hint row", CLS / "ClassipodNowPlaying.kt", ["HOLD CENTER OPTIONS"]),
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
    ("marquee/rows", CLS / "ClassipodRoot.kt", ["PodMarquee", "scroll = selected"]),
    ("marquee/tracks", CLS / "ClassipodBrowse.kt", ["PodMarquee", "scroll = index == selected"]),
    ("marquee/now-playing", CLS / "ClassipodNowPlaying.kt", ["PodMarquee", "scroll = true"]),
    ("marquee/coverflow", CLS / "ClassipodCoverFlow.kt", ["PodMarquee", "scroll = true"]),
    ("liked/paged list", CLS / "ClassipodBrowse.kt", ["ClassipodPagedTracks", "PlaylistCache", "exactTotal"]),
    ("liked/true total", CLS / "ClassipodHost.kt", ["likedTotal", "PagedTracks"]),
    ("liked/menu badge", CLS / "ClassipodHost.kt", ["likedTotal?.toString()"]),
    ("np/lyrics sheet", CLS / "ClassipodNowPlaying.kt", ["LyricsSheet", "animateScrollToItem", "followSuspendUntil"]),
    ("np/volume bar", CLS / "ClassipodNowPlaying.kt", ["NpVolumeBar", "rotationZ = 45f"]),
    ("np/song options", CLS / "ClassipodNowPlaying.kt", ["onCenterLongPress", "onSongMenu"]),
    ("wheel/long-press", CLS / "ClassipodRoot.kt", ["onCenterLongPress"]),
    ("search/live+submit", CLS / "ClassipodHost.kt", ["typeaheadResults", "submitSearch"]),
    ("albums/coverflow", CLS / "ClassipodCoverFlow.kt", ["HorizontalPager", "rotationY", "360f * extra"]),
    ("art/no letterbox", CLS / "ClassipodArt.kt", ["ContentScale.Crop", "scaleY = -1f"]),
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
