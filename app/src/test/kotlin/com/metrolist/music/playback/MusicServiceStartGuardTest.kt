/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Crash report 2026-11-07: "Unable to start service MusicService with Intent UPDATE_WIDGET: NullPointerException:
 * ExoPlayerImpl.isPlaying() on a null object reference". `onCreate` returns early when the system refuses the foreground
 * start (a widget starting the service from the background on Android 12+); the framework then still calls
 * `onStartCommand`, and the widget actions used `player` that was never built.
 *
 * MusicService cannot be created in a unit test, so this reads its source and pins the rule: `onStartCommand` leaves
 * (stopSelf + START_NOT_STICKY) when the player is not initialised, BEFORE any of its actions touches `player`.
 */
class MusicServiceStartGuardTest {
    private val source: String by lazy {
        listOf("src/main/kotlin", "app/src/main/kotlin")
            .map { File(it, "com/metrolist/music/playback/MusicService.kt") }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("MusicService.kt not found from ${File(".").absolutePath}")
    }

    private val body: String by lazy {
        val start = source.indexOf("override fun onStartCommand(")
        assertTrue("onStartCommand not found", start >= 0)
        val end = source.indexOf("private fun handleAlarmTrigger(", start)
        assertTrue("handleAlarmTrigger not found after onStartCommand", end > start)
        source.substring(start, end)
    }

    @Test
    fun `onStartCommand stops when the player was never built`() {
        val guard = body.indexOf("if (!playerInitialized.value)")
        assertTrue("no player guard in onStartCommand", guard >= 0)
        val after = body.substring(guard, minOf(body.length, guard + 400))
        assertTrue("the guard must stopSelf()", after.contains("stopSelf()"))
        assertTrue("the guard must return START_NOT_STICKY", after.contains("return START_NOT_STICKY"))
    }

    @Test
    fun `no action of onStartCommand reaches the player before the guard`() {
        val guard = body.indexOf("if (!playerInitialized.value)")
        val firstAction = body.indexOf("when (intent?.action)")
        assertTrue("the guard must come before the action switch", guard in 0 until firstAction)
        val beforeGuard = body.substring(0, guard)
        assertTrue("player is used before the guard", !Regex("""\bplayer\.""").containsMatchIn(beforeGuard))
    }
}
