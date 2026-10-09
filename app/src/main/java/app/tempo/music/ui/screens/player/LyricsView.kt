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
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import app.tempo.music.data.lyrics.LyricEntry
import app.tempo.music.data.lyrics.LyricWord
import app.tempo.music.data.lyrics.LyricsTiming
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * Lyrics for the current song, synced with playback when available. In karaoke mode (or when the
 * lyrics time each word) the current line fills in word by word as it's sung.
 */
@Composable
fun LyricsPanel(song: Song, state: PlayerUiState, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    var attempt by remember(song.id) { mutableIntStateOf(0) }
    var result by remember(song.id) { mutableStateOf<Load<Lyrics>>(Load.Loading) }
    val durationSec = if (song.durationSec > 0) song.durationSec else state.durationMs / 1000
    val karaoke by container.karaoke.enabled.collectAsState()

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
                is Lyrics.Synced -> SyncedLyrics(lyrics.lines, state, durationSec * 1000, karaoke)
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
private fun SyncedLyrics(lines: List<LyricLine>, state: PlayerUiState, songEndMs: Long, wordByWord: Boolean) {
    val player = LocalPlayer.current
    val listState = rememberLazyListState()
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var lastUserScroll by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(player.positionMs) }
    val topOffset = with(LocalDensity.current) { -90.dp.roundToPx() }
    val entries = remember(lines, songEndMs) {
        // Timing is a nicety: if it ever fails, show the lines as they are rather than crash.
        runCatching { LyricsTiming.entries(lines, songEndMs) }.getOrElse { e ->
            android.util.Log.w("Lyrics", "Word timing failed", e)
            lines.filter { it.text.isNotBlank() }.map { line ->
                val end = lines.getOrNull(lines.indexOf(line) + 1)?.timeMs ?: (line.timeMs + 5_000)
                LyricEntry.Line(line.text, listOf(LyricWord(line.timeMs, end, line.text)), line.timeMs, end, exact = false)
            }
        }
    }
    // Read only while drawing, so following the song every frame redraws without recomposing.
    val positionNow = remember { { position } }

    LaunchedEffect(state.current?.id, state.isPlaying) {
        while (true) {
            if (state.isPlaying) {
                withFrameMillis { position = player.positionMs }
            } else {
                position = player.positionMs
                delay(500)
            }
        }
    }
    LaunchedEffect(dragged) { if (dragged) lastUserScroll = System.currentTimeMillis() }

    // Show a line slightly before it's sung, like Apple Music.
    val current by remember(entries) {
        derivedStateOf { entries.indexOfLast { it.startMs <= position + 250 } }
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
        itemsIndexed(entries) { index, entry ->
            val active = index == current
            when (entry) {
                is LyricEntry.Interlude -> InterludeDots(entry, active, positionNow)
                is LyricEntry.Line -> {
                    val alpha by animateFloatAsState(
                        when {
                            active -> 1f
                            index < current -> 0.32f
                            else -> 0.45f
                        },
                        label = "lineAlpha",
                    )
                    val scale by animateFloatAsState(if (active) 1f else 0.97f, label = "lineScale")
                    LyricLineText(
                        entry,
                        active = active,
                        wordByWord = wordByWord || entry.exact,
                        alpha = alpha,
                        position = positionNow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                            }
                            .clickable {
                                lastUserScroll = 0L
                                player.seekTo(entry.startMs)
                                if (!state.playWhenReady) player.togglePlayPause()
                            }
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }
        item { Credit() }
    }
}

/**
 * One line of lyrics. Word by word, the line being sung fills from dim to white as each word
 * is sung, like Apple Music: a white copy of the text is drawn over the dim one and masked to
 * the part already sung, with a soft edge that sweeps through the current word.
 */
@Composable
internal fun LyricLineText(
    entry: LyricEntry.Line,
    active: Boolean,
    wordByWord: Boolean,
    alpha: Float,
    position: () -> Long,
    modifier: Modifier = Modifier,
) {
    val highlight by animateFloatAsState(if (active && wordByWord) 1f else 0f, label = "highlight")
    if (highlight == 0f) {
        LyricText(entry.text, Color.White.copy(alpha = alpha), modifier)
        return
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val edge = with(LocalDensity.current) { 18.dp.toPx() }
    Box(modifier) {
        LyricText(entry.text, Color.White.copy(alpha = 0.45f), Modifier.fillMaxWidth(), onTextLayout = { layout = it })
        LyricText(
            entry.text,
            Color.White.copy(alpha = highlight),
            Modifier
                .fillMaxWidth()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val l = layout ?: return@drawWithContent
                    // Once the line is over it stays lit while it fades back.
                    val sung = if (active) LyricsTiming.sungCharacters(entry, position()) else entry.text.length.toFloat()
                    drawSungMask(l, sung, edge)
                },
        )
    }
}

@Composable
private fun LyricText(text: String, color: Color, modifier: Modifier = Modifier, onTextLayout: (TextLayoutResult) -> Unit = {}) {
    Text(
        text,
        color = color,
        fontSize = 27.sp,
        lineHeight = 33.sp,
        fontWeight = FontWeight.Bold,
        onTextLayout = onTextLayout,
        modifier = modifier,
    )
}

/** Erases all but the first [sung] characters of the text (fractions included), with a soft edge. */
private fun DrawScope.drawSungMask(layout: TextLayoutResult, sung: Float, edge: Float) {
    val length = layout.layoutInput.text.length
    for (line in 0 until layout.lineCount) {
        val start = layout.getLineStart(line)
        val end = layout.getLineEnd(line)
        val top = layout.getLineTop(line)
        val height = layout.getLineBottom(line) - top
        if (sung >= end) continue
        if (sung <= start || length == 0) {
            drawRect(Color.Black, Offset(0f, top), Size(size.width, height), blendMode = BlendMode.DstOut)
            continue
        }
        val i = floor(sung).toInt().coerceIn(start, length - 1)
        val box = layout.getBoundingBox(i)
        val fraction = sung - i
        if (layout.getBidiRunDirection(i) == ResolvedTextDirection.Rtl) {
            val x = box.right - fraction * box.width
            drawRect(
                Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = x - edge / 2, endX = x + edge / 2),
                Offset(0f, top),
                Size(x + edge / 2, height),
                blendMode = BlendMode.DstOut,
            )
        } else {
            val x = box.left + fraction * box.width
            drawRect(
                Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = x - edge / 2, endX = x + edge / 2),
                Offset(x - edge / 2, top),
                Size(size.width - x + edge / 2, height),
                blendMode = BlendMode.DstOut,
            )
        }
    }
}

