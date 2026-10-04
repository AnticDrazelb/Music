package app.tempo.music.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tempo.music.data.UserPlaylist
import app.tempo.music.data.model.ArtistRef
import app.tempo.music.data.model.CollectionRef
import app.tempo.music.data.model.CollectionType
import app.tempo.music.data.model.Song
import app.tempo.music.ui.components.ArtistRow
import app.tempo.music.ui.components.CollectionCard
import app.tempo.music.ui.components.CollectionRow
import app.tempo.music.ui.components.DetailScaffold
import app.tempo.music.ui.components.EmptyState
import app.tempo.music.ui.components.LargeTitle
import app.tempo.music.ui.components.LocalSongMenu
import app.tempo.music.ui.components.MediaCard
import app.tempo.music.ui.components.MenuRow
import app.tempo.music.ui.components.Mosaic
import app.tempo.music.ui.components.NavigationRow
import app.tempo.music.ui.components.PlayShuffleButtons
import app.tempo.music.ui.components.SectionHeader
import app.tempo.music.ui.components.SongCard
import app.tempo.music.ui.components.SongRow
import app.tempo.music.ui.components.belowTopBar
import app.tempo.music.ui.components.formatDuration
import app.tempo.music.ui.navigation.LibrarySection
import app.tempo.music.ui.navigation.LocalAppContainer
import app.tempo.music.ui.navigation.LocalNavigator
import app.tempo.music.ui.navigation.LocalPlayer
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen() {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val player = LocalPlayer.current
    val menu = LocalSongMenu.current
    val playlists by container.library.userPlaylists.collectAsState(initial = emptyList())
    val recent by remember { container.library.recentlyPlayed(20) }.collectAsState(initial = emptyList())

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 160.dp)) {
        item {
            LargeTitle("Library") {
                IconButton(onClick = navigator::settings) {
                    Icon(Icons.Rounded.Settings, "Settings", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item { MenuRow(Icons.AutoMirrored.Rounded.PlaylistPlay, "Playlists", { navigator.library(LibrarySection.PLAYLISTS) }) }
        item { MenuRow(Icons.Rounded.Star, "Favorite Songs", { navigator.library(LibrarySection.FAVORITES) }) }
        item { MenuRow(Icons.Rounded.History, "Recently Played", { navigator.library(LibrarySection.RECENT) }) }
        item { MenuRow(Icons.Rounded.Album, "Albums", { navigator.library(LibrarySection.ALBUMS) }) }
        item { MenuRow(Icons.Rounded.Mic, "Artists", { navigator.library(LibrarySection.ARTISTS) }, showDivider = false) }

        item { SectionHeader("Your Playlists") }
        val tiles: List<UserPlaylist?> = listOf<UserPlaylist?>(null) + playlists
        items(tiles.chunked(2)) { row ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                row.forEach { playlist ->
                    if (playlist == null) {
                        NewPlaylistTile(Modifier.weight(1f)) { menu.createPlaylist() }
                    } else {
                        MediaCard(
                            title = playlist.name,
                            subtitle = "${playlist.songCount} songs",
                            imageUrl = null,
                            onClick = { navigator.userPlaylist(playlist.id) },
                            modifier = Modifier.weight(1f),
                            width = Dp.Unspecified,
                            image = { Mosaic(playlist.thumbnails, Modifier.fillMaxSize(), key = playlist.name) },
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        if (recent.isNotEmpty()) {
            item { SectionHeader("Recently Played", onClick = { navigator.library(LibrarySection.RECENT) }) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(recent, key = { _, s -> s.id }) { index, song ->
                        SongCard(song, onClick = { player.play(recent, index) }, onLongClick = { menu.open(song) })
                    }
                }
            }
        }
    }
}

@Composable
private fun NewPlaylistTile(modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text("New Playlist", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun LibrarySectionScreen(sectionName: String) {
    val section = runCatching { LibrarySection.valueOf(sectionName) }.getOrDefault(LibrarySection.PLAYLISTS)
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val player = LocalPlayer.current
    val menu = LocalSongMenu.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val liked by container.library.likedSongs.collectAsState(initial = emptyList())
    val recent by remember { container.library.recentlyPlayed(200) }.collectAsState(initial = emptyList())
    val playlists by container.library.userPlaylists.collectAsState(initial = emptyList())
    val saved by container.library.savedItems.collectAsState(initial = emptyList())

    DetailScaffold(
        title = section.title,
        listState = listState,
        actions = {
            if (section == LibrarySection.RECENT && recent.isNotEmpty()) {
                TextButton(onClick = { scope.launch { container.library.clearHistory() } }) {
                    Text("Clear", color = MaterialTheme.colorScheme.primary)
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = padding) {
            item {
                Text(
                    section.title,
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.belowTopBar().padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                )
            }
            when (section) {
                LibrarySection.FAVORITES, LibrarySection.RECENT -> {
                    val songs = if (section == LibrarySection.FAVORITES) liked else recent
                    if (songs.isEmpty()) {
                        item {
                            if (section == LibrarySection.FAVORITES) {
                                EmptyState(Icons.Rounded.Star, "No Favorites Yet", "Tap ⋯ on any song and choose Favorite to save it here.")
                            } else {
                                EmptyState(Icons.Rounded.History, "Nothing Played Yet", "Songs you play will show up here.")
                            }
                        }
                    } else {
                        item {
                            PlayShuffleButtons(onPlay = { player.play(songs) }, onShuffle = { player.play(songs, shuffle = true) })
                            Spacer(Modifier.height(12.dp))
                        }
                        itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
                            SongRow(song, onClick = { player.play(songs, index) }, onMore = { menu.open(song) })
                        }
                    }
                }
                LibrarySection.PLAYLISTS -> {
                    item {
                        NavigationRow(title = "New Playlist…", onClick = { menu.createPlaylist() }) {
                            Box(
                                Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary) }
                        }
                    }
                    items(playlists, key = { "user-${it.id}" }) { p ->
                        NavigationRow(title = p.name, subtitle = "${p.songCount} songs", onClick = { navigator.userPlaylist(p.id) }) {
                            Mosaic(p.thumbnails, Modifier.size(56.dp), RoundedCornerShape(6.dp), key = p.name)
                        }
                    }
                    val remote = saved.filterIsInstance<CollectionRef>().filter { it.type != CollectionType.ALBUM }
                    items(remote, key = { it.url }) { ref -> CollectionRow(ref, onClick = { navigator.collection(ref) }) }
                }
                LibrarySection.ALBUMS -> {
                    val albums = saved.filterIsInstance<CollectionRef>().filter { it.type == CollectionType.ALBUM }
                    if (albums.isEmpty()) {
                        item { EmptyState(Icons.Rounded.Album, "No Albums", "Tap + on an album to add it to your library.") }
                    }
                    items(albums.chunked(2)) { row ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            row.forEach { ref ->
                                CollectionCard(ref, onClick = { navigator.collection(ref) }, modifier = Modifier.weight(1f), width = Dp.Unspecified)
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                LibrarySection.ARTISTS -> {
                    val artists = saved.filterIsInstance<ArtistRef>()
                    if (artists.isEmpty()) {
                        item { EmptyState(Icons.Rounded.Mic, "No Artists", "Follow artists to find them quickly here.") }
                    }
                    items(artists, key = { it.url }) { ref -> ArtistRow(ref, onClick = { navigator.artist(ref) }) }
                }
            }
        }
    }
}

@Composable
fun UserPlaylistScreen(id: Long) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val player = LocalPlayer.current
    val menu = LocalSongMenu.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val playlist by remember(id) { container.library.userPlaylist(id) }.collectAsState(initial = null)
    val entries by remember(id) { container.library.playlistSongs(id) }.collectAsState(initial = emptyList())
    val songs: List<Song> = entries.map { it.song }
    var showMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val name = playlist?.name ?: "Playlist"

    DetailScaffold(
        title = name,
        listState = listState,
        actions = {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Rounded.MoreHoriz, "More", tint = MaterialTheme.colorScheme.primary)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { showMenu = false; renaming = true })
                    DropdownMenuItem(
                        text = { Text("Add All to Queue") },
                        onClick = { showMenu = false; player.addToQueue(songs) },
                        enabled = songs.isNotEmpty(),
                    )
                    DropdownMenuItem(
                        text = { Text("Delete Playlist", color = MaterialTheme.colorScheme.error) },
                        onClick = { showMenu = false; confirmDelete = true },
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = padding) {
            item {
                Column(Modifier.fillMaxWidth().belowTopBar(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(8.dp))
                    Mosaic(
                        playlist?.thumbnails.orEmpty(),
                        Modifier.size(250.dp).shadow(18.dp, RoundedCornerShape(10.dp)),
                        RoundedCornerShape(10.dp),
                        key = name,
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 28.dp))
                    val totalSec = songs.sumOf { it.durationSec.coerceAtLeast(0) }
                    Text(
                        buildString {
                            append("${songs.size} songs")
                            if (totalSec > 0) append(" · ${formatDuration(totalSec)}")
                        },
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
                }
            }
            if (entries.isEmpty()) {
                item {
                    EmptyState(Icons.Rounded.LibraryMusic, "Add Music", "Tap ⋯ on any song and choose “Add to a Playlist”.")
                }
            }
            itemsIndexed(entries, key = { _, e -> e.entryId }) { index, entry ->
                SongRow(
                    entry.song,
                    onClick = { player.play(songs, index) },
                    onMore = { menu.open(entry.song, playlistId = id, entryId = entry.entryId) },
                )
            }
        }
    }

    if (renaming) {
        var text by remember { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename Playlist") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    renaming = false
                    scope.launch { container.library.renamePlaylist(id, text) }
                }) { Text("Save", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel", color = MaterialTheme.colorScheme.primary) } },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “$name”?") },
            text = { Text("This playlist will be removed from your library.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { container.library.deletePlaylist(id) }
                    navigator.back()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = MaterialTheme.colorScheme.primary) } },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
    }
}
