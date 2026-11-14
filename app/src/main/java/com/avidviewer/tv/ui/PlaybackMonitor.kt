package com.avidviewer.tv.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.avidviewer.tv.data.PlaybackLog
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** What Stream info shows (Picture & sound panel), refreshed every second while something plays. */
data class StreamStats(
    val video: String = "",
    val streamKbps: Int = 0,
    val downloadKbps: Int = 0,
    val bufferedMs: Long = 0,
    val freezes: Int = 0,
    val longestFreezeS: Int = 0,
    val droppedFrames: Int = 0,
    val lastProblem: String? = null,
)

/**
 * Watches the player and writes the playback log (1.5.0). A freeze is playback waiting for data after it had
 * started ([Player.STATE_BUFFERING] after [Player.STATE_READY]); a picture stop is the picture not changing for
 * 3 seconds while playback (and the sound) goes on. Each one is logged when it ends, with a likely cause from what
 * happened just before: failed downloads (provider answered with an error, or timed out), the buffer running out
 * with a download speed below the stream's bitrate (network or provider too slow), or a stuck picture with
 * frames dropped (the TV's decoder).
 */
@UnstableApi
class PlaybackMonitor(
    private val player: ExoPlayer,
    private val meter: DefaultBandwidthMeter,
    private val log: PlaybackLog,
    private val nameOf: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) : AnalyticsListener {
    var stats by mutableStateOf(StreamStats()); private set

    private var name = ""
    private var everReady = false
    private var stallAt = 0L
    private var bufferBefore = 0L
    private var lastBuffered = 0L
    private var picStuckSince = 0L
    private var picLogged = false
    private var lastRendered = -1
    private var lastPosition = 0L
    private var dropped = 0
    private var droppedWindow = 0
    private var droppedWindowAt = 0L
    private val recentLoadErrors = ArrayDeque<Pair<Long, String>>()

    /** A channel or title is starting: ends anything still open on the one before. */
    fun started(what: String, live: Boolean) {
        endStall(left = true)
        endPicture()
        name = what
        everReady = false; lastRendered = -1; dropped = 0; droppedWindow = 0; recentLoadErrors.clear()
        stats = StreamStats()
        log.add(PlaybackLog.START, what, if (live) "live channel" else "movie or episode")
    }

    /** Every second: buffer, speed, frames; spots a picture that stopped while playback goes on. */
    fun sample() {
        val now = clock()
        val buffered = player.totalBufferedDuration
        lastBuffered = buffered
        val counters = player.videoDecoderCounters
        counters?.ensureUpdated()
        val rendered = counters?.renderedOutputBufferCount ?: -1
        val pos = player.currentPosition
        if (player.isPlaying && player.videoFormat != null && rendered >= 0) {
            val moved = pos - lastPosition
            if (rendered == lastRendered && lastRendered >= 0 && moved > 500) {
                if (picStuckSince == 0L) picStuckSince = now
                if (!picLogged && now - picStuckSince >= 3_000) picLogged = true
            } else endPicture()
        } else if (!player.isPlaying) endPicture()
        lastRendered = rendered; lastPosition = pos
        if (droppedWindowAt > 0 && now - droppedWindowAt > 10_000) {
            if (droppedWindow >= 30) log.add(PlaybackLog.DROPPED, name, "$droppedWindow frames dropped in 10 s (the TV struggled to decode${videoText().let { if (it.isBlank()) "" else ": $it" }})")
            droppedWindow = 0; droppedWindowAt = 0
        }
        stats = stats.copy(
            video = videoText(),
            streamKbps = streamKbps(),
            downloadKbps = (meter.bitrateEstimate / 1000).toInt(),
            bufferedMs = buffered,
            droppedFrames = dropped,
        )
    }

    private fun endPicture() {
        if (picLogged) {
            val s = ((clock() - picStuckSince) / 1000).toInt()
            val why = if (droppedWindow > 0 || dropped > 0) "the TV's video decoder (frames dropped while the picture stood still" + videoText().let { if (it.isBlank()) ")" else "; $it)" }
                else "the stream or the TV's decoder (the picture stood still while the sound went on)"
            note(PlaybackLog.PICTURE, "Picture stopped for $s s while playback went on. Likely: $why.", s)
        }
        picStuckSince = 0; picLogged = false
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        when (state) {
            Player.STATE_READY -> { everReady = true; endStall(left = false) }
            Player.STATE_BUFFERING -> if (everReady && stallAt == 0L) { stallAt = clock(); bufferBefore = lastBuffered }
            Player.STATE_ENDED, Player.STATE_IDLE -> endStall(left = true)
        }
    }

    private fun endStall(left: Boolean) {
        if (stallAt == 0L) return
        val s = ((clock() - stallAt) / 1000).toInt()
        val errs = recentLoadErrors.filter { it.first >= stallAt - 15_000 }.map { it.second }
        val why = cause(errs, bufferBefore, (meter.bitrateEstimate / 1000).toInt(), streamKbps())
        note(PlaybackLog.STALL, (if (left) "Waited $s s for data, then left the channel. " else "Froze $s s waiting for data. ") + "Likely: $why.", s)
        stallAt = 0
    }

    private fun note(kind: String, text: String, seconds: Int) {
        log.add(kind, name, text)
        stats = stats.copy(freezes = stats.freezes + 1, longestFreezeS = maxOf(stats.longestFreezeS, seconds), lastProblem = text)
    }

    override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData, error: IOException, wasCanceled: Boolean) {
        if (wasCanceled) return
        val what = describe(error)
        recentLoadErrors.addLast(clock() to what)
        while (recentLoadErrors.size > 20) recentLoadErrors.removeFirst()
        log.add(PlaybackLog.LOAD, name, what)
    }

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
        endStall(left = false)
        val cause = generateSequence(error.cause) { it.cause }.firstOrNull { it is IOException }?.let { describe(it as IOException) }
        note(PlaybackLog.ERROR, "Player stopped: ${error.errorCodeName}" + (cause?.let { " ($it)" } ?: "") + ".", 0)
    }

    override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
        log.add(PlaybackLog.FORMAT, name, formatText(format))
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        dropped += droppedFrames
        if (droppedWindowAt == 0L) droppedWindowAt = clock()
        droppedWindow += droppedFrames
    }

    private fun videoText() = player.videoFormat?.let { formatText(it) } ?: ""
    private fun streamKbps(): Int = player.videoFormat?.let { f -> listOf(f.bitrate, f.peakBitrate, f.averageBitrate).firstOrNull { it > 0 } }?.let { it / 1000 } ?: 0

    companion object {
        /** "1920×1080 · H.264 · 30 fps · 6.2 Mb/s" */
        fun formatText(f: Format): String = listOfNotNull(
            if (f.width > 0 && f.height > 0) "${f.width}×${f.height}" else null,
            codecName(f.sampleMimeType ?: f.codecs),
            if (f.frameRate > 0) "${Math.round(f.frameRate)} fps" else null,
            listOf(f.bitrate, f.peakBitrate, f.averageBitrate).firstOrNull { it > 0 }?.let { mbps(it / 1000) },
        ).joinToString(" · ")

        fun codecName(mime: String?): String? = when {
            mime == null -> null
            mime.contains("avc", true) -> "H.264"
            mime.contains("hevc", true) || mime.contains("hvc", true) || mime.contains("hev", true) -> "HEVC (H.265)"
            mime.contains("mpeg2", true) || mime.contains("mp2v", true) -> "MPEG-2"
            mime.contains("av01", true) || mime.contains("av1", true) -> "AV1"
            mime.contains("vp9", true) -> "VP9"
            else -> mime.substringAfter('/')
        }

        fun mbps(kbps: Int) = if (kbps >= 1000) "%.1f Mb/s".format(kbps / 1000.0) else "$kbps kb/s"

        /** A failed download in plain words: "HTTP 503 from the provider", "timed out", "server not found"… */
        fun describe(e: IOException): String {
            val chain = generateSequence<Throwable>(e) { it.cause }.toList()
            chain.firstNotNullOfOrNull { it as? HttpDataSource.InvalidResponseCodeException }?.let { return "provider answered HTTP ${it.responseCode}" }
            return when {
                chain.any { it is SocketTimeoutException } -> "connection timed out"
                chain.any { it is UnknownHostException } -> "server not found (DNS)"
                chain.any { it is java.net.ConnectException } -> "couldn't connect to the server"
                chain.any { it is java.net.SocketException } -> "connection dropped"
                else -> e.javaClass.simpleName.ifBlank { "download failed" }
            }
        }

        /**
         * The likely cause of a freeze, from the download errors around it, how much was buffered just before,
         * and the download speed against the stream's bitrate (0 = unknown).
         */
        fun cause(loadErrors: List<String>, bufferBeforeMs: Long, downloadKbps: Int, streamKbps: Int): String {
            val http = loadErrors.filter { it.startsWith("provider answered HTTP") }
            val net = loadErrors.filter { it.contains("timed out") || it.contains("dropped") || it.contains("connect") || it.contains("DNS") }
            return when {
                http.isNotEmpty() -> "the provider (" + http.groupingBy { it.removePrefix("provider answered ") }.eachCount().entries.joinToString(", ") { "${it.key} ×${it.value}" } + ")"
                net.isNotEmpty() -> "the network or the provider's server (" + net.groupingBy { it }.eachCount().entries.joinToString(", ") { "${it.key} ×${it.value}" } + ")"
                streamKbps > 0 && downloadKbps in 1 until streamKbps -> "the network or provider is too slow (getting ${mbps(downloadKbps)}, the stream needs ${mbps(streamKbps)})"
                bufferBeforeMs < 1_500 -> "data stopped arriving: the buffer ran out with no download error" + (if (downloadKbps > 0) " (download speed ${mbps(downloadKbps)})" else "") + ", which usually means the provider's server paused the stream"
                else -> "unclear (${bufferBeforeMs / 1000} s was buffered, no download errors)"
            }
        }
    }
}
