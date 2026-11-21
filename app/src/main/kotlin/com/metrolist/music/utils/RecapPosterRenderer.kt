/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Renders the shareable 1080x1920 Recap poster (period, minutes, top 5 songs, top 5 artists, Auralys mark)
 * and hands it to the system share sheet, using the same drawing helpers and share flow as [SongShareCard].
 */

package com.metrolist.music.utils

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.withClip
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.music.R
import com.metrolist.music.ui.utils.resize
import com.metrolist.music.viewmodels.RecapData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.text.NumberFormat

object RecapPosterRenderer {
    private const val W = 1080
    private const val H = 1920
    private const val FALLBACK_ACCENT = ShareCardColors.ACCENT

    private suspend fun load(context: Context, url: String?, size: Int): Bitmap? {
        if (url.isNullOrBlank()) return null
        return try {
            context.imageLoader
                .execute(
                    ImageRequest
                        .Builder(context)
                        .data(url.resize(size, size))
                        .size(size)
                        .allowHardware(false)
                        .build(),
                ).image
                ?.toBitmap()
        } catch (_: Exception) {
            null
        }
    }

    suspend fun create(
        context: Context,
        data: RecapData,
        periodTitle: String,
    ): Bitmap {
        val songs = data.songs.take(5)
        val artists = data.artists.take(5)
        val (hero, songCovers, artistCovers) =
            coroutineScope {
                val heroJob = async { load(context, songs.firstOrNull()?.thumbnailUrl, 720) }
                val songJobs = songs.map { s -> async { load(context, s.thumbnailUrl, 226) } }
                val artistJobs = artists.map { a -> async { load(context, a.thumbnailUrl, 226) } }
                Triple(heroJob.await(), songJobs.awaitAll(), artistJobs.awaitAll())
            }

        return withContext(Dispatchers.Default) {
            val bitmap = createBitmap(W, H)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            val accent =
                hero?.let { c ->
                    runCatching {
                        val p = Palette.from(c).maximumColorCount(12).generate()
                        p.getVibrantColor(p.getDominantColor(FALLBACK_ACCENT))
                    }.getOrNull()
                } ?: FALLBACK_ACCENT

            // ---- background: dark base, very soft blurred cover, coloured glows
            val bgRect = RectF(0f, 0f, W.toFloat(), H.toFloat())
            paint.color = ShareCardColors.BASE
            canvas.drawRect(bgRect, paint)
            if (hero != null) {
                canvas.drawBitmap(LyricsCardRenderer.softBackdrop(hero), null, bgRect, imagePaint)
                paint.color = 0xA6000000.toInt()
                canvas.drawRect(bgRect, paint)
            }
            paint.shader = RadialGradient(0f, 0f, W * 1.2f, withAlpha(accent, 160), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(bgRect, paint)
            paint.shader = RadialGradient(W.toFloat(), H * 0.9f, W * 1.1f, withAlpha(accent, 90), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(bgRect, paint)
            paint.shader = null

            val bold = LyricsCardRenderer.typeface(context, LyricsCardFont.GEOMETRIC, 700)
            val regular = LyricsCardRenderer.typeface(context, LyricsCardFont.GEOMETRIC, 450)
            val display = LyricsCardRenderer.typeface(context, LyricsCardFont.EDITORIAL, 600)
            val nf = NumberFormat.getIntegerInstance()
            val left = 96f
            val contentWidth = W - left * 2

            // ---- header
            val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xCCFFFFFF.toInt()
                textSize = 34f
                typeface = bold
                letterSpacing = 0.18f
            }
            canvas.drawText(context.getString(R.string.auralys_recap_poster_label), left, 190f, labelPaint)
            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 112f
                typeface = display
            }
            canvas.drawText(ellipsize(periodTitle, titlePaint, contentWidth), left, 320f, titlePaint)

            val minutesPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 168f
                typeface = bold
            }
            canvas.drawText(nf.format(data.totalMinutes), left - 6f, 500f, minutesPaint)
            val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xD9FFFFFF.toInt()
                textSize = 40f
                typeface = regular
            }
            canvas.drawText(
                ellipsize(
                    context.getString(R.string.auralys_recap_minutes_listened) + "  ·  " +
                        context.getString(R.string.auralys_recap_songs_count, nf.format(data.songCount)),
                    subPaint,
                    contentWidth,
                ),
                left,
                566f,
                subPaint,
            )

