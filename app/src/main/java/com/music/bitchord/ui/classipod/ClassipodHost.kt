package com.music.bitchord.ui.classipod

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.discord.discordAudioQualityLine
import com.music.bitchord.data.flow.FlowMood
import com.music.bitchord.data.flow.FlowStore
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.isSameTrackAs
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AppUi
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.QueueSource
import com.music.bitchord.playback.rememberPlayerState
import com.music.bitchord.ui.MainViewModel
import kotlinx.coroutines.launch

/**
 * The iPod, hosted: menu stack, wheel transport, and every page wired to
 * the same [MainViewModel], player and stores the BitChord theme uses.
 * Nothing here keeps its own copy of library, queue or Flow state — it
 * reads and writes the shared ones, so both shells always agree.
 */
@Composable
fun ClassipodHost(
    darkTheme: Boolean,
    viewModel: MainViewModel,
    player: com.music.bitchord.playback.PlayerState,
    controller: MediaController?,
    onPlaySongs: (List<Song>, Int, QueueSource) -> Unit,
    onStartFlow: () -> Unit,
    openSongMenu: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lcd = ClassipodTheme.lcd(darkTheme)
    val wheel = remember { ClassipodWheelState() }
    var stack by remember { mutableStateOf<List<ClassipodPage>>(listOf(ClassipodPage.Menu("Music", emptyList()))) }

    fun push(page: ClassipodPage) {
        stack = stack + page
    }
    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    // Wheel transport is global: every screen inherits it.
    wheel.onMenu = { pop() }
    wheel.onPlayPause = {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }
    wheel.onPrev = { controller?.seekToPreviousMediaItem() }
    wheel.onNext = { controller?.seekToNextMediaItem() }

    val home by viewModel.home.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val likeStatuses by viewModel.likeStatuses.collectAsStateWithLifecycle()
    val flowStatus by viewModel.flowStatus.collectAsStateWithLifecycle()
    val flowQueue by viewModel.flowQueue.collectAsStateWithLifecycle()
    val flowLoading by viewModel.flowLoading.collectAsStateWithLifecycle()
    val flowMood by FlowStore.mood.collectAsStateWithLifecycle()
    val flowTuner by FlowStore.tuner.collectAsStateWithLifecycle()
    val shuffleOn by AppSettings.shuffleEnabled.collectAsStateWithLifecycle()
    val repeatMode by AppSettings.repeatMode.collectAsStateWithLifecycle()
    val wifiQuality by AppSettings.audioQualityWifi.collectAsStateWithLifecycle()
    val cellularQuality by AppSettings.audioQualityCellular.collectAsStateWithLifecycle()
    val sleepMinutes by com.music.bitchord.playback.SleepTimer.minutes.collectAsStateWithLifecycle()
    val sourceConfigs by SourceRegistry.configs.collectAsStateWithLifecycle()
    val colorway by AppSettings.classipodColorway.collectAsStateWithLifecycle()
    val clicksOn by AppSettings.classipodClicks.collectAsStateWithLifecycle()
    val wheelSteps by AppSettings.classipodWheelSteps.collectAsStateWithLifecycle()
    val nerdStats by NerdStats.current.collectAsStateWithLifecycle()

    val likedSongs = (library as? UiState.Success)?.data?.likedSongs.orEmpty()
    val librarySongs = (library as? UiState.Success)?.data?.librarySongs.orEmpty()
    val allKnown = (likedSongs + librarySongs).distinctBy { it.videoId }
    val historySongs = (history as? UiState.Success)?.data.orEmpty()

    fun openTracks(title: String, songs: List<Song>, source: QueueSource) {
        push(ClassipodPage.Tracks(title, songs, source))
    }

    fun playFromList(songs: List<Song>, index: Int, label: String, type: com.music.bitchord.data.model.PlaybackSourceType) {
        onPlaySongs(songs, index, QueueSource(label, type))
    }




























    fun repeatLabel(mode: Int): String = when (mode) {
        Player.REPEAT_MODE_ALL -> "All"
        Player.REPEAT_MODE_ONE -> "One"
        else -> "Off"
    }

    // Cycling a rung re-pushes a fresh page (values are snapshots).
    fun qualityPage(): ClassipodPage.Menu {
        fun cycle(current: AudioQuality, setter: (AudioQuality) -> Unit): () -> Unit = {
            val order = listOf(AudioQuality.LOSSLESS, AudioQuality.HIGH, AudioQuality.MEDIUM, AudioQuality.LOW)
            setter(order[(order.indexOf(current) + 1) % order.size])
        }
        return ClassipodPage.Menu(
            "Quality",
            listOf(
                MenuItem("Wi-Fi ceiling", wifiQuality.label) {
                    cycle(wifiQuality, AppSettings::setAudioQualityWifi)()
                    push(qualityPage())
                    pop()
                },
                MenuItem("Data ceiling", cellularQuality.label) {
                    cycle(cellularQuality, AppSettings::setAudioQualityCellular)()
                    push(qualityPage())
                    pop()
                },
            ),
        )
    }

    fun sourcesPage(): ClassipodPage.Menu = ClassipodPage.Menu(
        "Sources",
        sourceConfigs.map { config ->
            MenuItem(
                config.displayName,
                if (config.enabled) "On" else "Off",
            ) {
                SourceRegistry.setEnabled(config.id, !config.enabled)
                push(sourcesPage())
                pop()
            }
        },
    )

    fun interfacePage(): ClassipodPage.Menu = ClassipodPage.Menu(
        "Interface",
        listOf(
            MenuItem("Theme: BitChord") { AppSettings.setAppUi(AppUi.BITCHORD) },
            MenuItem(
                "Faceplate",
                AppSettings.classipodColorway.value,
            ) {
                val names = ClassipodTheme.COLORWAYS.map { it.name }
                val next = names[(names.indexOf(AppSettings.classipodColorway.value) + 1).mod(names.size)]
                AppSettings.setClassipodColorway(next)
                push(interfacePage())
                pop()
            },
            MenuItem(
                "Click sounds",
                if (clicksOn) "On" else "Off",
            ) {
                AppSettings.setClassipodClicks(!clicksOn)
                push(interfacePage())
                pop()
            },
            MenuItem("Wheel speed", "$wheelSteps/step") {
                val next = when (wheelSteps) {
                    in Int.MIN_VALUE..28 -> 36
                    in 29..42 -> 48
                    else -> 24
                }
                AppSettings.setClassipodWheelSteps(next)
                push(interfacePage())
                pop()
            },
        ),
    )

    fun aboutPage(): ClassipodPage.Menu = ClassipodPage.Menu(
        "About",
        listOf(
            MenuItem("BitChord Next"),
            MenuItem("Tracks", allKnown.size.toString()),
            MenuItem("Liked", likedSongs.size.toString()),
        ),
    )

    fun podSettingsRows(): List<PodSettingRow> = listOf(
        PodSettingRow.Toggle("Shuffle", shuffleOn, AppSettings::setShuffleEnabled),
        PodSettingRow.Action("Repeat", repeatLabel(repeatMode)) {
            val next = when (repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            AppSettings.setRepeatMode(next)
            controller?.repeatMode = next
        },
        PodSettingRow.Action(
            "Sleep Timer",
            sleepMinutes?.let { "$it min" } ?: "Off",
        ) { push(ClassipodPage.SleepTimer) },
        PodSettingRow.Action(
            "Quality",
            "Wi-Fi ${wifiQuality.label} · Data ${cellularQuality.label}",
        ) { push(qualityPage()) },
        PodSettingRow.Action("Sources") { push(sourcesPage()) },
        PodSettingRow.Action("Interface", AppUi.CLASSIPOD.label) { push(interfacePage()) },
        PodSettingRow.Action("About") { push(aboutPage()) },
    )

    fun sleepRows(): List<PodSettingRow> {
        val options: List<Int?> = listOf(null, 15, 30, 45, 60)
        return options.map { mins ->
            val label = mins?.let { "$it min" } ?: "Off"
            PodSettingRow.Action(
                (if (sleepMinutes == mins) "✓ " else "") + label,
            ) {
                if (mins == null) com.music.bitchord.playback.SleepTimer.cancel()
                else com.music.bitchord.playback.SleepTimer.start(mins)
                pop()
            }
        } + PodSettingRow.Action("End of song") {
            com.music.bitchord.playback.SleepTimer.startAfterTrack()
            pop()
        }
    }

    fun pushShelf(shelf: HomeShelf) {
        // Shelf cards are tracks or collections; tracks play, collections open.
        push(
            ClassipodPage.Menu(
                shelf.title,
                shelf.items.map { item ->
                    MenuItem(item.title, item.subtitle) {
                        val song = item.videoId?.let { vid ->
                            Song(vid, item.title, item.subtitle, item.thumbnailUrl)
                        }
                        if (song != null) {
                            playFromList(
                                listOf(song), 0, shelf.title,
                                com.music.bitchord.data.model.PlaybackSourceType.HOME,
                            )
                        } else {
                            val browseId = item.browseId
                            if (browseId == null) return@MenuItem
                            viewModel.openDetail(
                                browseId = browseId,
                                title = item.title,
                                subtitle = item.subtitle,
                                thumbnailUrl = item.thumbnailUrl,
                            )
                            pendingDetail = browseId to { page: com.music.bitchord.data.model.DetailPage ->
                                val songs = (page.songs as? UiState.Success)?.data.orEmpty()
                                if (songs.isNotEmpty()) {
                                    openTracks(
                                        page.title, songs,
                                        QueueSource(page.title, com.music.bitchord.data.model.PlaybackSourceType.BROWSE),
                                    )
                                }
                            }
                        }
                    }
                },
            ),
        )
    }

    fun pushArtists() {
        val names = allKnown.map { it.artist }.filter { it.isNotBlank() }.distinct().sorted()
        push(
            ClassipodPage.Menu(
                "Artists",
                names.map { name ->
                    MenuItem(name) {
                        openTracks(
                            name,
                            allKnown.filter { it.artist == name },
                            QueueSource(name, com.music.bitchord.data.model.PlaybackSourceType.BROWSE),
                        )
                    }
                },
            ),
        )
    }

    fun pushAlbums() {
        val names = allKnown.mapNotNull { it.albumName?.takeIf(String::isNotBlank) }.distinct().sorted()
        push(
            ClassipodPage.Menu(
                "Albums",
                names.map { name ->
                    MenuItem(name) {
                        openTracks(
                            name,
                            allKnown.filter { it.albumName == name },
                            QueueSource(name, com.music.bitchord.data.model.PlaybackSourceType.BROWSE),
                        )
                    }
                },
            ),
        )
    }

    fun pushPlaylists() {
        push(
            ClassipodPage.Menu(
                "Playlists",
                playlists.map { pl ->
                    MenuItem(pl.title, pl.subtitle) {
                        viewModel.openDetail(
                            browseId = pl.browseId,
                            title = pl.title,
                            subtitle = pl.subtitle,
                            thumbnailUrl = pl.thumbnailUrl,
                        )
                        pendingDetail = pl.browseId to { page ->
                            val songs = (page.songs as? UiState.Success)?.data.orEmpty()
                            if (songs.isNotEmpty()) {
                                openTracks(
                                    page.title, songs,
                                    QueueSource(page.title, com.music.bitchord.data.model.PlaybackSourceType.BROWSE),
                                )
                            }
                        }
                    }
                },
            ),
        )
    }

    fun pushDownloads() {
        scope.launch {
            val songs = Downloads.getDownloadedSongs(context)
            openTracks(
                "Downloads", songs,
                QueueSource("Downloads", com.music.bitchord.data.model.PlaybackSourceType.QUEUE),
            )
        }
    }


    // Root menu, rebuilt as feeds land.
    val root = remember(home, likedSongs, librarySongs, playlists, flowStatus, player.song) {
        ClassipodPage.Menu(
            title = "Music",
            items = listOf(
                MenuItem("Listen Now") {
                    val shelves = (home as? UiState.Success)?.data.orEmpty()
                    push(
                        ClassipodPage.Menu(
                            "Listen Now",
                            shelves.map { shelf ->
                                MenuItem(shelf.title) { pushShelf(shelf) }
                            },
                        ),
                    )
                },
                MenuItem("Flow", value = if (flowStatus.unlocked) null else "🔒") {
                    push(ClassipodPage.FlowHome)
                },
                MenuItem("Search") { push(ClassipodPage.Search) },
                MenuItem("Artists") { pushArtists() },
                MenuItem("Albums") { pushAlbums() },
                MenuItem("Songs") {
                    openTracks(
                        "Songs", allKnown,
                        QueueSource("Library", com.music.bitchord.data.model.PlaybackSourceType.BROWSE),
                    )
                },
                MenuItem("Liked Songs", value = likedSongs.size.takeIf { it > 0 }?.toString()) {
                    openTracks(
                        "Liked Songs", likedSongs,
                        QueueSource("Liked Songs", com.music.bitchord.data.model.PlaybackSourceType.BROWSE),
                    )
                },
                MenuItem("Playlists") { pushPlaylists() },
                MenuItem("Downloads") { pushDownloads() },
                MenuItem("Up Next", value = player.queue.size.takeIf { it > 0 }?.toString()) {
                    push(ClassipodPage.UpNext)
                },
                MenuItem("Now Playing", value = if (player.isPlaying) "▶" else null) {
                    push(ClassipodPage.NowPlaying)
                },
                MenuItem("Settings") { push(ClassipodPage.PodSettings) },
            ),
        )
    }

    // Keep the root fresh without dropping the user's place: swap index 0.
    LaunchedEffect(root) {
        stack = listOf(root) + stack.drop(1)
    }






    // A detail page opened for its track list: when its songs land, push them.
    val detailStack by viewModel.detailStack.collectAsStateWithLifecycle()
    LaunchedEffect(detailStack) {
        val (id, consume) = pendingDetail ?: return@LaunchedEffect
        val page = detailStack.lastOrNull { it.browseId == id } ?: return@LaunchedEffect
        if ((page.songs as? UiState.Success)?.data?.isNotEmpty() == true) {
            pendingDetail = null
            consume(page)
        }
    }

    var volume by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0.8f) }

    ClassipodRoot(
        darkTheme = darkTheme,
        stack = stack,
        onPush = ::push,
        onPop = ::pop,
        onPopToRoot = { stack = listOf(stack.first()) },
        wheel = wheel,
        modifier = modifier,
        content = {
            ClassipodPageContent(
                page = stack.last(),
                lcd = lcd,
                wheel = wheel,
                player = player,
                controller = controller,
                viewModel = viewModel,
                likeStatuses = likeStatuses,
                nerdStats = nerdStats,
                flowMood = flowMood,
                flowTuner = flowTuner,
                flowStatus = flowStatus,
                flowQueue = flowQueue,
                volume = volume,
                onVolume = {
                    volume = it
                    runCatching { controller?.volume = it }
                },
                onStartFlow = onStartFlow,
                openSongMenu = openSongMenu,
                openTracks = ::openTracks,
                playFromList = ::playFromList,
                podSettingsRows = ::podSettingsRows,
                sleepRows = ::sleepRows,
                onPop = ::pop,
            )
        },
    )
}

