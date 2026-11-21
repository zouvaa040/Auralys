/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.poster

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.music.ui.theme.extractCoverTint
import com.metrolist.music.ui.utils.resize
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Why a picture could not be opened (the screen shows a short message for each). */
class PosterLoadException(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason { UNREADABLE, TOO_BIG, MEMORY }
}

/**
 * Opening the user's photo (from the Android Photo Picker: no storage permission) and their sticker pictures: EXIF orientation applied, the long side brought
 * down to [maxSide] (2048, 1600 on a low-memory phone) so a 50 MP photo never sits in memory whole, decoded with a power-of-two sample size first.
 */
object PosterPhotoLoad {
    const val MAX_SIDE = 2048
    const val MAX_SIDE_LOW_RAM = 1600
    const val STICKER_SIDE = 1024

    fun maxSideFor(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return if (am?.isLowRamDevice == true || (am?.memoryClass ?: 256) < 192) MAX_SIDE_LOW_RAM else MAX_SIDE
    }

    /**
     * The power-of-two sample size that keeps the decoded long side at least about [maxSide] (95 percent of it; the exact scale follows).
     * The 5 percent let a 4000 px or 8160 px photo decode straight at 2000 / 2040 px instead of at twice that size (review item 32).
     */
    fun sampleSize(w: Int, h: Int, maxSide: Int): Int {
        var s = 1
        val enough = maxSide * 0.95f
        while (max(w, h) / (s * 2f) >= enough) s *= 2
        return s
    }

    /** The size after the long side is brought down to [maxSide] (never up). */
    fun fitted(w: Int, h: Int, maxSide: Int): Pair<Int, Int> {
        val m = max(w, h)
        if (m <= maxSide) return w to h
        val k = maxSide.toFloat() / m
        return max(1, (w * k).roundToInt()) to max(1, (h * k).roundToInt())
    }

    /** The EXIF orientation as a matrix (rotation and mirroring), identity when there is none. */
    fun orientationMatrix(orientation: Int): Matrix {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
        }
        return m
    }

    /** Decodes [uri] upright, long side at most [maxSide], as a software ARGB_8888 bitmap (alpha kept). */
    fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // a bounds-only decode returns null by design: only a missing stream is an error here
        (cr.openInputStream(uri) ?: throw PosterLoadException(PosterLoadException.Reason.UNREADABLE)).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw PosterLoadException(PosterLoadException.Reason.UNREADABLE)
        if (bounds.outWidth.toLong() * bounds.outHeight > 400_000_000L) throw PosterLoadException(PosterLoadException.Reason.TOO_BIG)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }
        val raw =
            try {
                cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            } catch (e: OutOfMemoryError) {
                throw PosterLoadException(PosterLoadException.Reason.MEMORY, e)
            } ?: throw PosterLoadException(PosterLoadException.Reason.UNREADABLE)
        val orientation =
            runCatching { cr.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }.getOrNull()
                ?: ExifInterface.ORIENTATION_NORMAL
        return upright(raw, orientation, maxSide)
    }

    /** [raw] turned by the EXIF [orientation] and scaled so its long side is at most [maxSide]. Recycles [raw] when a new bitmap was made. */
    fun upright(raw: Bitmap, orientation: Int, maxSide: Int): Bitmap {
        val m = orientationMatrix(orientation)
        val (fw, fh) = fitted(raw.width, raw.height, maxSide)
        val k = fw.toFloat() / raw.width
        if (k < 1f) m.postScale(k, k)
        val soft = raw.config == Bitmap.Config.ARGB_8888
        if (m.isIdentity && soft) return raw
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (out !== raw) raw.recycle()
        return if (out.config == Bitmap.Config.ARGB_8888) out else out.copy(Bitmap.Config.ARGB_8888, false)
    }

    /** The photo as opaque ARGB ints (a see-through pixel becomes black, as it would look on the poster). */
    fun toImage(b: Bitmap): PosterImage {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        for (i in px.indices) px[i] = px[i] or (0xFF shl 24)
        return PosterImage(b.width, b.height, px)
    }
}

