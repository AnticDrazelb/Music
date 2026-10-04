package app.tempo.music.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.tempo.music.BuildConfig
import app.tempo.music.TempoApp
import app.tempo.music.data.youtube.ArtworkUrls
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Serves artwork to Android Auto, which only loads content:// URIs, not web addresses. Images
 * are fetched once and kept in the cache. Only YouTube's image hosts are served, so this can't be
 * used to fetch anything else.
 */
class ArtworkProvider : ContentProvider() {

    companion object {
        private const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.artwork"
        private val allowedHosts = listOf("googleusercontent.com", "ggpht.com", "ytimg.com")

        fun uriFor(imageUrl: String): Uri =
            Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("image")
                .appendQueryParameter("url", imageUrl)
                .build()

        internal fun isAllowed(url: String): Boolean {
            val host = Uri.parse(url).host.orEmpty()
            return url.startsWith("https://") && allowedHosts.any { host == it || host.endsWith(".$it") }
        }
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val url = uri.getQueryParameter("url") ?: throw FileNotFoundException(uri.toString())
        // Sharper sizes first; the last candidate always exists.
        val candidates = ArtworkUrls.candidates(url, 600).filter(::isAllowed)
        if (candidates.isEmpty()) throw FileNotFoundException(url)
        val context = context ?: throw FileNotFoundException(url)
        val file = File(context.cacheDir, "car-artwork/${sha1(url)}.jpg")
        if (!file.exists() && candidates.none { download(it, file) }) throw FileNotFoundException(url)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun download(url: String, file: File): Boolean = runCatching {
        val client = (context?.applicationContext as? TempoApp)?.container?.httpClient ?: return false
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return false
            file.parentFile?.mkdirs()
            val partial = File(file.parentFile, "${file.name}.${Thread.currentThread().id}.part")
            partial.outputStream().use { response.body.byteStream().copyTo(it) }
            partial.renameTo(file)
        }
    }.getOrDefault(false)

    private fun sha1(text: String) = MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