@Composable
private fun ClassipodPageContent(
    page: ClassipodPage,
    lcd: ClassipodTheme.Lcd,
    wheel: ClassipodWheelState,
    player: com.music.bitchord.playback.PlayerState,
    controller: MediaController?,
    viewModel: MainViewModel,
    likeStatuses: Map<String, LikeStatus>,
    nerdStats: NerdStats.Snapshot?,
    flowMood: FlowMood,
    flowTuner: com.music.bitchord.data.flow.FlowTuner,
    flowStatus: com.music.bitchord.data.flow.FlowStatus,
    flowQueue: UiState<List<Song>>,
    volume: Float,
    onVolume: (Float) -> Unit,
    onStartFlow: () -> Unit,
    openSongMenu: (Song) -> Unit,
    openTracks: (String, List<Song>, QueueSource) -> Unit,
    playFromList: (List<Song>, Int, String, com.music.bitchord.data.model.PlaybackSourceType) -> Unit,
    podSettingsRows: () -> List<PodSettingRow>,
    sleepRows: () -> List<PodSettingRow>,
    onPop: () -> Unit,
) {
    when (page) {
            is ClassipodPage.Menu -> ClassipodMenuPage(
                page = page, lcd = lcd, wheel = wheel, onBack = onPop,
            )
            is ClassipodPage.Tracks -> ClassipodTrackList(
                title = page.title,
                songs = page.songs,
                lcd = lcd,
                wheel = wheel,
                onPlay = { songs, index -> playFromList(songs, index, page.source.title, page.source.type) },
                onLongPress = openSongMenu,
                onBack = onPop,
                currentSong = player.song,
                isPlaying = player.isPlaying,
            )
            ClassipodPage.NowPlaying -> {
                val song = player.song
                ClassipodNowPlaying(
                    song = song,
                    isPlaying = player.isPlaying,
                    positionMs = player.position.positionMs,
                    durationMs = player.durationMs,
                    liked = song?.let { likeStatuses[it.videoId] == LikeStatus.LIKE } == true,
                    qualityLine = discordAudioQualityLine(nerdStats),
                    lcd = lcd,
                    wheel = wheel,
                    volume = volume,
                    onVolume = onVolume,
                    onSeek = { controller?.seekTo(it) },
                    onToggleLike = { song?.let { viewModel.toggleLike(it.videoId) } },
                    onBack = onPop,
                )
                // Rotary seeks here too? No — volume owns the wheel (classic).
            }
            ClassipodPage.FlowHome -> ClassipodFlowHome(
                mood = flowMood,
                tuner = flowTuner,
                status = flowStatus,
                trackCount = (flowQueue as? UiState.Success)?.data?.size ?: 0,
                lcd = lcd,
                wheel = wheel,
                onStartFlow = onStartFlow,
                onApply = { m, d, mem, ex -> viewModel.applyFlowConfig(m, d, mem, ex) },
                onNewMix = { viewModel.newFlowMix() },
                onSave = {
                    val name = if (flowMood == FlowMood.FLOW) "My Flow" else "Flow · ${flowMood.label}"
                    viewModel.saveFlow(name)
                },
                onBack = onPop,
            )
            ClassipodPage.FlowTuner -> {
                // Tuner lives inside FlowHome on iPod; this page is a shortcut to it.
                LaunchedEffect(Unit) { onPop() }
            }
            ClassipodPage.Search -> {
                val query by viewModel.query.collectAsStateWithLifecycle()
                val results by viewModel.results.collectAsStateWithLifecycle()
                ClassipodSearch(
                    query = query,
                    onQuery = viewModel::onQueryChange,
                    results = results,
                    lcd = lcd,
                    wheel = wheel,
                    onSong = { songs, index ->
                        playFromList(
                            songs, index, "Search",
                            com.music.bitchord.data.model.PlaybackSourceType.SEARCH,
                        )
                    },
                    onBrowse = { item ->
                        viewModel.openDetail(
                            browseId = item.browseId,
                            title = item.title,
                            subtitle = item.subtitle,
                            thumbnailUrl = item.thumbnailUrl,
                        )
                        pendingDetail = item.browseId to { pg ->
                            val songs = (pg.songs as? UiState.Success)?.data.orEmpty()
                            if (songs.isNotEmpty()) {
                                openTracks(
                                    pg.title, songs,
                                    QueueSource(pg.title, com.music.bitchord.data.model.PlaybackSourceType.BROWSE),
                                )
                            }
                        }
                    },
                    onBack = onPop,
                )
            }
            ClassipodPage.UpNext -> {
                val queue = player.queue
                val at = player.queueIndex
                ClassipodTrackList(
                    title = "Up Next",
                    songs = queue.drop(at + 1),
                    lcd = lcd,
                    wheel = wheel,
                    onPlay = { songs, index ->
                        controller?.seekTo(at + 1 + index, 0)
                    },
                    onLongPress = openSongMenu,
                    onBack = onPop,
                    currentSong = player.song,
                    isPlaying = player.isPlaying,
                )
            }
            ClassipodPage.PodSettings -> ClassipodSettingsList(
                rows = podSettingsRows(),
                lcd = lcd,
                wheel = wheel,
                onBack = onPop,
            )
            ClassipodPage.SleepTimer -> ClassipodSettingsList(
                rows = sleepRows(),
                lcd = lcd,
                wheel = wheel,
                onBack = onPop,
            )
        }
    }

/** Pending detail opener: set before openDetail, consumed when songs land. */
private var pendingDetail: Pair<String, (com.music.bitchord.data.model.DetailPage) -> Unit>? = null
