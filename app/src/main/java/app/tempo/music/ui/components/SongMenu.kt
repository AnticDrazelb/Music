package app.tempo.music.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tempo.music.data.model.Song
import app.tempo.music.ui.navigation.LocalAppContainer
import app.tempo.music.ui.navigation.LocalNavigator
import app.tempo.music.ui.navigation.LocalPlayer
import kotlinx.coroutines.launch

data class MenuTarget(val song: Song, val playlistId: Long? = null, val entryId: Long? = null)

/** Global state for the song action sheet and playlist pickers. */
class SongMenuState {
    var target by mutableStateOf<MenuTarget?>(null)
    var addToPlaylist by mutableStateOf<List<Song>?>(null)
    var newPlaylistFor by mutableStateOf<List<Song>?>(null)
    var showNewPlaylist by mutableStateOf(false)

    fun open(song: Song, playlistId: Long? = null, entryId: Long? = null) {
        target = MenuTarget(song, playlistId, entryId)
    }

    fun pickPlaylist(songs: List<Song>) {
        addToPlaylist = songs
    }

    fun createPlaylist(initial: List<Song> = emptyList()) {
        newPlaylistFor = initial
        showNewPlaylist = true
    }
}

val LocalSongMenu = staticCompositionLocalOf<SongMenuState> { error("No SongMenuState") }

fun shareSong(context: Context, song: Song) {
    val text = "${song.title} — ${song.artist}\n${song.shareUrl}"
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
fun SongMenuHost(state: SongMenuState, onNavigateAway: () -> Unit) {
    val target = state.target
    if (target != null) {
        SongActionsSheet(target, state, onNavigateAway)
    }
    val songs = state.addToPlaylist
    if (songs != null) {
        AddToPlaylistSheet(songs, state)
    }
    if (state.showNewPlaylist) {
        NewPlaylistDialog(state)
    }
}

@Composable
private fun SongActionsSheet(target: MenuTarget, state: SongMenuState, onNavigateAway: () -> Unit) {
    val player = LocalPlayer.current
    val navigator = LocalNavigator.current
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val liked = target.song.id in LocalLikedIds.current
    val song = target.song

    fun act(block: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            state.target = null
            block()
        }
    }

    ModalBottomSheet(
        onDismissRequest = { state.target = null },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(song.thumbnailUrl, Modifier.size(56.dp), RoundedCornerShape(6.dp), fallbackKey = song.id)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(10.dp))
            SheetGroup {
                SheetAction(Icons.Rounded.QueuePlayNext, "Play Next") { act { player.playNext(song) } }
                SheetAction(Icons.AutoMirrored.Rounded.QueueMusic, "Play Last") { act { player.addToQueue(listOf(song)) } }
                SheetAction(Icons.Rounded.Radio, "Create Station", showDivider = false) { act { player.startRadio(song) } }
            }
            SheetGroup {
                SheetAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to a Playlist…") { act { state.pickPlaylist(listOf(song)) } }
                SheetAction(
                    if (liked) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    if (liked) "Undo Favorite" else "Favorite",
                    showDivider = target.playlistId != null,
                ) { act { player.toggleLike(song) } }
                if (target.playlistId != null && target.entryId != null) {
                    SheetAction(Icons.Rounded.RemoveCircleOutline, "Remove from Playlist", tint = MaterialTheme.colorScheme.error, showDivider = false) {
                        act {
                            // App scope: this sheet leaves composition as soon as it closes.
                            container.appScope.launch { container.library.removeFromPlaylist(target.playlistId, target.entryId) }
                        }
                    }
                }
            }
            SheetGroup {
                val artistUrl = song.artistUrl
                if (artistUrl != null) {
                    SheetAction(Icons.Rounded.Person, "Go to Artist") {
                        act {
                            onNavigateAway()
                            navigator.artist(artistUrl, song.artist)
                        }
                    }
                }
                SheetAction(Icons.Rounded.Share, "Share Song…", showDivider = false) { act { shareSong(context, song) } }
            }
        }
    }
}

@Composable
private fun SheetGroup(content: @Composable () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)),
    ) { content() }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    showDivider: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp)
            .height(50.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint, modifier = Modifier.weight(1f))
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
    }
    if (showDivider) {
        HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun AddToPlaylistSheet(songs: List<Song>, state: SongMenuState) {
    val container = LocalAppContainer.current
    val player = LocalPlayer.current
    val playlists by container.library.userPlaylists.collectAsState(initial = emptyList())
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = { state.addToPlaylist = null },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Text(
            "Add to Playlist",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.heightIn(max = 480.dp).navigationBarsPadding()) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            state.addToPlaylist = null
                            state.createPlaylist(songs)
                        }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("New Playlist…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            items(playlists, key = { it.id }) { playlist ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            state.addToPlaylist = null
                            container.appScope.launch {
                                container.library.addToPlaylist(playlist.id, songs)
                                player.notify("Added to ${playlist.name}")
                            }
                        }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Mosaic(playlist.thumbnails, Modifier.size(52.dp), RoundedCornerShape(6.dp), key = playlist.name)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(playlist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${playlist.songCount} songs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun NewPlaylistDialog(state: SongMenuState) {
    val container = LocalAppContainer.current
    val player = LocalPlayer.current
    val navigator = LocalNavigator.current
    var name by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val initial = state.newPlaylistFor.orEmpty()

    fun close() {
        state.showNewPlaylist = false
        state.newPlaylistFor = null
    }

    AlertDialog(
        onDismissRequest = ::close,
        title = { Text("New Playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Playlist Name") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = {
            TextButton(onClick = {
                val playlistName = name.ifBlank { "New Playlist" }
                close()
                container.appScope.launch {
                    val id = container.library.createPlaylist(playlistName, initial)
                    if (initial.isNotEmpty()) player.notify("Added to $playlistName") else navigator.userPlaylist(id)
                }
            }) { Text("Create", color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = {
            TextButton(onClick = ::close) { Text("Cancel", color = MaterialTheme.colorScheme.primary) }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
}
