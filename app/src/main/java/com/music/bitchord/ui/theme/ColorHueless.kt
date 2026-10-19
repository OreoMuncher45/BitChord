/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx: ui/theme/HeroTheme.kt -> Color.isHueless().
 */

package com.music.bitchord.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

/** saturation floor shared with the glass text-contrast shift (0.08f). */
fun Color.isHueless(): Boolean {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    return hsl[1] < 0.08f
}
