package app.tempo.music.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.core.content.ContextCompat
import app.tempo.music.AppContainer
import app.tempo.music.MainActivity
import app.tempo.music.data.model.ArtistRef
import app.tempo.music.data.model.CollectionRef
import app.tempo.music.data.model.CollectionType
import app.tempo.music.data.youtube.YouTubeUtils
import app.tempo.music.ui.components.LocalDownloads
import app.tempo.music.ui.components.LocalLikedIds
import app.tempo.music.ui.components.LocalRequestNotificationPermission
import app.tempo.music.ui.components.LocalNowPlaying
import app.tempo.music.ui.components.LocalSongMenu
import app.tempo.music.ui.components.NowPlaying
import app.tempo.music.ui.components.SongMenuHost
import app.tempo.music.ui.components.SongMenuState
import app.tempo.music.ui.navigation.ArtistRoute
import app.tempo.music.ui.navigation.BrowseRoute
import app.tempo.music.ui.navigation.ChartRoute
import app.tempo.music.ui.navigation.CollectionRoute
import app.tempo.music.ui.navigation.GenreRoute
import app.tempo.music.ui.navigation.HomeRoute
import app.tempo.music.ui.navigation.LibraryRoute
import app.tempo.music.ui.navigation.LibrarySectionRoute
import app.tempo.music.ui.navigation.LocalAppContainer
import app.tempo.music.ui.navigation.LocalNavigator
import app.tempo.music.ui.navigation.LocalPlayer
import app.tempo.music.ui.navigation.Navigator
import app.tempo.music.ui.navigation.SearchRoute
import app.tempo.music.ui.navigation.SettingsRoute
import app.tempo.music.ui.navigation.Tab
import app.tempo.music.ui.navigation.TopArtistsRoute
import app.tempo.music.ui.navigation.UserPlaylistRoute
import app.tempo.music.ui.screens.artist.ArtistScreen
import app.tempo.music.ui.screens.browse.BrowseScreen
import app.tempo.music.ui.screens.browse.GenreScreen
import app.tempo.music.ui.screens.charts.ChartScreen
import app.tempo.music.ui.screens.charts.TopArtistsScreen
import app.tempo.music.ui.screens.collection.CollectionScreen
import app.tempo.music.ui.screens.home.HomeScreen
import app.tempo.music.ui.screens.library.LibrarySectionScreen
import app.tempo.music.ui.screens.library.LibraryScreen
import app.tempo.music.ui.screens.library.UserPlaylistScreen
import app.tempo.music.ui.screens.player.MiniPlayer
import app.tempo.music.ui.screens.player.NowPlayingScreen
import app.tempo.music.ui.screens.search.SearchScreen
import app.tempo.music.ui.screens.settings.SettingsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private fun NavDestination.isTabRoot() =
    hasRoute<HomeRoute>() || hasRoute<BrowseRoute>() || hasRoute<SearchRoute>() || hasRoute<LibraryRoute>()

private fun Tab.route(): Any = when (this) {
    Tab.HOME -> HomeRoute
    Tab.BROWSE -> BrowseRoute
    Tab.SEARCH -> SearchRoute
    Tab.LIBRARY -> LibraryRoute
}

