package com.studysync.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Shared Material 3 colour, typography and shape roles.
// Reference: https://developer.android.com/develop/ui/compose/designsystems/material3
private val StudySyncColors = lightColorScheme(
    primary = Color(0xFF2855B8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDEE7FF),
    onPrimaryContainer = Color(0xFF102A56),
    secondary = Color(0xFF006C52),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB5F1D2),
    onSecondaryContainer = Color(0xFF073D2B),
    tertiary = Color(0xFF4C5A7A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE1E8FF),
    onTertiaryContainer = Color(0xFF1C2C4C),
    background = Color(0xFFF5F7FB),
    onBackground = Color(0xFF172238),
    surface = Color.White,
    onSurface = Color(0xFF172238),
    surfaceVariant = Color(0xFFE7EDF5),
    onSurfaceVariant = Color(0xFF44526B),
    outline = Color(0xFF6D7B91),
    outlineVariant = Color(0xFFC8D2E0),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF293348),
    inverseOnSurface = Color(0xFFF2F4FB),
    inversePrimary = Color(0xFFB7C7FF),
    surfaceTint = Color(0xFF2855B8),
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFD8DFEA),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F4FA),
    surfaceContainer = Color(0xFFE9EFF7),
    surfaceContainerHigh = Color(0xFFE2EAF4),
    surfaceContainerHighest = Color(0xFFDCE5F0)
)

private val BaseTypography = Typography()
private val StudySyncTypography = Typography(
    headlineLarge = BaseTypography.headlineLarge.copy(fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = BaseTypography.headlineMedium.copy(fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = BaseTypography.headlineSmall.copy(fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = BaseTypography.titleLarge.copy(fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = BaseTypography.titleMedium.copy(fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 26.sp),
    titleSmall = BaseTypography.titleSmall.copy(fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = BaseTypography.bodyLarge.copy(fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = BaseTypography.bodyMedium.copy(fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = BaseTypography.bodySmall.copy(fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = BaseTypography.labelLarge.copy(fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp)
)

private val StudySyncShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun StudySyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = StudySyncColors, typography = StudySyncTypography, shapes = StudySyncShapes) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground) {
            content()
        }
    }
}

/** Decorative brand mark; the adjacent app name or screen heading supplies its label. */
@Composable
internal fun StudySyncBrandMark(size: Dp = 56.dp) {
    Image(painter = painterResource(R.drawable.ic_studysync), contentDescription = null,
        modifier = Modifier.size(size).clip(RoundedCornerShape(percent = 25)))
}
