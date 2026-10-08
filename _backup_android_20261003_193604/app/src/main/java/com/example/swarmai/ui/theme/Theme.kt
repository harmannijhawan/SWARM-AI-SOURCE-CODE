package com.example.swarmai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PrimaryBlue = Color(0xFF0A84FF)
val PrimaryDarkBlue = Color(0xFF0060DF)
val SecondaryOrange = Color(0xFFFF9F0A)
val BackgroundGray = Color(0xFFF5F5F7)
val SurfaceWhite = Color(0xFFFFFFFF)
val OnPrimaryWhite = Color(0xFFFFFFFF)
val OnSurfaceDark = Color(0xFF1C1C1E)
val DividerGray = Color(0xFFC6C6C8)
val SuccessGreen = Color(0xFF34C759)
val ErrorRed = Color(0xFFFF3B30)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = OnPrimaryWhite,
    secondary = SecondaryOrange,
    background = BackgroundGray,
    surface = SurfaceWhite,
    onSurface = OnSurfaceDark,
    error = ErrorRed
)

@Composable
fun SwarmAITheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        content = content
    )
}
