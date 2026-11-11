package com.avidviewer.tv.data

import org.json.JSONObject
import java.security.MessageDigest

/**
 * Stable identities for M3U channels and movies, and moving saved choices across when a list changes.
 *
 * Older versions named M3U entries by their position in the file ("p1:57"). When a provider added or
 * removed one entry, every later one shifted, so favorites, lists, renames and hidden channels landed
 * on the neighbour, most visibly after restoring a backup. Entries are now named after their stream
 * address, and each new download maps the old names onto the new ones.
 */
object ChannelKeys {
    /** A short token for a stream address that survives reordering (and, for Xtream-style links, a new password). */
    fun token(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
        val segments = path.substringAfter("://", path).split('/')
        val last = segments.lastOrNull().orEmpty().substringBeforeLast('.')
        // http://host/live/user/pass/12345.ts or http://host/user/pass/12345: the provider's stream id.
        if (segments.size >= 3 && last.isNotEmpty() && last.all { it.isDigit() }) return "s$last"
        val d = MessageDigest.getInstance("SHA-1").digest(path.toByteArray(Charsets.UTF_8))
        return "h" + d.take(6).joinToString("") { "%02x".format(it) }
    }

    /** Gives each address its key; the same address twice gets ~2, ~3… */
    class Namer(private val prefix: String) {
        private val seen = HashMap<String, Int>()
        fun next(url: String): String {
            val t = token(url)
            val n = (seen[t] ?: 0) + 1
            seen[t] = n
            return prefix + t + if (n > 1) "~$n" else ""
        }
    }

    /** What's needed to find an entry again: its key, address token, name, category and guide id. */
    data class Ref(val key: String, val token: String, val name: String, val group: String, val epgId: String?, val isVod: Boolean = false) {
        fun toJson(): JSONObject = JSONObject().put("k", key).put("t", token).put("n", name).put("g", group).put("e", epgId ?: "").put("v", isVod)

        companion object {
            fun fromJson(o: JSONObject) = Ref(o.getString("k"), o.optString("t"), o.optString("n"), o.optString("g"), o.optString("e").ifBlank { null }, o.optBoolean("v", false))
            fun of(c: Channel) = Ref(c.key, token(c.url), c.name, c.group, c.epgId)
            fun of(v: VodItem) = Ref(v.key, token(v.url), v.name, v.category, null, isVod = true)
        }
    }

    /**
     * Old key → new key for every old entry that can be found in the new list: same address first,
     * then guide id + name, then name + category, then a name that appears only once. Unchanged keys are left out.
     */
    fun remap(old: List<Ref>, new: List<Ref>): Map<String, String> {
        if (old.isEmpty() || new.isEmpty()) return emptyMap()
        val byToken = new.groupBy { it.token }
        val byEpgName = new.filter { it.epgId != null }.groupBy { it.epgId!!.lowercase() + "|" + norm(it.name) }
        val byNameGroup = new.groupBy { norm(it.name) + "|" + norm(it.group) }
        val byName = new.groupBy { norm(it.name) }
        val newByKey = new.associateBy { it.key }
        val taken = HashSet<String>()
        val out = HashMap<String, String>()
        fun pick(list: List<Ref>?): Ref? = list?.firstOrNull { it.key !in taken }
        for (o in old) {
            if (newByKey[o.key]?.token == o.token) { taken.add(o.key); continue }
            val hit = pick(byToken[o.token])
                ?: o.epgId?.let { pick(byEpgName[it.lowercase() + "|" + norm(o.name)]) }
                ?: byNameGroup[norm(o.name) + "|" + norm(o.group)]?.let { l -> pick(l) }
                ?: byName[norm(o.name)]?.takeIf { it.size == 1 }?.let { pick(it) }
            if (hit != null) { taken.add(hit.key); if (hit.key != o.key) out[o.key] = hit.key }
        }
        return out
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    /** Moves every saved choice that names a channel or title from old keys to new ones. */
    fun Prefs.remapped(m: Map<String, String>): Prefs {
        if (m.isEmpty()) return this
        fun k(s: String) = m[s] ?: s
        return copy(
            favorites = favorites.map(::k).distinct(),
            lists = lists.map { l -> l.copy(keys = l.keys.map(::k).distinct()) },
            channelNames = channelNames.mapKeys { (key, _) -> k(key) },
            hiddenChannels = hiddenChannels.map(::k).toSet(),
            channelOrder = channelOrder.mapValues { (_, l) -> l.map(::k).distinct() },
            epgMap = epgMap.mapKeys { (key, _) -> k(key) },
            lastChannel = lastChannel?.let(::k),
            dismissedSuggestions = dismissedSuggestions.map(::k).toSet(),
            myList = myList.map { v -> m[v.key]?.let { v.copy(key = it) } ?: v },
            vodHiddenTitles = vodHiddenTitles.map(::k).toSet(),
        )
    }

    /** Every key a backup's choices refer to, so a restore can find them again in a changed list. */
    fun Prefs.referencedKeys(resume: Map<String, Long>, history: List<HistoryEntry>): Set<String> =
        (favorites + lists.flatMap { it.keys } + channelNames.keys + hiddenChannels + channelOrder.values.flatten() +
            epgMap.keys + listOfNotNull(lastChannel) + dismissedSuggestions + myList.map { it.key } + vodHiddenTitles +
            resume.keys + history.map { it.key }).toSet()
}
