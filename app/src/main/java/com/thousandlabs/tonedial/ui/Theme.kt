package com.thousandlabs.tonedial.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object TdColors {
    val Background = Color(0xFF0D1017)
    val Surface = Color(0xFF171B24)
    val SurfaceHigh = Color(0xFF212633)
    val Accent = Color(0xFFF2B544)
    val AccentDim = Color(0x33F2B544)
    val Text = Color(0xFFEDEFF4)
    val TextDim = Color(0xFF9AA1B2)
    val Divider = Color(0xFF262B38)
}

private val scheme = darkColorScheme(
    primary = TdColors.Accent,
    onPrimary = Color(0xFF1A1405),
    primaryContainer = TdColors.AccentDim,
    onPrimaryContainer = TdColors.Accent,
    secondary = TdColors.Accent,
    onSecondary = Color(0xFF1A1405),
    secondaryContainer = TdColors.SurfaceHigh,
    onSecondaryContainer = TdColors.Text,
    background = TdColors.Background,
    onBackground = TdColors.Text,
    surface = TdColors.Background,
    onSurface = TdColors.Text,
    surfaceVariant = TdColors.Surface,
    onSurfaceVariant = TdColors.TextDim,
    surfaceContainer = TdColors.Surface,
    surfaceContainerHigh = TdColors.SurfaceHigh,
    surfaceContainerHighest = TdColors.SurfaceHigh,
    surfaceContainerLow = TdColors.Surface,
    outline = TdColors.Divider,
    outlineVariant = TdColors.Divider,
)

private val typography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun ToneDialTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
