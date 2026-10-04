package app.tempo.music.ui.screens.home

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
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tempo.music.data.mixes.Mix
import app.tempo.music.ui.components.CircleIconButton
import app.tempo.music.ui.components.DetailScaffold
import app.tempo.music.ui.components.DownloadAllButton
import app.tempo.music.ui.components.GradientArt
import app.tempo.music.ui.components.LocalSongMenu
import app.tempo.music.ui.components.PlayShuffleButtons
import app.tempo.music.ui.components.SaveAsPlaylistDialog
import app.tempo.music.ui.components.SongRow
import app.tempo.music.ui.components.belowTopBar
import app.tempo.music.ui.components.songsLabel
import app.tempo.music.ui.navigation.LocalAppContainer
import app.tempo.music.ui.navigation.LocalPlayer
import app.tempo.music.ui.theme.Gradients
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Cover art for a mix: its colour, its name, and the Tempo eyebrow. */
@Composable
fun MixArt(mix: Mix, modifier: Modifier = Modifier, titleSize: Int = 30) {
    GradientArt(
        title = mix.title,
        colors = Gradients.all[mix.palette.mod(Gradients.all.size)],
        eyebrow = "Tempo",
        corner = 12.dp,
        titleSize = titleSize,
        modifier = modifier,
    )
}

@Composable
fun MixScreen(id: String) {
    val container = LocalAppContainer.current
    val player = LocalPlayer.current
    val menu = LocalSongMenu.current
    val mixes by container.mixes.mixes.collectAsState()
    val mix = mixes.firstOrNull { it.id == id }
    val listState = rememberLazyListState()
    var saving by remember { mutableStateOf(false) }
    val songs = mix?.songs.orEmpty()

    if (saving && mix != null) {
        val date = LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM"))
        SaveAsPlaylistDialog("${mix.title} · $date", songs, onDismiss = { saving = false })
    }

    DetailScaffold(
        title = mix?.title.orEmpty(),
        listState = listState,
        actions = {
            DownloadAllButton(songs)
            Spacer(Modifier.size(10.dp))
            if (songs.isNotEmpty()) {
                CircleIconButton(Icons.AutoMirrored.Rounded.PlaylistAdd, "Save as Playlist", onClick = { saving = true })
            }
            Spacer(Modifier.size(12.dp))
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = padding) {
            item {
                Column(Modifier.fillMaxWidth().belowTopBar(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(8.dp))
                    if (mix != null) {
                        MixArt(
                            mix,
                            Modifier.size(250.dp).shadow(18.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)),
                            titleSize = 38,
                        )
                        Spacer(Modifier.height(18.dp))
                        Text(mix.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        Text(
                            mix.subtitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 28.dp),
                        )
                        Text(
                            "Made for you · ${songsLabel(songs.size)} · Updated today",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        PlayShuffleButtons(
                            onPlay = { player.play(songs) },
                            onShuffle = { player.play(songs, shuffle = true) },
                            enabled = songs.isNotEmpty(),
                        )
                        Spacer(Modifier.height(12.dp))
                    } else {
                        Text(
                            "This mix has been refreshed. Head back to Home for today's mixes.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(32.dp),
                        )
                    }
                }
            }
            itemsIndexed(songs, key = { i, s -> "$i-${s.id}" }) { index, song ->
                SongRow(song = song, onClick = { player.play(songs, index) }, onMore = { menu.open(song) })
            }
        }
    }
}
