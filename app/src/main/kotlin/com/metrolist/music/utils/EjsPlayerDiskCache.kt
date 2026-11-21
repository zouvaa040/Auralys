/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Disk store for the EJS solver's preprocessed YouTube player (innertubex `setPreprocessedPlayerCache`). One file per
 * player version (the key is a SHA-1 the library makes from the player URL and the solver bundle), written atomically,
 * the [MAX_FILES] newest kept. Every failure reads as "nothing cached": the solver then works from the full player as
 * before, it is never worse than without the store.
 *
 * Review 2026-11-12, finding 28: two extractions can solve the same player version at once (the current song plus a
 * download or the next song). They used one fixed `$key.tmp`, so their bytes could interleave, and the trim deleted the
 * other writer's temporary file in the middle of its write. Now every write has its own temporary name, writes are
 * serialised, and the trim only removes temporary files left over from a killed write (older than [STALE_TEMP_MS]).
 */
internal object EjsPlayerDiskCache {
    const val DIR_NAME = "innertubex_ejs"
    const val MAX_FILES = 3
    const val STALE_TEMP_MS = 60_000L
    private const val TEMP_SUFFIX = ".tmp"
    private val KEY = Regex("[0-9a-f]{40}")
    private val lock = Any()

    fun read(
        dir: File,
        key: String,
    ): String? {
        if (!KEY.matches(key)) return null
        return try {
            val file = File(dir, "$key.js")
            if (!file.isFile) return null
            file.setLastModified(System.currentTimeMillis())
            file.readText().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /** Stores [value] under [key]; a null value deletes it (the library found it unusable). */
    fun write(
        dir: File,
        key: String,
        value: String?,
    ) {
        if (!KEY.matches(key)) return
        try {
            synchronized(lock) {
                val file = File(dir, "$key.js")
                if (value == null) {
                    file.delete()
                    return
                }
                dir.mkdirs()
                // unique per write: a second writer of the same key never shares (or deletes) this file
                val tmp = File.createTempFile("$key.", TEMP_SUFFIX, dir)
                try {
                    tmp.writeText(value)
                    moveOver(tmp, file)
                } catch (e: Exception) {
                    tmp.delete()
                    throw e
                }
                trim(dir)
            }
        } catch (_: Exception) {
            // a full disk or a read-only folder: the solver keeps working without the store
        }
    }

    private fun moveOver(
        from: File,
        to: File,
    ) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    internal fun trim(
        dir: File,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val files = dir.listFiles { f -> f.name.endsWith(".js") }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_FILES).forEach { it.delete() }
        // only what a killed write left behind; a write in progress (another process, a test) is never touched
        dir.listFiles { f -> f.name.endsWith(TEMP_SUFFIX) && nowMs - f.lastModified() > STALE_TEMP_MS }?.forEach { it.delete() }
    }
}
