package com.avidviewer.tv.data

import android.content.SharedPreferences
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * Which video decoder each channel or title uses (#30, #32). Fire TV's hardware decoders can get stuck on some
 * streams: the picture freezes while the sound goes on, or every start fails with ERROR_CODE_DECODING_FAILED for
 * hours. Such a stream is switched to the TV's other decoder (Android's software one), and that's remembered for
 * [KEEP_MS]. If the other decoder has trouble too, the stream goes back to the TV's usual one.
 *
 * [prefs] null = kept in memory only (tests).
 */
@UnstableApi
class Decoders(private val prefs: SharedPreferences?, private val clock: () -> Long = System::currentTimeMillis) {
    private val mem = HashMap<String, Long>()

    init {
        val now = clock()
        prefs?.all?.forEach { (k, v) -> (v as? Long)?.takeIf { now - it < KEEP_MS }?.let { mem[k] = it } }
    }

    /** The decoder used from the next start: software first when true. Read by [selector] on the player's thread. */
    @Volatile var softwareFirst = false; private set

    /** [key] plays with the other (software) decoder. */
    @Synchronized fun usesOther(key: String): Boolean = mem[key]?.let { clock() - it < KEEP_MS } == true

    /** Sets [softwareFirst] for [key], which is about to start. */
    fun startingNow(key: String) { softwareFirst = usesOther(key) }

    /** Switches [key] to the other decoder (or back, if it's on it already). Returns true when it's now on the other one. */
    @Synchronized fun switch(key: String): Boolean {
        val other = !usesOther(key)
        if (other) mem[key] = clock() else mem.remove(key)
        prefs?.edit()?.apply { if (other) putLong(key, clock()) else remove(key) }?.apply()
        softwareFirst = other
        return other
    }

    /** For the player: Android's list of decoders, with software ones first while [softwareFirst] is on (video only). */
    val selector = MediaCodecSelector { mime, secure, tunneling ->
        val list = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
        if (softwareFirst && mime.startsWith("video/")) order(list) else list
    }

    companion object {
        /** How long a stream stays on the other decoder. */
        const val KEEP_MS = 7L * 24 * 3600 * 1000

        /** Software decoders first, keeping the order within each kind. */
        fun order(list: List<MediaCodecInfo>): List<MediaCodecInfo> = list.sortedBy { if (isSoftware(it.name, it.softwareOnly)) 0 else 1 }

        fun isSoftware(name: String, softwareOnly: Boolean = false): Boolean = softwareOnly ||
            name.startsWith("OMX.google.") || name.startsWith("c2.android.") || name.startsWith("OMX.ffmpeg.")

        /**
         * Whether a picture freeze may switch the stream to the software decoder: only streams the TV can decode in
         * software without falling behind (H.264 / MPEG-2 up to 1080p at 30 fps, or 720p at 60). A decoding error
         * switches any stream, since otherwise it doesn't play at all.
         */
        fun softwareCanKeepUp(width: Int, height: Int, fps: Float, mime: String?): Boolean {
            if (width <= 0 || height <= 0) return false
            val m = mime.orEmpty().lowercase()
            if (!(m.contains("avc") || m.contains("mpeg2") || m.contains("mp2v") || m.contains("mpeg"))) return false
            val rate = width.toLong() * height * (if (fps > 0) fps else 30f)
            return rate <= 1920L * 1088 * 30
        }

        /** Media3's decoder error codes (4001–4005: init, query, decoding, format too big, format unsupported). */
        fun isDecoderError(code: Int): Boolean = code in 4001..4005
    }
}
