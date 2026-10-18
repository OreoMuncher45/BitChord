package com.music.bitchord.ui.apple

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.R

/**
 * Apple Music's design language, written from the references in
 * `apple-music-ui/` rather than derived from the app's existing theme.
 *
 * The important difference from [com.music.bitchord.ui.theme.BitChordTheme]:
 * this is Apple's *system* palette, not a Material one. Apple expresses
 * hierarchy through **fill** and **label** colours — translucent greys at four
 * levels — where Material uses elevation tints. The values below are the iOS
 * system greys, and they are what makes a screen read as Apple at a glance:
 * the backgrounds are true white and true black, and everything between is a
 * percentage of grey over them.
 *
 * Nothing here is a Material `ColorScheme`. The two vocabularies disagree about
 * what a surface *is*, and mapping one onto the other is what produces the
 * generic look this overhaul exists to replace. [AppleMusicTheme] builds a
 * scheme only so that the shared components still have one to read.
 */
object AppleColors {

    /** Apple Music's own red, and the app's accent since the first release. */
    val Accent = Color(0xFFFA2D48)

    /** The pink used for the currently-playing row and for "live" moments. */
    val AccentPink = Color(0xFFFB4A62)

    // ── Light ───────────────────────────────────────────────────────────────
    val LightBackground = Color(0xFFFFFFFF)
    val LightBackgroundElevated = Color(0xFFF7F7F9)
    val LightLabel = Color(0xFF000000)
    val LightSecondaryLabel = Color(0x993C3C43)
    val LightTertiaryLabel = Color(0x4D3C3C43)
    val LightQuaternaryLabel = Color(0x2E3C3C43)
    val LightSeparator = Color(0x493C3C43)
    val LightFill = Color(0x333C3C43)
    val LightFillSecondary = Color(0x1F3C3C43)
    val LightFillTertiary = Color(0x143C3C43)

    // ── Dark ────────────────────────────────────────────────────────────────
    val DarkBackground = Color(0xFF000000)
    val DarkBackgroundElevated = Color(0xFF1C1C1E)
    val DarkLabel = Color(0xFFFFFFFF)
    val DarkSecondaryLabel = Color(0x99EBEBF5)
    val DarkTertiaryLabel = Color(0x4DEBEBF5)
    val DarkQuaternaryLabel = Color(0x2EEBEBF5)
    val DarkSeparator = Color(0x99545458)
    val DarkFill = Color(0x5C787880)
    val DarkFillSecondary = Color(0x51787880)
    val DarkFillTertiary = Color(0x3D767680)
}

/**
 * The type ramp, in Apple's terms: a **large title** that shrinks into the
 * navigation bar as the page scrolls, section headings a step below it, and
 * body copy at 17pt.
 *
 * The negative tracking is not a style flourish — it is what Apple's display
 * sizes do, and without it a 34pt title sets noticeably wider than the same
 * word in the real app.
 */
object AppleType {

    /** SF Pro Display, already bundled for the legacy shell. */
    val family: FontFamily = FontFamily(
        androidx.compose.ui.text.font.Font(R.font.sf_pro_display_regular, FontWeight.Normal),
        androidx.compose.ui.text.font.Font(R.font.sf_pro_display_medium, FontWeight.Medium),
        androidx.compose.ui.text.font.Font(R.font.sf_pro_display_semibold, FontWeight.SemiBold),
        androidx.compose.ui.text.font.Font(R.font.sf_pro_display_bold, FontWeight.Bold),
        androidx.compose.ui.text.font.Font(R.font.sf_pro_display_heavy, FontWeight.ExtraBold),
    )

    /** The page title at rest: 34pt, extra bold, tight. */
    val LargeTitle = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 41.sp, letterSpacing = (-0.8).sp)

    /** The same title once it has collapsed into the bar. */
    val CollapsedTitle = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.4).sp)

    /** "Top Picks for You" — 22pt bold. */
    val SectionHeader = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.5).sp)

    /** A card's own title, laid over artwork. */
    val CardTitle = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.6).sp)

    val CardSubtitle = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.2).sp)

    /** Row title. */
    val RowTitle = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.4).sp)

    /** Row subtitle — artist, or the collection line under a card. */
    val RowSubtitle = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.2).sp)

    /** Small badges: explicit marks, formats, counts. */
    val Caption = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = (-0.1).sp)

    /** Tab-bar and mini-player labels. */
    val TabLabel = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = (-0.1).sp)

    /** Button and picker text. */
    val Body = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.4).sp)

    val Button = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.4).sp)
}

/** Corner radii, taken from where Apple uses each one rather than one scale. */
object AppleShape {
    /** The tab bar capsule and the mini player: fully round. */
    val Capsule = RoundedCornerShape(percent = 50)

    /** A shelf card. */
    val Card = RoundedCornerShape(12.dp)

    /** The big hero cards. */
    val HeroCard = RoundedCornerShape(16.dp)

    /** Rows sit flush; only the content is inset. */
    val Row = RoundedCornerShape(10.dp)

    /** Sheets and popovers. */
    val Sheet = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
}

/** Spacing. Apple's layouts are built on a 4pt grid with named insets. */
object AppleSpace {
    /** Left and right margin of every page. */
    val Gutter: Dp = 16.dp

    /** Between a shelf heading and its cards. */
    val BelowHeader: Dp = 12.dp

    /** Between shelves. */
    val BetweenShelves: Dp = 28.dp

    /** Inset of a row's text from its leading edge, past the artwork. */
    val RowTextInset: Dp = 12.dp

    /** Gap between the floating bars and the page edge. */
    val BarInset: Dp = 8.dp

    /** Height of the tab bar capsule itself, excluding the safe area. */
    val TabBarHeight: Dp = 49.dp

    /** Height of the mini player capsule. */
    val MiniPlayerHeight: Dp = 56.dp

    /** Gap between the mini player and the tab bar. */
    val BetweenBars: Dp = 8.dp
}
