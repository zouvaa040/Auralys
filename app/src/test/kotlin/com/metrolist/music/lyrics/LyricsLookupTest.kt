package com.metrolist.music.lyrics

import com.metrolist.music.lyrics.LyricsSources.Answer
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Review 2026-11-12, findings 1, 2, 5, 11 and the timing bar echo: what counts as "could not ask", what a race leaves
 * for the picker, what a tap in the picker applies.
 */
class LyricsLookupTest {
    private val request = LyricsRequest.of("id", "Song", listOf("Artist"), 180, null)
    private val fits = "[00:10.00]a\n[02:50.00]z"

    @Test
    fun `not found only when most sources really answered`() {
        val found = Answer.Found(LyricsSelection.candidate("A", "[00:10.00]a\n[04:50.00]z", 180))
        assertEquals(LyricsLookup.NotFound, LyricsLookup.withoutLyrics(listOf(Answer.Missing, Answer.Missing, Answer.Failed)))
        assertEquals(LyricsLookup.NotFound, LyricsLookup.withoutLyrics(listOf(found, Answer.Missing, Answer.NotAsked, Answer.Failed)))
        // offline / DNS: most sources could not be reached
        assertEquals(LyricsLookup.NoConnection, LyricsLookup.withoutLyrics(listOf(Answer.Failed, Answer.Failed, Answer.Failed, Answer.Missing)))
        assertEquals(LyricsLookup.NoConnection, LyricsLookup.withoutLyrics(listOf(Answer.Failed, Answer.NotAsked, null)))
        // nothing to ask (every source switched off): a real "not found"
        assertEquals(LyricsLookup.NotFound, LyricsLookup.withoutLyrics(emptyList()))
    }

    @Test
    fun `network failures are told apart from misses`() {
        assertTrue(LyricsLookup.isNetworkFailure(UnknownHostException("lrclib.net")))
        assertTrue(LyricsLookup.isNetworkFailure(SocketTimeoutException()))
        assertTrue(LyricsLookup.isNetworkFailure(SSLHandshakeException("blocked")))
        assertTrue(LyricsLookup.isNetworkFailure(IllegalStateException("wrapped", IOException("reset"))))
        assertTrue(LyricsLookup.isNetworkFailure(LyricsHttp.ServerBusy(503)))
        assertTrue(runBlocking { runCatching { withTimeout(1) { delay(100) } }.exceptionOrNull() } is TimeoutCancellationException)
        assertTrue(LyricsLookup.isNetworkFailure(runBlocking { runCatching { withTimeout(1) { delay(100) } }.exceptionOrNull()!! }))
        assertFalse(LyricsLookup.isNetworkFailure(IllegalStateException("No matching track")))
        assertFalse(LyricsLookup.isNetworkFailure(IllegalArgumentException("bad json")))
    }

    private suspend fun ask(
        name: String,
        log: MutableList<Answer>,
        fetch: suspend () -> Result<LyricsHit>,
    ): Answer = LyricsSources.askOnce(name, request, { synchronized(log) { log += it } }, fetch)

    @Test
    fun `an ask reads the provider's result`() =
        runBlocking {
            val log = mutableListOf<Answer>()
            assertTrue(ask("A", log) { Result.success(LyricsHit(fits)) } is Answer.Found)
            assertEquals(Answer.Missing, ask("B", log) { Result.failure(IllegalStateException("No matching track")) })
            assertEquals(Answer.Failed, ask("C", log) { Result.failure(UnknownHostException("offline")) })
            assertEquals(Answer.Failed, ask("D", log) { throw SocketTimeoutException() })
            assertEquals(Answer.Missing, ask("E", log) { Result.success(LyricsHit("  ")) })
            // a timeout of the provider's own while the ask is still wanted
            assertEquals(Answer.Failed, ask("F", log) { withTimeout(1) { delay(100) }; Result.success(LyricsHit(fits)) })
            assertEquals(Answer.Loading, log.first())
        }

    @Test
    fun `sources the race stops waiting for are not asked, even when they swallow the cancellation`() =
        runBlocking {
            val answers = HashMap<String, Answer>()
            fun entry(
                name: String,
                fetch: suspend () -> Result<LyricsHit>,
            ) = RaceEntry(name) {
                (LyricsSources.askOnce(name, request, { synchronized(answers) { answers[name] = it } }, fetch) as? Answer.Found)?.candidate
            }
            val word = "[00:10.00]a b\n<a:10.0:10.4|b:10.4:11.0>\n[02:50.00]z"
            val best =
                LyricsSelection.race(
                    listOf(
                        entry("Quick") { Result.success(LyricsHit(word)) },
                        // the providers that wrap everything in runCatching (LrcLib, KuGou, BetterLyrics...)
                        entry("Swallows") { runCatching { delay(5_000); LyricsHit(fits) } },
                        entry("Plain") { delay(5_000); Result.success(LyricsHit(fits)) },
                    ),
                    perProviderMs = 7_000,
                    totalMs = 10_000,
                )
            assertEquals("Quick", best?.provider)
            assertTrue(answers["Quick"] is Answer.Found)
            // before: "Swallows" was recorded "not found" and could not be tapped in the picker
            assertEquals(Answer.NotAsked, answers["Swallows"])
            assertEquals(Answer.NotAsked, answers["Plain"])
            assertEquals(LyricsSources.RowState.IDLE, LyricsSources.rowState(answers["Swallows"]))
        }

    @Test
    fun `a tap applies usable lyrics, another version only with use anyway`() {
        val usable = Answer.Found(LyricsSelection.candidate("A", fits, 180))
        val other = Answer.Found(LyricsSelection.candidate("B", "[00:10.00]a\n[03:40.00]z", 180))
        assertEquals(LyricsHelper.PickResult.APPLIED, LyricsHelper.decide(usable, useAnyway = false))
        assertEquals(LyricsHelper.PickResult.OTHER_VERSION, LyricsHelper.decide(other, useAnyway = false))
        assertEquals(LyricsHelper.PickResult.APPLIED, LyricsHelper.decide(other, useAnyway = true))
        assertEquals(LyricsHelper.PickResult.NOT_FOUND, LyricsHelper.decide(Answer.Missing, useAnyway = true))
        assertEquals(LyricsHelper.PickResult.NO_CONNECTION, LyricsHelper.decide(Answer.Failed, useAnyway = false))
        assertEquals(LyricsHelper.PickResult.NO_CONNECTION, LyricsHelper.decide(Answer.NotAsked, useAnyway = false))
    }

    @Test
    fun `the timing bar keeps quick taps and takes outside changes`() {
        // the echo of an earlier tap comes back while the user is still tapping: the bar keeps its own value
        assertEquals(300, LyricsTiming.shownAfterSave(shown = 300, saved = 100, msSinceLastTap = 200))
        // the bar was just opened, or another screen changed the value
        assertEquals(-500, LyricsTiming.shownAfterSave(shown = 0, saved = -500, msSinceLastTap = 60_000))
        assertEquals(-500, LyricsTiming.shownAfterSave(shown = 0, saved = -500, msSinceLastTap = LyricsTiming.ECHO_WINDOW_MS))
    }
}
