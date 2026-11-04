package com.example.careguardcamera.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/** 간격·반경·터치 토큰. */
object CGSpace {
    val S1 = 4.dp; val S2 = 8.dp; val S3 = 12.dp; val S4 = 16.dp
    val S5 = 20.dp; val S6 = 24.dp; val S8 = 32.dp; val S10 = 40.dp
    val Gutter = 20.dp
}

object CGRadius {
    val Chip = 10.dp
    val Card = 12.dp
    val Sheet = 16.dp
    val Hero = 22.dp
    val Rows = 18.dp
    val Pill = 999.dp
}

object CGSize {
    val Touch = 48.dp
    val Emergency = 56.dp
    val Glyph = 20.dp
    val GlyphBox = 48.dp
}

private val LightScheme = lightColorScheme(
    primary = CGColor.Brand,
    onPrimary = Color.White,
    primaryContainer = CGColor.BrandSoft,
    onPrimaryContainer = CGColor.BrandInk,
    secondary = CGColor.Ink2,
    background = CGColor.Surface,
    onBackground = CGColor.Ink,
    surface = CGColor.Paper,
    onSurface = CGColor.Ink,
    surfaceVariant = CGColor.Surface,
    onSurfaceVariant = CGColor.Ink2,
    outline = CGColor.Line,
    outlineVariant = CGColor.Line,
    error = CGColor.Critical,
    onError = Color.White,
    errorContainer = CGColor.CriticalSoft
)

val CGShapes = Shapes(
    extraSmall = RoundedCornerShape(CGRadius.Chip),
    small = RoundedCornerShape(CGRadius.Card),
    medium = RoundedCornerShape(CGRadius.Sheet),
    large = RoundedCornerShape(CGRadius.Hero),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun CGTheme(darkStatusBar: Boolean = false, content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkStatusBar
        }
    }
    MaterialTheme(colorScheme = LightScheme, typography = CGTypography, shapes = CGShapes, content = content)
}
