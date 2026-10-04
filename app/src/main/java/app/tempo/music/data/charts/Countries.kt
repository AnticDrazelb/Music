package app.tempo.music.data.charts

import java.util.Locale

/** Countries with official YouTube music charts (from charts.youtube.com). */
object Countries {
    val codes: List<String> = listOf(
        "AE", "AR", "AT", "AU", "BE", "BO", "BR", "CA", "CH", "CL", "CO", "CR", "CZ", "DE",
        "DK", "DO", "EC", "EE", "EG", "ES", "FI", "FR", "GB", "GT", "HN", "HU", "ID", "IE",
        "IL", "IN", "IS", "IT", "JP", "KE", "KR", "LU", "MX", "NG", "NI", "NL", "NO", "NZ",
        "PA", "PE", "PL", "PT", "PY", "RO", "RS", "RU", "SA", "SE", "SV", "TR", "TZ", "UA",
        "UG", "US", "UY", "ZA", "ZW",
    )

    fun name(code: String): String = when {
        code.equals(ChartsRepository.GLOBAL, ignoreCase = true) -> "Global"
        else -> Locale.Builder().setRegion(code.uppercase()).build().getDisplayCountry(Locale.getDefault())
            .ifBlank { code.uppercase() }
    }

    fun flag(code: String): String {
        if (code.equals(ChartsRepository.GLOBAL, ignoreCase = true) || code.length != 2) return "🌍"
        val upper = code.uppercase()
        val first = Character.codePointAt(upper, 0) - 'A'.code + 0x1F1E6
        val second = Character.codePointAt(upper, 1) - 'A'.code + 0x1F1E6
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    /** All countries sorted by display name. */
    fun sorted(): List<String> = codes.sortedBy { name(it) }

    fun defaultFor(locale: Locale = Locale.getDefault()): String {
        val country = locale.country.uppercase()
        return if (country in codes) country else "US"
    }
}
