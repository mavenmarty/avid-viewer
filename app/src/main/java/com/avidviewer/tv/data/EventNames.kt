package com.avidviewer.tv.data

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Guide listings for event channels that have no guide id, read from the channel's own name.
 *
 * Providers rename their event slots to say what's on, in a handful of formats:
 *
 *     LIVE | GRAND SLAM OF CURLING | Sat 21 Nov 06:30 EST (US) | 8K EXCLUSIVE | US: ESPN+ PPV 2
 *     Next | Cheminots vs. Kouilou | Ligue 1 | 2026-11-21 | 13:00 (GMT) | GB: DAZN PPV 22
 *     US (ESPN+ 209) | Real Madrid vs. Celta de Vigo Nov 21 2:30PM ET (2026-11-21 14:30:10)
 *     NCAAM: Siena vs Bucknell @ Nov 21 11:00 AM :NCAAB  02
 *     LIVE EVENT 01 - 10am UFC Fight Night Prochazka v Stirling
 *     - NO EVENT STREAMING - | 8K EXCLUSIVE | US: ESPN+ PPV 527
 *
 * Such a channel gets the guide id [ID_PREFIX] + its key (see AppViewModel.epgIdOf), and
 * [LiveDb.guide] answers that id with [programs]: "Next: …", the event, "Ended: …", or
 * "No event scheduled". Nothing is saved: the listings follow the name as the provider changes it.
 * Times without a zone are US Eastern, which is what the names that do give a zone use
 * (a group starting "UK" uses UK time).
 */
object EventNames {
    const val ID_PREFIX = "event:"
    const val EVENT_MS = 3 * 3600_000L
    private const val HOUR = 3600_000L
    private const val DAY = 24 * HOUR

    /** A parsed name: [title] null = the slot says no event is on. [start] in ms. */
    data class Event(val title: String?, val start: Long)

    private val EASTERN: TimeZone = TimeZone.getTimeZone("America/New_York")
    private val UK: TimeZone = TimeZone.getTimeZone("Europe/London")
    private val ZONES: Map<String, TimeZone> = buildMap {
        listOf("ET", "EST", "EDT").forEach { put(it, EASTERN) }
        listOf("CT", "CST", "CDT").forEach { put(it, TimeZone.getTimeZone("America/Chicago")) }
        listOf("MT", "MST", "MDT").forEach { put(it, TimeZone.getTimeZone("America/Denver")) }
        listOf("PT", "PST", "PDT").forEach { put(it, TimeZone.getTimeZone("America/Los_Angeles")) }
        listOf("GMT", "UTC").forEach { put(it, TimeZone.getTimeZone("UTC")) }
        listOf("BST", "UK").forEach { put(it, UK) }
        listOf("CET", "CEST").forEach { put(it, TimeZone.getTimeZone("Europe/Paris")) }
        listOf("AEST", "AEDT").forEach { put(it, TimeZone.getTimeZone("Australia/Sydney")) }
    }
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private const val STATUS = "(?:LIVE|ENDED|END|NEXT|UPCOMING|SOON|STARTING SOON)"
    private val RE_A = Regex(
        "^\\s*$STATUS\\s*\\|\\s*(.+?)\\s*\\|\\s*(?:[A-Za-z]{3}\\s+)?(\\d{1,2})\\s+([A-Za-z]{3})\\s+(\\d{1,2}):(\\d{2})\\s*([A-Z]{2,4})?\\b",
        RegexOption.IGNORE_CASE)
    private val RE_B = Regex(
        "^\\s*$STATUS\\s*\\|\\s*(.+?)\\s*\\|.*?\\|\\s*(\\d{4}-\\d{2}-\\d{2}|\\d{2}-\\d{2}-\\d{4})\\s*\\|\\s*(\\d{1,2}):(\\d{2})\\s*\\(([A-Z]{2,4})\\)",
        RegexOption.IGNORE_CASE)
    private val RE_C = Regex("^(.*?)\\s*\\((\\d{4})-(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2})(?::\\d{2})?\\)\\s*$")
    private val RE_D = Regex(
        "^(.+?)\\s*@\\s*([A-Za-z]{3})\\s+(\\d{1,2})\\s+(\\d{1,2}):(\\d{2})\\s*(AM|PM)?\\s*:.+$", RegexOption.IGNORE_CASE)
    private val RE_E = Regex(
        "^LIVE EVENT\\s*\\d+\\s*-\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val RE_NO_EVENT = Regex("no event|no stream|^\\s*:|\\(2098-", RegexOption.IGNORE_CASE)
    private val RE_SLOT_PREFIX = Regex("^\\s*(?:(?:[A-Z]{2}\\s*)?\\([^)|]{1,30}\\)\\s*)+\\|\\s*(.*)$")
    private val RE_TRAILING_TIME = Regex(
        "\\s+[A-Za-z]{3}\\s+\\d{1,2}\\s+\\d{1,2}:\\d{2}\\s*(?:AM|PM)?\\s*(?:ET|EST|EDT|PT|CT)?\\s*$", RegexOption.IGNORE_CASE)

    private val cache = object : LinkedHashMap<String, Any>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?) = size > 4000
    }
    private val NONE = Any()
    private var cacheDay = 0L

