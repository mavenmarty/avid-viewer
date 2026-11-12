package com.avidviewer.tv.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** What a streamed save did: [fill]'s result, how many titles were added, and whether they replaced the old ones. */
data class Ingest<T>(val result: T, val added: Int, val committed: Boolean)

/**
 * Movies and series of every playlist, on the device (SQLite), so screens read a page at a time
 * instead of holding the whole catalog in memory (menu audit DATA-01, work plan 1.5).
 *
 * Columns: k key, p playlist, t kind (movie/series/episode), g kind group ("movie" for movies and
 * episodes, "series" for series), c category ("Other" when blank), n name, nn normalized name (for
 * search and matching), l logo, u url, s series id, a added, r rating, y year, o position in the
 * provider's list, h hidden (1 when the title or its category is hidden by the user), tk the
 * stream address token (ChannelKeys.token), to follow saved choices when keys change.
 */
class Catalog(context: Context) : SQLiteOpenHelper(context.applicationContext, "catalog.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE title (k TEXT PRIMARY KEY, p TEXT NOT NULL, t TEXT NOT NULL, g TEXT NOT NULL, c TEXT NOT NULL,
               n TEXT NOT NULL, nn TEXT NOT NULL, l TEXT, u TEXT NOT NULL, s TEXT, a INTEGER NOT NULL DEFAULT 0,
               r TEXT NOT NULL DEFAULT '', y TEXT NOT NULL DEFAULT '', o INTEGER NOT NULL, h INTEGER NOT NULL DEFAULT 0, tk TEXT)""",
        )
        db.execSQL("CREATE INDEX title_list ON title (g, h, c, p, o)")
        db.execSQL("CREATE INDEX title_all ON title (g, h, p, o)")
        db.execSQL("CREATE INDEX title_added ON title (g, a)")
        db.execSQL("CREATE INDEX title_name ON title (g, nn)")
        db.execSQL("CREATE INDEX title_playlist ON title (p, o)")
        db.execSQL("CREATE TABLE source (p TEXT PRIMARY KEY, saved INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX title_token ON title (p, tk)")
        LiveDb.create(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 2: stream-address token, to follow titles to new keys after a download (ChannelKeys).
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE title ADD COLUMN tk TEXT")
            db.execSQL("CREATE INDEX title_token ON title (p, tk)")
        }
        // 3: live channels and guide programmes (LiveDb).
        if (oldVersion < 3) LiveDb.create(db)
    }

    override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }

    companion object {
        fun group(kind: String) = if (kind == "series") "series" else "movie"
        fun cat(v: VodItem) = v.category.ifBlank { "Other" }
        private const val COLS = "k, p, t, c, n, l, u, s, a, r, y"
        fun read(c: Cursor) = VodItem(
            key = c.getString(0), playlistId = c.getString(1), kind = c.getString(2), category = c.getString(3),
            name = c.getString(4), logo = c.getString(5), url = c.getString(6), seriesId = c.getString(7),
            added = c.getLong(8), rating = c.getString(9), year = c.getString(10),
        )
    }

    /** When this playlist's titles were last saved (0 = never). */
    fun savedAt(pid: String): Long = readableDatabase.rawQuery("SELECT saved FROM source WHERE p = ?", arrayOf(pid)).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun has(pid: String): Boolean = savedAt(pid) > 0L

    /**
     * Replaces a playlist's titles, in batches inside one transaction (readers see the old list until it
     * commits). [hidden] says which titles the user has hidden.
     */
    fun replace(pid: String, items: Sequence<VodItem>, hidden: (VodItem) -> Boolean) {
        replacing(pid, hidden) { add -> items.forEach(add) }
    }

    /**
     * Replaces a playlist's titles with what [fill] hands to `add`, as it reads them (a download can
     * stream straight in, nothing is collected in memory). One transaction: readers keep seeing the old
     * titles until it commits, and if [fill] throws (a failed download) the old titles stay.
     * The first title with a given key wins, as before. [keep] decides, once [fill] is done, whether
     * the new titles replace the old (false keeps the old ones, e.g. when part of a download failed).
     */
    fun <T> replacing(
        pid: String,
        hidden: (VodItem) -> Boolean,
        keep: (result: T, added: Int) -> Boolean = { _, _ -> true },
        fill: ((VodItem) -> Unit) -> T,
    ): Ingest<T> {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("title", "p = ?", arrayOf(pid))
            val st = db.compileStatement("INSERT OR IGNORE INTO title (k, p, t, g, c, n, nn, l, u, s, a, r, y, o, h, tk) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
            var o = 0L
            var added = 0
            val result = fill { v ->
                st.clearBindings()
                st.bindString(1, v.key); st.bindString(2, pid); st.bindString(3, v.kind); st.bindString(4, group(v.kind)); st.bindString(5, cat(v))
                st.bindString(6, v.name); st.bindString(7, Tmdb.normalize(v.name))
                if (v.logo != null) st.bindString(8, v.logo) else st.bindNull(8)
                st.bindString(9, v.url)
                if (v.seriesId != null) st.bindString(10, v.seriesId) else st.bindNull(10)
                st.bindLong(11, v.added); st.bindString(12, v.rating); st.bindString(13, v.year); st.bindLong(14, o++); st.bindLong(15, if (hidden(v)) 1 else 0)
                st.bindString(16, ChannelKeys.token(v.url))
                if (st.executeInsert() != -1L) added++
            }
            if (!keep(result, added)) return Ingest(result, added, committed = false)
            db.insertWithOnConflict("source", null, ContentValues().apply { put("p", pid); put("saved", System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
            return Ingest(result, added, committed = true)
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Old key → new key for titles the user's choices point at, looked up in the newly saved titles:
     * same stream address first, then name + category, then a name that appears only once
     * (the same rules as [ChannelKeys.remap], without loading the whole new list).
     */
    fun remap(pid: String, old: List<ChannelKeys.Ref>): Map<String, String> {
        if (old.isEmpty()) return emptyMap()
        val db = readableDatabase
        val out = HashMap<String, String>()
        val taken = HashSet<String>()
        val unmatched = ArrayList<ChannelKeys.Ref>()
        fun keys(sql: String, args: Array<String>): List<String> =
            db.rawQuery(sql, args).use { c -> val l = ArrayList<String>(); while (c.moveToNext()) l.add(c.getString(0)); l }
        // Same key, same address: nothing moved.
        for (o in old) {
            if (keys("SELECT k FROM title WHERE p = ? AND k = ? AND tk = ?", arrayOf(pid, o.key, o.token)).isNotEmpty()) taken.add(o.key)
            else unmatched.add(o)
        }
        val byToken = HashMap<ChannelKeys.Ref, List<String>>()
        for (o in unmatched) byToken[o] = keys("SELECT k FROM title WHERE p = ? AND tk = ? ORDER BY o LIMIT 8", arrayOf(pid, o.token))
        // Name fallbacks: one pass over the playlist, keeping only rows whose name one of the refs needs.
        val wantedNames = unmatched.filter { byToken[it].isNullOrEmpty() }.mapTo(HashSet()) { ChannelKeys.norm(it.name) }
        val byName = HashMap<String, MutableList<Pair<String, String>>>()   // norm name → (key, norm category), in list order
        if (wantedNames.isNotEmpty()) db.rawQuery("SELECT k, n, c FROM title WHERE p = ? ORDER BY o", arrayOf(pid)).use { c ->
            while (c.moveToNext()) {
                val n = ChannelKeys.norm(c.getString(1))
                if (n in wantedNames) byName.getOrPut(n) { ArrayList() }.add(c.getString(0) to ChannelKeys.norm(c.getString(2)))
            }
        }
        for (o in unmatched) {
            val n = ChannelKeys.norm(o.name)
            val named = byName[n].orEmpty()
            val hit = byToken[o].orEmpty().firstOrNull { it !in taken }
                ?: named.filter { it.second == ChannelKeys.norm(o.group.ifBlank { "Other" }) }.map { it.first }.firstOrNull { it !in taken }
                ?: named.takeIf { it.size == 1 }?.first()?.first?.takeIf { it !in taken }
            if (hit != null) { taken.add(hit); if (hit != o.key) out[o.key] = hit }
        }
        return out
    }

    /** Makes the next sync download this playlist's titles again (they stay visible meanwhile). */
    fun markStale(pid: String) { writableDatabase.execSQL("UPDATE source SET saved = 1 WHERE p = ?", arrayOf(pid)) }

    /** Drops titles of playlists that no longer exist. */
    fun retainOnly(pids: Collection<String>) {
        val keep = pids.toSet()
        val all = readableDatabase.rawQuery("SELECT p FROM source", null).use { c -> val out = ArrayList<String>(); while (c.moveToNext()) out.add(c.getString(0)); out }
        all.filter { it !in keep }.forEach { delete(it) }
    }

    fun delete(pid: String) {
        val db = writableDatabase
        db.delete("title", "p = ?", arrayOf(pid))
        db.delete("source", "p = ?", arrayOf(pid))
    }

    /** Recomputes the hidden flag from the user's hidden categories ("movie|pid|cat") and titles. */
    fun applyHidden(hiddenCats: Set<String>, hiddenTitles: Set<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE title SET h = 0 WHERE h = 1")
            for (key in hiddenCats) {
                val parts = key.split('|', limit = 3)
                if (parts.size == 3) db.execSQL("UPDATE title SET h = 1 WHERE g = ? AND p = ? AND c = ?", arrayOf(parts[0], parts[1], parts[2]))
            }
            hiddenTitles.chunked(400).forEach { chunk ->
                db.execSQL("UPDATE title SET h = 1 WHERE k IN (${chunk.joinToString(",") { "?" }})", chunk.toTypedArray())
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Applies only what changed in the hidden categories and titles (fast enough to run as the user
     * presses the button, so lists are right immediately).
     */
    fun applyHiddenChange(beforeCats: Set<String>, afterCats: Set<String>, beforeTitles: Set<String>, afterTitles: Set<String>) {
        val db = writableDatabase
        fun catArgs(key: String) = key.split('|', limit = 3).takeIf { it.size == 3 }?.toTypedArray()
        db.beginTransaction()
        try {
            for (k in afterCats - beforeCats) catArgs(k)?.let { db.execSQL("UPDATE title SET h = 1 WHERE g = ? AND p = ? AND c = ?", it) }
            for (k in beforeCats - afterCats) catArgs(k)?.let { a ->
                db.execSQL("UPDATE title SET h = 0 WHERE g = ? AND p = ? AND c = ?", a)
                afterTitles.chunked(400).forEach { ch -> db.execSQL("UPDATE title SET h = 1 WHERE g = ? AND p = ? AND c = ? AND k IN (${ch.joinToString(",") { "?" }})", a + ch) }
            }
            (afterTitles - beforeTitles).chunked(400).forEach { ch -> db.execSQL("UPDATE title SET h = 1 WHERE k IN (${ch.joinToString(",") { "?" }})", ch.toTypedArray()) }
            val shown = (beforeTitles - afterTitles).toList()
            shown.chunked(400).forEach { ch ->
                db.execSQL("UPDATE title SET h = 0 WHERE k IN (${ch.joinToString(",") { "?" }})", ch.toTypedArray())
                for (k in afterCats) catArgs(k)?.let { a -> db.execSQL("UPDATE title SET h = 1 WHERE g = ? AND p = ? AND c = ? AND k IN (${ch.joinToString(",") { "?" }})", a + ch) }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Which rows a list shows. */
    data class Query(
        val group: String,
        val playlists: List<String>,
        val includeHidden: Boolean = false,
        val category: String? = null,
        val nameContains: String? = null,
        val newestFirst: Boolean = false,
        val limit: Int? = null,
        /** Only this exact kind (e.g. "episode" within the movie group). */
        val kind: String? = null,
    ) {
        fun where(): Pair<String, Array<String>> {
            val sb = StringBuilder("g = ?")
            val args = ArrayList<String>(); args.add(group)
            if (!includeHidden) sb.append(" AND h = 0")
            if (category != null) { sb.append(" AND c = ?"); args.add(category) }
            if (kind != null) { sb.append(" AND t = ?"); args.add(kind) }
            sb.append(" AND p IN (${playlists.joinToString(",") { "?" }})"); args.addAll(playlists)
            nameContains?.takeIf { it.isNotBlank() }?.let { sb.append(" AND n LIKE ? ESCAPE '\\'"); args.add("%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%") }
            return sb.toString() to args.toTypedArray()
        }
        // Playlists in the user's order, then the provider's order within each.
        fun order(): String = if (newestFirst) "a DESC, o" else "CASE p ${playlists.mapIndexed { i, p -> "WHEN '${p.replace("'", "''")}' THEN $i" }.joinToString(" ")} END, o"
    }

    fun count(q: Query): Int {
        if (q.playlists.isEmpty()) return 0
        val (w, a) = q.where()
        val n = readableDatabase.rawQuery("SELECT COUNT(*) FROM title WHERE $w", a).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return q.limit?.let { minOf(it, n) } ?: n
    }

    fun page(q: Query, offset: Int, size: Int): List<VodItem> {
        if (q.playlists.isEmpty()) return emptyList()
        val (w, a) = q.where()
        val lim = q.limit?.let { minOf(size, it - offset) } ?: size
        if (lim <= 0) return emptyList()
        return readableDatabase.rawQuery("SELECT $COLS FROM title WHERE $w ORDER BY ${q.order()} LIMIT $lim OFFSET $offset", a).use { c ->
            val out = ArrayList<VodItem>(c.count); while (c.moveToNext()) out.add(read(c)); out
        }
    }

    /** Categories with title counts, per playlist, in the provider's order: (playlist, category, count). */
    fun categories(group: String, playlists: List<String>, includeHidden: Boolean): List<Triple<String, String, Int>> {
        if (playlists.isEmpty()) return emptyList()
        val q = Query(group, playlists, includeHidden)
        val (w, a) = q.where()
        return readableDatabase.rawQuery("SELECT p, c, COUNT(*), MIN(o) FROM title WHERE $w GROUP BY p, c", a).use { c ->
            val rows = ArrayList<Pair<Triple<String, String, Int>, Long>>()
            while (c.moveToNext()) rows.add(Triple(c.getString(0), c.getString(1), c.getInt(2)) to c.getLong(3))
            rows.sortedWith(compareBy({ playlists.indexOf(it.first.first) }, { it.second })).map { it.first }
        }
    }

    fun byKeys(keys: Collection<String>): List<VodItem> {
        val out = ArrayList<VodItem>()
        keys.toList().chunked(400).forEach { chunk ->
            readableDatabase.rawQuery("SELECT $COLS FROM title WHERE k IN (${chunk.joinToString(",") { "?" }})", chunk.toTypedArray()).use { c ->
                while (c.moveToNext()) out.add(read(c))
            }
        }
        val pos = keys.withIndex().associate { it.value to it.index }
        return out.sortedBy { pos[it.key] ?: Int.MAX_VALUE }
    }

    /** The first title of this kind whose normalized name matches (Discover's "On your provider"). */
    fun findByName(group: String, normalized: String, playlists: List<String>): VodItem? {
        if (playlists.isEmpty() || normalized.isBlank()) return null
        val q = Query(group, playlists, includeHidden = true)
        val (w, a) = q.where()
        return readableDatabase.rawQuery("SELECT $COLS FROM title WHERE nn = ? AND $w ORDER BY ${q.order()} LIMIT 1", arrayOf(normalized) + a).use { c ->
            if (c.moveToFirst()) read(c) else null
        }
    }

    /** Playlists that have a category of this kind (to hide it everywhere). */
    fun playlistsWithCategory(group: String, category: String): List<String> =
        readableDatabase.rawQuery("SELECT DISTINCT p FROM title WHERE g = ? AND c = ?", arrayOf(group, category)).use { c ->
            val out = ArrayList<String>(); while (c.moveToNext()) out.add(c.getString(0)); out
        }

    /** What's needed to find a playlist's titles again after its list changes (see [ChannelKeys]). */
    fun refs(pid: String, keys: Collection<String>? = null): List<ChannelKeys.Ref> {
        val sql = "SELECT k, u, n, c FROM title WHERE p = ?"
        val out = ArrayList<ChannelKeys.Ref>()
        fun collect(c: Cursor) { while (c.moveToNext()) out.add(ChannelKeys.Ref(c.getString(0), ChannelKeys.token(c.getString(1)), c.getString(2), c.getString(3), null, isVod = true)) }
        if (keys == null) readableDatabase.rawQuery(sql, arrayOf(pid)).use(::collect)
        else keys.toList().chunked(400).forEach { chunk ->
            readableDatabase.rawQuery("$sql AND k IN (${chunk.joinToString(",") { "?" }})", arrayOf(pid) + chunk).use(::collect)
        }
        return out
    }
}

/**
 * A list of titles that reads pages from the [Catalog] as they're shown: a 15,000-title category costs
 * a few pages of memory, not 15,000 objects. Snapshot semantics: it describes one query; a changed
 * catalog makes a new list.
 */
class PagedTitles(private val catalog: Catalog, val query: Catalog.Query, private val pageSize: Int = 100) : AbstractList<VodItem>(), RandomAccess {
    override val size: Int by lazy { runCatching { catalog.count(query) }.getOrDefault(0) }

    // Compared by identity. A list's usual equals/hashCode walk every item, which here means reading
    // every page from the database; Compose compares a state's old and new value with equals, so
    // replacing one paged list with another (each catalog update, each screen recomposition) read
    // whole catalogs on the main thread and froze the screen (Discover, Movies).
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
    private val pages = object : LinkedHashMap<Int, List<VodItem>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<VodItem>>?) = size > 12
    }

    @Synchronized
    override fun get(index: Int): VodItem {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("$index of $size")
        val p = index / pageSize
        val page = pages[p] ?: runCatching { catalog.page(query, p * pageSize, pageSize) }.getOrDefault(emptyList()).also { pages[p] = it }
        // The catalog changed under this list (a refresh just finished): show an empty tile until the
        // screen picks up the new list, rather than failing.
        return page.getOrNull(index - p * pageSize) ?: VodItem("gone:$index", "", query.group, "", null, "")
    }
}
