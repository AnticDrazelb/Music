@file:OptIn(UnstableApi::class)

package app.tempo.music.ui.screens.settings

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.media3.common.util.UnstableApi
import app.tempo.music.BuildConfig
import app.tempo.music.data.SettingsRepository
import app.tempo.music.data.charts.Countries
import app.tempo.music.data.download.DownloadState
import app.tempo.music.ui.components.formatBytes
import app.tempo.music.ui.components.songsLabel
import app.tempo.music.ui.navigation.LibrarySection
import app.tempo.music.ui.navigation.LocalNavigator
import app.tempo.music.ui.components.DetailScaffold
import app.tempo.music.ui.components.belowTopBar
import app.tempo.music.ui.navigation.LocalAppContainer
import app.tempo.music.ui.navigation.LocalPlayer
import app.tempo.music.ui.screens.browse.CountryPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun SettingsScreen() {
    val container = LocalAppContainer.current
    val player = LocalPlayer.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val autoplay by container.settings.autoplay.collectAsState()
    val highQuality by container.settings.highQuality.collectAsState()
    val dataSaver by container.settings.dataSaverOnCellular.collectAsState()
    val crossfade by container.settings.crossfade.collectAsState()
    val crossfadeSeconds by container.settings.crossfadeSeconds.collectAsState()
    val country by container.settings.country.collectAsState()
    var pickCountry by remember { mutableStateOf(false) }
    var confirmRemoveDownloads by remember { mutableStateOf(false) }
    val downloads by container.downloads.items.collectAsState(initial = emptyList())
    val downloadedBytes by container.downloads.totalBytes.collectAsState(initial = 0L)
    val navigator = LocalNavigator.current

    DetailScaffold(title = "Settings", listState = listState) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow),
            state = listState,
            contentPadding = padding,
        ) {
            item {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.belowTopBar().padding(start = 20.dp, bottom = 8.dp),
                )
            }
            item {
                Group(
                    "Playback",
                    "Crossfade blends the end of each song into the next. Autoplay keeps the music going " +
                        "with similar songs when your queue ends.",
                ) {
                    SwitchRow("Crossfade", crossfade) { v -> scope.launch { container.settings.setCrossfade(v) } }
                    if (crossfade) {
                        SecondsSliderRow(crossfadeSeconds, SettingsRepository.CROSSFADE_RANGE) { v ->
                            scope.launch { container.settings.setCrossfadeSeconds(v) }
                        }
                    }
                    SwitchRow("Autoplay", autoplay) { v -> scope.launch { container.settings.setAutoplay(v) } }
                    SwitchRow("High Quality Audio", highQuality) { v -> scope.launch { container.settings.setHighQuality(v) } }
                    SwitchRow("Data Saver on Mobile Data", dataSaver, last = true) { v ->
                        scope.launch { container.settings.setDataSaverOnCellular(v) }
                    }
                }
            }
            item {
                Group("Charts & Content", "Used for Top Charts, trending music and search results.") {
                    ValueRow("Country", "${Countries.flag(country)} ${Countries.name(country)}", last = true) { pickCountry = true }
                }
            }
            item {
                Group("Storage", "Downloaded songs play without an internet connection.") {
                    val doneCount = downloads.count { it.status.state == DownloadState.DONE }
                    ValueRow("Downloads", "${songsLabel(doneCount)} · ${formatBytes(downloadedBytes)}") {
                        navigator.library(LibrarySection.DOWNLOADS)
                    }
                    if (downloads.isNotEmpty()) {
                        ActionRow("Remove All Downloads") { confirmRemoveDownloads = true }
                    }
                    ActionRow("Clear Listening History") {
                        scope.launch {
                            container.library.clearHistory()
                            player.notify("Listening history cleared")
                        }
                    }
                    ActionRow("Clear Search History") {
                        scope.launch {
                            container.library.clearSearches()
                            player.notify("Search history cleared")
                        }
                    }
                    ActionRow("Clear Audio Cache", last = true) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val cache = container.mediaCache
                                cache.keys.toList().forEach { runCatching { cache.removeResource(it) } }
                            }
                            player.notify("Audio cache cleared")
                        }
                    }
                }
            }
            item {
                Group(
                    "About",
                    "Tempo streams music from YouTube using the open-source NewPipe Extractor (GPLv3). " +
                        "Charts come from YouTube Charts. Not affiliated with Apple, Google or YouTube.",
                ) {
                    ValueRow("Version", BuildConfig.VERSION_NAME, last = true, onClick = null)
                }
            }
        }
    }

    if (confirmRemoveDownloads) {
        AlertDialog(
            onDismissRequest = { confirmRemoveDownloads = false },
            title = { Text("Remove all downloads?") },
            text = { Text("Songs stay in your library and playlists, but won't play offline.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveDownloads = false
                    container.downloads.removeAll()
                    player.notify("Downloads removed")
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveDownloads = false }) { Text("Cancel", color = MaterialTheme.colorScheme.primary) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
    }

    if (pickCountry) {
        CountryPicker(
            selected = country,
            onDismiss = { pickCountry = false },
            onPick = { code ->
                pickCountry = false
                scope.launch { container.settings.setCountry(code) }
            },
        )
    }
}

@Composable
private fun Group(title: String, footer: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 18.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 36.dp, bottom = 6.dp),
        )
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) { content() }
        if (footer != null) {
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 36.dp, end = 36.dp, top = 6.dp),
            )
        }
    }
}

@Composable
private fun RowBase(last: Boolean, onClick: (() -> Unit)?, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
    if (!last) HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, last: Boolean = false, onChange: (Boolean) -> Unit) {
    RowBase(last, onClick = { onChange(!checked) }) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Color(0xFF34C759),
                checkedThumbColor = Color.White,
                checkedBorderColor = Color.Transparent,
                uncheckedBorderColor = Color.Transparent,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                uncheckedThumbColor = Color.White,
            ),
        )
    }
}

@Composable
private fun SecondsSliderRow(seconds: Int, range: IntRange, last: Boolean = false, onChange: (Int) -> Unit) {
    var value by remember(seconds) { mutableFloatStateOf(seconds.toFloat()) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Duration", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            val shown = value.roundToInt()
            Text(if (shown == 1) "1 second" else "$shown seconds", style = MaterialTheme.typography.bodyLarge, color = muted)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${range.first}s", style = MaterialTheme.typography.bodySmall, color = muted)
            val active = MaterialTheme.colorScheme.primary
            val inactive = MaterialTheme.colorScheme.surfaceContainerHighest
            Slider(
                value = value,
                onValueChange = { value = it },
                onValueChangeFinished = { onChange(value.roundToInt()) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = range.last - range.first - 1,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                // iOS-style: thin track and a round white knob, no tick marks.
                thumb = {
                    Box(
                        Modifier
                            .size(26.dp)
                            .shadow(3.dp, CircleShape)
                            .background(Color.White, CircleShape),
                    )
                },
                track = { state ->
                    val span = state.valueRange.endInclusive - state.valueRange.start
                    val fraction = ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
                    Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                        val corner = CornerRadius(size.height / 2)
                        drawRoundRect(inactive, cornerRadius = corner)
                        drawRoundRect(active, size = Size(size.width * fraction, size.height), cornerRadius = corner)
                    }
                },
            )
            Text("${range.last}s", style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
    if (!last) HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun ValueRow(label: String, value: String, last: Boolean = false, onClick: (() -> Unit)?) {
    RowBase(last, onClick) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onClick != null) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ActionRow(label: String, last: Boolean = false, onClick: () -> Unit) {
    RowBase(last, onClick) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
}
