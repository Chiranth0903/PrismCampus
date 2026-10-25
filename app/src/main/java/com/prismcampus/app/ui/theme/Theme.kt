package com.prismcampus.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = CyberEmerald,
    onPrimary = PrismBackground,
    primaryContainer = CyberEmeraldDark,
    onPrimaryContainer = TextPrimary,
    secondary = ElectricBlue,
    onSecondary = PrismBackground,
    secondaryContainer = ElectricBlueDark,
    onSecondaryContainer = TextPrimary,
    tertiary = VioletAccent,
    background = PrismBackground,
    onBackground = TextPrimary,
    surface = PrismSurface,
    onSurface = TextPrimary,
    surfaceVariant = PrismSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = PrismBorder,
    error = CrimsonAlert
)

@Composable
fun PrismCampusTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = PrismBackground.toArgb()
            window.navigationBarColor = PrismBackground.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
