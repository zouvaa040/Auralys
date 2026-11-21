package com.metrolist.music.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The one line of the media-info dialog: which words the owner reads, and in which order the facts win. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VideoStatusLineTest {
    @Test
    fun `the setting off wins over everything`() {
        assertEquals(VideoStatusLine.Off, VideoStatusLine.resolve(enabled = false, cooldownRemainingMs = 30_000, status = VideoStatus.Available))
    }

    @Test
    fun `a rest of the whole mode wins over what happened to the song, and counts up to the next whole second`() {
        assertEquals(VideoStatusLine.Cooldown(30), VideoStatusLine.resolve(true, 30_000, VideoStatus.Available))
        assertEquals(VideoStatusLine.Cooldown(1), VideoStatusLine.resolve(true, 1, VideoStatus.NoMatch))
        assertEquals(VideoStatusLine.Cooldown(2), VideoStatusLine.resolve(true, 1_001, VideoStatus.Unknown))
    }

    @Test
    fun `without a rest the status of the song is told`() {
        assertEquals(VideoStatusLine.Unknown, VideoStatusLine.resolve(true, 0, VideoStatus.Unknown))
        assertEquals(VideoStatusLine.Checking, VideoStatusLine.resolve(true, 0, VideoStatus.Checking))
        assertEquals(VideoStatusLine.Available, VideoStatusLine.resolve(true, 0, VideoStatus.Available))
        assertEquals(VideoStatusLine.NoMatch, VideoStatusLine.resolve(true, 0, VideoStatus.NoMatch))
        assertEquals(
            VideoStatusLine.Failed(VideoFailureKind.BOT_CHECK, retrying = false),
            VideoStatusLine.resolve(true, 0, VideoStatus.Failed(VideoFailureKind.BOT_CHECK, retrying = false)),
        )
    }

    @Test
    fun `diagnostics keep the last status per song, newest wins, and are bounded`() {
        VideoDiagnostics.clear()
        assertEquals(VideoStatus.Unknown, VideoDiagnostics.statusFor("x"))
        VideoDiagnostics.set("x", VideoStatus.Checking)
        VideoDiagnostics.set("x", VideoStatus.Available)
        assertEquals(VideoStatus.Available, VideoDiagnostics.statusFor("x"))
        val v = VideoDiagnostics.version.value
        VideoDiagnostics.set("x", VideoStatus.Available)
        assertEquals("the same status is not a change", v, VideoDiagnostics.version.value)
        for (i in 1..100) VideoDiagnostics.set("s$i", VideoStatus.NoMatch)
        assertEquals(VideoStatus.Unknown, VideoDiagnostics.statusFor("x"))
        assertEquals(VideoStatus.NoMatch, VideoDiagnostics.statusFor("s100"))
        VideoDiagnostics.clear()
    }

    private fun strings(lang: String): Map<String, String> {
        val dir = File(System.getProperty("user.dir"), "src/main/res/$lang/auralys_video4.xml")
        val text = dir.readText()
        return Regex("""<string name="([^"]+)">([^<]*)</string>""").findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `both languages have the same keys, one for every reason and every status`() {
        val en = strings("values")
        val id = strings("values-in")
        assertEquals(en.keys, id.keys)
        for (kind in VideoFailureKind.entries) assertTrue("reason of $kind", "video4_reason_${kind.key}" in en)
        for (key in listOf("available", "checking", "nomatch", "failed", "failed_retry", "cooldown", "off", "unknown")) {
            assertTrue("status $key", "video4_status_$key" in en)
        }
    }

    @Test
    fun `the Indonesian words follow the wording rule`() {
        val id = strings("values-in")
        val all = id.values.joinToString(" ").lowercase()
        // plain: jika (not kalau), tidak (not nggak/gak), no slang, no filler
        for (bad in listOf("kalau", "nggak", "gak ", "banget", "dong", "nih", "anda")) assertTrue("'$bad' in the Indonesian text", !all.contains(bad))
        assertEquals("tersedia", id["video4_status_available"])
        assertEquals("tidak ada klip yang cocok", id["video4_status_nomatch"])
        assertEquals("gagal: %1\$s", id["video4_status_failed"])
        assertEquals("dimatikan sementara (coba lagi dalam %1\$d detik)", id["video4_status_cooldown"])
    }
}
