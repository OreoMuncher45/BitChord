package com.music.bitchord.ui.classipod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.playback.QueueSource
import com.music.bitchord.playback.rememberPlayerState
import com.music.bitchord.ui.components.QueueActionNotice
import com.music.bitchord.ui.components.QueueActionNoticeHost
import com.music.bitchord.ui.MainViewModel
import kotlinx.coroutines.delay
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
    onQueueSongs: (List<Song>) -> Unit = {},
    onPlayNext: (List<Song>) -> Unit = {},
    onDownloadSong: (Song) -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onOpenDiscord: () -> Unit = {},
    onOpenDiscordLogin: () -> Unit = {},
    onOpenEqualizer: () -> Unit = {},
    onOpenLyricsSources: () -> Unit = {},
    onOpenTranslationLanguage: () -> Unit = {},
    onOpenAppLanguage: () -> Unit = {},
    onOpenReplay: () -> Unit = {},
    onOpenListenTogether: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    queueNotice: QueueActionNotice? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lcd = ClassipodTheme.lcd(darkTheme)
    val wheel = remember { ClassipodWheelState() }
    var stack by remember { mutableStateOf<List<ClassipodPage>>(listOf(ClassipodPage.Menu("Music", emptyList()))) }

    // Transient pill ("Queued 34 songs", "Couldn't load X"): queue-all and
    // play-all answer here instead of dying silent like opens used to.
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeGen by remember { mutableIntStateOf(0) }
    fun notify(msg: String) {
        notice = msg
        noticeGen++
    }
    LaunchedEffect(noticeGen) {
        if (noticeGen == 0) return@LaunchedEffect
        delay(2200)
        notice = null
    }

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
    val account by viewModel.account.collectAsStateWithLifecycle()
    val discordRpc by AppSettings.discordRpcEnabled.collectAsStateWithLifecycle()
    val discordQuality by AppSettings.discordShowAudioQuality.collectAsStateWithLifecycle()
    val discordDetails by AppSettings.discordUseDetails.collectAsStateWithLifecycle()
    val atmosOn by AppSettings.dolbyAtmos.collectAsStateWithLifecycle()
    val dlQuality by AppSettings.downloadQuality.collectAsStateWithLifecycle()
    val wifiOnlyDl by AppSettings.wifiOnlyDownloads.collectAsStateWithLifecycle()
    val musicOnly by AppSettings.preferMusicOnly.collectAsStateWithLifecycle()
    val autoplayOn by AppSettings.autoplay.collectAsStateWithLifecycle()
    val loudness by AppSettings.loudnessNormalization.collectAsStateWithLifecycle()
    val skipSil by AppSettings.skipSilence.collectAsStateWithLifecycle()
    val usbDac by AppSettings.preferUsbDac.collectAsStateWithLifecycle()
    val spatial by AppSettings.spatialAudio.collectAsStateWithLifecycle()
    val speed by AppSettings.playbackSpeed.collectAsStateWithLifecycle()
    val crossfade by AppSettings.crossfadeSeconds.collectAsStateWithLifecycle()
    val themeModeVal by AppSettings.themeMode.collectAsStateWithLifecycle()
    val reduceAnim by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val nerd by AppSettings.showNerdStats.collectAsStateWithLifecycle()
    val syncedLyr by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val syllable by AppSettings.prioritizeSyllableSync.collectAsStateWithLifecycle()
    val cacheLimit by AppSettings.audioCacheLimitBytes.collectAsStateWithLifecycle()
    val swipeNext by AppSettings.swipeToPlayNext.collectAsStateWithLifecycle()
    val noRepeat by AppSettings.dontRepeatSuggestions.collectAsStateWithLifecycle()
    val stopClose by AppSettings.stopOnTaskRemoved.collectAsStateWithLifecycle()
    val genreStats by AppSettings.replayGenres.collectAsStateWithLifecycle()
    val eqOn by AppSettings.equalizerEnabled.collectAsStateWithLifecycle()

    val likedSongs = (library as? UiState.Success)?.data?.likedSongs.orEmpty()
    // The library tab only carries Liked Music's first page (~100 rows).
    // Liked libraries run past that, so the full list is paged once per
    // session — otherwise everything past row 100 silently vanishes.
    var fullLiked by remember { mutableStateOf<List<Song>?>(null) }
    LaunchedEffect(library) {
        if (likedSongs.isNotEmpty() && fullLiked == null) {
            fullLiked = com.music.bitchord.data.YtMusicRepository.allSongs(
                com.music.bitchord.data.YtMusicRepository.LIKED_MUSIC,
            ).getOrNull()?.takeIf { it.isNotEmpty() }
        }
    }
    val likedAll = fullLiked ?: likedSongs
    var likedTotal by remember { mutableStateOf<Int?>(null) }
    val librarySongs = (library as? UiState.Success)?.data?.librarySongs.orEmpty()
    val allKnown = (likedAll + librarySongs).distinctBy { it.videoId }
    val historySongs = (history as? UiState.Success)?.data.orEmpty()

    fun openTracks(title: String, songs: List<Song>, source: QueueSource) {
        push(ClassipodPage.Tracks(title, songs, source))
    }

    /** A playlist/album/liked list that pages itself open — see PagedTracks. */
    fun openPaged(title: String, browseId: String, reportTotal: Boolean = false) {
        push(ClassipodPage.PagedTracks(title, browseId, reportTotal))
    }

    fun playFromList(songs: List<Song>, index: Int, label: String, type: com.music.bitchord.data.model.PlaybackSourceType) {
        onPlaySongs(songs, index, QueueSource(label, type))
    }

    // Classipod plays a picked track and opens Now Playing, like the real thing.
    fun playAndShow(songs: List<Song>, index: Int, label: String, type: com.music.bitchord.data.model.PlaybackSourceType) {
        playFromList(songs, index, label, type)
        push(ClassipodPage.NowPlaying)
    }

    /**
     * The three-dot menu for a playlist or album, wherever it is met:
     * Playlists menu, shelf cards, search hits. Open pages instantly,
     * Play all replaces the queue and starts it, Queue all appends.
     */
    fun collectionOptionsPage(title: String, browseId: String): ClassipodPage.Menu =
        ClassipodPage.Menu(
            title,
            listOf(
                MenuItem("Open") { openPaged(title, browseId, false) },
                MenuItem("Play all") {
                    scope.launch {
                        notify("Loading " + title + "\u2026")
                        val all = com.music.bitchord.data.YtMusicRepository.allSongs(browseId)
                            .getOrNull().orEmpty()
                        if (all.isNotEmpty()) {
                            playFromList(
                                all, 0, title,
                                com.music.bitchord.data.model.PlaybackSourceType.BROWSE,
                            )
                            push(ClassipodPage.NowPlaying)
                        } else {
                            notify("Couldn't load " + title)
                        }
                    }
                },
                MenuItem("Queue all") {
                    scope.launch {
                        val all = com.music.bitchord.data.YtMusicRepository.allSongs(browseId)
                            .getOrNull().orEmpty()
                        if (all.isNotEmpty()) {
                            // Success answers through the app's own action
                            // notice (mirrored over the LCD); only failures
                            // need the iPod pill.
                            onQueueSongs(all)
                        } else {
                            notify("Couldn't load " + title)
                        }
                    }
                },
            ),
        )

    /** Native playlist picker: existing playlists, honest empty states. */
    fun podPlaylistPicker(song: Song): ClassipodPage.Menu {
        if (account == null) {
            return ClassipodPage.Menu(
                song.title,
                listOf(MenuItem("Sign in first", "Account in Advanced")),
            )
        }
        if (playlists.isEmpty()) {
            return ClassipodPage.Menu(
                song.title,
                listOf(MenuItem("No playlists yet", "Create one in BitChord")),
            )
        }
        return ClassipodPage.Menu(
            song.title,
            playlists.map { pl ->
                MenuItem(pl.title, pl.subtitle) {
                    viewModel.addToPlaylists(listOf(pl), song) { added, there, _ ->
                        notify(
                            when {
                                added > 0 -> "Added to " + pl.title
                                there > 0 -> "Already in " + pl.title
                                else -> "Couldn't add to " + pl.title
                            },
                        )
                    }
                    pop()
                }
            },
        )
    }

    /**
     * The universal song menu: one native iPod page for every song row in
     * every list, reached by center, hold-center and the › affordance
     * alike. It deliberately never touches the app's bottom sheets — a
     * menu that is ordinary LCD content cannot fail to appear, swallow
     * taps, or strand input the way a sheet over the iPod did.
     */
    fun podSongMenu(song: Song): ClassipodPage.Menu {
        val liked = likeStatuses[song.videoId] == LikeStatus.LIKE
        val albumSongs = song.albumName?.takeIf { it.isNotBlank() }
            ?.let { name -> allKnown.filter { it.albumName == name } }.orEmpty()
        val artistSongs = song.artist.takeIf { it.isNotBlank() }
            ?.let { name -> allKnown.filter { it.artist == name } }.orEmpty()
        return ClassipodPage.Menu(
            song.title,
            listOfNotNull(
                MenuItem("Play") {
                    playAndShow(listOf(song), 0, song.title, PlaybackSourceType.QUEUE)
                },
                MenuItem("Play next") {
                    onPlayNext(listOf(song))
                    pop()
                },
                MenuItem("Add to queue") {
                    onQueueSongs(listOf(song))
                    pop()
                },
                MenuItem(if (liked) "Unlove" else "Love") {
                    viewModel.toggleLike(song.videoId)
                    pop()
                    push(podSongMenu(song))
                },
                MenuItem("Download") {
                    onDownloadSong(song)
                    pop()
                },
                MenuItem("Add to playlist…") { push(podPlaylistPicker(song)) },
                MenuItem("Go to album", song.albumName)
                    .takeIf { albumSongs.isNotEmpty() }
                    ?.let {
                        MenuItem("Go to album", song.albumName) {
                            openTracks(
                                song.albumName.orEmpty(), albumSongs,
                                QueueSource(song.albumName.orEmpty(), PlaybackSourceType.BROWSE),
                            )
                        }
                    },
                MenuItem("Go to artist", song.artist)
                    .takeIf { artistSongs.isNotEmpty() }
                    ?.let {
                        MenuItem("Go to artist", song.artist) {
                            openTracks(
                                song.artist, artistSongs,
                                QueueSource(song.artist, PlaybackSourceType.BROWSE),
                            )
                        }
                    },
            ),
        )
    }

    /** Every song row's center, hold-center and › land here. */
    fun openPodSongMenu(song: Song) {
        push(podSongMenu(song))
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
            MenuItem("BitChord Next", "v" + BuildConfig.VERSION_NAME),
            MenuItem("Tracks", allKnown.size.toString()),
            MenuItem("Liked", likedAll.size.toString()),
        ),
    )

    // ── Advanced Settings ─────────────────────────────────────────
    // Every functional setting from the main Settings sheet, driven by the
    // same AppSettings flows and the same app overlays: toggles flip the
    // pref, rows that need a form raise the app's own sheet over the iPod.

    /** Toggle row that rebuilds its page so the value reads live. */
    fun tog(
        title: String,
        on: Boolean,
        set: (Boolean) -> Unit,
        refresh: () -> ClassipodPage.Menu,
    ): MenuItem = MenuItem(title, if (on) "On" else "Off") {
        set(!on)
        push(refresh())
        pop()
    }

    /** Cycle row for enums and rung lists. */
    fun <T> cyc(
        title: String,
        value: String,
        current: T,
        order: List<T>,
        set: (T) -> Unit,
        refresh: () -> ClassipodPage.Menu,
    ): MenuItem = MenuItem(title, value) {
        set(order[(order.indexOf(current) + 1) % order.size])
        push(refresh())
        pop()
    }

    fun cacheLabel(bytes: Long): String = when (bytes) {
        AppSettings.UNLIMITED_CACHE_LIMIT_BYTES -> "Unlimited"
        else -> {
            val mb = (bytes / (1024 * 1024)).toInt()
            if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"
        }
    }

    fun discordPage(): ClassipodPage.Menu = ClassipodPage.Menu(
        "Discord",
        listOf(
            tog("Rich presence", discordRpc, AppSettings::setDiscordRpcEnabled, ::discordPage),
            tog("Show quality", discordQuality, AppSettings::setDiscordShowAudioQuality, ::discordPage),
            tog("Show details", discordDetails, AppSettings::setDiscordUseDetails, ::discordPage),
            MenuItem("Open Discord…") { onOpenDiscord() },
            MenuItem("Discord login") { onOpenDiscordLogin() },
        ),
    )

    fun advAudioPage(): ClassipodPage.Menu = ClassipodPage.Menu(
        "Audio",
        listOf(
            cyc("Wi-Fi ceiling", wifiQuality.label, wifiQuality,
                listOf(AudioQuality.LOSSLESS, AudioQuality.HIGH, AudioQuality.MEDIUM, AudioQuality.LOW),
                AppSettings::setAudioQualityWifi, ::advAudioPage),
            cyc("Data ceiling", cellularQuality.label, cellularQuality,
                listOf(AudioQuality.LOSSLESS, AudioQuality.HIGH, AudioQuality.MEDIUM, AudioQuality.LOW),
                AppSettings::setAudioQualityCellular, ::advAudioPage),
            tog("Dolby Atmos", atmosOn, AppSettings::setDolbyAtmos, ::advAudioPage),
            cyc("Download quality", dlQuality.label, dlQuality,
                com.music.bitchord.data.settings.DownloadQuality.entries,
                AppSettings::setDownloadQuality, ::advAudioPage),
            tog("Wi-Fi-only downloads", wifiOnlyDl, AppSettings::setWifiOnlyDownloads, ::advAudioPage),
        ),
    )

    fun advPlaybackPage(): ClassipodPage.Menu = ClassipodPage.Menu(
        "Playback",
        listOf(
            tog("Music only", musicOnly, AppSettings::setPreferMusicOnly, ::advPlaybackPage),
            tog("Autoplay", autoplayOn, AppSettings::setAutoplay, ::advPlaybackPage),
            tog("Loudness", loudness, AppSettings::setLoudnessNormalization, ::advPlaybackPage),
            tog("Skip silence", skipSil, AppSettings::setSkipSilence, ::advPlaybackPage),
            tog("USB DAC", usbDac, AppSettings::setPreferUsbDac, ::advPlaybackPage),
            tog("Spatial audio", spatial, AppSettings::setSpatialAudio, ::advPlaybackPage),
            cyc("Speed", if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x", speed,
                listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f),
                AppSettings::setPlaybackSpeed, ::advPlaybackPage),
            cyc("Crossfade", if (crossfade == 0) "Off" else "${crossfade}s", crossfade,
                listOf(0, 2, 5, 10),
                AppSettings::setCrossfadeSeconds, ::advPlaybackPage),
        ),
    )

    fun advAppearancePage(): ClassipodPage.Menu = ClassipodPage.Menu(
        "Appearance",
        listOf(
            cyc("Theme", themeModeVal.label, themeModeVal,
                com.music.bitchord.data.settings.ThemeMode.entries,
                AppSettings::setThemeMode, ::advAppearancePage),
            tog("Reduce animation", reduceAnim, AppSettings::setReduceAnimation, ::advAppearancePage),
            tog("Nerd stats", nerd, AppSettings::setShowNerdStats, ::advAppearancePage),
        ),
    )

    fun advLyricsPage(): ClassipodPage.Menu = ClassipodPage.Menu(
        "Lyrics",
        listOf(
            tog("Synced lyrics", syncedLyr, AppSettings::setSyncedLyrics, ::advLyricsPage),
            tog("Syllable sync", syllable, AppSettings::setPrioritizeSyllableSync, ::advLyricsPage),
            MenuItem("Sources…") { onOpenLyricsSources() },
            MenuItem("Translation…") { onOpenTranslationLanguage() },
        ),
    )

    fun advStoragePage(): ClassipodPage.Menu = ClassipodPage.Menu(
        "Storage",
        listOf(
            cyc("Cache limit", cacheLabel(cacheLimit), cacheLimit,
                listOf(512L * 1024 * 1024, 1024L * 1024 * 1024, 2048L * 1024 * 1024,
                    4096L * 1024 * 1024, AppSettings.UNLIMITED_CACHE_LIMIT_BYTES),
                AppSettings::setAudioCacheLimitBytes, ::advStoragePage),
            MenuItem("Clear song cache") {
                com.music.bitchord.playback.AudioCache.clear { notify("Song cache cleared") }
            },
            MenuItem("Clear image cache") {
                coil3.SingletonImageLoader.get(context).memoryCache?.clear()
                coil3.SingletonImageLoader.get(context).diskCache?.clear()
                notify("Image cache cleared")
            },
        ),
    )

    fun advDataPage(): ClassipodPage.Menu = ClassipodPage.Menu(
        "Data",
        listOf(
            MenuItem("Replay…") { onOpenReplay() },
            tog("Genre stats", genreStats, AppSettings::setReplayGenres, ::advDataPage),
        ),
    )

    fun advMorePage(): ClassipodPage.Menu = ClassipodPage.Menu(
        "More",
        listOf(
            tog("Swipe to play next", swipeNext, AppSettings::setSwipeToPlayNext, ::advMorePage),
            tog("Don't repeat", noRepeat, AppSettings::setDontRepeatSuggestions, ::advMorePage),
            tog("Stop on close", stopClose, AppSettings::setStopOnTaskRemoved, ::advMorePage),
            MenuItem("Equalizer", if (eqOn) "On" else "Off") { onOpenEqualizer() },
            MenuItem("Language…") { onOpenAppLanguage() },
            MenuItem("Listen Together…") { onOpenListenTogether() },
            MenuItem("Downloads…") { onOpenDownloads() },
        ),
    )

    fun advancedPage(): ClassipodPage.Menu = ClassipodPage.Menu(
        "Advanced",
        listOf(
            MenuItem("Account", account?.email?.takeIf { it.isNotBlank() } ?: "Sign in") { onOpenAccount() },
            MenuItem("Discord", if (discordRpc) "On" else "Off") { push(discordPage()) },
            MenuItem("Audio") { push(advAudioPage()) },
            MenuItem("Playback") { push(advPlaybackPage()) },
            MenuItem("Appearance") { push(advAppearancePage()) },
            MenuItem("Lyrics") { push(advLyricsPage()) },
            MenuItem("Storage") { push(advStoragePage()) },
            MenuItem("Data") { push(advDataPage()) },
            MenuItem("More") { push(advMorePage()) },
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
        PodSettingRow.Action("Advanced") { push(advancedPage()) },
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
                    val song = item.videoId?.let { vid ->
                        Song(vid, item.title, item.subtitle, item.thumbnailUrl)
                    }
                    MenuItem(
                        title = item.title,
                        value = item.subtitle,
                        onSelect = {
                            if (song != null) {
                                playFromList(
                                    listOf(song), 0, shelf.title,
                                    com.music.bitchord.data.model.PlaybackSourceType.HOME,
                                )
                            } else {
                                val browseId = item.browseId ?: return@MenuItem
                                push(ClassipodPage.PagedTracks(item.title, browseId))
                            }
                        },
                        onCenter = song?.let { s -> { push(podSongMenu(s)) } }
                            ?: item.browseId?.let { id ->
                                { push(collectionOptionsPage(item.title, id)) }
                            },
                    )
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
        val albums = allKnown
            .groupBy { it.albumName?.takeIf(String::isNotBlank) ?: "Unknown Album" }
            .map { (name, songs) ->
                CoverAlbum(
                    name = name,
                    artist = songs.firstOrNull()?.artist.orEmpty(),
                    artUrl = songs.firstOrNull()?.thumbnailUrl,
                    songs = songs,
                )
            }
            .sortedBy { it.name.lowercase() }
        push(ClassipodPage.CoverFlow(albums))
    }

    fun pushPlaylists() {
        push(
            ClassipodPage.Menu(
                "Playlists",
                playlists.map { pl ->
                    MenuItem(
                        title = pl.title,
                        value = pl.subtitle,
                        onSelect = {
                            val browseId = pl.browseId ?: return@MenuItem
                            push(ClassipodPage.PagedTracks(pl.title, browseId))
                        },
                        onCenter = {
                            val browseId = pl.browseId ?: return@MenuItem
                            push(collectionOptionsPage(pl.title, browseId))
                        },
                    )
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
    val root = remember(home, likedAll, likedTotal, librarySongs, playlists, flowStatus, player.song) {
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
                MenuItem("Liked Songs", value = likedTotal?.toString()) {
                    push(
                        ClassipodPage.PagedTracks(
                            "Liked Songs",
                            com.music.bitchord.data.YtMusicRepository.LIKED_MUSIC,
                            reportTotal = true,
                        ),
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






    var volume by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0.8f) }

    ClassipodRoot(
        darkTheme = darkTheme,
        stack = stack,
        onPush = ::push,
        onPop = ::pop,
        onPopToRoot = { stack = listOf(stack.first()) },
        wheel = wheel,
        modifier = modifier,
        content = { page ->
            Box(Modifier.fillMaxSize()) {
                ClassipodPageContent(
                page = page,
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
                openPodSongMenu = ::openPodSongMenu,
                openTracks = ::openTracks,
                openPaged = ::openPaged,
                openCollectionOptions = { title, id -> push(collectionOptionsPage(title, id)) },
                playFromList = ::playFromList,
                playAndShow = ::playAndShow,
                onLikedTotal = { likedTotal = it },
                podSettingsRows = ::podSettingsRows,
                sleepRows = ::sleepRows,
                shuffleOn = shuffleOn,
                repeatMode = repeatMode,
                scope = scope,
                onPop = ::pop,
                )
                QueueActionNoticeHost(
                queueNotice,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 54.dp),
            )
            if (notice != null) {
                    Text(
                        text = notice.orEmpty(),
                        fontFamily = ClassipodTheme.helveticaBold,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color.White,
                        maxLines = 2,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp, start = 16.dp, end = 16.dp)
                            .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
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
    openPodSongMenu: (Song) -> Unit,
    openTracks: (String, List<Song>, QueueSource) -> Unit,
    openPaged: (String, String, Boolean) -> Unit,
    openCollectionOptions: (String, String) -> Unit,
    playFromList: (List<Song>, Int, String, com.music.bitchord.data.model.PlaybackSourceType) -> Unit,
    playAndShow: (List<Song>, Int, String, com.music.bitchord.data.model.PlaybackSourceType) -> Unit,
    podSettingsRows: () -> List<PodSettingRow>,
    sleepRows: () -> List<PodSettingRow>,
    shuffleOn: Boolean,
    repeatMode: Int,
    scope: kotlinx.coroutines.CoroutineScope,
    onLikedTotal: (Int) -> Unit,
    onPop: () -> Unit,
) {
    when (page) {
            is ClassipodPage.Menu -> ClassipodMenuPage(
                page = page, lcd = lcd, wheel = wheel, onBack = onPop,
            )
            is ClassipodPage.CoverFlow -> ClassipodCoverFlow(
                albums = page.albums,
                lcd = lcd,
                wheel = wheel,
                onSelect = { album ->
                    openTracks(
                        album.name, album.songs,
                        QueueSource(album.name, com.music.bitchord.data.model.PlaybackSourceType.BROWSE),
                    )
                },
                onBack = onPop,
            )
            is ClassipodPage.PagedTracks -> ClassipodPagedTracks(
                title = page.title,
                browseId = page.browseId,
                lcd = lcd,
                wheel = wheel,
                onPlay = { songs, index ->
                    playAndShow(
                        songs, index, page.title,
                        com.music.bitchord.data.model.PlaybackSourceType.BROWSE,
                    )
                },
                onLongPress = openPodSongMenu,
                onTotal = { if (page.reportTotal) onLikedTotal(it) },
                onBack = onPop,
                currentSong = player.song,
                isPlaying = player.isPlaying,
            )
            is ClassipodPage.Tracks -> ClassipodTrackList(
                title = page.title,
                songs = page.songs,
                lcd = lcd,
                wheel = wheel,
                onPlay = { songs, index -> playAndShow(songs, index, page.source.title, page.source.type) },
                onLongPress = openPodSongMenu,
                onBack = onPop,
                currentSong = player.song,
                isPlaying = player.isPlaying,
            )
            ClassipodPage.NowPlaying -> {
                val song = player.song
                val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
                val lyricsChecked by viewModel.lyricsChecked.collectAsStateWithLifecycle()
                val holdScope = rememberCoroutineScope()
                var holdJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                androidx.compose.runtime.DisposableEffect(Unit) {
                    onDispose { holdJob?.cancel(); holdJob = null }
                }
                ClassipodNowPlaying(
                    song = song,
                    isPlaying = player.isPlaying,
                    positionMs = player.position.positionMs,
                    durationMs = player.durationMs,
                    queuePosition = (player.queueIndex + 1).coerceAtLeast(1),
                    queueTotal = player.queue.size,
                    qualityLine = discordAudioQualityLine(nerdStats),
                    lyrics = lyrics,
                    lyricsChecked = lyricsChecked,
                    queue = player.queue,
                    queueIndex = player.queueIndex,
                    shuffleOn = shuffleOn,
                    repeatMode = repeatMode,
                    lcd = lcd,
                    wheel = wheel,
                    volume = volume,
                    onVolume = onVolume,
                    onSeek = { controller?.seekTo(it) },
                    onPlayAt = { index -> controller?.seekTo(index, 0) },
                    onHoldSeekStart = { dir ->
                        holdJob?.cancel()
                        holdJob = holdScope.launch {
                            while (true) {
                                controller?.let { c ->
                                    val d = c.duration.coerceAtLeast(0)
                                    if (d > 0) c.seekTo((c.currentPosition + dir * 8000).coerceIn(0, d))
                                }
                                delay(300)
                            }
                        }
                    },
                    onHoldSeekStop = { holdJob?.cancel(); holdJob = null },
                    onSongMenu = openPodSongMenu,
                    onToggleShuffle = { AppSettings.setShuffleEnabled(!shuffleOn) },
                    onCycleRepeat = {
                        val next = when (repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                        AppSettings.setRepeatMode(next)
                        controller?.repeatMode = next
                    },
                )
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
                val typeahead by viewModel.typeaheadResults.collectAsStateWithLifecycle()
                LaunchedEffect(query) {
                    if (query.isNotBlank()) {
                        delay(700)
                        viewModel.submitSearch()
                    }
                }
                val shown =
                    if (typeahead.isNotEmpty()) UiState.Success(typeahead) else results
                ClassipodSearch(
                    query = query,
                    onQuery = viewModel::onQueryChange,
                    results = shown,
                    lcd = lcd,
                    wheel = wheel,
                    onSong = { songs, index ->
                        playAndShow(
                            songs, index, "Search",
                            com.music.bitchord.data.model.PlaybackSourceType.SEARCH,
                        )
                    },
                    onSongLongPress = openPodSongMenu,
                    onBrowse = { item ->
                        openPaged(item.title, item.browseId, false)
                    },
                    onBrowseCenter = { item ->
                        if (item.type == BrowseType.PLAYLIST) {
                            openCollectionOptions(item.title, item.browseId)
                        } else {
                            openPaged(item.title, item.browseId, false)
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
                    onLongPress = openPodSongMenu,
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
