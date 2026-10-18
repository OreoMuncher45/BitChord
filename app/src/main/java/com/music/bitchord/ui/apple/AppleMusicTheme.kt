package com.music.bitchord.ui.apple

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.music.bitchord.ui.theme.StatusBarIcons

/**
 * The four greys every Apple surface is drawn from, as a composition local.
 *
 * Not a `ColorScheme`. Material's scheme is a *role* vocabulary — primary,
 * surfaceVariant, outlineVariant — and it forces two decisions Apple's design
 * does not make: what a surface is tinted (nothing here is tinted; Apple's
 * backgrounds are pure white and pure black), and which of a dozen greys a
 * border should be. Carrying the four levels directly is both fewer values and
 * the actual system.
 */
data class ApplePalette(
    val background: Color,
    val backgroundElevated: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val quaternaryLabel: Color,
    val separator: Color,
    val fill: Color,
    val fillSecondary: Color,
    val fillTertiary: Color,
    val accent: Color = AppleColors.Accent,
) {
    val isDark: Boolean get() = background.luminanceIsDark()

    private fun Color.luminanceIsDark(): Boolean =
        (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f
}

val LocalApplePalette = staticCompositionLocalOf {
    ApplePalette(
        AppleColors.LightBackground, AppleColors.LightBackgroundElevated,
        AppleColors.LightLabel, AppleColors.LightSecondaryLabel,
        AppleColors.LightTertiaryLabel, AppleColors.LightQuaternaryLabel,
        AppleColors.LightSeparator, AppleColors.LightFill,
        AppleColors.LightFillSecondary, AppleColors.LightFillTertiary,
    )
}

private val LightPalette = ApplePalette(
    background = AppleColors.LightBackground,
    backgroundElevated = AppleColors.LightBackgroundElevated,
    label = AppleColors.LightLabel,
    secondaryLabel = AppleColors.LightSecondaryLabel,
    tertiaryLabel = AppleColors.LightTertiaryLabel,
    quaternaryLabel = AppleColors.LightQuaternaryLabel,
    separator = AppleColors.LightSeparator,
    fill = AppleColors.LightFill,
    fillSecondary = AppleColors.LightFillSecondary,
    fillTertiary = AppleColors.LightFillTertiary,
)

private val DarkPalette = ApplePalette(
    background = AppleColors.DarkBackground,
    backgroundElevated = AppleColors.DarkBackgroundElevated,
    label = AppleColors.DarkLabel,
    secondaryLabel = AppleColors.DarkSecondaryLabel,
    tertiaryLabel = AppleColors.DarkTertiaryLabel,
    quaternaryLabel = AppleColors.DarkQuaternaryLabel,
    separator = AppleColors.DarkSeparator,
    fill = AppleColors.DarkFill,
    fillSecondary = AppleColors.DarkFillSecondary,
    fillTertiary = AppleColors.DarkFillTertiary,
)

/**
 * Wraps content in Apple's palette and typography.
 *
 * A Material scheme is still provided, because the shared components in
 * `sharedUi` — `SongRow`, `ShelfRow`, the skeletons — read one, and refusing
 * to would mean forking all of them before a single screen existed. It is
 * mapped from the same palette so that where the two vocabularies overlap they
 * agree, and the Apple components read [LocalApplePalette] directly.
 */
@Composable
fun AppleMusicTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    StatusBarIcons(dark = darkTheme)
    CompositionLocalProvider(LocalApplePalette provides palette) {
        MaterialTheme(
            colorScheme = if (darkTheme) darkColorScheme(
                primary = palette.accent,
                onPrimary = Color.White,
                background = palette.background,
                onBackground = palette.label,
                surface = palette.background,
                onSurface = palette.label,
                surfaceVariant = palette.backgroundElevated,
                onSurfaceVariant = palette.secondaryLabel,
                outline = palette.separator,
                outlineVariant = palette.fillTertiary,
            ) else lightColorScheme(
                primary = palette.accent,
                onPrimary = Color.White,
                background = palette.background,
                onBackground = palette.label,
                surface = palette.background,
                onSurface = palette.label,
                surfaceVariant = palette.backgroundElevated,
                onSurfaceVariant = palette.secondaryLabel,
                outline = palette.separator,
                outlineVariant = palette.fillTertiary,
            ),
            typography = androidx.compose.material3.Typography(
                headlineLarge = AppleType.LargeTitle,
                headlineMedium = AppleType.SectionHeader,
                titleLarge = AppleType.CardTitle,
                titleMedium = AppleType.RowTitle,
                bodyLarge = AppleType.Body,
                bodyMedium = AppleType.RowSubtitle,
                labelMedium = AppleType.Caption,
                labelSmall = AppleType.TabLabel,
            ),
            shapes = Shapes(
                extraSmall = AppleShape.Row,
                small = AppleShape.Card,
                medium = AppleShape.HeroCard,
                large = AppleShape.Sheet,
                extraLarge = AppleShape.Capsule,
            ),
            content = content,
        )
    }
}
