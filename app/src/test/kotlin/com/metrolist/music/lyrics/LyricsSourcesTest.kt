package com.metrolist.music.lyrics

import com.metrolist.music.lyrics.LyricsSources.Answer
import com.metrolist.music.lyrics.LyricsSources.RowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The source picker's state logic, the per song pick store and the timing words. */
class LyricsSourcesTest {
    private val word = LyricsSelection.candidate("A", "[00:10.00]a b\n<a:10.0:10.4|b:10.4:11.0>\n[02:50.00]z", 180)
    private val line = LyricsSelection.candidate("B", "[00:10.00]a\n[02:50.00]z", 180)
    private val plain = LyricsSelection.candidate("C", "words\nmore words", 180)
    private val other = LyricsSelection.candidate("D", "[00:10.00]a\n[03:40.00]z", 180)

    @Test
    fun `every answer reads as one row state`() {
        assertEquals(RowState.IDLE, LyricsSources.rowState(null))
        assertEquals(RowState.IDLE, LyricsSources.rowState(Answer.NotAsked))
        assertEquals(RowState.LOADING, LyricsSources.rowState(Answer.Loading))
        assertEquals(RowState.NOT_FOUND, LyricsSources.rowState(Answer.Missing))
        // could not be reached: a tap asks again
        assertEquals(RowState.IDLE, LyricsSources.rowState(Answer.Failed))
        assertEquals(RowState.WORD, LyricsSources.rowState(Answer.Found(word)))
        assertEquals(RowState.LINE, LyricsSources.rowState(Answer.Found(line)))
        assertEquals(RowState.PLAIN, LyricsSources.rowState(Answer.Found(plain)))
        assertEquals(RowState.OTHER_VERSION, LyricsSources.rowState(Answer.Found(other)))
    }

    @Test
    fun `rows follow the provider order and mark the picked source only when the user picked it`() {
        val state =
            LyricsSources.State()
                .with("A", Answer.Found(word))
                .with("B", Answer.Loading)
                .with("C", Answer.Missing)
        val providers = listOf("C", "A", "B", "D", "YouTube Music")
        val manual = LyricsSources.rows(providers, state, shownProvider = "A", choice = "A") { it == "YouTube Music" }
        assertEquals(providers, manual.map { it.provider })
        assertEquals(listOf(RowState.NOT_FOUND, RowState.WORD, RowState.LOADING, RowState.IDLE, RowState.UNAVAILABLE), manual.map { it.state })
        assertEquals(listOf(false, true, false, false, false), manual.map { it.current })
        // with the automatic choice no source row is "current" (the Automatic row is)
        assertTrue(LyricsSources.rows(providers, state, "A", choice = null) { false }.none { it.current })
        // a pick that is not on screen (it had nothing, the automatic answer is shown) marks no row
        assertTrue(LyricsSources.rows(providers, state, "B", choice = "A") { false }.none { it.current })
    }

    @Test
    fun `only rows that can do something are tappable`() {
        val state = LyricsSources.State().with("A", Answer.Found(word)).with("B", Answer.Loading).with("C", Answer.Missing).with("D", Answer.Found(other))
        val rows =
            LyricsSources.rows(listOf("A", "B", "C", "D", "E", "F", "G"), state.with("G", Answer.Failed), shownProvider = "A", choice = "A") { it == "F" }
                .associateBy { it.provider }
        assertFalse("current", rows.getValue("A").tappable)
        assertFalse("loading", rows.getValue("B").tappable)
        assertFalse("not found", rows.getValue("C").tappable)
        // another version is never applied by a tap on the row (review finding 11), only by its own "Use anyway"
        assertFalse("another version: no tap on the row", rows.getValue("D").tappable)
        assertTrue("another version: Use anyway", rows.getValue("D").canUseAnyway)
        assertFalse(rows.getValue("A").canUseAnyway)
        assertTrue("not asked yet: a tap asks it", rows.getValue("E").tappable)
        assertTrue("could not be reached: a tap asks again", rows.getValue("G").tappable)
        assertFalse("unavailable", rows.getValue("F").tappable)
    }

    @Test
    fun `the automatic row is current without a pick, with a pick that is not shown, never with previous lyrics shown`() {
        assertTrue(LyricsSources.automaticIsCurrent(null, "A", previousShown = false))
        assertFalse(LyricsSources.automaticIsCurrent("A", "A", previousShown = false))
        assertTrue(LyricsSources.automaticIsCurrent("A", "B", previousShown = false))
        assertFalse(LyricsSources.automaticIsCurrent(LyricsSources.PREVIOUS, "LrcLib", previousShown = true))
        assertFalse(LyricsSources.automaticIsCurrent(null, "LrcLib", previousShown = true))
    }

