package app.sunosongwriter.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback palette for devices without dynamic colour (Android 11 and below).
private val LightColors = lightColorScheme(
    primary = Color(0xFF6B3FA0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEBDCFF),
    onPrimaryContainer = Color(0xFF250059),
    secondary = Color(0xFF645A70),
    tertiary = Color(0xFF7E525E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD4BBFF),
    onPrimary = Color(0xFF3B1F5C),
    primaryContainer = Color(0xFF532A87),
    onPrimaryContainer = Color(0xFFEBDCFF),
    secondary = Color(0xFFCEC2DB),
    tertiary = Color(0xFFF0B7C5),
)

@Composable
fun SongWriterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
