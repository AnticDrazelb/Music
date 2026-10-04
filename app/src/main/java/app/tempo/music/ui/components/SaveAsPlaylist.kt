package app.tempo.music.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.tempo.music.data.model.Song
import app.tempo.music.ui.navigation.LocalAppContainer
import app.tempo.music.ui.navigation.LocalPlayer
import kotlinx.coroutines.launch

/** Saves a set of songs (a radio station, the queue, a mix) as a playlist, optionally downloading it. */
@Composable
fun SaveAsPlaylistDialog(defaultName: String, songs: List<Song>, onDismiss: () -> Unit) {
    val container = LocalAppContainer.current
    val player = LocalPlayer.current
    val download = rememberDownloader()
    var name by remember { mutableStateOf(defaultName) }
    var offline by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save as Playlist") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Playlist Name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { offline = !offline }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Download for offline", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(songsLabel(songs.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = offline,
                        onCheckedChange = { offline = it },
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
        },
        confirmButton = {
            TextButton(
                enabled = songs.isNotEmpty(),
                onClick = {
                    val playlistName = name.trim().ifBlank { defaultName }
                    onDismiss()
                    // App scope: the dialog leaves composition as soon as it closes.
                    container.appScope.launch {
                        container.library.createPlaylist(playlistName, songs)
                        if (!offline) player.notify("Saved “$playlistName” to your playlists")
                    }
                    if (offline) download(songs)
                },
            ) { Text("Save", color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.primary) }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
}
