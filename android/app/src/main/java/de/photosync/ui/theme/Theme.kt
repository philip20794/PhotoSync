package de.photosync.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(primary = Color(0xFF315DA8), secondary = Color(0xFF53647D))
private val DarkColors = darkColorScheme(primary = Color(0xFFB4C5FF), secondary = Color(0xFFBBC7E3))

@Composable
fun PhotoSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
