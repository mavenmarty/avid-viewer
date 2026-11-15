package com.avidviewer.tv.data

import java.text.Normalizer

/**
 * Forgiving search: what's typed is split into words, and a name matches when it contains every
 * word, in any order, as part of a word or whole ("farg" finds "Fargo", "news fargo" finds
 * "KVLY Fargo News HD"). Case, accents and punctuation don't matter ("cafe" finds "Café").
 *
 * Near matches (1.6.1): [score] also accepts a small typo in a word ("ofice", "breking bad") and,
 * for longer searches, a word that isn't there ("the office us" finds "The Office"). They rank
 * after names that hold every word, which rank after names that hold the search as typed.
 */
object SearchText {
    private val MARKS = Regex("\\p{Mn}+")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val APOSTROPHES = Regex("['’‘`´]")

    /** Lower case, no accents, punctuation turned into spaces. */
    fun fold(s: String): String {
        val lower = s.lowercase()
        // Most names are plain ASCII: skip the (slow) accent removal for them.
        val plain = if (lower.all { it.code < 128 }) lower else Normalizer.normalize(lower, Normalizer.Form.NFD).replace(MARKS, "")
        return plain.replace(APOSTROPHES, "").replace(NON_ALNUM, " ").trim()
    }

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

    // ------------------------------------------------------------------ ranked, forgiving matching

    /** A search prepared once for [score]: its words (longest first) and the folded phrase as typed. */
    class Prepared internal constructor(raw: String) {
        val phrase: String = fold(raw).split(' ').filter { it.isNotBlank() }.joinToString(" ")
        val words: List<String> = words(raw)
        val isEmpty get() = words.isEmpty()
    }

    fun prepare(q: String) = Prepared(q)

    /** [score] results, best first. */
    const val PHRASE = 4
    const val WORD_STARTS = 3
    const val ALL_WORDS = 2
    const val NEAR = 1

    /**
     * How well [text] matches [q]: 0 not at all; [NEAR] a typo or (for 3+ words, or a 1–2 letter word)
     * one word missing; [ALL_WORDS] every word inside; [WORD_STARTS] every word starts a word;
     * [PHRASE] the search as typed starts a word ("the office" in "The Office (US)").
     */
    fun score(text: String?, q: Prepared): Int {
        if (text.isNullOrBlank() || q.isEmpty) return 0
        val f = fold(text)
        if (f.isEmpty()) return 0
        val padded = " $f"
        if (q.phrase.isNotEmpty() && padded.contains(" " + q.phrase)) return PHRASE
        val joined = f.replace(" ", "")
        var all = true
        var starts = true
        for (w in q.words) {
            if (!(f.contains(w) || joined.contains(w))) { all = false; break }
            if (!padded.contains(" $w") && !joined.startsWith(w)) starts = false
        }
        if (all) return if (starts) WORD_STARTS else ALL_WORDS
        // Near: every word found (a typo allowed), or one word missing.
        val tokens = f.split(' ')
        var missing = 0
        for ((i, w) in q.words.withIndex()) {
            if (f.contains(w) || joined.contains(w) || tokens.any { close(w, it) }) continue
            missing++
            // The longest word must be there; another may be left out only from a longer search, or when it's tiny.
            // A number is never left out ("abc 6" shouldn't offer ABC 3).
            if (i == 0 || missing > 1 || w.all { it.isDigit() } || !(q.words.size >= 3 || w.length <= 2)) return 0
        }
        return NEAR
    }

    /** [score] > 0. */
    fun near(text: String?, q: Prepared): Boolean = score(text, q) > 0

    /**
     * True when the typed [w] is one small slip from (the start of) [token]: one letter wrong, missing,
     * extra or swapped for words of 4–7 letters, two for longer ones. Numbers must be exact, and the
     * first letter must agree (or be swapped with the second), which keeps this quick over a whole guide.
     */
    internal fun close(w: String, token: String): Boolean {
        if (w.length < 4 || token.length < 3) return false
        if (w.all { it.isDigit() }) return false
        val firstOk = token[0] == w[0] || (token[0] == w[1] && token[1] == w[0])
        if (!firstOk) return false
        val budget = if (w.length >= 8) 2 else 1
        val lo = maxOf(3, w.length - budget)
        val hi = minOf(token.length, w.length + budget)
        for (len in lo..hi) if (distance(w, token, len, budget) <= budget) return true
        return false
    }

    /** Edit distance (with swaps) between [a] and the first [len] letters of [b], stopping early past [max]. */
    private fun distance(a: String, b: String, len: Int, max: Int): Int {
        val n = a.length; val m = len
        if (kotlin.math.abs(n - m) > max) return max + 1
        var prev2 = IntArray(m + 1)
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[m]
    }

    /**
     * LIKE patterns that find the database rows [score] may accept: the longest word in full when it's
     * short, else its first three and last three letters (a typo usually leaves one of them whole).
     */
    fun nearPatterns(q: Prepared): List<String> {
        val w = q.words.firstOrNull() ?: return emptyList()
        val parts = if (w.length < 4 || w.all { it.isDigit() }) listOf(w) else listOf(w.take(3), w.takeLast(3)).distinct()
        return parts.map { like(it) }
    }

    /** A LIKE pattern for "contains [s]" (with ESCAPE '\'). */
    fun like(s: String) = "%" + s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
}
