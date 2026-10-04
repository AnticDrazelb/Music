package app.tempo.music.ui.screens.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tempo.music.AppContainer
import app.tempo.music.data.model.ArtistRef
import app.tempo.music.data.model.CollectionRef
import app.tempo.music.data.model.MusicItem
import app.tempo.music.data.model.SearchFilter
import app.tempo.music.data.model.Song
import app.tempo.music.ui.components.ArtistRow
import app.tempo.music.ui.components.CollectionRow
import app.tempo.music.ui.components.EmptyState
import app.tempo.music.ui.components.ErrorBox
import app.tempo.music.ui.components.LoadingBox
import app.tempo.music.ui.components.LocalSongMenu
import app.tempo.music.ui.components.SectionHeader
import app.tempo.music.ui.components.SongRow
import app.tempo.music.ui.components.formatDuration
import app.tempo.music.ui.components.userMessage
import app.tempo.music.ui.navigation.LocalNavigator
import app.tempo.music.ui.navigation.LocalPlayer
import app.tempo.music.ui.navigation.Tab
import app.tempo.music.ui.navigation.appViewModel
import app.tempo.music.ui.screens.browse.Genres
import app.tempo.music.ui.screens.browse.genreGrid
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchResults(
    val items: List<MusicItem> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val nextPage: Any? = null,
    val loadingMore: Boolean = false,
)

@OptIn(FlowPreview::class)
class SearchViewModel(private val c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val submitted = MutableStateFlow<String?>(null)
    val filter = MutableStateFlow(SearchFilter.SONGS)

    private val _results = MutableStateFlow<Map<SearchFilter, SearchResults>>(emptyMap())
    val results: StateFlow<Map<SearchFilter, SearchResults>> = _results

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions

    val recent: StateFlow<List<String>> = c.library.recentSearches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            query.debounce(180).map { it.trim() }.distinctUntilChanged().collect { q ->
                _suggestions.value = if (q.isEmpty()) emptyList()
                else runCatching { c.youtube.suggestions(q) }.getOrDefault(_suggestions.value)
            }
        }
    }

    fun submit(text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        query.value = q
        submitted.value = q
        _results.value = emptyMap()
        viewModelScope.launch { c.library.addSearch(q) }
        load(filter.value)
    }

    fun selectFilter(f: SearchFilter) {
        filter.value = f
        if (submitted.value != null && _results.value[f] == null) load(f)
    }

    fun retry() = load(filter.value)

    private fun load(f: SearchFilter) {
        val q = submitted.value ?: return
        _results.update { it + (f to SearchResults(loading = true)) }
        viewModelScope.launch {
            runCatching { c.youtube.search(q, f) }
                .onSuccess { page ->
                    if (submitted.value == q) {
                        _results.update { it + (f to SearchResults(page.items, loading = false, nextPage = page.nextPage)) }
                    }
                }
                .onFailure { e ->
                    if (submitted.value == q) {
                        _results.update { it + (f to SearchResults(loading = false, error = e.userMessage())) }
                    }
                }
        }
    }

    fun loadMore() {
        val q = submitted.value ?: return
        val f = filter.value
        val current = _results.value[f] ?: return
        val page = current.nextPage ?: return
        if (current.loadingMore || current.loading) return
        _results.update { it + (f to current.copy(loadingMore = true)) }
        viewModelScope.launch {
            runCatching { c.youtube.search(q, f, page) }
                .onSuccess { more ->
                    _results.update { map ->
                        val r = map[f] ?: return@update map
                        map + (f to r.copy(items = (r.items + more.items).distinctBy { it.key }, nextPage = more.nextPage, loadingMore = false))
                    }
                }
                .onFailure { _results.update { map -> map + (f to (map[f] ?: current).copy(loadingMore = false, nextPage = null)) } }
        }
    }

    fun clear() {
        query.value = ""
        submitted.value = null
        _results.value = emptyMap()
    }

    fun deleteRecent(q: String) = viewModelScope.launch { c.library.deleteSearch(q) }
    fun clearRecent() = viewModelScope.launch { c.library.clearSearches() }
}

