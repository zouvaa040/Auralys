package com.metrolist.music.spotify

import com.metrolist.music.ui.component.IconSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where Spotify is connected: ONE place, next to the YouTube Music sign-in (the account block that the You tab and the top of
 * Settings both draw), as a tile with the real Spotify mark. It must not come back to Backup and restore, and the search, the
 * Library list and the hint of the animated covers must all point at the new place. The two sign-in screens share one frame.
 * Reads sources and resources, like the other guards.
 */
class SpotifyPlacementTest {
    private val root: File =
        listOf(File("."), File(".."))
            .firstOrNull { File(it, "app/src/main/kotlin").isDirectory }
            ?: error("project not found from ${File(".").absolutePath}")

    private val main = "app/src/main/kotlin/com/metrolist/music"

    private fun text(path: String) = File(root, path).readText().replace("\r\n", "\n")

    private val account get() = text("$main/ui/screens/settings/YtmAccountSection.kt")

    private val kit get() = text("$main/ui/component/AccountKit.kt")

    @Test
    fun `the You tab and Settings both draw the one account block`() {
        assertTrue(text("$main/ui/screens/YouScreen.kt").contains("YtmAccountSection("))
        assertTrue(text("$main/ui/screens/settings/SettingsScreen.kt").contains("YtmAccountSection("))
        assertTrue(account.contains("AccountBlock("))
    }

