package com.example.novelseek_ultra.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Primary = Color(0xFF6D5BFF)
private val PrimaryContainer = Color(0xFFE5E1FF)
private val Secondary = Color(0xFF14B8A6)

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = Color(0xFF1A1240),
    secondary = Secondary,
    background = Color(0xFFF9FAFB),
    surface = Color.White,
    surfaceVariant = Color(0xFFF1F1F4),
    onSurface = Color(0xFF111827),
    onSurfaceVariant = Color(0xFF4B5563),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAFA4FF),
    onPrimary = Color(0xFF1F1462),
    primaryContainer = Color(0xFF3A2E8C),
    onPrimaryContainer = Color(0xFFE5E1FF),
    secondary = Color(0xFF5EEAD4),
    background = Color(0xFF0F1115),
    surface = Color(0xFF161A22),
    surfaceVariant = Color(0xFF222732),
    onSurface = Color(0xFFE5E7EB),
    onSurfaceVariant = Color(0xFFB8BEC9),
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun NovelSeekTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}