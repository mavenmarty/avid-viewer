package com.avidviewer.tv.data

import org.json.JSONObject
import java.util.Locale

/** How the picture fills the screen. */
enum class ScreenFormat(val id: String, val label: String) {
    AUTO("auto", "Auto (fit)"),
    FILL("fill", "Fill screen (stretch)"),
    ZOOM("zoom", "Zoom (crop edges)"),
    WIDE("16:9", "16:9"),
    STANDARD("4:3", "4:3");

    /** Width ÷ height the picture is forced to (null = the video's own). */
    val ratio: Float? get() = when (this) { WIDE -> 16f / 9f; STANDARD -> 4f / 3f; else -> null }

    companion object { fun of(id: String?): ScreenFormat? = entries.firstOrNull { it.id == id } }
}

/**
 * Picture & sound choices for one channel (or title), or the default for all. Null = not set:
 * a channel's unset choices come from the default, the default's from the stream.
 * [audio] and [captionTrack] are track signatures ([Av.signature]); [captions] false = off.
 */
data class AvChoice(
    val format: String? = null,
    val audio: String? = null,
    val captions: Boolean? = null,
    val captionTrack: String? = null,
) {
    val isEmpty get() = format == null && audio == null && captions == null && captionTrack == null

    /** These choices, with the unset ones taken from [base]. */
    fun over(base: AvChoice) = AvChoice(format ?: base.format, audio ?: base.audio, captions ?: base.captions, captionTrack ?: base.captionTrack)

    fun toJson(): JSONObject = JSONObject().apply {
        format?.let { put("format", it) }; audio?.let { put("audio", it) }
        captions?.let { put("captions", it) }; captionTrack?.let { put("captionTrack", it) }
    }

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) AvChoice() else AvChoice(
            o.optString("format").ifBlank { null }, o.optString("audio").ifBlank { null },
            if (o.has("captions")) o.optBoolean("captions") else null, o.optString("captionTrack").ifBlank { null },
        )
    }
}

/** How captions look (the same everywhere). */
data class CaptionStyle(val size: String = "medium", val background: String = "dark", val position: String = "bottom") {
    /** Text height as a share of the picture's height. */
    val fraction: Float get() = when (size) { "small" -> 0.042f; "large" -> 0.068f; "xlarge" -> 0.085f; else -> 0.0533f }
    /** Space under the captions as a share of the picture's height. */
    val bottomPadding: Float get() = if (position == "raised") 0.2f else 0.08f

    fun toJson(): JSONObject = JSONObject().put("size", size).put("background", background).put("position", position)

    companion object {
        val SIZES = listOf("small" to "Small", "medium" to "Medium", "large" to "Large", "xlarge" to "Extra large")
        val BACKGROUNDS = listOf("none" to "None (outlined text)", "dark" to "Dark box", "solid" to "Solid black")
        val POSITIONS = listOf("bottom" to "Bottom", "raised" to "Raised")
        fun fromJson(o: JSONObject?) = if (o == null) CaptionStyle() else CaptionStyle(
            o.optString("size", "medium").takeIf { s -> SIZES.any { it.first == s } } ?: "medium",
            o.optString("background", "dark").takeIf { s -> BACKGROUNDS.any { it.first == s } } ?: "dark",
            o.optString("position", "bottom").takeIf { s -> POSITIONS.any { it.first == s } } ?: "bottom",
        )
    }
}

/**
 * An audio or caption track of what's playing, from the player's track list ([group] and [index]
 * point into it). [cc] is the caption service number of TV captions (CC1–CC4, or 708 services).
 */
data class AvTrack(
    val audio: Boolean,
    val group: Int,
    val index: Int,
    val lang: String?,
    val label: String?,
    val channels: Int,
    val mime: String?,
    val roles: Int,
    val selected: Boolean,
    val cc: Int = -1,
)

object Av {
    private const val ROLE_MAIN = androidx.media3.common.C.ROLE_FLAG_MAIN
    private const val ROLE_ALTERNATE = androidx.media3.common.C.ROLE_FLAG_ALTERNATE
    private const val ROLE_SUPPLEMENTARY = androidx.media3.common.C.ROLE_FLAG_SUPPLEMENTARY
    private const val ROLE_COMMENTARY = androidx.media3.common.C.ROLE_FLAG_COMMENTARY
    private const val ROLE_DESCRIBES_VIDEO = androidx.media3.common.C.ROLE_FLAG_DESCRIBES_VIDEO

