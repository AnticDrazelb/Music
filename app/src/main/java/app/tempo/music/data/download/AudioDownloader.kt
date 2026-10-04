package app.tempo.music.data.download

import app.tempo.music.data.youtube.StreamRequests
import app.tempo.music.data.youtube.StreamResolver
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class DownloadCancelledException : IOException("Download cancelled")

/**
 * Downloads a song's audio to a file in ranged chunks, so progress can be reported and an
 * expired stream URL can be refreshed mid-download without starting over.
 */
class AudioDownloader(
    private val client: OkHttpClient,
    private val resolver: StreamResolver,
    private val chunkSize: Long = 2L * 1024 * 1024,
) {

    /** Blocking. Returns the finished file in [dir], named `<videoId>.<ext>`. */
    @Throws(IOException::class)
    fun download(
        videoId: String,
        dir: File,
        isCancelled: () -> Boolean = { false },
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File {
        dir.mkdirs()
        var resolved = resolver.resolveForDownload(videoId)
        val part = File(dir, "$videoId.part").apply { delete() }
        var position = 0L
        var total = -1L
        var requestNumber = 1
        var refreshes = 0

        try {
            RandomAccessFile(part, "rw").use { out ->
                while (total < 0 || position < total) {
                    if (isCancelled()) throw DownloadCancelledException()
                    val request = Request.Builder()
                        .url(StreamRequests.withRequestNumber(resolved.url, requestNumber++))
                        .post(StreamRequests.POST_BODY.toRequestBody())
                        .apply { StreamRequests.headers(resolved.url).forEach { (k, v) -> header(k, v) } }
                        .header("Range", "bytes=$position-${position + chunkSize - 1}")
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.code == 403 || response.code == 410) {
                            // Signed URL expired or was revoked: get a fresh one and resume.
                            if (++refreshes > 3) throw IOException("YouTube refused the download (HTTP ${response.code})")
                            resolved = resolver.resolveForDownload(videoId)
                            return@use
                        }
                        if (!response.isSuccessful) throw IOException("Download failed (HTTP ${response.code})")

                        if (response.code == 200) {
                            // Server ignored the range and is sending the whole file.
                            position = 0
                            out.setLength(0)
                            total = response.body.contentLength()
                        } else {
                            total = parseTotal(response.header("Content-Range")) ?: total
                        }
                        out.seek(position)
                        val buffer = ByteArray(64 * 1024)
                        response.body.byteStream().use { input ->
                            while (true) {
                                if (isCancelled()) throw DownloadCancelledException()
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                position += n
                                onProgress(position, total)
                            }
                        }
                        if (response.code == 200 && total < 0) total = position
                    }
                }
            }
        } catch (e: IOException) {
            part.delete()
            throw e
        }

        if (position == 0L) {
            part.delete()
            throw IOException("Downloaded file was empty")
        }
        val target = File(dir, "$videoId.${StreamRequests.extensionFor(resolved.mimeType)}")
        target.delete()
        if (!part.renameTo(target)) throw IOException("Couldn't save the download")
        return target
    }

    private fun parseTotal(contentRange: String?): Long? =
        contentRange?.substringAfterLast('/', "")?.toLongOrNull()
}
