package app.tempo.music.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Apple Music's signature pink-red. */
val Accent = Color(0xFFFA2D48)
val AccentDark = Color(0xFFFF375F)
val PositiveGreen = Color(0xFF34C759)
val NegativeRed = Color(0xFFFF3B30)

private val DarkColors = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color.White,
    secondary = AccentDark,
    onSecondary = Color.White,
    tertiary = AccentDark,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF8E8E93),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF111113),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF3A3A3C),
    outline = Color(0xFF38383A),
    outlineVariant = Color(0xFF2C2C2E),
    error = Color(0xFFFF453A),
    inverseSurface = Color(0xFFF2F2F7),
    inverseOnSurface = Color.Black,
)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Accent,
    onSecondary = Color.White,
    tertiary = Accent,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF2F2F7),
    onSurfaceVariant = Color(0xFF8A8A8E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F7FA),
    surfaceContainer = Color(0xFFF2F2F7),
    surfaceContainerHigh = Color(0xFFE5E5EA),
    surfaceContainerHighest = Color(0xFFD1D1D6),
    outline = Color(0xFFC6C6C8),
    outlineVariant = Color(0xFFE5E5EA),
    error = Color(0xFFFF3B30),
    inverseSurface = Color(0xFF1C1C1E),
    inverseOnSurface = Color.White,
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun TempoTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

/** Gradients used for generated artwork (charts, genres, empty playlists). */
object Gradients {
    val all: List<List<Color>> = listOf(
        listOf(Color(0xFFFF5E7E), Color(0xFFE8123C)),
        listOf(Color(0xFF8E5CFF), Color(0xFF4A1FD1)),
        listOf(Color(0xFF2FB8FF), Color(0xFF1560D6)),
        listOf(Color(0xFFFFB13D), Color(0xFFF2600C)),
        listOf(Color(0xFF3DDC97), Color(0xFF0B8F6A)),
        listOf(Color(0xFFFF6FD8), Color(0xFFB0167D)),
        listOf(Color(0xFF5B6CFF), Color(0xFF1C2D9E)),
        listOf(Color(0xFFFF8A65), Color(0xFFC62828)),
        listOf(Color(0xFF26C6DA), Color(0xFF00695C)),
        listOf(Color(0xFFFFD54F), Color(0xFFFF8F00)),
        listOf(Color(0xFFB388FF), Color(0xFF6A1B9A)),
        listOf(Color(0xFF90A4AE), Color(0xFF37474F)),
    )

    fun forKey(key: String): List<Color> = all[Math.floorMod(key.hashCode(), all.size)]
}
