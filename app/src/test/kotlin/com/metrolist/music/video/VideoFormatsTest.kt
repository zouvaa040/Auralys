package com.metrolist.music.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFormatsTest {
    @Test
    fun `vp9 in webm is read from the container and the codecs string`() {
        val f = VideoFormats.parse("video/webm", "vp9", 640, 360, 500_000)
        assertNotNull(f)
        assertEquals(VideoContainer.WEBM, f!!.container)
        assertEquals(VideoCodec.VP9, f.codec)
        assertEquals(640, f.width)
        assertEquals(360, f.height)
    }

    @Test
    fun `h264 in mp4 with a full profile string`() {
        val f = VideoFormats.parse("video/mp4", "avc1.4D401E", 640, 360, null)
        assertEquals(VideoContainer.MP4, f!!.container)
        assertEquals(VideoCodec.AVC, f.codec)
    }

    @Test
    fun `the codecs can sit inside the mime type`() {
        val f = VideoFormats.parse("video/webm; codecs=\"vp09.00.40.08\"", null, 1280, 720, null)
        assertEquals(VideoCodec.VP9, f!!.codec)
        val g = VideoFormats.parse("Video/MP4;codecs=\"avc1.64001F\"", null, 1280, 720, null)
        assertEquals(VideoCodec.AVC, g!!.codec)
    }

    @Test
    fun `av1 in webm and in mp4 are both readable`() {
        assertEquals(VideoCodec.AV1, VideoFormats.parse("video/webm", "av01.0.05M.08", 640, 360, null)!!.codec)
        assertEquals(VideoCodec.AV1, VideoFormats.parse("video/mp4", "av01.0.05M.08", 640, 360, null)!!.codec)
    }

    @Test
    fun `unknown containers codecs and impossible pairs are not read`() {
        assertNull(VideoFormats.parse("audio/webm", "opus", 0, 0, null))
        assertNull(VideoFormats.parse("video/3gpp", "avc1", 0, 0, null))
        assertNull(VideoFormats.parse("video/webm", "avc1.4D401E", 640, 360, null))
        assertNull(VideoFormats.parse("video/mp4", "vp9", 640, 360, null))
        assertNull(VideoFormats.parse("video/webm", null, 640, 360, null))
        assertNull(VideoFormats.parse(null, "vp9", 640, 360, null))
    }

    @Test
    fun `a codecs list takes the video codec`() {
        assertEquals(VideoCodec.VP9, VideoFormats.codecOf("opus, vp9"))
        assertEquals(VideoCodec.HEVC, VideoFormats.codecOf("hvc1.1.6.L93.B0"))
        assertNull(VideoFormats.codecOf("mp4a.40.2"))
        assertNull(VideoFormats.codecOf(""))
    }

    @Test
    fun `a codec the phone cannot decode is refused, the size is passed on`() {
        val f = VideoFormats.parse("video/webm", "vp9", 1280, 720, null)!!
        var asked: Triple<String, Int, Int>? = null
        assertFalse(
            VideoFormats.isDecodable(f) { m, w, h ->
                asked = Triple(m, w, h)
                false
            },
        )
        assertEquals(Triple("video/x-vnd.on2.vp9", 1280, 720), asked)
        assertTrue(VideoFormats.isDecodable(f) { _, _, _ -> true })
        val h264 = VideoFormats.parse("video/mp4", "avc1.4D401E", 640, 360, null)!!
        assertTrue(VideoFormats.isDecodable(h264) { m, _, _ -> m == "video/avc" })
    }

    @Test
    fun `quality is 360 on a metered network, with the data saver and on a low ram phone, else 720`() {
        assertEquals(720, VideoQuality.maxHeight(metered = false, dataSaverActive = false, lowRam = false))
        assertEquals(360, VideoQuality.maxHeight(metered = true, dataSaverActive = false, lowRam = false))
        assertEquals(360, VideoQuality.maxHeight(metered = false, dataSaverActive = true, lowRam = false))
        assertEquals(360, VideoQuality.maxHeight(metered = false, dataSaverActive = false, lowRam = true))
    }

    // ---------------------------------------------------------------- REVIEW-2026-11-07 F3: hardware or software

    @Test
    fun `software decoders are told by name below API 29`() {
        for (soft in listOf("OMX.google.vp9.decoder", "c2.android.vp9.decoder", "c2.google.av1.decoder", "OMX.ffmpeg.vp9.decoder", "OMX.sec.vp9.sw.dec", "vendor.decoder")) {
            assertTrue(soft, DecoderChoice.isSoftwareName(soft))
        }
        for (hard in listOf("OMX.qcom.video.decoder.vp9", "c2.qti.vp9.decoder", "c2.exynos.vp9.decoder", "OMX.MTK.VIDEO.DECODER.VP9", "arc.vp9.decoder")) {
            assertFalse(hard, DecoderChoice.isSoftwareName(hard))
        }
    }

    @Test
    fun `one hardware decoder is enough, only software ones mean software, none means none`() {
        val soft = DecoderCandidate("c2.android.vp9.decoder", null)
        val hard = DecoderCandidate("c2.qti.vp9.decoder", null)
        assertEquals(DecoderKind.NONE, DecoderChoice.kindOf(emptyList()))
        assertEquals(DecoderKind.SOFTWARE, DecoderChoice.kindOf(listOf(soft)))
        assertEquals(DecoderKind.HARDWARE, DecoderChoice.kindOf(listOf(soft, hard)))
        assertEquals(DecoderKind.HARDWARE, DecoderChoice.kindOf(listOf(hard)))
    }

    @Test
    fun `what the system says about acceleration wins over the name`() {
        // API 29+: isHardwareAccelerated is given
        assertEquals(DecoderKind.HARDWARE, DecoderChoice.kindOf(listOf(DecoderCandidate("OMX.google.vp9.decoder", true))))
        assertEquals(DecoderKind.SOFTWARE, DecoderChoice.kindOf(listOf(DecoderCandidate("c2.qti.vp9.decoder", false))))
    }

    @Test
    fun `a software decoder gets at most 360 p`() {
        assertEquals(360, DecoderChoice.SOFTWARE_MAX_HEIGHT)
        assertFalse(DecoderChoice.tooSharpForSoftware(360, 720))
        assertFalse(DecoderChoice.tooSharpForSoftware(240, 720))
        assertTrue(DecoderChoice.tooSharpForSoftware(720, 720))
        assertTrue(DecoderChoice.tooSharpForSoftware(480, 360))
        // an unknown height depends on what was asked for
        assertTrue(DecoderChoice.tooSharpForSoftware(0, 720))
        assertFalse(DecoderChoice.tooSharpForSoftware(0, 360))
    }

    @Test
    fun `hardware decoders are tried first, the order inside each group is kept`() {
        val all = listOf("sw1" to false, "hw1" to true, "sw2" to false, "hw2" to true)
        val sorted = DecoderChoice.hardwareFirst(all) { it.second }.map { it.first }
        assertEquals(listOf("hw1", "hw2", "sw1", "sw2"), sorted)
        assertEquals(emptyList<String>(), DecoderChoice.hardwareFirst(emptyList<String>()) { true })
    }

    @Test
    fun `the data saver counts as active like the player service reads it`() {
        // on, only on mobile data: active only on a metered network
        assertTrue(VideoQuality.dataSaverActive(dataSaverOn = true, mobileOnly = true, metered = true))
        assertFalse(VideoQuality.dataSaverActive(dataSaverOn = true, mobileOnly = true, metered = false))
        // on, everywhere: always
        assertTrue(VideoQuality.dataSaverActive(dataSaverOn = true, mobileOnly = false, metered = false))
        // off: never
        assertFalse(VideoQuality.dataSaverActive(dataSaverOn = false, mobileOnly = false, metered = true))
    }
}
