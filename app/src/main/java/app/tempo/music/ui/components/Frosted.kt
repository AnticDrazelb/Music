package app.tempo.music.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint

/**
 * Apple Music's bar material: content scrolling behind a bar is blurred and washed with the
 * background colour. Phones older than Android 12 can't blur, so they get a nearly solid bar.
 */
@Composable
@ReadOnlyComposable
fun frostedBarStyle(): HazeStyle {
    val background = MaterialTheme.colorScheme.background
    return HazeStyle(
        backgroundColor = background,
        tint = HazeTint(background.copy(alpha = 0.72f)),
        blurRadius = 28.dp,
        noiseFactor = 0f,
        fallbackTint = HazeTint(background.copy(alpha = 0.97f)),
    )
}
