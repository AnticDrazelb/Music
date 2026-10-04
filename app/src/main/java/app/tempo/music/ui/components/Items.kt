package app.tempo.music.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowDropUp
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tempo.music.data.model.ArtistRef
import app.tempo.music.data.model.ChartEntry
import app.tempo.music.data.model.CollectionRef
import app.tempo.music.data.model.CollectionType
import app.tempo.music.data.model.Song
import app.tempo.music.ui.theme.NegativeRed
import app.tempo.music.ui.theme.PositiveGreen

/** Id of the song currently loaded in the player, and whether it is playing. */
data class NowPlaying(val songId: String? = null, val isPlaying: Boolean = false)

val LocalNowPlaying = compositionLocalOf { NowPlaying() }
val LocalLikedIds = compositionLocalOf { emptySet<String>() }

fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatCount(n: Long): String = when {
    n >= 1_000_000_000 -> "%.1fB".format(n / 1e9).replace(".0B", "B")
    n >= 1_000_000 -> "%.1fM".format(n / 1e6).replace(".0M", "M")
    n >= 1_000 -> "%.1fK".format(n / 1e3).replace(".0K", "K")
    else -> n.toString()
}

// ------------------------------------------------------------------ rows

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    trackNumber: Int? = null,
    showDivider: Boolean = true,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val nowPlaying = LocalNowPlaying.current
    val isCurrent = nowPlaying.songId == song.id
    val liked = song.id in LocalLikedIds.current
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onMore)
                .padding(start = 20.dp, end = 4.dp)
                .height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke()
            if (trackNumber != null) {
                Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
                    if (isCurrent) NowPlayingBars(nowPlaying.isPlaying)
                    else Text("$trackNumber", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Artwork(song.thumbnailUrl, Modifier.size(48.dp), RoundedCornerShape(6.dp), fallbackKey = song.id)
                    if (isCurrent) {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f)))
                        NowPlayingBars(nowPlaying.isPlaying, color = Color.White)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (liked) {
                        Icon(Icons.Rounded.Star, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                    }
                    Text(
                        subtitle ?: song.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onMore) {
                Icon(Icons.Rounded.MoreHoriz, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (showDivider) {
            HorizontalDivider(
                Modifier.padding(start = if (trackNumber != null) 48.dp else 80.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
fun RankBadge(entry: ChartEntry, modifier: Modifier = Modifier) {
    Column(modifier.width(34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "${entry.rank}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        when {
            entry.isNew -> Text("NEW", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            entry.movement > 0 -> Icon(Icons.Rounded.ArrowDropUp, "Up ${entry.movement}", tint = PositiveGreen, modifier = Modifier.size(16.dp))
            entry.movement < 0 -> Icon(Icons.Rounded.ArrowDropDown, "Down ${-entry.movement}", tint = NegativeRed, modifier = Modifier.size(16.dp))
            else -> Text("–", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ChartSongRow(
    entry: ChartEntry,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
) {
    SongRow(
        song = entry.song,
        onClick = onClick,
        onMore = onMore,
        modifier = modifier,
        showDivider = showDivider,
        leading = {
            RankBadge(entry)
            Spacer(Modifier.width(8.dp))
        },
    )
}

@Composable
fun CollectionRow(item: CollectionRef, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val typeLabel = when (item.type) {
        CollectionType.ALBUM -> "Album"
        CollectionType.MIX -> "Mix"
        CollectionType.PLAYLIST -> "Playlist"
    }
    NavigationRow(
        title = item.title,
        subtitle = listOfNotNull(typeLabel, item.subtitle?.takeIf { it.isNotBlank() }).joinToString(" · "),
        onClick = onClick,
        modifier = modifier,
    ) {
        Artwork(item.thumbnailUrl, Modifier.size(56.dp), RoundedCornerShape(6.dp), fallbackKey = item.url)
    }
}

@Composable
fun ArtistRow(item: ArtistRef, onClick: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null) {
    NavigationRow(
        title = item.name,
        subtitle = subtitle ?: if (item.subscribers > 0) "${formatCount(item.subscribers)} subscribers" else "Artist",
        onClick = onClick,
        modifier = modifier,
    ) {
        Artwork(item.thumbnailUrl, Modifier.size(56.dp), CircleShape, fallbackKey = item.url, icon = Icons.Rounded.Person)
    }
}

@Composable
fun NavigationRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    showDivider: Boolean = true,
    leading: @Composable () -> Unit,
) {
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (showDivider) {
            HorizontalDivider(Modifier.padding(start = 88.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** Library style row: tinted icon, label and chevron. */
@Composable
fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, showDivider: Boolean = true) {
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp)
                .height(52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(16.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (showDivider) {
            HorizontalDivider(Modifier.padding(start = 62.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

// ------------------------------------------------------------------ cards

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 160.dp,
    onLongClick: (() -> Unit)? = null,
    imageKey: String = imageUrl ?: title,
    image: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier
            .width(width)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        if (image != null) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) { image() }
        } else {
            SquareArtwork(imageUrl, Modifier.fillMaxWidth(), key = imageKey)
        }
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun CollectionCard(item: CollectionRef, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 160.dp) {
    MediaCard(item.title, item.subtitle, item.thumbnailUrl, onClick, modifier, width, imageKey = item.url)
}

@Composable
fun SongCard(song: Song, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 160.dp) {
    MediaCard(song.title, song.artist, song.thumbnailUrl, onClick, modifier, width, onLongClick, imageKey = song.id)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoCard(song: Song, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 260.dp) {
    Column(
        modifier
            .width(width)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box {
            Artwork(
                song.thumbnailUrl,
                Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                RoundedCornerShape(8.dp),
                fallbackKey = song.id,
            )
            Icon(
                Icons.Rounded.PlayCircle,
                null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).size(28.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(song.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ArtistCircle(item: ArtistRef, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 120.dp, caption: String? = null) {
    Column(
        modifier
            .width(size)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(item.thumbnailUrl, Modifier.size(size), CircleShape, fallbackKey = item.url, icon = Icons.Rounded.Person)
        Spacer(Modifier.height(8.dp))
        Text(
            item.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (caption != null) {
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** Generated artwork for charts and genres: a gradient tile with large type. */
@Composable
fun GradientArt(
    title: String,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    subtitle: String? = null,
    corner: Dp = 8.dp,
    titleSize: Int = 30,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(colors)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.25f)))),
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (eyebrow != null) {
                Text(eyebrow.uppercase(), color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            Text(
                title,
                color = Color.White,
                fontSize = titleSize.sp,
                lineHeight = (titleSize + 2).sp,
                fontWeight = FontWeight.Black,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(subtitle, color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
