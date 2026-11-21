/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Renders a "song card" image (blurred cover glow, glass panel, big cover, title, artist, Auralys mark)
 * that can be posted to a story or a chat.
 */

package com.metrolist.music.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import androidx.palette.graphics.Palette
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.music.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SongShareCard {
    private const val W = 1080
    private const val H = 1350
    private const val SCALE = 1.5f

    suspend fun create(
        context: Context,
        coverUrl: String?,
        title: String,
        artist: String,
    ): Bitmap =
        withContext(Dispatchers.Default) {
            val bitmap = createBitmap((W * SCALE).toInt(), (H * SCALE).toInt())
            val canvas = Canvas(bitmap)
            canvas.scale(SCALE, SCALE)

            // ---- cover art
            var cover: Bitmap? = null
            if (coverUrl != null) {
                try {
                    val result =
                        ImageLoader(context)
                            .execute(
                                ImageRequest.Builder(context).data(coverUrl).size(1024).allowHardware(false).build(),
                            )
                    cover = result.image?.toBitmap()
                } catch (_: Exception) {
                }
            }

            val accent =
                cover?.let { c ->
                    runCatching {
                        val p = Palette.from(c).maximumColorCount(12).generate()
                        p.getVibrantColor(p.getDominantColor(ShareCardColors.ACCENT))
                    }.getOrNull()
                } ?: ShareCardColors.ACCENT

            // ---- background: dark base + very soft blurred cover + coloured glows
            val bgRect = RectF(0f, 0f, W.toFloat(), H.toFloat())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            paint.color = ShareCardColors.BASE
            canvas.drawRect(bgRect, paint)

            if (cover != null) {
                canvas.drawBitmap(LyricsCardRenderer.softBackdrop(cover), null, bgRect, paint)
                paint.color = 0x99000000.toInt()
                canvas.drawRect(bgRect, paint)
            }
            paint.shader = RadialGradient(0f, 0f, W * 1.1f, withAlpha(accent, 150), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(bgRect, paint)
            paint.shader = RadialGradient(W.toFloat(), H * 0.95f, W * 1.0f, withAlpha(accent, 90), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(bgRect, paint)
            paint.shader = null

            // ---- glass panel
            val panel = RectF(80f, 110f, W - 80f, H - 150f)
            paint.style = Paint.Style.FILL
            paint.color = 0x1AFFFFFF
            canvas.drawRoundRect(panel, 84f, 84f, paint)
            paint.shader = LinearGradient(
                panel.left, panel.top, panel.right, panel.bottom,
                intArrayOf(0x99FFFFFF.toInt(), 0x0DFFFFFF, 0x59FFFFFF),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f
            canvas.drawRoundRect(panel, 84f, 84f, paint)
            paint.shader = null
            paint.style = Paint.Style.FILL

            // ---- big cover with a soft shadow
            val coverSize = panel.width() - 200f
            val coverRect = RectF(panel.left + 100f, panel.top + 60f, panel.left + 100f + coverSize, panel.top + 60f + coverSize)
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF000000.toInt()
                setShadowLayer(60f, 0f, 30f, 0x99000000.toInt())
            }
            canvas.drawRoundRect(coverRect, 56f, 56f, shadow)
            if (cover != null) {
                val clip = Path().apply { addRoundRect(coverRect, 56f, 56f, Path.Direction.CW) }
                canvas.withClip(clip) {
                    // centre-crop to a square
                    val s = minOf(cover.width, cover.height)
                    val src = android.graphics.Rect((cover.width - s) / 2, (cover.height - s) / 2, (cover.width + s) / 2, (cover.height + s) / 2)
                    // a fresh paint: `paint` still carries the 10% white of the panel, which made the cover come out faded
                    drawBitmap(cover, src, coverRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                }
            } else {
                paint.color = withAlpha(accent, 200)
                canvas.drawRoundRect(coverRect, 56f, 56f, paint)
            }
            paint.color = 0x40FFFFFF
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            canvas.drawRoundRect(coverRect, 56f, 56f, paint)
            paint.style = Paint.Style.FILL

            // ---- text
            val base = ResourcesCompat.getFont(context, R.font.plus_jakarta_sans)
            val bold = if (base != null && android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(base, 700, false) else Typeface.DEFAULT_BOLD
            val regular = if (base != null && android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(base, 450, false) else Typeface.DEFAULT

            val textLeft = panel.left + 100f
            val textWidth = (panel.width() - 200f).toInt()
            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textSize = 58f
                typeface = bold
            }
            val titleLayout = StaticLayout.Builder
                .obtain(title, 0, title.length, titlePaint, textWidth)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
            canvas.withTranslation(textLeft, coverRect.bottom + 56f) { titleLayout.draw(this) }

            val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xCCFFFFFF.toInt()
                textSize = 40f
                typeface = regular
            }
            val artistLayout = StaticLayout.Builder
                .obtain(artist, 0, artist.length, artistPaint, textWidth)
                .setMaxLines(1)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            canvas.withTranslation(textLeft, coverRect.bottom + 56f + titleLayout.height + 12f) { artistLayout.draw(this) }

            // ---- footer: Auralys mark + name
            val logo = ContextCompat.getDrawable(context, R.drawable.app_logo)
            val logoSize = 84
            val logoTop = (H - 150f + 36f).toInt()
            val logoLeft = 80
            if (logo != null) {
                val logoBmp = logo.toBitmap(logoSize * 2, logoSize * 2)
                canvas.drawBitmap(logoBmp, null, RectF(logoLeft.toFloat(), logoTop.toFloat(), (logoLeft + logoSize).toFloat(), (logoTop + logoSize).toFloat()), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }
            val brandPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textSize = 50f
                typeface = bold
            }
            canvas.drawText("Auralys", logoLeft + logoSize + 22f, logoTop + logoSize / 2f + 17f, brandPaint)

            bitmap
        }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
