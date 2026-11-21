/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MusicService cannot be created in a unit test, so this reads its source (the module folder is the working directory of
 * unit tests) and pins the rules that keep "the song stays quiet after a notification" from coming back:
 *  - the audio focus goes through [AudioFocusCoordinator] only, the service has no focus request or listener of its own;
 *  - the user's volume is `playerVolume`; no code reads `player.volume` and keeps it, and every write to a player's
 *    volume is one of the known ones. A new writer fails this test on purpose: check that it cannot store a ducked value.
 */
class MusicServiceVolumeGuardTest {
    private val source: String by lazy {
        listOf("src/main/kotlin", "app/src/main/kotlin")
            .map { File(it, "com/metrolist/music/playback/MusicService.kt") }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("MusicService.kt not found from ${File(".").absolutePath}")
    }

    private val codeLines: List<String> by lazy {
        source.lines().map { it.trim() }.filter { !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
    }

    @Test
    fun `the service has no audio focus request or listener of its own`() {
        val text = codeLines.joinToString("\n")
        assertFalse(text.contains("AudioFocusRequest"))
        assertFalse(text.contains("OnAudioFocusChangeListener"))
        assertFalse(text.contains("requestAudioFocus(request"))
        assertFalse(text.contains("hasAudioFocus ="))
        assertTrue(text.contains("audioFocusCoordinator?.request()"))
        assertTrue(text.contains("audioFocusCoordinator?.abandon()"))
        assertTrue(text.contains("audioFocusCoordinator?.onPlaybackResumed()"))
    }

    @Test
    fun `no code keeps the volume the player has right now`() {
        val keepers =
            codeLines.filter {
                Regex("""\bval\s+\w*[vV]olume\w*\s*=\s*(?:fadingPlayer\??|secondaryPlayer\??|player)\.volume""").containsMatchIn(it) ||
                    Regex("""\bplayerVolume\.value\s*=\s*\S*player\.volume""").containsMatchIn(it)
            }
        assertEquals("a copy of the player's volume freezes a duck: $keepers", emptyList<String>(), keepers)
    }

    @Test
    fun `every write to a player volume is a known one`() {
        val allowed =
            listOf(
                "player.volume = calculateEffectiveVolume()", // applyEffectiveVolume and the end of a crossfade
                "player.volume = it", // the flow of user volume x mute x sleep fade x focus
                "newPlayer.volume = calculateEffectiveVolume()", // a rebuilt player
                "secPlayer.volume = 0f", // the crossfade player starts silent
                "player.volume = target * fadeIn", // crossfade, target read at every step
                "fadingPlayer?.volume = target * fadeOut",
                "fadingPlayer?.volume = 0f",
            )
        val writes = codeLines.filter { Regex("""\.volume\s*=[^=]""").containsMatchIn(it) }
        val unknown = writes.filterNot { line -> allowed.any { line == it } }
        assertEquals("new writer of a player volume, check it cannot store a ducked value: $unknown", emptyList<String>(), unknown)
        assertEquals("known writers are gone, update the list", allowed.size, allowed.count { a -> writes.any { it == a } })
    }

    @Test
    fun `the saved volume is the user volume and nothing else`() {
        val saves = codeLines.filter { it.contains("settings[PlayerVolumeKey]") }
        assertEquals(listOf("settings[PlayerVolumeKey] = volume"), saves)
        val persistedState = codeLines.filter { it.contains("volume = playerVolume.value") }
        assertTrue("player state saves the user volume", persistedState.isNotEmpty())
        assertTrue(
            codeLines.any { it == "playerVolume.debounce(1000).collect(scope) { volume ->" },
        )
    }
}
