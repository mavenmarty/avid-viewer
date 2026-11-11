package com.avidviewer.tv.data

/** How a channel was found for a game, best first. */
enum class MatchKind(val rank: Int) { NATIONAL(100), GUIDE(90), EVENT(85), LOCAL(70) }

data class ChannelMatch(val key: String, val name: String, val playlistId: String, val kinds: Set<MatchKind>, val reasons: List<String>, val country: String? = null) {
    /** US channels first, Canadian ones as a backup (unmarked channels in between). */
    val rank: Int get() = (kinds.maxOfOrNull { it.rank } ?: 0) + 5 * (kinds.size - 1) + when (country) { "US" -> 30; null -> 15; else -> 0 }
}

/**
 * The country a channel is for, from the code providers put on its name or category ("US| ESPN",
 * "CA: TSN 1", "UK | Sky Sports", category "CANADA SPORTS"); null when nothing says.
 */
object Country {
    private val CODES = mapOf(
        "us" to "US", "usa" to "US", "ca" to "CA", "can" to "CA", "canada" to "CA", "cdn" to "CA",
        "uk" to "UK", "gb" to "UK", "en" to null, "ie" to "IE", "irl" to "IE", "au" to "AU", "aus" to "AU", "nz" to "NZ",
        "fr" to "FR", "de" to "DE", "ger" to "DE", "es" to "ES", "spa" to "ES", "it" to "IT", "ita" to "IT", "pt" to "PT", "por" to "PT",
        "nl" to "NL", "be" to "BE", "ch" to "CH", "at" to "AT", "pl" to "PL", "se" to "SE", "no" to "NO", "dk" to "DK", "fi" to "FI",
        "tr" to "TR", "gr" to "GR", "ru" to "RU", "ua" to "UA", "ro" to "RO", "hu" to "HU", "cz" to "CZ", "rs" to "RS", "hr" to "HR", "bg" to "BG", "al" to "AL",
        "ar" to "AR", "arab" to "AR", "in" to "IN", "ind" to "IN", "pk" to "PK", "bd" to "BD", "ph" to "PH", "my" to "MY", "id" to "ID", "th" to "TH", "vn" to "VN",
        "br" to "BR", "mx" to "MX", "latino" to "LATAM", "latam" to "LATAM", "afr" to "AF", "za" to "ZA", "ng" to "NG", "kr" to "KR", "jp" to "JP", "cn" to "CN", "hk" to "HK", "tw" to "TW",
        "ex-yu" to "EXYU", "exyu" to "EXYU", "il" to "IL", "ir" to "IR", "af" to "AF",
    )
    private val NAMES = mapOf(
        "united states" to "US", "usa" to "US", "canada" to "CA", "canadian" to "CA", "united kingdom" to "UK", "british" to "UK",
        "france" to "FR", "germany" to "DE", "deutschland" to "DE", "spain" to "ES", "italy" to "IT", "italia" to "IT", "portugal" to "PT",
        "arabic" to "AR", "india" to "IN", "pakistan" to "PK", "brazil" to "BR", "brasil" to "BR", "mexico" to "MX", "latino" to "LATAM",
        "australia" to "AU", "ireland" to "IE", "netherlands" to "NL", "turkey" to "TR", "poland" to "PL", "africa" to "AF", "philippines" to "PH",
    )
    private val PREFIX = Regex("""^\s*[\[(]?\s*([a-z]{2,6}(?:-[a-z]{2})?)\s*[\])]?\s*[|:\-]""", RegexOption.IGNORE_CASE)

    fun of(name: String, group: String?): String? = fromText(name) ?: group?.let { fromText(it) ?: named(it) }

    private fun fromText(s: String): String? {
        val code = PREFIX.find(s)?.groupValues?.get(1)?.lowercase() ?: return null
        return if (code in CODES) CODES[code] else null
    }

    private fun named(s: String): String? {
        val l = s.lowercase()
        return NAMES.entries.firstOrNull { SportsMatcher.has(l, it.key) }?.value
    }

    /** North America: kept in Sports; any other country is left out. */
    fun northAmerican(code: String?) = code == null || code == "US" || code == "CA"
}

