package com.music.bitchord.ui.player

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The expanded player's colours, derived from the artwork alone.
 *
 * Every cover follows one path: a light tint of the artwork's accent hue on a
 * background clamped dark. That is the fix for covers whose own colours used
 * to become their foreground — a white sleeve with a red accent produced a
 * mid-grey ink on a light background, and the controls disappeared. Lightness
 * here is OKLCH L, so "0.92 on 0.40" means what it says to the eye rather than
 * only to a luminance formula.
 */
@Immutable
internal data class NowPlayingPalette(
    /** The mesh's stops after clamping, darkest-safe first. */
    val backgroundStops: List<Color>,
    /** Title, transport, filled progress, and every icon at rest. */
    val fg: Color,
    val alphas: NowPlayingAlphas = NowPlayingAlphas(),
    /** The highest OKLCH L the backdrop's mesh may use. */
    val backgroundLightnessCap: Float = MAX_BACKGROUND_L,
    /**
     * The highest WCAG luminance any backdrop cell may reach. Only the cells
     * that exceed it are darkened — a bright yellow patch is toned down while
     * the rest of the sleeve keeps the lightness it had. This is what keeps
     * the blur from collapsing to near-black on a pale cover.
     */
    val backgroundLuminanceLimit: Float = 1f,
)

/**
 * The player's opacity roles. Keeping them here rather than as literals at
 * each glyph means a cover change can never leave one control behind.
 */
@Immutable
internal data class NowPlayingAlphas(
    /** Title, play/pause, previous, next, filled progress and volume. */
    val primary: Float = 1f,
    /** Artist, time labels, the quality badge and the current lyric. */
    val secondary: Float = 0.70f,
    /** Lyrics, AirPlay, queue and volume icons, and the device name. */
    val tertiary: Float = 0.65f,
    /** The unfilled part of the progress and volume tracks. */
    val track: Float = 0.30f,
    /** The discs behind the round star and "..." buttons. */
    val buttonFill: Float = 0.20f,
)

/** The palette before any artwork has been read: neutral, dark, near-white ink. */
internal val DefaultNowPlayingPalette = NowPlayingPalette(
    backgroundStops = listOf(Oklch(0.36f, 0f, 0f).toColor()),
    fg = Oklch(0.92f, 0f, 0f).toColor(),
    backgroundLuminanceLimit = backgroundLuminanceLimit(Oklch(0.92f, 0f, 0f).toColor()),
)

/**
 * [art] is a sampled set of the sleeve's ARGB pixels. The function is pure so
 * the six awkward covers — dark, bright, grey, mono, saturated and neutral —
 * can be scored in a unit test with nothing composable involved.
 */
internal fun computeNowPlayingPalette(art: List<Int>): NowPlayingPalette {
    val buckets = cluster(art.filter { (it ushr 24) != 0 })
    if (buckets.isEmpty()) return DefaultNowPlayingPalette

    val total = buckets.sumOf { it.count }.coerceAtLeast(1)
    val accent = buckets
        .map { it to Oklch.fromArgb(it.color.toArgb()) }
        .filter { (bucket, oklch) ->
            oklch.chroma >= MIN_ACCENT_CHROMA &&
                oklch.l in MIN_ACCENT_L..MAX_ACCENT_L &&
                bucket.count.toFloat() / total >= MIN_ACCENT_SHARE
        }
        .maxByOrNull { (bucket, oklch) ->
            oklch.chroma * (bucket.count.toFloat() / total)
        }
        ?.second

    // Always light: the hue is the artwork's, the lightness is the player's.
    // Chroma is reduced only as far as sRGB needs, never L or H.
    val fg = Oklch(
        l = FOREGROUND_L,
        chroma = minOf(accent?.chroma ?: 0f, MAX_FOREGROUND_CHROMA),
        hue = accent?.hue ?: 0f,
    ).toColor()

    // One stop per leading colour; the mesh keeps their spatial arrangement
    // separately, but every cell is clamped by the same rule.
    val cap = MAX_BACKGROUND_L
    val luminanceLimit = backgroundLuminanceLimit(fg)
    val stops = buckets
        .sortedByDescending { it.count }
        .take(BACKGROUND_STOP_COUNT)
        .map { backgroundColor(it.color, cap, luminanceLimit) }

    return NowPlayingPalette(
        backgroundStops = stops,
        fg = fg,
        backgroundLightnessCap = cap,
        backgroundLuminanceLimit = luminanceLimit,
    )
}

/**
 * The brightest a backdrop cell may be while every text and icon role still
 * clears its contrast target over it.
 *
 * A role with opacity `a` over a backdrop of luminance `Yb` composites to
 * `a*Yf + (1-a)*Yb`, and WCAG contrast against that backdrop is
 * `(composite + 0.05) / (Yb + 0.05)`. Solving for `Yb` gives the ceiling.
 * Only the cell is darkened, so a sleeve that is mostly mid-toned stays
 * mid-toned; one bright patch no longer drags the whole blur down with it.
 */
