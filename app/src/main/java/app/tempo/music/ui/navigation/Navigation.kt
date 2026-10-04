package app.tempo.music.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import app.tempo.music.AppContainer
import app.tempo.music.data.model.ArtistRef
import app.tempo.music.data.model.ChartKind
import app.tempo.music.data.model.CollectionRef
import app.tempo.music.playback.PlayerConnection
import kotlinx.serialization.Serializable

// ---- routes

@Serializable object HomeRoute
@Serializable object BrowseRoute
@Serializable object SearchRoute
@Serializable object LibraryRoute
@Serializable object SettingsRoute

@Serializable data class ChartRoute(val kind: String, val country: String, val limit: Int = 100)
@Serializable data class TopArtistsRoute(val country: String)
@Serializable data class GenreRoute(val id: String)
@Serializable data class UserPlaylistRoute(val id: Long)
@Serializable data class LibrarySectionRoute(val section: String)

@Serializable
data class CollectionRoute(
    val url: String,
    val title: String = "",
    val subtitle: String? = null,
    val thumbnail: String? = null,
    val type: String = "PLAYLIST",
)

@Serializable
data class ArtistRoute(val url: String, val name: String = "", val thumbnail: String? = null)

enum class LibrarySection(val title: String) {
    PLAYLISTS("Playlists"),
    FAVORITES("Favorite Songs"),
    RECENT("Recently Played"),
    ALBUMS("Albums"),
    ARTISTS("Artists"),
}

enum class Tab { HOME, BROWSE, SEARCH, LIBRARY }

// ---- navigator

class Navigator(val nav: NavHostController) {
    fun back() {
        nav.popBackStack()
    }

    fun chart(kind: ChartKind, country: String, limit: Int = 100) {
        if (kind == ChartKind.TOP_ARTISTS) nav.navigate(TopArtistsRoute(country))
        else nav.navigate(ChartRoute(kind.name, country, limit))
    }

    fun collection(ref: CollectionRef) =
        nav.navigate(CollectionRoute(ref.url, ref.title, ref.subtitle, ref.thumbnailUrl, ref.type.name))

    fun artist(ref: ArtistRef) = nav.navigate(ArtistRoute(ref.url, ref.name, ref.thumbnailUrl))
    fun artist(url: String, name: String) = nav.navigate(ArtistRoute(url, name))
    fun genre(id: String) = nav.navigate(GenreRoute(id))
    fun userPlaylist(id: Long) = nav.navigate(UserPlaylistRoute(id))
    fun library(section: LibrarySection) = nav.navigate(LibrarySectionRoute(section.name))
    fun settings() = nav.navigate(SettingsRoute)
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No Navigator") }
val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("No AppContainer") }
val LocalPlayer = staticCompositionLocalOf<PlayerConnection> { error("No PlayerConnection") }

/** Creates a ViewModel scoped to the current back stack entry with access to the container. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = LocalAppContainer.current
    return viewModel(key = key) { create(container) }
}
