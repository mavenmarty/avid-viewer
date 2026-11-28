package com.avidviewer.tv.data

/**
 * One Live TV (Nov 28, 2026, owner): every live playlist's channels as one guide.
 *
 * Owner's decisions (after beta 2 showed the playlists' categories side by side with nothing combined, because providers
 * name their categories differently): each channel shows **once in the whole guide**, and channels are sorted into
 * Avid Viewer's own **common categories** (Local stations, Sports, News, Entertainment…, other countries by country).
 *
 * A channel is the same channel in every playlist and category when its name matches once country, quality and feed
 * tags are taken off ("US ESPN (HD)", "Espn", "ESPN (720p)" and "ESPN (Backup)" are one ESPN), in the
 * same country (US ESPN and Australian ESPN stay apart). A local station is matched by its call sign ("US ABC 6 (WDAY)
 * Fargo", "ABC (WDAY) FARGO ND" are WDAY; KVLY never joins WDAY). Versions play in playlist order first (the account's
 * order), then quality. Event and pay-per-view channels are never combined and keep their playlist's own category.
 */
object OneLive {
    /** The id the one guide's own choices are kept under (hidden common categories), like a playlist's id. */
    const val PID = "*live"

    // Common categories, in the guide's order. Other countries follow Religious, then events, then Adult.
    const val LOCALS = "Local stations"
    const val SPORTS = "Sports"
    const val NEWS = "News"
    const val ENTERTAINMENT = "Entertainment"
    const val MOVIES = "Movies"
    const val KIDS = "Kids"
    const val DOCS = "Documentary & science"
    const val MUSIC = "Music"
    const val SPANISH = "En español"
    const val RELIGIOUS = "Faith"
    const val ALWAYS_ON = "24/7 channels"
    const val ADULT = "Adult"
    private val ORDER = listOf(LOCALS, SPORTS, NEWS, ENTERTAINMENT, MOVIES, KIDS, DOCS, MUSIC, SPANISH, ALWAYS_ON, RELIGIOUS)

    class Result(
        val channels: List<Channel>,
        /** Shown key → its versions, best first (only channels with more than one copy). */
        val versions: Map<String, List<Channel>>,
        /** Shown key → its playlist's own category name, when it's shown under a common one. */
        val rawGroups: Map<String, String>,
        /** Shown key → the copy whose guide it shows, when it has none of its own. */
        val guideFrom: Map<String, Channel>,
    ) {
        val shownAs: Map<String, String> = HashMap<String, String>().apply { versions.forEach { (main, vs) -> vs.forEach { put(it.key, main) } } }
    }

    // ---------------------------------------------------------------- country
    private val CODES = mapOf(
        "us" to "us", "usa" to "us", "uk" to "uk", "gb" to "uk", "ca" to "ca", "can" to "ca", "au" to "au", "nz" to "nz", "ie" to "ie",
        "mx" to "mx", "br" to "br", "ar" to "ar", "es" to "es", "fr" to "fr", "de" to "de", "it" to "it", "pt" to "pt", "nl" to "nl",
        "be" to "be", "se" to "se", "no" to "no", "dk" to "dk", "fi" to "fi", "pl" to "pl", "tr" to "tr", "gr" to "gr", "ru" to "ru",
        "in" to "in", "pk" to "pk", "ph" to "ph", "za" to "za", "af" to "af", "ex" to "ex", "latam" to "latam",
    )
    private val WORDS = mapOf(
        "usa" to "us", "united states" to "us", "uk" to "uk", "united kingdom" to "uk", "britain" to "uk", "canada" to "ca",
        "canadian" to "ca", "australia" to "au", "new zealand" to "nz", "ireland" to "ie", "mexico" to "mx", "brazil" to "br",
        "argentina" to "ar", "spain" to "es", "france" to "fr", "germany" to "de", "italy" to "it", "portugal" to "pt",
        "netherlands" to "nl", "india" to "in", "pakistan" to "pk", "philippines" to "ph", "africa" to "af", "latino" to "latam",
        "latin" to "latam", "latam" to "latam",
    )
    val COUNTRY_NAMES = mapOf(
        "us" to "United States", "uk" to "United Kingdom", "ca" to "Canada", "au" to "Australia", "nz" to "New Zealand",
        "ie" to "Ireland", "mx" to "Mexico", "br" to "Brazil", "ar" to "Argentina", "es" to "Spain", "fr" to "France",
        "de" to "Germany", "it" to "Italy", "pt" to "Portugal", "nl" to "Netherlands", "be" to "Belgium", "se" to "Sweden",
        "no" to "Norway", "dk" to "Denmark", "fi" to "Finland", "pl" to "Poland", "tr" to "Turkey", "gr" to "Greece",
        "ru" to "Russia", "in" to "India", "pk" to "Pakistan", "ph" to "Philippines", "za" to "South Africa", "af" to "Africa",
        "ex" to "Ex-Yugoslavia", "latam" to "Latin America",
    )
    /** A code before a separator ("US|", "UK:", "AM | USA |", "(AU)"), or US/UK/CA/AU before a space ("US ESPN"). */
    private val NAME_CODE = Regex("^\\s*(?:\\(([a-zA-Z]{2,5})\\)|([a-zA-Z]{2,5})\\s*(?:\\||:|-|–|»)|(us|usa|uk|ca|au)\\s)", RegexOption.IGNORE_CASE)

