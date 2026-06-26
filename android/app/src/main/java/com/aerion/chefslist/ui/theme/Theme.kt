package com.aerion.chefslist.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = Terracotta,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = TerracottaContainer,
    onPrimaryContainer = OnTerracottaContainer,
    secondary = Gold,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = GoldContainer,
    onSecondaryContainer = OnGoldContainer,
    tertiary = DeepGreen,
    onTertiary = androidx.compose.ui.graphics.Color.White,
    tertiaryContainer = GreenContainer,
    onTertiaryContainer = OnGreenContainer,
    background = Cream,
    onBackground = WarmBrown,
    surface = SurfaceLight,
    onSurface = WarmBrown,
    surfaceVariant = SurfaceVariantLight,
    outline = OutlineLight
)

private val DarkColorScheme = darkColorScheme(
    primary = TerracottaDark,
    onPrimary = OnTerracottaDark,
    primaryContainer = TerracottaContainerDark,
    onPrimaryContainer = TerracottaContainer,
    secondary = GoldDark,
    onSecondary = OnGoldDark,
    secondaryContainer = GoldContainerDark,
    onSecondaryContainer = GoldContainer,
    tertiary = GreenDark,
    onTertiary = OnGreenDark,
    tertiaryContainer = GreenContainerDark,
    onTertiaryContainer = GreenContainer,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnBackgroundDark,
    surfaceVariant = SurfaceVariantDark,
    outline = OutlineDark
)

@Composable
fun ChefsListTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ChefsListTypography,
        content = content
    )
}
