package com.avidviewer.tv

import com.avidviewer.tv.data.AccountApi
import com.avidviewer.tv.data.Updater
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Beta channel (Nov 18, 2026): chosen devices get GitHub pre-releases tagged beta-v<version>-<n>.<build>. */
@RunWith(RobolectricTestRunner::class)
class BetaChannelTest {
    private fun release(tag: String) = Updater.parse(JSONObject().put("tag_name", tag).put("body", "")
        .put("assets", org.json.JSONArray().put(JSONObject().put("name", "AvidViewer.apk").put("browser_download_url", "https://example.com/a.apk").put("size", 10))))!!

    @Test
    fun betaTagsReadAsBetaVersionsWithTheBuildLast() {
        assertEquals("1.20.0 beta 2", Updater.displayVersion("beta-v1.20.0-2.360"))
        assertTrue(Updater.isBetaTag("beta-v1.20.0-2.360"))
        assertFalse(Updater.isBetaTag("v1.20.0.360"))
        val r = release("beta-v1.20.0-2.360")
        assertEquals(360L, r.versionCode)
        assertEquals("1.20.0 beta 2", r.versionName)
        assertTrue(Updater.isBetaVersion(r.versionName))
        assertFalse(Updater.isBetaVersion("1.19.0"))
    }

    @Test
    fun theNewestOfPublicAndBetaIsOfferedAndLeavingKeepsTheBetaUntilPublicCatchesUp() {
        val pub = release("v1.19.0.348")
        val beta = release("beta-v1.20.0-1.355")
        // In the beta, on 1.19.0: the beta is offered.
        assertEquals(355L, Updater.pick(pub, beta, 348)?.versionCode)
        // Public devices never get the beta.
        assertNull(Updater.pick(pub, null, 348))
        // Left the beta, still on the beta build: nothing until a public version is newer...
        assertNull(Updater.pick(pub, null, 355))
        // ...then the public version (built later, so its number is higher).
        assertEquals(361L, Updater.pick(release("v1.20.0.361"), null, 355)?.versionCode)
        // In the beta when the public release has caught up: the public one.
        assertEquals(361L, Updater.pick(release("v1.20.0.361"), beta, 355)?.versionCode)
    }

    @Test
    fun theAccountSaysWhetherThisDeviceIsInTheBeta() {
        FakeAvidSite().use { site ->
            assertFalse(AccountApi.me("TVTOKEN_1", "1.19.0", site.base)!!.beta)
            site.beta = """{"in":true,"tag":"beta-v1.20.0-1.355"}"""
            val m = AccountApi.me("TVTOKEN_1", "1.19.0", site.base)!!
            assertTrue(m.beta); assertEquals("beta-v1.20.0-1.355", m.betaTag)
            site.beta = """{"in":true,"tag":null}"""
            assertNull(AccountApi.me("TVTOKEN_1", "1.19.0", site.base)!!.betaTag)
            site.beta = """{"in":false,"tag":null}"""
            assertFalse(AccountApi.me("TVTOKEN_1", "1.19.0", site.base)!!.beta)
        }
    }
}

/** Plain JUnit (no Android needed). */
class BetaTextTest {
    @Test
    fun settingsExplainTheBetaAndHowToLeave() {
        assertTrue(Updater.betaText(true).contains("avidviewer.app/devices"))
        assertTrue(Updater.betaText(false).contains("left the beta"))
    }
}
