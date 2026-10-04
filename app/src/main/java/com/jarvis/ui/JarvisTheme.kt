package com.jarvis.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes

object JarvisColors {
    val Background = Color(0xFF0B0F17)
    val Surface = Color(0xFF131A26)
    val SurfaceContainer = Color(0xFF1C2536)
    val Border = Color(0xFF222E42)
    val Primary = Color(0xFF38BDF8)
    val Secondary = Color(0xFF60A5FA)
    val Tertiary = Color(0xFF0284C7)
    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF94A3B8)
    val TextTertiary = Color(0xFF64748B)
    val Error = Color(0xFFEF4444)
}

object JarvisSpacing {
    val Screen = 16.dp
    val Section = 16.dp
    val List = 12.dp
    val Compact = 8.dp
}

private val JarvisColorScheme = darkColorScheme(
    primary = JarvisColors.Primary,
    onPrimary = JarvisColors.Background,
    primaryContainer = JarvisColors.Tertiary,
    onPrimaryContainer = JarvisColors.TextPrimary,
    secondary = JarvisColors.Secondary,
    onSecondary = JarvisColors.Background,
    secondaryContainer = JarvisColors.SurfaceContainer,
    onSecondaryContainer = JarvisColors.TextPrimary,
    tertiary = JarvisColors.Tertiary,
    onTertiary = JarvisColors.TextPrimary,
    background = JarvisColors.Background,
    onBackground = JarvisColors.TextPrimary,
    surface = JarvisColors.Surface,
    onSurface = JarvisColors.TextPrimary,
    surfaceVariant = JarvisColors.SurfaceContainer,
    onSurfaceVariant = JarvisColors.TextSecondary,
    error = JarvisColors.Error,
    onError = JarvisColors.Background,
    outline = JarvisColors.TextTertiary,
    outlineVariant = JarvisColors.Border,
    surfaceTint = JarvisColors.Primary
)

private val JarvisTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
        lineHeight = 48.sp,
        letterSpacing = (-0.8).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.45).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.24).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp
    ),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
)

private val JarvisShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp)
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JarvisColorScheme,
        typography = JarvisTypography,
        shapes = JarvisShapes,
        content = content
    )
}
