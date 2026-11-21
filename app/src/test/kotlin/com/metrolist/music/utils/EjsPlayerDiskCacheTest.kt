/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.StreamResolveException.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Explicit songs stuck at 0:00 (videos C/D): the EJS solver re-read the full YouTube player for every song (18-22 s on the
 * emulator) because the app gave it no place to keep the preprocessed player. Pure JVM.
 */
class EjsPlayerDiskCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun key(n: Int) = n.toString(16).padStart(40, '0')

    @Test
    fun `a preprocessed player is kept and read back, null deletes it`() {
        val dir = tmp.newFolder("ejs")
        assertNull(EjsPlayerDiskCache.read(dir, key(1)))
        EjsPlayerDiskCache.write(dir, key(1), "var player = 1;")
        assertEquals("var player = 1;", EjsPlayerDiskCache.read(dir, key(1)))
        EjsPlayerDiskCache.write(dir, key(1), null)
        assertNull(EjsPlayerDiskCache.read(dir, key(1)))
    }

    @Test
    fun `only the newest players are kept and a bad key is never a path`() {
        val dir = tmp.newFolder("ejs2")
        for (i in 1..5) {
            EjsPlayerDiskCache.write(dir, key(i), "p$i")
            dir.listFiles()!!.forEach { if (it.name.startsWith(key(i))) it.setLastModified(1_000_000L * i) }
        }
        assertEquals(EjsPlayerDiskCache.MAX_FILES, dir.listFiles { f -> f.name.endsWith(".js") }!!.size)
        assertEquals("p5", EjsPlayerDiskCache.read(dir, key(5)))
        EjsPlayerDiskCache.write(dir, "../../evil", "x")
        assertNull(EjsPlayerDiskCache.read(dir, "../../evil"))
        assertTrue(dir.parentFile!!.listFiles()!!.none { it.name == "evil.js" })
    }

    @Test
    fun `an explicit song tries the fast direct clients first, other hints are kept`() {
        assertNull(InnerTubeXPlayer.explicitFirstPass(true))
        assertNull(InnerTubeXPlayer.explicitFirstPass(null))
        assertEquals(false, InnerTubeXPlayer.explicitFirstPass(false))
    }

    @Test
    fun `finding 28 - writers of the same player at once each get their own temporary file and the result is whole`() {
        val dir = tmp.newFolder("ejs3")
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        val start = java.util.concurrent.CountDownLatch(1)
        val values = (0 until 4).map { w -> "var player$w = '" + "x".repeat(200_000) + "';" }
        val jobs =
            values.map { v ->
                pool.submit {
                    start.await()
                    repeat(10) { EjsPlayerDiskCache.write(dir, key(7), v) }
                }
            }
        start.countDown()
        jobs.forEach { it.get(60, java.util.concurrent.TimeUnit.SECONDS) }
        pool.shutdown()
        // the stored player is exactly one writer's value, never a mix, and no temporary file is left
        assertTrue(EjsPlayerDiskCache.read(dir, key(7)) in values)
        assertEquals(listOf("${key(7)}.js"), dir.list()!!.toList())
    }

    @Test
    fun `finding 28 - the trim never deletes a temporary file that is being written, only leftovers`() {
        val dir = tmp.newFolder("ejs4")
        val inProgress = java.io.File(dir, "${key(8)}.123.tmp").apply { writeText("half") }
        val leftover = java.io.File(dir, "${key(9)}.456.tmp").apply { writeText("old") }
        val now = System.currentTimeMillis()
        leftover.setLastModified(now - EjsPlayerDiskCache.STALE_TEMP_MS - 10_000L)
        EjsPlayerDiskCache.write(dir, key(1), "p1")
        EjsPlayerDiskCache.trim(dir, nowMs = now)
        assertTrue(inProgress.exists())
        assertTrue(!leftover.exists())
        assertEquals("p1", EjsPlayerDiskCache.read(dir, key(1)))
    }

    private fun explicitFailure(reason: Reason) = StreamResolveException(reason = reason, message = "x")

    @Test
    fun `finding 22 - an explicit song no client can play ends as before (unsupported, skipped at once)`() {
        val mapped = InnerTubeXPlayer.explicitFinalFailure(true, explicitFailure(Reason.NO_PLAYABLE_STREAM))
        assertEquals(Reason.EXPLICIT_UNSUPPORTED, mapped.reason)
        // what the service does with it: skip at once, as 4.3.3 did, not three fresh extractions
        assertEquals(
            com.metrolist.music.playback.PlaybackFailureKind.SONG_UNAVAILABLE,
            com.metrolist.music.playback.PlaybackFailures.classify(mapped),
        )
        // a normal song keeps "another client may work"
        val normal = InnerTubeXPlayer.explicitFinalFailure(null, explicitFailure(Reason.NO_PLAYABLE_STREAM))
        assertEquals(Reason.NO_PLAYABLE_STREAM, normal.reason)
        assertEquals(com.metrolist.music.playback.PlaybackFailureKind.STREAM_REJECTED, com.metrolist.music.playback.PlaybackFailures.classify(normal))
        // network, age gate and unavailable keep their own handling, also for explicit songs
        for (r in listOf(Reason.NETWORK, Reason.AGE_RESTRICTED, Reason.UNAVAILABLE, Reason.UNKNOWN)) {
            assertEquals(r, InnerTubeXPlayer.explicitFinalFailure(true, explicitFailure(r)).reason)
        }
    }

    @Test
    fun `finding 22 - the extractor is built without a client health monitor (explicit refusals cannot demote a client)`() {
        val source =
            listOf("src/main/kotlin", "app/src/main/kotlin")
                .map { java.io.File(it, "com/metrolist/music/utils/InnerTubeXPlayer.kt") }
                .first { it.isFile }
                .readText()
        val build = source.substring(source.indexOf("InnerTubeExtractor("), source.indexOf("ExtractionBundle(latestTransport.generation"))
        assertTrue("a ClientHealthMonitor would score first-pass refusals of explicit songs as NORMAL content", !build.contains("clientHealthMonitor"))
        // the explicit first pass also keeps the kids fallback off, like the explicit path
        assertTrue(source.contains("isKidsContent = if (contentHints.isExplicit == true)"))
        // the failure of the extraction goes through the explicit mapping (finalFailure -> explicitFinalFailure)
        assertTrue(source.contains("finalFailure(contentHints.isExplicit, failure)"))
        assertTrue(source.contains("explicitFinalFailure(isExplicit, raw)"))
    }
}
