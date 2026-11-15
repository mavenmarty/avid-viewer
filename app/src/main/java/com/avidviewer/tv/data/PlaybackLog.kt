package com.avidviewer.tv.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The playback log (1.5.0): what happened while video played, kept on the TV so freezes can be explained
 * afterwards. Each entry is one line: when, what kind, what was playing, and the details.
 *
 * Kinds: [START] (a channel or title started), [FORMAT] (its video: size, codec, bitrate), [STALL] (playback
 * stopped to wait for data; logged when it ends, with how long and the likely cause), [PICTURE] (the picture
 * stopped while the sound went on), [ERROR] (the player gave up), [LOAD] (a download of stream data failed),
 * [DROPPED] (a burst of video frames the TV couldn't show in time).
 *
 * At most [max] entries are kept (oldest go first). Sent with [CrashReport.sendLog], with addresses removed.
 *
 * Privacy (1.5.2): what was playing is never written down. [label] gives each channel or title a short code
 * ("Live channel 4F2A", "Title 9C01"), the same every time on this TV, so freezes on one channel can be seen
 * together, but the code comes from a random number kept on this TV and can't be turned back into a name.
 * Entries written by 1.5.0–1.5.1 (which held the names) are rewritten as "Earlier stream" when the log opens.
 */
class PlaybackLog(private val file: File, private val max: Int = 400, private val clock: () -> Long = System::currentTimeMillis) {
    data class Entry(val time: Long, val kind: String, val what: String, val text: String)

    private val entries = ArrayList<Entry>()
    private val state = File(file.parentFile, file.name + ".sent")
    private val saltFile = File(file.parentFile, file.name + ".salt")
    private val salt: String by lazy {
        runCatching { saltFile.readText().trim() }.getOrNull()?.takeIf { it.length >= 16 }
            ?: java.util.UUID.randomUUID().toString().also { runCatching { saltFile.writeText(it) } }
    }

    /** The code that stands for a channel or title in the log: never its name. */
    fun label(key: String, live: Boolean): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest((salt + "|" + key).toByteArray())
        val code = "%02X%02X".format(d[0], d[1])
        return (if (live) "Live channel " else "Title ") + code
    }

    /** Time of the newest entry already sent, and of the last automatic send. */
    var sentUpTo: Long = 0; private set
    var lastAutoSend: Long = 0; private set

    init {
        runCatching {
            if (file.exists()) file.forEachLine { line ->
                val p = line.split('\t', limit = 4)
                if (p.size == 4) p[0].toLongOrNull()?.let { entries += Entry(it, p[1], p[2], p[3]) }
            }
            while (entries.size > max) entries.removeAt(0)
            // Names written by 1.5.0–1.5.1 are taken out (privacy, 1.5.2).
            if (entries.any { it.what.isNotBlank() && !LABEL.matches(it.what) }) {
                for (i in entries.indices) if (entries[i].what.isNotBlank() && !LABEL.matches(entries[i].what)) entries[i] = entries[i].copy(what = "Earlier stream")
                file.writeText(entries.joinToString("") { line(it) })
            }
            if (state.exists()) state.readText().trim().split(' ').let { s ->
                sentUpTo = s.getOrNull(0)?.toLongOrNull() ?: 0; lastAutoSend = s.getOrNull(1)?.toLongOrNull() ?: 0
            }
        }
    }

    @Synchronized
    fun add(kind: String, what: String, text: String) {
        val e = Entry(clock(), kind, clean(what), clean(text))
        entries += e
        runCatching {
            if (entries.size > max) {
                while (entries.size > max) entries.removeAt(0)
                file.writeText(entries.joinToString("") { line(it) })
            } else file.appendText(line(e))
        }
    }

    @Synchronized fun entries(): List<Entry> = entries.toList()

    @Synchronized
    fun clear() { entries.clear(); runCatching { file.delete() } }

    /** Freezes and errors (not starts, formats or load hiccups that didn't stop playback). */
    fun problems(since: Long = 0): List<Entry> = entries().filter { it.time > since && it.kind in PROBLEMS }

    /** Problems not sent yet. */
    fun unsent(): List<Entry> = problems(sentUpTo)

    @Synchronized
    fun markSent(upTo: Long, auto: Boolean) {
        sentUpTo = maxOf(sentUpTo, upTo)
        if (auto) lastAutoSend = clock()
        runCatching { state.writeText("$sentUpTo $lastAutoSend") }
    }

    /** One line, e.g. "Playback log: 5 freezes (longest 12 s), 1 picture stop, 2 errors in the last 3 days". */
    fun summary(list: List<Entry> = entries()): String {
        val stalls = list.filter { it.kind == STALL }
        val pics = list.count { it.kind == PICTURE }
        val errors = list.count { it.kind == ERROR }
        if (stalls.isEmpty() && pics == 0 && errors == 0) return "Playback log: no freezes or errors"
        val longest = stalls.maxOfOrNull { secondsIn(it.text) } ?: 0
        val days = list.firstOrNull()?.let { ((clock() - it.time) / 86_400_000L).toInt() + 1 } ?: 1
        return "Playback log: " + listOfNotNull(
            stalls.size.takeIf { it > 0 }?.let { "$it freeze" + (if (it == 1) "" else "s") + (if (longest > 0) " (longest $longest s)" else "") },
            pics.takeIf { it > 0 }?.let { "$it picture stop" + if (it == 1) "" else "s" },
            errors.takeIf { it > 0 }?.let { "$it error" + if (it == 1) "" else "s" },
        ).joinToString(", ") + if (days <= 1) " today" else " in the last $days days"
    }

    /** The whole log as text, newest last, with a count of the causes found at the top. */
    fun text(): String {
        val list = entries()
        val fmt = SimpleDateFormat("MMM d HH:mm:ss", Locale.US)
        val causes = list.filter { it.kind == STALL || it.kind == PICTURE }
            .groupingBy { it.text.substringAfter("Likely: ", "").substringBefore('.').ifBlank { "unclear" } }.eachCount()
        return buildString {
            appendLine(summary(list))
            if (causes.isNotEmpty()) appendLine("Likely causes: " + causes.entries.sortedByDescending { it.value }.joinToString("; ") { "${it.key} ×${it.value}" })
            appendLine()
            list.forEach { appendLine("${fmt.format(Date(it.time))}  ${it.kind.padEnd(7)}  ${it.what}  ·  ${it.text}") }
        }
    }

    private fun line(e: Entry) = "${e.time}\t${e.kind}\t${e.what}\t${e.text}\n"
    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').take(400)
    private fun secondsIn(text: String) = Regex("""(\d+) s\b""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    companion object {
        const val START = "START"
        const val FORMAT = "FORMAT"
        const val STALL = "STALL"
        const val PICTURE = "PICTURE"
        const val ERROR = "ERROR"
        const val LOAD = "LOAD"
        const val DROPPED = "DROPPED"
        val PROBLEMS = setOf(STALL, PICTURE, ERROR)
        val LABEL = Regex("^(Live channel|Title) [0-9A-F]{4}$|^Earlier stream$")
    }
}
