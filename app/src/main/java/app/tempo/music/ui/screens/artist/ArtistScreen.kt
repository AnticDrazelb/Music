package app.tempo.music.ui.screens.artist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tempo.music.AppContainer
import app.tempo.music.data.model.ArtistDetail
import app.tempo.music.data.model.ArtistRef
import app.tempo.music.ui.components.Artwork
import app.tempo.music.ui.components.CircleIconButton
import app.tempo.music.ui.components.CollectionCard
import app.tempo.music.ui.components.DetailScaffold
import app.tempo.music.ui.components.ErrorBox
import app.tempo.music.ui.components.Load
import app.tempo.music.ui.components.LoadingBox
import app.tempo.music.ui.components.LocalSongMenu
import app.tempo.music.ui.components.PillButton
import app.tempo.music.ui.components.SectionHeader
import app.tempo.music.ui.components.SongRow
import app.tempo.music.ui.components.formatCount
import app.tempo.music.ui.components.userMessage
import app.tempo.music.ui.navigation.LocalNavigator
import app.tempo.music.ui.navigation.LocalPlayer
import app.tempo.music.ui.navigation.appViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ArtistViewModel(private val c: AppContainer, private val initial: ArtistRef) : ViewModel() {
    private val _detail = MutableStateFlow<Load<ArtistDetail>>(Load.Loading)
    val detail: StateFlow<Load<ArtistDetail>> = _detail

    val isSaved: StateFlow<Boolean> = c.library.isSaved(initial.url)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        load()
    }

    fun load() {
        _detail.value = Load.Loading
        viewModelScope.launch {
            _detail.value = runCatching { c.youtube.artist(initial.url) }
                .fold({ Load.Ready(it) }, { Load.Failed(it.userMessage()) })
        }
    }

    fun toggleSave() {
        viewModelScope.launch {
            val ref = (detail.value as? Load.Ready)?.data?.ref ?: initial
            if (isSaved.value) c.library.unsave(ref.url) else c.library.save(ref)
            c.playerConnection.notify(if (isSaved.value) "Removed from Library" else "Added to Library")
        }
    }
}

@Composable
fun ArtistScreen(initial: ArtistRef) {
    val vm = appViewModel(key = initial.url) { ArtistViewModel(it, initial) }
    val detail by vm.detail.collectAsState()
    val saved by vm.isSaved.collectAsState()
    val player = LocalPlayer.current
    val menu = LocalSongMenu.current
    val navigator = LocalNavigator.current
    val listState = rememberLazyListState()
    var showAllTop by remember { mutableStateOf(false) }
    val data = (detail as? Load.Ready)?.data
    val name = data?.ref?.name ?: initial.name

    DetailScaffold(
        title = name,
        listState = listState,
        actions = {
            CircleIconButton(
                if (saved) Icons.Rounded.Check else Icons.Rounded.Add,
                if (saved) "Remove from Library" else "Add to Library",
                onClick = vm::toggleSave,
            )
            Spacer(Modifier.size(12.dp))
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = padding) {
            item {
                Box(Modifier.fillMaxWidth().height(340.dp)) {
                    Artwork(
                        data?.ref?.thumbnailUrl ?: initial.thumbnailUrl,
                        Modifier.fillMaxSize(),
                        RoundedCornerShape(0.dp),
                        fallbackKey = initial.url,
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))),
                    )
                    Row(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(20.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.displaySmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val subs = data?.ref?.subscribers ?: -1
                            if (subs > 0) {
                                Text("${formatCount(subs)} subscribers", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
                            }
                        }
                        val top = data?.topSongs.orEmpty()
                        if (top.isNotEmpty()) {
                            CircleIconButton(
                                Icons.Rounded.PlayArrow,
                                "Play",
                                onClick = { player.play(top) },
                                background = MaterialTheme.colorScheme.primary,
                                tint = Color.White,
                                size = 52.dp,
                            )
                        }
                    }
                }
            }

            when (val d = detail) {
                is Load.Loading -> item { LoadingBox() }
                is Load.Failed -> item { ErrorBox(d.message, onRetry = vm::load) }
                is Load.Ready -> {
                    val a = d.data
                    if (a.topSongs.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                PillButton(Icons.Rounded.Radio, "Station", { player.startRadio(a.topSongs.first()) }, Modifier.weight(1f))
                                PillButton(
                                    if (saved) Icons.Rounded.Check else Icons.Rounded.Add,
                                    if (saved) "Following" else "Follow",
                                    vm::toggleSave,
                                    Modifier.weight(1f),
                                )
                            }
                        }
                        item { SectionHeader("Top Songs") }
                        val visible = if (showAllTop) a.topSongs else a.topSongs.take(5)
                        itemsIndexed(visible, key = { i, s -> "top-$i-${s.id}" }) { index, song ->
                            SongRow(song, onClick = { player.play(a.topSongs, index) }, onMore = { menu.open(song) })
                        }
                        if (a.topSongs.size > 5 && !showAllTop) {
                            item {
                                TextButton(onClick = { showAllTop = true }, modifier = Modifier.padding(start = 8.dp)) {
                                    Text("See All", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                    if (a.albums.isNotEmpty()) {
                        item { SectionHeader("Albums") }
                        item {
                            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                itemsIndexed(a.albums, key = { i, ref -> "album-$i-${ref.url}" }) { _, ref -> CollectionCard(ref, onClick = { navigator.collection(ref) }) }
                            }
                        }
                    }
                    if (a.playlists.isNotEmpty()) {
                        item { SectionHeader("Playlists") }
                        item {
                            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                itemsIndexed(a.playlists, key = { i, ref -> "playlist-$i-${ref.url}" }) { _, ref -> CollectionCard(ref, onClick = { navigator.collection(ref) }) }
                            }
                        }
                    }
                    val about = a.description?.trim()
                    if (!about.isNullOrBlank()) {
                        item { SectionHeader("About") }
                        item {
                            var expanded by remember { mutableStateOf(false) }
                            Text(
                                about,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (expanded) Int.MAX_VALUE else 5,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                            )
                            if (!expanded) {
                                TextButton(onClick = { expanded = true }, modifier = Modifier.padding(start = 8.dp)) {
                                    Text("More", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
