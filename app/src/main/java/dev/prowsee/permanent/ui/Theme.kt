package dev.prowsee.permanent.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val Violet = Color(0xFF7C5CFF)
private val VioletDark = Color(0xFF5B3FE0)
private val Ink = Color(0xFF101114)
private val Paper = Color(0xFFF7F7F9)

private val DarkColors = darkColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A2350),
    onPrimaryContainer = Color(0xFFDCD4FF),
    secondary = Color(0xFF8FD6C0),
    background = Ink,
    onBackground = Color(0xFFE8E8EC),
    surface = Color(0xFF17181C),
    onSurface = Color(0xFFE8E8EC),
    surfaceVariant = Color(0xFF23242A),
    onSurfaceVariant = Color(0xFFB9BAC4),
    outline = Color(0xFF3A3B44),
)

private val LightColors = lightColorScheme(
    primary = VioletDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E1FF),
    onPrimaryContainer = Color(0xFF1E1552),
    secondary = Color(0xFF0F7A63),
    background = Paper,
    onBackground = Color(0xFF17181C),
    surface = Color.White,
    onSurface = Color(0xFF17181C),
    surfaceVariant = Color(0xFFEDEDF2),
    onSurfaceVariant = Color(0xFF565762),
    outline = Color(0xFFC7C8D2),
)

@Composable
fun ProwseeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
        }
    }

    MaterialTheme(colorScheme = colors, content = content)
}
