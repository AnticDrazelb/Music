package app.tempo.music.ui.screens.collection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tempo.music.AppContainer
import app.tempo.music.data.model.CollectionRef
import app.tempo.music.data.model.CollectionType
import app.tempo.music.data.model.Song
import app.tempo.music.ui.components.Artwork
import app.tempo.music.ui.components.CircleIconButton
import app.tempo.music.ui.components.DetailScaffold
import app.tempo.music.ui.components.ErrorBox
import app.tempo.music.ui.components.LoadingBox
import app.tempo.music.ui.components.LocalSongMenu
import app.tempo.music.ui.components.PlayShuffleButtons
import app.tempo.music.ui.components.SongRow
import app.tempo.music.ui.components.belowTopBar
import app.tempo.music.ui.components.userMessage
import app.tempo.music.ui.navigation.LocalPlayer
import app.tempo.music.ui.navigation.appViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CollectionState(
    val ref: CollectionRef,
    val songs: List<Song> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val nextPage: Any? = null,
    val loadingMore: Boolean = false,
)

class CollectionViewModel(private val c: AppContainer, initial: CollectionRef) : ViewModel() {
    private val _state = MutableStateFlow(CollectionState(initial))
    val state: StateFlow<CollectionState> = _state

    val isSaved: StateFlow<Boolean> = c.library.isSaved(initial.url)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { c.youtube.collection(_state.value.ref.url) }
                .onSuccess { detail ->
                    val initial = _state.value.ref
                    _state.value = CollectionState(
                        ref = detail.ref.copy(
                            title = detail.ref.title.ifBlank { initial.title },
                            subtitle = detail.ref.subtitle?.takeIf { it.isNotBlank() } ?: initial.subtitle,
                            thumbnailUrl = initial.thumbnailUrl ?: detail.ref.thumbnailUrl,
                            type = if (initial.type != CollectionType.PLAYLIST) initial.type else detail.ref.type,
                        ),
                        songs = detail.songs,
                        loading = false,
                        nextPage = detail.nextPage,
                    )
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.userMessage()) } }
        }
    }

    fun loadMore() {
        val s = _state.value
        val page = s.nextPage ?: return
        if (s.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            runCatching { c.youtube.moreCollectionSongs(s.ref.url, page) }
                .onSuccess { more ->
                    _state.update { st ->
                        st.copy(
                            songs = (st.songs + more.items).distinctBy { it.id },
                            nextPage = more.nextPage,
                            loadingMore = false,
                        )
                    }
                }
                .onFailure { _state.update { it.copy(loadingMore = false, nextPage = null) } }
        }
    }

    fun toggleSave() {
        viewModelScope.launch {
            val ref = _state.value.ref
            if (isSaved.value) c.library.unsave(ref.url) else c.library.save(ref)
            c.playerConnection.notify(if (isSaved.value) "Removed from Library" else "Added to Library")
        }
    }
}

@Composable
fun CollectionScreen(initial: CollectionRef) {
    val vm = appViewModel(key = initial.url) { CollectionViewModel(it, initial) }
    val state by vm.state.collectAsState()
    val saved by vm.isSaved.collectAsState()
    val player = LocalPlayer.current
    val menu = LocalSongMenu.current
    val listState = rememberLazyListState()
    val ref = state.ref
    val isAlbum = ref.type == CollectionType.ALBUM

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= listState.layoutInfo.totalItemsCount - 6) vm.loadMore() }
    }

    DetailScaffold(
        title = ref.title,
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
                Column(Modifier.fillMaxWidth().belowTopBar(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(8.dp))
                    Artwork(
                        ref.thumbnailUrl,
                        Modifier.size(250.dp).shadow(18.dp, RoundedCornerShape(10.dp)),
                        RoundedCornerShape(10.dp),
                        fallbackKey = ref.url,
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        ref.title,
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 28.dp),
                    )
                    ref.subtitle?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 28.dp),
                        )
                    }
                    val count = if (ref.itemCount > 0) ref.itemCount else state.songs.size.toLong()
                    val kind = when (ref.type) {
                        CollectionType.ALBUM -> "Album"
                        CollectionType.MIX -> "Mix"
                        CollectionType.PLAYLIST -> "Playlist"
                    }
                    Text(
                        if (count > 0) "$kind · $count songs" else kind,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    PlayShuffleButtons(
                        onPlay = { player.play(state.songs) },
                        onShuffle = { player.play(state.songs, shuffle = true) },
                        enabled = state.songs.isNotEmpty(),
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
            when {
                state.loading -> item { LoadingBox() }
                state.error != null -> item { ErrorBox(state.error!!, onRetry = vm::load) }
                else -> {
                    itemsIndexed(state.songs, key = { i, s -> "$i-${s.id}" }) { index, song ->
                        SongRow(
                            song = song,
                            onClick = { player.play(state.songs, index) },
                            onMore = { menu.open(song) },
                            trackNumber = if (isAlbum) index + 1 else null,
                        )
                    }
                    if (state.loadingMore) item { LoadingBox() }
                    if (state.songs.isNotEmpty()) {
                        item {
                            Column(Modifier.padding(20.dp)) {
                                Text(
                                    "${state.songs.size} songs",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(16.dp))
                                CircleIconButton(
                                    Icons.Rounded.Radio,
                                    "Start station",
                                    onClick = { player.startRadio(state.songs.first()) },
                                    size = 44.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
