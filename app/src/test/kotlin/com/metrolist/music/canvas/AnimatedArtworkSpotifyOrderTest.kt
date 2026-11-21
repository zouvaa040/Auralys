/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.canvas

import android.content.Context
import com.metrolist.music.canvas.AnimatedArtworkRepository.Outcome
import com.metrolist.music.spotify.SpotifyCanvasResult
import com.metrolist.music.spotify.SpotifyCanvasSource
import com.metrolist.music.spotify.SpotifySession
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Provider order and cache behaviour of the animated covers: Spotify Canvas first (connected + switch on), then Apple Music;
 * a miss, a failure, a time-out or no token falls through; both providers are cached per their own key. The network of both
 * providers is replaced by fakes (the repository's test hooks); nothing here talks to Spotify or Apple.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class AnimatedArtworkSpotifyOrderTest {
    private val repo = AnimatedArtworkRepository
    private lateinit var context: Context

    private val spotifyUrl = "https://canvaz.scdn.co/upload/artist/clip.cnvs.mp4"
    private val appleUrl = "https://apple.example/motion/album.mp4"

    private val appleCalls = mutableListOf<String>()
    private val spotifyCalls = mutableListOf<String>()
    private var appleOutcome: Outcome = Outcome.Found(appleUrl)
    private var spotifyAnswer: suspend (title: String) -> SpotifyCanvasResult = { SpotifyCanvasResult.Found(spotifyUrl) }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.cacheDir, "animated_artwork").deleteRecursively()
        repo.resetForTest()
        SpotifySession.setConnected(false)
        repo.testHooks.apply {
            spotifyEnabled = { true }
            dataSaver = { false }
            apple = { title, _, _ ->
                appleCalls += title
                appleOutcome
            }
            spotifySource = SpotifyCanvasSource { title, _, _, _, _ ->
                spotifyCalls += title
                spotifyAnswer(title)
            }
        }
    }

    @After
    fun tearDown() {
        repo.testHooks.apply {
            spotifyEnabled = null
            dataSaver = null
            apple = null
            spotifySource = null
            download = null
            spotifyBudgetMs = null
            spotifyMintRunning = null
        }
        repo.resetForTest()
        SpotifySession.setConnected(false)
    }

    private fun url(title: String = "Let Down", artist: String = "Radiohead", album: String? = "OK Computer") =
        runBlocking { repo.findVideoUrl(context, title, artist, album, durationSec = 299) }

    private fun index(): JSONObject = JSONObject(File(context.cacheDir, "animated_artwork/index.json").readText())

    // Order

    @Test
    fun `Spotify is asked first and a hit ends it, Apple is not asked`() {
        assertEquals(spotifyUrl, url())
        assertEquals(1, spotifyCalls.size)
        assertTrue(appleCalls.isEmpty())
    }

    @Test
    fun `a Spotify hit wins even when Apple already has this album cached`() {
        repo.testHooks.spotifyEnabled = { false }
        assertEquals(appleUrl, url())
        repo.testHooks.spotifyEnabled = { true }
        assertEquals(spotifyUrl, url())
        assertEquals(1, appleCalls.size)
    }

    @Test
    fun `a Spotify miss falls through to Apple`() {
        spotifyAnswer = { SpotifyCanvasResult.Missing }
        assertEquals(appleUrl, url())
        assertEquals(1, spotifyCalls.size)
        assertEquals(1, appleCalls.size)
    }

    @Test
    fun `a Spotify failure falls through to Apple`() {
        spotifyAnswer = { SpotifyCanvasResult.Failed }
        assertEquals(appleUrl, url())
        assertEquals(1, appleCalls.size)
    }

    @Test
    fun `a Spotify call that throws falls through to Apple`() {
        spotifyAnswer = { error("boom") }
        assertEquals(appleUrl, url())
        assertEquals(1, appleCalls.size)
    }

    @Test
    fun `no Spotify token falls through to Apple at once and remembers nothing about the song`() {
        spotifyAnswer = { SpotifyCanvasResult.Unavailable }
        assertEquals(appleUrl, url())
        assertTrue(index().keys().asSequence().none { it.startsWith("spotify:") })
    }

    @Test
    fun `a Spotify call that outlives its budget is cut and Apple answers, then Spotify is left alone for a while`() {
        repo.testHooks.spotifyBudgetMs = 150
        spotifyAnswer = {
            delay(30_000)
            SpotifyCanvasResult.Found(spotifyUrl)
        }
        val started = System.currentTimeMillis()
        assertEquals(appleUrl, url())
        assertTrue("took ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 5_000)
        assertEquals(1, spotifyCalls.size)
        // the next song does not wait for Spotify again
        assertEquals(appleUrl, url(title = "Another Song", album = "Another Album"))
        assertEquals(1, spotifyCalls.size)
    }

    @Test
    fun `the budget is six seconds`() {
        assertEquals(6_000L, AnimatedArtworkRepository.SPOTIFY_BUDGET_MS)
    }

    // The token mint (a WebView loading the web player) outlives the budget

    @Test
    fun `while a token is still being minted Spotify is skipped at once, Apple answers, and nothing is remembered about the song`() {
        repo.testHooks.spotifyMintRunning = { true }
        val started = System.currentTimeMillis()
        assertEquals(appleUrl, url())
        assertTrue("took ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 3_000)
        assertTrue("Spotify was not even asked", spotifyCalls.isEmpty())
        assertTrue(index().keys().asSequence().none { it.startsWith("spotify:") })
    }

    @Test
    fun `a budget that ends while the mint runs falls through to Apple, and the next song finds the warm token at once`() {
        repo.testHooks.spotifyBudgetMs = 150
        val minting = AtomicBoolean(false)
        repo.testHooks.spotifyMintRunning = { minting.get() }
        // the first song: the lookup waits for the token, the mint keeps running, the budget ends
        spotifyAnswer = {
            minting.set(true)
            delay(30_000)
            SpotifyCanvasResult.Found(spotifyUrl)
        }
        assertEquals(appleUrl, url())
        assertEquals(1, spotifyCalls.size)
        // the mint finishes in the background: the token is warm
        minting.set(false)
        spotifyAnswer = { SpotifyCanvasResult.Found(spotifyUrl) }
        // Spotify is NOT paused for a minute: the next song gets its canvas right away
        assertEquals(spotifyUrl, url(title = "Another Song", album = "Another Album"))
        // and the song that ran out of time was not held against it either
        assertEquals(spotifyUrl, url())
        assertEquals(3, spotifyCalls.size)
    }

    @Test
    fun `songs played while the mint still runs do not each wait for the budget`() {
        repo.testHooks.spotifyBudgetMs = 150
        val minting = AtomicBoolean(false)
        repo.testHooks.spotifyMintRunning = { minting.get() }
        spotifyAnswer = {
            minting.set(true)
            delay(30_000)
            SpotifyCanvasResult.Found(spotifyUrl)
        }
        assertEquals(appleUrl, url())
        val started = System.currentTimeMillis()
        // the mint is still going: these three skip Spotify without waiting
        repeat(3) { n -> assertEquals(appleUrl, url(title = "Song $n", album = "Album $n")) }
        assertTrue("took ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 100 * 3 + 1_000)
        assertEquals(1, spotifyCalls.size)
    }

    // Caches

    @Test
    fun `a cached Spotify miss goes straight to Apple, and Apple's cached hit is used instantly after it`() {
        spotifyAnswer = { SpotifyCanvasResult.Missing }
        assertEquals(appleUrl, url())
        assertEquals(appleUrl, url())
        assertEquals(appleUrl, url())
        assertEquals(1, spotifyCalls.size)
        assertEquals(1, appleCalls.size)
        // the miss is on disk with its own key, as a remembered null
        val key = index().keys().asSequence().single { it.startsWith("spotify:") }
        assertTrue(index().getJSONObject(key).isNull("u"))
    }

    @Test
    fun `a Spotify hit is cached per track, in memory and on disk`() {
        assertEquals(spotifyUrl, url())
        assertEquals(spotifyUrl, url())
        assertEquals(1, spotifyCalls.size)
        val key = index().keys().asSequence().single { it.startsWith("spotify:") }
        assertEquals(spotifyUrl, index().getJSONObject(key).getString("u"))
        // a restart: memory is empty, the index on disk answers
        repo.resetForTest()
        assertEquals(spotifyUrl, url())
        assertEquals(1, spotifyCalls.size)
    }

    @Test
    fun `canvases are per track while Apple's answer is per album`() {
        spotifyAnswer = { title -> if (title == "Let Down") SpotifyCanvasResult.Found(spotifyUrl) else SpotifyCanvasResult.Missing }
        assertEquals(spotifyUrl, url("Let Down"))
        assertEquals(appleUrl, url("Paranoid Android"))
        assertEquals(appleUrl, url("Karma Police"))
        // each song asked Spotify once; Apple was asked once for the whole album
        assertEquals(listOf("Let Down", "Paranoid Android", "Karma Police"), spotifyCalls)
        assertEquals(1, appleCalls.size)
        assertEquals(spotifyUrl, url("Let Down"))
        assertEquals(3, spotifyCalls.size)
    }

    @Test
    fun `a take of the song has its own key`() {
        spotifyAnswer = { title -> if (title.contains("Acoustic")) SpotifyCanvasResult.Found(spotifyUrl) else SpotifyCanvasResult.Missing }
        assertEquals(appleUrl, url("Let Down"))
        assertEquals(spotifyUrl, url("Let Down (Acoustic)"))
        assertEquals(2, spotifyCalls.size)
    }

    @Test
    fun `a failure is remembered for the back-off, not for good`() {
        spotifyAnswer = { SpotifyCanvasResult.Failed }
        assertEquals(appleUrl, url())
        assertEquals(appleUrl, url())
        assertEquals(1, spotifyCalls.size)
        assertTrue(index().keys().asSequence().none { it.startsWith("spotify:") })
    }

    @Test
    fun `with data saver on Spotify is not asked, but what is cached is still used`() {
        assertEquals(spotifyUrl, url())
        repo.testHooks.dataSaver = { true }
        assertEquals(spotifyUrl, url())
        assertEquals(1, spotifyCalls.size)
        assertEquals(appleUrl, url("Another Song", album = "Another Album"))
        assertEquals(1, spotifyCalls.size)
    }

    @Test
    fun `a song that cannot be identified is not looked up on Spotify`() {
        assertEquals(appleUrl, url(title = "Song", artist = ""))
        assertTrue(spotifyCalls.isEmpty())
    }

    // Not connected / switched off: the way it always was

    @Test
    fun `switched off, Spotify is never asked and only Apple's key is written`() {
        repo.testHooks.spotifyEnabled = { false }
        assertEquals(appleUrl, url())
        assertTrue(spotifyCalls.isEmpty())
        assertEquals(setOf("radiohead|a:ok computer"), index().keys().asSequence().toSet())
    }

    @Test
    fun `signed out (the real gate, no hook), Spotify is never asked`() {
        repo.testHooks.spotifyEnabled = null
        SpotifySession.setConnected(false)
        assertEquals(appleUrl, url())
        assertTrue(spotifyCalls.isEmpty())
        assertEquals(setOf("radiohead|a:ok computer"), index().keys().asSequence().toSet())
    }

    @Test
    fun `connected (the real gate, no hook), the switch defaults to on`() {
        repo.testHooks.spotifyEnabled = null
        SpotifySession.setConnected(true)
        assertEquals(spotifyUrl, url())
        assertEquals(1, spotifyCalls.size)
    }

    // The file

    private fun fileFor(name: String) = File(context.cacheDir, name).also { it.writeText("video") }

    @Test
    fun `videoFile downloads Spotify's mp4 when there is one`() {
        val downloaded = mutableListOf<String>()
        repo.testHooks.download = { u ->
            downloaded += u
            fileFor("spotify.mp4")
        }
        val file = runBlocking { repo.videoFile(context, "Let Down", "Radiohead", "OK Computer", 299, false) }
        assertNotNull(file)
        assertEquals(listOf(spotifyUrl), downloaded)
    }

    @Test
    fun `when Spotify's file will not download, Apple's is used and Spotify's answer is dropped`() {
        val downloaded = mutableListOf<String>()
        repo.testHooks.download = { u ->
            downloaded += u
            if (u == spotifyUrl) null else fileFor("apple.mp4")
        }
        val file = runBlocking { repo.videoFile(context, "Let Down", "Radiohead", "OK Computer", 299, false) }
        assertEquals("apple.mp4", file?.name)
        assertEquals(listOf(spotifyUrl, appleUrl), downloaded)
        // the dead answer is not served again for a while
        downloaded.clear()
        val again = runBlocking { repo.videoFile(context, "Let Down", "Radiohead", "OK Computer", 299, false) }
        assertEquals("apple.mp4", again?.name)
        assertEquals(listOf(appleUrl), downloaded)
        assertEquals(1, spotifyCalls.size)
    }

    @Test
    fun `without Spotify videoFile is exactly the Apple path`() {
        repo.testHooks.spotifyEnabled = { false }
        val downloaded = mutableListOf<String>()
        repo.testHooks.download = { u ->
            downloaded += u
            fileFor("apple.mp4")
        }
        assertEquals("apple.mp4", runBlocking { repo.videoFile(context, "Let Down", "Radiohead", "OK Computer") }?.name)
        assertEquals(listOf(appleUrl), downloaded)
        // an Apple file that will not download is not retried through anything else
        downloaded.clear()
        repo.testHooks.download = { u ->
            downloaded += u
            null
        }
        assertNull(runBlocking { repo.videoFile(context, "Let Down", "Radiohead", "OK Computer") })
        assertEquals(listOf(appleUrl), downloaded)
        assertFalse(spotifyCalls.isNotEmpty())
    }
}
