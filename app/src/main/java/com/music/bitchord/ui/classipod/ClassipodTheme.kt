package com.music.bitchord.ui.classipod

import androidx.compose.ui.graphics.Color
import com.music.bitchord.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The Classipod look, values lifted 1-to-1 from adeeteya/Classipod's
 * `AppPalette`: device faceplate gradients per colorway, LCD screen colors,
 * and Helvetica type.
 *
 * Only the presentation lives here — every row is backed by BitChord data
 * (library, Flow, sources), so the whole streaming workflow works under it.
 */
object ClassipodTheme {

    /** One iPod faceplate: frame gradient + wheel colors. */
    data class Colorway(
        val name: String,
        val frameTop: Color,
        val frameBottom: Color,
        val wheelRingTop: Color,
        val wheelRingBottom: Color,
        val wheelCenterTop: Color,
        val wheelCenterBottom: Color,
        val glyph: Color,
        val dark: Boolean,
    )

    // Hexes verbatim from Classipod's AppPalette.
    val COLORWAYS = listOf(
        Colorway(
            name = "Silver",
            frameTop = Color(0xFFF2F2F2), frameBottom = Color(0xFFADADAD),
            wheelRingTop = Color(0xFFE1E1E1), wheelRingBottom = Color(0xFFB1B1B0),
            wheelCenterTop = Color(0xFFE1E1E1), wheelCenterBottom = Color(0xFFB1B1B0),
            glyph = Color(0xFF8793A0), dark = false,
        ),
        Colorway(
            name = "Black",
            frameTop = Color(0xFF939295), frameBottom = Color(0xFF262527),
            wheelRingTop = Color(0xFF676467), wheelRingBottom = Color(0xFF282829),
            wheelCenterTop = Color(0xFF676467), wheelCenterBottom = Color(0xFF282829),
            glyph = Color(0xFFB9B9BD), dark = true,
        ),
        Colorway(
            name = "Red",
            frameTop = Color(0xFFE74954), frameBottom = Color(0xFF7C0015),
            wheelRingTop = Color(0xFFFF5F6D), wheelRingBottom = Color(0xFF9A001F),
            wheelCenterTop = Color(0xFFFF5F6D), wheelCenterBottom = Color(0xFF9A001F),
            glyph = Color(0xFFF7F7F2), dark = true,
        ),
        Colorway(
            name = "Blue",
            frameTop = Color(0xFF9FD3FF), frameBottom = Color(0xFF0F4C9A),
            wheelRingTop = Color(0xFF4D8FE6), wheelRingBottom = Color(0xFF1C4F9A),
            wheelCenterTop = Color(0xFF4D8FE6), wheelCenterBottom = Color(0xFF1C4F9A),
            glyph = Color(0xFFF8FCFF), dark = true,
        ),
        Colorway(
            name = "Purple",
            frameTop = Color(0xFFE6C9FF), frameBottom = Color(0xFF6A1B9A),
            wheelRingTop = Color(0xFFF3DFFF), wheelRingBottom = Color(0xFFB04CCC),
            wheelCenterTop = Color(0xFFF3DFFF), wheelCenterBottom = Color(0xFFB04CCC),
            glyph = Color(0xFFF7F7F2), dark = true,
        ),
        Colorway(
            name = "Green",
            frameTop = Color(0xFFE0FF97), frameBottom = Color(0xFF0E5C2A),
            wheelRingTop = Color(0xFFC8FF6E), wheelRingBottom = Color(0xFF0E5C2A),
            wheelCenterTop = Color(0xFFC8FF6E), wheelCenterBottom = Color(0xFF0E5C2A),
            glyph = Color(0xFFF7F7F2), dark = true,
        ),
        Colorway(
            name = "Gold",
            frameTop = Color(0xFFFFF3C3), frameBottom = Color(0xFFB27700),
            wheelRingTop = Color(0xFFFFE28C), wheelRingBottom = Color(0xFFD08900),
            wheelCenterTop = Color(0xFFFFE28C), wheelCenterBottom = Color(0xFFD08900),
            glyph = Color(0xFFF7F7F2), dark = true,
        ),
        Colorway(
            name = "Orange",
            frameTop = Color(0xFFFFE7B6), frameBottom = Color(0xFFE37400),
            wheelRingTop = Color(0xFFFFE2A1), wheelRingBottom = Color(0xFFF59A28),
            wheelCenterTop = Color(0xFFFFE2A1), wheelCenterBottom = Color(0xFFF59A28),
            glyph = Color(0xFFE06600), dark = false,
        ),
    )

