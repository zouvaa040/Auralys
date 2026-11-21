/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.local

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import coil3.ImageLoader
import coil3.Uri
import coil3.annotation.InternalCoilApi
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.Dimension
import coil3.util.FetcherServiceLoaderTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.max
import kotlin.reflect.KClass

/**
 * Loads the cover of a song from the phone: `localaudio://<MediaStore id>`.
 *
 * Android 10+ asks the system for the file's thumbnail; older versions (and files the system has no thumbnail for)
 * read the picture embedded in the file. When there is none, a plain tile is drawn so lists never show a hole.
 */
class LocalCoverFetcher(
    private val mediaStoreId: Long,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult =
        withContext(Dispatchers.IO) {
            val side = targetSide(options.size.width, options.size.height)
            val bitmap = loadCover(options.context, mediaStoreId, side) ?: LocalCoverPlaceholder.render(mediaStoreId, side.coerceAtMost(256))
            ImageFetchResult(image = bitmap.asImage(), isSampled = false, dataSource = DataSource.DISK)
        }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(
            data: Uri,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher? {
            if (data.scheme != LocalMusic.COVER_SCHEME) return null
            val id = LocalMusic.mediaStoreIdOfCover(data.toString()) ?: return null
            return LocalCoverFetcher(id, options)
        }
    }

    companion object {
        private const val DEFAULT_SIDE = 512
        private const val MIN_SIDE = 96
        private const val MAX_SIDE = 1024

        /** The edge, in pixels, of the square to ask for: the larger requested side, within [MIN_SIDE]..[MAX_SIDE]. */
        internal fun sideFor(widthPx: Int?, heightPx: Int?): Int {
            val wanted = max(widthPx ?: 0, heightPx ?: 0)
            return if (wanted <= 0) DEFAULT_SIDE else wanted.coerceIn(MIN_SIDE, MAX_SIDE)
        }

        private fun targetSide(width: Dimension, height: Dimension): Int =
            sideFor((width as? Dimension.Pixels)?.px, (height as? Dimension.Pixels)?.px)

        private fun loadCover(context: Context, mediaStoreId: Long, side: Int): Bitmap? {
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaStoreId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    return context.contentResolver.loadThumbnail(uri, Size(side, side), null)
                } catch (e: Exception) {
                    // no thumbnail for this file: fall through to the embedded picture
                }
            }
            return embeddedPicture(context, uri, side)
        }

        private fun embeddedPicture(context: Context, uri: android.net.Uri, side: Int): Bitmap? {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(context, uri)
                val bytes = retriever.embeddedPicture ?: return null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= side && bounds.outHeight / (sample * 2) >= side) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            } catch (e: Exception) {
                Timber.tag("LocalCover").d(e, "No embedded picture")
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }
}

/** Registers the fetcher for every Coil image loader in the app (the widget and the share cards build their own). */
@OptIn(InternalCoilApi::class)
class LocalCoverFetcherTarget : FetcherServiceLoaderTarget<Uri> {
    override fun factory(): Fetcher.Factory<Uri> = LocalCoverFetcher.Factory()

    override fun type(): KClass<Uri> = Uri::class
}

/** A plain tile with a music note, in a colour taken from the song id, for songs without a picture. */
object LocalCoverPlaceholder {
    internal fun colorFor(seed: Long): Int {
        val hue = ((seed * 2654435761L) and 0xFFFFFFL) % 360L
        return Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.28f, 0.34f))
    }

    fun render(seed: Long, side: Int): Bitmap {
        val size = side.coerceAtLeast(16)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(colorFor(seed))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255) }
        val unit = size / 10f
        // note head, stem and flag
        canvas.drawOval(RectF(3.4f * unit, 6.0f * unit, 5.2f * unit, 7.4f * unit), paint)
        canvas.drawRect(RectF(4.9f * unit, 2.4f * unit, 5.3f * unit, 6.7f * unit), paint)
        canvas.drawRect(RectF(4.9f * unit, 2.4f * unit, 6.8f * unit, 3.2f * unit), paint)
        return bitmap
    }
}