    /** The flag emoji's country ("🇺🇸" → us), if [s] has one. */
    private fun flag(s: String): String? {
        var i = 0
        while (i < s.length - 3) {
            val a = s.codePointAt(i)
            if (a in 0x1F1E6..0x1F1FF) {
                val b = s.codePointAt(i + 2)
                if (b in 0x1F1E6..0x1F1FF) {
                    val code = "${'a' + (a - 0x1F1E6)}${'a' + (b - 0x1F1E6)}"
                    return CODES[code] ?: if (code == "gb") "uk" else code
                }
            }
            i += Character.charCount(a)
        }
        return null
    }

    /**
     * The channel's country, null when nothing says: the category first (a flag, "US | Sports", "AM | USA |", "TV Guide
     * (Canada)"), then a US call sign, then the name ("UK: …", "(AU) …", "US ESPN"). The category goes first because a
     * name's "CA:" can be California ("CA: LOS ANGELES: UNIMÁS 46 KFTR-DT" in "US| LATIN UNIMAS").
     */
    fun country(name: String, group: String): String? {
        flag(group)?.let { return it }
        val g = group.lowercase()
        for (part in g.split('|', ':', '-', '–', '»', '(', ')', '[', ']').map { it.trim() }) CODES[part]?.let { return it }
        for ((w, c) in WORDS) if (Regex("(?<![a-z])" + Regex.escape(w) + "(?![a-z])").containsMatchIn(g)) return c
        if (callSign(name, group) != null) return "us"
        flag(name)?.let { return it }
        NAME_CODE.find(name)?.let { m -> CODES[(m.groupValues[1] + m.groupValues[2] + m.groupValues[3]).lowercase()]?.let { return it } }
        return null
    }

    // ---------------------------------------------------------------- matching
    private val SEPARATOR = Regex("^[\\s#*=~_\\-–—|.]*$|^\\s*#{2,}|#{3,}\\s*$")
    /** A heading the provider puts in the list ("##### ENTERTAINMENT #####"): not a channel. */
    fun isSeparator(name: String) = SEPARATOR.containsMatchIn(name)

