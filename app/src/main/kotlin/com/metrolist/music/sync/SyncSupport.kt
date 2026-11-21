/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Small pieces of the sync that need no database and no Android: time conversion, the sid map file and the safety
 * backup zip.
 */

package com.metrolist.music.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The app stores a time as the device's wall clock written as if it were UTC (Converters.kt: LocalDateTime.now() is
 * saved with ZoneOffset.UTC). The sync speaks real epoch milliseconds, so these convert with the device's zone.
 */
object SyncTime {
    /** Stored database value -> real epoch milliseconds. */
    fun dbToEpoch(stored: Long, zone: ZoneId): Long =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(stored), ZoneOffset.UTC).atZone(zone).toInstant().toEpochMilli()

    /** Real epoch milliseconds -> the LocalDateTime the app would have stored at that moment. */
    fun epochToLocal(epochMs: Long, zone: ZoneId): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone)
}

/** `localPlaylistId -> sid` in a small JSON file next to the app data. Written atomically. */
class FileSyncStateStore(private val file: File) : SyncStateStore {
    override fun loadSidMap(): Map<String, String> =
        try {
            if (!file.exists()) {
                emptyMap()
            } else {
                val root = SyncJson.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
                val sid = root["sid"] as? JsonObject ?: JsonObject(emptyMap())
                sid.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { key to it } }.toMap()
            }
        } catch (e: Exception) {
            // A damaged file must not block syncing; the name fallback of the merge finds the copies again.
            emptyMap()
        }

    override fun saveSidMap(map: Map<String, String>) {
        file.absoluteFile.parentFile?.mkdirs()
        val text =
            buildJsonObject {
                put("v", 1)
                put("sid", buildJsonObject { map.forEach { (local, sid) -> put(local, sid) } })
            }.toString()
        val temp = File(file.absolutePath + ".tmp")
        temp.writeText(text, Charsets.UTF_8)
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * The safety backup: the same zip the Backup feature writes (settings, artist aliases, the Room database with its WAL),
 * so the existing Restore feature can read it. Keeps the newest [keep] files.
 */
class SyncBackup(
    private val dir: File,
    private val databaseFile: () -> File?,
    private val settingsFile: File,
    private val extraEntries: () -> Map<String, ByteArray>,
    private val checkpoint: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val keep: Int = 3,
) {
    fun create(): File {
        dir.mkdirs()
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(SyncTime.epochToLocal(clock(), zone))
        val target = File(dir, "before-sync-$stamp.zip")
        val partial = File(dir, "before-sync-$stamp.zip.part")
        try {
            checkpoint()
            ZipOutputStream(partial.outputStream().buffered()).use { zip ->
                if (settingsFile.exists()) addFile(zip, SETTINGS_ENTRY, settingsFile)
                extraEntries().forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                databaseFile()?.let { db ->
                    addFile(zip, DB_ENTRY, db)
                    File(db.path + "-wal").takeIf { it.exists() }?.let { addFile(zip, "$DB_ENTRY-wal", it) }
                    File(db.path + "-shm").takeIf { it.exists() }?.let { addFile(zip, "$DB_ENTRY-shm", it) }
                }
            }
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            partial.delete()
            throw e
        }
        prune()
        return target
    }

    private fun addFile(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        FileInputStream(file).use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun prune() {
        val backups = dir.listFiles { f -> f.name.startsWith("before-sync-") && f.name.endsWith(".zip") }.orEmpty()
        backups.sortedByDescending { it.name }.drop(keep).forEach { it.delete() }
    }

    companion object {
        const val SETTINGS_ENTRY = "settings.preferences_pb"
        const val DB_ENTRY = "song.db"
    }
}
