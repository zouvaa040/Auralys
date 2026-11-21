package com.metrolist.music.spotify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Spotify sign-in cookie (`sp_dc`) is the user's whole Spotify session. These guards read the sources and resources and fail
 * when it could end up where it must not: in the DataStore (the backup export zips settings.preferences_pb), in Android's Auto
 * Backup or device transfer, in a log, or anywhere but a spotify.com request.
 */
class SpotifyPrivacyGuardTest {
    private val root: File =
        listOf(File("."), File(".."))
            .firstOrNull { File(it, "app/src/main/kotlin").isDirectory }
            ?: error("project not found from ${File(".").absolutePath}")

    private fun file(path: String) = File(root, path)

    private fun text(path: String) = file(path).readText()

    private val spotifySources: List<File> by lazy {
        file("app/src/main/kotlin/com/metrolist/music/spotify").listFiles { f -> f.extension == "kt" }!!
            // the Canvas files belong to another feature and have their own rules
            .filter { !it.name.startsWith("SpotifyCanvas") }
    }

    @Test
    fun `the cookie file is left out of Auto Backup and device transfer`() {
        val backup = text("app/src/main/res/xml/backup_rules.xml")
        val rules = text("app/src/main/res/xml/data_extraction_rules.xml")
        val exclude = Regex("""domain="sharedpref"\s+path="${Regex.escape(SpotifyAuthStore.PREFS_FILE)}\.xml"""")
        assertTrue("backup_rules.xml must exclude the Spotify prefs file", exclude.containsMatchIn(backup))
        val cloud = rules.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        val transfer = rules.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
        assertTrue("cloud backup must exclude it", exclude.containsMatchIn(cloud))
        assertTrue("device transfer must exclude it", exclude.containsMatchIn(transfer))
    }

    @Test
    fun `the web view's data directory is left out of Auto Backup and device transfer too`() {
        // the sign-in is written into the web view's cookie jar as plain text for the web player
        val backup = text("app/src/main/res/xml/backup_rules.xml")
        val rules = text("app/src/main/res/xml/data_extraction_rules.xml")
        val exclude = Regex("""domain="root"\s+path="app_webview"""")
        assertTrue("backup_rules.xml must exclude app_webview", exclude.containsMatchIn(backup))
        val cloud = rules.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        val transfer = rules.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
        assertTrue("cloud backup must exclude app_webview", exclude.containsMatchIn(cloud))
        assertTrue("device transfer must exclude app_webview", exclude.containsMatchIn(transfer))
    }

    @Test
    fun `the cookie is only ever written into the jar through the HttpOnly helper`() {
        val minter = text("app/src/main/kotlin/com/metrolist/music/spotify/SpotifyTokenMinter.kt")
        // every setCookie of the sign-in goes through spDcCookie (which adds HttpOnly), none builds the string by hand
        val writes = Regex("""setCookie\([^\n]*sp_dc""").findAll(minter).toList()
        assertTrue("a setCookie writes sp_dc by hand: ${writes.map { it.value }}", writes.isEmpty())
        assertTrue(Regex("""setCookie\([^\n]*spDcCookie\(""").findAll(minter).count() >= 2)
        // and the hook compares host and path of the token request, not a piece of its text
        assertFalse(minter.contains("indexOf('/api/token')"))
        assertTrue(minter.contains("a.hostname === 'open.spotify.com' && a.pathname === '/api/token'"))
    }

    @Test
    fun `sign-out removes Spotify's web storage and cookies only, never everyone's`() {
        val account = text("app/src/main/kotlin/com/metrolist/music/spotify/SpotifyAccount.kt")
        val clear = account.substringAfter("fun clearSpotifyWebSession()")
        assertTrue(clear.contains("deleteOrigin("))
        assertFalse("sign-out must not wipe the YouTube sign-in", clear.contains("removeAllCookies") || clear.contains("deleteAllData"))
    }

    @Test
    fun `the backup export zips only the settings, the aliases and the database`() {
        val source = text("app/src/main/kotlin/com/metrolist/music/viewmodels/BackupRestoreViewModel.kt")
        val backup = source.substringAfter("fun backup(").substringBefore("fun restore(")
        // everything that goes into the zip
        val entries = Regex("""ZipEntry\(([^)]*)\)""").findAll(backup).map { it.groupValues[1] }.toList()
        assertTrue("entries: $entries", entries.isNotEmpty())
        assertTrue(entries.all { it.contains("SETTINGS_FILENAME") || it.contains("BACKUP_FILENAME") || it.contains("DB_NAME") })
        assertFalse("the export must not walk shared_prefs", backup.contains("shared_prefs") || backup.contains("sharedPref"))
        assertFalse(backup.contains("spotify", ignoreCase = true))
    }

    @Test
    fun `the cookie is not a DataStore setting`() {
        val keys = file("app/src/main/kotlin/com/metrolist/music/constants").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse("sp_dc must not have a preference key", keys.contains("sp_dc", ignoreCase = true) || keys.contains("SpDc", ignoreCase = true))
        for (f in spotifySources) {
            val s = f.readText()
            assertFalse("${f.name} must not use the DataStore", s.contains("safeDataStoreEdit") || s.contains("dataStore.edit") || s.contains("PreferenceKeys"))
        }
    }

    @Test
    fun `nothing in the Spotify account code logs`() {
        for (f in spotifySources) {
            val s = f.readText()
            assertFalse("${f.name} logs with Timber", s.contains("Timber."))
            assertFalse("${f.name} logs with Log", Regex("""\bLog\.[dwiev]\(""").containsMatchIn(s))
            assertFalse("${f.name} prints", s.contains("println(") || s.contains("printStackTrace"))
            assertFalse("${f.name} reports an exception", s.contains("reportException"))
        }
    }

    @Test
    fun `every URL in the account code is a Spotify one`() {
        val hosts = Regex("""https://([A-Za-z0-9.-]+)""")
        for (f in spotifySources) {
            // code only: the credit lines of the file headers name github.com
            val code =
                f.readLines()
                    .filter { line -> line.trim().let { !it.startsWith("*") && !it.startsWith("/*") && !it.startsWith("//") } }
                    .joinToString("\n")
                    .replace("\\.", ".") // a regex written https://open\.spotifycdn\.com/...
            for (m in hosts.findAll(code)) {
                val host = m.groupValues[1].trimEnd('.')
                // the sign-in's identity providers are written as host suffixes in an allow-list, not as URLs
                assertTrue("${f.name}: $host", isSpotifyAssetHost(host))
            }
        }
    }

    @Test
    fun `the script bridge is kept by name for the release build`() {
        val rules = text("app/proguard-rules.pro")
        assertTrue(rules.contains("com.metrolist.music.spotify.SpotifyTokenBridge"))
        assertTrue(rules.contains("@android.webkit.JavascriptInterface"))
    }
}
