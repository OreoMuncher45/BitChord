package com.music.bitchord.data.flow

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.flow.FlowConfig
import com.music.bitchord.data.flow.FlowMood
import com.music.bitchord.data.flow.FlowTuner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Flow persistence: mood, tuner, bans and skip signals.
 *
 * Own prefs file so Flow state survives AppSettings imports/exports.
 * Skips are counts (demote after 2, exclude after 3); bans are permanent
 * until unbanned from the Tuner. Capped so the files stay small.
 */
object FlowStore {

    private lateinit var prefs: SharedPreferences

    private const val FILE = "bitchord_flow"
    private const val KEY_MOOD = "flow_mood"
    private const val KEY_DISCOVERY = "flow_discovery"
    private const val KEY_FAV_BIAS = "flow_fav_bias"
    private const val KEY_EXCLUDED_GENRES = "flow_excluded_genres"
    private const val KEY_BANNED = "flow_banned"
    private const val KEY_SKIPS = "flow_skips"
    private const val KEY_SEED_CURSOR = "flow_seed_cursor"

    private const val MAX_BANNED = 200
    private const val MAX_SKIPS = 300

    private val _mood = MutableStateFlow(FlowMood.FLOW)
    val mood: StateFlow<FlowMood> = _mood.asStateFlow()

    private val _tuner = MutableStateFlow(FlowTuner())
    val tuner: StateFlow<FlowTuner> = _tuner.asStateFlow()

    val config: FlowConfig get() = FlowConfig(_mood.value, _tuner.value)

    private val _bannedIds = MutableStateFlow<Set<String>>(emptySet())
    val bannedIds: StateFlow<Set<String>> = _bannedIds.asStateFlow()

    private val _skipCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val skipCounts: StateFlow<Map<String, Int>> = _skipCounts.asStateFlow()

    /** Rotating radio-seed cursor for endless top-ups. */
    var seedCursor: Int = 0
        private set

    fun init(context: Context) {
        if (this::prefs.isInitialized) return
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _mood.value = runCatching { FlowMood.valueOf(prefs.getString(KEY_MOOD, null) ?: "FLOW") }
            .getOrDefault(FlowMood.FLOW)
        _tuner.value = FlowTuner(
            discovery = prefs.getFloat(KEY_DISCOVERY, 0.35f),
            favoritesBias = prefs.getFloat(KEY_FAV_BIAS, 0.65f),
            excludedGenres = prefs.getStringSet(KEY_EXCLUDED_GENRES, emptySet()).orEmpty(),
        )
        _bannedIds.value = prefs.getStringSet(KEY_BANNED, emptySet()).orEmpty().take(MAX_BANNED).toSet()
        _skipCounts.value = readSkips()
        seedCursor = prefs.getInt(KEY_SEED_CURSOR, 0)
    }

    fun setMood(mood: FlowMood) {
        _mood.value = mood
        if (this::prefs.isInitialized) prefs.edit().putString(KEY_MOOD, mood.name).apply()
    }

    fun setDiscovery(value: Float) {
        _tuner.value = _tuner.value.withDiscovery(value)
        if (this::prefs.isInitialized) prefs.edit().putFloat(KEY_DISCOVERY, _tuner.value.discovery).apply()
    }

    fun setFavoritesBias(value: Float) {
        _tuner.value = _tuner.value.withFavoritesBias(value)
        if (this::prefs.isInitialized) prefs.edit().putFloat(KEY_FAV_BIAS, _tuner.value.favoritesBias).apply()
    }

    fun toggleGenre(genre: String, enabled: Boolean) {
        _tuner.value = _tuner.value.toggleGenre(genre, enabled)
        if (this::prefs.isInitialized) {
            prefs.edit().putStringSet(KEY_EXCLUDED_GENRES, _tuner.value.excludedGenres).apply()
        }
    }

    /** Replaces the exclusion set wholesale — used by batched Apply. */
    fun setExcludedGenres(genres: Set<String>) {
        _tuner.value = _tuner.value.copy(excludedGenres = genres.map { it.lowercase() }.toSet())
        if (this::prefs.isInitialized) {
            prefs.edit().putStringSet(KEY_EXCLUDED_GENRES, _tuner.value.excludedGenres).apply()
        }
    }

    fun ban(videoId: String) {
        if (videoId.isBlank()) return
        val next = (_bannedIds.value + videoId).toList().takeLast(MAX_BANNED).toSet()
        _bannedIds.value = next
        if (this::prefs.isInitialized) prefs.edit().putStringSet(KEY_BANNED, next).apply()
    }

    fun unban(videoId: String) {
        _bannedIds.value = _bannedIds.value - videoId
        if (this::prefs.isInitialized) prefs.edit().putStringSet(KEY_BANNED, _bannedIds.value).apply()
    }

    fun isBanned(videoId: String): Boolean = videoId in _bannedIds.value

    fun clearBans() {
        _bannedIds.value = emptySet()
        if (this::prefs.isInitialized) prefs.edit().remove(KEY_BANNED).apply()
    }

    /**
     * Record a skip: counts toward demotion/exclusion in [FlowEngine].
     * Call only for user-initiated skips (<30s into the track or Next tap),
     * not for natural track ends — the caller decides.
     */
    fun recordSkip(videoId: String) {
        if (videoId.isBlank() || videoId in _bannedIds.value) return
        val next = _skipCounts.value.toMutableMap()
        next[videoId] = (next[videoId] ?: 0) + 1
        // Evict smallest counts first when over cap.
        if (next.size > MAX_SKIPS) {
            next.entries.sortedBy { it.value }.take(next.size - MAX_SKIPS)
                .forEach { next.remove(it.key) }
        }
        _skipCounts.value = next
        if (this::prefs.isInitialized) writeSkips(next)
    }

    /** A full listen forgives one skip — the track wasn't that bad. */
    fun recordFullListen(videoId: String) {
        val cur = _skipCounts.value[videoId] ?: return
        if (cur <= 1) {
            _skipCounts.value = _skipCounts.value - videoId
        } else {
            _skipCounts.value = _skipCounts.value + (videoId to cur - 1)
        }
        if (this::prefs.isInitialized) writeSkips(_skipCounts.value)
    }

    fun advanceSeedCursor() {
        seedCursor += 1
        if (this::prefs.isInitialized) prefs.edit().putInt(KEY_SEED_CURSOR, seedCursor).apply()
    }

    private fun readSkips(): Map<String, Int> {
        val raw = prefs.getString(KEY_SKIPS, "").orEmpty()
        if (raw.isBlank()) return emptyMap()
        val out = mutableMapOf<String, Int>()
        raw.split(";").forEach { part ->
            val kv = part.split("=")
            if (kv.size != 2 || kv[0].isBlank()) return@forEach
            val count = kv[1].toIntOrNull() ?: return@forEach
            if (count > 0) out[kv[0]] = count
        }
        return out
    }

    private fun writeSkips(map: Map<String, Int>) {
        prefs.edit().putString(KEY_SKIPS, map.entries.joinToString(";") { "${it.key}=${it.value}" }).apply()
    }
}
