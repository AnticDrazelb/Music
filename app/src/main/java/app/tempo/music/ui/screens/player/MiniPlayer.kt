package app.tempo.music.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tempo.music.ui.components.Artwork
import app.tempo.music.ui.navigation.LocalPlayer
import kotlinx.coroutines.delay

@Composable
fun MiniPlayer(onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val player = LocalPlayer.current
    val state by player.state.collectAsState()
    val song = state.current ?: return
    val dark = isSystemInDarkTheme()
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(state.isPlaying, state.durationMs, song.id) {
        while (true) {
            progress = if (state.durationMs > 0) player.positionMs.toFloat() / state.durationMs else 0f
            if (!state.isPlaying) break
            delay(500)
        }
    }

    Box(
        modifier
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .shadow(14.dp, RoundedCornerShape(16.dp), ambientColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f))
            .clip(RoundedCornerShape(16.dp))
            .background(if (dark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onExpand)
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dragAmount ->
                    if (dragAmount < -12) {
                        change.consume()
                        onExpand()
                    }
                }
            },
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().height(60.dp).padding(start = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(song.thumbnailUrl, Modifier.size(44.dp), RoundedCornerShape(6.dp), fallbackKey = song.id)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.basicMarquee(),
                    )
                    Text(
                        song.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.isBuffering && state.playWhenReady) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurface)
                    }
                } else {
                    IconButton(onClick = player::togglePlayPause) {
                        Icon(
                            if (state.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (state.playWhenReady) "Pause" else "Play",
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
                IconButton(onClick = player::skipNext, enabled = state.hasNext) {
                    Icon(Icons.Rounded.FastForward, "Next", modifier = Modifier.size(28.dp))
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}