/**
 * Apple Music's three dots for an instrumental break: they light up one after another as the
 * break goes on, breathe gently, and shrink away just before the singing starts again.
 */
@Composable
internal fun InterludeDots(entry: LyricEntry.Interlude, active: Boolean, position: () -> Long) {
    val presence by animateFloatAsState(if (active) 1f else 0.6f, label = "interlude")
    Canvas(Modifier.padding(vertical = 14.dp).size(width = 64.dp, height = 18.dp)) {
        val radius = 5.dp.toPx()
        var progress = 0f
        var scale = presence
        if (active) {
            val now = position()
            val length = (entry.endMs - entry.startMs).coerceAtLeast(1)
            progress = ((now - entry.startMs).toFloat() / length).coerceIn(0f, 1f)
            scale *= 1f + 0.12f * sin(2 * PI.toFloat() * (now - entry.startMs) / 2_400f)
            val left = entry.endMs - now
            if (left < 500) scale *= (left / 500f).coerceIn(0f, 1f)
        }
        repeat(3) { i ->
            val lit = (progress * 3 - i).coerceIn(0f, 1f)
            drawCircle(
                Color.White.copy(alpha = 0.35f + 0.65f * lit),
                radius = radius * scale,
                center = Offset(radius + i * (size.width - 2 * radius) / 2, size.height / 2),
            )
        }
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