    fun colorway(name: String): Colorway =
        COLORWAYS.firstOrNull { it.name == name } ?: COLORWAYS.first()

    // LCD screen: light face + dark face, exactly as Classipod ships them.
    val SCREEN_LIGHT_BG = Color(0xFFFFFFFF)
    val SCREEN_LIGHT_BAR = Color(0xFFE8E8E8)
    val SCREEN_LIGHT_TEXT = Color(0xFF000000)
    val SCREEN_LIGHT_DIM = Color(0xFF6E6E6E)
    val SCREEN_LIGHT_SELECTED_BG = Color(0xFF3B3B3B)
    val SCREEN_LIGHT_SELECTED_TEXT = Color(0xFFFFFFFF)

    val SCREEN_DARK_BG = Color(0xFF121418)
    val SCREEN_DARK_BAR = Color(0xFF1E2126)
    val SCREEN_DARK_TEXT = Color(0xFFF2F2F2)
    val SCREEN_DARK_DIM = Color(0xFF9AA0A6)
    val SCREEN_DARK_SELECTED_BG = Color(0xFF3A3F47)
    val SCREEN_DARK_SELECTED_TEXT = Color(0xFFFFFFFF)

    /** LCD screen colors resolved against the device theme. */
    data class Lcd(
        val bg: Color,
        val bar: Color,
        val text: Color,
        val dim: Color,
        val selectedBg: Color,
        val selectedText: Color,
        val divider: Color,
    )

    fun lcd(darkTheme: Boolean): Lcd = if (darkTheme) {
        Lcd(
            bg = SCREEN_DARK_BG, bar = SCREEN_DARK_BAR,
            text = SCREEN_DARK_TEXT, dim = SCREEN_DARK_DIM,
            selectedBg = SCREEN_DARK_SELECTED_BG, selectedText = SCREEN_DARK_SELECTED_TEXT,
            divider = Color(0xFF2A2E34),
        )
    } else {
        Lcd(
            bg = SCREEN_LIGHT_BG, bar = SCREEN_LIGHT_BAR,
            text = SCREEN_LIGHT_TEXT, dim = SCREEN_LIGHT_DIM,
            selectedBg = SCREEN_LIGHT_SELECTED_BG, selectedText = SCREEN_LIGHT_SELECTED_TEXT,
            divider = Color(0xFFD9D9D9),
        )
    }

    // Liberation Sans: metric-compatible with the Helvetica Classipod
    // bundles, under the SIL Open Font License — same look, clean rights.
    val helvetica: FontFamily = FontFamily(
        androidx.compose.ui.text.font.Font(R.font.classipod_sans_regular, FontWeight.Normal),
        androidx.compose.ui.text.font.Font(R.font.classipod_sans_bold, FontWeight.Bold),
    )
    val helveticaBold: FontFamily = FontFamily(
        androidx.compose.ui.text.font.Font(R.font.classipod_sans_bold, FontWeight.Bold),
    )

    // Type scale, verbatim from Classipod's IpodTypography.
    val TITLE_SIZE = 18.sp
    val ROW_SIZE = 16.sp
    val SMALL_SIZE = 12.sp
    val METADATA_SIZE = 14.sp
    val TITLE_WEIGHT = FontWeight.Bold

    // Selection: blue gradient w/ hairline borders, white bold text.
    // Verbatim from Classipod's IpodGradients.selection + AppPalette.
    val SELECT_GRAD_TOP = Color(0xFF3EABE3)
    val SELECT_GRAD_MID = Color(0xFF339CDD)
    val SELECT_GRAD_BOTTOM = Color(0xFF1584D3)
    val SELECT_BORDER_TOP = Color(0xFF9ADEF8)
    val SELECT_BORDER_BOTTOM = Color(0xFF154E83)

    // Header status-bar gradient, verbatim.
    val STATUS_GRAD_TOP = Color(0xFFFAFAFA)
    val STATUS_GRAD_MID = Color(0xFFD1D1D1)
    val STATUS_GRAD_BOTTOM = Color(0xFFABABAB)

    // Now Playing progress fill, verbatim (7-stop blue).
    val PROGRESS_FILL = listOf(
        Color(0xFF7E97D7), Color(0xFF86ABFF), Color(0xFF66A3FF),
        Color(0xFF3D89EB), Color(0xFF32A3FF), Color(0xFF5CBBFF),
        Color(0xFF5C9ED0),
    )

    // Rating stars + secondary metadata gray, verbatim.
    val RATING_GRAY = Color(0xFF606060)
}
