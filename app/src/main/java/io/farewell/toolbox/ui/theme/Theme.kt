package io.farewell.toolbox.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7DD3FC),
    secondary = Color(0xFFA7F3D0),
    tertiary = Color(0xFFFDE68A)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0369A1),
    secondary = Color(0xFF047857),
    tertiary = Color(0xFFB45309)
)

@Composable
fun FarewellTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
