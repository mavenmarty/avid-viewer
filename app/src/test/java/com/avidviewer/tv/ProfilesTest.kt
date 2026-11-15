package com.avidviewer.tv

import com.avidviewer.tv.data.Kids
import com.avidviewer.tv.data.Prefs
import com.avidviewer.tv.data.Profile
import com.avidviewer.tv.data.ProfileSet
import com.avidviewer.tv.data.personalOf
import com.avidviewer.tv.data.withPersonal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfilesTest {
    @Test
    fun pinsAreHashedAndEachProfileHasItsOwn() {
        val owner = Profile(Profile.OWNER, "Marty").withPin("1234")
        val sam = Profile("sam", "Sam").withPin("5678")
        val kids = Profile("kids", "Kids", kids = true)
        assertFalse("the PIN isn't stored as text", owner.pinHash.contains("1234"))
        assertTrue(owner.pinMatches("1234")); assertFalse(owner.pinMatches("5678"))
        val set = ProfileSet(listOf(owner, sam, kids))
        assertFalse(set.needsPin(kids))
        assertTrue(set.needsPin(sam))
        assertTrue("own PIN", set.unlocks(sam, "5678"))
        assertTrue("a parent's PIN", set.unlocks(sam, "1234"))
        assertFalse("Sam's PIN doesn't open the owner", set.unlocks(owner, "5678"))
        // Without a Kids profile, a profile without a PIN opens freely.
        assertFalse(ProfileSet(listOf(owner, Profile("guest", "Guest"))).needsPin(Profile("guest", "Guest")))
        assertTrue("…but with one, it needs a parent's", set.needsPin(Profile("guest", "Guest")))
        val back = ProfileSet.fromJson(set.toJson())
        assertEquals(set, back)
    }

    @Test
    fun personalFieldsSwapAndSharedOnesStay() {
        val a = Prefs(favorites = listOf("x"), hiddenChannels = setOf("h"), channelNames = mapOf("x" to "Mine"), timeZone = "America/Chicago")
        val b = Prefs(favorites = listOf("y"), timeZone = "UTC")
        val mixed = a.withPersonal(personalOf(b))
        assertEquals(listOf("y"), mixed.favorites)
        assertTrue(mixed.hiddenChannels.isEmpty())
        assertEquals("shared settings are kept", "America/Chicago", mixed.timeZone)
        assertEquals(mapOf("x" to "Mine"), mixed.channelNames)
    }

    @Test
    fun adultAndKidsCategoryNames() {
        assertTrue(Kids.isAdult("XXX | Adults")); assertTrue(Kids.isAdult("18+ Movies")); assertFalse(Kids.isAdult("Essex News"))
        assertTrue(Kids.looksForKids("KIDS")); assertTrue(Kids.looksForKids("EN | Cartoons")); assertTrue(Kids.looksForKids("Family Movies"))
        assertFalse(Kids.looksForKids("NEWS")); assertFalse(Kids.looksForKids("Kids XXX"))
    }

    /** Themes (1.4.0): profiles from before keep Midnight blue; a new install starts on Dark roast; the choice is saved. */
    @Test
    fun existingProfilesKeepMidnightAndNewInstallsGetDarkRoast() {
        val old = ProfileSet.fromJson(org.json.JSONObject(ProfileSet(listOf(Profile(Profile.OWNER, "Me"), Profile("p2", "Sam"))).toJson().toString().replace("\"theme\":\"\",", "")))
        assertTrue(old.list.all { it.theme.isEmpty() })
        assertEquals(listOf("midnight", "midnight"), old.withThemes(existingInstall = true)!!.list.map { it.theme })
        assertEquals(listOf("roast"), ProfileSet().withThemes(existingInstall = false)!!.list.map { it.theme })
        val chosen = old.withThemes(true)!!.let { s -> s.copy(list = s.list.map { if (it.id == "p2") it.copy(theme = "latte", vodLayout = "showcase") else it }) }
        assertEquals(null, chosen.withThemes(true))
        val back = ProfileSet.fromJson(chosen.toJson())
        assertEquals("latte", back.get("p2")!!.theme)
        assertEquals("showcase", back.get("p2")!!.vodLayout)
        assertEquals("midnight", back.owner.theme)
    }
}
