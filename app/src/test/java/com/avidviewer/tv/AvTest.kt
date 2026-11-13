package com.avidviewer.tv

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.test.core.app.ApplicationProvider
import com.avidviewer.tv.data.Av
import com.avidviewer.tv.data.AvChoice
import com.avidviewer.tv.data.AvTrack
import com.avidviewer.tv.data.CaptionStyle
import com.avidviewer.tv.data.Prefs
import com.avidviewer.tv.ui.AppViewModel
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Picture & sound: describing and finding tracks, saving choices, and putting them on the player. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AvTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var vm: AppViewModel? = null

    @After fun stop() { vm?.viewModelScope?.cancel(); vm?.player?.release(); shadowOf(android.os.Looper.getMainLooper()).idle() }

    private fun a(i: Int, lang: String?, ch: Int, mime: String, roles: Int = 0, label: String? = null) =
        AvTrack(audio = true, group = i, index = 0, lang = lang, label = label, channels = ch, mime = mime, roles = roles, selected = i == 0)

    @Test
    fun tracksAreDescribedInPlainWords() {
        val main = a(0, "en", 6, "audio/ac3", roles = C.ROLE_FLAG_MAIN)
        val sap = a(1, "spa", 2, "audio/mp4a-latm")
        val dv = a(2, "en", 2, "audio/mp4a-latm", roles = C.ROLE_FLAG_DESCRIBES_VIDEO)
        val all = listOf(main, sap, dv)
        assertEquals("English · Dolby Digital 5.1", Av.describe(main, all))
        assertEquals("Spanish · AAC Stereo · Secondary audio", Av.describe(sap, all))
        assertEquals("English · AAC Stereo · Described video", Av.describe(dv, all))
        val cc1 = AvTrack(false, 3, 0, null, null, 0, "application/cea-608", 0, false, cc = 1)
        assertEquals("CC1 · TV captions", Av.describe(cc1, listOf(cc1)))
        val vtt = AvTrack(false, 4, 0, "fr", null, 0, "text/vtt", 0, false)
        assertEquals("French · WebVTT", Av.describe(vtt, listOf(cc1, vtt)))
    }

    @Test
    fun aSavedTrackIsFoundAgainEvenWhenTheNumberingChanges() {
        val en = a(0, "en", 2, "audio/mp4a-latm"); val es = a(1, "es", 2, "audio/mp4a-latm")
        val sig = Av.signature(es)
        // Next visit: the tracks come in another order, Spanish now with a 3-letter code.
        val later = listOf(a(0, "spa", 2, "audio/mp4a-latm"), a(1, "eng", 2, "audio/mp4a-latm"))
        assertEquals(0, Av.pick(later, sig)!!.group)
        // Same language with another format still counts; another language doesn't.
        assertEquals(1, Av.pick(listOf(a(0, "fr", 2, "audio/ac3"), a(1, "es", 6, "audio/ac3")), sig)!!.group)
        assertEquals(null, Av.pick(listOf(en), sig))
        assertEquals(null, Av.pick(listOf(en, es), null))
    }

    @Test
    fun choicesAreSavedPerChannelOverTheDefault() {
        val p = Prefs(
            avDefault = AvChoice(format = "zoom", captions = true),
            avChannels = mapOf("p:1" to AvChoice(format = "4:3", captions = false), "p:2" to AvChoice()),
            captionStyle = CaptionStyle(size = "large", background = "none", position = "raised"), avForAll = true,
        )
        val back = Prefs.fromJson(JSONObject(p.toJson().toString()))
        assertEquals(p.avDefault, back.avDefault)
        assertEquals(mapOf("p:1" to AvChoice(format = "4:3", captions = false)), back.avChannels) // empty ones aren't kept
        assertEquals(p.captionStyle, back.captionStyle)
        assertTrue(back.avForAll)
        assertEquals(AvChoice(format = "4:3", captions = false), back.avChannels["p:1"]!!.over(back.avDefault))
        assertEquals(AvChoice(format = "zoom", captions = true), AvChoice().over(back.avDefault))
        // Older settings files have none of this.
        val old = Prefs.fromJson(JSONObject("{}"))
        assertEquals(AvChoice(), old.avDefault); assertFalse(old.avForAll); assertEquals(CaptionStyle(), old.captionStyle)
    }

    @Test
    fun theChosenAudioAndCaptionsArePutOnThePlayer() {
        TestData.seed(app)
        val v = AppViewModel(app).also { vm = it }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val audio = TrackGroup(
            Format.Builder().setId("a1").setSampleMimeType(MimeTypes.AUDIO_AC3).setLanguage("en").setChannelCount(6).build(),
            Format.Builder().setId("a2").setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage("es").setChannelCount(2).build(),
        )
        val text = TrackGroup(Format.Builder().setId("cc").setSampleMimeType(MimeTypes.APPLICATION_CEA608).setAccessibilityChannel(1).build())
        val tracks = Tracks(listOf(
            Tracks.Group(audio, false, intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED), booleanArrayOf(true, false)),
            Tracks.Group(text, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)),
        ))
        v.tracksChanged(tracks)
        assertEquals(listOf("English · Dolby Digital 5.1", "Spanish · AAC Stereo · Secondary audio"), v.audioTracks.map { Av.describe(it, v.audioTracks) })
        assertEquals(1, v.captionTracks.size)
        // Nothing chosen: the stream's own audio, captions off.
        assertTrue(v.player.trackSelectionParameters.overrides.isEmpty())
        assertTrue(C.TRACK_TYPE_TEXT in v.player.trackSelectionParameters.disabledTrackTypes)
        // Secondary audio and captions on.
        v.setAudioTrack(v.audioTracks[1])
        v.setCaptions(v.captionTracks[0])
        val params = v.player.trackSelectionParameters
        assertEquals(1, params.overrides[audio]!!.trackIndices.single())
        assertEquals(0, params.overrides[text]!!.trackIndices.single())
        assertFalse(C.TRACK_TYPE_TEXT in params.disabledTrackTypes)
        // Applying again with the same tracks changes nothing (no loop with the player's own callback).
        v.tracksChanged(tracks)
        assertTrue(params == v.player.trackSelectionParameters)
        // Off and Automatic undo it.
        v.setCaptions(null); v.setAudioTrack(null)
        assertTrue(v.player.trackSelectionParameters.overrides.isEmpty())
        assertTrue(C.TRACK_TYPE_TEXT in v.player.trackSelectionParameters.disabledTrackTypes)
    }
}