    /** "en", "spa" → "English", "Spanish"; unknown or missing → null. */
    fun languageName(code: String?): String? {
        val c = code?.trim()?.lowercase()?.substringBefore('-')?.takeIf { it.isNotBlank() && it != "und" && it != "unknown" } ?: return null
        val name = runCatching { Locale(c).getDisplayLanguage(Locale.ENGLISH) }.getOrNull()
        return name?.takeIf { it.isNotBlank() && !it.equals(c, ignoreCase = true) }?.replaceFirstChar { it.uppercase() }
    }

    private fun langKey(code: String?): String = runCatching { Locale(code?.substringBefore('-') ?: "").isO3Language }.getOrNull()?.takeIf { it.isNotBlank() } ?: code?.lowercase().orEmpty()

    fun codecName(mime: String?): String? = when (mime?.lowercase()) {
        null -> null
        "audio/ac3" -> "Dolby Digital"
        "audio/eac3", "audio/eac3-joc" -> "Dolby Digital Plus"
        "audio/ac4" -> "Dolby AC-4"
        "audio/mp4a-latm", "audio/aac" -> "AAC"
        "audio/mpeg", "audio/mpeg-l2", "audio/mpeg-l1" -> "MPEG"
        "audio/opus" -> "Opus"
        "audio/vnd.dts", "audio/vnd.dts.hd" -> "DTS"
        "application/cea-608" -> "TV captions"
        "application/cea-708" -> "TV captions (digital)"
        "text/vtt" -> "WebVTT"
        "application/x-subrip" -> "SRT"
        "application/ttml+xml" -> "TTML"
        "application/dvbsubs" -> "DVB"
        else -> null
    }

    fun channelsName(n: Int): String? = when (n) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; in 3..5 -> "Surround"; else -> null }

    /** What identifies a track again on another visit (the player's numbering can change). */
    fun signature(t: AvTrack): String = listOf(if (t.audio) "a" else "t", langKey(t.lang), t.label.orEmpty(), if (t.audio) t.channels.toString() else t.cc.toString(), t.mime.orEmpty()).joinToString("|")

    private fun sigLang(sig: String?): String? = sig?.split('|')?.getOrNull(1)?.takeIf { it.isNotBlank() }

    /** The track [wanted] names, or another in the same language, or null (play the stream's own choice). */
    fun pick(tracks: List<AvTrack>, wanted: String?): AvTrack? {
        if (wanted == null || tracks.isEmpty()) return null
        tracks.firstOrNull { signature(it) == wanted }?.let { return it }
        val lang = sigLang(wanted) ?: return null
        return tracks.firstOrNull { langKey(it.lang) == lang }
    }

    /** "English · Dolby Digital 5.1", "Spanish · Stereo · Secondary audio", "Described video". */
    fun describe(t: AvTrack, all: List<AvTrack>): String {
        val sameKind = all.filter { it.audio == t.audio }
        val parts = ArrayList<String>()
        val lang = languageName(t.lang)
        if (t.audio) {
            parts.add(lang ?: t.label?.takeIf { it.isNotBlank() } ?: "Track ${sameKind.indexOf(t) + 1}")
            if (lang != null && !t.label.isNullOrBlank() && !t.label.equals(lang, ignoreCase = true)) parts.add(t.label)
            listOfNotNull(codecName(t.mime), channelsName(t.channels)).joinToString(" ").takeIf { it.isNotBlank() }?.let { parts.add(it) }
            when {
                (t.roles and ROLE_DESCRIBES_VIDEO) != 0 -> parts.add("Described video")
                (t.roles and (ROLE_ALTERNATE or ROLE_SUPPLEMENTARY or ROLE_COMMENTARY)) != 0 || (sameKind.indexOf(t) > 0 && (t.roles and ROLE_MAIN) == 0) -> parts.add("Secondary audio")
            }
        } else {
            val cc = when {
                t.cc > 0 && t.mime == "application/cea-608" -> "CC${t.cc}"
                t.cc > 0 && t.mime == "application/cea-708" -> "Service ${t.cc}"
                else -> null
            }
            parts.add(listOfNotNull(lang ?: t.label?.takeIf { it.isNotBlank() }, cc).joinToString(" ").ifBlank { "Captions ${sameKind.indexOf(t) + 1}" })
            if (lang != null && !t.label.isNullOrBlank() && !t.label.equals(lang, ignoreCase = true)) parts.add(t.label)
            codecName(t.mime)?.let { parts.add(it) }
        }
        return parts.joinToString(" · ")
    }
}
