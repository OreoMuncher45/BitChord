package com.music.bitchord.data.flow

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueBuilder
import com.music.bitchord.playback.QueueTimeline.asQueueEntry
import kotlin.math.min
import kotlin.random.Random

/**
 * Builds Deezer-style Flow queues from local signals + YouTube radio.
 *
 * Signals (all optional, best-effort):
 * - favorites: liked library tracks (highest weight)
 * - history: recent plays, newest-first (recency weight)
 * - quickPicks: fresh recommendations shelf (discovery pool)
 * - likeStatuses: session ratings incl. DISLIKE (hard filter)
 * - bannedIds: user-banned tracks, never return
 * - skipCounts: videoId -> skips; >=2 skips demotes, >=3 excludes
 * - genreOf: artist name -> genres (ArtistFacts on Android), for mood/tuner
 *
 * Endless playback reuses the Autoplay loop: the initial batch is a Flow
 * queue and [nextSeed] picks the rotating seed for top-ups.
 */
object FlowEngine {

    const val INITIAL_TRACKS = 50
    const val TOPUP_TRACKS = 10
    private const val SKIP_EXCLUDE_AFTER = 3
    private const val SKIP_DEMOTE_AFTER = 2

    data class SeedPool(
        val favorites: List<Song> = emptyList(),
        val history: List<Song> = emptyList(),
        val quickPicks: List<Song> = emptyList(),
    )

    /**
     * Score candidates for Flow ordering. Higher = earlier in the mix.
     * Pure function so tests don't need network.
     */
    fun score(
        candidate: Song,
        favIds: Set<String>,
        historyRank: Map<String, Int>,
        skipCounts: Map<String, Int>,
        tuner: FlowTuner,
        moodHints: List<String>,
        genreOf: (String) -> List<String> = { emptyList() },
    ): Double {
        var s = 1.0
        // Likes are always the backbone — unless the pool gate below removed
        // them, in which case they never reach this function at all.
        if (candidate.videoId in favIds) s += 4.0
        historyRank[candidate.videoId]?.let { rank ->
            // Newest history (rank 0) scores highest, decays over ~20 plays,
            // scaled by the Memory slider: all-time mode barely listens to
            // recent plays, recent mode lets them dominate.
            val memory = tuner.memory.coerceIn(0f, 1f)
            s += (1.0 - min(rank, 20) / 22.0) * (0.4 + 2.4 * memory)
        }
        val skips = skipCounts[candidate.videoId] ?: 0
        if (skips >= SKIP_DEMOTE_AFTER) s -= 2.0
        // Discovery slider: fresh (non-fav, non-history) tracks get a boost.
        val isFresh = candidate.videoId !in favIds && candidate.videoId !in historyRank
        if (isFresh) s += tuner.discovery.coerceIn(0f, 1f) * 2.0 else s += 0.5
        // Mood boost via genre hints.
        if (moodHints.isNotEmpty()) {
            val genres = genreOf(candidate.artist).map { it.lowercase() }
            if (genres.any { g -> moodHints.any { h -> g.contains(h) } }) s += 1.5
        }
        // Slight artist-name match counts too (no genre lookup available).
        if (moodHints.isNotEmpty()) {
            val hay = "${candidate.title} ${candidate.artist}".lowercase()
            if (moodHints.any { hay.contains(it) }) s += 0.5
        }
        return s
    }

    fun isExcluded(
        candidate: Song,
        bannedIds: Set<String>,
        likeStatuses: Map<String, LikeStatus>,
        skipCounts: Map<String, Int>,
        tuner: FlowTuner,
        genreOf: (String) -> List<String> = { emptyList() },
    ): Boolean {
        if (candidate.videoId in bannedIds) return true
        if (likeStatuses[candidate.videoId] == LikeStatus.DISLIKE) return true
        if ((skipCounts[candidate.videoId] ?: 0) >= SKIP_EXCLUDE_AFTER) return true
        if (tuner.excludedGenres.isNotEmpty()) {
            val genres = genreOf(candidate.artist).map { it.lowercase() }
            if (genres.any { it in tuner.excludedGenres }) return true
        }
        return false
    }

    /**
     * Order + dedupe a local pool into the Flow opening order.
     * No network; used for instant playback before radio expansion.
     */
    fun orderLocalPool(
        pool: List<Song>,
        favIds: Set<String>,
        history: List<Song>,
        skipCounts: Map<String, Int>,
        tuner: FlowTuner,
        mood: FlowMood,
        genreOf: (String) -> List<String> = { emptyList() },
        limit: Int = INITIAL_TRACKS,
        random: Random = Random.Default,
    ): List<Song> {
        val historyRank = history.mapIndexed { i, s -> s.videoId to i }.toMap()
        val hints = mood.genreHints()
        // Hard pool gates at the slider extremes, so full Adventurous means
        // zero familiar tracks and full Personal means zero fresh ones —
        // scoring alone could never promise that.
        val discovery = tuner.discovery.coerceIn(0f, 1f)
        val gated = pool.distinctBy { it.videoId }.let { distinct ->
            when {
                discovery >= 0.85f -> distinct.filter { it.videoId !in favIds && it.videoId !in historyRank }
                discovery <= 0.15f -> distinct.filter { it.videoId in favIds || it.videoId in historyRank }
                else -> distinct
            }
        }
        return gated
            .filterNot { isExcluded(it, emptySet(), emptyMap(), skipCounts, tuner, genreOf) }
            .map { it to score(it, favIds, historyRank, skipCounts, tuner, hints, genreOf) }
            .sortedByDescending { it.second }
            // Interleave: avoid >2 same-artist tracks in a row for mix feel.
            .map { it.first }
            .let { spreadArtists(it, random) }
            .take(limit)
    }

