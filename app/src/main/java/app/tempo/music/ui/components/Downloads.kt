package app.tempo.music.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowCircleDown
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tempo.music.data.download.DownloadState
import app.tempo.music.data.download.DownloadStatus
import app.tempo.music.data.model.Song
import app.tempo.music.ui.navigation.LocalAppContainer
import app.tempo.music.ui.navigation.LocalPlayer
import kotlinx.coroutines.launch

/** Download status of every song that has one, keyed by song id. */
val LocalDownloads = compositionLocalOf { emptyMap<String, DownloadStatus>() }

/** Asks for notification permission (Android 13+) so download progress can be shown. */
val LocalRequestNotificationPermission = staticCompositionLocalOf<() -> Unit> { {} }

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%d MB".format(bytes / (1L shl 20))
    bytes >= 1L shl 10 -> "%d KB".format(bytes / (1L shl 10))
    else -> "$bytes B"
}

/** Returns a function that queues songs for download and reports what happened. */
@Composable
fun rememberDownloader(): (List<Song>) -> Unit {
    val container = LocalAppContainer.current
    val player = LocalPlayer.current
    val requestPermission = LocalRequestNotificationPermission.current
    return remember(container, player, requestPermission) {
        { songs ->
            requestPermission()
            container.appScope.launch {
                val added = container.downloads.download(songs)
                player.notify(
                    when (added) {
                        0 -> if (songs.size == 1) "Already downloaded" else "Already downloaded or downloading"
                        1 -> "Downloading…"
                        else -> "Downloading $added songs"
                    },
                )
            }
        }
    }
}

/** Download button for a whole album or playlist: progress while busy, a check when done. */
@Composable
fun DownloadAllButton(songs: List<Song>, modifier: Modifier = Modifier) {
    if (songs.isEmpty()) return
    val statuses = LocalDownloads.current
    val download = rememberDownloader()
    val player = LocalPlayer.current
    val done = songs.count { statuses[it.id]?.state == DownloadState.DONE }
    val busy = songs.count { statuses[it.id]?.state == DownloadState.QUEUED || statuses[it.id]?.state == DownloadState.DOWNLOADING }
    when {
        done == songs.size -> CircleIconButton(
            Icons.Rounded.DownloadDone,
            "Downloaded",
            onClick = { player.notify(if (done == 1) "Downloaded" else "All $done songs are downloaded") },
            modifier = modifier,
        )
        busy > 0 -> Box(modifier.size(32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { done.toFloat() / songs.size },
                strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(26.dp),
            )
        }
        else -> CircleIconButton(Icons.Rounded.Download, "Download", onClick = { download(songs) }, modifier = modifier)
    }
}

/** Small status mark shown next to a song's artist. */
@Composable
fun DownloadMark(songId: String, size: Dp = 13.dp) {
    val status = LocalDownloads.current[songId] ?: return
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    when (status.state) {
        DownloadState.DONE -> Icon(Icons.Rounded.ArrowCircleDown, "Downloaded", tint = tint, modifier = Modifier.size(size))
        DownloadState.DOWNLOADING -> CircularProgressIndicator(
            progress = { status.progress },
            strokeWidth = 1.5.dp,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(size - 2.dp),
        )
        DownloadState.QUEUED -> Icon(Icons.Rounded.Schedule, "Waiting to download", tint = tint, modifier = Modifier.size(size))
        DownloadState.FAILED -> Unit
    }
}