internal fun backgroundLuminanceLimit(fg: Color): Float {
    val roles = NowPlayingAlphas()
    val ink = relativeLuminance(fg.toArgb())
    fun limit(alpha: Float, target: Float): Float =
        (alpha * ink + 0.05f * (1f - target)) / (target - 1f + alpha)
    return minOf(
        limit(roles.secondary, MIN_TEXT_CONTRAST + CONTRAST_MARGIN),
        limit(roles.tertiary, MIN_ICON_CONTRAST + CONTRAST_MARGIN),
        1f,
    ).coerceAtLeast(0f)
}

/** Text is scored at the dimmest text role — the artist line. */
internal fun minTextContrast(fg: Color, stops: List<Color>): Float =
    minContrast(fg, stops, NowPlayingAlphas().secondary)

/** Icons are scored at the dimmest icon role — the action row. */
internal fun minIconContrast(fg: Color, stops: List<Color>): Float =
    minContrast(fg, stops, NowPlayingAlphas().tertiary)

internal fun minTextContrast(palette: NowPlayingPalette): Float =
    minTextContrast(palette.fg, palette.backgroundStops)

internal fun minIconContrast(palette: NowPlayingPalette): Float =
    minIconContrast(palette.fg, palette.backgroundStops)

/**
 * The worst case anywhere on the backdrop: each role composited over the
 * lightest stop, since that is the stop that leaves the least room.
 */
private fun minContrast(fg: Color, stops: List<Color>, alpha: Float): Float {
    if (stops.isEmpty()) return contrastRatio(relativeLuminance(fg.toArgb()), 0f)
    return stops.minOf { stop ->
        val composite = fg.compositeOver(stop, alpha)
        contrastRatio(relativeLuminance(composite.toArgb()), relativeLuminance(stop.toArgb()))
    }
}

private fun Color.compositeOver(background: Color, alpha: Float): Color {
    val fgArgb = toArgb()
    val bgArgb = background.toArgb()
    fun channel(shift: Int): Float {
        val fg = srgbToLinear(((fgArgb shr shift) and 0xFF) / 255f)
        val bg = srgbToLinear(((bgArgb shr shift) and 0xFF) / 255f)
        return fg * alpha + bg * (1f - alpha)
    }
    return Color(
        red = linearToSrgb(channel(16)),
        green = linearToSrgb(channel(8)),
        blue = linearToSrgb(channel(0)),
    )
}

/** WCAG relative luminance, gamma-decoded rather than read off the bytes. */
private fun relativeLuminance(argb: Int): Float {
    fun linear(shift: Int) = srgbToLinear(((argb shr shift) and 0xFF) / 255f)
    return 0.2126f * linear(16) + 0.7152f * linear(8) + 0.0722f * linear(0)
}

private fun backgroundColor(source: Color, cap: Float, luminanceLimit: Float): Color =
    Oklch.fromArgb(source.toArgb()).clampedBackground(cap, luminanceLimit).toColor()

private fun Oklch.clampedBackground(cap: Float, luminanceLimit: Float): Oklch {
    val floor = minOf(MIN_BACKGROUND_L, cap)
    val chroma = if (this.chroma < MIN_BACKGROUND_CHROMA) {
        0f
    } else {
        this.chroma.coerceIn(MIN_BACKGROUND_CHROMA, MAX_BACKGROUND_CHROMA)
    }
    val base = Oklch(l = l.coerceIn(floor, cap), chroma = chroma, hue = hue)
    if (luminanceLimit >= 1f) return base
    if (relativeLuminance(base.toColor().toArgb()) <= luminanceLimit) return base

    // Luminance rises with L at a fixed hue and chroma, so the brightest
    // admissible cell is a search along L — hue and chroma stay untouched.
    var low = 0f
    var high = base.l
    repeat(LUMINANCE_STEPS) {
        val mid = (low + high) / 2f
        val lit = relativeLuminance(base.copy(l = mid).toColor().toArgb())
        if (lit <= luminanceLimit) low = mid else high = mid
    }
    return base.copy(l = low)
}

/**
 * The same clamp the palette says it used, for the mesh's own cells — see
 * [computeNowPlayingPalette] and `meshOf`.
 */
internal fun backgroundStopArgb(argb: Int, cap: Float, luminanceLimit: Float = 1f): Int =
    Oklch.fromArgb(argb).clampedBackground(cap, luminanceLimit).toColor().toArgb()

/** One colour's share of the sleeve, averaged rather than quantised to its bin. */
private class Bucket {
    var count = 0
    var red = 0L
    var green = 0L
    var blue = 0L

    val color: Color
        get() {
            val n = count.coerceAtLeast(1)
            return Color(
                red = (red / n).toInt(),
                green = (green / n).toInt(),
                blue = (blue / n).toInt(),
            )
        }
}

