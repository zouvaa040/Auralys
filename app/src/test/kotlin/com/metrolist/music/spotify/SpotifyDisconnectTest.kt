package com.metrolist.music.spotify

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The sign-out: every step, in order, in the account's own scope, even when the page that asked is gone. */
class SpotifyDisconnectTest {
    private val accountScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = java.util.Collections.synchronizedList(ArrayList<String>())

    @After
    fun tearDown() {
        accountScope.cancel()
    }

    private fun steps(
        beforeStore: () -> Unit = {},
        fail: Set<String> = emptySet(),
    ): SpotifyDisconnectSteps {
        fun step(name: String): () -> Unit = {
            log += name
            if (name in fail) error("$name failed")
        }
        return SpotifyDisconnectSteps(
            clearStore = {
                beforeStore()
                step("store").invoke()
            },
            markDisconnected = step("connected=false"),
            dropMemory = step("memory"),
            clearWebSession = step("webview"),
            deleteKey = step("keystore"),
        )
    }

    private fun disconnector(steps: SpotifyDisconnectSteps) =
        SpotifyDisconnector(accountScope, steps, io = Dispatchers.Unconfined, main = Dispatchers.Unconfined)

    private val inOrder = listOf("store", "connected=false", "memory", "webview", "keystore")

    @Test
    fun `every step runs once, in the order the encrypted cookie goes first and the key last`() = runBlocking {
        disconnector(steps()).start().join()
        assertEquals(inOrder, log.toList())
    }

    @Test
    fun `a step that fails does not keep the later ones from running`() = runBlocking {
        disconnector(steps(fail = setOf("store", "webview"))).start().join()
        assertEquals(inOrder, log.toList())
    }

    @Test
    fun `leaving the page that asked does not stop it half way`() = runBlocking {
        val inStore = CountDownLatch(1)
        val release = CountDownLatch(1)
        val d =
            disconnector(
                steps(
                    beforeStore = {
                        inStore.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    },
                ),
            )
        // the screen's scope: "Putuskan" is tapped and the page is left at once
        val screen = CoroutineScope(Job())
        lateinit var started: Job
        screen.launch { started = d.start() }.join()
        assertTrue(inStore.await(5, TimeUnit.SECONDS))
        screen.cancel()
        // the first step is still busy, the rest has not run: and the page is already gone
        assertTrue(log.isEmpty())
        release.countDown()
        started.join()
        assertEquals(inOrder, log.toList())
    }

    @Test
    fun `a second tap while it runs joins the run instead of starting another`() = runBlocking {
        val inStore = CountDownLatch(1)
        val release = CountDownLatch(1)
        val d =
            disconnector(
                steps(
                    beforeStore = {
                        inStore.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    },
                ),
            )
        val first = d.start()
        assertTrue(inStore.await(5, TimeUnit.SECONDS))
        assertSame(first, d.start())
        release.countDown()
        first.join()
        assertEquals(inOrder, log.toList())
    }

    @Test
    fun `a sign-out after a finished one runs again`() = runBlocking {
        val d = disconnector(steps())
        d.start().join()
        d.start().join()
        assertEquals(inOrder + inOrder, log.toList())
    }

    @Test
    fun `the web view part clears Spotify's cookies and web storage on all of its hosts`() {
        assertTrue(SPOTIFY_WEB_ORIGINS.containsAll(listOf("https://open.spotify.com", "https://accounts.spotify.com", "https://www.spotify.com", "https://spotify.com")))
        assertTrue(SPOTIFY_WEB_ORIGINS.all { it.startsWith("https://") && (it.endsWith("spotify.com")) })
    }
}
