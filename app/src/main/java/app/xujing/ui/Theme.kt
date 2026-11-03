package app.xujing.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.xujing.core.UiPreferences

private val LightColors = lightColorScheme(
    primary = Color(0xFF536744), secondary = Color(0xFF62644F),
    tertiary = Color(0xFF3B6668), background = Color(0xFFF9FAF3), surface = Color(0xFFF9FAF3),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFB7CF9F), secondary = Color(0xFFC8C9AF),
    tertiary = Color(0xFFA3CED0), background = Color(0xFF12150F), surface = Color(0xFF12150F),
)

@Composable
internal fun XujingTheme(preferences: UiPreferences, content: @Composable () -> Unit) {
    val dark = when (preferences.darkMode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val context = LocalContext.current
    val colors = if (preferences.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
