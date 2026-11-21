/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 4.3.4, reports 1, 2 and 6. MusicService cannot be created in a unit test, so this pins the rules in its source:
 *
 *  - onCreate never leaves before the player is built (a refused foreground start left an instance without a player that
 *    the app bound to; every tap then waited for ever, "harus force stop dulu");
 *  - the notification update that runs from the main loop when a cover finishes loading is guarded, and
 *    startForegroundService is overridden, so a refused foreground start in the background does not crash (vivo report);
 *  - a refused foreground start never pauses playback the user wants;
 *  - only the app's own intent gets the MusicBinder;
 *  - the stream resolver waits for an extraction through the gate (with a limit), never directly;
 *  - a stream of a bounded-range client is not cut to one chunk any more.
 */
class MusicServiceReliabilityGuardTest {
    private fun read(name: String): String =
        listOf("src/main/kotlin", "app/src/main/kotlin")
            .map { File(it, "com/metrolist/music/playback/$name") }
            .firstOrNull { it.isFile }
            ?.readText()
            ?.replace("\r\n", "\n")
            ?: error("$name not found from ${File(".").absolutePath}")

    private val source by lazy { read("MusicService.kt") }

    private fun between(
        start: String,
        end: String,
    ): String {
        val a = source.indexOf(start)
        assertTrue("'$start' not found", a >= 0)
        val b = source.indexOf(end, a)
        assertTrue("'$end' not found after '$start'", b > a)
        return source.substring(a, b)
    }

    @Test
    fun `onCreate builds the player whatever the foreground start says`() {
        val head = between("override fun onCreate() {", "player = createExoPlayer(prefs")
        // a bare `return` (the old early exit); the notification lambdas' `return@Callback` / `return x` are fine
        val bareReturn = Regex("""(?m)(^\s*return\s*$)|(\)\s*return\s*$)""")
        assertFalse("onCreate must not return before the player exists", bareReturn.containsMatchIn(head))
        // and the old code would have failed it
        assertTrue(bareReturn.containsMatchIn("        if (!ensureStartedAsForegroundOrStop()) {\n            return\n        }"))
        assertTrue(head.contains("tryStartForegroundAtCreate()"))
        assertFalse(source.contains("ensureStartedAsForegroundOrStop"))
    }

    @Test
    fun `the async notification update is guarded and startForegroundService is overridden`() {
        val callback = between("val trackingCallback =", "return defaultMediaNotificationProvider")
        val call = callback.indexOf("onNotificationChangedCallback.onNotificationChanged(notification)")
        val tryAt = callback.indexOf("try {")
        val catchAt = callback.indexOf("catch (e: IllegalStateException)")
        assertTrue("the provider callback must be inside a try", tryAt in 0 until call)
        assertTrue("and caught as an IllegalStateException", catchAt > call)
        assertTrue(callback.contains("handleForegroundServiceStartNotAllowed(e)"))

        val override = between("override fun startForegroundService(service: Intent)", "override fun onTaskRemoved")
        assertTrue(override.contains("super.startForegroundService(service)"))
        assertTrue(override.contains("isForegroundServiceStartNotAllowedException(e)"))
    }

    @Test
    fun `a refused foreground start keeps playback the user wants`() {
        val handler = between("private fun handleForegroundServiceStartNotAllowed(", "private fun isForegroundServiceStartNotAllowedException(")
        val keep = handler.indexOf("if (player.playWhenReady)")
        val stop = handler.indexOf("pauseAllPlayersAndStopSelf()")
        assertTrue("playWhenReady must be checked before anything pauses", keep in 0 until stop)
        assertFalse("isPlaying is false while buffering: it must not decide", handler.contains("if (player.isPlaying)"))
    }

    @Test
    fun `only the app's own intent gets the service binder, and the UI attaching lifts task-cleared`() {
        val bind = between("override fun onBind(intent: Intent?)", "fun onUiAttached()")
        assertTrue(bind.contains("if (intent?.action != null) return null"))
        val attach = between("fun onUiAttached()", "override fun startForegroundService")
        assertTrue(attach.contains("taskCleared = false"))
        // every tap from the app lifts it right before playQueue (which drops queues while taskCleared is up)
        val connection = read("PlayerConnection.kt")
        val tap = connection.substring(connection.indexOf("private fun playQueueSafely("))
        val lift = tap.indexOf("service.onUiAttached()")
        val play = tap.indexOf("service.playQueue(queue)")
        assertTrue("a tap must lift taskCleared before playQueue", lift in 0 until play)
        assertTrue(connection.indexOf("runCatching { service.onUiAttached() }") >= 0)
    }

    @Test
    fun `the resolver waits for the extraction through the gate`() {
        val resolver = between("private fun createDataSourceFactory(", "private fun createMediaSourceFactory(")
        val gate = resolver.indexOf("extractionGate.await(mediaId)")
        val extract = resolver.indexOf("InnerTubeXPlayer.playerResponseForPlayback(")
        assertTrue("the extraction must run inside extractionGate.await", gate in 0 until extract)
        assertTrue(resolver.contains("throw StreamResolveFailure("))
        // a new address is checked at its end before it is used (it may serve only the first MiB)
        assertTrue(resolver.contains("streamProbe.statusAt("))
        assertFalse("PlaybackException from the resolver hid its kind", resolver.contains("throw PlaybackException("))
    }

    @Test
    fun `no recovery is a seek to the same position while buffering`() {
        assertFalse(source.contains("updateInitialBufferRecovery"))
        val recover = between("private fun recoverStalledStream(", "override fun onEvents(")
        val stop = recover.indexOf("player.stop()")
        val prepare = recover.indexOf("player.prepare()")
        assertTrue("a stalled load is only cancelled by stop()", stop in 0 until prepare)
    }

    @Test
    fun `READY alone no longer resets the tries of a song`() {
        val ready = between("if (playbackState == Player.STATE_READY) {", "if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED)")
        assertFalse(ready.contains("resetRetryCount"))
        assertFalse(ready.contains("consecutivePlaybackErr = 0"))
    }

    @Test
    fun `a bounded stream is marked for range reads, not cut to one chunk`() {
        val cache = read("StreamUrlCache.kt")
        assertFalse(cache.contains(".subrange("))
        assertTrue(cache.contains("StreamChunking("))
        assertTrue(source.contains("RangeChunkDataSource.Factory("))
    }
}
