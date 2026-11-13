package com.avidviewer.tv

import com.avidviewer.tv.data.Updater
import org.junit.Assert.assertEquals
import org.junit.Test

/** Release tags: v1.0.N up to 1.0.179, then v<major>.<minor>.<patch>.<build>. */
class VersionTagTest {
    @Test
    fun semanticTagsShowTheVersionAndKeepTheBuildLast() {
        assertEquals("1.1.0", Updater.displayVersion("v1.1.0.190"))
        assertEquals("2.0.3", Updater.displayVersion("v2.0.3.412"))
        assertEquals("1.0.179", Updater.displayVersion("v1.0.179"))
        // What 1.0.179 and older read as the build number: the last part, so they still see updates.
        assertEquals(190L, "v1.1.0.190".substringAfterLast('.').toLong())
    }
}