            // ---- glass panel holding both lists
            val panel = RectF(64f, 630f, W - 64f, 1760f)
            paint.style = Paint.Style.FILL
            paint.color = 0x1AFFFFFF
            canvas.drawRoundRect(panel, 64f, 64f, paint)
            paint.shader = LinearGradient(
                panel.left, panel.top, panel.right, panel.bottom,
                intArrayOf(0x99FFFFFF.toInt(), 0x0DFFFFFF, 0x59FFFFFF),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f
            canvas.drawRoundRect(panel, 64f, 64f, paint)
            paint.shader = null
            paint.style = Paint.Style.FILL

            val sectionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 44f
                typeface = bold
            }
            val rankPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 40f
                typeface = bold
            }
            val rowTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 38f
                typeface = bold
            }
            val rowSubPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xB3FFFFFF.toInt()
                textSize = 30f
                typeface = regular
            }
            val inner = panel.left + 48f
            val innerRight = panel.right - 48f

            // songs
            canvas.drawText(context.getString(R.string.auralys_recap_top_songs), inner, panel.top + 86f, sectionPaint)
            var y = panel.top + 116f
            val songCover = 84f
            songs.forEachIndexed { i, s ->
                rankPaint.color = if (i == 0) withAlpha(lighten(accent), 255) else 0x99FFFFFF.toInt()
                canvas.drawText("${i + 1}", inner, y + songCover / 2f + 14f, rankPaint)
                val c = RectF(inner + 56f, y, inner + 56f + songCover, y + songCover)
                drawCover(canvas, songCovers.getOrNull(i), c, 18f, circle = false, paint = paint, imagePaint = imagePaint, accent = accent)
                val tx = c.right + 28f
                val tw = innerRight - tx
                canvas.drawText(ellipsize(s.title, rowTitlePaint, tw), tx, y + 38f, rowTitlePaint)
                canvas.drawText(ellipsize(s.artist, rowSubPaint, tw), tx, y + 76f, rowSubPaint)
                y += 100f
            }

            // artists
            y = panel.top + 116f + songs.size * 100f + 28f
            canvas.drawText(context.getString(R.string.auralys_recap_top_artists), inner, y + 40f, sectionPaint)
            y += 72f
            val avatar = 68f
            artists.forEachIndexed { i, a ->
                rankPaint.color = if (i == 0) withAlpha(lighten(accent), 255) else 0x99FFFFFF.toInt()
                canvas.drawText("${i + 1}", inner, y + avatar / 2f + 14f, rankPaint)
                val c = RectF(inner + 56f, y, inner + 56f + avatar, y + avatar)
                drawCover(canvas, artistCovers.getOrNull(i), c, avatar / 2f, circle = true, paint = paint, imagePaint = imagePaint, accent = accent)
                val tx = c.right + 28f
                val minutes = context.getString(R.string.auralys_recap_minutes_short, nf.format(a.minutes))
                val mw = rowSubPaint.measureText(minutes)
                canvas.drawText(minutes, innerRight - mw, y + avatar / 2f + 11f, rowSubPaint)
                canvas.drawText(ellipsize(a.name, rowTitlePaint, innerRight - tx - mw - 24f), tx, y + avatar / 2f + 13f, rowTitlePaint)
                y += 76f
            }

            // ---- footer: Auralys mark + name
            val logo = ContextCompat.getDrawable(context, R.drawable.app_logo)
            val logoSize = 84
            val logoTop = 1802f
            if (logo != null) {
                val logoBmp = logo.toBitmap(logoSize * 2, logoSize * 2)
                canvas.drawBitmap(logoBmp, null, RectF(left, logoTop, left + logoSize, logoTop + logoSize), imagePaint)
            }
            val brandPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 50f
                typeface = bold
            }
            canvas.drawText("Auralys", left + logoSize + 22f, logoTop + logoSize / 2f + 17f, brandPaint)

            bitmap
        }
    }

    /** Renders the poster, stores it with [ComposeToImage.saveBitmapAsFile] and opens the share sheet. */
    suspend fun share(
        context: Context,
        data: RecapData,
        periodTitle: String,
    ) {
        val image = create(context, data, periodTitle)
        val uri =
            withContext(Dispatchers.IO) {
                ComposeToImage.saveBitmapAsFile(context, image, "auralys_recap_${System.currentTimeMillis()}")
            }
        context.startActivity(
            Intent
                .createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    context.getString(R.string.auralys_recap_share),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun drawCover(
        canvas: Canvas,
        bmp: Bitmap?,
        rect: RectF,
        radius: Float,
        circle: Boolean,
        paint: Paint,
        imagePaint: Paint,
        accent: Int,
    ) {
        val clip = Path().apply {
            if (circle) addOval(rect, Path.Direction.CW) else addRoundRect(rect, radius, radius, Path.Direction.CW)
        }
        if (bmp != null) {
            canvas.withClip(clip) {
                val s = minOf(bmp.width, bmp.height)
                val src = Rect((bmp.width - s) / 2, (bmp.height - s) / 2, (bmp.width + s) / 2, (bmp.height + s) / 2)
                drawBitmap(bmp, src, rect, imagePaint)
            }
        } else {
            paint.color = withAlpha(accent, 160)
            canvas.drawPath(clip, paint)
        }
        paint.color = 0x33FFFFFF
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        canvas.drawPath(clip, paint)
        paint.style = Paint.Style.FILL
    }

    private fun ellipsize(text: String, paint: TextPaint, width: Float): String =
        TextUtils.ellipsize(text, paint, width.coerceAtLeast(1f), TextUtils.TruncateAt.END).toString()

    /** Keeps the accent readable as text on the dark poster. */
    private fun lighten(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = maxOf(hsv[2], 0.9f)
        hsv[1] = minOf(hsv[1], 0.75f)
        return Color.HSVToColor(hsv)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
