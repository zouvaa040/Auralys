package com.metrolist.music.lyrics

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.InternalDatabase
import com.metrolist.music.db.entities.LyricsEntity
import com.metrolist.music.db.entities.SongEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The 4.3.4 re-check on a real (in-memory) database (review 2026-11-12, finding 1): nothing is deleted, a listed row is
 * replaced only by found lyrics (the old text is backed up), offline / failed / not found keep it; "not found" is never
 * stored for a failed lookup and never replaces lyrics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LyricsRecheck434Test {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: InternalDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var prefs: DataStore<Preferences>
    private lateinit var backup: LyricsBackup
    private lateinit var list: LyricsRecheckList
    private val handMade = HashSet<String>()
    private lateinit var store: LyricsStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        prefs = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "test.preferences_pb") }
        backup = LyricsBackup(File(folder.root, LyricsBackup.DIR))
        list = LyricsRecheckList(File(folder.root, LyricsRecheck434.FILE))
        store = LyricsStore(database.dao, backup, isHandMade = { it in handMade }, onReplacedByFound = { handMade.remove(it) })
    }

    @After
    fun tearDown() {
        database.close()
        scope.cancel()
    }

    private fun lyrics(id: String) = runBlocking { database.dao.lyrics(id).first() }

    private fun put(
        id: String,
        provider: String,
        text: String = "[00:01.00]words of $id",
    ) = database.dao.upsert(LyricsEntity(id, text, provider))

    private fun found(
        text: String,
        provider: String = "BiniLyrics",
    ) = LyricsLookup.Found(LyricsWithProvider(text, provider))

    private suspend fun recheck(
        id: String,
        outcome: LyricsLookup,
        online: Boolean = true,
        keptByUser: Boolean = false,
    ): LyricsStore.Result? {
        var asked = 0
        val result =
            LyricsRecheck434.recheck(
                row = database.dao.lyrics(id).first()!!,
                list = list,
                store = store,
                keptByUser = { keptByUser },
                online = { online },
                lookup = {
                    asked++
                    outcome
                },
            )
        if (!online || keptByUser) assertEquals("no lookup", 0, asked)
        return result
    }

    @Test
    fun `listing deletes nothing, lists only automatic rows with lyrics, skips kept songs and runs once`() =
        runBlocking {
            put("acoustic", "LrcLib")
            put("untitled", "Kugou")
            put("dracula", "BetterLyrics")
            put("redred", "lrc.red")
            put("yt", "YouTube Music")
            database.dao.upsert(LyricsEntity("missing", LyricsEntity.LYRICS_NOT_FOUND, ""))
            put("edited", "Manual")
            put("old", "Unknown")
            put("imported", "Some File")
            put("picked", "LrcLib")
            put("handmade", "BiniLyrics")
            database.dao.insert(SongEntity(id = "acoustic", title = "La La Lost You (Acoustic Version)", lyricsOffset = -700))

            val listed = LyricsRecheck434.listOnce(prefs, database.dao, list, keep = setOf("picked", "handmade"))
            assertEquals(5, listed)
            // nothing is gone
            listOf("acoustic", "untitled", "dracula", "redred", "yt", "missing", "edited", "old", "imported", "picked", "handmade")
                .forEach { assertNotNull(it, lyrics(it)) }
            listOf("acoustic", "untitled", "dracula", "redred", "yt").forEach { assertTrue(it, list.contains(it)) }
            listOf("missing", "edited", "old", "imported", "picked", "handmade").forEach { assertFalse(it, list.contains(it)) }
            assertEquals(-700, database.dao.getSongById("acoustic")?.song?.lyricsOffset)
            assertTrue(prefs.data.first()[LyricsRecheck434.ListedKey] == true)
            // the list is on disk: a new process reads it back
            assertTrue(LyricsRecheckList(File(folder.root, LyricsRecheck434.FILE)).contains("dracula"))
            // once only
            put("later", "LrcLib")
            assertNull(LyricsRecheck434.listOnce(prefs, database.dao, list, keep = emptySet()))
            assertFalse(list.contains("later"))
        }

    @Test
    fun `a listed row is replaced only by found lyrics and the old text is kept as previous lyrics`() =
        runBlocking {
            put("acoustic", "LrcLib", "[00:07.60]the original's timing")
            put("offline", "LrcLib", "[00:01.00]hand edited on 4.3.3")
            put("failing", "Kugou", "[00:01.00]old")
            put("none", "BetterLyrics", "[00:01.00]old none")
            list.replaceAll(listOf("acoustic", "offline", "failing", "none"))

            // offline: no lookup, the row and the listing stay
            assertNull(recheck("offline", found("x"), online = false))
            assertEquals("[00:01.00]hand edited on 4.3.3", lyrics("offline")?.lyrics)
            assertTrue(list.contains("offline"))

            // every source failed: the row stays, still listed for a later try
            assertEquals(LyricsStore.Result.NO_CONNECTION, recheck("failing", LyricsLookup.NoConnection))
            assertEquals("[00:01.00]old", lyrics("failing")?.lyrics)
            assertTrue(list.contains("failing"))

            // the sources answered and found nothing: the row stays and is not asked again
            assertEquals(LyricsStore.Result.KEPT, recheck("none", LyricsLookup.NotFound))
            assertEquals("[00:01.00]old none", lyrics("none")?.lyrics)
            assertFalse(list.contains("none"))

            // found: replaced, the old text backed up, off the list
            assertEquals(LyricsStore.Result.STORED, recheck("acoustic", found("[00:01.40]acoustic word sync")))
            assertEquals("[00:01.40]acoustic word sync", lyrics("acoustic")?.lyrics)
            assertEquals("BiniLyrics", lyrics("acoustic")?.provider)
            assertEquals(LyricsBackup.Saved("LrcLib", "[00:07.60]the original's timing"), backup.read("acoustic"))
            assertFalse(list.contains("acoustic"))
            // a second show does nothing (not listed any more)
            assertNull(recheck("acoustic", found("[00:09.00]something else")))
            assertEquals("[00:01.40]acoustic word sync", lyrics("acoustic")?.lyrics)
        }

    @Test
    fun `a song the user kept is never replaced by the re-check`() =
        runBlocking {
            put("picked", "LrcLib")
            put("manual", "Manual")
            list.replaceAll(listOf("picked", "manual"))
            assertNull(recheck("picked", found("[00:02.00]new"), keptByUser = true))
            assertNull(recheck("manual", found("[00:02.00]new")))
            assertEquals("[00:01.00]words of picked", lyrics("picked")?.lyrics)
            assertEquals("[00:01.00]words of manual", lyrics("manual")?.lyrics)
            assertFalse(list.contains("picked"))
            assertFalse(list.contains("manual"))
        }

    @Test
    fun `a fresh lookup during the re-check list is never undone (no delete at all)`() =
        runBlocking {
            put("song", "LrcLib", "[00:01.00]old")
            list.replaceAll(listOf("song"))
            // a fresh lookup stored new lyrics while the song was still listed
            assertEquals(LyricsStore.Result.STORED, store.apply("song", found("[00:01.00]fresh"), LyricsStore.Mode.REPLACE))
            // the re-check of the same song answers the same: nothing changes, nothing is lost
            val result = recheck("song", found("[00:01.00]fresh"))
            assertEquals(LyricsStore.Result.SAME, result)
            assertEquals("[00:01.00]fresh", lyrics("song")?.lyrics)
            // many shows at once (two views, the prefetch): the row is still there and right
            (1..8).map { async(Dispatchers.IO) { store.apply("song", found("[00:01.00]fresh"), LyricsStore.Mode.RECHECK) } }.awaitAll()
            assertEquals("[00:01.00]fresh", lyrics("song")?.lyrics)
        }

    @Test
    fun `not found is stored only where there were no lyrics, and never for a failed lookup`() =
        runBlocking {
            // no row and offline: nothing stored (4.3.3 stored "not found" for good)
            assertEquals(LyricsStore.Result.NO_CONNECTION, store.apply("new", LyricsLookup.NoConnection, LyricsStore.Mode.FILL))
            assertNull(lyrics("new"))
            // no row and the sources answered nothing: stored
            assertEquals(LyricsStore.Result.STORED_NOT_FOUND, store.apply("new", LyricsLookup.NotFound, LyricsStore.Mode.FILL))
            assertEquals(LyricsEntity.LYRICS_NOT_FOUND, lyrics("new")?.lyrics)
            // a stored "not found" is filled when lyrics come (the not-found retry)
            assertEquals(LyricsStore.Result.STORED, store.apply("new", found("[00:01.00]came back"), LyricsStore.Mode.FILL))
            assertEquals("[00:01.00]came back", lyrics("new")?.lyrics)
            assertNull("a not-found row has no text to keep", backup.read("new"))

            // lyrics are never replaced by "not found" (Refetch, Automatic, re-check)
            put("has", "LrcLib")
            for (mode in LyricsStore.Mode.entries) {
                assertEquals(LyricsStore.Result.KEPT, store.apply("has", LyricsLookup.NotFound, mode))
                assertEquals(LyricsStore.Result.NO_CONNECTION, store.apply("has", LyricsLookup.NoConnection, mode))
            }
            assertEquals("[00:01.00]words of has", lyrics("has")?.lyrics)

            // FILL does not overwrite lyrics that came meanwhile (a pick, another lookup)
            assertEquals(LyricsStore.Result.KEPT, store.apply("has", found("[00:05.00]other"), LyricsStore.Mode.FILL))
            assertEquals("[00:01.00]words of has", lyrics("has")?.lyrics)
        }

    @Test
    fun `the backup keeps the first text, unless a later one was made by hand`() =
        runBlocking {
            put("s", "LrcLib", "[00:01.00]4.3.3 text")
            store.apply("s", found("[00:01.00]first new"), LyricsStore.Mode.RECHECK)
            store.apply("s", found("[00:01.00]second new"), LyricsStore.Mode.REPLACE)
            assertEquals("[00:01.00]4.3.3 text", backup.read("s")?.text)
            // the user takes the old text back by hand, then refetches: the backup still holds it
            store.put("s", "[00:01.00]4.3.3 text", "LrcLib", byHand = true)
            store.apply("s", found("[00:01.00]third new"), LyricsStore.Mode.REPLACE)
            assertEquals("[00:01.00]4.3.3 text", backup.read("s")?.text)
            // an edit by hand is what gets kept when found lyrics replace it
            handMade += "s"
            database.dao.upsert(LyricsEntity("s", "[00:01.00]my own edit", "LrcLib"))
            store.apply("s", found("[00:01.00]fourth new"), LyricsStore.Mode.REPLACE)
            assertEquals("[00:01.00]my own edit", backup.read("s")?.text)
            assertFalse("found lyrics end the hand-made mark", "s" in handMade)
        }

    @Test
    fun `backup files are safe names, read back as written and capped`() {
        assertEquals("dQw4w9WgXcQ.txt", LyricsBackup.fileName("dQw4w9WgXcQ"))
        val odd = LyricsBackup.fileName("LOCAL_/storage/emulated/0/Music/a b.mp3")
        assertTrue(odd, odd.startsWith("x_") && odd.endsWith(".txt") && odd.none { it == '/' || it == ' ' })
        assertTrue(backup.write("LOCAL_/x y", "Manual", "line one\nline two"))
        assertEquals(LyricsBackup.Saved("Manual", "line one\nline two"), backup.read("LOCAL_/x y"))
        assertFalse("too long to keep", backup.write("big", "X", "a".repeat(LyricsBackup.MAX_CHARS + 1)))
        assertNull(backup.read("nothing"))
        repeat(LyricsBackup.MAX_FILES + 5) { backup.write("s$it", "P", "text $it") }
        val files = File(folder.root, LyricsBackup.DIR).listFiles { f -> f.name.endsWith(".txt") }!!
        assertTrue("${files.size}", files.size <= LyricsBackup.MAX_FILES)
    }

    @Test
    fun `every name the automatic lookup writes today is on the re-check list`() {
        val names = LyricsProviderRegistry.providerNames.mapNotNull { LyricsProviderRegistry.getProviderByName(it)?.name?.lowercase() }
        names.forEach { assertTrue(it, it in LyricsRecheck434.AUTO_PROVIDERS) }
        assertTrue("manual" !in LyricsRecheck434.AUTO_PROVIDERS && "unknown" !in LyricsRecheck434.AUTO_PROVIDERS)
    }

    @Test
    fun `hand-made ids are kept newest last, capped, and can be taken off`() {
        var text = ""
        repeat(LyricsHandMade.MAX + 3) { text = LyricsHandMade.update(text, "s$it") }
        val ids = text.lines()
        assertEquals(LyricsHandMade.MAX, ids.size)
        assertEquals("s${LyricsHandMade.MAX + 2}", ids.last())
        text = LyricsHandMade.update(text, "s10")
        assertEquals("s10", text.lines().last())
        assertEquals(LyricsHandMade.MAX, text.lines().size)
        assertFalse("s10" in LyricsHandMade.without(text, "s10").lines())
        assertEquals(LyricsHandMade.MAX - 1, LyricsHandMade.without(text, "s10").lines().size)
    }
}
