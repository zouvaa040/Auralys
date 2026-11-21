package com.metrolist.music.lyrics

import com.metrolist.music.lyrics.LyricsSelection.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The automatic choice on the answers recorded from the live probe of 2026-11-12 (`LyricsLiveProbeTest`, fixtures in
 * `src/test/resources/lyrics-probe/`: the shape of each source's lyrics and what it said it matched, no lyric text).
 * Offline: the rules run on what the sources really answered for the reported songs.
 */
class LyricsProbeFixturesTest {
    /** The default order as the app asks it, LyricsPlus left out (it is off by default). */
    private val order = LyricsProviderRegistry.getDefaultProviderOrder().filter { it != "LyricsPlus" }

    private class Outcome(val chosen: LyricsSelection.Candidate?, val rated: Map<String, LyricsSelection.Candidate>)

    private fun choose(name: String): Outcome {
        val fixture = LyricsProbeFixtures.load(name)
        val request = fixture.request
        val rated =
            order.mapNotNull { key -> fixture.answers[key]?.let { key to LyricsSelection.rate(key, request, it.hit()) } }.toMap()
        return Outcome(LyricsSelection.best(order.mapNotNull { rated[it] }), rated)
    }

    private fun firstMs(c: LyricsSelection.Candidate?): Long = LyricsSelection.firstSungMs(c!!.lyrics)!!

    @Test
    fun `La La Lost You (Acoustic Version) gets the acoustic timing, not the original's`() {
        val out = choose("la-la-lost-you-acoustic-version-niki")
        assertEquals("BiniLyrics", out.chosen?.provider)
        assertEquals(Quality.VERIFIED, out.chosen?.quality)
        assertEquals(LyricsSelection.Sync.WORD, out.chosen?.sync)
        // the acoustic version starts singing at 1.4 s
        assertTrue(firstMs(out.chosen) in 1_000L..2_000L)
        // what build 448 showed: LRCLIB's original "La La Lost You" (also 209 s), singing from 7.6 s
        val old = LyricsProbeFixtures.load("la-la-lost-you-acoustic-version-niki").providers.getValue("LrcLib")!!
        assertEquals(7_590L, old.times.first())
        // BetterLyrics found the right document but does not say which; a version song keeps it below checked sources
        assertEquals(Quality.LOOSE, out.rated.getValue("BetterLyrics").quality)
        // LRCLIB's own acoustic entry is ~5 s late against three Apple documents: passed over
        assertTrue(out.rated.getValue("LrcLib") in LyricsSelection.outliers(order.mapNotNull { out.rated[it] }))
    }

    @Test
    fun `La La Lost You (the original cut) is not affected`() {
        val out = choose("la-la-lost-you-niki")
        assertEquals("BiniLyrics", out.chosen?.provider)
        assertTrue(firstMs(out.chosen) in 9_000L..10_500L)
    }

    @Test
    fun `Untitled by Rex Orange County takes its own lyrics, never another band's or KuGou's title line`() {
        val out = choose("untitled-rex-orange-county")
        assertEquals("BetterLyrics", out.chosen?.provider)
        assertEquals(Quality.REJECTED, out.rated.getValue("LrcLib").quality)
        // KuGou's answer no longer starts with "Untitled (Explicit) - Rex Orange County" at 0:00
        assertTrue(firstMs(out.rated.getValue("KuGou")) > 1_000L)
    }

    @Test
    fun `Dracula and Dracula (feat JENNIE) get their own documents`() {
        val solo = choose("dracula-tame-impala")
        val duet = choose("dracula-feat-jennie-tame-impala")
        assertEquals("BetterLyrics", solo.chosen?.provider)
        assertEquals("BetterLyrics", duet.chosen?.provider)
        val soloLines = solo.chosen!!.lyrics.lines().count { it.startsWith("[") }
        val duetLines = duet.chosen!!.lyrics.lines().count { it.startsWith("[") }
        assertNotEquals(soloLines, duetLines)
        // the solo document (3:25) is not taken for the duet (3:30)
        assertEquals(Quality.REJECTED, duet.rated.getValue("BiniLyrics").quality)
    }

    @Test
    fun `Despacito (Remix) does not get the original's lyrics`() {
        val out = choose("despacito-remix-luis-fonsi-justin-bieber")
        assertEquals("BiniLyrics", out.chosen?.provider)
        assertEquals(Quality.LOOSE, out.rated.getValue("BetterLyrics").quality)
        // another remix (DNMO) is another recording
        assertEquals(Quality.REJECTED, out.rated.getValue("KuGou").quality)
        assertTrue(firstMs(out.chosen) in 9_000L..10_000L)
    }

    @Test
    fun `Hotel California does not take the live entry`() {
        val out = choose("hotel-california-live-eagles")
        assertEquals("LrcRed", out.chosen?.provider)
        assertTrue(out.rated.getValue("Unison") in LyricsSelection.outliers(order.mapNotNull { out.rated[it] }))
    }

    @Test
    fun `Die For You (Sped Up) gets the sped up timing`() {
        val out = choose("die-for-you-sped-up-the-weeknd")
        assertEquals("BiniLyrics", out.chosen?.provider)
        // what build 448 had from LRCLIB: the original's timing, running to 4:03 on a 3:44 track
        val old = LyricsProbeFixtures.load("die-for-you-sped-up-the-weeknd").providers.getValue("LrcLib")!!
        assertEquals(Quality.REJECTED, LyricsSelection.quality(LyricsProbeFixtures.text(old), 224))
    }

    @Test
    fun `Line Without a Hook does not take a document of another cut`() {
        val out = choose("line-without-a-hook-ricky-montgomery")
        // BetterLyrics' TTML is for a 3:59 cut of a 4:10 track
        assertEquals(Quality.REJECTED, out.rated.getValue("BetterLyrics").quality)
        assertEquals("BiniLyrics", out.chosen?.provider)
    }

    @Test
    fun `an ad-lib line is not taken for another version and a mixed cut is`() {
        val out = choose("blinding-lights-the-weeknd")
        assertEquals("BetterLyrics", out.chosen?.provider)
        assertTrue(LyricsSelection.outliers(order.mapNotNull { out.rated[it] }).isEmpty())
        assertEquals(Quality.REJECTED, out.rated.getValue("LrcRed").quality)
    }

    @Test
    fun `word timing wins over line timing, and Indonesian songs are found`() {
        assertEquals("BiniLyrics", choose("snooze-sza").chosen?.provider)
        assertEquals("BiniLyrics", choose("bahagia-lagi-piche-kota").chosen?.provider)
        val usai = choose("tak-ingin-usai-keisya-levronka")
        assertEquals("BetterLyrics", usai.chosen?.provider)
        assertEquals(LyricsSelection.Sync.WORD, usai.chosen?.sync)
    }
}
