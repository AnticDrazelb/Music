@file:OptIn(UnstableApi::class)

package app.tempo.music.playback.cast

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import app.tempo.music.playback.MediaItems
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A small HTTP server on the phone that a Cast device (TV, speaker) plays songs from.
 *
 * Cast devices fetch media themselves, but YouTube's audio URLs need the exact requests
 * [app.tempo.music.data.youtube.StreamRequests] makes, and downloaded songs only exist on the
 * phone. So the TV is given `http://<phone>:<port>/song/<id>?k=<key>` and this server answers it
 * through the player's own [DataSource]: from the download, the audio cache, or YouTube.
 * Seeking works through HTTP Range requests. The random key stops other devices on the network
 * using it.
 */
class CastStreamServer(private val dataSourceFactory: DataSource.Factory) {

    private val key = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val mimeTypes = ConcurrentHashMap<String, String>()
    private var socket: ServerSocket? = null
    private var executor: ExecutorService? = null

    /** Starts listening if needed; returns the port. */
    @Synchronized
    fun start(): Int {
        socket?.let { return it.localPort }
        val server = ServerSocket(0, 16, null as InetAddress?) // all interfaces, so the TV can reach it
        val pool = Executors.newCachedThreadPool { r -> Thread(r, "cast-stream").apply { isDaemon = true } }
        socket = server
        executor = pool
        pool.execute {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                pool.execute { client.use(::handle) }
            }
        }
        return server.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        executor?.shutdownNow()
        socket = null
        executor = null
    }

    /** Address of [videoId] for a device that reaches this phone at [host]. */
    fun urlFor(host: String, videoId: String): String = "http://$host:${start()}/song/$videoId?k=$key"

    /** The audio's MIME type, once it has been served or sniffed. */
    fun knownMimeType(videoId: String): String? = mimeTypes[videoId]

    private fun handle(client: Socket) {
        client.soTimeout = 30_000
        val output = BufferedOutputStream(client.getOutputStream(), 64 * 1024)
        try {
            val request = readRequest(client.getInputStream()) ?: return
            serve(request, output)
        } catch (_: SocketTimeoutException) {
        } catch (_: IOException) {
            // The receiver hung up (seek, skip or stop); nothing to report.
        }
        runCatching { output.flush() }
    }

    internal data class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>)

    private fun readRequest(input: InputStream): Request? {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        var total = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (++total > 16_384) return null
            if (b == '\n'.code) {
                val text = line.toString().trimEnd('\r')
                if (text.isEmpty()) break
                lines += text
                line.clear()
            } else {
                line.append(b.toChar())
            }
        }
        return parseRequest(lines)
    }

    private fun serve(request: Request, out: OutputStream) {
        if (request.method == "OPTIONS") {
            respond(out, "204 No Content", emptyMap())
            return
        }
        if (request.method != "GET" && request.method != "HEAD") {
            respond(out, "405 Method Not Allowed", emptyMap())
            return
        }
        val videoId = request.path.removePrefix("/song/")
        if (!request.path.startsWith("/song/") || !videoId.matches(VIDEO_ID) || request.query["k"] != key) {
            respond(out, "404 Not Found", emptyMap())
            return
        }

        val range = parseRange(request.headers["range"])
        val uri = MediaItems.uriFor(videoId)
        val mime = mimeTypes[videoId] ?: sniff(uri).also { mimeTypes[videoId] = it }
        val start = range?.first ?: 0L
        val requestedLength = range?.second?.let { it - start + 1 } ?: C.LENGTH_UNSET.toLong()

        val source = dataSourceFactory.createDataSource()
        try {
            val opened = try {
                source.open(DataSpec.Builder().setUri(uri).setPosition(start).setLength(requestedLength).setKey(videoId).build())
            } catch (e: IOException) {
                if (range != null && isRangeError(e)) {
                    respond(out, "416 Range Not Satisfiable", emptyMap())
                } else {
                    respond(out, "502 Bad Gateway", emptyMap())
                }
                return
            }
            val totalLength = if (range == null || range.second != null) {
                if (range == null && opened != C.LENGTH_UNSET.toLong()) opened else totalLength(uri, videoId)
            } else {
                if (opened != C.LENGTH_UNSET.toLong()) start + opened else C.LENGTH_UNSET.toLong()
            }

            val headers = linkedMapOf("Content-Type" to mime, "Accept-Ranges" to "bytes")
            if (opened != C.LENGTH_UNSET.toLong()) headers["Content-Length"] = opened.toString()
            val status = if (range != null) {
                val end = if (opened != C.LENGTH_UNSET.toLong()) start + opened - 1 else (totalLength - 1)
                val size = if (totalLength != C.LENGTH_UNSET.toLong()) totalLength.toString() else "*"
                headers["Content-Range"] = "bytes $start-$end/$size"
                "206 Partial Content"
            } else {
                "200 OK"
            }
            respond(out, status, headers)
            if (request.method == "HEAD") return

            val buffer = ByteArray(32 * 1024)
            while (true) {
                val n = source.read(buffer, 0, buffer.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, n)
            }
        } finally {
            runCatching { source.close() }
        }
    }

    private fun totalLength(uri: android.net.Uri, videoId: String): Long {
        val source = dataSourceFactory.createDataSource()
        return try {
            source.open(DataSpec.Builder().setUri(uri).setKey(videoId).build())
        } catch (_: IOException) {
            C.LENGTH_UNSET.toLong()
        } finally {
            runCatching { source.close() }
        }
    }

    /** Reads the file's first bytes to tell MP4 (AAC) from WebM (Opus). */
    private fun sniff(uri: android.net.Uri): String {
        val source = dataSourceFactory.createDataSource()
        val head = ByteArray(12)
        var read = 0
        try {
            source.open(DataSpec.Builder().setUri(uri).setLength(head.size.toLong()).setKey(uri.lastPathSegment).build())
            while (read < head.size) {
                val n = source.read(head, read, head.size - read)
                if (n == C.RESULT_END_OF_INPUT) break
                read += n
            }
        } catch (_: IOException) {
        } finally {
            runCatching { source.close() }
        }
        return mimeTypeOf(head.copyOf(read))
    }

    private fun isRangeError(e: IOException): Boolean =
        generateSequence<Throwable>(e) { it.cause }.any { it.javaClass.simpleName.contains("PositionOutOfRange") || it.message?.contains("416") == true }

    private fun respond(out: OutputStream, status: String, headers: Map<String, String>) {
        val text = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            if ("Content-Length" !in headers && !status.startsWith("2") || status.startsWith("204")) append("Content-Length: 0\r\n")
            // Cast receivers are web pages; let them read the audio across origins.
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: Range\r\n")
            append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(text.toByteArray(Charsets.ISO_8859_1))
    }

    internal companion object {
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{6,20}")

        fun parseRequest(lines: List<String>): Request? {
            val parts = lines.firstOrNull()?.split(' ') ?: return null
            if (parts.size < 2) return null
            val target = parts[1]
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "").split('&').filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            val headers = lines.drop(1).filter { ':' in it }
                .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            return Request(parts[0].uppercase(), path, query, headers)
        }

        /** "bytes=100-" or "bytes=100-199" as (start, end or null); suffix ranges aren't used by receivers. */
        fun parseRange(header: String?): Pair<Long, Long?>? {
            val spec = header?.trim()?.removePrefix("bytes=") ?: return null
            if (header.trim() == spec || ',' in spec) return null
            val start = spec.substringBefore('-').toLongOrNull() ?: return null
            val end = spec.substringAfter('-').takeIf { it.isNotBlank() }?.toLongOrNull()
            if (end != null && end < start) return null
            return start to end
        }

        fun mimeTypeOf(head: ByteArray): String = when {
            head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte() -> "audio/webm"
            head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> "audio/mp4"
            else -> "audio/mp4"
        }
    }
}
