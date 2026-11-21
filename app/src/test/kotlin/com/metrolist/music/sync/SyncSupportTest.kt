package com.metrolist.music.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.zip.ZipFile

class SyncSupportTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `stored wall clock time becomes a real instant and back`() {
        val jakarta = ZoneId.of("Asia/Jakarta")
        // the app stores LocalDateTime.now() as if it were UTC
        val wall = LocalDateTime.of(2026, 11, 4, 20, 15, 30)
        val stored = wall.toInstant(ZoneOffset.UTC).toEpochMilli()
        val epoch = SyncTime.dbToEpoch(stored, jakarta)
        assertEquals(java.time.Instant.parse("2026-11-04T13:15:30Z").toEpochMilli(), epoch)
        assertEquals(wall, SyncTime.epochToLocal(epoch, jakarta))
        // round trip through the storage format
        val back = SyncTime.epochToLocal(epoch, jakarta).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(stored, back)
    }

    @Test
    fun `the round trip also holds in a zone with daylight saving, away from the changeover`() {
        val zone = ZoneId.of("Europe/Amsterdam")
        for (wall in listOf(LocalDateTime.of(2026, 1, 15, 8, 0), LocalDateTime.of(2026, 7, 15, 23, 59, 59))) {
            val stored = wall.toInstant(ZoneOffset.UTC).toEpochMilli()
            val epoch = SyncTime.dbToEpoch(stored, zone)
            assertEquals(stored, SyncTime.epochToLocal(epoch, zone).toInstant(ZoneOffset.UTC).toEpochMilli())
        }
    }

    @Test
    fun `the sid map survives a restart and a damaged file does not block anything`() {
        val file = File(tmp.root, "sub/sync_state.json")
        val store = FileSyncStateStore(file)
        assertEquals(emptyMap<String, String>(), store.loadSidMap())
        store.saveSidMap(mapOf("LPaaaa" to "pl-1", "LPbbbb" to "pl-2"))
        assertEquals(mapOf("LPaaaa" to "pl-1", "LPbbbb" to "pl-2"), FileSyncStateStore(file).loadSidMap())
        store.saveSidMap(mapOf("LPaaaa" to "pl-9"))
        assertEquals(mapOf("LPaaaa" to "pl-9"), store.loadSidMap())
        assertFalse(File(file.path + ".tmp").exists())
        file.writeText("{ not json")
        assertEquals(emptyMap<String, String>(), store.loadSidMap())
    }

    private fun backupFor(dir: File, clock: () -> Long, db: File, settings: File) =
        SyncBackup(
            dir = dir,
            databaseFile = { db },
            settingsFile = settings,
            extraEntries = { mapOf("artist_name_aliases.json" to "{}".toByteArray()) },
            checkpoint = {},
            clock = clock,
            zone = ZoneId.of("UTC"),
        )

    @Test
    fun `the safety backup is a zip the existing restore can read and only the newest three are kept`() {
        val db = tmp.newFile("song.db").apply { writeText("DB-BYTES") }
        File(db.path + "-wal").writeText("WAL-BYTES")
        val settings = tmp.newFile("settings.preferences_pb").apply { writeText("SETTINGS") }
        val dir = File(tmp.root, "sync_backup")
        var now = java.time.Instant.parse("2026-11-05T10:00:00Z").toEpochMilli()
        val backup = backupFor(dir, { now }, db, settings)

        val made = ArrayList<File>()
        repeat(5) {
            made += backup.create()
            now += 2_000
        }
        assertEquals("before-sync-20261105-100000.zip", made.first().name)
        val left = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(
            listOf("before-sync-20261105-100004.zip", "before-sync-20261105-100006.zip", "before-sync-20261105-100008.zip"),
            left,
        )
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") })

        ZipFile(made.last()).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertEquals(setOf("settings.preferences_pb", "artist_name_aliases.json", "song.db", "song.db-wal"), names)
            assertEquals("DB-BYTES", zip.getInputStream(zip.getEntry("song.db")).readBytes().decodeToString())
            assertEquals("SETTINGS", zip.getInputStream(zip.getEntry("settings.preferences_pb")).readBytes().decodeToString())
        }
    }

    @Test
    fun `a failing backup leaves no half file behind`() {
        val dir = File(tmp.root, "sync_backup")
        val backup =
            SyncBackup(
                dir = dir,
                databaseFile = { null },
                settingsFile = File(tmp.root, "none"),
                extraEntries = { throw java.io.IOException("boom") },
                checkpoint = {},
            )
        try {
            backup.create()
            org.junit.Assert.fail("no error")
        } catch (_: java.io.IOException) {
        }
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }
}