@Composable
fun SearchScreen() {
    val vm = appViewModel { SearchViewModel(it) }
    val query by vm.query.collectAsState()
    val submitted by vm.submitted.collectAsState()
    val filter by vm.filter.collectAsState()
    val results by vm.results.collectAsState()
    val suggestions by vm.suggestions.collectAsState()
    val recent by vm.recent.collectAsState()
    val navigator = LocalNavigator.current
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    val typing = focused && query.trim() != submitted

    Column(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = !focused && submitted == null) {
            Text(
                "Search",
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.statusBarsPadding().padding(start = 20.dp, top = 12.dp, bottom = 6.dp),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (focused || submitted != null) Modifier.statusBarsPadding() else Modifier)
                .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text("Artists, Songs, Albums and More", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { vm.query.value = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            vm.submit(query)
                            focusManager.clearFocus()
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { focused = it.isFocused },
                    )
                }
                if (query.isNotEmpty()) {
                    Icon(
                        Icons.Rounded.Cancel,
                        "Clear",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(50))
                            .clickable {
                                vm.query.value = ""
                                focusRequester.requestFocus()
                            },
                    )
                }
            }
            if (focused || submitted != null) {
                TextButton(onClick = {
                    vm.clear()
                    focusManager.clearFocus()
                    navigator.showTab(Tab.HOME)
                }) { Text("Cancel", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge) }
            } else {
                Spacer(Modifier.width(8.dp))
            }
        }

        when {
            typing -> SuggestionList(
                query = query,
                suggestions = suggestions,
                recent = recent,
                onPick = {
                    vm.submit(it)
                    focusManager.clearFocus()
                },
                onFill = { vm.query.value = "$it " },
                onDeleteRecent = { vm.deleteRecent(it) },
            )
            submitted != null -> ResultsList(
                filter = filter,
                results = results[filter],
                onFilter = vm::selectFilter,
                onRetry = vm::retry,
                onLoadMore = vm::loadMore,
            )
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 160.dp)) {
                if (recent.isNotEmpty()) {
                    item {
                        SectionHeader("Recently Searched") {
                            TextButton(onClick = { vm.clearRecent() }) { Text("Clear", color = MaterialTheme.colorScheme.primary) }
                        }
                    }
                    items(recent.take(6)) { q ->
                        SuggestionRow(Icons.Rounded.History, q, onClick = { vm.submit(q) })
                    }
                }
                item { SectionHeader("Browse Categories") }
                genreGrid(Genres.genres + Genres.moods) { navigator.genre(it.id) }
            }
        }
    }
}

@Composable
private fun SuggestionList(
    query: String,
    suggestions: List<String>,
    recent: List<String>,
    onPick: (String) -> Unit,
    onFill: (String) -> Unit,
    onDeleteRecent: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 160.dp)) {
        if (query.isBlank()) {
            items(recent) { q ->
                SuggestionRow(Icons.Rounded.History, q, onClick = { onPick(q) }, onFill = { onDeleteRecent(q) }, fillIcon = Icons.Rounded.Cancel)
            }
        } else {
            item { SuggestionRow(Icons.Rounded.Search, query, onClick = { onPick(query) }, bold = true) }
            items(suggestions.filter { !it.equals(query, ignoreCase = true) }) { s ->
                SuggestionRow(Icons.AutoMirrored.Rounded.TrendingUp, s, onClick = { onPick(s) }, onFill = { onFill(s) })
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
    onFill: (() -> Unit)? = null,
    fillIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Rounded.NorthWest,
    bold: Boolean = false,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 20.dp, end = 4.dp)
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onFill != null) {
                IconButton(onClick = onFill) {
                    Icon(fillIcon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 54.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ResultsList(
    filter: SearchFilter,
    results: SearchResults?,
    onFilter: (SearchFilter) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
) {
    val player = LocalPlayer.current
    val menu = LocalSongMenu.current
    val navigator = LocalNavigator.current
    val listState = rememberLazyListState()

    LaunchedEffect(listState, filter) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= listState.layoutInfo.totalItemsCount - 5) onLoadMore() }
    }

    Column(Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 6.dp),
        ) {
            items(SearchFilter.entries) { f ->
                val selected = f == filter
                Text(
                    f.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onFilter(f) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 160.dp)) {
            when {
                results == null || results.loading -> item { LoadingBox() }
                results.error != null -> item { ErrorBox(results.error, onRetry = onRetry) }
                results.items.isEmpty() -> item {
                    EmptyState(Icons.Rounded.SearchOff, "No Results", "Try a different search or another category.")
                }
                else -> {
                    itemsIndexed(results.items, key = { _, item -> item.key }) { _, item ->
                        when (item) {
                            is Song -> SongRow(
                                song = item,
                                onClick = { player.playSong(item) },
                                onMore = { menu.open(item) },
                                subtitle = listOfNotNull(
                                    item.artist,
                                    formatDuration(item.durationSec).takeIf { it.isNotEmpty() },
                                ).joinToString(" · "),
                            )
                            is CollectionRef -> CollectionRow(item, onClick = { navigator.collection(item) })
                            is ArtistRef -> ArtistRow(item, onClick = { navigator.artist(item) })
                        }
                    }
                    if (results.loadingMore) item { LoadingBox() }
                }
            }
        }
    }
}
