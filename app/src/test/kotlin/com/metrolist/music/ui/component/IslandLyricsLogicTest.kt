package com.metrolist.music.ui.component

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.metrolist.music.constants.DynamicIslandLayout
import com.metrolist.music.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IslandLyricsLogicTest {
    private val lrc =
        """
        [00:01.00]First line
        [00:05.50]Second line
        [00:09.00]Third
        """.trimIndent()

    @Test
    fun `stored lyrics turn into a status and lines`() {
        assertEquals(IslandLyricsStatus.LOADING, IslandLyricsLogic.fromStored(null).status)
        assertEquals(IslandLyricsStatus.NONE, IslandLyricsLogic.fromStored(LYRICS_NOT_FOUND).status)
        assertEquals(IslandLyricsStatus.NONE, IslandLyricsLogic.fromStored("  ").status)
        val plain = IslandLyricsLogic.fromStored("just words\nno times")
        assertEquals(IslandLyricsStatus.PLAIN, plain.status)
        assertTrue(plain.lines.isEmpty())
        val synced = IslandLyricsLogic.fromStored(lrc)
        assertEquals(IslandLyricsStatus.SYNCED, synced.status)
        assertEquals(listOf("First line", "Second line", "Third"), synced.lines.map { it.text })
        assertEquals(listOf(1000L, 5500L, 9000L), synced.lines.map { it.startMs })
    }

    @Test
    fun `a line ends at the next one or after a time its length allows`() {
        val lines = IslandLyricsLogic.fromStored(lrc).lines
        // "First line" is short: 1.6 s, well before the next line
        assertEquals(2600L, lines[0].endMs)
        // the last line gets 4 s at most
        assertTrue(lines[2].endMs in 9001L..13000L)
        lines.forEach { assertTrue("a line always lasts", it.endMs > it.startMs) }
    }

    @Test
    fun `the line at a position is the last one that started`() {
        val lines = IslandLyricsLogic.fromStored(lrc).lines
        assertEquals(-1, IslandLyricsLogic.indexAt(lines, 0))
        assertEquals(-1, IslandLyricsLogic.indexAt(lines, 870))
        // a line shows 120 ms before its time stamp
        assertEquals(0, IslandLyricsLogic.indexAt(lines, 880))
        assertEquals(0, IslandLyricsLogic.indexAt(lines, 5000))
        assertEquals(1, IslandLyricsLogic.indexAt(lines, 5400))
        assertEquals(2, IslandLyricsLogic.indexAt(lines, 600_000))
        assertEquals(-1, IslandLyricsLogic.indexAt(emptyList(), 1000))
    }

    @Test
    fun `the sung part grows from zero to one and stays inside`() {
        val lines = IslandLyricsLogic.fromStored(lrc).lines
        assertEquals(0f, IslandLyricsLogic.sweepAt(lines, 0, 500), 0f)
        assertEquals(0.5f, IslandLyricsLogic.sweepAt(lines, 0, 1800), 0.001f)
        assertEquals(1f, IslandLyricsLogic.sweepAt(lines, 0, 8000), 0f)
        assertEquals(0f, IslandLyricsLogic.sweepAt(lines, -1, 8000), 0f)
        var last = 0f
        for (t in 1000L..2600L step 50L) {
            val v = IslandLyricsLogic.sweepAt(lines, 0, t)
            assertTrue(v >= last)
            last = v
        }
    }

    @Test
    fun `a long line glides only in the middle of being sung`() {
        assertEquals(0f, IslandLyricsLogic.followFraction(0f), 0f)
        assertEquals(0f, IslandLyricsLogic.followFraction(0.25f), 0f)
        assertEquals(1f, IslandLyricsLogic.followFraction(0.85f), 0.001f)
        assertEquals(1f, IslandLyricsLogic.followFraction(1f), 0f)
        assertTrue(IslandLyricsLogic.followFraction(0.5f) in 0.1f..0.9f)
    }

    // 10 px per letter
    private val ten: (String) -> Float = { it.length * 10f }

    @Test
    fun `a line is split around the camera at a space`() {
        // room for 15 letters left of the camera
        assertEquals("Melihatmu masuk" to "ke dalam ruang operasi", IslandLyricsLogic.splitAround("Melihatmu masuk ke dalam ruang operasi", 150f, ten))
        assertEquals("Namun" to "jangan hari ini", IslandLyricsLogic.splitAround("Namun jangan hari ini", 100f, ten))
        // it all fits: nothing after the camera
        assertEquals("Hai" to "", IslandLyricsLogic.splitAround("Hai", 150f, ten))
        // no room left of the camera: everything after it
        assertEquals("" to "Hai dunia", IslandLyricsLogic.splitAround("Hai dunia", 0f, ten))
        assertEquals("" to "Hai dunia", IslandLyricsLogic.splitAround("Hai dunia", -20f, ten))
    }

    @Test
    fun `one long word is broken by letters and no text is lost`() {
        val (a, b) = IslandLyricsLogic.splitAround("Supercalifragilistic", 80f, ten)
        assertEquals("Supercal", a)
        assertEquals("ifragilistic", b)
        val (c, d) = IslandLyricsLogic.splitAround("Satu dua tiga empat lima enam", 95f, ten)
        assertEquals("Satu dua", c)
        assertEquals("Satu dua tiga empat lima enam".replace("Satu dua ", ""), d)
        // a letter that does not fit at all
        assertEquals("" to "WWWW", IslandLyricsLogic.splitAround("WWWW", 5f, ten))
    }

    @Test
    fun `the camera gap only exists where the hole is over the text`() {
        // text from x=300 and 500 px wide, 2 px per dp; hole 480..560 over the middle of it
        val gap = IslandLyricsLogic.cameraGapDp(480f, 560f, 300f, 500f, 10f, 2f)!!
        assertEquals(85f, gap.first, 0.001f)
        assertEquals(135f, gap.second, 0.001f)
        // a hole that starts before the text only takes the beginning
        val early = IslandLyricsLogic.cameraGapDp(250f, 340f, 300f, 500f, 10f, 2f)!!
        assertTrue(early.first < 0f && early.second > 0f)
        // beside the text: no gap, the line is drawn as usual
        assertEquals(null, IslandLyricsLogic.cameraGapDp(0f, 136f, 300f, 500f, 10f, 2f))
        assertEquals(null, IslandLyricsLogic.cameraGapDp(900f, 1000f, 300f, 500f, 10f, 2f))
        // no hole
        assertEquals(null, IslandLyricsLogic.cameraGapDp(-1f, -1f, 300f, 500f, 10f, 2f))
    }

    @Test
    fun `the sung part runs through the first part and then the second`() {
        assertEquals(0f to 0f, IslandLyricsLogic.splitSweep(0f, 100f, 100f))
        assertEquals(1f to 0f, IslandLyricsLogic.splitSweep(0.5f, 100f, 100f))
        assertEquals(0.5f to 0f, IslandLyricsLogic.splitSweep(0.25f, 100f, 100f))
        val (a, b) = IslandLyricsLogic.splitSweep(0.75f, 100f, 100f)
        assertEquals(1f, a, 0f)
        assertEquals(0.5f, b, 0.001f)
        assertEquals(1f to 1f, IslandLyricsLogic.splitSweep(1f, 100f, 100f))
        // everything in the first part
        assertEquals(0.3f to 0f, IslandLyricsLogic.splitSweep(0.3f, 100f, 0f))
        assertEquals(0f to 0f, IslandLyricsLogic.splitSweep(0.5f, 0f, 0f))
    }

    @Test
    fun `a line with word times is sung word by word`() {
        val entry =
            com.metrolist.music.lyrics.LyricsEntry(
                time = 0L,
                text = "Hello world",
                words =
                    listOf(
                        com.metrolist.music.lyrics.WordTimestamp("Hello", 0.0, 1.0, true),
                        com.metrolist.music.lyrics.WordTimestamp("world", 1.2, 2.0, false),
                    ),
            )
        val lines = IslandLyricsLogic.linesOf(listOf(entry))
        assertEquals(1, lines.size)
        val spans = lines[0].words!!
        assertEquals(2, spans.size)
        assertEquals(0f, spans[0].from, 0f)
        assertEquals(5f / 11f, spans[0].to, 0.001f)
        assertEquals(6f / 11f, spans[1].from, 0.001f)
        assertEquals(1f, spans[1].to, 0.001f)
        // inside the first word: half of it
        assertEquals(5f / 22f, IslandLyricsLogic.sweepAt(lines, 0, 500), 0.001f)
        // between the words it waits at the end of the first
        assertEquals(5f / 11f, IslandLyricsLogic.sweepAt(lines, 0, 1100), 0.001f)
        // inside the second word
        assertEquals(6f / 11f + 5f / 22f, IslandLyricsLogic.sweepAt(lines, 0, 1600), 0.001f)
        assertEquals(1f, IslandLyricsLogic.sweepAt(lines, 0, 9000), 0f)
        assertEquals(0f, IslandLyricsLogic.sweepAt(lines, 0, 0), 0f)
        // one timed word is not "word by word": the whole line runs on the line's times
        val single = IslandLyricsLogic.linesOf(listOf(com.metrolist.music.lyrics.LyricsEntry(0L, "Hi", listOf(com.metrolist.music.lyrics.WordTimestamp("Hi", 0.0, 1.0)))))
        assertTrue(single[0].words == null)
    }

    @Test
    fun `right to left text is found by its first letter`() {
        assertTrue(IslandLyricsLogic.isRtl("مرحبا بالعالم"))
        assertTrue(IslandLyricsLogic.isRtl("... שלום"))
        assertFalse(IslandLyricsLogic.isRtl("Hello"))
        assertFalse(IslandLyricsLogic.isRtl("1234 ..."))
        assertFalse(IslandLyricsLogic.isRtl(""))
    }

    @Test
    fun `the lyric line and page only appear where they fit`() {
        val card = DynamicIslandConfig()
        assertTrue(IslandLyricsLogic.miniLineFits(card))
        assertTrue(IslandLyricsLogic.pageAvailable(card))
        assertFalse("text 130 percent does not fit beside the cover", IslandLyricsLogic.miniLineFits(card.copy(textScale = 130)))
        assertTrue(IslandLyricsLogic.miniLineFits(card.copy(textScale = 130, showArtist = false)))
        assertFalse("off is off", IslandLyricsLogic.pageAvailable(card.copy(showLyrics = false)))
        assertFalse("the page needs the room of the buttons", IslandLyricsLogic.pageAvailable(card.copy(showButtons = false)))
        assertTrue(IslandLyricsLogic.pageAvailable(card.copy(showProgress = false)))
        val row = card.copy(layout = DynamicIslandLayout.COMPACT_ROW)
        assertFalse("the row has no page", IslandLyricsLogic.pageAvailable(row))
    }

    @Test
    fun `the page region is the room of the progress bar and the buttons`() {
        val card = DynamicIslandConfig()
        val full = IslandLyricsLogic.pageRegionHeight(card)
        assertEquals(
            DynamicIslandDimens.expandedHeight(card) -
                DynamicIslandDimens.CARD_PAD_TOP - DynamicIslandDimens.cardCover(card) - DynamicIslandDimens.CARD_PAD_BOTTOM,
            full,
            0.01f,
        )
        assertEquals(0f, IslandLyricsLogic.pageRegionHeight(card.copy(showProgress = false, showButtons = false)), 0f)
    }

    @Test
    fun `the camera gap survives preferences and stays in its range`() {
        val mine = DynamicIslandConfig(lyricCameraGap = true, cameraGapDp = 52)
        assertEquals(mine, mutablePreferencesOf().apply { putDynamicIslandConfig(mine) }.toDynamicIslandConfig())
        assertEquals(36, DynamicIslandConfig().cameraGapDp)
        val wild = mutablePreferencesOf().apply { putDynamicIslandConfig(DynamicIslandConfig(cameraGapDp = 900)) }.toDynamicIslandConfig()
        assertEquals(80, wild.cameraGapDp)
        assertFalse("off by default", DynamicIslandConfig().lyricCameraGap)
    }

    @Test
    fun `the lyric option survives preferences and presets`() {
        val off = DynamicIslandConfig(showLyrics = false)
        val prefs = mutablePreferencesOf().apply { putDynamicIslandConfig(off) }
        assertEquals(off, prefs.toDynamicIslandConfig())
        assertTrue(DynamicIslandConfig().showLyrics)
        DynamicIslandPreset.entries.forEach { preset ->
            val applied = preset.applyTo(off)
            assertFalse("a preset keeps the lyric choice", applied.showLyrics)
            assertTrue(preset.matches(applied))
        }
    }
}
