package app.tempo.music.ui.screens.player

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.media3.common.Player
import app.tempo.music.playback.PlayerUiState
import app.tempo.music.playback.QueueEntry
import app.tempo.music.ui.components.Artwork
import app.tempo.music.ui.components.LocalLikedIds
import app.tempo.music.ui.components.LocalSongMenu
import app.tempo.music.ui.components.shareSong
import app.tempo.music.ui.navigation.LocalAppContainer
import app.tempo.music.ui.navigation.LocalNavigator
import app.tempo.music.ui.navigation.LocalPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val White70 = Color.White.copy(alpha = 0.7f)
private val White55 = Color.White.copy(alpha = 0.55f)

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
fun NowPlayingScreen(onCollapse: () -> Unit) {
    val player = LocalPlayer.current
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val menu = LocalSongMenu.current
    val context = LocalContext.current
    val state by player.state.collectAsState()
    val autoplay by container.settings.autoplay.collectAsState()
    val sleepEnd by player.sleepTimerEnd.collectAsState()
    val song = state.current
    val colors = animatedArtworkColors(song?.thumbnailUrl)
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val dismissThreshold = with(LocalDensity.current) { 140.dp.toPx() }

    BackHandler { if (showQueue) showQueue = false else onCollapse() }
    LightSystemBars()

    val dismissDrag = Modifier.pointerInput(Unit) {
        detectVerticalDragGestures(
            onDragEnd = { if (dragOffset > dismissThreshold) onCollapse() else dragOffset = 0f },
            onDragCancel = { dragOffset = 0f },
            onVerticalDrag = { change, amount ->
                change.consume()
                dragOffset = (dragOffset + amount).coerceAtLeast(0f)
            },
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dragOffset.roundToInt()) }
            .clip(RoundedCornerShape(if (dragOffset > 0f) 24.dp else 0.dp))
            .background(Brush.verticalGradient(listOf(colors.top, colors.bottom)))
            .clickable(enabled = false) {},
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 26.dp),
        ) {
            // Grabber
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .then(dismissDrag)
                    .clickable(onClick = onCollapse),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(width = 38.dp, height = 5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.35f)))
            }

            if (song == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.MusicNote, null, tint = White55, modifier = Modifier.size(64.dp))
                        Text("Not Playing", color = White70, style = MaterialTheme.typography.titleLarge)
                    }
                }
                return@Column
            }

            AnimatedContent(
                targetState = showQueue,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                modifier = Modifier.weight(1f),
                label = "queue",
            ) { queueVisible ->
                if (!queueVisible) {
                    val scale by animateFloatAsState(
                        if (state.playWhenReady) 1f else 0.82f,
                        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                        label = "artScale",
                    )
                    Box(Modifier.fillMaxSize().then(dismissDrag), contentAlignment = Alignment.Center) {
                        Artwork(
                            song.thumbnailUrl,
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                }
                                .shadow(if (state.playWhenReady) 30.dp else 12.dp, RoundedCornerShape(12.dp)),
                            RoundedCornerShape(12.dp),
                            fallbackKey = song.id,
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).then(dismissDrag), verticalAlignment = Alignment.CenterVertically) {
                            Artwork(song.thumbnailUrl, Modifier.size(64.dp).shadow(8.dp, RoundedCornerShape(8.dp)), RoundedCornerShape(8.dp), fallbackKey = song.id)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(song.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(song.artist, color = White70, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        QueueList(state, autoplay, Modifier.weight(1f))
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // Title / artist / favourite / more
            if (!showQueue) {
                val liked = song.id in LocalLikedIds.current
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            song.title,
                            color = Color.White,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee(),
                        )
                        Text(
                            song.artist,
                            color = White70,
                            fontSize = 19.sp,
                            maxLines = 1,
                            modifier = Modifier
                                .basicMarquee()
                                .clickable(enabled = song.artistUrl != null) {
                                    onCollapse()
                                    song.artistUrl?.let { navigator.artist(it, song.artist) }
                                },
                        )
                    }
                    RoundGlassButton(if (liked) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favorite") { player.toggleLike(song) }
                    Spacer(Modifier.width(12.dp))
                    RoundGlassButton(Icons.Rounded.MoreHoriz, "More") { menu.open(song) }
                }
                Spacer(Modifier.height(14.dp))
            }

            SeekBar(state)

            // Transport controls
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = player::skipPrevious, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Rounded.FastRewind, "Previous", tint = Color.White, modifier = Modifier.size(46.dp))
                }
                IconButton(onClick = player::togglePlayPause, modifier = Modifier.size(84.dp)) {
                    Icon(
                        if (state.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (state.playWhenReady) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(68.dp),
                    )
                }
                IconButton(onClick = player::skipNext, enabled = state.hasNext, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Rounded.FastForward, "Next", tint = if (state.hasNext) Color.White else White55, modifier = Modifier.size(46.dp))
                }
            }

            VolumeBar()

            // Bottom actions
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SleepTimerButton(active = sleepEnd != null) { player.setSleepTimer(it) }
                IconButton(onClick = { shareSong(context, song) }) {
                    Icon(Icons.Rounded.Share, "Share", tint = White70)
                }
                ToggleGlassButton(Icons.AutoMirrored.Rounded.QueueMusic, "Playing Next", showQueue) { showQueue = !showQueue }
            }
        }
    }
}

