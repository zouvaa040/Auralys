/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.NotSerializableException
import java.io.Serializable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Review 2026-11-12, finding 3: the saved queue (up to 5 000 songs) was rewritten in place, and a kill during the write
 * left a broken file that the next start deleted together with the cursor and the player state. Pure JVM.
 */
class PersistFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private data class Saved(
        val title: String,
        val items: List<String>,
    ) : Serializable

    private class NotSaveable(
        val x: Any = Any(),
    ) : Serializable

    private fun read(file: File) = PersistFiles.read(file) { it as Saved }

    @Test
    fun `a written object reads back, a missing file is missing`() {
        val file = File(tmp.root, "persistent_queue.data")
        assertEquals(PersistFiles.ReadResult.Missing, read(file))
        val value = Saved("q", (1..5_000).map { "id$it" })
        PersistFiles.write(file, value)
        assertEquals(PersistFiles.ReadResult.Ok(value), read(file))
        // no temporary file is left behind
        assertEquals(listOf("persistent_queue.data"), tmp.root.list()!!.toList())
    }

    @Test
    fun `a write that fails half way leaves the old file whole and no temporary file`() {
        val file = File(tmp.root, "persistent_queue.data")
        val old = Saved("old", listOf("a", "b"))
        PersistFiles.write(file, old)
        try {
            // fails inside writeObject, after some bytes went out: the in-place write of 4.3.4 broke the file here
            PersistFiles.write(file, listOf("x", NotSaveable()))
            fail("the write should have failed")
        } catch (_: NotSerializableException) {
        }
        assertEquals(PersistFiles.ReadResult.Ok(old), read(file))
        assertEquals(listOf("persistent_queue.data"), tmp.root.list()!!.toList())
    }

    @Test
    fun `a killed write (a temporary file cut short) never touches the saved file and is swept later`() {
        val file = File(tmp.root, "persistent_queue.data")
        val old = Saved("old", listOf("a"))
        PersistFiles.write(file, old)
        // what a kill in the middle of a write leaves: a partial temporary file next to the real one
        val partial = File(tmp.root, "persistent_queue.data.123456.tmp").apply { writeBytes(ByteArray(40) { 0x77 }) }
        assertEquals(PersistFiles.ReadResult.Ok(old), read(file))

        val names = listOf("persistent_queue.data", "persistent_queue_cursor.data")
        val other = File(tmp.root, "someone_else.data.1.tmp").apply { writeText("x") }
        val now = System.currentTimeMillis()
        // a fresh one may belong to a write in progress: kept
        PersistFiles.sweepTemps(tmp.root, names, nowMs = now)
        assertTrue(partial.exists())
        // a minute later it is a leftover; files of other names are never touched
        PersistFiles.sweepTemps(tmp.root, names, nowMs = now + PersistFiles.STALE_TEMP_MS + 5_000L)
        assertTrue(!partial.exists())
        assertTrue(other.exists())
    }

    @Test
    fun `a broken or foreign file is reported as broken, not thrown`() {
        val file = File(tmp.root, "persistent_queue_cursor.data")
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        assertTrue(read(file) is PersistFiles.ReadResult.Broken)
        // another class in the file (an old version): broken too, the cast error does not escape
        PersistFiles.write(file, arrayListOf("not a Saved"))
        assertTrue(read(file) is PersistFiles.ReadResult.Broken)
    }

    @Test
    fun `a write never uses or removes the temporary file of another write in progress`() {
        val file = File(tmp.root, "persistent_automix.data")
        // another writer's file, half written, under the name pattern a write uses
        val busy = File(tmp.root, "persistent_automix.data.999.tmp").apply { writeBytes(byteArrayOf(9, 9, 9)) }
        val value = Saved("automix", listOf("x", "y"))
        PersistFiles.write(file, value)
        PersistFiles.write(file, value.copy(title = "again"))
        assertEquals(PersistFiles.ReadResult.Ok(value.copy(title = "again")), read(file))
        assertTrue(busy.exists())
        assertEquals(3, busy.length())
    }

    @Test
    fun `writers that take turns (the service's lock) always leave one whole value`() {
        val file = File(tmp.root, "persistent_queue.data")
        val lock = Any()
        val pool = Executors.newFixedThreadPool(3)
        val start = CountDownLatch(1)
        val values = (0 until 3).map { w -> Saved("w$w", (0 until 2_000).map { "w$w-$it" }) }
        val futures =
            values.map { v ->
                pool.submit {
                    start.await()
                    repeat(10) { synchronized(lock) { PersistFiles.write(file, v, sync = false) } }
                }
            }
        start.countDown()
        futures.forEach { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        val result = read(file)
        assertTrue("got $result", result is PersistFiles.ReadResult.Ok && result.value in values)
        assertEquals(listOf("persistent_queue.data"), tmp.root.list()!!.toList())
    }
}
