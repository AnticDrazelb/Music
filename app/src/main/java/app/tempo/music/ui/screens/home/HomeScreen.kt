package app.tempo.music.ui.screens.home

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tempo.music.AppContainer
import app.tempo.music.data.charts.ChartsRepository
import app.tempo.music.data.charts.Countries
import app.tempo.music.data.model.ArtistChart
import app.tempo.music.data.model.Chart
import app.tempo.music.data.model.ChartKind
import app.tempo.music.data.model.CollectionRef
import app.tempo.music.data.model.Song
import app.tempo.music.data.mixes.Mix
import app.tempo.music.ui.components.ArtistCircle
import app.tempo.music.ui.components.ChartSongRow
import app.tempo.music.ui.components.CollectionCard
import app.tempo.music.ui.components.ErrorBox
import app.tempo.music.ui.components.GradientArt
import app.tempo.music.ui.components.LargeTitle
import app.tempo.music.ui.components.Load
import app.tempo.music.ui.components.LoadingBox
import app.tempo.music.ui.components.LocalSongMenu
import app.tempo.music.ui.components.SectionHeader
import app.tempo.music.ui.components.SongCard
import app.tempo.music.ui.components.dataOrNull
import app.tempo.music.ui.components.userMessage
import app.tempo.music.ui.navigation.LocalNavigator
import app.tempo.music.ui.navigation.LocalPlayer
import app.tempo.music.ui.navigation.appViewModel
import app.tempo.music.ui.theme.Gradients
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class Shelf(val title: String, val query: String, val items: Load<List<CollectionRef>> = Load.Loading)

data class HomeState(
    val country: String,
    val topGlobal: Load<Chart> = Load.Loading,
    val topLocal: Load<Chart> = Load.Loading,
    val artists: Load<ArtistChart> = Load.Loading,
    val shelves: List<Shelf> = emptyList(),
    val refreshing: Boolean = false,
)

class HomeViewModel(private val c: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(HomeState(country = c.settings.country.value))
    val state: StateFlow<HomeState> = _state

    val recent: StateFlow<List<Song>> = c.library.recentlyPlayed(20)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val liked: StateFlow<List<Song>> = c.library.likedSongs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val mixes: StateFlow<List<Mix>> = c.mixes.mixes

    init {
        load()
        viewModelScope.launch {
            c.settings.country.drop(1).collect {
                _state.value = HomeState(country = it)
                load()
            }
        }
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load()
    }

    private fun <T> loadInto(block: suspend () -> T, set: HomeState.(Load<T>) -> HomeState) =
        viewModelScope.async {
            val result = runCatching { block() }
            _state.update { s ->
                s.set(result.fold({ Load.Ready(it) }, { Load.Failed(it.userMessage()) }))
            }
        }

    private fun load() {
        // Today's mixes; a no-op once they've been made today.
        viewModelScope.launch { runCatching { c.mixes.refresh() } }
        val country = _state.value.country
        val jobs = listOf(
            loadInto({ c.charts.chart(ChartKind.TOP_SONGS, ChartsRepository.GLOBAL) }) { copy(topGlobal = it) },
            loadInto({ c.charts.chart(ChartKind.TOP_SONGS, country) }) { copy(topLocal = it) },
            loadInto({ c.charts.artists(country) }) { copy(artists = it) },
        )
        val shelves = pickShelves()
        _state.update { it.copy(shelves = shelves) }
        val shelfJobs = shelves.mapIndexed { index, shelf ->
            loadInto({ c.youtube.playlistsFor(shelf.query).take(12) }) { result ->
                copy(shelves = this.shelves.toMutableList().also { list ->
                    if (index < list.size) list[index] = list[index].copy(items = result)
                })
            }
        }
        viewModelScope.launch {
            (jobs + shelfJobs).forEach { it.await() }
            _state.update { it.copy(refreshing = false) }
        }
    }

    private fun pickShelves(): List<Shelf> {
        val year = LocalDate.now().year
        val all = listOf(
            Shelf("Today's Hits", "today's top hits $year"),
            Shelf("Chill Vibes", "chill vibes playlist"),
            Shelf("Feel-Good Pop", "feel good pop hits"),
            Shelf("Hip-Hop Essentials", "hip hop essentials"),
            Shelf("Throwback Hits", "throwback hits 2000s"),
            Shelf("Workout Mix", "workout music mix"),
            Shelf("Dance Party", "dance party hits"),
            Shelf("Acoustic Covers", "acoustic covers playlist"),
        )
        val day = LocalDate.now().dayOfYear
        return listOf(all[0]) + (1..2).map { all[1 + (day + it * 3) % (all.size - 1)] }.distinct()
    }
}

