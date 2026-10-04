package app.tempo.music.ui.screens.artist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tempo.music.AppContainer
import app.tempo.music.data.model.BrowseRef
import app.tempo.music.data.model.MusicItem
import app.tempo.music.data.model.Song
import app.tempo.music.ui.components.DetailScaffold
import app.tempo.music.ui.components.ErrorBox
import app.tempo.music.ui.components.Load
import app.tempo.music.ui.components.LoadingBox
import app.tempo.music.ui.components.belowTopBar
import app.tempo.music.ui.components.userMessage
import app.tempo.music.ui.navigation.appViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class BrowseItemsViewModel(private val c: AppContainer, private val ref: BrowseRef) : ViewModel() {
    private val _items = MutableStateFlow<Load<List<MusicItem>>>(Load.Loading)
    val items: StateFlow<Load<List<MusicItem>>> = _items

    init {
        load()
    }

    fun load() {
        _items.value = Load.Loading
        viewModelScope.launch {
            _items.value = runCatching { c.youtube.browse(ref) }.fold({ Load.Ready(it) }, { Load.Failed(it.userMessage()) })
        }
    }
}

/** "See All" behind an artist page row: every album, single or video, two to a row. */
@Composable
fun BrowseItemsScreen(ref: BrowseRef) {
    val vm = appViewModel(key = ref.browseId + ref.params) { BrowseItemsViewModel(it, ref) }
    val state by vm.items.collectAsState()
    val listState = rememberLazyListState()
    val cardWidth = ((LocalConfiguration.current.screenWidthDp - 52) / 2).dp

    DetailScaffold(title = ref.title, listState = listState) { padding ->
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = padding) {
            item {
                Text(
                    ref.title,
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.belowTopBar().padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                )
            }
            when (val s = state) {
                is Load.Loading -> item { LoadingBox() }
                is Load.Failed -> item { ErrorBox(s.message, onRetry = vm::load) }
                is Load.Ready -> {
                    val songs = s.data.filterIsInstance<Song>()
                    items(s.data.chunked(2)) { pair ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            pair.forEach { MusicItemCard(it, songs, cardWidth) }
                            if (pair.size == 1) Spacer(Modifier.width(cardWidth))
                        }
                    }
                }
            }
        }
    }
}