    @Test
    fun `the account block has a tile for each sign-in and each tile goes where it should`() {
        val src = account
        assertTrue(src.contains("AccountProvider.YOUTUBE_MUSIC"))
        assertTrue(src.contains("AccountProvider.SPOTIFY"))
        // YouTube Music: the sign-in, or the account once signed in; Spotify: its own page, connected or not
        assertTrue(src.contains("""if (isLoggedIn) "account" else "login""""))
        assertTrue(src.contains("""navController.navigate("settings/spotify")"""))
        // sync and sign out stay, for the YouTube Music sign-in only
        assertTrue(src.contains("R.string.yt_sync") && src.contains("R.string.action_logout"))
    }

    @Test
    fun `the account name is only shown when it is already known, never fetched by the tile`() {
        val src = account
        assertTrue(src.contains("SpotifyAccount.profile"))
        assertFalse("the tile must not start a network read", src.contains("loadProfile"))
    }

    @Test
    fun `the account block adds no description line to the settings budget`() {
        // the settings pages have a budget for description lines (DesignGuardTest); the tiles carry their line themselves
        assertEquals(0, Regex("""\bdescription\s*=""").findAll(account).count())
        assertEquals(0, Regex("""\bdescription\s*=""").findAll(text("$main/ui/screens/settings/SpotifySettings.kt")).count())
    }

    @Test
    fun `the tiles are rounded rectangles from the radius scale with no hairline, capsule or blur`() {
        val src = kit
        assertTrue(src.contains("HorizonRadius."))
        for (banned in listOf("RoundedCornerShape(50", "border(", "BorderStroke", "glassBlur", "liquidGlass", "infiniteRepeatable", "rememberInfiniteTransition", ".blur(")) {
            assertFalse("AccountKit.kt has $banned", src.contains(banned))
        }
        val frame = text("$main/ui/component/LoginFrame.kt")
        for (banned in listOf("RoundedCornerShape(50", "border(", "BorderStroke", "glassBlur", "liquidGlass", "infiniteRepeatable", "rememberInfiniteTransition", ".blur(")) {
            assertFalse("LoginFrame.kt has $banned", frame.contains(banned))
        }
    }

    @Test
    fun `Backup and restore no longer has any Spotify account row`() {
        val src = text("$main/ui/screens/settings/BackupAndRestore.kt")
        assertFalse(src.contains("settings/spotify"))
        assertFalse(src.contains("R.string.spotify_"))
        assertFalse(src.contains("SpotifySession"))
        assertFalse(src.contains("hub_cat_integrations"))
        // the link import (a Spotify playlist link) is a different feature and stays
        assertTrue(src.contains("PlaylistLinkSource.SPOTIFY"))
    }

    @Test
    fun `the routes the tiles use exist`() {
        val nav = text("$main/ui/screens/NavigationBuilder.kt")
        assertTrue(nav.contains("""composable("settings/spotify")"""))
        assertTrue(nav.contains("""composable("spotify")"""))
        assertTrue(nav.contains("""route = "spotify_login""""))
        assertTrue(nav.contains("""composable("login")"""))
        assertTrue(nav.contains("""composable("account")"""))
    }

    @Test
    fun `search finds Spotify on the Spotify page under Account, not under Connected apps`() {
        val index = text("$main/ui/screens/settings/SettingsSearchIndex.kt")
        val rows = index.lines().filter { it.contains("settings/spotify") && it.trimStart().startsWith("SettingsSearchEntry(") }
        assertEquals(3, rows.size)
        for (row in rows) {
            assertTrue(row, row.contains("R.string.account"))
            assertFalse(row, row.contains("hub_cat_integrations"))
        }
        assertTrue(rows.any { it.contains("SettingsSearchEntry(R.string.spotify_title,") })
        assertTrue(rows.any { it.contains("SettingsSearchEntry(R.string.spotify_connect,") })
        assertTrue(rows.any { it.contains("SettingsSearchEntry(R.string.spotify_disconnect,") })
        // the old category still belongs to Listen Together, so its string stays
        assertTrue(File(root, "app/src/main/res/values/auralys_hub.xml").readText().contains("""name="hub_cat_integrations""""))
        assertTrue(index.contains("hub_cat_integrations, \"settings/integrations/listen_together\""))
    }

    private fun assertBrandMark(file: String, exemptName: String) {
        val xml = File(root, "app/src/main/res/drawable/$file.xml").readText()
        assertTrue(xml.contains("""android:viewportWidth="24"""") && xml.contains("""android:viewportHeight="24""""))
        assertTrue(xml.contains("""android:width="24dp"""") && xml.contains("""android:height="24dp""""))
        assertTrue(xml.contains("""android:fillColor="#ffffff""""))
        assertTrue("the details are holes", xml.contains("""android:fillType="evenOdd""""))
        assertEquals("one path only", 1, Regex("<path").findAll(xml).count())
        assertTrue(IconSources.exempt.getValue(exemptName).contains("brand"))
    }

    @Test
    fun `the Spotify mark is a white 24 viewport vector, a circle with three bars cut out, and a listed brand mark`() {
        assertBrandMark("spotify", "spotify")
        val data = Regex("""pathData="([^"]+)"""").find(File(root, "app/src/main/res/drawable/spotify.xml").readText())!!.groupValues[1]
        assertEquals("one circle and three bars", 4, Regex("[zZ]").findAll(data).count())
    }

    @Test
    fun `the YouTube Music mark is a white 24 viewport vector, a disc with the ring and the triangle cut out, and a listed brand mark`() {
        assertBrandMark("youtube_music", "youtube_music")
        val data = Regex("""pathData="([^"]+)"""").find(File(root, "app/src/main/res/drawable/youtube_music.xml").readText())!!.groupValues[1]
        assertEquals("a disc, the two edges of the ring and the triangle", 4, Regex("[zZ]").findAll(data).count())
    }

    @Test
    fun `every place that names Spotify draws the mark, never the generic library icon`() {
        for (file in listOf("ui/screens/library/LibraryMixScreen.kt", "ui/screens/library/LibraryPlaylistsScreen.kt")) {
            val src = text("$main/$file")
            val line = src.lines().single { it.contains("CollectionEntry(R.string.spotify_title") }
            assertTrue("$file: $line", line.contains("R.drawable.spotify"))
        }
        assertTrue(kit.contains("R.drawable.spotify") && kit.contains("R.drawable.youtube_music"))
        assertTrue(text("$main/ui/screens/settings/SpotifySettings.kt").contains("BrandMark(AccountProvider.SPOTIFY"))
        assertFalse(account.contains("R.drawable.library_music"))
    }

    @Test
    fun `the Spotify page is a hero with three benefits, one main action, the privacy sentence and the account card`() {
        val page = text("$main/ui/screens/settings/SpotifySettings.kt")
        for (needed in listOf("R.string.spotify_connect_desc", "R.string.spotify_benefit_play", "R.string.spotify_canvas_title", "R.string.spotify_settings_privacy", "AccountCard(", "R.string.spotify_disconnect", "R.string.spotify_disconnect_title")) {
            assertTrue("SpotifySettings.kt lacks $needed", page.contains(needed))
        }
        // the sign-out still asks first and still runs in the account's own scope
        assertTrue(page.contains("SpotifyAccount.disconnect()"))
        assertTrue(page.contains("""navController.navigate("spotify_login")"""))
    }

    @Test
    fun `both sign-in screens use the one frame and report the page state, without touching how the sign-in is read`() {
        val ytm = text("$main/ui/screens/LoginScreen.kt")
        val spotify = text("$main/spotify/SpotifyLogin.kt")
        for ((name, src) in listOf("LoginScreen.kt" to ytm, "SpotifyLogin.kt" to spotify)) {
            assertTrue("$name lacks LoginFrame", src.contains("LoginFrame("))
            assertTrue("$name lacks the offline hook", src.contains("override fun onReceivedError"))
            assertTrue("$name lacks the progress hook", src.contains("override fun onProgressChanged"))
            assertTrue("$name lacks the first-paint hook", src.contains("override fun onPageCommitVisible"))
            assertTrue("$name lacks the retry", src.contains("reload()"))
        }
        assertTrue(ytm.contains("R.string.login_privacy_google") && spotify.contains("R.string.login_privacy_spotify"))
        // the YouTube Music capture is as it was
        assertTrue(ytm.contains("suspend fun extractAuthData(") && ytm.contains("YouTube.cookie = authData.cookie") && ytm.contains("jsInterface"))
        // the Spotify sign-in is still read from the cookie manager, with no script bridge
        assertTrue(spotify.contains("spDcFrom(CookieManager.getInstance().getCookie(") && !spotify.contains("addJavascriptInterface"))
    }

    @Test
    fun `the strings exist in both languages in plain wording and the animated-covers hint points at the You tab`() {
        val en = File(root, "app/src/main/res/values/auralys_spotify.xml").readText()
        val id = File(root, "app/src/main/res/values-in/auralys_spotify.xml").readText()
        for (name in listOf("spotify_connect_desc", "spotify_hero_sub", "spotify_benefit_play", "acct_pitch_ytm", "acct_pitch_spotify", "acct_action_login", "acct_action_connect", "acct_action_manage", "acct_connected_as", "login_privacy_google", "login_privacy_spotify", "login_loading", "login_connecting", "login_offline_title", "login_offline_message")) {
            assertTrue("en lacks $name", en.contains("""name="$name""""))
            assertTrue("id lacks $name", id.contains("""name="$name""""))
        }
        assertTrue(id.contains("""<string name="spotify_connect">Hubungkan Spotify</string>"""))
        assertTrue(id.contains("""<string name="spotify_connect_desc">Playlist dan lagu disukai di Pustaka</string>"""))
        assertTrue(id.contains("""<string name="login_privacy_google">Masuk lewat halaman resmi Google. Auralys tidak melihat kata sandimu.</string>"""))
        for (banned in listOf("kalau", "gak", "nggak", "banget", " & ")) assertFalse(banned, id.contains(banned))
        for (file in listOf("values/auralys_spotify_canvas.xml", "values-in/auralys_spotify_canvas.xml")) {
            val hint = File(root, "app/src/main/res/$file").readText()
            assertFalse("$file still points at Backup and restore", hint.contains("Backup and restore") || hint.contains("Cadangan"))
        }
        assertTrue(File(root, "app/src/main/res/values-in/auralys_spotify_canvas.xml").readText().contains("tab Kamu"))
    }
}
