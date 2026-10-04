package app.tempo.music.ui.screens.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tempo.music.AppContainer
import app.tempo.music.data.model.CollectionRef
import app.tempo.music.data.model.Song
import app.tempo.music.ui.components.CollectionCard
import app.tempo.music.ui.components.DetailScaffold
import app.tempo.music.ui.components.ErrorBox
import app.tempo.music.ui.components.GradientArt
import app.tempo.music.ui.components.Load
import app.tempo.music.ui.components.LoadingBox
import app.tempo.music.ui.components.LocalSongMenu
import app.tempo.music.ui.components.PlayShuffleButtons
import app.tempo.music.ui.components.SectionHeader
import app.tempo.music.ui.components.SongRow
import app.tempo.music.ui.components.userMessage
import app.tempo.music.ui.navigation.LocalNavigator
import app.tempo.music.ui.navigation.LocalPlayer
import app.tempo.music.ui.navigation.appViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class GenreViewModel(private val c: AppContainer, private val genre: Genre) : ViewModel() {
    private val _songs = MutableStateFlow<Load<List<Song>>>(Load.Loading)
    val songs: StateFlow<Load<List<Song>>> = _songs
    private val _playlists = MutableStateFlow<Load<List<CollectionRef>>>(Load.Loading)
    val playlists: StateFlow<Load<List<CollectionRef>>> = _playlists

    init {
        load()
    }

    fun load() {
        _songs.value = Load.Loading
        _playlists.value = Load.Loading
        viewModelScope.launch {
            _songs.value = runCatching { c.youtube.songsFor(genre.songQuery) }
                .fold({ Load.Ready(it) }, { Load.Failed(it.userMessage()) })
        }
        viewModelScope.launch {
            _playlists.value = runCatching { c.youtube.playlistsFor(genre.playlistQuery) }
                .fold({ Load.Ready(it) }, { Load.Failed(it.userMessage()) })
        }
    }
}

@Composable
fun GenreScreen(id: String) {
    val genre = Genres.byId(id) ?: Genres.genres.first()
    val vm = appViewModel(key = id) { GenreViewModel(it, genre) }
    val songs by vm.songs.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val player = LocalPlayer.current
    val menu = LocalSongMenu.current
    val navigator = LocalNavigator.current
    val listState = rememberLazyListState()
    var showAll by remember { mutableStateOf(false) }

    DetailScaffold(title = genre.title, listState = listState) { padding ->
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = padding) {
            item {
                GradientArt(
                    title = genre.title,
                    eyebrow = "Tempo",
                    colors = genre.colors,
                    corner = 0.dp,
                    titleSize = 44,
                    modifier = Modifier.fillMaxWidth().height(280.dp),
                )
            }
            val songList = (songs as? Load.Ready)?.data.orEmpty()
            item {
                Spacer(Modifier.height(16.dp))
                PlayShuffleButtons(
                    onPlay = { player.play(songList) },
                    onShuffle = { player.play(songList, shuffle = true) },
                    enabled = songList.isNotEmpty(),
                )
            }
            item { SectionHeader("Top Songs") }
            when (val s = songs) {
                is Load.Loading -> item { LoadingBox() }
                is Load.Failed -> item { ErrorBox(s.message, onRetry = vm::load) }
                is Load.Ready -> {
                    val visible = if (showAll) s.data else s.data.take(8)
                    itemsIndexed(visible, key = { i, song -> "$i-${song.id}" }) { index, song ->
                        SongRow(song, onClick = { player.play(s.data, index) }, onMore = { menu.open(song) })
                    }
                    if (s.data.size > 8 && !showAll) {
                        item {
                            TextButton(onClick = { showAll = true }, modifier = Modifier.padding(start = 8.dp)) {
                                Text("See All", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
            item { SectionHeader("Playlists") }
            when (val p = playlists) {
                is Load.Loading -> item { LoadingBox() }
                is Load.Failed -> item { ErrorBox(p.message, onRetry = vm::load) }
                is Load.Ready -> items(p.data.chunked(2)) { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        row.forEach { ref ->
                            CollectionCard(ref, onClick = { navigator.collection(ref) }, modifier = Modifier.weight(1f), width = androidx.compose.ui.unit.Dp.Unspecified)
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
