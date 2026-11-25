package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val ColorErrorText = androidx.compose.ui.graphics.Color(0xFFFDA4AF)

private val SpaceDeckOledColorScheme = darkColorScheme(
    primary = SignalAmber,
    onPrimary = OnSignalAmber,
    primaryContainer = SignalAmberContainer,
    onPrimaryContainer = OnSignalAmberContainer,
    secondary = TelemetryCyan,
    onSecondary = OnTelemetryCyan,
    secondaryContainer = TelemetryCyanContainer,
    onSecondaryContainer = OnTelemetryCyanContainer,
    tertiary = ZeroGpuViolet,
    onTertiary = OledBlack,
    tertiaryContainer = ZeroGpuVioletContainer,
    onTertiaryContainer = TextPrimary,
    background = OledBlack,
    onBackground = TextPrimary,
    surface = ObsidianSurface,
    onSurface = TextPrimary,
    surfaceVariant = ObsidianContainer,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = OledBlack,
    surfaceContainerLow = ObsidianContainerLow,
    surfaceContainer = ObsidianContainer,
    surfaceContainerHigh = ObsidianContainerHigh,
    surfaceContainerHighest = ObsidianContainerHighest,
    outline = HairlineBorder,
    outlineVariant = SubtleBorder,
    error = StatusCrimson,
    onError = TextPrimary,
    errorContainer = StatusCrimsonContainer,
    onErrorContainer = ColorErrorText
)

val SpaceDeckShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun SpaceDeckTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = SpaceDeckOledColorScheme,
        typography = Typography,
        shapes = SpaceDeckShapes,
        content = content
    )
}
