package app.tempo.music.ui.screens.browse

import androidx.compose.ui.graphics.Color

data class Genre(
    val id: String,
    val title: String,
    /** Query used to find playlists. */
    val playlistQuery: String,
    /** Query used to find top songs. */
    val songQuery: String,
    val colors: List<Color>,
)

object Genres {
    private fun g(id: String, title: String, playlists: String, songs: String, a: Long, b: Long) =
        Genre(id, title, playlists, songs, listOf(Color(a), Color(b)))

    val genres = listOf(
        g("pop", "Pop", "pop hits playlist", "pop hits", 0xFFFF6FA5, 0xFFE0115F),
        g("hiphop", "Hip-Hop/Rap", "hip hop rap hits playlist", "hip hop hits", 0xFFFFB13D, 0xFFE2580C),
        g("rnb", "R&B", "r&b hits playlist", "r&b soul hits", 0xFFB388FF, 0xFF6A1B9A),
        g("dance", "Dance/Electronic", "dance electronic hits playlist", "edm dance hits", 0xFF2FB8FF, 0xFF1560D6),
        g("rock", "Rock", "rock classics playlist", "rock hits", 0xFF90A4AE, 0xFF263238),
        g("alt", "Alternative", "alternative indie hits playlist", "alternative indie songs", 0xFF3DDC97, 0xFF0B7A5C),
        g("country", "Country", "country hits playlist", "country hits", 0xFFFFD54F, 0xFFD87A00),
        g("latin", "Latin", "latin hits playlist", "reggaeton latin hits", 0xFFFF8A65, 0xFFC62828),
        g("kpop", "K-Pop", "kpop hits playlist", "kpop hits", 0xFFFF6FD8, 0xFF8E24AA),
        g("afro", "Afrobeats", "afrobeats hits playlist", "afrobeats hits", 0xFF66BB6A, 0xFF1B5E20),
        g("jazz", "Jazz", "jazz classics playlist", "jazz classics", 0xFF5B6CFF, 0xFF1C2D9E),
        g("classical", "Classical", "classical music essentials", "classical music masterpieces", 0xFFBCAAA4, 0xFF5D4037),
        g("metal", "Metal", "metal essentials playlist", "heavy metal songs", 0xFF757575, 0xFF101010),
        g("reggae", "Reggae", "reggae classics playlist", "reggae hits", 0xFFFFEE58, 0xFF2E7D32),
        g("soul", "Soul & Funk", "soul funk classics playlist", "soul funk classics", 0xFFFFA726, 0xFF6D4C41),
        g("indian", "Bollywood", "bollywood hits playlist", "bollywood hits", 0xFFFF7043, 0xFFAD1457),
    )

    val moods = listOf(
        g("chill", "Chill", "chill vibes playlist", "chill songs", 0xFF4DD0E1, 0xFF00838F),
        g("workout", "Workout", "workout music playlist", "workout songs", 0xFFFF5252, 0xFFB71C1C),
        g("party", "Party", "party hits playlist", "party songs", 0xFFE040FB, 0xFF4A148C),
        g("focus", "Focus", "focus music study playlist", "lofi study music", 0xFF81C784, 0xFF33691E),
        g("sleep", "Sleep", "sleep music playlist", "relaxing sleep music", 0xFF7986CB, 0xFF1A237E),
        g("romance", "Romance", "love songs playlist", "love songs", 0xFFF48FB1, 0xFFC2185B),
        g("feelgood", "Feel Good", "feel good songs playlist", "feel good songs", 0xFFFFCA28, 0xFFF57C00),
        g("sad", "Heartbreak", "sad songs playlist", "sad songs", 0xFF78909C, 0xFF37474F),
        g("roadtrip", "Road Trip", "road trip songs playlist", "road trip songs", 0xFF4FC3F7, 0xFF01579B),
        g("throwback", "Throwback", "throwback hits playlist", "throwback hits", 0xFFFFAB91, 0xFFBF360C),
        g("90s", "90s", "90s hits playlist", "90s hits", 0xFF26C6DA, 0xFF6A1B9A),
        g("80s", "80s", "80s hits playlist", "80s hits", 0xFFFF4081, 0xFF311B92),
    )

    fun byId(id: String): Genre? = (genres + moods).firstOrNull { it.id == id }
}
