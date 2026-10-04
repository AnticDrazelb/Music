package app.tempo.music.playback

import android.content.ComponentName
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.SessionToken
import androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil
import androidx.test.core.app.ApplicationProvider
import app.tempo.music.TempoApp
import app.tempo.music.data.model.Song
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/** Browses and plays from the real PlaybackService through a MediaBrowser, as Android Auto does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidAutoTest {

    private val app = ApplicationProvider.getApplicationContext<TempoApp>()
    private lateinit var service: ServiceController<PlaybackService>
    private lateinit var browser: MediaBrowser

    private val songs = (1..3).map { n ->
        Song("video0000${n}x", "Song $n", "Artist $n", thumbnailUrl = "https://i.ytimg.com/vi/video0000${n}x/hqdefault.jpg", durationSec = 200)
    }

    @Before
    fun connect() {
        runBlocking(Dispatchers.IO) {
            songs.forEach { app.container.library.setLiked(it, true) }
            app.container.library.createPlaylist("Road Trip", songs.take(2))
        }
        service = Robolectric.buildService(PlaybackService::class.java).create()
        shadowOf(app).setComponentNameAndServiceForBindService(
            ComponentName(app, PlaybackService::class.java),
            service.get().onBind(Intent(MediaLibraryService.SERVICE_INTERFACE)),
        )
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        browser = await(
            MediaBrowser.Builder(app, token)
                .setListener(object : MediaBrowser.Listener {
                    override fun onSearchResultChanged(browser: MediaBrowser, query: String, itemCount: Int, params: MediaLibraryService.LibraryParams?) {
                        searchDone = true
                    }
                })
                .buildAsync(),
        )
    }

    @After
    fun disconnect() {
        browser.release()
        service.destroy()
    }

    private fun <T> await(future: ListenableFuture<T>): T {
        runMainLooperUntil { future.isDone }
        return future.get()
    }

    private fun children(parentId: String, page: Int = 0, pageSize: Int = 100): List<MediaItem> {
        val result: LibraryResult<*> = await(browser.getChildren(parentId, page, pageSize, null))
        assertEquals("children of $parentId", LibraryResult.RESULT_SUCCESS, result.resultCode)
        @Suppress("UNCHECKED_CAST")
        return (result.value as List<MediaItem>)
    }

    @Test
    fun rootShowsTabsAsTilesAndSongsAsRows() {
        val root = await(browser.getLibraryRoot(null))
        assertEquals(LibraryTree.ROOT, root.value!!.mediaId)
        val extras = root.params!!.extras
        assertEquals(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM, extras.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))
        assertEquals(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM, extras.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE))

        val tabs = children(LibraryTree.ROOT)
        assertEquals(listOf("Made for You", "Library", "Charts"), tabs.map { it.mediaMetadata.title.toString() })
        assertTrue(tabs.all { it.mediaMetadata.isBrowsable == true && it.mediaMetadata.isPlayable == false })
        assertEquals(
            listOf("Favourite Songs", "Playlists", "Downloaded", "Recently Played"),
            children(LibraryTree.LIBRARY).map { it.mediaMetadata.title.toString() },
        )
    }

    @Test
    fun favouritesAndPlaylistsListPlayableSongsWithCarArtwork() {
        val favourites = children(LibraryTree.FAVOURITES)
        assertEquals(songs.map { it.id }.toSet(), favourites.map { LibraryTree.parse(it.mediaId)!!.second }.toSet())
        val first = favourites.first()
        assertTrue(first.mediaId.startsWith("${LibraryTree.FAVOURITES}::"))
        assertTrue(first.mediaMetadata.isPlayable == true)
        val art = first.mediaMetadata.artworkUri!!
        assertEquals("content", art.scheme)
        assertTrue(art.authority!!.endsWith(".artwork"))

        // Paged the way Android Auto asks for long lists.
        assertEquals(2, children(LibraryTree.FAVOURITES, page = 0, pageSize = 2).size)
        assertEquals(1, children(LibraryTree.FAVOURITES, page = 1, pageSize = 2).size)
        assertEquals(0, children(LibraryTree.FAVOURITES, page = 5, pageSize = 2).size)

        val playlist = children(LibraryTree.PLAYLISTS).single()
        assertEquals("Road Trip", playlist.mediaMetadata.title.toString())
        assertEquals(songs.take(2).map { it.id }, children(playlist.mediaId).map { LibraryTree.parse(it.mediaId)!!.second })

        val looked = await(browser.getItem(first.mediaId)).value
        assertEquals(first.mediaId, looked!!.mediaId)
    }

    @Test
    fun pickingASongPlaysItsWholeListFromThere() {
        val playlist = children(LibraryTree.PLAYLISTS).single()
        val second = children(playlist.mediaId)[1]
        // Android Auto sends just the id of the song that was tapped.
        browser.setMediaItem(MediaItem.Builder().setMediaId(second.mediaId).build())
        runMainLooperUntil { browser.mediaItemCount == 2 }
        assertEquals(1, browser.currentMediaItemIndex)
        assertEquals(songs[1].id, browser.currentMediaItem!!.mediaId)
        assertEquals(songs[0].id, browser.getMediaItemAt(0).mediaId)
        assertEquals("Song 2", browser.currentMediaItem!!.mediaMetadata.title.toString())

        // Adding a browsed song to the queue adds that song, not the browse id.
        val favourite = children(LibraryTree.FAVOURITES).first()
        browser.addMediaItem(MediaItem.Builder().setMediaId(favourite.mediaId).build())
        // The browser shows the request at once; the service's resolved song replaces it.
        runMainLooperUntil { browser.mediaItemCount == 3 && "::" !in browser.getMediaItemAt(2).mediaId }
        assertEquals(LibraryTree.parse(favourite.mediaId)!!.second, browser.getMediaItemAt(2).mediaId)
        assertEquals(favourite.mediaMetadata.title, browser.getMediaItemAt(2).mediaMetadata.title)
    }

    @Test
    fun liveChartsSearchVoiceAndArtwork() {
        assumeTrue(System.getProperty("live") == "true")
        val charts = children(LibraryTree.CHARTS)
        assertEquals(2, charts.size)
        val top = children(charts.first().mediaId)
        assertTrue("global chart has songs", top.size >= 50)
        println("Car chart: " + top.take(3).map { "${it.mediaMetadata.title} - ${it.mediaMetadata.artist}" })

        // The car can load the artwork through the provider.
        val art = top.first().mediaMetadata.artworkUri!!
        val bytes = app.contentResolver.openInputStream(art)!!.use { it.readBytes() }
        assertTrue("artwork is a JPEG (${bytes.size} bytes)", bytes.size > 5_000 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte())

        // Typed search.
        await(browser.search("coldplay yellow", null))
        runMainLooperUntil({ searchDone }, 30_000, androidx.media3.common.util.Clock.DEFAULT)
        val results = await(browser.getSearchResult("coldplay yellow", 0, 50, null)).value.orEmpty()
        println("Car search: " + results.take(3).map { "${it.mediaMetadata.title} - ${it.mediaMetadata.artist}" })
        assertTrue("search finds songs", results.isNotEmpty())

        // "Hey Google, play coldplay yellow on Tempo".
        browser.setMediaItem(
            MediaItem.Builder().setRequestMetadata(
                MediaItem.RequestMetadata.Builder().setSearchQuery("coldplay yellow").build(),
            ).build(),
        )
        runMainLooperUntil({ browser.mediaItemCount > 1 }, 30_000, androidx.media3.common.util.Clock.DEFAULT)
        println("Voice queue starts with: ${browser.currentMediaItem!!.mediaMetadata.title} - ${browser.currentMediaItem!!.mediaMetadata.artist}")
        assertEquals(results.first().mediaMetadata.title, browser.currentMediaItem!!.mediaMetadata.title)
    }

    @Volatile private var searchDone = false

    @Test
    fun artworkProviderOnlyServesYouTubeImages() {
        assertTrue(ArtworkProvider.isAllowed("https://i.ytimg.com/vi/abc/hq720.jpg"))
        assertTrue(ArtworkProvider.isAllowed("https://lh3.googleusercontent.com/abc=w600-h600"))
        assertTrue(ArtworkProvider.isAllowed("https://yt3.ggpht.com/abc=s600"))
        assertFalse(ArtworkProvider.isAllowed("http://i.ytimg.com/vi/abc/hq720.jpg"))
        assertFalse(ArtworkProvider.isAllowed("https://example.com/ytimg.com.jpg"))
        assertFalse(ArtworkProvider.isAllowed("https://ytimg.com.example.com/a.jpg"))
        assertFalse(ArtworkProvider.isAllowed("file:///data/data/app.tempo.music/databases/music.db"))
    }
}
