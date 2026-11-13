package com.avidviewer.tv.data

/**
 * The language a provider marks a title or channel with, from the code on its name or category
 * ("EN | Netflix Series", "FR - MobLand", "Séries VF", "4K-MULTI"). Used to put English first.
 */
object Language {
    private val CODE = Regex("""^\s*[\[(]?\s*([a-z]{2,5})\s*[\])]?\s*[|:\-]""", RegexOption.IGNORE_CASE)
    private val ENGLISH_CODES = setOf("en", "eng", "us", "usa", "uk", "gb", "ca", "au", "nz", "ie")
    private val LABELS = mapOf(
        "en" to "English", "eng" to "English", "us" to "English", "usa" to "English", "uk" to "English", "gb" to "English", "ca" to "English", "au" to "English", "nz" to "English", "ie" to "English",
        "fr" to "French", "vf" to "French", "vff" to "French", "vostfr" to "French subtitles", "de" to "German", "ger" to "German", "es" to "Spanish", "spa" to "Spanish", "lat" to "Spanish (Latin America)",
        "it" to "Italian", "ita" to "Italian", "pt" to "Portuguese", "br" to "Portuguese (Brazil)", "nl" to "Dutch", "pl" to "Polish", "tr" to "Turkish", "ar" to "Arabic", "ru" to "Russian",
        "in" to "Hindi", "hi" to "Hindi", "sv" to "Swedish", "se" to "Swedish", "no" to "Norwegian", "dk" to "Danish", "fi" to "Finnish", "gr" to "Greek", "ro" to "Romanian", "hu" to "Hungarian",
        "cz" to "Czech", "al" to "Albanian", "ex" to "Ex-Yu", "multi" to "Multi-language", "mul" to "Multi-language",
    )
    private val WORDS = mapOf(
        "english" to "English", "french" to "French", "francais" to "French", "français" to "French", "vf" to "French", "vostfr" to "French subtitles",
        "german" to "German", "deutsch" to "German", "spanish" to "Spanish", "espanol" to "Spanish", "español" to "Spanish", "latino" to "Spanish (Latin America)",
        "italian" to "Italian", "italiano" to "Italian", "portuguese" to "Portuguese", "arabic" to "Arabic", "turkish" to "Turkish", "hindi" to "Hindi", "multi" to "Multi-language",
    )

    /** "English", "French", … or null when nothing says. */
    fun of(name: String, category: String?): String? = code(name) ?: category?.let { code(it) ?: word(it) } ?: word(name)

    private fun code(s: String) = CODE.find(s)?.groupValues?.get(1)?.lowercase()?.let { LABELS[it] }

    /** All the words in one pattern, made once (it was made again for every word of every title). */
    private val WORD = Regex("(?:^|[^a-zà-ÿ])(" + WORDS.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")(?=$|[^a-zà-ÿ])")

    private fun word(s: String): String? = WORD.find(s.lowercase())?.groupValues?.get(1)?.let { WORDS[it] }

    /** English first, then unmarked (often English), then multi-language, then everything else. */
    fun rank(lang: String?): Int = when (lang) { "English" -> 0; null -> 1; "Multi-language" -> 2; else -> 3 }
}
