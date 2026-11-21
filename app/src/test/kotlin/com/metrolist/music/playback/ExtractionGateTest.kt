/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Reports 2 and 4: the player never waits for a stream address without a limit, and a slow extraction is not wasted. */
class ExtractionGateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 0L

    @After
    fun tearDown() = scope.cancel()

    private fun gate(
        waitMs: Long = 200L,
        hardCapMs: Long = 2_000L,
    ) = ExtractionGate<String>(scope, waitMs = waitMs, hardCapMs = hardCapMs, keepMs = 30_000L, clock = { now })

    @Test
    fun `two waiters of the same song share one extraction`() =
        runBlocking {
            val g = gate(waitMs = 1_000L)
            val runs = AtomicInteger()
            val block: suspend () -> Result<String> = {
                runs.incrementAndGet()
                delay(150)
                Result.success("url")
            }
            val a = async { g.await("id", block) }
            val b = async { g.await("id", block) }
            assertEquals("url", a.await()!!.getOrThrow())
            assertEquals("url", b.await()!!.getOrThrow())
            assertEquals(1, runs.get())
        }

    @Test
    fun `the player stops waiting after the limit, the extraction goes on and the next try gets its result`() =
        runBlocking {
            val g = gate(waitMs = 100L)
            val runs = AtomicInteger()
            val block: suspend () -> Result<String> = {
                runs.incrementAndGet()
                delay(300)
                Result.success("late url")
            }
            assertNull(g.await("id", block)) // slow: the caller reports a slow network
            assertTrue(g.isRunning("id"))
            delay(350) // the retry comes a moment later
            assertEquals("late url", g.await("id", block)!!.getOrThrow())
            assertEquals(1, runs.get())
        }

    @Test
    fun `a failure is never handed out twice`() =
        runBlocking {
            val g = gate()
            val runs = AtomicInteger()
            val block: suspend () -> Result<String> = {
                if (runs.incrementAndGet() == 1) Result.failure(IllegalStateException("bot check")) else Result.success("url")
            }
            assertTrue(g.await("id", block)!!.isFailure)
            assertEquals("url", g.await("id", block)!!.getOrThrow())
            assertEquals(2, runs.get())
        }

    @Test
    fun `forget makes the next try resolve again, a kept result expires`() =
        runBlocking {
            val g = gate()
            val runs = AtomicInteger()
            val block: suspend () -> Result<String> = { Result.success("url${runs.incrementAndGet()}") }
            assertEquals("url1", g.await("id", block)!!.getOrThrow())
            assertEquals("url1", g.await("id", block)!!.getOrThrow()) // kept
            g.forget("id")
            assertEquals("url2", g.await("id", block)!!.getOrThrow())
            now += 31_000L
            assertEquals("url3", g.await("id", block)!!.getOrThrow())
        }

    @Test
    fun `finding 27 - a skipped song's extraction is cancelled once nobody waits, current and next keep theirs`() =
        runBlocking {
            val g = gate(waitMs = 50L, hardCapMs = 10_000L)
            val cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            fun slow(id: String): suspend () -> Result<String> =
                {
                    try {
                        delay(5_000)
                        Result.success(id)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        cancelled += id
                        throw e
                    }
                }
            // fast skipping on a weak link: each song's wait times out, the extraction goes on in the background
            for (id in listOf("s1", "s2", "s3", "s4")) assertNull(g.await(id, slow(id)))
            assertEquals(4, g.size())
            // now on s4, next is s5: s1-s3 have nobody waiting and are not wanted
            g.retainOnly(setOf("s4", "s5"))
            delay(100)
            assertEquals(setOf("s1", "s2", "s3"), cancelled.toSet())
            assertTrue(g.isRunning("s4"))
            assertEquals(1, g.size())
        }

    @Test
    fun `finding 27 - a wait in progress keeps its extraction, it is dropped when that wait ends`() =
        runBlocking {
            val g = gate(waitMs = 300L, hardCapMs = 10_000L)
            val waiting = async { g.await("old") { delay(5_000); Result.success("old") } }
            delay(50)
            g.retainOnly(setOf("new"))
            delay(50)
            // the loader still waits for it: not cancelled under its feet
            assertTrue(g.isRunning("old"))
            assertNull(waiting.await()) // the wait ends (time limit) ...
            delay(50)
            assertTrue(!g.isRunning("old")) // ... and with nobody left and the song not wanted, it stops
            assertEquals(0, g.size())
        }

    @Test
    fun `finding 27 - finished entries are swept after the keep time, forget stops an orphan`() =
        runBlocking {
            val g = gate(waitMs = 1_000L)
            for (i in 1..20) assertEquals("u$i", g.await("id$i") { Result.success("u$i") }!!.getOrThrow())
            assertEquals(20, g.size())
            now += 31_000L
            g.await("fresh") { Result.success("f") }
            assertEquals(1, g.size()) // the 20 old results are gone, only the new one stays

            val quick = gate(waitMs = 30L, hardCapMs = 10_000L)
            assertNull(quick.await("orphan") { delay(5_000); Result.success("x") })
            assertTrue(quick.isRunning("orphan"))
            quick.forget("orphan")
            delay(50)
            assertEquals(0, quick.size())
        }

    @Test
    fun `a waiter that joins an extraction cancelled a moment before gets a new one`() =
        runBlocking {
            val g = gate(waitMs = 1_000L, hardCapMs = 10_000L)
            val runs = AtomicInteger()
            val first = async { g.await("id") { runs.incrementAndGet(); delay(200); Result.success("one") } }
            delay(50)
            // a refusal elsewhere forgets the song while this load still waits: its extraction is kept (it has a waiter)
            g.forget("id")
            assertEquals("one", first.await()!!.getOrThrow())
            assertEquals("two", g.await("id") { runs.incrementAndGet(); Result.success("two") }!!.getOrThrow())
            assertEquals(2, runs.get())
        }

    @Test
    fun `an extraction that never ends is cut at the hard cap`() =
        runBlocking {
            val g = gate(waitMs = 1_000L, hardCapMs = 150L)
            val r = g.await("id") {
                delay(10_000)
                Result.success("never")
            }
            assertTrue(r!!.isFailure)
            assertTrue(r.exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException)
            assertTrue(!g.isRunning("id"))
        }
}