@Composable
fun TempoRoot(container: AppContainer, incoming: StateFlow<Intent?>, onIntentHandled: () -> Unit) {
    val navController = rememberNavController()
    val navigator = remember(navController) { Navigator(navController) }
    val player = container.playerConnection
    val songMenu = remember { SongMenuState() }
    val playerState by player.state.collectAsState()
    val liked by player.likedIds.collectAsState()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var toast by remember { mutableStateOf<String?>(null) }
    val downloads by container.downloads.statuses.collectAsState()
    val pendingIntent by incoming.collectAsState()
    val context = LocalContext.current
    var askedForNotifications by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val requestNotifications: () -> Unit = {
        val granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted && !askedForNotifications) {
            askedForNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()

    LaunchedEffect(Unit) { player.connect() }
    LaunchedEffect(Unit) {
        player.messages.collectLatest { message ->
            toast = message
            delay(2200)
            toast = null
        }
    }
    LaunchedEffect(backStackEntry) {
        val d = backStackEntry?.destination ?: return@LaunchedEffect
        tab = when {
            d.hasRoute<HomeRoute>() -> Tab.HOME
            d.hasRoute<BrowseRoute>() -> Tab.BROWSE
            d.hasRoute<SearchRoute>() -> Tab.SEARCH
            d.hasRoute<LibraryRoute>() -> Tab.LIBRARY
            else -> tab
        }
    }
    LaunchedEffect(pendingIntent) {
        val intent = pendingIntent ?: return@LaunchedEffect
        onIntentHandled()
        if (intent.getBooleanExtra(MainActivity.EXTRA_OPEN_PLAYER, false)) {
            expanded = true
            return@LaunchedEffect
        }
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val videoId = YouTubeUtils.videoId(text)
            val playlistId = YouTubeUtils.playlistId(text)
            when {
                videoId != null -> launch {
                    player.notify("Loading…")
                    runCatching { container.youtube.song(videoId) }
                        .onSuccess { song ->
                            player.playSong(song)
                            expanded = true
                        }
                        .onFailure { player.notify("Couldn't open that link") }
                }
                playlistId != null -> navigator.collection(
                    CollectionRef(url = YouTubeUtils.playlistUrl(playlistId), title = "", type = CollectionType.PLAYLIST),
                )
                else -> player.notify("That doesn't look like a YouTube link")
            }
        }
    }

    fun selectTab(target: Tab) {
        if (target == tab) {
            // Re-selecting a tab pops back to its root, like iOS.
            navController.popBackStack(target.route(), inclusive = false)
        } else {
            navController.navigate(target.route()) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        tab = target
    }

    CompositionLocalProvider(
        LocalAppContainer provides container,
        LocalPlayer provides player,
        LocalNavigator provides navigator,
        LocalSongMenu provides songMenu,
        LocalNowPlaying provides NowPlaying(playerState.current?.id, playerState.isPlaying),
        LocalLikedIds provides liked,
        LocalDownloads provides downloads,
        LocalRequestNotificationPermission provides requestNotifications,
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            NavHost(
                navController = navController,
                startDestination = HomeRoute,
                modifier = Modifier.fillMaxSize(),
                enterTransition = {
                    if (targetState.destination.isTabRoot()) fadeIn(tween(180))
                    else slideInHorizontally(tween(300)) { it } + fadeIn(tween(300))
                },
                exitTransition = {
                    if (targetState.destination.isTabRoot()) fadeOut(tween(180))
                    else slideOutHorizontally(tween(300)) { -it / 4 } + fadeOut(tween(300))
                },
                popEnterTransition = { slideInHorizontally(tween(300)) { -it / 4 } + fadeIn(tween(300)) },
                popExitTransition = { slideOutHorizontally(tween(300)) { it } + fadeOut(tween(300)) },
            ) {
                composable<HomeRoute> { HomeScreen() }
                composable<BrowseRoute> { BrowseScreen() }
                composable<SearchRoute> { SearchScreen() }
                composable<LibraryRoute> { LibraryScreen() }
                composable<SettingsRoute> { SettingsScreen() }
                composable<ChartRoute> { entry ->
                    val r = entry.toRoute<ChartRoute>()
                    ChartScreen(r.kind, r.country, r.limit)
                }
                composable<TopArtistsRoute> { entry -> TopArtistsScreen(entry.toRoute<TopArtistsRoute>().country) }
                composable<GenreRoute> { entry -> GenreScreen(entry.toRoute<GenreRoute>().id) }
                composable<UserPlaylistRoute> { entry -> UserPlaylistScreen(entry.toRoute<UserPlaylistRoute>().id) }
                composable<LibrarySectionRoute> { entry -> LibrarySectionScreen(entry.toRoute<LibrarySectionRoute>().section) }
                composable<CollectionRoute> { entry ->
                    val r = entry.toRoute<CollectionRoute>()
                    CollectionScreen(
                        CollectionRef(
                            url = r.url,
                            title = r.title,
                            subtitle = r.subtitle,
                            thumbnailUrl = r.thumbnail,
                            type = runCatching { CollectionType.valueOf(r.type) }.getOrDefault(CollectionType.PLAYLIST),
                        ),
                    )
                }
                composable<ArtistRoute> { entry ->
                    val r = entry.toRoute<ArtistRoute>()
                    ArtistScreen(ArtistRef(url = r.url, name = r.name, thumbnailUrl = r.thumbnail))
                }
            }

            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                AnimatedVisibility(visible = toast != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(
                        toast.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(horizontal = 32.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f))
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
                if (playerState.current != null) {
                    MiniPlayer(onExpand = { expanded = true })
                }
                BottomTabs(selected = tab, onSelect = ::selectTab)
            }

            AnimatedVisibility(
                visible = expanded && playerState.current != null,
                enter = slideInVertically(tween(320)) { it },
                exit = slideOutVertically(tween(280)) { it },
            ) {
                NowPlayingScreen(onCollapse = { expanded = false })
            }

            SongMenuHost(songMenu, onNavigateAway = { expanded = false })
        }
    }
}

@Composable
private fun BottomTabs(selected: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background.copy(alpha = 0.97f))) {
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(54.dp)) {
            TabItem(Icons.Rounded.Home, "Home", selected == Tab.HOME, Modifier.weight(1f)) { onSelect(Tab.HOME) }
            TabItem(Icons.Rounded.GridView, "Browse", selected == Tab.BROWSE, Modifier.weight(1f)) { onSelect(Tab.BROWSE) }
            TabItem(Icons.Rounded.LibraryMusic, "Library", selected == Tab.LIBRARY, Modifier.weight(1f)) { onSelect(Tab.LIBRARY) }
            TabItem(Icons.Rounded.Search, "Search", selected == Tab.SEARCH, Modifier.weight(1f)) { onSelect(Tab.SEARCH) }
        }
    }
}

@Composable
private fun TabItem(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier
            .fillMaxHeight()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(icon, label, tint = color, modifier = Modifier.size(26.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
