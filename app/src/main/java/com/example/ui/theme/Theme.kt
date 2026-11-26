package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val BiyeBariColorScheme = darkColorScheme(
    primary = MarigoldGold,
    onPrimary = MaroonNight,
    primaryContainer = BridalCrimson,
    onPrimaryContainer = WarmIvory,
    secondary = CngEmerald,
    onSecondary = MaroonNight,
    secondaryContainer = CngEmeraldDark,
    onSecondaryContainer = WarmIvory,
    tertiary = FairyCyan,
    onTertiary = MaroonNight,
    background = MaroonNight,
    onBackground = WarmIvory,
    surface = MaroonSurface,
    onSurface = WarmIvory,
    surfaceVariant = MaroonCard,
    onSurfaceVariant = WarmMuted,
    error = FairyPink,
    onError = MaroonNight,
    outline = MarigoldGold.copy(alpha = 0.45f)
)

@Composable
fun BouChorTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = BiyeBariColorScheme,
        typography = Typography,
        content = content
    )
}
