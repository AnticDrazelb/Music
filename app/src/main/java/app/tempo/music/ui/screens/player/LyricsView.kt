package app.tempo.music.ui.screens.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tempo.music.data.lyrics.LyricLine
import app.tempo.music.data.lyrics.Lyrics
import app.tempo.music.data.model.Song
import app.tempo.music.playback.PlayerUiState
import app.tempo.music.ui.components.Load
import app.tempo.music.ui.components.userMessage
import app.tempo.music.ui.navigation.LocalAppContainer
import app.tempo.music.ui.navigation.LocalPlayer
import kotlinx.coroutines.delay

/** Lyrics for the current song, synced line-by-line with playback when available. */
@Composable
fun LyricsPanel(song: Song, state: PlayerUiState, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    var attempt by remember(song.id) { mutableIntStateOf(0) }
    var result by remember(song.id) { mutableStateOf<Load<Lyrics>>(Load.Loading) }
    val durationSec = if (song.durationSec > 0) song.durationSec else state.durationMs / 1000

    LaunchedEffect(song.id, attempt) {
        result = Load.Loading
        result = runCatching { container.lyrics.lyrics(song, durationSec) }
            .fold({ Load.Ready(it) }, { Load.Failed(it.userMessage()) })
    }

    Box(modifier.fillMaxWidth()) {
        when (val r = result) {
            is Load.Loading -> CircularProgressIndicator(
                color = White70,
                strokeWidth = 2.5.dp,
                modifier = Modifier.align(Alignment.Center).size(30.dp),
            )
            is Load.Failed -> Message("Couldn't load lyrics", r.message, Modifier.align(Alignment.Center)) {
                TextButton(onClick = { attempt++ }) { Text("Try Again", color = Color.White, fontWeight = FontWeight.SemiBold) }
            }
            is Load.Ready -> when (val lyrics = r.data) {
                is Lyrics.Synced -> SyncedLyrics(lyrics.lines, state)
                is Lyrics.Plain -> PlainLyrics(lyrics.text)
                Lyrics.Instrumental -> Message("Instrumental", "This song has no lyrics.", Modifier.align(Alignment.Center))
                Lyrics.NotFound -> Message(
                    "No lyrics available",
                    "Lyrics for this song haven't been added yet.",
                    Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun SyncedLyrics(lines: List<LyricLine>, state: PlayerUiState) {
    val player = LocalPlayer.current
    val listState = rememberLazyListState()
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var lastUserScroll by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(player.positionMs) }
    val topOffset = with(LocalDensity.current) { -90.dp.roundToPx() }

    LaunchedEffect(state.current?.id, state.isPlaying) {
        while (true) {
            position = player.positionMs
            delay(if (state.isPlaying) 150 else 500)
        }
    }
    LaunchedEffect(dragged) { if (dragged) lastUserScroll = System.currentTimeMillis() }

    // Show a line slightly before it's sung, like Apple Music.
    val current by remember(lines) {
        derivedStateOf { lines.indexOfLast { it.timeMs <= position + 250 } }
    }
    LaunchedEffect(current) {
        val userScrolledRecently = System.currentTimeMillis() - lastUserScroll < 3500
        if (current >= 0 && !dragged && !userScrolledRecently) {
            listState.animateScrollToItem(current, topOffset)
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = 120.dp, bottom = 320.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize().fadingEdges(),
    ) {
        itemsIndexed(lines) { index, line ->
            val active = index == current
            val alpha by animateFloatAsState(
                when {
                    active -> 1f
                    index < current -> 0.32f
                    else -> 0.45f
                },
                label = "lineAlpha",
            )
            val scale by animateFloatAsState(if (active) 1f else 0.97f, label = "lineScale")
            Text(
                line.text.ifBlank { "♪" },
                color = Color.White.copy(alpha = alpha),
                fontSize = 27.sp,
                lineHeight = 33.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                    }
                    .clickable {
                        lastUserScroll = 0L
                        player.seekTo(line.timeMs)
                        if (!state.playWhenReady) player.togglePlayPause()
                    }
                    .padding(vertical = 8.dp),
            )
        }
        item { Credit() }
    }
}

@Composable
private fun PlainLyrics(text: String) {
    Column(
        Modifier
            .fillMaxSize()
            .fadingEdges()
            .verticalScroll(rememberScrollState())
            .padding(top = 16.dp, bottom = 200.dp),
    ) {
        Text(
            text,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 22.sp,
            lineHeight = 31.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(16.dp))
        Text("These lyrics aren't time-synced.", color = White55, fontSize = 13.sp)
        Credit()
    }
}

@Composable
private fun Credit() {
    Text(
        "Lyrics from LRCLIB",
        color = White55,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 24.dp),
    )
}

@Composable
private fun Message(title: String, detail: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Column(modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Lyrics, null, tint = White55, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(detail, color = White70, fontSize = 15.sp, textAlign = TextAlign.Center)
        action()
    }
}

/** Fades content out towards the top and bottom edges. */
private fun Modifier.fadingEdges(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.1f to Color.Black,
                0.85f to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
