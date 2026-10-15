package com.music.bitchord.data.flow

/**
 * Deezer-inspired Flow for BitChord: an endless personalized mix combining
 * listening history, favorites, skips and bans with fresh recommendations.
 *
 * Pure-Kotlin models so Android, desktop and tests share unlock rules.
 */
object FlowRules {
    /** Flow appears on Home once favorited tracks+artists reach this many. */
    const val UNLOCK_FAV_TOTAL = 16

    /** Newer builds also unlock on this many favorited artists alone. */
    const val UNLOCK_ARTIST_COUNT = 10

    const val FLOW_RADIO_NAME = "Flow"

    /** Synthetic browse id used by the Home Flow shelf/card click path. */
    const val FLOW_BROWSE_ID = "bitchord:flow"

    /** Synthetic shelf title for the Home entry point. */
    const val FLOW_SHELF_TITLE = "Flow — Your personal soundtrack"

    fun isUnlocked(favTrackCount: Int, favArtistCount: Int): Boolean =
        (favTrackCount + favArtistCount >= UNLOCK_FAV_TOTAL) ||
            (favArtistCount >= UNLOCK_ARTIST_COUNT)
}

/** Filter Flow by mood, Deezer-style. FLOW = unfiltered endless mix. */
enum class FlowMood(val label: String) {
    FLOW("Flow"),
    LOVE("Love"),
    WORKOUT("Workout"),
    CHILL("Chill"),
    SAD("Sad"),
    FOCUS("Focus"),
    PARTY("Party"),
}

/**
 * Flow Tuner (Deezer 2026-style): actively steer the algorithm.
 *
 * Two independent axes:
 * @param discovery fresh-vs-familiar ratio. 0 = only likes, 1 = only fresh
 *   finds (past ~0.85 likes and history are dropped from the pool entirely).
 * @param memory recent-vs-all-time weight. 0 leans the mix on all-time
 *   likes; 1 leans it on what has actually been playing lately.
 * @param excludedGenres genre names toggled OFF in the tuner — never seeded,
 *   and filtered when [genreOf] can resolve a candidate's genres.
 */
data class FlowTuner(
    val discovery: Float = 0.35f,
    val memory: Float = 0.5f,
    val excludedGenres: Set<String> = emptySet(),
) {
    fun withDiscovery(value: Float) = copy(discovery = value.coerceIn(0f, 1f))
    fun withMemory(value: Float) = copy(memory = value.coerceIn(0f, 1f))
    fun toggleGenre(genre: String, enabled: Boolean): FlowTuner =
        if (enabled) copy(excludedGenres = excludedGenres - genre.lowercase())
        else copy(excludedGenres = excludedGenres + genre.lowercase())

    fun isGenreEnabled(genre: String): Boolean = genre.lowercase() !in excludedGenres
}

data class FlowConfig(
    val mood: FlowMood = FlowMood.FLOW,
    val tuner: FlowTuner = FlowTuner(),
)

data class FlowStatus(
    val unlocked: Boolean,
    val favTrackCount: Int,
    val favArtistCount: Int,
) {
    /** How many more favorites before Flow unlocks. 0 when unlocked. */
    val neededMore: Int
        get() = if (unlocked) 0 else maxOf(
            FlowRules.UNLOCK_FAV_TOTAL - (favTrackCount + favArtistCount),
            0,
        )
}

/** Curated genre list for the Tuner toggles (subset of ArtistFacts vocabulary). */
val FLOW_TUNER_GENRES = listOf(
    "Pop", "Hip-Hop", "Rock", "Electronic", "Dance", "R&B",
    "Latin", "K-Pop", "Country", "Jazz", "Classical", "Metal",
    "Indie", "Folk", "Soul", "Reggae", "Punk", "Blues",
    "Soundtrack", "Ambient",
)

/** Mood -> genre keywords used to boost matching seeds/candidates. */
fun FlowMood.genreHints(): List<String> = when (this) {
    FlowMood.FLOW -> emptyList()
    FlowMood.LOVE -> listOf("r&b", "soul", "pop", "acoustic")
    FlowMood.WORKOUT -> listOf("hip-hop", "electronic", "dance", "edm", "metal")
    FlowMood.CHILL -> listOf("ambient", "chill", "acoustic", "jazz", "lo-fi")
    FlowMood.SAD -> listOf("acoustic", "indie", "soul", "folk", "piano")
    FlowMood.FOCUS -> listOf("classical", "ambient", "jazz", "lo-fi", "soundtrack")
    FlowMood.PARTY -> listOf("dance", "pop", "latin", "hip-hop", "edm")
}
