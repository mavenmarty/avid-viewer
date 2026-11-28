package com.avidviewer.tv.data

/**
 * Nov 28, 2026 (owner: "get rid of duplicate channels existing even in the same playlist"; design boards 72–73):
 * copies of one channel in the same category ("ESPN", "ESPN HD", "US| ESPN FHD", "ESPN ᴿᴬᵂ") show as one channel.
 * The best version plays first; if it fails or doesn't start, the next one does.
 *
 * Copies are the same channel when, in one category, their names match once country and quality tags are taken off,
 * or they share a guide id. East and West feeds and local stations stay apart (their names differ: "A&E East",
 * "NBC [Chicago]"). Event channels (PPV, game slots like "NFL 03: Bears @ Packers") are never combined: their names
 * change with what's on.
 */
object ChannelMerge {
    /** The result: the channels to show (one per copy set, in the list's order) and, for each, its versions best first. */
    class Result(val channels: List<Channel>, val versions: Map<String, List<Channel>>) {
        /** A version's key → the key of the channel it's shown as. */
        val shownAs: Map<String, String> = HashMap<String, String>().apply { versions.forEach { (main, vs) -> vs.forEach { put(it.key, main) } } }
    }

    /** Country and language tags providers put in front ("US|", "USA:", "AM | USA |", "EN -"); a network's name isn't one. */
    private val PREFIX = Regex("^\\s*(?:us|usa|uk|gb|ca|can|mx|am|en|eng|fr|de|es|sp|it|pt|br|ar|au|nz|ie|in|pk|latam|la|ex|ny)\\s*(?:\\||:|-|–|»)\\s*", RegexOption.IGNORE_CASE)
    private val QUALITY = Regex(
        "(?<![a-z0-9])(?:fhd|uhd|hd|sd|qhd|4k|8k|hevc|h\\.?265|h\\.?264|1080[pi]?|720p?|2160p?|480p?|\\d{2}\\s?fps|raw|vip|backup|alt|hq|lq|multi|50hz|60hz)(?![a-z0-9])",
        RegexOption.IGNORE_CASE,
    )
    private val BRACKETED_QUALITY = Regex("[\\[(](?:[^\\])]*?)(?:hd|fhd|uhd|4k|sd|backup|alt)[^\\])]*[\\])]", RegexOption.IGNORE_CASE)
    private val EVENT_GROUP = Regex(
        "ppv|pay.?per.?view|event|nfl\\s*(?:game|sunday|ticket|plus|\\+)|nba\\s*(?:league|pass|game)|nhl\\s*(?:center|game|live)|mlb\\s*(?:extra|game|tv)|ncaa|espn\\s*\\+|espn\\s*plus|\\bf1\\b|formula|ufc|boxing|dazn|flo\\s*sports|matchday|sports?\\s*pass|live\\s*sports?\\s*events?",
        RegexOption.IGNORE_CASE,
    )
    private val EVENT_NAME = Regex("(?:^|\\s|\\|)(?:\\d{1,3}\\s*[:|\\-]|#\\s*\\d{1,3})|\\s(?:vs\\.?|v|@|x)\\s|\\b\\d{1,2}[:.]\\d{2}\\s*(?:am|pm|et|pt|ct|uk|cet)?\\b|no event|off air|\\b(?:live|replay)\\s*:", RegexOption.IGNORE_CASE)

    /** Whether [name] in category [group] is an event channel (never combined). */
    fun isEvent(name: String, group: String): Boolean = EVENT_GROUP.containsMatchIn(group) || EVENT_NAME.containsMatchIn(name)

    /** [name] with country and quality tags, symbols and letter case taken off ("US| ESPN FHD ◉" → "espn"). */
    fun key(name: String): String {
        var s = name
        // Modifier letters (ᴿᴬᵂ, ⁶⁰ᶠᵖˢ) and symbols go first, so "ESPN ᴿᴬᵂ" is "ESPN".
        s = buildString { for (ch in s) { val t = Character.getType(ch); if (t != Character.MODIFIER_LETTER.toInt() && t != Character.OTHER_SYMBOL.toInt() && !(ch in '⁰'..'₟')) append(ch) } }
        repeat(3) { s = s.replace(PREFIX, "") }
        s = s.replace(BRACKETED_QUALITY, " ").replace(QUALITY, " ")
        s = s.lowercase().replace('&', '+')
        s = s.replace(Regex("[^\\p{L}\\p{N}+\\[\\]()]+"), " ").replace(Regex("\\s+"), " ").trim()
        // Brackets left empty by a removed tag ("ESPN ()").
        s = s.replace(Regex("[\\[(]\\s*[\\])]"), "").trim()
        return s
    }

