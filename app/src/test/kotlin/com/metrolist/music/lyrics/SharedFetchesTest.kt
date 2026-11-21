/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SharedFetchesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun `fetch is cancelled when the only caller is cancelled`() =
        runBlocking {
            val fetches = SharedFetches<String>(scope)
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val caller =
                async(Dispatchers.Default) {
                    fetches.await("a") {
                        try {
                            started.complete(Unit)
                            delay(60_000)
                            "late"
                        } catch (e: CancellationException) {
                            cancelled.complete(Unit)
                            throw e
                        }
                    }
                }
            started.await()
            caller.cancel()
            withTimeout(5_000) { cancelled.await() }
            assertEquals(0, fetches.runningCount())
        }

    @Test
    fun `fetch survives while another caller still waits and runs once`() =
        runBlocking {
            val fetches = SharedFetches<String>(scope)
            val runs = AtomicInteger()
            val release = CompletableDeferred<Unit>()
            val first = async(Dispatchers.Default) { fetches.await("a") { runs.incrementAndGet(); release.await(); "ok" } }
            while (runs.get() == 0) delay(5)
            val second = async(Dispatchers.Default) { fetches.await("a") { runs.incrementAndGet(); "other" } }
            delay(50)
            first.cancel()
            delay(50)
            assertEquals(1, fetches.runningCount())
            release.complete(Unit)
            assertEquals("ok", withTimeout(5_000) { second.await() })
            assertEquals(1, runs.get())
            assertTrue(fetches.runningCount() == 0)
        }

    @Test
    fun `a new caller after a cancelled fetch starts a fresh one`() =
        runBlocking {
            val fetches = SharedFetches<String>(scope)
            val started = CompletableDeferred<Unit>()
            val caller = async(Dispatchers.Default) { fetches.await("a") { started.complete(Unit); delay(60_000); "x" } }
            started.await()
            caller.cancel()
            caller.join()
            assertEquals("fresh", withTimeout(5_000) { fetches.await("a") { "fresh" } })
        }
}
