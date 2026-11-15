package com.avidviewer.tv

import com.avidviewer.tv.data.SearchText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nov 12: "Farg" found nothing while "Fargo" did; searches now take part of a word, any order. */
class SearchTextTest {
    @Test
    fun partOfAWordAnyOrderNoAccentsOrPunctuation() {
        fun m(text: String, q: String) = SearchText.matches(text, SearchText.words(q))
        assertTrue(m("US: WDAY ABC 6 Fargo HD", "farg"))
        assertTrue(m("US: WDAY ABC 6 Fargo HD", "Fargo abc"))
        assertTrue(m("US: WDAY ABC 6 Fargo HD", "abc  FARG"))
        assertTrue(m("Café Society (2016)", "cafe soc"))
        assertTrue(m("Grey's Anatomy", "greys"))
        assertTrue(m("Spider-Man: No Way Home", "spider man home"))
        assertTrue(m("Spider-Man: No Way Home", "spiderman"))
        assertFalse(m("US: WDAY ABC 6 Fargo HD", "fargo nbc"))
        assertFalse(m("", "farg"))
        assertEquals(listOf("fargo", "abc"), SearchText.words("ABC, Fargo!"))
        assertEquals("fargo", SearchText.lookup("abc fargo"))
    }

    /** #16 follow-up (Nov 14): searches were too strict; a typo or a word left out now finds near matches, ranked last. */
    @Test
    fun nearMatchesForTyposAndMissingWordsRankedAfterExactOnes() {
        fun s(text: String, q: String) = SearchText.score(text, SearchText.prepare(q))
        assertEquals(SearchText.PHRASE, s("The Office (US)", "the office"))
        assertEquals(SearchText.WORD_STARTS, s("Spider-Man: No Way Home", "spiderman"))
        assertEquals(SearchText.WORD_STARTS, s("KVLY Fargo News HD", "news farg"))
        assertEquals(SearchText.ALL_WORDS, s("Grey's Anatomy", "natomy"))
        // Typos: one slip in a short word, two in a long one; a word typed only partly still counts.
        assertEquals(SearchText.NEAR, s("The Office", "the ofice"))
        assertEquals(SearchText.NEAR, s("Breaking Bad", "breking bad"))
        assertEquals(SearchText.NEAR, s("The Godfather Part II", "godfathr"))
        assertEquals(SearchText.NEAR, s("WDAY ABC 3 Fargo", "wdya farg"))
        assertEquals(SearchText.NEAR, s("Drama Movie 1", "drma movie"))
        // A word left out: from three or more words, or a one- or two-letter word.
        assertEquals(SearchText.NEAR, s("The Office", "the office us"))
        assertEquals(SearchText.NEAR, s("Yellowstone", "yellowstone tv"))
        // Not near: the longest word must be there; numbers are exact; two-word searches keep both words.
        assertEquals(0, s("WDAY ABC 6 Fargo HD", "fargo nbc"))
        assertEquals(0, s("ABC 3", "abc 6"))
        assertEquals(0, s("Rocky 4", "rocky 3"))
        assertEquals(0, s("The Office", "zzzz"))
        assertEquals(0, s("Cheers", "office"))
        assertEquals(0, s("", "office"))
        assertTrue(SearchText.near("Café Society", SearchText.prepare("cafe societi")))
        assertFalse(SearchText.near("Sports Center", SearchText.prepare("spurtz")))
        // Database candidates: the longest word's first and last three letters.
        assertEquals(listOf("%bre%", "%ing%"), SearchText.nearPatterns(SearchText.prepare("breking bad")))
    }
}
