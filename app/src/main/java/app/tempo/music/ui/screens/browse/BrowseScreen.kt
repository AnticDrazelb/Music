package app.tempo.music.ui.screens.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tempo.music.data.charts.ChartsRepository
import app.tempo.music.data.charts.Countries
import app.tempo.music.data.model.ChartKind
import app.tempo.music.ui.components.GradientArt
import app.tempo.music.ui.components.LargeTitle
import app.tempo.music.ui.components.SectionHeader
import app.tempo.music.ui.navigation.LocalAppContainer
import app.tempo.music.ui.navigation.LocalNavigator
import app.tempo.music.ui.theme.Gradients
import kotlinx.coroutines.launch

private data class ChartTile(
    val kind: ChartKind,
    val country: String,
    val limit: Int,
    val eyebrow: String,
    val label: String,
    val caption: String,
)

@Composable
fun BrowseScreen() {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val country by container.settings.country.collectAsState()
    var pickCountry by remember { mutableStateOf(false) }
    val countryName = Countries.name(country)
    val global = ChartsRepository.GLOBAL

    val tiles = listOf(
        ChartTile(ChartKind.TOP_SONGS, global, 100, "Top 100", "Global", "Top 100: Global"),
        ChartTile(ChartKind.TOP_SONGS, country, 100, "Top 100", countryName, "Top 100: $countryName"),
        ChartTile(ChartKind.TOP_SONGS, global, 40, "Top 40", "Global", "Top 40: Global"),
        ChartTile(ChartKind.TOP_SONGS, country, 40, "Top 40", countryName, "Top 40: $countryName"),
        ChartTile(ChartKind.TOP_ARTISTS, country, 100, "Top Artists", countryName, "Top Artists"),
    )

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 160.dp)) {
        item { LargeTitle("Browse") }

        item {
            SectionHeader("Top Charts", subtitle = "Updated every week") {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { pickCountry = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${Countries.flag(country)} $countryName", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(tiles) { tile ->
                    Column(Modifier.width(160.dp).clickable { navigator.chart(tile.kind, tile.country, tile.limit) }) {
                        GradientArt(
                            title = tile.label,
                            eyebrow = tile.eyebrow,
                            colors = if (tile.country == global) Gradients.all[0] else Gradients.forKey(tile.kind.name + tile.country),
                            titleSize = 24,
                            modifier = Modifier.size(160.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(tile.caption, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        item { SectionHeader("Genres") }
        genreGrid(Genres.genres) { navigator.genre(it.id) }

        item { SectionHeader("Moods & Activities") }
        genreGrid(Genres.moods) { navigator.genre(it.id) }
    }

    if (pickCountry) {
        CountryPicker(
            selected = country,
            onDismiss = { pickCountry = false },
            onPick = { code ->
                pickCountry = false
                container.appScope.launch { container.settings.setCountry(code) }
            },
        )
    }
}

fun LazyListScope.genreGrid(items: List<Genre>, onClick: (Genre) -> Unit) {
    items(items.chunked(2)) { row ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            row.forEach { genre ->
                GradientArt(
                    title = genre.title,
                    colors = genre.colors,
                    titleSize = 18,
                    corner = 10.dp,
                    modifier = Modifier
                        .weight(1f)
                        .height(104.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onClick(genre) },
                )
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
fun CountryPicker(selected: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Text("Charts Country", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        LazyColumn(Modifier.navigationBarsPadding()) {
            items(Countries.sorted()) { code ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            scope.launch { sheetState.hide() }.invokeOnCompletion { onPick(code) }
                        }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(Countries.flag(code), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(14.dp))
                    Text(Countries.name(code), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (code.equals(selected, ignoreCase = true)) {
                        Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
