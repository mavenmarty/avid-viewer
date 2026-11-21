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
    private var live = false
    private var startedAt = 0L

    // ---- Reconnecting (Nov 15) ----
    /** Reconnects the stream ([live]: at the live point; else where it was). Set by AppViewModel. */
    var onReconnect: ((live: Boolean, full: Boolean, otherDecoder: Boolean) -> Unit)? = null
    private val reconnects = ArrayDeque<Long>()
    private var reconnectAt = 0L
    private var reconnectWhy = ""
    private var lastBufferEnd = 0L
    private var bufferGrewAt = 0L
    /** 3 reconnects in 3 minutes: from then on at most one every [SLOW_RECONNECT_MS]. */
    private var slowReconnect = false
    /** #30: reconnects for a still picture on this channel, and whether it was switched to the other decoder. */
    private var pictureReconnects = 0
    private var decoderSwitched = false
    /** The video decoder in use ("" until one starts), e.g. "OMX.MTK.VIDEO.DECODER.AVC" or "c2.android.avc.decoder". */
    var decoderName = ""; private set

    // ---- Device load (Nov 21, 2026) ----
    /** The TV's memory and the app's processor use, e.g. "Memory 410 MB free of 1.9 GB · app 74% of a core". Set by AppViewModel. */
    var deviceLoad: (() -> String)? = null
    /** Which screen is open ("Settings"), never what's on it. Set by AppViewModel. */
    var screenName: (() -> String)? = null
    private var decoderStarts = 0
    private var deviceLoggedAt = 0L

    /** A channel or title is starting ([key]: its key, never shown; the log gets a code instead of the name). */
    fun started(key: String, live: Boolean) {
        endStall(left = true)
        endPicture()
        this.live = live
        startedAt = clock(); reconnects.clear(); reconnectAt = 0; slowReconnect = false; pictureReconnects = 0; decoderSwitched = false
        stallStarts.clear(); lastStallEnd = 0; stutterCount = 0; stutterSince = 0; lastBufferEnd = 0; bufferGrewAt = startedAt; minuteAt = 0
        DataFlow.lastBytesAt = startedAt
        name = log.label(key, live)
        everReady = false; lastRendered = -1; dropped = 0; droppedWindow = 0; recentLoadErrors.clear()
        decoderStarts = 0; deviceLoggedAt = startedAt
        stats = StreamStats()
        log.add(PlaybackLog.START, name, if (live) "live channel" else "movie or episode")
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
        if (stutterCount > 0 && stallAt == 0L && now - lastStallEnd >= 3_000) endStutter()
        // #23: the buffer's end, to tell a stream that trickles a few bytes (so "no data" never fires) from one that flows.
        val bufEnd = player.bufferedPosition
        if (bufEnd > lastBufferEnd) { lastBufferEnd = bufEnd; bufferGrewAt = now }
        checkReconnect(now, buffered)
        // Every 2 minutes while something plays: how busy the TV is.
        if (player.playWhenReady && name.isNotEmpty() && now - deviceLoggedAt >= DEVICE_EVERY_MS) { deviceLoggedAt = now; logDevice("") }
        if (droppedWindowAt > 0 && now - droppedWindowAt > 10_000) {
            if (droppedWindow >= 30) put(PlaybackLog.DROPPED, "$droppedWindow frames dropped in 10 s (the TV struggled to decode${videoText().let { if (it.isBlank()) "" else ": $it" }})")
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

    /**
     * Reconnects when the picture has stood still for 5 seconds while the sound plays (usually a jump in the
     * provider's timestamps the player doesn't recover from), or when no data has arrived for 4 seconds and
     * less than 8 seconds is left in the buffer (the provider's server stopped sending), before the screen
     * freezes. Not in the first 10 seconds of a channel or after a reconnect, and at most 3 times in 3 minutes on
     * one channel, so a channel that keeps failing isn't reconnected over and over.
     */
    private fun checkReconnect(now: Long, buffered: Long) {
        if (onReconnect == null || name.isEmpty() || !player.playWhenReady) return
        val state = player.playbackState
        if (state != Player.STATE_READY && state != Player.STATE_BUFFERING) return
        if (reconnectAt > 0 && player.isPlaying && now - reconnectAt in 1..60_000 && reconnectWhy.isNotEmpty()) {
            put(PlaybackLog.RECOVER, "Playing again ${((now - reconnectAt) / 1000).coerceAtLeast(1)} s after reconnecting ($reconnectWhy).")
            reconnectWhy = ""
        }
        while (reconnects.isNotEmpty() && now - reconnects.first() > 180_000) reconnects.removeFirst()
        val stops = stallStarts.count { now - it <= 30_000 }
        val why = reconnectReason(now, startedAt, reconnectAt, picStuckSince, DataFlow.lastBytesAt, buffered, everReady,
            stallingSince = stallAt, bufferGrewAt = bufferGrewAt, stopsIn30s = stops) ?: return
        val picture = why.startsWith("the picture")
        // #31: stops with a full buffer are the TV's decoder falling behind, not the download: reconnecting throws the
        // buffer away and doesn't help. The only thing worth trying is the other decoder, once.
        val decoderBehind = why.startsWith(DECODER_BEHIND)
        if (decoderBehind && (decoderSwitched || !mayUseOtherDecoder())) return
        if (reconnects.size < 3) slowReconnect = false
        if (reconnects.size >= 3) {
            // BUG-058: after 3 in 3 minutes the app used to stop trying, and a channel could sit frozen for minutes
            // (295 s in one log) until the viewer changed it. Now it keeps trying while it stays stuck: every 30 s,
            // or every 15 s for a still picture (#30: the sound goes on, so a frozen picture is all the viewer sees).
            if (!slowReconnect) { slowReconnect = true; put(PlaybackLog.RECOVER, "Reconnecting less often: 3 reconnects in 3 minutes ($why); trying every ${(if (picture) SLOW_PICTURE_MS else SLOW_RECONNECT_MS) / 1000} s while it stays stuck.") }
            if (!mayReconnect(now, reconnects, if (picture) SLOW_PICTURE_MS else SLOW_RECONNECT_MS)) return
        }
        if (picture) pictureReconnects++
        // #30: a second still picture on a channel (a reconnect didn't cure it): the TV's other video decoder.
        val otherDecoder = !decoderSwitched && (decoderBehind || (picture && pictureReconnects >= 2 && mayUseOtherDecoder()))
        if (otherDecoder) decoderSwitched = true
        reconnects.addLast(now)
        if (picture) endPicture() else endStall(left = false)
        put(PlaybackLog.RECOVER, "Reconnected: $why." + if (otherDecoder) " Trying the TV's other video decoder for this stream." else "")
        logDevice("at the reconnect")
        reconnectAt = now; reconnectWhy = why
        DataFlow.lastBytesAt = now; bufferGrewAt = now; stallStarts.clear()
        // #23: a reconnect within a minute of the last one rebuilds the player (a restart alone rarely helped again).
        val full = otherDecoder || (reconnects.size >= 2 && now - reconnects[reconnects.size - 2] < 60_000)
        onReconnect?.invoke(live, full, otherDecoder)
    }

    /**
     * Whether this stream may change decoder for a freeze: back to the TV's own decoder at any time, or to the software
     * one when it can keep up with the stream ([com.avidviewer.tv.data.Decoders.softwareCanKeepUp]).
     */
    private fun mayUseOtherDecoder(): Boolean {
        if (decoderName.isNotEmpty() && com.avidviewer.tv.data.Decoders.isSoftware(decoderName)) return true
        val f = player.videoFormat ?: return false
        return com.avidviewer.tv.data.Decoders.softwareCanKeepUp(f.width, f.height, f.frameRate, f.sampleMimeType ?: f.codecs)
    }

    /** #32: a decoding error made the app restart the stream on the other decoder ([other]: software; else the TV's own). */
    fun decoderError(error: String, other: Boolean) {
        endStall(left = false); endPicture()
        put(PlaybackLog.RECOVER, "Restarted after a decoding error ($error) with " + (if (other) "the TV's other (software) video decoder." else "the TV's own video decoder again."))
        reconnectAt = clock(); reconnectWhy = "decoding error"
    }

    /** Playback stopped (leaving full screen or the app). (The temporary buffer log of 1.10.0 was removed in 1.17.1.) */
    fun finished() {
        // BUG-059: close a freeze or picture stop under this channel's code before forgetting it (a later close had none).
        endStall(left = true); endPicture()
        name = ""
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
            Player.STATE_BUFFERING -> if (everReady && stallAt == 0L) {
                stallAt = clock(); bufferBefore = lastBuffered
                stallStarts.addLast(stallAt); while (stallStarts.size > 40) stallStarts.removeFirst()
            }
            Player.STATE_ENDED, Player.STATE_IDLE -> endStall(left = true)
        }
    }

    // ---- #25: stutter (the player flipping between waiting and playing, many times a second) ----
    private val stallStarts = ArrayDeque<Long>()
    private var lastStallEnd = 0L
    private var stutterCount = 0
    private var stutterSince = 0L

    /** A burst of short stops has ended: one entry for all of them. */
    private fun endStutter() {
        if (stutterCount >= 2) note(PlaybackLog.STALL, "Stuttered $stutterCount times over ${((lastStallEnd - stutterSince) / 1000).coerceAtLeast(1)} s (the picture kept stopping for a moment). Likely: data arriving unevenly from the provider.", 0)
        stutterCount = 0; stutterSince = 0
    }

    private fun endStall(left: Boolean) {
        if (stallAt == 0L) return
        if (name.isEmpty()) { stallAt = 0; return }
        val now = clock()
        val ms = now - stallAt
        // A stop under a second is never a freeze on its own (#34: 398 "Froze 0 s" entries in 2 minutes): it starts or
        // joins a stutter (stops within 3 s of each other), logged once at its end if there were at least 2.
        if (ms < 1_000) {
            if (stutterCount > 0 && stallAt - lastStallEnd < 3_000) stutterCount++
            else { endStutter(); stutterCount = 1; stutterSince = stallAt }
            lastStallEnd = now; stallAt = 0
            if (left) endStutter()
            return
        }
        endStutter()
        lastStallEnd = now
        val s = (ms / 1000).toInt()
        val errs = recentLoadErrors.filter { it.first >= stallAt - 15_000 }.map { it.second }
        val why = cause(errs, bufferBefore, (meter.bitrateEstimate / 1000).toInt(), streamKbps(), droppedWindow)
        val decoder = why.startsWith("the TV's video decoder")
        note(PlaybackLog.STALL, (if (left) "Waited $s s for data, then left the channel. " else if (decoder) "Froze $s s. " else "Froze $s s waiting for data. ") + "Likely: $why.", s)
        stallAt = 0
    }

    // ---- #25: one channel can't flood the log ----
    private var minuteAt = 0L
    private var minuteCount = 0
    private var minuteSkipped = 0
    /** Writes to the log, at most [MAX_PER_MINUTE] entries a minute for a channel; the rest are counted, then noted once. */
    private fun put(kind: String, text: String) {
        val now = clock()
        if (now - minuteAt >= 60_000) {
            if (minuteSkipped > 0) log.add(PlaybackLog.STALL, name, "…and $minuteSkipped more entries in that minute, left out.")
            minuteAt = now; minuteCount = 0; minuteSkipped = 0
        }
        if (++minuteCount > MAX_PER_MINUTE) { minuteSkipped++; return }
        log.add(kind, name, text)
    }

    private fun note(kind: String, text: String, seconds: Int) {
        put(kind, text)
        stats = stats.copy(freezes = stats.freezes + 1, longestFreezeS = maxOf(stats.longestFreezeS, seconds), lastProblem = text)
    }

    override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData, error: IOException, wasCanceled: Boolean) {
        if (wasCanceled) return
        val what = describe(error)
        recentLoadErrors.addLast(clock() to what)
        while (recentLoadErrors.size > 20) recentLoadErrors.removeFirst()
        put(PlaybackLog.LOAD, what)
    }

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
        endStall(left = false)
        val cause = generateSequence(error.cause) { it.cause }.firstOrNull { it is IOException }?.let { describe(it as IOException) }
        note(PlaybackLog.ERROR, "Player stopped: ${error.errorCodeName}" + (cause?.let { " ($it)" } ?: "") + ".", 0)
    }

    /** One DEVICE entry: memory, the app's processor use and the screen open (never a channel or title). */
    private fun logDevice(why: String) {
        val load = runCatching { deviceLoad?.invoke() }.getOrNull()?.ifBlank { null } ?: return
        val screen = runCatching { screenName?.invoke() }.getOrNull()?.ifBlank { null }
        put(PlaybackLog.DEVICE, load + (screen?.let { " · on $it" } ?: "") + (if (why.isNotBlank()) " ($why)" else ""))
    }

    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        // Nov 21, 2026: every start of the video decoder after the first on a stream (a new video window, a reconnect).
        decoderStarts++
        if (decoderStarts > 1) put(PlaybackLog.FORMAT, "Video decoder started again ($decoderStarts times on this stream, ${initializationDurationMs} ms)")
        if (decoderName == this.decoderName) return
        this.decoderName = decoderName
        put(PlaybackLog.FORMAT, "Video decoder: $decoderName" + if (com.avidviewer.tv.data.Decoders.isSoftware(decoderName)) " (software)" else "")
    }

    override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
        put(PlaybackLog.FORMAT, formatText(format))
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        dropped += droppedFrames
        if (droppedWindowAt == 0L) droppedWindowAt = clock()
        droppedWindow += droppedFrames
    }

    private fun videoText() = player.videoFormat?.let { formatText(it) } ?: ""
    private fun streamKbps(): Int = player.videoFormat?.let { f -> listOf(f.bitrate, f.peakBitrate, f.averageBitrate).firstOrNull { it > 0 } }?.let { it / 1000 } ?: 0

    /** When stream data last arrived (any bytes from the provider). Fed by [transfer]. */
    object DataFlow {
        @Volatile var lastBytesAt = 0L
    }

    companion object {
        const val DEVICE_EVERY_MS = 120_000L
        /** After 3 reconnects in 3 minutes, the shortest gap between reconnects (BUG-058). */
        const val SLOW_RECONNECT_MS = 30_000L
        /** #30: the same for a still picture. */
        const val SLOW_PICTURE_MS = 15_000L
        /** #31: how a stutter with a full buffer is named ([reconnectReason]). */
        const val DECODER_BEHIND = "the TV's decoder falling behind"
        /** #25: at most this many log entries a minute for one channel. */
        const val MAX_PER_MINUTE = 20

        /** [recent]: reconnects in the last 3 minutes, oldest first. Up to 3 at once, then one every [SLOW_RECONNECT_MS]. */
        fun mayReconnect(now: Long, recent: Collection<Long>, gap: Long = SLOW_RECONNECT_MS): Boolean = recent.size < 3 || now - recent.last() >= gap
        /**
         * Why the stream should be reconnected now, or null (see checkReconnect): the picture still for 5 s, or no
         * data for 4 s with under 8 s buffered; never in the first 10 s of a channel or of a reconnect.
         */
        fun reconnectReason(now: Long, startedAt: Long, reconnectAt: Long, picStuckSince: Long, lastBytesAt: Long, bufferedMs: Long, everReady: Boolean,
                            stallingSince: Long = 0L, bufferGrewAt: Long = now, stopsIn30s: Int = 0): String? {
            if (now - startedAt < 10_000 || (reconnectAt > 0 && now - reconnectAt < 10_000)) return null
            if (picStuckSince > 0 && now - picStuckSince >= 5_000) return "the picture stood still for ${(now - picStuckSince) / 1000} s"
            val quiet = now - lastBytesAt
            if (everReady && quiet >= 4_000 && bufferedMs < 8_000) return "no data for ${quiet / 1000} s with ${bufferedMs / 1000} s left in the buffer"
            // #23: waiting 6 s with the buffer not growing, though a few bytes keep arriving (the provider trickling).
            // (#31: not with 8 s or more buffered: a full buffer doesn't grow because it's full.)
            if (everReady && stallingSince > 0 && now - stallingSince >= 6_000 && now - bufferGrewAt >= 6_000 && bufferedMs < 8_000) return "waiting ${(now - stallingSince) / 1000} s with the buffer not growing"
            // #25: the picture keeps stopping for a moment. #31: with 20 s or more buffered it isn't the data.
            if (everReady && stopsIn30s > 5) return if (bufferedMs >= 20_000) "$DECODER_BEHIND ($stopsIn30s stops in 30 s with ${bufferedMs / 1000} s buffered)" else "stuttering ($stopsIn30s stops in 30 s)"
            return null
        }

        /** Notes each piece of data that arrives, for the "no data for 4 s" rule. */
        val transfer = object : androidx.media3.datasource.TransferListener {
            override fun onTransferInitializing(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {}
            override fun onTransferStart(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) { DataFlow.lastBytesAt = System.currentTimeMillis() }
            override fun onBytesTransferred(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean, bytesTransferred: Int) { if (bytesTransferred > 0) DataFlow.lastBytesAt = System.currentTimeMillis() }
            override fun onTransferEnd(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {}
        }

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
                // BUG-059: never the class name: release builds are obfuscated, so it read as "t" in the logs.
                chain.any { it is javax.net.ssl.SSLException } -> "secure connection failed (SSL)"
                chain.any { it is java.io.EOFException } -> "the connection ended early"
                chain.any { it is java.io.InterruptedIOException } -> "connection timed out"
                chain.any { it is java.net.ProtocolException } -> "the server's answer couldn't be read"
                chain.any { it is androidx.media3.common.ParserException } -> "the stream's data couldn't be read"
                chain.any { it is HttpDataSource.CleartextNotPermittedException } -> "plain http isn't allowed for this address"
                chain.any { it is HttpDataSource.HttpDataSourceException } -> "download failed (network)"
                else -> "download failed"
            }
        }

        /**
         * The likely cause of a freeze, from the download errors around it, how much was buffered just before,
         * and the download speed against the stream's bitrate (0 = unknown).
         */
        fun cause(loadErrors: List<String>, bufferBeforeMs: Long, downloadKbps: Int, streamKbps: Int, droppedFrames: Int = 0): String {
            val http = loadErrors.filter { it.startsWith("provider answered HTTP") }
            val net = loadErrors.filter { it.contains("timed out") || it.contains("dropped") || it.contains("connect") || it.contains("DNS") }
            return when {
                http.isNotEmpty() -> "the provider (" + http.groupingBy { it.removePrefix("provider answered ") }.eachCount().entries.joinToString(", ") { "${it.key} ×${it.value}" } + ")"
                net.isNotEmpty() -> "the network or the provider's server (" + net.groupingBy { it }.eachCount().entries.joinToString(", ") { "${it.key} ×${it.value}" } + ")"
                streamKbps > 0 && downloadKbps in 1 until streamKbps -> "the network or provider is too slow (getting ${mbps(downloadKbps)}, the stream needs ${mbps(streamKbps)})"
                // #31: a stop with plenty buffered and nothing failing to download is the TV's video player, not the data.
                bufferBeforeMs >= 10_000 && loadErrors.isEmpty() -> "the TV's video decoder fell behind, not the download (${bufferBeforeMs / 1000} s was buffered, no download errors" +
                    (if (droppedFrames > 0) ", $droppedFrames frames dropped" else "") + ")"
                bufferBeforeMs < 1_500 -> "data stopped arriving: the buffer ran out with no download error" + (if (downloadKbps > 0) " (download speed ${mbps(downloadKbps)})" else "") + ", which usually means the provider's server paused the stream"
                else -> "unclear (${bufferBeforeMs / 1000} s was buffered, no download errors)"
            }
        }
    }
}
