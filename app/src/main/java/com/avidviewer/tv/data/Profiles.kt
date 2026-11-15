package com.avidviewer.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Who's watching. Every profile has its own favorites, lists, hidden items, My List, watch history,
 * resume points and last channel ([PERSONAL_KEYS]); everything else (playlists, guide sources and
 * matches, renames and order, Picture & sound, location…) is shared.
 *
 * The owner profile [OWNER] keeps its data where the app always kept it (prefs.json, history.json…),
 * so a TV with one profile is exactly as before. Other profiles keep theirs under profiles/<id>/.
 */
data class Profile(
    val id: String,
    val name: String,
    /** Index into [PROFILE_COLORS]. */
    val color: Int = 0,
    /** A Kids profile: only the categories in [allowedGroups] / [allowedVodCats], no PIN, no Settings. */
    val kids: Boolean = false,
    /** Can manage profiles and parental controls, and unlock any profile, with its own PIN. The owner always can. */
    val parent: Boolean = false,
    /** "salt:sha256" of the profile's PIN; empty = none. */
    val pinHash: String = "",
    /** Kids: live TV categories allowed, as "playlistId\u0001category". */
    val allowedGroups: Set<String> = emptySet(),
    /** Kids: movie and series categories allowed, as "movie|playlistId|category" / "series|…". */
    val allowedVodCats: Set<String> = emptySet(),
    /** Colour theme (ui.Themes) id; empty = not chosen yet (resolved at start: see [ProfileSet.withThemes]). */
    val theme: String = "",
    /** Movies and Series layout: "grid", "featured" or "showcase". */
    val vodLayout: String = "grid",
) {
    val isOwner get() = id == OWNER
    val isParent get() = isOwner || parent
    val hasPin get() = pinHash.isNotEmpty()
    val initial get() = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    fun pinMatches(pin: String): Boolean {
        val (salt, hash) = pinHash.split(':', limit = 2).takeIf { it.size == 2 } ?: return false
        return hash == sha256("$salt:$pin")
    }

    fun withPin(pin: String?): Profile = copy(pinHash = if (pin.isNullOrEmpty()) "" else hashPin(pin))

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("color", color).put("kids", kids).put("parent", parent).put("pin", pinHash)
        .put("allowedGroups", JSONArray(allowedGroups.toList())).put("allowedVodCats", JSONArray(allowedVodCats.toList()))
        .put("theme", theme).put("vodLayout", vodLayout)

    companion object {
        const val OWNER = "main"

        fun fromJson(o: JSONObject) = Profile(
            id = o.getString("id"), name = o.optString("name", "Profile"), color = o.optInt("color", 0),
            kids = o.optBoolean("kids"), parent = o.optBoolean("parent"), pinHash = o.optString("pin"),
            allowedGroups = strings(o.optJSONArray("allowedGroups")), allowedVodCats = strings(o.optJSONArray("allowedVodCats")),
            theme = o.optString("theme", ""), vodLayout = o.optString("vodLayout", "grid"),
        )

        fun hashPin(pin: String): String {
            val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            return "$salt:" + sha256("$salt:$pin")
        }

        private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        private fun strings(a: JSONArray?): Set<String> = if (a == null) emptySet() else (0 until a.length()).map { a.getString(it) }.toSet()
    }
}

