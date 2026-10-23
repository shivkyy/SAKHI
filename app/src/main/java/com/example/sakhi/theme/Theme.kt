package com.example.sakhi.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = SakhiPrimary,
    onPrimary = Color.White,
    primaryContainer = SakhiPrimaryVariant,
    onPrimaryContainer = Color.White,
    secondary = SakhiLavenderAccent,
    onSecondary = SakhiTextPrimaryLight,
    error = SakhiEmergency,
    onError = Color.White,
    background = SakhiBackgroundLight,
    onBackground = SakhiTextPrimaryLight,
    surface = SakhiSurfaceLight,
    onSurface = SakhiTextPrimaryLight,
    surfaceVariant = SakhiSurfaceVariantLight,
    onSurfaceVariant = SakhiTextSecondaryLight,
    outline = SakhiCardBorderLight
)

private val DarkColorScheme = darkColorScheme(
    primary = SakhiPrimary,
    onPrimary = Color.White,
    primaryContainer = SakhiPrimaryContainer,
    onPrimaryContainer = SakhiPrimaryLight,
    secondary = SakhiLavenderAccent,
    onSecondary = Color.Black,
    error = SakhiEmergency,
    onError = Color.White,
    background = SakhiBackgroundDark,
    onBackground = SakhiTextPrimaryDark,
    surface = SakhiSurfaceDark,
    onSurface = SakhiTextPrimaryDark,
    surfaceVariant = SakhiSurfaceVariantDark,
    onSurfaceVariant = SakhiTextSecondaryDark,
    outline = SakhiCardBorderDark
)

val EmergencyColorScheme = darkColorScheme(
    primary = SakhiEmergency,
    onPrimary = Color.White,
    primaryContainer = SakhiEmergencyDark,
    onPrimaryContainer = Color.White,
    secondary = SakhiWarning,
    onSecondary = Color.Black,
    error = SakhiEmergency,
    onError = Color.White,
    background = SakhiEmergencyBackground,
    onBackground = Color.White,
    surface = SakhiEmergencySurface,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2E131C),
    onSurfaceVariant = Color(0xFFFCA5A5),
    outline = SakhiEmergencyCardBorder
)

@Composable
fun SAKHITheme(
    darkTheme: Boolean = true, // SAKHI signature is Midnight Indigo by default
    isEmergencyMode: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        isEmergencyMode -> EmergencyColorScheme
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