/**
 * 5 bits per channel is enough to separate a red accent from a grey sleeve,
 * and small enough that a 128 px cover produces a few dozen buckets rather
 * than one per pixel.
 */
private fun cluster(pixels: List<Int>): List<Bucket> {
    val buckets = HashMap<Int, Bucket>()
    pixels.forEach { argb ->
        val red = (argb shr 16) and 0xFF
        val green = (argb shr 8) and 0xFF
        val blue = argb and 0xFF
        val key = (red shr 3 shl 10) or (green shr 3 shl 5) or (blue shr 3)
        val bucket = buckets.getOrPut(key, ::Bucket)
        bucket.count++
        bucket.red += red
        bucket.green += green
        bucket.blue += blue
    }
    return buckets.values.toList()
}

/** OKLCH: L 0..1, chroma, hue in degrees. */
private data class Oklch(val l: Float, val chroma: Float, val hue: Float) {
    companion object {
        fun fromArgb(argb: Int): Oklch {
            val r = srgbToLinear(((argb shr 16) and 0xFF) / 255f)
            val g = srgbToLinear(((argb shr 8) and 0xFF) / 255f)
            val b = srgbToLinear((argb and 0xFF) / 255f)

            val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
            val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
            val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)

            val lightness = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
            val a = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
            val bb = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
            var hue = Math.toDegrees(atan2(bb, a).toDouble()).toFloat()
            if (hue < 0f) hue += 360f
            return Oklch(lightness, sqrt(a * a + bb * bb), hue)
        }
    }

    fun toColor(): Color {
        fun linear(chroma: Float): FloatArray {
            val radians = Math.toRadians(hue.toDouble())
            val a = chroma * cos(radians).toFloat()
            val b = chroma * sin(radians).toFloat()
            val l = this.l + 0.3963377774f * a + 0.2158037573f * b
            val m = this.l - 0.1055613458f * a - 0.0638541728f * b
            val s = this.l - 0.0894841775f * a - 1.2914855480f * b
            val l3 = l * l * l
            val m3 = m * m * m
            val s3 = s * s * s
            return floatArrayOf(
                4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3,
                -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3,
                -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3,
            )
        }

        fun inGamut(rgb: FloatArray) = rgb.all { it in -0.0001f..1.0001f }

        // Out of gamut, give up chroma and nothing else: changing L would
        // break the contrast promise, and changing H would change the record.
        var chroma = this.chroma.coerceAtLeast(0f)
        if (!inGamut(linear(chroma))) {
            var low = 0f
            var high = chroma
            repeat(GAMUT_STEPS) {
                val mid = (low + high) / 2f
                if (inGamut(linear(mid))) low = mid else high = mid
            }
            chroma = low
        }
        val rgb = linear(chroma)
        return Color(
            red = linearToSrgb(rgb[0].coerceIn(0f, 1f)),
            green = linearToSrgb(rgb[1].coerceIn(0f, 1f)),
            blue = linearToSrgb(rgb[2].coerceIn(0f, 1f)),
        )
    }
}

private fun srgbToLinear(channel: Float): Float =
    if (channel <= 0.04045f) channel / 12.92f
    else ((channel + 0.055f) / 1.055f).pow(2.4f)

private fun linearToSrgb(channel: Float): Float =
    if (channel <= 0.0031308f) channel * 12.92f
    else 1.055f * channel.pow(1f / 2.4f) - 0.055f

private fun cbrt(value: Float): Float =
    if (value < 0f) -(-value).pow(1f / 3f) else value.pow(1f / 3f)

/** Pixels below this share are too rare to read as the sleeve's colour. */
private const val MIN_ACCENT_SHARE = 0.02f
/** A near-grey has no hue worth tinting the ink with. */
private const val MIN_ACCENT_CHROMA = 0.04f
private const val MIN_ACCENT_L = 0.10f
private const val MAX_ACCENT_L = 0.95f

private const val FOREGROUND_L = 0.92f
private const val MAX_FOREGROUND_CHROMA = 0.09f

private const val MIN_BACKGROUND_L = 0.28f
internal const val MAX_BACKGROUND_L = 0.46f
private const val MIN_BACKGROUND_CHROMA = 0.03f
private const val MAX_BACKGROUND_CHROMA = 0.16f
private const val BACKGROUND_STOP_COUNT = 6

/** WCAG AA for the artist line; 3:1 for controls and icons. */
private const val MIN_TEXT_CONTRAST = 4.5f
private const val MIN_ICON_CONTRAST = 3f
/** A hair of headroom so 8-bit rounding cannot land a stop just under target. */
private const val CONTRAST_MARGIN = 0.05f
/** Search steps for the brightest admissible L; well below a JND. */
private const val LUMINANCE_STEPS = 14

/** Binary-search steps for the largest in-gamut chroma; far below a JND. */
private const val GAMUT_STEPS = 18