/** All profiles on this TV, the one in use, and what the app does when it starts. */
data class ProfileSet(
    val list: List<Profile> = listOf(Profile(Profile.OWNER, "Me")),
    val active: String = Profile.OWNER,
    /** "ask" (Who's watching?) or "last" (the last profile used; Kids instead when that one needs a PIN). */
    val startMode: String = "ask",
) {
    val activeProfile: Profile get() = list.firstOrNull { it.id == active } ?: list.first()
    val owner: Profile get() = list.firstOrNull { it.isOwner } ?: list.first()
    val hasKids get() = list.any { it.kids }
    /** More than one profile: Who's watching? and the switcher appear. */
    val multiple get() = list.size > 1

    /** Opening [p] asks for a PIN: Kids never; any other profile when it has one, or always while a Kids profile exists. */
    fun needsPin(p: Profile) = !p.kids && (p.hasPin || hasKids)

    /** [pin] opens [p]: its own PIN, or a parent's. */
    fun unlocks(p: Profile, pin: String) = p.pinMatches(pin) || list.any { it.isParent && it.hasPin && it.pinMatches(pin) }

    /** [pin] is a parent's: it can change profiles and parental controls. */
    fun isParentPin(pin: String) = list.any { it.isParent && it.hasPin && it.pinMatches(pin) }

    fun get(id: String) = list.firstOrNull { it.id == id }

    /**
     * Gives every profile without a theme one: [THEME_LEGACY] (the look they had) when the app was already set up
     * before 1.4.0, else [THEME_DEFAULT]. Null when nothing needed one.
     */
    fun withThemes(existingInstall: Boolean): ProfileSet? =
        if (list.none { it.theme.isEmpty() }) null
        else copy(list = list.map { if (it.theme.isEmpty()) it.copy(theme = if (existingInstall) THEME_LEGACY else THEME_DEFAULT) else it })

    fun toJson(): JSONObject = JSONObject().put("active", active).put("startMode", startMode)
        .put("profiles", JSONArray().apply { list.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): ProfileSet {
            val a = o.optJSONArray("profiles") ?: JSONArray()
            val list = (0 until a.length()).mapNotNull { runCatching { Profile.fromJson(a.getJSONObject(it)) }.getOrNull() }
                .let { l -> if (l.none { it.isOwner }) listOf(Profile(Profile.OWNER, "Me")) + l else l }
            val active = o.optString("active", Profile.OWNER).takeIf { id -> list.any { it.id == id } } ?: Profile.OWNER
            return ProfileSet(list, active, o.optString("startMode", "ask").takeIf { it == "ask" || it == "last" } ?: "ask")
        }
    }
}

/** New installs and new profiles. */
const val THEME_DEFAULT = "roast"
/** Profiles from before themes (1.4.0) keep the original look. */
const val THEME_LEGACY = "midnight"

/** The colours a profile can have (tile background to letter colour). */
val PROFILE_COLORS = listOf(
    0xFF1E9BFF to 0xFF02132E, // blue
    0xFFE8833A to 0xFF2A1405, // orange
    0xFF3FB68B to 0xFF04241A, // green
    0xFF9B7BFF to 0xFF160B3A, // purple
    0xFFF06BA8 to 0xFF3A0820, // pink
    0xFFFFD25E to 0xFF2A1E00, // yellow
)

/** The fields of [Prefs] (in its JSON) that belong to one profile. */
val PERSONAL_KEYS = listOf(
    "favorites", "lists", "hiddenGroups", "hiddenChannels", "vodHiddenCats", "vodHiddenTitles",
    "myList", "lastChannel", "dismissedSuggestions", "historyPaused",
)

/** [p]'s personal fields, as JSON. */
fun personalOf(p: Prefs): JSONObject {
    val all = p.toJson()
    return JSONObject().apply { PERSONAL_KEYS.forEach { k -> if (all.has(k)) put(k, all.get(k)) } }
}

/** [p] with its personal fields replaced by [personal] (fields missing there are cleared). */
fun Prefs.withPersonal(personal: JSONObject): Prefs {
    val all = toJson()
    PERSONAL_KEYS.forEach { k -> if (personal.has(k)) all.put(k, personal.get(k)) else all.remove(k) }
    return Prefs.fromJson(all)
}

object Kids {
    /** Category names that are adult content; a Kids profile never shows them, even if chosen. */
    private val ADULT = Regex("""(?i)(\bxxx\b|\badults?\b|18\s*\+|\bporn|\berotic|\bplayboy\b|\bhustler\b)""")
    /** Category names that look made for children: picked for a new Kids profile. */
    private val KIDSY = Regex("""(?i)(\bkids?\b|\bchild|\bcartoon|\bfamily\b|\bjunior\b|\banimation\b|\banime\b|\btoddler|\bpreschool|\benfants?\b|\binfantil|\bniños\b)""")

    fun isAdult(name: String) = ADULT.containsMatchIn(name)
    fun looksForKids(name: String) = !isAdult(name) && KIDSY.containsMatchIn(name)
}