    private val CALL = Regex("(?<![A-Za-z])([KW][A-Z]{3})(?:-?(?:DT|TV|LD|CD|CA)\\d?)?(?![A-Za-z])")
    private val NOT_CALLS = setOf("KIDS", "WEST", "WILD", "WIFE", "WORK", "WISH", "KING", "KICK", "WOOD", "WARS", "WAVE", "WEEK", "WIND", "WINE", "WEAR", "WALK", "WALL", "WAKE", "KEEP", "KISS", "WOOF", "WOKE")
    private val NETWORK = Regex("(?<![A-Za-z])(?:ABC|NBC|CBS|FOX|CW|PBS|MNT|MYNETWORK|MY ?TV|TELEMUNDO|UNIVISION|UNIM[AÁ]S|ION|IND|INDEPENDENT)(?![A-Za-z])", RegexOption.IGNORE_CASE)
    private val LOCAL_GROUP = Regex("local|affiliate|network\\s*\\(|\\bota\\b", RegexOption.IGNORE_CASE)
    /** A network station named by its city: "NBC [Chicago]", "CBS (Denver)". */
    private val LOCAL_NAME = Regex("^\\s*(?:ABC|NBC|CBS|FOX|CW|PBS)\\s*\\d*\\s*[\\[(]\\s*[A-Za-z]", RegexOption.IGNORE_CASE)

    /** A local station's call sign ("WDAY"), when the name has one with a network or the category is for locals. */
    fun callSign(name: String, group: String): String? {
        val m = CALL.findAll(name).map { it.groupValues[1] }.firstOrNull { it !in NOT_CALLS } ?: return null
        return if (NETWORK.containsMatchIn(name) || LOCAL_GROUP.containsMatchIn(group)) m else null
    }

    private val COUNTRY_WORD = Regex("^(?:us|usa|uk|ca|au)\\s+", RegexOption.IGNORE_CASE)
    private val FEED = Regex("(?<![a-z0-9])(?:east|eastern|feed|channel|tv|network|hd\\d?)(?![a-z0-9])")

    /** The name to match on: [ChannelMerge.key] without a country word, the East feed's "East" or symbols. */
    fun nameKey(name: String): String {
        var s = ChannelMerge.key(COUNTRY_WORD.replace(name.trim(), ""))
        s = COUNTRY_WORD.replace(s, "")
        s = s.replace(Regex("\\((?:h|d|s|b)\\)"), " ") // provider markers: (H), (D)
        s = s.replace(FEED, " ")
        return s.replace(Regex("[^\\p{L}\\p{N}+]"), "")
    }

    // ---------------------------------------------------------------- common categories
    private fun rx(s: String) = Regex(s, RegexOption.IGNORE_CASE)
    private val R_ADULT = rx("xxx|adult|18\\+|\\bporn|playboy|hustler|brazzers|bangbros|vivid")
    private val R_SPANISH = rx("español|espanol|spanish|latino|latin\\b|deportes|telemundo|univision|unimas|galavision|\\btudn\\b|estrella|azteca|televisa|\\bcnn\\s*en\\s*espa")
    private val R_KIDS = rx("\\bkids?\\b|cartoon|disney|nick(?:elodeon|\\s*jr|toons)?\\b|boomerang|baby\\s*tv|babyfirst|pbs\\s*kids|universal\\s*kids|duck\\s*tv|\\bteen|toon|sprout|nat\\s*geo\\s*kids|discovery\\s*family")
    private val R_NEWS = rx("\\bnews|cnn|msnbc|cnbc|bloomberg|weather|c-?span|newsmax|\\bhln\\b|fox\\s*business|sky\\s*news|bbc\\s*world|al\\s*jazeera|\\bcheddar|\\bnewsnation|\\bcbsn|\\bcourt\\s*tv|\\bfbn\\b|\\bbusiness")
    private val R_SPORTS = rx("sport|espn|\\bfs[12]\\b|fox\\s*soccer|\\bnfl\\b|\\bnba\\b|\\bmlb\\b|\\bnhl\\b|\\bnba\\s*tv|golf|tennis|\\bbtn\\b|big\\s*ten|\\bsec\\s*net|\\bacc\\s*net|pac-?12|tsn|sportsnet|dazn|bein|tnt\\s*sports|racing|\\bwwe\\b|\\bufc\\b|fight|boxing|olympic|soccer|football|baseball|hockey|basketball|cricket|rugby|motor|\\bnascar|\\bf1\\b|\\bmsg\\b|\\bnesn\\b|\\byes\\s*network|marquee|\\bsny\\b|bally|\\bstadium\\b|\\bfanduel|\\btvg\\b|outdoor|\\bsportsman")
    private val R_MOVIES = rx("movie|cinema|\\bhbo|showtime|starz|cinemax|\\bepix|\\bmgm|\\btcm\\b|\\bflix|\\bmax\\b|film|hallmark\\s*mov|lifetime\\s*mov|\\bifc\\b|sundance|\\bthriller|\\bfxm\\b")
    private val R_MUSIC = rx("music|\\bmtv|\\bvh1|\\bcmt\\b|\\bbet\\s*jams|\\bfuse\\b|\\baxs\\b|stingray|\\bradio\\b|\\bhits\\b|\\bconcert|\\bjams\\b|\\bvevo")
    private val R_DOCS = rx("discovery|nat\\s*geo|national\\s*geographic|history|science|animal\\s*planet|smithsonian|documentar|\\bdocu|\\bdiy\\b|\\bnature|\\bplanet|\\bcuriosity|\\bmagnolia|\\bhistory|\\bahc\\b|\\bvice\\b|destination\\s*america")
    private val R_FAITH = rx("church|gospel|faith|christian|\\btbn\\b|\\bewtn|daystar|\\bgod\\b|\\bbible|\\bcatholic|\\bislam|\\bquran|\\bhillsong|\\binspiration|\\bcbn\\b|\\bsalt\\s*\\+|\\bcornerstone")
    private val R_ALWAYS = rx("24/7|24-7|\\b247\\b|\\bnon-?stop|\\bmarathon|pluto|\\btubi\\b|\\bsamsung\\s*tv\\s*plus|\\bxumo|\\bfast\\s*channels?|\\broku\\s*channels?|prime\\s*channels?")

