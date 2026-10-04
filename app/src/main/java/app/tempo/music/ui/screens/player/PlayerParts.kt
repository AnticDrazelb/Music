package app.tempo.music.ui.screens.player

import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ArtworkColors(val top: Color, val bottom: Color)

private val DefaultColors = ArtworkColors(Color(0xFF4A2B3A), Color(0xFF1A1016))

/** Extracts a dark, white-text-friendly gradient from the artwork, like Apple Music. */
@Composable
fun rememberArtworkColors(url: String?): State<ArtworkColors> {
    val context = LocalContext.current
    val colors = remember { mutableStateOf(DefaultColors) }
    LaunchedEffect(url) {
        if (url == null) {
            colors.value = DefaultColors
            return@LaunchedEffect
        }
        val extracted = runCatching { extractColors(context, url) }.getOrNull()
        if (extracted != null) colors.value = extracted
    }
    return colors
}

private suspend fun extractColors(context: Context, url: String): ArtworkColors? {
    val request = ImageRequest.Builder(context).data(url).size(160).allowHardware(false).build()
    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
    val bitmap = result.image.toBitmap()
    val palette = withContext(Dispatchers.Default) { Palette.from(bitmap).maximumColorCount(16).generate() }
    val primary = palette.vibrantSwatch ?: palette.dominantSwatch ?: palette.mutedSwatch ?: return null
    val secondary = palette.darkMutedSwatch ?: palette.darkVibrantSwatch ?: palette.mutedSwatch ?: primary
    fun darken(rgb: Int, amount: Float) = Color(ColorUtils.blendARGB(rgb, Color.Black.toArgb(), amount))
    return ArtworkColors(top = darken(primary.rgb, 0.35f), bottom = darken(secondary.rgb, 0.6f))
}

@Composable
fun animatedArtworkColors(url: String?): ArtworkColors {
    val target by rememberArtworkColors(url)
    val top by animateColorAsState(target.top, tween(600), label = "top")
    val bottom by animateColorAsState(target.bottom, tween(600), label = "bottom")
    return ArtworkColors(top, bottom)
}

/**
 * Apple-style thin scrubber: a rounded track that thickens while dragging.
 * [value] is 0..1; [onValueChange] fires continuously, [onValueChangeFinished] on release.
 */
@Composable
fun ThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = Color.White.copy(alpha = 0.9f),
    inactiveColor: Color = Color.White.copy(alpha = 0.25f),
    bufferedValue: Float = 0f,
) {
    var dragging by remember { mutableStateOf(false) }
    var width by remember { mutableFloatStateOf(1f) }
    val trackHeight by animateDpAsState(if (dragging) 10.dp else 6.dp, label = "track")
    Box(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onValueChange((offset.x / size.width).coerceIn(0f, 1f))
                    onValueChangeFinished()
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        width = size.width.toFloat()
                        onValueChange((offset.x / width).coerceIn(0f, 1f))
                    },
                    onDragEnd = {
                        dragging = false
                        onValueChangeFinished()
                    },
                    onDragCancel = {
                        dragging = false
                        onValueChangeFinished()
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        onValueChange((change.position.x / width).coerceIn(0f, 1f))
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(trackHeight)) {
            val radius = CornerRadius(size.height / 2, size.height / 2)
            drawRoundRect(inactiveColor, cornerRadius = radius)
            if (bufferedValue > 0f) {
                drawRoundRect(
                    inactiveColor,
                    size = Size(size.width * bufferedValue.coerceIn(0f, 1f), size.height),
                    cornerRadius = radius,
                )
            }
            drawRoundRect(
                if (dragging) Color.White else activeColor,
                topLeft = Offset.Zero,
                size = Size(size.width * value.coerceIn(0f, 1f), size.height),
                cornerRadius = radius,
            )
        }
    }
}

/** System media volume, polled so hardware button changes are reflected. */
class VolumeController(context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    fun get(): Float = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()

    fun set(fraction: Float) {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (fraction * max).toInt().coerceIn(0, max), 0)
    }
}
