package app.xujing.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.xujing.core.UiPreferences

private val LightColors = lightColorScheme(
    primary = Color(0xFF5A4BB5), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8E1FF), onPrimaryContainer = Color(0xFF1C1140),
    secondary = Color(0xFF625B71), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8), onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260), tertiaryContainer = Color(0xFFFFD8E4), onTertiaryContainer = Color(0xFF31111D),
    background = Color(0xFFF5F3FB), onBackground = Color(0xFF1B1A22),
    surface = Color(0xFFF5F3FB), onSurface = Color(0xFF1B1A22),
    surfaceVariant = Color(0xFFE7E0EC), onSurfaceVariant = Color(0xFF49454F), outline = Color(0xFF79747E),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFB7A6FF), onPrimary = Color(0xFF241A4D),
    primaryContainer = Color(0xFF382F66), onPrimaryContainer = Color(0xFFE8E1FF),
    secondary = Color(0xFFCCC2DC), onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458), onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8), tertiaryContainer = Color(0xFF633B48), onTertiaryContainer = Color(0xFFFFD8E4),
    background = Color(0xFF131119), onBackground = Color(0xFFE7E2EF),
    surface = Color(0xFF131119), onSurface = Color(0xFFE7E2EF),
    surfaceVariant = Color(0xFF49454F), onSurfaceVariant = Color(0xFFCAC4D0), outline = Color(0xFF938F99),
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