@Composable
fun HomeScreen() {
    val vm = appViewModel { HomeViewModel(it) }
    val state by vm.state.collectAsState()
    val recent by vm.recent.collectAsState()
    val liked by vm.liked.collectAsState()
    val mixes by vm.mixes.collectAsState()
    val navigator = LocalNavigator.current
    val player = LocalPlayer.current
    val menu = LocalSongMenu.current
    val countryName = Countries.name(state.country)

    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 160.dp)) {
            item {
                LargeTitle("Home") {
                    IconButton(onClick = navigator::settings) {
                        Icon(Icons.Rounded.Settings, "Settings", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // ---- Top picks
            item { SectionHeader("Top Picks") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        PickCard("Top 100", "Global", "Updated weekly", Gradients.all[0]) {
                            navigator.chart(ChartKind.TOP_SONGS, ChartsRepository.GLOBAL)
                        }
                    }
                    item {
                        PickCard("Top 100", countryName, "Updated weekly", Gradients.forKey(state.country)) {
                            navigator.chart(ChartKind.TOP_SONGS, state.country)
                        }
                    }
                    recent.firstOrNull()?.let { seed ->
                        item {
                            PickCard("Your Station", "Based on ${seed.title}", "Made for you", Gradients.all[1]) {
                                player.startRadio(seed)
                            }
                        }
                    }
                    if (liked.isNotEmpty() && mixes.none { it.id == "favourites" }) {
                        item {
                            PickCard("Favorites Mix", "${liked.size} songs you love", "Made for you", Gradients.all[5]) {
                                player.play(liked, shuffle = true)
                            }
                        }
                    }
                    item {
                        PickCard("Top 40", "Global", "Updated weekly", Gradients.all[3]) {
                            navigator.chart(ChartKind.TOP_SONGS, ChartsRepository.GLOBAL, limit = 40)
                        }
                    }
                }
            }

            // ---- Made for you: daily mixes from your listening
            if (mixes.isNotEmpty()) {
                item { SectionHeader("Made for You") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(mixes, key = { it.id }) { mix ->
                            Column(Modifier.width(170.dp).clickable { navigator.mix(mix.id) }) {
                                MixArt(mix, Modifier.size(170.dp).clip(RoundedCornerShape(12.dp)), titleSize = 26)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    mix.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            // ---- Recently played
            if (recent.isNotEmpty()) {
                item { SectionHeader("Recently Played") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(recent, key = { _, s -> s.id }) { index, song ->
                            SongCard(song, onClick = { player.play(recent, index) }, onLongClick = { menu.open(song) })
                        }
                    }
                }
            }

            chartShelf("Top Songs: $countryName", state.topLocal, onRetry = vm::refresh) {
                navigator.chart(ChartKind.TOP_SONGS, state.country)
            }

            // ---- Top artists
            val artists = state.artists
            if (artists !is Load.Failed) {
                item { SectionHeader("Top Artists: $countryName", onClick = { navigator.chart(ChartKind.TOP_ARTISTS, state.country) }) }
                item {
                    val data = artists.dataOrNull
                    if (data == null) LoadingBox() else {
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            itemsIndexed(data.entries.take(20), key = { i, e -> "$i-${e.artist.url}" }) { _, entry ->
                                ArtistCircle(entry.artist, onClick = { navigator.artist(entry.artist) }, caption = "#${entry.rank}")
                            }
                        }
                    }
                }
            }

            chartShelf("Top Songs: Global", state.topGlobal, onRetry = vm::refresh) {
                navigator.chart(ChartKind.TOP_SONGS, ChartsRepository.GLOBAL)
            }

            // ---- Playlist shelves
            state.shelves.forEach { shelf ->
                val data = shelf.items.dataOrNull
                if (shelf.items is Load.Failed || (data != null && data.isEmpty())) return@forEach
                item(key = "shelf-${shelf.title}") { SectionHeader(shelf.title) }
                item(key = "shelf-row-${shelf.title}") {
                    if (data == null) LoadingBox() else {
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            itemsIndexed(data, key = { i, ref -> "$i-${ref.url}" }) { _, ref ->
                                CollectionCard(ref, onClick = { navigator.collection(ref) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickCard(title: String, subtitle: String, caption: String, colors: List<androidx.compose.ui.graphics.Color>, onClick: () -> Unit) {
    Column(Modifier.width(240.dp)) {
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        GradientArt(
            title = title,
            subtitle = subtitle,
            colors = colors,
            eyebrow = "Tempo",
            corner = 12.dp,
            titleSize = 34,
            modifier = Modifier
                .size(240.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick),
        )
    }
}

private fun LazyListScope.chartShelf(title: String, chart: Load<Chart>, onRetry: () -> Unit, onSeeAll: () -> Unit) {
    item(key = "chart-header-$title") { SectionHeader(title, onClick = onSeeAll) }
    item(key = "chart-$title") {
        when (chart) {
            is Load.Loading -> LoadingBox()
            is Load.Failed -> ErrorBox(chart.message, onRetry = onRetry)
            is Load.Ready -> ChartGrid(chart.data)
        }
    }
}

/** Apple Music style 4-row swipeable grid of chart songs. */
@Composable
private fun ChartGrid(chart: Chart) {
    val player = LocalPlayer.current
    val menu = LocalSongMenu.current
    val columnWidth = (LocalConfiguration.current.screenWidthDp * 0.86f).dp
    val entries = chart.entries.take(20)
    val songs = chart.entries.map { it.song }
    LazyRow(contentPadding = PaddingValues(end = 12.dp)) {
        items(entries.chunked(4)) { column ->
            Column(Modifier.width(columnWidth)) {
                column.forEachIndexed { i, entry ->
                    ChartSongRow(
                        entry = entry,
                        onClick = { player.play(songs, entry.rank - 1) },
                        onMore = { menu.open(entry.song) },
                        showDivider = i < column.lastIndex,
                    )
                }
            }
        }
    }
}
