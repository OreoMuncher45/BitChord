package com.music.bitchord.ui.classipod

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings

/**
 * The real iPod click: `ipod_click.wav`, courtesy of adeeteya/Classipod
 * (BSD-3-Clause, © 2025 Aditya R — see NOTICE in this folder's README).
 *
 * One SoundPool, loaded once — a MediaPlayer per click would leak a player
 * per detent, and ToneGenerator's beep is the wrong sound entirely.
 * Silent unless the Classipod click-sounds switch is on.
 */
object ClassipodClicks {

    @Volatile
    private var pool: SoundPool? = null

    @Volatile
    private var soundId: Int = 0

    @Volatile
    private var loaded: Boolean = false

    fun play(context: Context) {
        if (!AppSettings.classipodClicks.value) return
        runCatching {
            val p = pool ?: SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .build()
                .also { pool = it }
            if (!loaded) {
                soundId = p.load(context.applicationContext, R.raw.ipod_click, 1)
                loaded = true
                return
            }
            if (soundId != 0) p.play(soundId, 0.5f, 0.5f, 1, 0, 1f)
        }
    }
}