    /**
     * Full initial Flow queue: local ordered pool expanded with YouTube radio.
     * Falls back to the local pool alone when offline/radio fails.
     */
    suspend fun buildInitialQueue(
        seeds: SeedPool,
        likeStatuses: Map<String, LikeStatus> = emptyMap(),
        bannedIds: Set<String> = emptySet(),
        skipCounts: Map<String, Int> = emptyMap(),
        config: FlowConfig = FlowConfig(),
        limit: Int = INITIAL_TRACKS,
        genreOf: (String) -> List<String> = { emptyList() },
    ): List<Song> {
        val favIds = seeds.favorites.map { it.videoId }.toSet()
        val localPool = (seeds.favorites + seeds.history + seeds.quickPicks).distinctBy { it.videoId }
            .filterNot { isExcluded(it, bannedIds, likeStatuses, skipCounts, config.tuner, genreOf) }
        val ordered = orderLocalPool(
            pool = localPool.ifEmpty { seeds.quickPicks },
            favIds = favIds,
            history = seeds.history,
            skipCounts = skipCounts,
            tuner = config.tuner,
            mood = config.mood,
            genreOf = genreOf,
            limit = min(limit, 12),
        )
        if (ordered.isEmpty()) {
            // Nothing local (fresh account edge): fall back to quick picks raw.
            val fallback = seeds.quickPicks
                .filterNot { isExcluded(it, bannedIds, likeStatuses, skipCounts, config.tuner, genreOf) }
                .take(limit)
            return tagFlow(fallback)
        }
        // Expand via radio on rotating favorite/history seeds for breadth.
        val expanded = ordered.toMutableList()
        val radioSeeds = pickRadioSeeds(ordered, seeds.favorites, config.tuner)
        for (seed in radioSeeds) {
            if (expanded.size >= limit) break
            val related = runCatching { YtMusicRepository.radio(seed.videoId).getOrNull() }
                .getOrNull().orEmpty()
                .filterNot { isExcluded(it, bannedIds, likeStatuses, skipCounts, config.tuner, genreOf) }
                // Full Adventurous keeps liked tracks out of the expansion too.
                .filterNot { config.tuner.discovery >= 0.85f && it.videoId in favIds }
            val extra = QueueBuilder.extend(expanded, related, min(6, limit - expanded.size))
            // Re-score radio tracks so favorites-weighting + mood still apply.
            val historyRank = seeds.history.mapIndexed { i, s -> s.videoId to i }.toMap()
            val ranked = extra.sortedByDescending {
                score(it, favIds, historyRank, skipCounts, config.tuner, config.mood.genreHints(), genreOf)
            }
            expanded += ranked.filterNot { c -> expanded.any { QueueBuilder.isSameRecording(it, c) } }
        }
        val final = expanded.distinctBy { it.videoId }.take(limit)
        if (final.isEmpty()) return tagFlow(ordered.take(limit))
        return tagFlow(final)
    }

    /** Rotating seed for endless top-ups: least-recently-seeded favorite first. */
    fun nextSeed(
        queue: List<Song>,
        favorites: List<Song>,
        seedCursor: Int,
    ): Song? {
        if (favorites.isNotEmpty()) return favorites[seedCursor % favorites.size]
        return queue.lastOrNull()
    }

    private fun pickRadioSeeds(
        ordered: List<Song>,
        favorites: List<Song>,
        tuner: FlowTuner,
    ): List<Song> {
        // Discovery high -> seed from history/quick picks; low -> favorites.
        return if (tuner.discovery > 0.6f) ordered.take(4)
        else (favorites.take(2) + ordered.take(2)).distinctBy { it.videoId }.take(4)
    }

    private fun spreadArtists(tracks: List<Song>, random: Random): List<Song> {
        if (tracks.size < 4) return tracks
        val out = mutableListOf<Song>()
        val buckets = tracks.groupBy { it.artist.lowercase() }.mapValues { it.value.toMutableList() }
        val keys = buckets.keys.shuffled(random).toMutableList()
        var ki = 0
        var guard = tracks.size * 3
        while (out.size < tracks.size && guard-- > 0) {
            if (keys.isEmpty()) break
            val k = keys[ki % keys.size]
            val bucket = buckets[k]
            if (bucket == null) {
                ki++
                continue
            }
            if (bucket.isNotEmpty()) out += bucket.removeAt(0)
            if (bucket.isEmpty()) keys.remove(k) else ki++
        }
        // Any leftovers (guard exhausted on pathological input).
        buckets.values.forEach { out += it }
        return out.distinctBy { it.videoId }
    }

    /** Tag tracks as Flow origin so Now Playing + queue show the source. */
    fun tagFlow(tracks: List<Song>): List<Song> = tracks.map {
        it.copy(
            radioName = FlowRules.FLOW_RADIO_NAME,
            playbackSource = FlowRules.FLOW_RADIO_NAME,
            playbackSourceType = PlaybackSourceType.EXPLORE,
            playbackSourceId = FlowRules.FLOW_BROWSE_ID,
        ).asQueueEntry(QueueTier.CONTEXT)
    }
}
