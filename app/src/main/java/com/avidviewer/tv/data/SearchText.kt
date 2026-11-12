package com.avidviewer.tv.data

import java.text.Normalizer

/**
 * Forgiving search: what's typed is split into words, and a name matches when it contains every
 * word, in any order, as part of a word or whole ("farg" finds "Fargo", "news fargo" finds
 * "KVLY Fargo News HD"). Case, accents and punctuation don't matter ("cafe" finds "Café").
 */
object SearchText {
    private val MARKS = Regex("\\p{Mn}+")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val APOSTROPHES = Regex("['’‘`´]")

    /** Lower case, no accents, punctuation turned into spaces. */
    fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARKS, "").replace(APOSTROPHES, "").replace(NON_ALNUM, " ").trim()

    /** The words of a search, longest first (the longest narrows a database lookup the most). */
    fun words(q: String): List<String> = fold(q).split(' ').filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }

    /** True when [text] contains every one of [words] (from [words]). */
    fun matches(text: String?, words: List<String>): Boolean {
        if (text.isNullOrBlank() || words.isEmpty()) return false
        val f = fold(text)
        val joined = f.replace(" ", "") // "spiderman" finds "Spider-Man"
        return words.all { f.contains(it) || joined.contains(it) }
    }

    /**
     * What a database LIKE can look for to find candidates: the longest word, without accents
     * (a name with accents is still found by its other letters when the word has none).
     */
    fun lookup(q: String): String = words(q).firstOrNull() ?: q.trim()
}