/**
 * Finds the channels that carry ESPN's games, in one pass over each playlist's channels and one over
 * its guide (fed by [addChannel] and [addProgram], from the database: nothing is loaded whole).
 * 1. National TV and streaming from ESPN, matched to channel names (TNT, ESPN2, Prime Video, Fox 9 KMSP…).
 * 2. A programme during the game whose title names both teams.
 * 3. Event channels whose name has both teams ("NHL 03: Red Wings @ Blues").
 * 4. Each team's local network from ESPN (FanDuel Sports Network, Altitude Sports…).
 */
class SportsMatcher(private val games: List<Game>, private val isHidden: (String) -> Boolean = { false }) {

    private class Found(val key: String, val name: String, val pid: String, val country: String?) {
        val kinds = LinkedHashSet<MatchKind>()
        val reasons = ArrayList<String>()
        fun add(kind: MatchKind, reason: String) { kinds.add(kind); if (reason !in reasons) reasons.add(reason) }
    }

    private val found = HashMap<String, LinkedHashMap<String, Found>>() // game id → channel key → found

    // National networks: canonical name → (game, broadcast).
    private val national = HashMap<String, MutableList<Pair<Game, Broadcast>>>()
    // Local networks: their words, per game.
    private val locals = ArrayList<Triple<Game, Broadcast, List<String>>>()
    // Team words → games, to look up only the games a name could be about.
    private val byWord = HashMap<String, MutableSet<Game>>()
    // Guide id → channels carrying it (filled by the channel pass, per playlist).
    private val byEpg = HashMap<String, MutableList<Triple<String, String, String>>>()
    // Country of each channel seen (null = not marked).
    private val countries = HashMap<String, String?>()

    init {
        for (g in games) {
            for (b in g.broadcasts) {
                if (b.market == "national") Networks.canonical(b.name)?.let { national.getOrPut(it) { ArrayList() }.add(g to b) }
                // A local network: matched by its words. Not a big network's name (a local Fox station
                // would match every Fox channel) and nothing too short to be specific.
                else if (Networks.canonical(b.name) == null) Networks.words(Networks.expand(b.name))
                    .takeIf { it.isNotEmpty() && (it.size >= 2 || it[0].length >= 4 || it[0] in Networks.SHORT_LOCALS) }
                    ?.let { locals.add(Triple(g, b, it)) }
            }
            for (t in listOf(g.home, g.away)) for (n in t.matchNames) for (w in words(n)) if (w.length >= 3) byWord.getOrPut(w) { HashSet() }.add(g)
        }
    }

    private fun hit(g: Game, pid: String, key: String, name: String, kind: MatchKind, reason: String) {
        if (isHidden(key)) return
        found.getOrPut(g.id) { LinkedHashMap() }.getOrPut(key) { Found(key, name, pid, countries[key]) }.add(kind, reason)
    }

    /**
     * A channel of playlist [pid] in category [group]. Call for every channel before that playlist's
     * programmes. Channels marked for a country outside North America are left out.
     */
    fun addChannel(pid: String, key: String, name: String, epgId: String?, mappedEpg: String? = null, group: String? = null) {
        val country = Country.of(name, group)
        if (!Country.northAmerican(country)) return
        countries[key] = country
        val e = (mappedEpg ?: epgId)?.takeIf { it.isNotBlank() }
        if (e != null) byEpg.getOrPut("$pid|$e") { ArrayList() }.add(Triple(key, name, pid))
        // 1. National networks.
        val nets = Networks.identities(name)
        for (n in nets) national[n]?.forEach { (g, b) -> hit(g, pid, key, name, MatchKind.NATIONAL, (if (b.kind.equals("Streaming", true)) "Streaming · " else "National TV · ") + b.name) }
        // 3. Event channels naming both teams.
        val lower = name.lowercase()
        for (g in candidates(lower)) if (namesBoth(g, lower)) hit(g, pid, key, name, MatchKind.EVENT, "Event channel · names both teams")
        // 4. Local networks.
        if (locals.isNotEmpty()) {
            val cw = Networks.words(name).toHashSet()
            for ((g, b, w) in locals) if (w.all { it in cw }) {
                val team = if (b.market == "home") g.home.name else if (b.market == "away") g.away.name else null
                hit(g, pid, key, name, MatchKind.LOCAL, (if (team != null) "$team local TV · " else "Local TV · ") + b.name)
            }
        }
    }

