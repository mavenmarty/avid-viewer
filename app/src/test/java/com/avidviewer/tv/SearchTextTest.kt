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
}
