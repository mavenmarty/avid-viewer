package com.avidviewer.tv.data

/** A live channel suggested because it looks local to the user's postal code. */
data class Suggestion(val key: String, val playlistId: String, val reason: String, val score: Int)

/**
 * Finds channels that look local to a place such as "Fargo, North Dakota", entirely on the device:
 *  - the city's name in the channel name or category (strongest),
 *  - US call signs (KVLY, WDAY…) seen on those city channels, found again elsewhere in the playlist,
 *  - the state or province, on channels that look like local network affiliates.
 */
object LocalSuggest {
    private val REGIONS = mapOf(
        "alabama" to "AL", "alaska" to "AK", "arizona" to "AZ", "arkansas" to "AR", "california" to "CA",
        "colorado" to "CO", "connecticut" to "CT", "delaware" to "DE", "district of columbia" to "DC", "florida" to "FL",
        "georgia" to "GA", "hawaii" to "HI", "idaho" to "ID", "illinois" to "IL", "indiana" to "IN", "iowa" to "IA",
        "kansas" to "KS", "kentucky" to "KY", "louisiana" to "LA", "maine" to "ME", "maryland" to "MD",
        "massachusetts" to "MA", "michigan" to "MI", "minnesota" to "MN", "mississippi" to "MS", "missouri" to "MO",
        "montana" to "MT", "nebraska" to "NE", "nevada" to "NV", "new hampshire" to "NH", "new jersey" to "NJ",
        "new mexico" to "NM", "new york" to "NY", "north carolina" to "NC", "north dakota" to "ND", "ohio" to "OH",
        "oklahoma" to "OK", "oregon" to "OR", "pennsylvania" to "PA", "rhode island" to "RI", "south carolina" to "SC",
        "south dakota" to "SD", "tennessee" to "TN", "texas" to "TX", "utah" to "UT", "vermont" to "VT",
        "virginia" to "VA", "washington" to "WA", "west virginia" to "WV", "wisconsin" to "WI", "wyoming" to "WY",
        "puerto rico" to "PR",
        "alberta" to "AB", "british columbia" to "BC", "manitoba" to "MB", "new brunswick" to "NB",
        "newfoundland and labrador" to "NL", "nova scotia" to "NS", "ontario" to "ON", "prince edward island" to "PE",
        "quebec" to "QC", "saskatchewan" to "SK",
    )
    private val NETWORKS = setOf("ABC", "NBC", "CBS", "FOX", "CW", "PBS", "MY", "MNT", "ION", "CTV", "CBC", "GLOBAL", "TELEMUNDO", "UNIVISION")
    private val LOCAL_GROUP = Regex("LOCAL|AFFILIATE|NETWORK", RegexOption.IGNORE_CASE)
    private val CALL_SIGN = Regex("^[KW][A-Z]{2,3}$")
    /** Common words that look like call signs but aren't. */
    private val NOT_CALL = setOf("WWE", "KIDS", "WEST", "WILD", "WORK", "KING", "WAR", "WIN", "WOW", "KISS", "WISE", "WEAR")

    private fun tokens(s: String) = s.uppercase().split(Regex("[^A-Z0-9]+")).filter { it.isNotEmpty() }

    /** "Fargo, North Dakota" → ("Fargo", "North Dakota", "ND"). */
    fun parsePlace(place: String): Triple<String, String, String?> {
        val city = place.substringBefore(',').trim()
        val region = place.substringAfter(',', "").trim()
        return Triple(city, region, REGIONS[region.lowercase()])
    }

    fun find(
        playlists: List<Pair<Playlist, List<Channel>>>,
        place: String,
        groupName: (String) -> String,
        limit: Int = 40,
    ): List<Suggestion> {
        val (city, region, abbr) = parsePlace(place)
        if (city.isBlank()) return emptyList()
        val cityTokens = tokens(city)
        val regionTokens = tokens(region)
        val out = HashMap<String, Suggestion>()
        fun add(key: String, pid: String, reason: String, score: Int) {
            val old = out[key]
            if (old == null || old.score < score) out[key] = Suggestion(key, pid, reason, score)
        }
        fun containsRun(hay: List<String>, needle: List<String>) =
            needle.isNotEmpty() && hay.windowed(needle.size).any { it == needle }

        for ((p, chs) in playlists) {
            // 1. City name in the channel name or its category.
            val citySigns = HashSet<String>()
            for (c in chs) {
                val nt = tokens(c.name)
                val gt = tokens(groupName(c.group))
                if (containsRun(nt, cityTokens) || containsRun(gt, cityTokens)) {
                    add(c.key, p.id, "Mentions $city", 100)
                    nt.filter { CALL_SIGN.matches(it) && it !in NOT_CALL }.forEach { citySigns.add(it) }
                }
            }
            // 2. The same stations listed elsewhere (another category, an HD copy…) by call sign.
            if (citySigns.isNotEmpty()) for (c in chs) {
                val sign = tokens(c.name).firstOrNull { it in citySigns } ?: continue
                add(c.key, p.id, "$sign · a $city station", 80)
            }
            // 3. Network affiliates labelled with the state or province.
            if (abbr != null) for (c in chs) {
                val nt = tokens(c.name)
                val gname = groupName(c.group)
                val gt = tokens(gname)
                val stateHit = (abbr in nt || abbr in gt || containsRun(nt, regionTokens) || containsRun(gt, regionTokens))
                if (!stateHit) continue
                val looksLocal = nt.any { it in NETWORKS } || LOCAL_GROUP.containsMatchIn(gname) || nt.any { CALL_SIGN.matches(it) && it !in NOT_CALL }
                if (looksLocal) add(c.key, p.id, "Local to $region", 50)
            }
        }
        return out.values.sortedWith(compareByDescending<Suggestion> { it.score }).take(limit)
    }
}
