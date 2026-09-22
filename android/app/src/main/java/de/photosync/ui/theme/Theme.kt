package de.photosync.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF365F5A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E9E5),
    onPrimaryContainer = Color(0xFF173C38),
    secondary = Color(0xFF66587A),
    secondaryContainer = Color(0xFFEDE1F7),
    background = Color(0xFFF9F9F6),
    surface = Color(0xFFF9F9F6),
    surfaceVariant = Color(0xFFE8EAE5),
    outlineVariant = Color(0xFFD9DDD7),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFB4CCC7),
    onPrimary = Color(0xFF203F3B),
    primaryContainer = Color(0xFF365F5A),
    secondary = Color(0xFFD4C2E7),
    secondaryContainer = Color(0xFF4D405F),
    background = Color(0xFF111412),
    surface = Color(0xFF111412),
    surfaceVariant = Color(0xFF2B302D),
)

private val PhotoSyncTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 23.sp, lineHeight = 29.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
)

private val PhotoSyncShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun PhotoSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) DarkColors else LightColors,
        typography = PhotoSyncTypography,
        shapes = PhotoSyncShapes,
        content = content,
    )
}
