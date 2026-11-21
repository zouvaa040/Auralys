/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.spotify

import com.metrolist.music.constants.SpotifyCanvasKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the code reading cannot show at run time: the setting, its strings and its search entry exist (and the "Utamakan"
 * order switch is gone), and the Spotify tokens are handled in exactly one place.
 */
class SpotifyCanvasWiringTest {
    private val root: File =
        listOf(File("."), File(".."))
            .firstOrNull { File(it, "app/src/main/kotlin").isDirectory }
            ?: error("project not found from ${File(".").absolutePath}")

    private fun source(path: String) = File(root, path).readText()

    private val main = "app/src/main/kotlin/com/metrolist/music"

    @Test
    fun `the setting is one switch, on by default, with a plain Indonesian title and hint`() {
        assertEquals("spotifyCanvas", SpotifyCanvasKey.name)
        val id = source("app/src/main/res/values-in/auralys_spotify_canvas.xml")
        assertTrue(id.contains("""<string name="spotify_canvas_title">Sampul animasi dari Spotify</string>"""))
        assertTrue(id.contains("""<string name="spotify_canvas_connect_hint">Hubungkan Spotify di tab Kamu</string>"""))
        val en = source("app/src/main/res/values/auralys_spotify_canvas.xml")
        assertTrue(en.contains("""name="spotify_canvas_title""""))
        assertTrue(en.contains("""<string name="spotify_canvas_connect_hint">Connect Spotify in the You tab</string>"""))
        // the hint names the screen the way the app does: Spotify is connected next to the YouTube Music sign-in on the You tab
        assertTrue(source("app/src/main/res/values-in/auralys_hub.xml").contains("""<string name="you_title">Kamu</string>"""))
        assertTrue(source("app/src/main/res/values/auralys_hub.xml").contains("""<string name="you_title">You</string>"""))
    }

    @Test
    fun `there is no order switch any more, Spotify always comes first`() {
        val touched = listOf(
            "app/src/main/res/values/auralys_spotify_canvas.xml",
            "app/src/main/res/values-in/auralys_spotify_canvas.xml",
            "$main/constants/PreferenceKeys.kt",
            "$main/ui/screens/settings/PlayerSettings.kt",
            "$main/ui/screens/settings/SettingsSearchIndex.kt",
            "$main/canvas/AnimatedArtworkRepository.kt",
        ).associateWith(::source)
        touched.forEach { (file, text) ->
            assertFalse("$file still mentions an order switch", text.contains("Utamakan", ignoreCase = true) || text.contains("SpotifyCanvasFirst") || text.contains("spotifyFirst"))
        }
    }

    @Test
    fun `the switch is in the player settings, disabled with a hint until Spotify is connected, and is searchable`() {
        val player = source("$main/ui/screens/settings/PlayerSettings.kt")
        assertTrue(player.contains("R.string.spotify_canvas_title"))
        assertTrue(player.contains("R.string.spotify_canvas_connect_hint"))
        assertTrue(player.contains("SpotifySession.connected"))
        assertTrue(player.contains("enabled = spotifyConnected && animatedArtwork"))
        assertTrue(source("$main/ui/screens/settings/SettingsSearchIndex.kt").contains("R.string.spotify_canvas_title"))
    }

    @Test
    fun `the tokens are put on a request in one place and never logged`() {
        val client = source("$main/spotify/SpotifyCanvasClient.kt")
        assertEquals(1, Regex("\"Authorization\"").findAll(client).count())
        assertEquals(1, Regex("\"Client-Token\"").findAll(client).count())
        val logLines = client.lines().filter { it.contains("Timber") }
        assertTrue(logLines.isNotEmpty())
        assertTrue(logLines.none { Regex("bearer|clientToken|session|Authorization|token", RegexOption.IGNORE_CASE).containsMatchIn(it) })
        // the repository and the matcher never see a token
        listOf("$main/canvas/AnimatedArtworkRepository.kt", "$main/spotify/SpotifyCanvasMatch.kt", "$main/spotify/SpotifyCanvasQuery.kt").forEach {
            val text = source(it)
            assertFalse(it, text.contains("SpotifyTokens") || text.contains(".bearer") || text.contains("clientToken"))
        }
        // nothing prints stack traces or uses android.util.Log in the new code
        listOf("$main/spotify/SpotifyCanvasClient.kt", "$main/spotify/SpotifyCanvasMatch.kt", "$main/spotify/SpotifyCanvasQuery.kt").forEach {
            val text = source(it)
            assertFalse(it, text.contains("printStackTrace") || text.contains("android.util.Log"))
        }
    }

    @Test
    fun `the repository reads the switch only after the connected flag, so the Apple only path is untouched`() {
        val repo = source("$main/canvas/AnimatedArtworkRepository.kt")
        val gate = repo.substringAfter("private suspend fun spotifyActive").substringBefore("private suspend fun spotifyHit")
        assertTrue(gate.indexOf("SpotifySession.connected.value") in 0 until gate.indexOf("SpotifyCanvasKey"))
    }
}
