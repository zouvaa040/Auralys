/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import com.metrolist.music.db.DatabaseDao
import com.metrolist.music.db.entities.LyricsEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The text a song's lyrics had before they were replaced, one small file per song in `files/lyrics_backup_434/`. The
 * source picker offers it as "Previous lyrics", so lyrics replaced by the 4.3.4 re-check (or by a refetch) can be taken
 * back, a hand edit of 4.3.3 included (it kept the source's name and looks like a found one). Kept: the first text a
 * song had before any replacement, unless a later one was made by hand. At most [MAX_FILES] songs and [MAX_TOTAL_BYTES]
 * on disk; the oldest files go first. Nothing else deletes them.
 */
class LyricsBackup(
    private val dir: File,
) {
    data class Saved(
        val provider: String,
        val text: String,
    )

    fun fileOf(id: String): File = File(dir, fileName(id))

    fun exists(id: String): Boolean = fileOf(id).isFile

    fun read(id: String): Saved? =
        synchronized(this) {
            runCatching {
                val file = fileOf(id)
                if (!file.isFile) return null
                val content = file.readText()
                if (!content.startsWith(HEADER + "\n")) return null
                val rest = content.substring(HEADER.length + 1)
                val newline = rest.indexOf('\n')
                if (newline < 0) return null
                Saved(provider = rest.substring(0, newline), text = rest.substring(newline + 1)).takeIf { it.text.isNotBlank() }
            }.getOrNull()
        }

    /** False when the text is too long to keep or the disk refused it. */
    fun write(
        id: String,
        provider: String,
        text: String,
    ): Boolean =
        synchronized(this) {
            if (text.isBlank() || text.length > MAX_CHARS) return false
            runCatching {
                dir.mkdirs()
                val file = fileOf(id)
                val tmp = File(dir, file.name + ".tmp")
                tmp.writeText(HEADER + "\n" + provider.replace('\n', ' ') + "\n" + text)
                if (!tmp.renameTo(file)) {
                    file.delete()
                    if (!tmp.renameTo(file)) return false
                }
                trim()
                true
            }.getOrDefault(false)
        }

    private fun trim() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        files.forEachIndexed { index, f ->
            total += f.length()
            if (index >= MAX_FILES || total > MAX_TOTAL_BYTES) f.delete()
        }
    }

    companion object {
        const val DIR = "lyrics_backup_434"
        const val MAX_FILES = 400
        const val MAX_CHARS = 200_000
        const val MAX_TOTAL_BYTES = 8L * 1024 * 1024
        private const val HEADER = "auralys-lyrics-backup 1"
        private val PLAIN_ID = Regex("""[A-Za-z0-9_-]{1,120}""")

        /** A YouTube id as it is; anything else (a file id from the phone) hex encoded, so it is always a safe name. */
        fun fileName(id: String): String =
            if (PLAIN_ID.matches(id)) "$id.txt" else "x_" + id.toByteArray(Charsets.UTF_8).take(100).joinToString("") { "%02x".format(it) } + ".txt"
    }
}

/**
 * The one place where a lookup's outcome is written to a song's lyrics row, so every caller follows the same rules:
 *  - "could not ask" ([LyricsLookup.NoConnection]) is never written;
 *  - "not found" is written only where there were no lyrics; found lyrics are never replaced by "not found";
 *  - [Mode.FILL] (a song without lyrics yet) does not overwrite lyrics someone stored meanwhile;
 *  - before found lyrics replace other lyrics, the old text goes to [LyricsBackup].
 */
class LyricsStore(
    private val dao: DatabaseDao,
    private val backup: LyricsBackup,
    private val isHandMade: suspend (String) -> Boolean,
    private val onReplacedByFound: suspend (String) -> Unit = {},
) {
    enum class Mode {
        /** The song has no lyrics row yet (or "not found"): the automatic lookup fills it. */
        FILL,

        /** The 4.3.4 re-check of lyrics an older version stored. */
        RECHECK,

        /** The user asked for it: Refetch, "Automatic" in the picker. */
        REPLACE,
    }

    enum class Result {
        /** Found lyrics were written. */
        STORED,

        /** "Not found" was written (the song had no row). */
        STORED_NOT_FOUND,

        /** The row already holds this. */
        SAME,

        /** The row was left as it was: it has lyrics and the lookup found none, or (FILL) lyrics came meanwhile. */
        KEPT,

        /** Nothing written: the sources could not be reached. */
        NO_CONNECTION,
    }

    suspend fun current(id: String): LyricsEntity? = withContext(Dispatchers.IO) { dao.lyrics(id).first() }

    suspend fun apply(
        id: String,
        outcome: LyricsLookup,
        mode: Mode,
    ): Result =
        withContext(Dispatchers.IO) {
            val row = dao.lyrics(id).first()
            when (outcome) {
                LyricsLookup.NoConnection -> Result.NO_CONNECTION
                LyricsLookup.NotFound ->
                    when {
                        row == null -> {
                            dao.upsert(LyricsEntity(id, LyricsEntity.LYRICS_NOT_FOUND, ""))
                            Result.STORED_NOT_FOUND
                        }
                        row.lyrics == LyricsEntity.LYRICS_NOT_FOUND -> Result.SAME
                        else -> Result.KEPT
                    }
                is LyricsLookup.Found -> {
                    val found = outcome.lyrics
                    when {
                        row != null && row.lyrics == found.lyrics && row.provider == found.provider -> Result.SAME
                        mode == Mode.FILL && row != null && hasText(row) -> Result.KEPT
                        else -> {
                            write(id, found.lyrics, found.provider, row, byHand = false)
                            Result.STORED
                        }
                    }
                }
            }
        }

    /** Writes [lyrics] as the song's lyrics (a pick in the picker, a search result, "Previous lyrics"). */
    suspend fun put(
        id: String,
        lyrics: String,
        provider: String,
        byHand: Boolean,
    ) {
        withContext(Dispatchers.IO) { write(id, lyrics, provider, dao.lyrics(id).first(), byHand) }
    }

    private suspend fun write(
        id: String,
        lyrics: String,
        provider: String,
        row: LyricsEntity?,
        byHand: Boolean,
    ) {
        if (row != null && hasText(row) && row.lyrics.trim() != lyrics.trim()) {
            // the first text is kept; a later one only when the user made it
            if (!backup.exists(id) || row.provider.equals("Manual", ignoreCase = true) || isHandMade(id)) {
                backup.write(id, row.provider, row.lyrics)
            }
        }
        dao.upsert(LyricsEntity(id, lyrics, provider))
        if (!byHand) onReplacedByFound(id)
    }

    companion object {
        fun hasText(row: LyricsEntity): Boolean = row.lyrics.isNotBlank() && row.lyrics != LyricsEntity.LYRICS_NOT_FOUND
    }
}
