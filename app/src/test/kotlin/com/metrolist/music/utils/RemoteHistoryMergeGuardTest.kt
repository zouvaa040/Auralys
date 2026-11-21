package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source guards for the merge of the account history: the pure file stays pure, nothing here reaches the network from
 * the main thread or from the playback service, the schema of the database is untouched, and the strings live in
 * their own files.
 */
class RemoteHistoryMergeGuardTest {
    private val root: File =
        listOf("src/main/kotlin", "app/src/main/kotlin")
            .map { File(it, "com/metrolist/music").absoluteFile }
            .firstOrNull { it.isDirectory }
            ?: error("sources not found from ${File(".").absolutePath}")

    private val main: File = root.parentFile.parentFile.parentFile.parentFile // .../src/main

    private fun src(path: String): String = File(root, path).readText()

    private fun res(path: String): String = File(main, "res/$path").readText()

    private val mine =
        listOf(
            "utils/RemoteHistoryMerge.kt",
            "utils/RemoteHistoryImporter.kt",
            "utils/RemoteHistoryMerger.kt",
            "viewmodels/RemoteHistoryMergeViewModel.kt",
            "ui/screens/settings/HistoryMergeSettings.kt",
            "db/entities/EventStamp.kt",
        )

    @Test
    fun `the pure file imports nothing from Android, coroutines, the network or the database`() {
        val text = src("utils/RemoteHistoryMerge.kt")
        val imports = text.lines().filter { it.startsWith("import ") }
        for (line in imports) {
            assertTrue("pure file imports $line", line.startsWith("import java.time.") || line.startsWith("import java.util.") || line.startsWith("import java.text."))
        }
        assertFalse(text.contains("Timber"))
        assertFalse(text.contains("LocalDate.now("))
        assertFalse(text.contains("LocalDateTime.now("))
        assertFalse(text.contains("System.currentTimeMillis"))
    }

    @Test
    fun `no network and no database work happens on the main thread`() {
        val importer = src("utils/RemoteHistoryImporter.kt")
        val merger = src("utils/RemoteHistoryMerger.kt")
        val model = src("viewmodels/RemoteHistoryMergeViewModel.kt")
        assertTrue("the importer must switch to IO", importer.contains("withContext(Dispatchers.IO)"))
        assertTrue("the merger must launch on IO", merger.contains("scope.launch(Dispatchers.IO)"))
        for ((name, text) in listOf("importer" to importer, "merger" to merger, "view model" to model)) {
            assertFalse("$name uses runBlocking", text.contains("runBlocking"))
            assertFalse("$name uses the main dispatcher", text.contains("Dispatchers.Main"))
            assertFalse("$name sleeps", text.contains("Thread.sleep"))
            assertFalse("$name uses GlobalScope", text.contains("GlobalScope"))
            assertFalse("$name reads the DataStore blockingly", Regex("""dataStore\s*\[""").containsMatchIn(text) || text.contains("dataStore.get("))
        }
        // the only thing that touches the main looper is the toast, through a Handler
        assertTrue(merger.contains("Handler(Looper.getMainLooper())"))
        // the YouTube call lives in one place
        for ((path, text) in listOf("utils/RemoteHistoryMerger.kt" to merger, "viewmodels/RemoteHistoryMergeViewModel.kt" to model)) {
            assertFalse("$path calls YouTube.musicHistory", text.contains("musicHistory"))
        }
        assertEquals(1, Regex("""YouTube\.musicHistoryChunk""").findAll(importer).count())
    }

