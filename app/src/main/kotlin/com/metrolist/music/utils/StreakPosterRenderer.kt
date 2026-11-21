/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Renders the shareable 1080x1920 listening-streak poster (the flame of the streak with glow and sparks, days in a row,
 * longest streak, every flame on a ladder, the next flame, Auralys mark) and hands it to the system share sheet, using
 * the same helpers as [RecapPosterRenderer].
 */

package com.metrolist.music.utils

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import com.metrolist.music.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat

object StreakPosterRenderer {
    private const val W = 1080
    private const val H = 1920

    suspend fun create(context: Context, streak: ListeningStreak): Bitmap =
        withContext(Dispatchers.Default) {
            val bitmap = createBitmap(W, H)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val tier = streak.tier
            val accent = tier.accent
            val tip = tier.tip
            val glow = tier.glow
            val nf = NumberFormat.getIntegerInstance()
            val bold = LyricsCardRenderer.typeface(context, LyricsCardFont.GEOMETRIC, 700)
            val regular = LyricsCardRenderer.typeface(context, LyricsCardFont.GEOMETRIC, 450)
            val centre = W / 2f
            val bgRect = RectF(0f, 0f, W.toFloat(), H.toFloat())

            // ---- background: deep base, a big glow in the colour of the flame, faint stars, dark corners
            paint.color = 0xFF08080B.toInt()
            canvas.drawRect(bgRect, paint)
            paint.shader = RadialGradient(centre, 640f, 980f, withAlpha(glow, 190), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(bgRect, paint)
            paint.shader = RadialGradient(centre, H.toFloat(), 900f, withAlpha(glow, 70), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(bgRect, paint)
            paint.shader = null
            for (k in 0 until 90) {
                val x = frac(k * 0.7548777f) * W
                val y = frac(k * 0.5698403f + 0.31f) * H
                paint.color = withAlpha(Color.WHITE, 20 + (k * 37) % 70)
                canvas.drawCircle(x, y, 1.5f + (k % 3), paint)
            }
            paint.shader = RadialGradient(centre, H / 2f, 1300f, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, 0xCC000000.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(bgRect, paint)
            paint.shader = null

            // ---- header: label and the name of the flame
            val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xBFFFFFFF.toInt()
                textSize = 32f
                typeface = bold
                letterSpacing = 0.2f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(context.getString(R.string.auralys_streak_poster_label), centre, 128f, labelPaint)
            val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 40f
                typeface = bold
                letterSpacing = 0.14f
                textAlign = Paint.Align.CENTER
            }
            val name = context.getString(tier.nameRes).uppercase()
            val pillWidth = namePaint.measureText(name) + 110f
            val pill = RectF(centre - pillWidth / 2f, 168f, centre + pillWidth / 2f, 242f)
            paint.color = withAlpha(accent, 46)
            canvas.drawRoundRect(pill, 37f, 37f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = withAlpha(tip, 220)
            canvas.drawRoundRect(pill, 37f, 37f, paint)
            paint.style = Paint.Style.FILL
            canvas.drawText(name, centre, 168f + 52f, namePaint)

            // ---- the flame itself
            canvas.save()
            canvas.translate(140f, 262f)
            FlameArt.draw(canvas, 800f, 920f, tier, lit = true, phase = 0.2f)
            canvas.restore()

            // ---- number and label
            val numberPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 330f
                typeface = bold
                textAlign = Paint.Align.CENTER
                shader = LinearGradient(0f, 1130f, 0f, 1390f, Color.WHITE, tip, Shader.TileMode.CLAMP)
            }
            canvas.drawText(nf.format(streak.current), centre, 1390f, numberPaint)
            val daysPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xE6FFFFFF.toInt()
                textSize = 58f
                typeface = bold
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(
                context.resources.getQuantityString(R.plurals.auralys_streak_poster_days, streak.current),
                centre,
                1468f,
                daysPaint,
            )

            // ---- two figures: longest streak and days listened
            val boxWidth = 440f
            val boxTop = 1520f
            val boxHeight = 118f
            val figure = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 56f
                typeface = bold
                textAlign = Paint.Align.CENTER
            }
            val caption = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x99FFFFFF.toInt()
                textSize = 24f
                typeface = regular
                letterSpacing = 0.16f
                textAlign = Paint.Align.CENTER
            }
            listOf(
                streak.longest to R.string.auralys_streak_poster_longest,
                streak.totalDays to R.string.auralys_streak_poster_total,
            ).forEachIndexed { index, (value, label) ->
                val left = centre - boxWidth - 20f + index * (boxWidth + 40f)
                val rect = RectF(left, boxTop, left + boxWidth, boxTop + boxHeight)
                paint.color = 0x14FFFFFF
                canvas.drawRoundRect(rect, 30f, 30f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                paint.color = 0x26FFFFFF
                canvas.drawRoundRect(rect, 30f, 30f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawText(nf.format(value), rect.centerX(), boxTop + 62f, figure)
                canvas.drawText(context.getString(label), rect.centerX(), boxTop + 98f, caption)
            }

            // ---- every flame on a ladder: the ones reached burn, the ones ahead are grey, the current one has a ring
            val ladder = StreakTiers.all.filter { it.index > 0 }
            val slot = 920f / ladder.size
            val ladderTop = 1678f
            val flameBox = 92f
            ladder.forEachIndexed { index, step ->
                val reached = streak.current >= step.minDays
                val left = 80f + index * slot + (slot - flameBox) / 2f
                canvas.save()
                canvas.translate(left, ladderTop)
                if (!reached) canvas.saveLayerAlpha(0f, 0f, flameBox, flameBox * 1.1f, 110)
                FlameArt.draw(canvas, flameBox, flameBox * 1.1f, step, lit = reached, phase = 0.2f)
                if (!reached) canvas.restore()
                canvas.restore()
                if (step.index == tier.index) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 3f
                    paint.color = withAlpha(tip, 230)
                    canvas.drawRoundRect(RectF(left - 6f, ladderTop - 6f, left + flameBox + 6f, ladderTop + flameBox * 1.1f + 6f), 22f, 22f, paint)
                    paint.style = Paint.Style.FILL
                }
            }

            // ---- next flame
            val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xB3FFFFFF.toInt()
                textSize = 32f
                typeface = regular
                textAlign = Paint.Align.CENTER
            }
            val next = streak.nextMilestone
            if (next != null) {
                canvas.drawText(
                    context.getString(R.string.auralys_streak_poster_next, context.getString(StreakTiers.forDays(next).nameRes), next),
                    centre,
                    1826f,
                    subPaint,
                )
            } else {
                canvas.drawText(context.getString(R.string.auralys_streak_all_done), centre, 1826f, subPaint)
            }

            // ---- footer: Auralys mark + name
            val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val logo = ContextCompat.getDrawable(context, R.drawable.app_logo)
            val logoSize = 52
            val logoTop = 1846f
            val brandPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 38f
                typeface = bold
            }
            val brandWidth = brandPaint.measureText("Auralys")
            val footerLeft = centre - (logoSize + 16f + brandWidth) / 2f
            if (logo != null) {
                val logoBmp = logo.toBitmap(logoSize * 2, logoSize * 2)
                canvas.drawBitmap(logoBmp, null, RectF(footerLeft, logoTop, footerLeft + logoSize, logoTop + logoSize), imagePaint)
            }
            canvas.drawText("Auralys", footerLeft + logoSize + 16f, logoTop + logoSize / 2f + 13f, brandPaint)

            bitmap
        }

    /** Renders the poster, stores it with [ComposeToImage.saveBitmapAsFile] and opens the share sheet. */
    suspend fun share(context: Context, streak: ListeningStreak) {
        val image = create(context, streak)
        val uri =
            withContext(Dispatchers.IO) {
                ComposeToImage.saveBitmapAsFile(context, image, "auralys_streak_${System.currentTimeMillis()}")
            }
        context.startActivity(
            Intent
                .createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    context.getString(R.string.auralys_streak_share),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun frac(value: Float): Float = value - kotlin.math.floor(value)

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
