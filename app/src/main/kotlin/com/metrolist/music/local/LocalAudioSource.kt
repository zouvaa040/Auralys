/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.local

import android.content.ContentResolver
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Where the scanner gets the audio rows from. The real one is MediaStore; tests use a list. */
interface LocalAudioSource {
    /**
     * Reads every audio row that may be music and hands them over [batchSize] at a time, so a library of
     * thousands of files is never held in memory as one list.
     */
    suspend fun read(batchSize: Int, onBatch: suspend (List<LocalTrack>) -> Unit)
}

/** MediaStore audio on the shared storage (all volumes). Needs READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE. */
class MediaStoreAudioSource(
    private val resolver: ContentResolver,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : LocalAudioSource {
    internal fun projection(): Array<String> {
        val pathColumn =
            if (sdkInt >= Build.VERSION_CODES.Q) MediaStore.Audio.Media.RELATIVE_PATH else @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA
        return arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DISPLAY_NAME,
            pathColumn,
        )
    }

    /** The cheap part of the filter, done by MediaStore; folders and formats are checked afterwards in [LocalTrackRules]. */
    internal fun selection(): String {
        val clauses =
            mutableListOf(
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                "${MediaStore.Audio.Media.DURATION} >= ${LocalTrackRules.MIN_DURATION_MS}",
                "${MediaStore.Audio.Media.IS_ALARM} = 0",
                "${MediaStore.Audio.Media.IS_NOTIFICATION} = 0",
                "${MediaStore.Audio.Media.IS_RINGTONE} = 0",
            )
        if (sdkInt >= Build.VERSION_CODES.Q) clauses += "${MediaStore.Audio.Media.IS_PODCAST} = 0"
        return clauses.joinToString(" AND ")
    }

    override suspend fun read(batchSize: Int, onBatch: suspend (List<LocalTrack>) -> Unit) {
        val cursor =
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection(),
                selection(),
                null,
                "${MediaStore.Audio.Media._ID} ASC",
            ) ?: return
        cursor.use {
            val columns = Columns(it)
            val batch = ArrayList<LocalTrack>(batchSize)
            while (it.moveToNext()) {
                currentCoroutineContext().ensureActive()
                batch += columns.read(it)
                if (batch.size >= batchSize) {
                    onBatch(batch.toList())
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) onBatch(batch.toList())
        }
    }

    private class Columns(cursor: Cursor) {
        private val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        private val title = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
        private val artist = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
        private val album = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
        private val albumId = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
        private val duration = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
        private val added = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
        private val modified = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
        private val year = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR)
        private val displayName = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
        private val folder =
            cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH).takeIf { it >= 0 }
                ?: @Suppress("DEPRECATION") cursor.getColumnIndex(MediaStore.Audio.Media.DATA)

        private fun Cursor.stringAt(index: Int): String? = if (index < 0 || isNull(index)) null else getString(index)

        private fun Cursor.longAt(index: Int): Long? = if (index < 0 || isNull(index)) null else getLong(index)

        fun read(cursor: Cursor) =
            LocalTrack(
                mediaStoreId = cursor.getLong(id),
                title = cursor.stringAt(title),
                artist = cursor.stringAt(artist),
                album = cursor.stringAt(album),
                albumId = cursor.longAt(albumId)?.takeIf { it > 0L },
                durationMs = cursor.longAt(duration) ?: 0L,
                dateAddedSec = cursor.longAt(added) ?: 0L,
                dateModifiedSec = cursor.longAt(modified) ?: 0L,
                year = cursor.longAt(year)?.toInt(),
                displayName = cursor.stringAt(displayName),
                folder = cursor.stringAt(folder),
            )
    }
}
