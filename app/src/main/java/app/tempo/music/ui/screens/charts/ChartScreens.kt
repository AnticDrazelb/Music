package app.tempo.music.ui.screens.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tempo.music.AppContainer
import app.tempo.music.data.charts.ChartsRepository
import app.tempo.music.data.charts.Countries
import app.tempo.music.data.model.ArtistChart
import app.tempo.music.data.model.Chart
import app.tempo.music.data.model.ChartKind
import app.tempo.music.ui.components.ArtistRow
import app.tempo.music.ui.components.ChartSongRow
import app.tempo.music.ui.components.DetailScaffold
import app.tempo.music.ui.components.ErrorBox
import app.tempo.music.ui.components.GradientArt
import app.tempo.music.ui.components.Load
import app.tempo.music.ui.components.LoadingBox
import app.tempo.music.ui.components.LocalSongMenu
import app.tempo.music.ui.components.PlayShuffleButtons
import app.tempo.music.ui.components.belowTopBar
import app.tempo.music.ui.components.userMessage
import app.tempo.music.ui.navigation.LocalNavigator
import app.tempo.music.ui.navigation.LocalPlayer
import app.tempo.music.ui.navigation.appViewModel
import app.tempo.music.ui.theme.Gradients
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ChartViewModel(private val c: AppContainer, private val kind: ChartKind, private val country: String) : ViewModel() {
    private val _chart = MutableStateFlow<Load<Chart>>(Load.Loading)
    val chart: StateFlow<Load<Chart>> = _chart

    init {
        load()
    }

    fun load() {
        _chart.value = Load.Loading
        viewModelScope.launch {
            _chart.value = runCatching { c.charts.chart(kind, country) }
                .fold({ Load.Ready(it) }, { Load.Failed(it.userMessage()) })
        }
    }
}

class TopArtistsViewModel(private val c: AppContainer, private val country: String) : ViewModel() {
    private val _chart = MutableStateFlow<Load<ArtistChart>>(Load.Loading)
    val chart: StateFlow<Load<ArtistChart>> = _chart

    init {
        load()
    }

    fun load() {
        _chart.value = Load.Loading
        viewModelScope.launch {
            _chart.value = runCatching { c.charts.artists(country) }
                .fold({ Load.Ready(it) }, { Load.Failed(it.userMessage()) })
        }
    }
}

@Composable
fun ChartScreen(kindName: String, country: String, initialLimit: Int) {
    val kind = runCatching { ChartKind.valueOf(kindName) }.getOrDefault(ChartKind.TOP_SONGS)
    val vm = appViewModel(key = "$kindName-$country") { ChartViewModel(it, kind, country) }
    val chart by vm.chart.collectAsState()
    val player = LocalPlayer.current
    val menu = LocalSongMenu.current
    val listState = rememberLazyListState()
    var limit by rememberSaveable { mutableIntStateOf(initialLimit) }
    val showLimitToggle = kind == ChartKind.TOP_SONGS || kind == ChartKind.TOP_VIDEOS
    val effectiveLimit = if (showLimitToggle) limit else 100
    val place = if (country == ChartsRepository.GLOBAL) "Global" else Countries.name(country)
    val title = when (kind) {
        ChartKind.TOP_SONGS -> "Top $effectiveLimit: $place"
        ChartKind.TOP_VIDEOS -> "Top Music Videos: $place"
        ChartKind.TRENDING -> "Trending: $place"
        ChartKind.TOP_ARTISTS -> "Top Artists: $place"
    }
    val colors = if (country == ChartsRepository.GLOBAL) Gradients.all[0] else Gradients.forKey(kind.name + country)

    DetailScaffold(title = title, listState = listState) { padding ->
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = padding) {
            item {
                Column(Modifier.fillMaxWidth().belowTopBar(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(8.dp))
                    GradientArt(
                        title = place,
                        eyebrow = when (kind) {
                            ChartKind.TOP_SONGS -> "Top $effectiveLimit"
                            ChartKind.TOP_VIDEOS -> "Top Videos"
                            ChartKind.TRENDING -> "Trending"
                            ChartKind.TOP_ARTISTS -> "Top Artists"
                        },
                        colors = colors,
                        titleSize = 30,
                        corner = 10.dp,
                        modifier = Modifier.size(230.dp).shadow(16.dp, RoundedCornerShape(10.dp)),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                    val period = (chart as? Load.Ready)?.data?.periodLabel
                    Text(
                        period ?: if (kind == ChartKind.TRENDING) "Updated daily" else "Updated weekly",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (showLimitToggle) {
                        Spacer(Modifier.height(14.dp))
                        SegmentedControl(
                            options = listOf("Top 40", "Top 100"),
                            selected = if (limit == 40) 0 else 1,
                            onSelect = { limit = if (it == 0) 40 else 100 },
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
            when (val c = chart) {
                is Load.Loading -> item { LoadingBox() }
                is Load.Failed -> item { ErrorBox(c.message, onRetry = vm::load) }
                is Load.Ready -> {
                    val entries = c.data.entries.take(effectiveLimit)
                    val songs = entries.map { it.song }
                    item {
                        PlayShuffleButtons(onPlay = { player.play(songs) }, onShuffle = { player.play(songs, shuffle = true) })
                        Spacer(Modifier.height(12.dp))
                    }
                    items(entries, key = { it.rank.toString() + it.song.id }) { entry ->
                        ChartSongRow(
                            entry = entry,
                            onClick = { player.play(songs, songs.indexOf(entry.song).coerceAtLeast(0)) },
                            onMore = { menu.open(entry.song) },
                        )
                    }
                    item {
                        Text(
                            "${entries.size} songs · Source: YouTube Charts",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TopArtistsScreen(country: String) {
    val vm = appViewModel(key = "artists-$country") { TopArtistsViewModel(it, country) }
    val chart by vm.chart.collectAsState()
    val navigator = LocalNavigator.current
    val listState = rememberLazyListState()
    val place = if (country == ChartsRepository.GLOBAL) "Global" else Countries.name(country)

    DetailScaffold(title = "Top Artists: $place", listState = listState) { padding ->
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = padding) {
            item {
                Column(Modifier.belowTopBar().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text("Top Artists", style = MaterialTheme.typography.displaySmall)
                    Text(
                        "$place · ${(chart as? Load.Ready)?.data?.periodLabel ?: "Updated weekly"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when (val c = chart) {
                is Load.Loading -> item { LoadingBox() }
                is Load.Failed -> item { ErrorBox(c.message, onRetry = vm::load) }
                is Load.Ready -> items(c.data.entries, key = { it.artist.url }) { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${entry.rank}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(start = 12.dp).width(32.dp),
                        )
                        ArtistRow(entry.artist, onClick = { navigator.artist(entry.artist) }, modifier = Modifier.weight(1f), subtitle = "Artist")
                    }
                }
            }
        }
    }
}

@Composable
fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    Row(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = index == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (!isSelected) Color.Transparent else if (dark) MaterialTheme.colorScheme.surfaceContainerHighest else Color.White)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 22.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
