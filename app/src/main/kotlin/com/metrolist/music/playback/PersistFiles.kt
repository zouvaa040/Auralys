/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The saved queue, its cursor, the automix list and the player state, written so that a kill in the middle of a write
 * (swipe-away with "stop on task clear", an OOM kill, a battery saver) never leaves a broken file behind.
 *
 * Before (review 2026-11-12, finding 3) every file was truncated and rewritten in place; the queue can now hold 5 000
 * songs, so the window in which a kill broke it grew about tenfold, and the next start deleted the queue, the cursor and
 * the player state together. Now each file is written to a temporary file with a unique name in the same folder, synced,
 * and renamed over the old one in one step: a reader sees the old file or the new one, never half of one.
 */
internal object PersistFiles {
    const val TEMP_SUFFIX = ".tmp"

    /** A temporary file older than this is left over from a write that was killed (a running write takes milliseconds). */
    const val STALE_TEMP_MS = 60_000L

    sealed interface ReadResult<out T> {
        /** No such file: nothing was saved (or it was cleared). */
        data object Missing : ReadResult<Nothing>

        data class Ok<T>(
            val value: T,
        ) : ReadResult<T>

        /** The file is there but cannot be read (another class version, a damaged disk): only this file is dropped. */
        data class Broken(
            val error: Throwable,
        ) : ReadResult<Nothing>
    }

    /** Reads the object in [file]; [cast] checks its type (a ClassCastException makes the file [ReadResult.Broken]). */
    fun <T> read(
        file: File,
        cast: (Any?) -> T,
    ): ReadResult<T> {
        if (!file.isFile) return ReadResult.Missing
        return try {
            // the file stream is closed even when the object stream's constructor throws (a broken header): it used to
            // stay open until the next garbage collection
            val value =
                FileInputStream(file).use { fis ->
                    ObjectInputStream(BufferedInputStream(fis)).use { input -> cast(input.readObject()) }
                }
            ReadResult.Ok(value)
        } catch (e: Exception) {
            ReadResult.Broken(e)
        } catch (e: StackOverflowError) {
            ReadResult.Broken(e)
        }
    }

    /**
     * Writes [value] to [file] atomically. On failure the old file is untouched, the temporary file is removed and the
     * error is thrown to the caller (which logs it). [sync] forces the bytes to the disk before the rename, so a power
     * cut cannot leave the new name pointing to an empty file.
     */
    fun write(
        file: File,
        value: Any,
        sync: Boolean = true,
    ) {
        val dir = file.absoluteFile.parentFile ?: throw IOException("no folder for $file")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("cannot create $dir")
        // unique per writer: two saves at once (a periodic save and the one in onDestroy) never share a temporary file
        val tmp = File.createTempFile(file.name + ".", TEMP_SUFFIX, dir)
        try {
            FileOutputStream(tmp).use { fos ->
                val out = ObjectOutputStream(BufferedOutputStream(fos))
                out.writeObject(value)
                out.flush()
                if (sync) {
                    try {
                        fos.fd.sync()
                    } catch (_: IOException) {
                        // some file systems refuse sync: the rename below is still atomic
                    }
                }
                out.close()
            }
            moveOver(tmp, file)
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    /** Removes temporary files of [names] in [dir] that a killed write left behind (never one that is being written). */
    fun sweepTemps(
        dir: File,
        names: Collection<String>,
        nowMs: Long = System.currentTimeMillis(),
        staleMs: Long = STALE_TEMP_MS,
    ) {
        val leftovers =
            dir.listFiles { f ->
                f.isFile &&
                    f.name.endsWith(TEMP_SUFFIX) &&
                    names.any { f.name.startsWith("$it.") } &&
                    nowMs - f.lastModified() > staleMs
            } ?: return
        leftovers.forEach { it.delete() }
    }

    private fun moveOver(
        from: File,
        to: File,
    ) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            // same folder, so this does not happen on Android; rename(2) replaces the target atomically anyway
            if (!from.renameTo(to)) {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
