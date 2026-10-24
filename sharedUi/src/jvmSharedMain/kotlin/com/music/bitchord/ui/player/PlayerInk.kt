package com.music.bitchord.ui.player

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.music.bitchord.ui.graphics.ColorUtils
import com.music.bitchord.ui.theme.SINGLE_COLOUR_SHARE

/**
 * Ink for the controls laid directly over album artwork.
 *
 * White by default, so every surface outside the expanded player keeps its
 * existing look. The player swaps this to near-black when the sleeve's lower
 * half is bright enough that white glyphs would wash out.
 */
internal val LocalPlayerInk = staticCompositionLocalOf { Color.White }

/**
 * A soft text shadow, so dark ink stays legible where it crosses a dark patch
 * of an otherwise bright sleeve, and white ink keeps its edge over a bright one.
 *
 * Transparent by default, so nothing outside the expanded player changes.
 */
internal val LocalPlayerInkGlow = staticCompositionLocalOf { Color.Transparent }

/**
 * The chrome colour that stands in for "Use artwork".
 *
 * The backdrop is a blur of the sleeve, so its average is the colour of that
 * blur. Chrome keeps that hue — the record's own colour, not its complement —
 * and moves it only as far toward black or white as the backdrop demands.
 * Keeping the hue is what makes it read as *this* cover's colour; the move is
 * what makes it legible, and it is measured rather than assumed.
 *
 * "A bit brighter" is not a contrast rule. A mid orange lifted by a fixed
 * step is still orange on orange, which is why low-colour sleeves used to lose
 * their titles and transport. So the ink is scored with WCAG contrast against
 * the luminance of the artwork behind the controls, and only accepted once it
 * clears [MIN_INK_CONTRAST]. The direction is whichever pole gets there with
 * less travel, so a dark sleeve gets lightened ink and a bright one gets
 * deepened ink, both still tinted. When no tint can clear the bar, the result
 * is a near-black or near-white with the hue removed.
 *
 * A sleeve that is mostly one colour is the exception. Repeating that colour
 * as the ink turns the whole player into one flat hue, so the ink borrows the
 * less-used colour the artwork holds instead — still contrast-checked, still
 * deepened or lightened until it reads. When the artwork holds no second hue,
 * the ink is black and white. [leadingShare] and [secondary] come from the
 * same palette read that produced [average]; see `chooseArtworkTint`.
 */
internal fun artworkAverageInk(
    average: Color?,
    controlLuminance: Float?,
    leadingShare: Float = 0f,
    secondary: Color? = null,
): Color {
    val backdrop = controlLuminance
        ?: average?.let { ColorUtils.calculateLuminance(it.toArgb()).toFloat() }
        ?: 0f

    val source = if (leadingShare >= SINGLE_COLOUR_SHARE) secondary else average
    if (source == null) return neutralInk(backdrop)

    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(source.toArgb(), it) }
    if (hsl[1] < MONOCHROME_SATURATION) return neutralInk(backdrop)

    // A small floor: a muted average still reads as a tint, not as grey.
    val saturation = hsl[1].coerceIn(0.22f, 0.80f)
    return tintedInk(hsl[0], saturation, hsl[2], backdrop) ?: neutralInk(backdrop)
}

/** Light ink needs the dark glow, and the reverse. */
internal fun Color.isLightInk(): Boolean =
    ColorUtils.calculateLuminance(toArgb()) >= 0.5f

/**
 * The smallest contrast ratio the player accepts between ink and the artwork
 * behind it. WCAG AA for normal text — the song title and artist line.
 */
internal const val MIN_INK_CONTRAST = 4.5f

/** WCAG's contrast ratio, from two relative luminances. */
internal fun contrastRatio(one: Float, two: Float): Float {
    val lighter = maxOf(one, two)
    val darker = minOf(one, two)
    return (lighter + 0.05f) / (darker + 0.05f)
}

/**
 * The same hue moved until it clears [MIN_INK_CONTRAST], or null when even
 * black and white cannot get there — the caller then falls back to a neutral.
 *
 * Each pole is searched for the *closest* passing lightness, and the pole that
 * moves less wins. Travelling less is what keeps the tint: a hue only has to
 * give up its saturation when its own lightness is nowhere near legible.
 */
private fun tintedInk(
    hue: Float,
    saturation: Float,
    sourceLightness: Float,
    backdrop: Float,
): Color? {
    fun at(lightness: Float) = Color(
        ColorUtils.HSLToColor(floatArrayOf(hue, saturation, lightness)),
    )

    fun luminance(lightness: Float) =
        ColorUtils.calculateLuminance(at(lightness).toArgb()).toFloat()

    fun passes(lightness: Float) =
        contrastRatio(luminance(lightness), backdrop) >= MIN_INK_CONTRAST

    val source = at(sourceLightness)
    if (passes(sourceLightness)) return source

    // Darker: the darkest is L = 0, and the passing range is [0, threshold].
    val dark = if (passes(0f)) {
        var lo = 0f
        var hi = sourceLightness
        repeat(INK_SEARCH_STEPS) {
            val mid = (lo + hi) / 2f
            if (passes(mid)) lo = mid else hi = mid
        }
        at(lo) to (sourceLightness - lo)
    } else {
        null
    }

    // Lighter: the passing range is [threshold, 1].
    val light = if (passes(1f)) {
        var lo = sourceLightness
        var hi = 1f
        repeat(INK_SEARCH_STEPS) {
            val mid = (lo + hi) / 2f
            if (passes(mid)) hi = mid else lo = mid
        }
        at(hi) to (hi - sourceLightness)
    } else {
        null
    }

    return when {
        dark == null -> light?.first
        light == null -> dark.first
        else -> if (dark.second <= light.second) dark.first else light.first
    }
}

/**
 * Black or white, whichever the backdrop leaves more room for, with the
 * barely-warm tint the player already used on hue-less sleeves — unless that
 * tint is the difference between passing and not.
 */
private fun neutralInk(backdrop: Float): Color {
    fun contrastOf(color: Color) =
        contrastRatio(ColorUtils.calculateLuminance(color.toArgb()).toFloat(), backdrop)

    val warmBlack = Color(0xFF1B1513)
    val warmWhite = Color(0xFFFFF6F1)
    val darkContrast = contrastOf(warmBlack)
    val lightContrast = contrastOf(warmWhite)
    val useDark = darkContrast >= lightContrast
    if (maxOf(darkContrast, lightContrast) >= MIN_INK_CONTRAST) {
        return if (useDark) warmBlack else warmWhite
    }
    return if (useDark) Color.Black else Color.White
}

/** Steps of the binary search for a passing lightness; 12 is well under a perceptible step. */
private const val INK_SEARCH_STEPS = 12

/** Below this a sleeve has no hue worth tinting. */
private const val MONOCHROME_SATURATION = 0.10f