    /** The common category for a channel in [country] (the guide's [home] country or another). */
    fun category(name: String, group: String, country: String?, home: String?): String {
        val both = "$group  $name"
        if (R_ADULT.containsMatchIn(both)) return ADULT
        if (country != null && home != null && country != home) return COUNTRY_NAMES[country] ?: country.uppercase()
        if (callSign(name, group) != null || LOCAL_NAME.containsMatchIn(name)) return LOCALS
        if (R_SPANISH.containsMatchIn(both)) return SPANISH
        // The name first (a sports channel in a provider's "Entertainment" category is Sports), then the category.
        for (src in listOf(name, group)) {
            when {
                R_KIDS.containsMatchIn(src) -> return KIDS
                R_NEWS.containsMatchIn(src) -> return NEWS
                R_SPORTS.containsMatchIn(src) -> return SPORTS
                R_MOVIES.containsMatchIn(src) -> return MOVIES
                R_MUSIC.containsMatchIn(src) -> return MUSIC
                R_DOCS.containsMatchIn(src) -> return DOCS
                R_FAITH.containsMatchIn(src) -> return RELIGIOUS
            }
        }
        if (R_ALWAYS.containsMatchIn(both)) return ALWAYS_ON
        if (LOCAL_GROUP.containsMatchIn(group)) return LOCALS
        return ENTERTAINMENT
    }

    // ---------------------------------------------------------------- the guide
    private class Entry(val group: String, val order: Int) { val copies = ArrayList<Int>(2) }