    @Test
    fun `after the race the sources it did not wait for are not asked`() {
        val state = LyricsSources.State().with("A", Answer.Loading).with("B", Answer.Found(line)).settleLoading()
        assertEquals(Answer.NotAsked, state.answers["A"])
        assertEquals(Answer.Found(line), state.answers["B"])
    }

    // ---- the per song pick store ----

    @Test
    fun `picks are kept per song, newest last, and capped`() {
        var map = LyricsSourceChoices.decode("")
        map = LyricsSourceChoices.update(map, "song1", "LrcLib")
        map = LyricsSourceChoices.update(map, "song2", "BiniLyrics")
        map = LyricsSourceChoices.update(map, "song1", "Kugou")
        val text = LyricsSourceChoices.encode(map)
        val back = LyricsSourceChoices.decode(text)
        assertEquals(listOf("song2", "song1"), back.keys.toList())
        assertEquals("Kugou", back["song1"])
        // back to automatic
        assertNull(LyricsSourceChoices.update(back, "song1", null)["song1"])
        // a tab or a new line in an id cannot break the store
        val odd = LyricsSourceChoices.decode(LyricsSourceChoices.encode(LyricsSourceChoices.update(LinkedHashMap(), "a\tb\nc", "X")))
        assertEquals(1, odd.size)
        // the cap drops the oldest
        var big = LinkedHashMap<String, String>()
        repeat(LyricsSourceChoices.MAX + 5) { big = LyricsSourceChoices.update(big, "s$it", "P") }
        assertEquals(LyricsSourceChoices.MAX, big.size)
        assertFalse("s0" in big)
        assertTrue("s${LyricsSourceChoices.MAX + 4}" in big)
    }

    // ---- timing in words ----

    @Test
    fun `earlier and later move the offset the way the view reads it`() {
        // the view adds the offset to the position: a positive offset reaches each line sooner
        assertEquals(100, LyricsTiming.earlier(0, LyricsTiming.SMALL_STEP_MS))
        assertEquals(-500, LyricsTiming.later(0, LyricsTiming.BIG_STEP_MS))
        assertEquals(LyricsTiming.Direction.EARLIER, LyricsTiming.direction(300))
        assertEquals(LyricsTiming.Direction.LATER, LyricsTiming.direction(-300))
        assertEquals(LyricsTiming.Direction.NONE, LyricsTiming.direction(0))
        assertEquals(LyricsTiming.LIMIT_MS, LyricsTiming.earlier(LyricsTiming.LIMIT_MS, 500))
        assertEquals(-LyricsTiming.LIMIT_MS, LyricsTiming.later(-LyricsTiming.LIMIT_MS + 100, 500))
    }

    @Test
    fun `the value reads as seconds without a sign`() {
        assertEquals("0.3", LyricsTiming.seconds(300, Locale.US))
        assertEquals("0,3", LyricsTiming.seconds(-300, Locale("in", "ID")))
        assertEquals("1.25", LyricsTiming.seconds(1250, Locale.US))
        assertEquals("0.0", LyricsTiming.seconds(0, Locale.US))
    }

    @Test
    fun `tapping the line sung now makes it the active line`() {
        // the song is at 61.0 s, the tapped line starts at 63.5 s: the lyrics are 2.5 s late, so they must come earlier
        val offset = LyricsTiming.forTappedLine(lineTimeMs = 63_500, positionMs = 61_000)
        assertEquals(2_600, offset)
        assertTrue(61_000 + offset >= 63_500)
        // and the other way
        assertEquals(-1_900, LyricsTiming.forTappedLine(lineTimeMs = 10_000, positionMs = 12_000))
        assertEquals(LyricsTiming.LIMIT_MS, LyricsTiming.forTappedLine(lineTimeMs = 100_000, positionMs = 0))
    }

    @Test
    fun `the lyrics menu falls back to its own dialog when no lyrics view listens`() {
        assertFalse(LyricsUiEvents.send(LyricsUiEvents.Event.OPEN_TIMING))
        // the classic lyrics view only answers the source picker
        LyricsUiEvents.listen(LyricsUiEvents.Event.OPEN_SOURCES)
        assertFalse(LyricsUiEvents.send(LyricsUiEvents.Event.OPEN_TIMING))
        assertTrue(LyricsUiEvents.send(LyricsUiEvents.Event.OPEN_SOURCES))
        LyricsUiEvents.listen(LyricsUiEvents.Event.OPEN_TIMING)
        assertTrue(LyricsUiEvents.send(LyricsUiEvents.Event.OPEN_TIMING))
        LyricsUiEvents.stopListening(LyricsUiEvents.Event.OPEN_TIMING)
        LyricsUiEvents.stopListening(LyricsUiEvents.Event.OPEN_SOURCES)
        LyricsUiEvents.stopListening(LyricsUiEvents.Event.OPEN_SOURCES)
        assertFalse(LyricsUiEvents.isListened(LyricsUiEvents.Event.OPEN_SOURCES))
    }
}
