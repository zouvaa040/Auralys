/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.metrolist.music.ui.utils.resize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Covers kept for good, so they still show with no connection.
 *
 * The normal image cache only holds a picture at the exact size it was loaded at and drops old ones, so a
 * list opened offline asks for sizes it never loaded and gets blanks. This store keeps one medium-size copy
 * per cover, found by the cover's address without its size part.
 */
object OfflineArtworkStore {
    private const val DIR = "offline_artwork"
    private const val SAVE_SIZE = 544
    private const val MAX_FILE_BYTES = 3L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 150L * 1024 * 1024

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** The same cover at any size ("...=w226-h226-l90-rj" or "...=w60-h60") maps to one key. */
    internal fun keyOf(url: String): String = url.substringBefore('?').substringBefore('=')

    private fun fileFor(context: Context, url: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(keyOf(url).toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, DIR), hash)
    }

    fun find(context: Context, url: String): File? =
        fileFor(context, url).takeIf { it.isFile && it.length() > 0 }

    /** Downloads the cover unless it is already kept. Never throws. */
    suspend fun saveIfMissing(context: Context, url: String?) {
        if (url.isNullOrBlank() || !url.startsWith("http")) return
        val target = fileFor(context, url)
        if (target.isFile && target.length() > 0) return
        withContext(Dispatchers.IO) {
            try {
                target.parentFile?.mkdirs()
                client.newCall(Request.Builder().url(url.resize(SAVE_SIZE, SAVE_SIZE)).build()).execute().use { response ->
                    val body = response.body
                    if (!response.isSuccessful || body == null) return@use
                    // never read more than the limit into memory, whatever the address really returns
                    if (body.contentLength() > MAX_FILE_BYTES) return@use
                    val source = body.source()
                    source.request(MAX_FILE_BYTES + 1) // buffers at most the limit plus one byte, or less when the body is shorter
                    if (source.buffer.size == 0L || source.buffer.size > MAX_FILE_BYTES) return@use
                    val bytes = source.buffer.readByteArray()
                    val temp = File(target.parentFile, target.name + ".tmp")
                    temp.writeBytes(bytes)
                    if (!temp.renameTo(target)) temp.delete()
                }
                trim(context)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag("OfflineArtwork").d(e, "Could not keep cover")
            }
        }
    }

    /** Keeps the folder under the size limit by dropping the least recently written covers. */
    private fun trim(context: Context) {
        val files = File(context.filesDir, DIR).listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_TOTAL_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_TOTAL_BYTES * 9 / 10) break
            total -= file.length()
            file.delete()
        }
    }
}

/** When a cover cannot be loaded (no connection, bad address), falls back to the kept copy if there is one. */
class OfflineArtworkInterceptor(
    private val context: Context,
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val result = chain.proceed()
        if (result !is ErrorResult) return result
        val url = chain.request.data as? String ?: return result
        val kept = OfflineArtworkStore.find(context, url) ?: return result
        return chain.withRequest(chain.request.newBuilder().data(kept).build()).proceed()
    }
}