    /**
     * [lists]: the live playlists' channels, the account's first playlist first; [names]: their names. [keep]: whether a
     * channel of (playlist id, its own category) may be shown (hidden categories and Kids rules on the TV and website).
     * [uhd]: the TV shows 4K. [combine] off: nothing is combined (common categories still apply).
     */
    fun build(
        lists: List<List<Channel>>, names: List<String>, uhd: Boolean, combine: Boolean = true,
        keep: (String, String) -> Boolean = { _, _ -> true },
    ): Result {
        fun pidOf(c: Channel) = c.key.substringBefore(':')
        // The guide's own country: the one most channels name.
        val seen = HashMap<String, Int>()
        lists.forEach { l -> l.forEach { c -> country(c.name, c.group)?.let { seen[it] = (seen[it] ?: 0) + 1 } } }
        val home = seen.maxByOrNull { it.value }?.key
        val all = ArrayList<Channel>()
        val rank = ArrayList<Int>()
        val entries = ArrayList<Entry>()
        val byKey = HashMap<String, Entry>()
        val byGuide = HashMap<String, Entry>() // same playlist, same guide id
        val eventOwner = HashMap<String, Int>()
        var order = 0
        lists.forEachIndexed { r, l ->
            for (c in l) {
                if (isSeparator(c.name) || !keep(pidOf(c), c.group)) continue
                val i = all.size
                all.add(c); rank.add(r)
                if (ChannelMerge.isEvent(c.name, c.group)) {
                    // Kept apart, in the playlist's own category (named with the playlist if another playlist has it too).
                    val owner = eventOwner.getOrPut(c.group) { r }
                    val g = if (owner == r) c.group else "${c.group} (${names.getOrElse(r) { "" }})"
                    entries.add(Entry(g, order++).also { it.copies.add(i) })
                    continue
                }
                val cc = country(c.name, c.group) ?: home
                val call = callSign(c.name, c.group)
                val k = if (call != null) "call:$call" else nameKey(c.name).let { if (it.isEmpty()) "" else "$cc:$it" }
                val epg = c.epgId?.trim()?.lowercase().orEmpty().let { if (it.isEmpty()) "" else pidOf(c) + "\u0001" + it }
                val e = if (!combine) null else (if (k.isNotEmpty()) byKey[k] else null) ?: (if (epg.isNotEmpty()) byGuide[epg] else null)
                if (e != null) {
                    e.copies.add(i)
                    if (k.isNotEmpty()) byKey.putIfAbsent(k, e)
                    if (epg.isNotEmpty()) byGuide.putIfAbsent(epg, e)
                    continue
                }
                val ne = Entry(category(c.name, c.group, cc, home), order++).also { it.copies.add(i) }
                entries.add(ne)
                if (k.isNotEmpty()) byKey[k] = ne
                if (epg.isNotEmpty()) byGuide[epg] = ne
            }
        }
        // Categories: the common ones in their order, other countries by name, then events as found, Adult last.
        val countries = COUNTRY_NAMES.values.toSet()
        fun catRank(g: String): Int = when {
            g == ADULT -> 3000
            ORDER.indexOf(g) >= 0 -> ORDER.indexOf(g)
            g in countries -> 100
            else -> 1000
        }
        val firstOf = HashMap<String, Int>()
        entries.forEach { firstOf.putIfAbsent(it.group, it.order) }
        val sorted = entries.sortedWith(compareBy<Entry>({ catRank(it.group) }, { if (catRank(it.group) == 100) it.group else "" }, { firstOf[it.group] ?: 0 }, { it.order }))
        val out = ArrayList<Channel>(sorted.size)
        val versions = HashMap<String, List<Channel>>()
        val rawGroups = HashMap<String, String>()
        val guideFrom = HashMap<String, Channel>()
        for (e in sorted) {
            val ordered = if (e.copies.size == 1) e.copies else
                e.copies.sortedWith(compareBy<Int> { rank[it] }.thenByDescending { ChannelMerge.quality(all[it].name, uhd) }.thenBy { it })
            val best = all[ordered.first()]
            val logo = best.logo?.takeIf { it.isNotBlank() } ?: ordered.firstNotNullOfOrNull { all[it].logo?.takeIf { l -> l.isNotBlank() } }
            val sameGuide = if (best.epgId.isNullOrBlank()) ordered.map { all[it] }.firstOrNull { !it.epgId.isNullOrBlank() && pidOf(it) == pidOf(best) }?.epgId else null
            val shown = if (best.group == e.group && logo == best.logo && sameGuide == null) best
                else best.copy(group = e.group, logo = logo, epgId = sameGuide ?: best.epgId)
            if (best.group != e.group) rawGroups[shown.key] = best.group
            if (shown.epgId.isNullOrBlank()) ordered.map { all[it] }.firstOrNull { !it.epgId.isNullOrBlank() }?.let { guideFrom[shown.key] = it }
            out.add(shown)
            if (ordered.size > 1) versions[shown.key] = listOf(shown) + ordered.drop(1).map { all[it] }
        }
        return Result(out, versions, rawGroups, guideFrom)
    }
}