    /** The event a channel's name describes, or null if the name isn't an event slot. */
    fun parse(name: String, group: String = "", now: Long = System.currentTimeMillis()): Event? {
        val k = "$group\u0000$name"
        val day = now / DAY   // "LIVE EVENT 01 - 10am …" means today, so answers are kept for one day
        synchronized(cache) { if (day != cacheDay) { cache.clear(); cacheDay = day } }
        synchronized(cache) { cache[k] }?.let { return if (it === NONE) null else it as Event }
        val e = runCatching { parseNow(name, group, now) }.getOrNull()
        synchronized(cache) { cache[k] = e ?: NONE }
        return e
    }

    private fun parseNow(name: String, group: String, now: Long): Event? {
        val n = name.trim()
        if (n.isEmpty() || n.startsWith("#")) return null
        val local = if (Regex("^\\s*UK\\b", RegexOption.IGNORE_CASE).containsMatchIn(group)) UK else EASTERN

        RE_A.find(n)?.let { m ->
            val v = m.groupValues
            val mon = month(v[3]) ?: return@let
            val zone = ZONES[v[6].uppercase(Locale.US)] ?: EASTERN
            val day = v[2].toInt()
            at(yearFor(mon, day, now), mon, day, v[4].toInt(), v[5].toInt(), zone)?.let { return Event(clean(v[1]), it) }
        }
        RE_B.find(n)?.let { m ->
            val v = m.groupValues
            val d = v[2]
            val (y, mo, day) = if (d[4] == '-') Triple(d.substring(0, 4).toInt(), d.substring(5, 7).toInt(), d.substring(8, 10).toInt())
                else Triple(d.substring(6, 10).toInt(), d.substring(3, 5).toInt(), d.substring(0, 2).toInt())
            at(y, mo, day, v[3].toInt(), v[4].toInt(), ZONES[v[5].uppercase(Locale.US)] ?: ZONES.getValue("UTC"))?.let { return Event(clean(v[1]), it) }
        }
        if (RE_NO_EVENT.containsMatchIn(n)) return Event(null, 0L)
        RE_C.find(n)?.let { m ->
            val v = m.groupValues
            val head = RE_SLOT_PREFIX.find(v[1])?.groupValues?.get(1) ?: v[1]
            val title = clean(head)
            if (title.isNotEmpty()) at(v[2].toInt(), v[3].toInt(), v[4].toInt(), v[5].toInt(), v[6].toInt(), EASTERN)?.let { return Event(title, it) }
        }
        RE_D.find(n)?.let { m ->
            val v = m.groupValues
            val mon = month(v[2]) ?: return@let
            val day = v[3].toInt()
            at(yearFor(mon, day, now), mon, day, hour(v[4].toInt(), v[6]), v[5].toInt(), EASTERN)?.let { return Event(clean(v[1]), it) }
        }
        RE_E.find(n)?.let { m ->
            val v = m.groupValues
            val c = Calendar.getInstance(local).apply { timeInMillis = now }
            at(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                hour(v[1].toInt(), v[3]), v[2].ifEmpty { "0" }.toInt(), local)?.let { return Event(clean(v[4]), it) }
        }
        return null
    }

    /** Listings covering [from]–[to] for an event: before, during ([EVENT_MS]) and after. */
    fun programs(e: Event, from: Long, to: Long): List<Program> {
        if (to <= from) return emptyList()
        val title = e.title
        val end = e.start + EVENT_MS
        if (title == null || end < from - DAY) return listOf(Program(from, to, "No event scheduled", null, false))
        if (end <= from) return listOf(Program(from, to, "Ended: $title", null, false))
        if (e.start >= to) return listOf(Program(from, to, "Next: $title", null, false))
        val out = ArrayList<Program>(3)
        if (e.start > from) out.add(Program(from, e.start, "Next: $title", null, false))
        out.add(Program(maxOf(e.start, from), minOf(end, to), title, "Live event", false))
        if (end < to) out.add(Program(end, to, "Ended: $title", null, false))
        return out
    }

    private fun month(s: String): Int? = MONTHS.indexOf(s.lowercase(Locale.US)).takeIf { it >= 0 }?.plus(1)

    private fun hour(h: Int, ap: String?): Int = when (ap?.uppercase(Locale.US)) {
        "PM" -> if (h < 12) h + 12 else h
        "AM" -> if (h == 12) 0 else h
        else -> h
    }

    /** "Nov 21" has no year: the one that puts it within six months of now. */
    private fun yearFor(month: Int, day: Int, now: Long): Int {
        val c = Calendar.getInstance(EASTERN).apply { timeInMillis = now }
        val y = c.get(Calendar.YEAR)
        val cand = at(y, month, day, 12, 0, EASTERN) ?: return y
        return when {
            cand - now > 180 * DAY -> y - 1
            now - cand > 180 * DAY -> y + 1
            else -> y
        }
    }

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: TimeZone): Long? {
        if (mo !in 1..12 || d !in 1..31 || h !in 0..23 || mi !in 0..59) return null
        return Calendar.getInstance(zone).apply {
            isLenient = false
            clear(); set(y, mo - 1, d, h, mi, 0)
        }.let { runCatching { it.timeInMillis }.getOrNull() }
    }

    private fun clean(t: String): String =
        t.replace(RE_TRAILING_TIME, "").replace(Regex("\\s+"), " ").trim(' ', '|', '-', ':')
}
