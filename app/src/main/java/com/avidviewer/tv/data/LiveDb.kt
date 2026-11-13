package com.avidviewer.tv.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/**
 * Live channels and guide programmes of every playlist, in the same on-device database as the
 * movies & series ([Catalog]), so a download streams in without building the list in memory, and
 * other playlists are looked up (search, favorites, category counts) instead of loaded whole
 * (menu audit DATA-01/DATA-02, work plan 1.5 step 3).
 *
 * channel: k key, p playlist, o position, n name, g category, l logo, u url, e guide id, tk address token.
 * prog: p playlist, e guide id (as the playlist's channels name it), s start, x end, t title, st subtitle, nw new.
 * live_saved: when a playlist's channels ("ch") or guide ("guide") were last saved.
 */
class LiveDb(private val catalog: Catalog) {

    companion object {
        fun create(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE channel (k TEXT PRIMARY KEY, p TEXT NOT NULL, o INTEGER NOT NULL, n TEXT NOT NULL, g TEXT NOT NULL,
                   l TEXT, u TEXT NOT NULL, e TEXT, tk TEXT NOT NULL)""",
            )
            db.execSQL("CREATE INDEX channel_list ON channel (p, o)")
            db.execSQL("CREATE INDEX channel_token ON channel (p, tk)")
            db.execSQL("CREATE INDEX channel_epg ON channel (p, e)")
            db.execSQL("CREATE TABLE prog (p TEXT NOT NULL, e TEXT NOT NULL, s INTEGER NOT NULL, x INTEGER NOT NULL, t TEXT NOT NULL, st TEXT, nw INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX prog_channel ON prog (p, e, s)")
            db.execSQL("CREATE INDEX prog_end ON prog (p, x)")
            db.execSQL("CREATE TABLE live_saved (p TEXT NOT NULL, kind TEXT NOT NULL, saved INTEGER NOT NULL, PRIMARY KEY (p, kind))")
        }

        private const val CH_COLS = "k, n, g, l, u, e"
        private fun channel(c: Cursor) = Channel(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5))
        private fun program(c: Cursor, from: Int) = Program(c.getLong(from), c.getLong(from + 1), c.getString(from + 2), c.getString(from + 3), c.getInt(from + 4) == 1)

        /** Rows per page for the one-at-a-time readers ([forEachChannel], [forEachProgram], [guide]): well inside one CursorWindow. */
        const val PAGE = 500

        /** SQL LIKE pattern for "contains [s]", with LIKE's wildcards escaped. */
        fun contains(s: String) = "%" + s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    }

    private val db get() = catalog.writableDatabase

    private fun strings(sql: String, args: Array<String>): List<String> =
        db.rawQuery(sql, args).use { c -> val l = ArrayList<String>(); while (c.moveToNext()) l.add(c.getString(0)); l }

    // ---------------------------------------------------------------- when saved

    fun savedAt(pid: String, kind: String): Long =
        db.rawQuery("SELECT saved FROM live_saved WHERE p = ? AND kind = ?", arrayOf(pid, kind)).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun channelsSavedAt(pid: String) = savedAt(pid, "ch")
    fun guideSavedAt(pid: String) = savedAt(pid, "guide")

    /** The saved guide was found still current (its sources hadn't changed): counts as freshly downloaded. */
    fun guideChecked(pid: String) = markSaved(db, pid, "guide")

    /** When the last saved programme of a playlist ends (0 = none). */
    fun guideEnd(pid: String): Long = db.rawQuery("SELECT MAX(x) FROM prog WHERE p = ?", arrayOf(pid)).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }

    private fun markSaved(d: SQLiteDatabase, pid: String, kind: String, at: Long = System.currentTimeMillis()) {
        d.insertWithOnConflict("live_saved", null, ContentValues().apply { put("p", pid); put("kind", kind); put("saved", at) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---------------------------------------------------------------- channels

    /**
     * Replaces a playlist's channels with what [fill] hands to `add`, in one transaction (the old list
     * stays if [fill] throws or [keep] says no). The first channel with a given key wins.
     */
    fun <T> replacingChannels(
        pid: String,
        keep: (result: T, added: Int) -> Boolean = { _, _ -> true },
        fill: ((Channel) -> Unit) -> T,
    ): Ingest<T> {
        val d = db
        d.beginTransaction()
        try {
            d.delete("channel", "p = ?", arrayOf(pid))
            val st = d.compileStatement("INSERT OR IGNORE INTO channel (k, p, o, n, g, l, u, e, tk) VALUES (?,?,?,?,?,?,?,?,?)")
            var o = 0L
            var added = 0
            val result = fill { c ->
                st.clearBindings()
                st.bindString(1, c.key); st.bindString(2, pid); st.bindLong(3, o++); st.bindString(4, c.name); st.bindString(5, c.group)
                if (c.logo != null) st.bindString(6, c.logo) else st.bindNull(6)
                st.bindString(7, c.url)
                if (c.epgId != null) st.bindString(8, c.epgId) else st.bindNull(8)
                st.bindString(9, ChannelKeys.token(c.url))
                if (st.executeInsert() != -1L) added++
            }
            if (!keep(result, added)) return Ingest(result, added, committed = false)
            markSaved(d, pid, "ch")
            d.setTransactionSuccessful()
            return Ingest(result, added, committed = true)
        } finally {
            d.endTransaction()
        }
    }

    fun hasChannels(pid: String) = channelsSavedAt(pid) > 0L

    /** A playlist's whole channel list, in the provider's order (only for the playlist being watched). */
    fun channels(pid: String): List<Channel> =
        db.rawQuery("SELECT $CH_COLS FROM channel WHERE p = ? ORDER BY o", arrayOf(pid)).use { c ->
            val l = ArrayList<Channel>(c.count); while (c.moveToNext()) l.add(channel(c)); l
        }

    fun channelCount(pid: String): Int = db.rawQuery("SELECT COUNT(*) FROM channel WHERE p = ?", arrayOf(pid)).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun channelsByKeys(keys: Collection<String>): List<Channel> {
        if (keys.isEmpty()) return emptyList()
        val out = ArrayList<Channel>()
        keys.distinct().chunked(400).forEach { part ->
            db.rawQuery("SELECT $CH_COLS FROM channel WHERE k IN (${part.joinToString(",") { "?" }}) ORDER BY p, o", part.toTypedArray()).use { c ->
                while (c.moveToNext()) out.add(channel(c))
            }
        }
        return out
    }

    /** Channels whose name contains [needle] (case-insensitive for plain letters), in list order. */
    fun searchChannels(pid: String, needle: String, limit: Int): List<Channel> {
        // Every word typed, in any order.
        val words = SearchText.words(needle).ifEmpty { listOf(needle.trim()) }
        val where = words.joinToString(" AND ") { "n LIKE ? ESCAPE '\\'" }
        return db.rawQuery("SELECT $CH_COLS FROM channel WHERE p = ? AND $where ORDER BY o LIMIT $limit", arrayOf(pid) + words.map { contains(it) }).use { c ->
            val l = ArrayList<Channel>(); while (c.moveToNext()) l.add(channel(c)); l
        }
    }

    /** Channels that carry one of these guide ids (as saved by the provider). */
    fun channelsWithEpg(pid: String, ids: Collection<String>): List<Channel> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<Channel>()
        ids.distinct().chunked(400).forEach { part ->
            db.rawQuery("SELECT $CH_COLS FROM channel WHERE p = ? AND e IN (${part.joinToString(",") { "?" }}) ORDER BY o", arrayOf(pid) + part).use { c ->
                while (c.moveToNext()) out.add(channel(c))
            }
        }
        return out
    }

    /** Categories of a playlist in list order, with how many channels each holds. */
    fun groupCounts(pid: String): List<Pair<String, Int>> =
        db.rawQuery("SELECT g, COUNT(*) FROM channel WHERE p = ? GROUP BY g ORDER BY MIN(o)", arrayOf(pid)).use { c ->
            val l = ArrayList<Pair<String, Int>>(); while (c.moveToNext()) l.add(c.getString(0) to c.getInt(1)); l
        }

    /** The guide ids a playlist's channels name. */
    fun epgIds(pid: String): Set<String> = strings("SELECT DISTINCT e FROM channel WHERE p = ? AND e IS NOT NULL AND e != ''", arrayOf(pid)).toHashSet()

    fun channelRefs(pid: String, keys: Collection<String>? = null): List<ChannelKeys.Ref> {
        val out = ArrayList<ChannelKeys.Ref>()
        fun collect(c: Cursor) { while (c.moveToNext()) out.add(ChannelKeys.Ref(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4))) }
        val sql = "SELECT k, tk, n, g, e FROM channel WHERE p = ?"
        if (keys == null) db.rawQuery(sql, arrayOf(pid)).use(::collect)
        else keys.distinct().chunked(400).forEach { part ->
            db.rawQuery("$sql AND k IN (${part.joinToString(",") { "?" }})", arrayOf(pid) + part).use(::collect)
        }
        return out
    }

    /**
     * Old key → new key for channels the user's choices point at, looked up in the newly saved list:
     * same stream address, then guide id + name, then name + category, then a name that appears only
     * once (the rules of [ChannelKeys.remap], without loading the whole new list).
     */
    fun remapChannels(pid: String, old: List<ChannelKeys.Ref>): Map<String, String> {
        if (old.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        val taken = HashSet<String>()
        val unmatched = ArrayList<ChannelKeys.Ref>()
        for (o in old) {
            if (strings("SELECT k FROM channel WHERE p = ? AND k = ? AND tk = ?", arrayOf(pid, o.key, o.token)).isNotEmpty()) taken.add(o.key)
            else unmatched.add(o)
        }
        val byToken = HashMap<ChannelKeys.Ref, List<String>>()
        for (o in unmatched) byToken[o] = strings("SELECT k FROM channel WHERE p = ? AND tk = ? ORDER BY o LIMIT 8", arrayOf(pid, o.token))
        // Name fallbacks: one pass over the list, keeping only rows whose name one of the refs needs.
        val wantedNames = unmatched.filter { byToken[it].isNullOrEmpty() }.mapTo(HashSet()) { ChannelKeys.norm(it.name) }
        data class Row(val key: String, val group: String, val epg: String?)
        val byName = HashMap<String, MutableList<Row>>()
        if (wantedNames.isNotEmpty()) db.rawQuery("SELECT k, n, g, e FROM channel WHERE p = ? ORDER BY o", arrayOf(pid)).use { c ->
            while (c.moveToNext()) {
                val n = ChannelKeys.norm(c.getString(1))
                if (n in wantedNames) byName.getOrPut(n) { ArrayList() }.add(Row(c.getString(0), ChannelKeys.norm(c.getString(2)), c.getString(3)?.lowercase()))
            }
        }
        for (o in unmatched) {
            val named = byName[ChannelKeys.norm(o.name)].orEmpty()
            val hit = byToken[o].orEmpty().firstOrNull { it !in taken }
                ?: o.epgId?.lowercase()?.let { e -> named.filter { it.epg == e }.map { it.key }.firstOrNull { it !in taken } }
                ?: named.filter { it.group == ChannelKeys.norm(o.group) }.map { it.key }.firstOrNull { it !in taken }
                ?: named.takeIf { it.size == 1 }?.first()?.key?.takeIf { it !in taken }
            if (hit != null) { taken.add(hit); if (hit != o.key) out[o.key] = hit }
        }
        return out
    }

    // ---------------------------------------------------------------- guide

    /**
     * Replaces a playlist's programmes with what [fill] hands to `add` (guide id, programme), in one
     * transaction; the old guide stays if [fill] throws or [keep] says no.
     */
    fun <T> replacingGuide(
        pid: String,
        keep: (result: T, added: Int) -> Boolean = { _, _ -> true },
        fill: ((String, Program) -> Unit) -> T,
    ): Ingest<T> {
        val d = db
        d.beginTransaction()
        try {
            d.delete("prog", "p = ?", arrayOf(pid))
            val st = d.compileStatement("INSERT INTO prog (p, e, s, x, t, st, nw) VALUES (?,?,?,?,?,?,?)")
            var added = 0
            val result = fill { e, pr ->
                st.clearBindings()
                st.bindString(1, pid); st.bindString(2, e); st.bindLong(3, pr.start); st.bindLong(4, pr.end); st.bindString(5, pr.title)
                if (pr.subtitle != null) st.bindString(6, pr.subtitle) else st.bindNull(6)
                st.bindLong(7, if (pr.isNew) 1 else 0)
                st.executeInsert(); added++
            }
            if (!keep(result, added)) return Ingest(result, added, committed = false)
            markSaved(d, pid, "guide")
            d.setTransactionSuccessful()
            return Ingest(result, added, committed = true)
        } finally {
            d.endTransaction()
        }
    }

    /** Programmes between [from] and [to] by guide id (null [ids] = every channel), each list by start time. */
    fun guide(pid: String, ids: Collection<String>?, from: Long, to: Long): Map<String, List<Program>> {
        val out = HashMap<String, MutableList<Program>>()
        fun collect(c: Cursor) { while (c.moveToNext()) out.getOrPut(c.getString(0)) { ArrayList() }.add(program(c, 1)) }
        val sql = "SELECT e, s, x, t, st, nw FROM prog WHERE p = ? AND x > ? AND s < ?"
        if (ids == null) {
            // A whole playlist's guide can be tens of thousands of rows: read it in pages (see [pages]), then sort.
            pages("e, s, x, t, st, nw", "prog", "p = ? AND x > ? AND s < ?", arrayOf(pid, from.toString(), to.toString()), "x") { c ->
                out.getOrPut(c.getString(2)) { ArrayList() }.add(program(c, 3))
            }
            out.values.forEach { l -> l.sortBy { it.start } }
        }
        else ids.distinct().chunked(400).forEach { part ->
            if (part.isNotEmpty()) db.rawQuery("$sql AND e IN (${part.joinToString(",") { "?" }}) ORDER BY e, s", arrayOf(pid, from.toString(), to.toString()) + part).use(::collect)
        }
        return out
    }

    /** Programmes not yet over whose title or subtitle contains [needle], soonest first: (guide id, programme). */
    fun searchPrograms(pid: String, needle: String, after: Long, limit: Int): List<Pair<String, Program>> {
        // Every word typed, in any order, in the title and subtitle together.
        val words = SearchText.words(needle).ifEmpty { listOf(needle.trim()) }
        val where = words.joinToString(" AND ") { "(t || ' ' || IFNULL(st, '')) LIKE ? ESCAPE '\\'" }
        return db.rawQuery(
            "SELECT e, s, x, t, st, nw FROM prog WHERE p = ? AND x > ? AND $where ORDER BY s LIMIT $limit",
            arrayOf(pid, after.toString()) + words.map { contains(it) },
        ).use { c -> val l = ArrayList<Pair<String, Program>>(); while (c.moveToNext()) l.add(c.getString(0) to program(c, 1)); l }
    }

    /** How many channels have programmes saved. */
    fun guideChannelCount(pid: String): Int = db.rawQuery("SELECT COUNT(DISTINCT e) FROM prog WHERE p = ?", arrayOf(pid)).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    // ---------------------------------------------------------------- passes (Sports matching)

    /**
     * Reads `SELECT rowid, [key], [cols] FROM [table] WHERE [where]` a page of [PAGE] rows at a time, in
     * the order of an index that starts with the playlist and then [key] (an integer column), handing
     * each row to [row] with the selected [cols] starting at index 2.
     *
     * One long cursor over thousands of rows has to refill its window part-way through; if a guide or
     * channel download replaces the same rows in between, the refill finds fewer rows than it counted
     * and Android throws "Couldn't read row N from CursorWindow" (issue #9). A page always fits in one
     * window, so it is read in a single pass, and each page sees the table as it is when it starts.
     *
     * Each page carries on from the last row seen by ([key], rowid), which is the order of the index
     * (an index ends with the rowid), so SQLite walks the index and stops after [PAGE] rows. Paging by
     * rowid alone (1.2.0) made SQLite gather and sort every remaining row of the playlist for every page
     * when the database has no statistics, so a week of guide took many minutes on a Fire TV.
     */
    private inline fun pages(cols: String, table: String, where: String, args: Array<String>, key: String, row: (Cursor) -> Unit) {
        var lastKey: Long? = null
        var lastRow = 0L
        while (true) {
            var n = 0
            val k = lastKey
            val (more, moreArgs) = if (k == null) "" to emptyArray<String>()
                else " AND $key >= ? AND ($key > ? OR rowid > ?)" to arrayOf(k.toString(), k.toString(), lastRow.toString())
            db.rawQuery(
                "SELECT rowid, $key, $cols FROM $table WHERE $where$more ORDER BY $key, rowid LIMIT $PAGE",
                args + moreArgs,
            ).use { c -> while (c.moveToNext()) { lastRow = c.getLong(0); lastKey = c.getLong(1); row(c); n++ } }
            if (n < PAGE) break
        }
    }

    /** The query plan [pages] gets for the guide (tests check it walks the index without sorting). */
    fun programPagePlan(pid: String): List<String> = plan(
        "SELECT rowid, x, e, s, x, t, st FROM prog WHERE p = ? AND x > ? AND s < ? AND x >= ? AND (x > ? OR rowid > ?) ORDER BY x, rowid LIMIT $PAGE",
        arrayOf(pid, "0", "1", "0", "0", "0"),
    )

    /** The query plan [pages] gets for channels. */
    fun channelPagePlan(pid: String): List<String> = plan(
        "SELECT rowid, o, k, n, g, e FROM channel WHERE p = ? AND o >= ? AND (o > ? OR rowid > ?) ORDER BY o, rowid LIMIT $PAGE",
        arrayOf(pid, "0", "0", "0"),
    )

    private fun plan(sql: String, args: Array<String>): List<String> =
        db.rawQuery("EXPLAIN QUERY PLAN $sql", args).use { c -> buildList { while (c.moveToNext()) add(c.getString(c.columnCount - 1)) } }

    /** Every channel of a playlist, one at a time: key, name, category, guide id. */
    fun forEachChannel(pid: String, fn: (String, String, String, String?) -> Unit) {
        pages("k, n, g, e", "channel", "p = ?", arrayOf(pid), "o") { c -> fn(c.getString(2), c.getString(3), c.getString(4), c.getString(5)) }
    }

    /** Every programme of a playlist overlapping [from]..[to], one at a time: guide id, start, end, title, subtitle. */
    fun forEachProgram(pid: String, from: Long, to: Long, fn: (String, Long, Long, String, String?) -> Unit) {
        pages("e, s, x, t, st", "prog", "p = ? AND x > ? AND s < ?", arrayOf(pid, from.toString(), to.toString()), "x") { c ->
            fn(c.getString(2), c.getLong(3), c.getLong(4), c.getString(5), c.getString(6))
        }
    }

    // ---------------------------------------------------------------- housekeeping

    fun delete(pid: String) {
        val d = db
        d.delete("channel", "p = ?", arrayOf(pid))
        d.delete("prog", "p = ?", arrayOf(pid))
        d.delete("live_saved", "p = ?", arrayOf(pid))
    }

    fun retainOnly(pids: Collection<String>) {
        val keep = pids.toSet()
        strings("SELECT DISTINCT p FROM live_saved", emptyArray()).filter { it !in keep }.forEach { delete(it) }
    }

    /**
     * Moves channel and guide files saved by older versions (channels_<id>.json, guide_<id>.json) into
     * the database, then deletes them. A file is newer than anything in the database, so it wins.
     */
    @Synchronized
    fun importFiles(store: Store) {
        val dir = store.dataDir
        dir.listFiles { f -> f.name.startsWith("channels_") && f.name.endsWith(".json") }?.forEach { f ->
            val pid = f.name.removePrefix("channels_").removeSuffix(".json")
            runCatching {
                val at = f.lastModified()
                replacingChannels(pid) { add -> store.forEachSavedChannel(pid, add) }
                db.update("live_saved", ContentValues().apply { put("saved", at) }, "p = ? AND kind = 'ch'", arrayOf(pid))
                f.delete()
            }
        }
        dir.listFiles { f -> f.name.startsWith("guide_") && f.name.endsWith(".json") }?.forEach { f ->
            val pid = f.name.removePrefix("guide_").removeSuffix(".json")
            runCatching {
                val (g, at) = store.loadGuide(pid)
                replacingGuide(pid) { add -> g.forEach { (e, list) -> list.forEach { add(e, it) } } }
                if (at > 0) db.update("live_saved", ContentValues().apply { put("saved", at) }, "p = ? AND kind = 'guide'", arrayOf(pid))
                f.delete()
            }
        }
    }

    /** True when an older version's files are still waiting to be moved in (cheap check). */
    fun hasOldFiles(store: Store): Boolean =
        store.dataDir.listFiles { f -> (f.name.startsWith("channels_") || f.name.startsWith("guide_")) && f.name.endsWith(".json") }?.isNotEmpty() == true

}