/** Loading a song's cover for a card: a software bitmap of at most 544 px and its tint (the same Coil road as the rest of the app). */
object PosterCovers {
    suspend fun load(context: Context, url: String?): Pair<Bitmap, androidx.compose.ui.graphics.Color?>? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            val request = ImageRequest.Builder(context).data(url.resize(544, 544)).size(544, 544).allowHardware(false).build()
            val bmp = context.imageLoader.execute(request).image?.toBitmap() ?: return null
            val soft = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
            soft to runCatching { soft.extractCoverTint() }.getOrNull()
        }.getOrNull()
    }
}

/** Saving the poster to the gallery (Pictures/Auralys) and handing it to the share sheet. */
object PosterSave {
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private const val SHARE_KEEP_MS = 60L * 60 * 1000

    fun extension(format: PosterFormat): String = if (format == PosterFormat.JPEG) "jpg" else "png"

    fun mime(format: PosterFormat): String = if (format == PosterFormat.JPEG) "image/jpeg" else "image/png"

    fun fileName(now: Date = Date(), format: PosterFormat = PosterFormat.PNG): String = "Auralys-Poster-" + stamp.format(now) + "." + extension(format)

    /** Writes [bitmap] as PNG (lossless) or JPEG at [quality] 50..100 (the poster is opaque, so nothing is lost to the missing alpha). */
    internal fun encode(bitmap: Bitmap, format: PosterFormat, quality: Int, out: java.io.OutputStream) {
        val ok =
            if (format == PosterFormat.JPEG) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 100), out)
            } else {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        if (!ok) throw IllegalStateException(format.name)
    }

    /** True when saving needs the old storage permission first (Android 8 and 9). */
    fun needsLegacyPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** Writes the PNG to Pictures/Auralys. Android 10+: MediaStore, no permission. Android 8-9: the public folder (WRITE_EXTERNAL_STORAGE, asked by the screen). */
    fun saveToGallery(context: Context, bitmap: Bitmap, format: PosterFormat = PosterFormat.PNG, quality: Int = 92): Uri {
        val name = fileName(format = format)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime(format))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Auralys")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val cr = context.contentResolver
            val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("MediaStore refused the poster")
            try {
                cr.openOutputStream(uri)?.use { encode(bitmap, format, quality, it) } ?: throw IllegalStateException("no stream")
                cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (t: Throwable) {
                runCatching { cr.delete(uri, null, null) }
                throw t
            }
            return uri
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Auralys")
        if (!dir.isDirectory && !dir.mkdirs()) throw IllegalStateException("cannot make $dir")
        val file = File(dir, name)
        FileOutputStream(file).use { encode(bitmap, format, quality, it) }
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime(format)), null)
        return Uri.fromFile(file)
    }

    /**
     * Writes the PNG to the app's cache (posters/) and returns a share intent through the app's FileProvider. Posters shared earlier stay an
     * hour: the app they went to may still be reading one (review item 32: they used to be deleted at the next share).
     */
    fun shareIntent(context: Context, bitmap: Bitmap, format: PosterFormat = PosterFormat.PNG, quality: Int = 92): Intent {
        val dir = File(context.cacheDir, "posters").apply { mkdirs() }
        val old = System.currentTimeMillis() - SHARE_KEEP_MS
        dir.listFiles()?.forEach { if (it.lastModified() < old) it.delete() }
        val ext = "." + extension(format)
        var file = File(dir, fileName(format = format))
        var n = 1
        while (file.exists()) file = File(dir, fileName(format = format).removeSuffix(ext) + "-" + n++ + ext)
        try {
            FileOutputStream(file).use { encode(bitmap, format, quality, it) }
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime(format)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

/** One picture of the user's sticker library: [key] = file name, [thumb] = a small copy for the grid. */
class StickerLibItem(val key: String, val thumb: Bitmap)

/**
 * The user's own stickers ("Stiker saya"), kept as PNG files in the app's private storage (files/poster_stickers, at most 60, newest first),
 * long side at most 1024, so they can be used on the next poster too. Same rules as the Windows library.
 */
class PosterStickerLibrary(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "poster_stickers")

    /** Round 4.3.5: the picture each sticker was made from and its mask, so "Ubah" can edit it again from the original. */
    private val origDir = File(dir, "orig")
    private var counter = 0

    fun list(): List<String> = (dir.listFiles { f -> f.isFile && f.name.matches(KEY) }?.map { it.name } ?: emptyList()).sortedDescending()

    /** Saves a picture (scaled to at most 1024 px) and returns its key, dropping the oldest beyond [MAX]. */
    fun add(bitmap: Bitmap): String {
        dir.mkdirs()
        val key = String.format(Locale.US, "s%013d%04d.png", System.currentTimeMillis(), (counter++ % 10000))
        PosterSessionFiles.atomically(File(dir, key)) { out -> if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IllegalStateException("PNG") }
        list().drop(MAX).forEach { remove(it) }
        return key
    }

    /** Writes a new picture for an existing [key] (the sticker was edited again). False when the key is not a library key. */
    fun replace(key: String, bitmap: Bitmap): Boolean {
        if (!key.matches(KEY)) return false
        dir.mkdirs()
        PosterSessionFiles.atomically(File(dir, key)) { out -> if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IllegalStateException("PNG") }
        return true
    }

    private fun origPicture(key: String, png: Boolean) = File(origDir, key.removeSuffix(".png") + if (png) ".png" else ".jpg")

    private fun origMask(key: String) = File(origDir, key.removeSuffix(".png") + ".mask")

    /**
     * Keeps the picture [key] was made from ([argb], unpremultiplied, [w] x [h]: a JPEG when it is opaque, a PNG when it has alpha) and the
     * [mask] the user ended with. [picture] false = only the mask (the picture is there from an earlier edit).
     */
    fun saveOriginal(key: String, argb: IntArray, w: Int, h: Int, mask: ByteArray, picture: Boolean = true) {
        if (!key.matches(KEY)) return
        origDir.mkdirs()
        if (picture || (!origPicture(key, true).isFile && !origPicture(key, false).isFile)) {
            val opaque = argb.all { (it ushr 24) == 255 }
            val b = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
            try {
                origPicture(key, !opaque).let { f ->
                    PosterSessionFiles.atomically(f) { out -> if (!b.compress(if (opaque) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 92, out)) throw IllegalStateException("original") }
                }
                origPicture(key, opaque).delete()
            } finally {
                b.recycle()
            }
        }
        PosterSessionFiles.writeMaskFile(origMask(key), w, h, mask)
    }

    /** The picture [key] was made from and the mask it ended with (null when none was kept); else the sticker itself (its alpha as the mask). */
    fun loadForEdit(key: String): Pair<Bitmap, ByteArray?>? {
        if (!key.matches(KEY)) return null
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        for (png in listOf(true, false)) {
            val f = origPicture(key, png)
            if (!f.isFile) continue
            val b = runCatching { BitmapFactory.decodeFile(f.absolutePath, opts) }.getOrNull() ?: continue
            return b to PosterSessionFiles.readMaskFile(origMask(key), b.width, b.height)
        }
        return load(key)?.let { it to null }
    }

    fun remove(key: String) {
        if (!key.matches(KEY)) return
        File(dir, key).delete()
        origPicture(key, true).delete()
        origPicture(key, false).delete()
        origMask(key).delete()
    }

    /** The full picture of [key] (software, alpha kept), or null. */
    fun load(key: String): Bitmap? {
        if (!key.matches(KEY)) return null
        val f = File(dir, key)
        if (!f.isFile) return null
        return runCatching { BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) }.getOrNull()
    }

    fun thumb(key: String, side: Int = 160): Bitmap? {
        val full = load(key) ?: return null
        val (w, h) = PosterPhotoLoad.fitted(full.width, full.height, side)
        if (w == full.width && h == full.height) return full
        val t = Bitmap.createScaledBitmap(full, w, h, true)
        if (t !== full) full.recycle()
        return t
    }

    companion object {
        const val MAX = 60
        val KEY = Regex("s\\d{17}\\.png")
    }
}

/** Draws [src] into a new software bitmap of [w] x [h] (used to make a sticker's picture at most 1024 px). */
internal fun scaledCopy(src: Bitmap, w: Int, h: Int): Bitmap {
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(src, null, android.graphics.Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
    return out
}