    @Test
    fun `the playback service does not know about the merge and only the activity starts it`() {
        assertFalse(src("playback/MusicService.kt").contains("RemoteHistory"))
        val callers =
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }
                .filter { it.readText().contains(".onAppOpened()") }
                .map { it.relativeTo(root).path.replace('\\', '/') }
                .toList()
        assertEquals(listOf("MainActivity.kt"), callers)
    }

    @Test
    fun `the request for the history never changes the locale the other requests share`() {
        val youtube = File(main.parentFile.parentFile.parentFile, "innertube/src/main/kotlin/com/metrolist/innertube/YouTube.kt").readText()
        val from = youtube.indexOf("suspend fun musicHistoryChunk")
        assertTrue(from > 0)
        val body = youtube.substring(from, youtube.indexOf("suspend fun likeVideo", from))
        assertTrue("it must use its own copy of the session", body.contains("createIsolatedSession"))
        assertTrue(body.contains("session.locale ="))
        assertFalse("it must not set the shared locale", body.contains("innerTube.locale =") || body.contains("YouTube.locale") || Regex("""\n\s+locale\s*=""").containsMatchIn(body))
        assertFalse("it must not close the shared client", body.contains(".close()"))
    }

    @Test
    fun `the database schema is untouched`() {
        for (path in mine) {
            val text = src(path)
            for (banned in listOf("@Entity", "@Database", "@ColumnInfo", "@PrimaryKey", "AutoMigration", "Migration(", "ALTER TABLE", "CREATE TABLE", "CREATE INDEX", "@DatabaseView")) {
                assertFalse("$path contains $banned", text.contains(banned))
            }
        }
        // the Event table has the four columns it always had
        val event = src("db/entities/Event.kt")
        assertTrue(event.contains("val id: Long = 0"))
        assertTrue(event.contains("val songId: String"))
        assertTrue(event.contains("val timestamp: LocalDateTime"))
        assertTrue(event.contains("val playTime: Long"))
        assertEquals(4, Regex("""\bval\s+\w+\s*:""").findAll(event.substringAfter("data class Event(")).count())
        // the new data class is not one of the database's entities
        assertFalse(src("db/MusicDatabase.kt").contains("EventStamp"))
        // and the queries added to the DAO are plain queries
        val dao = src("db/DatabaseDao.kt")
        assertTrue(dao.contains("SELECT songId, timestamp FROM event WHERE timestamp >= :from AND timestamp < :to"))
        assertTrue(dao.contains("SELECT * FROM event ORDER BY timestamp ASC, rowId ASC LIMIT 1"))
    }

    @Test
    fun `what the merge remembers lives in the settings store, not in Room`() {
        val keys = src("constants/PreferenceKeys.kt")
        for (name in listOf("Enabled", "LastSuccess", "LastAttempt", "LastAdded", "Account", "CoveredDay", "FloorDay", "Removed")) {
            assertTrue("RemoteHistoryMerge${name}Key missing", keys.contains("val RemoteHistoryMerge${name}Key"))
        }
    }

    @Test
    fun `the strings are in their own files, in both languages, and nowhere else`() {
        val english = res("values/auralys_history_merge.xml")
        val indonesian = res("values-in/auralys_history_merge.xml")
        val nameOf = Regex("""<(?:string|plurals) name="([^"]+)"""")
        val en = nameOf.findAll(english).map { it.groupValues[1] }.toSet()
        val id = nameOf.findAll(indonesian).map { it.groupValues[1] }.toSet()
        assertEquals(en, id)
        assertTrue(en.size >= 9)
        val others =
            File(main, "res").walkTopDown().filter { it.isFile && it.extension == "xml" && it.parentFile.name.startsWith("values") && it.name != "auralys_history_merge.xml" }
        for (file in others) {
            val text = file.readText()
            assertFalse("${file.path} defines a history merge string", text.contains("auralys_history_merge"))
        }
        // plain, consistent Indonesian: "jika", "tidak", "sudah", no slang
        for (slang in listOf("kalau", "nggak", "enggak", "gak ", "udah", "banget", "aja ", "doang", "bikin")) {
            assertFalse("slang \"$slang\" in the Indonesian strings", indonesian.contains(slang, ignoreCase = true))
        }
        assertTrue(indonesian.contains("Gabungkan riwayat dari akun"))
        assertTrue(indonesian.contains("Gabungkan sekarang"))
        assertTrue(indonesian.contains("Belum pernah"))
        assertTrue(indonesian.contains("Terakhir: %1\$s, %2\$d putaran ditambahkan"))
        assertTrue(indonesian.contains("Riwayat dari akunmu digabung: %1\$d putaran"))
    }

    @Test
    fun `the rows are in the backup page and in the settings search`() {
        val backup = src("ui/screens/settings/BackupAndRestore.kt")
        assertTrue(backup.contains("historyMergeItems("))
        val index = src("ui/screens/settings/SettingsSearchIndex.kt")
        assertTrue(index.contains("R.string.auralys_history_merge_title"))
        assertTrue(index.contains("R.string.auralys_history_merge_now"))
        val rows = src("ui/screens/settings/HistoryMergeSettings.kt")
        assertTrue(rows.contains("R.string.auralys_history_merge_title"))
        assertTrue(rows.contains("R.string.auralys_history_merge_now"))
        // no description line (the settings pages have a budget for those): the last result is real state and sits
        // under the title of the button row
        assertEquals(0, Regex("""\bdescription\s*=""").findAll(rows).count())
        assertTrue(rows.contains("text = status"))
    }

    @Test
    fun `the remembered state is dropped at logout and the user's own history actions are noted`() {
        assertTrue(src("App.kt").contains("RemoteHistoryMerger.forget(settings)"))
        assertTrue(src("ui/menu/SongMenu.kt").contains("RemoteHistoryMerger.noteEventRemoved("))
        assertTrue(src("ui/screens/settings/PrivacySettings.kt").contains("RemoteHistoryMerger.noteHistoryCleared("))
    }

    @Test
    fun `the automatic runs respect the data saver, the pause and the interval`() {
        val merger = src("utils/RemoteHistoryMerger.kt")
        assertTrue(merger.contains("DataSaverKey"))
        assertTrue(merger.contains("DataSaverMobileOnlyKey"))
        assertTrue(merger.contains("PauseListenHistoryKey"))
        assertTrue(merger.contains("RemoteHistoryMergeGate.decide("))
        assertEquals(6L * 60 * 60 * 1000, RemoteHistoryMergeGate.INTERVAL_MS)
        val importer = src("utils/RemoteHistoryImporter.kt")
        assertTrue(importer.contains("val maxPages: Int = 40"))
        assertTrue(importer.contains("val maxEntries: Int = 2000"))
        assertTrue(importer.contains("val timeoutMs: Long = 60_000L"))
    }
}
