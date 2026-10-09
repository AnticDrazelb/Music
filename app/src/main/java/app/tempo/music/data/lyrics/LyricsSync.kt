package app.tempo.music.data.lyrics

import android.content.Context

/**
 * How far each song's lyrics are moved to match its audio: the automatic shift found by
 * [LyricsAligner] (worked out once per song) plus any adjustment the listener makes.
 */
class LyricsSync(context: Context) {
    private val prefs = context.getSharedPreferences("lyrics_sync", Context.MODE_PRIVATE)

    /** The automatic shift, or null if the song hasn't been analysed yet. */
    fun automatic(songId: String): Long? = if (prefs.contains("auto:$songId")) prefs.getLong("auto:$songId", 0) else null

    fun setAutomatic(songId: String, offsetMs: Long) = prefs.edit().putLong("auto:$songId", offsetMs).apply()

    fun manual(songId: String): Long = prefs.getLong("manual:$songId", 0)

    fun setManual(songId: String, offsetMs: Long) = prefs.edit().apply {
        if (offsetMs == 0L) remove("manual:$songId") else putLong("manual:$songId", offsetMs)
    }.apply()
}
