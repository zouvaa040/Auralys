/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import com.metrolist.music.playback.DownloadUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Copies a finished in-app download out of the (private, chunked) Media3 download cache into a real
 * audio file in the phone's public "Music/Auralys" folder, so other apps and file managers see it.
 *
 * The audio is copied byte for byte (no re-encoding). The title / artist / album go into the
 * MediaStore row, which is what music players and the gallery read; they are not written inside the
 * file itself.
 */
object DownloadExporter {
    private const val TAG = "DownloadExporter"
    private const val FOLDER = "Auralys"

    enum class Failure { NOT_DOWNLOADED, UNSUPPORTED, IO }

    class ExportException(val failure: Failure, cause: Throwable? = null) : Exception(failure.name, cause)

    suspend fun export(
        context: Context,
        downloadUtil: DownloadUtil,
        songId: String,
    ): Result<Uri> =
        withContext(Dispatchers.IO) {
            val song = downloadUtil.database.song(songId).first()
            val format = downloadUtil.database.format(songId).first()
            val length = format?.contentLength ?: -1L
            if (song == null || format == null || length <= 0L ||
                !downloadUtil.downloadCache.isCached(songId, 0, length)
            ) {
                return@withContext Result.failure(ExportException(Failure.NOT_DOWNLOADED))
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                // Scoped storage is what makes this safe without a storage permission.
                return@withContext Result.failure(ExportException(Failure.UNSUPPORTED))
            }

            val mime = format.mimeType.substringBefore(";").trim().ifBlank { "audio/mp4" }
            val extension =
                when {
                    mime.contains("mp4") || mime.contains("m4a") -> "m4a"
                    mime.contains("webm") -> "webm"
                    mime.contains("ogg") -> "ogg"
                    mime.contains("mpeg") -> "mp3"
                    else -> "m4a"
                }
            val artistText = song.artists.joinToString(", ") { it.name }
            val title = song.song.title
            val fileName =
                sanitize(if (artistText.isBlank()) title else "$artistText - $title") + ".$extension"

            val resolver = context.contentResolver
            val values =
                ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Audio.Media.MIME_TYPE, mime)
                    put(MediaStore.Audio.Media.TITLE, title)
                    if (artistText.isNotBlank()) put(MediaStore.Audio.Media.ARTIST, artistText)
                    song.album?.title?.let { put(MediaStore.Audio.Media.ALBUM, it) }
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/$FOLDER")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            // Some MediaStore builds (Samsung Android 16) reject "audio/webm" in the Music collection with an
            // IllegalArgumentException (crash report 2026-11-13). Then try without a MIME type, and last the
            // Downloads collection, which takes any file type.
            val target =
                runCatching { resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) }
                    .recoverCatching {
                        Timber.tag(TAG).w(it, "Music collection refused %s", mime)
                        resolver.insert(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            ContentValues(values).apply { remove(MediaStore.Audio.Media.MIME_TYPE) },
                        )
                    }.recoverCatching {
                        Timber.tag(TAG).w(it, "Music collection refused %s without a type", fileName)
                        resolver.insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            ContentValues().apply {
                                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                                put(MediaStore.Downloads.MIME_TYPE, mime)
                                put(MediaStore.Downloads.RELATIVE_PATH, "Download/$FOLDER")
                                put(MediaStore.Downloads.IS_PENDING, 1)
                            },
                        )
                    }.getOrElse { e ->
                        Timber.tag(TAG).e(e, "Exporting %s: no collection accepted the file", songId)
                        return@withContext Result.failure(ExportException(Failure.IO, e))
                    }
                    ?: return@withContext Result.failure(ExportException(Failure.IO))

            try {
                val dataSource =
                    CacheDataSource
                        .Factory()
                        .setCache(downloadUtil.downloadCache)
                        .createDataSource()
                val spec =
                    DataSpec
                        .Builder()
                        .setUri(Uri.parse("https://auralys.invalid/$songId"))
                        .setKey(songId)
                        .build()
                val input = DataSourceInputStream(dataSource, spec)
                val output =
                    resolver.openOutputStream(target)
                        ?: throw java.io.IOException("Cannot open the output file")
                input.use { source -> output.use { sink -> source.copyTo(sink) } }

                resolver.update(
                    target,
                    ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
                Result.success(target)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Exporting %s failed", songId)
                runCatching { resolver.delete(target, null, null) }
                Result.failure(ExportException(Failure.IO, e))
            }
        }

    private fun sanitize(name: String): String =
        name
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .trim()
            .trim('.')
            .take(120)
            .ifBlank { "Auralys" }
}
