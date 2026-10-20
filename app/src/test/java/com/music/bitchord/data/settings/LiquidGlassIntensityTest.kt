package com.music.bitchord.data.settings

import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The intensity slider's range rules, as plain logic plus the setter's write.
 *
 * `AppSettings` cannot be built on the JVM — its [SharedPreferences] are
 * Android's — so the clamp is a pure function and the persistence is pinned
 * through the same reflection-injected proxy prefs [QueueShuffleTierTest]
 * uses. The bug shape both guard against: a slider value off the ends of the
 * range stored as-is, zeroing the glass material into the flat fill the
 * Liquid Glass switch is for.
 */
class LiquidGlassIntensityTest {

    private val prefsField = AppSettings::class.java.getDeclaredField("prefs")
    private var savedIntensity = 1f
    private var savedPrefs: Any? = null

    @Before
    fun remember() {
        prefsField.isAccessible = true
        savedIntensity = AppSettings.liquidGlassIntensity.value
        savedPrefs = runCatching { prefsField.get(AppSettings) }.getOrNull()
    }

    @After
    fun restore() {
        AppSettings.liquidGlassIntensity.value = savedIntensity
        prefsField.set(AppSettings, savedPrefs)
    }

    @Test
    fun `full intensity is the default`() {
        assertEquals(AppSettings.MAX_LIQUID_GLASS_INTENSITY, AppSettings.liquidGlassIntensity.value)
    }

    @Test
    fun `the floor keeps the material alive instead of zeroing it`() {
        assertEquals(AppSettings.MIN_LIQUID_GLASS_INTENSITY, AppSettings.normalizeLiquidGlassIntensity(0f))
        assertEquals(AppSettings.MIN_LIQUID_GLASS_INTENSITY, AppSettings.normalizeLiquidGlassIntensity(-3f))
    }

    @Test
    fun `the ceiling caps above-range values`() {
        assertEquals(AppSettings.MAX_LIQUID_GLASS_INTENSITY, AppSettings.normalizeLiquidGlassIntensity(1.5f))
        assertEquals(AppSettings.MAX_LIQUID_GLASS_INTENSITY, AppSettings.normalizeLiquidGlassIntensity(7f))
    }

    @Test
    fun `in-range values pass through untouched`() {
        assertEquals(0.37f, AppSettings.normalizeLiquidGlassIntensity(0.37f))
        assertEquals(1f, AppSettings.normalizeLiquidGlassIntensity(1f))
        assertEquals(0.1f, AppSettings.normalizeLiquidGlassIntensity(0.1f))
    }

    @Test
    fun `the setter clamps before it writes, under its own key`() {
        val writes = mutableListOf<Pair<String, Float>>()
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            if (method.name == "putFloat") {
                writes += args[0] as String to args[1] as Float
            }
            if (method.returnType == SharedPreferences.Editor::class.java) proxy else null
        }
        val prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, _ -> if (method.name == "edit") editor else null }
        prefsField.set(AppSettings, prefs)

        AppSettings.setLiquidGlassIntensity(0f)
        AppSettings.setLiquidGlassIntensity(2f)

        assertEquals(
            listOf(
                "liquid_glass_intensity" to AppSettings.MIN_LIQUID_GLASS_INTENSITY,
                "liquid_glass_intensity" to AppSettings.MAX_LIQUID_GLASS_INTENSITY,
            ),
            writes,
        )
    }
}