    /** A programme of playlist [pid] (after its channels). */
    fun addProgram(pid: String, epgId: String, start: Long, end: Long, title: String, subtitle: String?) {
        val text = (title + " " + (subtitle ?: "")).lowercase()
        val cands = candidates(text)
        if (cands.isEmpty()) return
        for (g in cands) {
            // The listing has to be about this game: starting near its start and running into it.
            if (start > g.start + 60 * 60_000L || start < g.start - 90 * 60_000L || end <= g.start) continue
            if (!namesBoth(g, text)) continue
            val label = if (subtitle != null && namesBoth(g, subtitle.lowercase()) && !namesBoth(g, title.lowercase())) "$title · $subtitle" else title
            byEpg["$pid|$epgId"]?.forEach { (key, name, p) -> hit(g, p, key, name, MatchKind.GUIDE, "In the guide · $label") }
        }
    }

    /** Clears the guide id lookup between playlists (keeps what was found). */
    fun nextPlaylist() = byEpg.clear()

    /** Channels per game id, best first, at most [max] each. */
    fun result(max: Int = 12): Map<String, List<ChannelMatch>> = found.mapValues { (_, m) ->
        m.values.map { ChannelMatch(it.key, it.name, it.pid, it.kinds, it.reasons, it.country) }
            .sortedWith(compareByDescending<ChannelMatch> { it.rank }.thenBy { it.name.length }.thenBy { it.name })
            .take(max)
    }

    private fun candidates(lower: String): Set<Game> {
        val out = HashSet<Game>()
        for (w in words(lower)) byWord[w]?.let(out::addAll)
        return out
    }

    /**
     * Whether [lower] names both teams: each by its full name or nickname, or one of them by its
     * place when the other is named outright ("Washington at NY Giants").
     */
    private fun namesBoth(g: Game, lower: String): Boolean {
        fun strong(t: Team) = listOf(t.fullName, t.name).any { n -> n.length >= 3 && has(lower, n.lowercase()) }
        fun place(t: Team) = t.location.length >= 3 && has(lower, t.location.lowercase())
        val h = strong(g.home); val a = strong(g.away)
        // The same nickname twice (or a place shared by both teams) isn't two teams.
        if (g.home.name.equals(g.away.name, true)) return false
        return (h && a) || (h && place(g.away) && !g.away.location.equals(g.home.location, true)) ||
            (a && place(g.home) && !g.home.location.equals(g.away.location, true))
    }

    companion object {
        fun words(s: String): List<String> = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        /** [needle] as whole words in [hay]. */
        fun has(hay: String, needle: String): Boolean {
            var i = hay.indexOf(needle)
            while (i >= 0) {
                val before = i == 0 || !hay[i - 1].isLetterOrDigit()
                val after = i + needle.length >= hay.length || !hay[i + needle.length].isLetterOrDigit()
                if (before && after) return true
                i = hay.indexOf(needle, i + 1)
            }
            return false
        }
    }
}

/** Network names as ESPN writes them and as providers name channels. */
object Networks {
    private val NOISE = setOf("hd", "fhd", "uhd", "sd", "4k", "hevc", "h264", "h265", "1080p", "720p", "1080", "720", "60fps", "50fps", "fps", "raw", "vip", "backup", "alt", "east", "west", "pacific", "eastern", "feed", "live", "tv", "channel", "us", "usa", "ca", "can", "canada", "en", "eng", "the")
    private val PREFIX = Regex("""^\s*(\[[^\]]*]|\([^)]*\)|[a-z]{2,3}\s*[|:\-]|[a-z]{2,3}\s*\|\s*)\s*""", RegexOption.IGNORE_CASE)