@Composable
private fun LightSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousStatus = controller?.isAppearanceLightStatusBars
        val previousNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            previousStatus?.let { controller.isAppearanceLightStatusBars = it }
            previousNav?.let { controller.isAppearanceLightNavigationBars = it }
        }
    }
}

@Composable
private fun SeekBar(state: PlayerUiState) {
    val player = LocalPlayer.current
    var position by remember { mutableFloatStateOf(0f) }
    var buffered by remember { mutableFloatStateOf(0f) }
    var scrubbing by remember { mutableStateOf(false) }
    val duration = state.durationMs

    LaunchedEffect(state.current?.id, state.isPlaying, duration, scrubbing) {
        while (!scrubbing) {
            if (duration > 0) {
                position = player.positionMs.toFloat() / duration
                buffered = player.bufferedPositionMs.toFloat() / duration
            } else {
                position = 0f
            }
            if (!state.isPlaying) break
            delay(250)
        }
    }

    Column {
        ThinSlider(
            value = position,
            bufferedValue = buffered,
            onValueChange = {
                scrubbing = true
                position = it
            },
            onValueChangeFinished = {
                if (duration > 0) player.seekTo((position * duration).toLong())
                scrubbing = false
            },
        )
        Row(Modifier.fillMaxWidth()) {
            val elapsed = (position * duration).toLong()
            Text(formatTime(elapsed), color = White55, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            if (state.isBuffering && state.playWhenReady) {
                Text("Loading…", color = White55, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
            }
            Text(if (duration > 0) "-" + formatTime(duration - elapsed) else "--:--", color = White55, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun VolumeBar() {
    val context = LocalContext.current
    val volume = remember { VolumeController(context) }
    var level by remember { mutableFloatStateOf(volume.get()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(dragging) {
        while (!dragging) {
            level = volume.get()
            delay(600)
        }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Rounded.VolumeDown, null, tint = White55, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        ThinSlider(
            value = level,
            onValueChange = {
                dragging = true
                level = it
                volume.set(it)
            },
            onValueChangeFinished = { dragging = false },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = White55, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun RoundGlassButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.16f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ToggleGlassButton(icon: ImageVector, description: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) Color.White.copy(alpha = 0.9f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (active) Color.Black.copy(alpha = 0.75f) else White70, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun SleepTimerButton(active: Boolean, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToggleGlassButton(Icons.Rounded.Bedtime, "Sleep timer", active) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(15, 30, 45, 60).forEach { minutes ->
                DropdownMenuItem(text = { Text("$minutes minutes") }, onClick = { open = false; onPick(minutes) })
            }
            DropdownMenuItem(text = { Text("End of current song") }, onClick = { open = false; onPick(-1) })
            if (active) {
                DropdownMenuItem(text = { Text("Turn off timer") }, onClick = { open = false; onPick(0) })
            }
        }
    }
}

// ------------------------------------------------------------------ queue

@Composable
private fun QueueList(state: PlayerUiState, autoplay: Boolean, modifier: Modifier = Modifier) {
    val player = LocalPlayer.current
    val container = LocalAppContainer.current
    val upNext = state.upNext
    val userItems = upNext.filter { !it.isAutoplay }
    val autoplayItems = upNext.filter { it.isAutoplay }
    val rowHeightPx = with(LocalDensity.current) { 60.dp.toPx() }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }

    LazyColumn(modifier.fillMaxWidth()) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QueueToggle(Icons.Rounded.Shuffle, "Shuffle", state.shuffle, Modifier.weight(1f)) { player.toggleShuffle() }
                QueueToggle(
                    if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    "Repeat",
                    state.repeatMode != Player.REPEAT_MODE_OFF,
                    Modifier.weight(1f),
                ) { player.cycleRepeat() }
                QueueToggle(Icons.Rounded.AllInclusive, "Autoplay", autoplay, Modifier.weight(1f)) {
                    container.appScope.launch { container.settings.setAutoplay(!autoplay) }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                Text("Playing Next", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (userItems.isNotEmpty()) {
                    Text(
                        "Clear",
                        color = White70,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable { player.clearUpNext() }.padding(6.dp),
                    )
                }
            }
        }
        if (userItems.isEmpty()) {
            item {
                Text(
                    if (autoplay) "Similar songs will play when your queue ends." else "Nothing queued.",
                    color = White55,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        items(userItems, key = { "${it.index}-${it.song.id}" }) { entry ->
            val key = "${entry.index}-${entry.song.id}"
            val isDragging = draggingKey == key
            QueueRow(
                entry = entry,
                modifier = Modifier
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragging) dragY else 0f },
                dragHandle = Modifier.pointerInput(key) {
                    detectDragGestures(
                        onDragStart = {
                            draggingKey = key
                            dragY = 0f
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragY += amount.y
                        },
                        onDragEnd = {
                            val from = userItems.indexOfFirst { "${it.index}-${it.song.id}" == key }
                            val to = (from + (dragY / rowHeightPx).roundToInt()).coerceIn(0, userItems.lastIndex)
                            if (from >= 0 && to != from) player.moveQueueItem(userItems[from].index, userItems[to].index)
                            draggingKey = null
                            dragY = 0f
                        },
                        onDragCancel = {
                            draggingKey = null
                            dragY = 0f
                        },
                    )
                },
            )
        }
        if (autoplayItems.isNotEmpty()) {
            item {
                Column(Modifier.padding(top = 18.dp, bottom = 6.dp)) {
                    Text("Autoplay", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("Similar music based on what you're playing", color = White55, style = MaterialTheme.typography.bodySmall)
                }
            }
            items(autoplayItems, key = { "${it.index}-${it.song.id}" }) { entry ->
                QueueRow(entry, dimmed = true)
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun QueueToggle(icon: ImageVector, label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (active) Color.Black.copy(alpha = 0.8f) else Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun QueueRow(entry: QueueEntry, modifier: Modifier = Modifier, dimmed: Boolean = false, dragHandle: Modifier? = null) {
    val player = LocalPlayer.current
    val dismissState = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        modifier = modifier,
        onDismiss = { value -> if (value == SwipeToDismissBoxValue.EndToStart) player.removeFromQueue(entry) },
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Color(0xFFFF3B30)).padding(end = 16.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { Text("Remove", color = Color.White, fontWeight = FontWeight.SemiBold) }
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(60.dp)
                .clickable { player.playQueueEntry(entry) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(entry.song.thumbnailUrl, Modifier.size(44.dp), RoundedCornerShape(6.dp), fallbackKey = entry.song.id)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.song.title, color = if (dimmed) White70 else Color.White, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(entry.song.artist, color = White55, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (dragHandle != null) {
                Icon(Icons.Rounded.DragHandle, "Reorder", tint = White55, modifier = dragHandle.padding(8.dp).size(24.dp))
            }
        }
    }
}