    /** How good a copy is for this TV: FHD 3, HD 2, untagged 1, SD 0; 4K first on a 4K TV, else last. */
    fun quality(name: String, uhd: Boolean): Int {
        val n = name.lowercase()
        fun has(vararg t: String) = t.any { Regex("(?<![a-z0-9])" + Regex.escape(it) + "(?![a-z0-9])").containsMatchIn(n) }
        return when {
            has("4k", "uhd", "2160p", "2160", "8k") -> if (uhd) 4 else -1
            has("fhd", "1080p", "1080i", "1080") -> 3
            has("hd", "720p", "720") -> 2
            has("sd", "480p", "480") -> 0
            else -> 1
        }
    }

    /**
     * Combines copies in [list] (one playlist's channels, in its order). [uhd]: the TV shows 4K. A channel's versions
     * are best first (quality, then the list's order); the channel shown is the best one, in the place of the first copy.
     */
    fun merge(list: List<Channel>, uhd: Boolean): Result {
        val n = list.size
        val parent = IntArray(n) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) { if (ra < rb) parent[rb] = ra else parent[ra] = rb } }
        val byName = HashMap<String, Int>()
        val byEpg = HashMap<String, Int>()
        for (i in 0 until n) {
            val c = list[i]
            if (isEvent(c.name, c.group)) continue
            val k = key(c.name)
            if (k.isNotEmpty()) {
                val id = c.group + "\u0001" + k
                byName[id]?.let { union(it, i) } ?: byName.put(id, i)
            }
            val e = c.epgId?.trim()?.lowercase().orEmpty()
            if (e.isNotEmpty()) {
                val id = c.group + "\u0001" + e
                byEpg[id]?.let { union(it, i) } ?: byEpg.put(id, i)
            }
        }
        val sets = LinkedHashMap<Int, MutableList<Int>>()
        for (i in 0 until n) sets.getOrPut(find(i)) { ArrayList() }.add(i)
        val out = ArrayList<Channel>(sets.size)
        val versions = HashMap<String, List<Channel>>()
        // In the list's order (each set where its first copy was).
        for (first in 0 until n) {
            val members = sets[first] ?: continue
            if (find(first) != first) continue
            if (members.size == 1) { out.add(list[first]); continue }
            val ordered = members.sortedWith(compareByDescending<Int> { quality(list[it].name, uhd) }.thenBy { it }).map { list[it] }
            val best = ordered.first()
            val shown = best.copy(
                epgId = best.epgId?.takeIf { it.isNotBlank() } ?: ordered.firstNotNullOfOrNull { it.epgId?.takeIf { e -> e.isNotBlank() } },
                logo = best.logo?.takeIf { it.isNotBlank() } ?: ordered.firstNotNullOfOrNull { it.logo?.takeIf { l -> l.isNotBlank() } },
            )
            out.add(shown)
            versions[shown.key] = listOf(shown) + ordered.drop(1)
        }
        return Result(out, versions)
    }

    /**
     * A category's name for matching across playlists: letter case, spacing and punctuation ignored, "USA" read as "US"
     * ("USA: Sports" and "US| SPORTS" are one category). The country stays: "UK| Sports" is another category.
     */
    fun groupKey(group: String): String {
        var s = group.lowercase().replace('&', '+')
        s = s.replace(Regex("[^\\p{L}\\p{N}+]+"), " ").replace(Regex("\\s+"), " ").trim()
        s = s.replace(Regex("^usa\\b"), "us")
        return s
    }

    /**
     * One Live TV (Nov 28, 2026, owner; design boards 70–71): the channels of every live playlist as one list.
     *
     * [lists] are the playlists' channels, the primary playlist first (the account's playlist order). Categories with the
     * same name ([groupKey]) are one category, named as the first playlist that has it names it. In a category, copies are
     * combined as in [merge]; versions are ordered by playlist first, then quality, then the list's order. Event channels
     * are never combined, and an event category that several playlists have stays apart per playlist, its name followed by
     * the playlist's ([names], same order as [lists]).
     *
     * [Across.groupMembers]: for each category shown, the playlists' own categories in it (playlist id, name), primary
     * first, so hiding, renaming and Kids rules can be read from the playlist's own choices.
     */
    class Across(
        val channels: List<Channel>,
        val versions: Map<String, List<Channel>>,
        val groupMembers: Map<String, List<Pair<String, String>>>,
        /** Shown key → the version whose guide it shows, when that's not the shown version itself. */
        val guideFrom: Map<String, Channel>,
    ) {
        val shownAs: Map<String, String> = HashMap<String, String>().apply { versions.forEach { (main, vs) -> vs.forEach { put(it.key, main) } } }
    }

    fun mergeAcross(lists: List<List<Channel>>, names: List<String>, uhd: Boolean, combine: Boolean = true): Across {
        fun pidOf(c: Channel) = c.key.substringBefore(':')
        // Event categories found in more than one playlist: kept apart, named with the playlist.
        val eventGroupsIn = HashMap<String, MutableSet<Int>>()
        lists.forEachIndexed { r, l -> l.forEach { c -> if (EVENT_GROUP.containsMatchIn(c.group)) eventGroupsIn.getOrPut(groupKey(c.group)) { HashSet() }.add(r) } }
        val shownGroupOf = HashMap<String, String>() // category id → name shown
        val members = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        val all = ArrayList<Channel>(lists.sumOf { it.size })
        val rank = ArrayList<Int>(all.size)
        val gid = ArrayList<String>(all.size)
        lists.forEachIndexed { r, l ->
            for (c in l) {
                val gk = groupKey(c.group)
                val apart = (eventGroupsIn[gk]?.size ?: 0) > 1
                val id = if (apart) "$gk\u0002$r" else gk
                val shown = shownGroupOf.getOrPut(id) { if (apart && r > 0) "${c.group} (${names.getOrElse(r) { "" }})" else c.group }
                val m = members.getOrPut(shown) { ArrayList() }
                val member = pidOf(c) to c.group
                if (m.lastOrNull() != member && member !in m) m.add(member)
                all.add(if (c.group == shown) c else c.copy(group = shown))
                rank.add(r); gid.add(id)
            }
        }
        val n = all.size
        val parent = IntArray(n) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) { if (ra < rb) parent[rb] = ra else parent[ra] = rb } }
        val byName = HashMap<String, Int>()
        val byEpg = HashMap<String, Int>()
        for (i in 0 until n) {
            val c = all[i]
            if (!combine || isEvent(c.name, c.group)) continue
            val k = key(c.name)
            if (k.isNotEmpty()) { val id = gid[i] + "\u0001" + k; byName[id]?.let { union(it, i) } ?: byName.put(id, i) }
            val e = c.epgId?.trim()?.lowercase().orEmpty()
            if (e.isNotEmpty()) { val id = gid[i] + "\u0001" + e; byEpg[id]?.let { union(it, i) } ?: byEpg.put(id, i) }
        }
        val sets = HashMap<Int, MutableList<Int>>()
        for (i in 0 until n) sets.getOrPut(find(i)) { ArrayList() }.add(i)
        val out = ArrayList<Channel>()
        val versions = HashMap<String, List<Channel>>()
        val guideFrom = HashMap<String, Channel>()
        for (first in 0 until n) {
            if (find(first) != first) continue
            val ms = sets[first]
            if (ms == null || ms.size == 1) { out.add(all[first]); continue }
            val ordered = ms.sortedWith(compareBy<Int> { rank[it] }.thenByDescending { quality(all[it].name, uhd) }.thenBy { it }).map { all[it] }
            val best = ordered.first()
            val shown = best.copy(logo = best.logo?.takeIf { it.isNotBlank() } ?: ordered.firstNotNullOfOrNull { it.logo?.takeIf { l -> l.isNotBlank() } })
            // The guide: the best version's, else a version of the same playlist with one, else another playlist's.
            val sameGuide = if (shown.epgId.isNullOrBlank()) ordered.firstOrNull { !it.epgId.isNullOrBlank() && pidOf(it) == pidOf(shown) }?.epgId else null
            val shownWithGuide = if (sameGuide != null) shown.copy(epgId = sameGuide) else shown
            if (shownWithGuide.epgId.isNullOrBlank()) ordered.firstOrNull { !it.epgId.isNullOrBlank() }?.let { guideFrom[shown.key] = it }
            out.add(shownWithGuide)
            versions[shownWithGuide.key] = listOf(shownWithGuide) + ordered.drop(1)
        }
        return Across(out, versions, members, guideFrom)
    }

    /** A short label for a version ("FHD", "HD", "4K", "SD"), or the copy's name when it has no tag. */
    fun versionLabel(c: Channel): String = when (quality(c.name, true)) {
        4 -> "4K"; 3 -> "FHD"; 2 -> "HD"; 0 -> "SD"; else -> c.name
    }
}