    /** Different spellings of the same network → one name. */
    private val SYNONYMS = mapOf(
        "foxsports1" to "fs1", "foxsports2" to "fs2", "foxsportsone" to "fs1",
        "espnplus" to "espnplus", "espn" to "espn", "espn2" to "espn2", "espnu" to "espnu", "espnews" to "espnews", "abc" to "abc",
        "cbssportsnetwork" to "cbssn", "cbssports" to "cbssn", "cbssn" to "cbssn",
        "nflnetwork" to "nflnetwork", "nflnet" to "nflnetwork", "nfln" to "nflnetwork", "nflredzone" to "nflredzone", "redzone" to "nflredzone",
        "nbatv" to "nbatv", "nhlnetwork" to "nhlnetwork", "nhlnet" to "nhlnetwork", "mlbnetwork" to "mlbnetwork", "mlbnet" to "mlbnetwork", "mlbn" to "mlbnetwork",
        "primevideo" to "primevideo", "amazonprimevideo" to "primevideo", "amazonprime" to "primevideo", "prime" to "primevideo",
        "tnt" to "tnt", "tntsports" to "tnt", "tbs" to "tbs", "trutv" to "trutv",
        "peacock" to "peacock", "nbc" to "nbc", "cbs" to "cbs", "fox" to "fox", "cw" to "cw", "ion" to "ion",
        "appletv" to "appletv", "appletvplus" to "appletv", "paramountplus" to "paramountplus", "netflix" to "netflix", "youtube" to "youtube",
        "tsn" to "tsn", "tsn1" to "tsn", "tsn2" to "tsn", "tsn3" to "tsn", "tsn4" to "tsn", "tsn5" to "tsn",
        "sportsnet" to "sportsnet", "sn" to "sportsnet", "sportsnetone" to "sportsnetone", "sn1" to "sportsnetone", "sportsnet360" to "sportsnet360", "sn360" to "sportsnet360",
        "sportsnetontario" to "sportsnet", "sportsnetpacific" to "sportsnet", "sportsnetwest" to "sportsnet", "sportsneteast" to "sportsnet",
        "cbc" to "cbc", "tvasports" to "tvasports", "rds" to "rds", "rds2" to "rds",
        "golf" to "golf", "nbcsports" to "nbcsports", "usanetwork" to "usanetwork", "usa" to "usanetwork",
        "secnetwork" to "secn", "secn" to "secn", "bigtennetwork" to "btn", "btn" to "btn", "accnetwork" to "accn", "accn" to "accn", "pac12network" to "pac12",
    )

    /** Streaming services: numbered feeds ("ESPN+ 07") are generic, so only plain names count. */
    private val STREAMING = setOf("espnplus", "primevideo", "peacock", "appletv", "paramountplus", "netflix", "youtube", "nflredzone")

    /** Short local network names ESPN uses, spelled out as channels are named. */
    private val EXPAND = mapOf(
        "fdsn" to "fanduel sports network", "msgsn" to "msg sportsnet", "msg" to "msg", "mnmt" to "monumental sports network", "nbcsba" to "nbc sports bay area",
        "nbcsca" to "nbc sports california", "nbcsb" to "nbc sports boston", "nbcsp" to "nbc sports philadelphia", "nbcschi" to "chicago sports network", "sny" to "sny", "yes" to "yes network",
        "masn" to "masn", "nesn" to "nesn", "att sn" to "att sportsnet", "space city" to "space city home network", "kjzz-tv" to "kjzz", "minnhl" to "fanduel sports network north",
    )

    fun expand(name: String): String =
        EXPAND[name.lowercase().trim()] ?: name.split(Regex("\\s+")).joinToString(" ") { EXPAND[it.lowercase()] ?: it }

    /** Short local network names that are specific enough on their own. */
    val SHORT_LOCALS = setOf("sny", "yes", "msg", "nesn", "masn", "tsn", "rds", "kjzz")

    /** Words of a name without provider decoration, for comparing local network names. */
    fun words(name: String): List<String> =
        name.replace(PREFIX, "").lowercase().replace("+", " plus ").split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in NOISE && it != "sports" && it != "network" }

    /** ESPN's broadcast name → one network name (null for names we don't know as a network). */
    fun canonical(espnName: String): String? {
        val k = espnName.lowercase().replace("+", "plus").replace(Regex("[^a-z0-9]"), "")
        return SYNONYMS[k]
    }

    /** The networks a channel name could be: "US| ESPN 2 HD" → espn2, "FOX 9 KMSP" → fox, "TSN3" → tsn. */
    fun identities(channelName: String): Set<String> {
        val raw = channelName.replace(PREFIX, "").lowercase().replace("+", " plus ")
        val toks = raw.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in NOISE }
        if (toks.isEmpty()) return emptySet()
        val out = HashSet<String>()
        SYNONYMS[toks.joinToString("")]?.let(out::add)
        // Local stations: drop channel numbers and call signs ("fox 9 kmsp", "wday abc 6").
        val core = toks.filterNot { it.all(Char::isDigit) || Regex("^[kwc][a-z]{2,3}(dt|tv)?$").matches(it) && SYNONYMS[it] == null }
        if (core.isNotEmpty() && core.size < toks.size) {
            SYNONYMS[core.joinToString("")]?.let { n -> if (n !in STREAMING) out.add(n) }
        }
        return out
    }
}
