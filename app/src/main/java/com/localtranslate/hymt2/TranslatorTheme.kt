package com.localtranslate.hymt2

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF245CC4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9F0FF),
    onPrimaryContainer = Color(0xFF183E83),
    secondaryContainer = Color(0xFFE9F0FF),
    onSecondaryContainer = Color(0xFF245CC4),
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF172536),
    surface = Color.White,
    onSurface = Color(0xFF172536),
    surfaceVariant = Color(0xFFEDF1F7),
    onSurfaceVariant = Color(0xFF58677A),
    outline = Color(0xFF8592A5),
    outlineVariant = Color(0xFFDBE2EC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8EB6FF),
    onPrimary = Color(0xFF102953),
    primaryContainer = Color(0xFF243C65),
    onPrimaryContainer = Color(0xFFDBE7FF),
    secondaryContainer = Color(0xFF243C65),
    onSecondaryContainer = Color(0xFFDBE7FF),
    background = Color(0xFF171C24),
    onBackground = Color(0xFFEEF2F8),
    surface = Color(0xFF232B36),
    onSurface = Color(0xFFEEF2F8),
    surfaceVariant = Color(0xFF2D3746),
    onSurfaceVariant = Color(0xFFADBACD),
    outline = Color(0xFF8A98AB),
    outlineVariant = Color(0xFF394454),
)

@Composable
internal fun HyTranslatorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
