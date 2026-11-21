/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Draws the "share lyrics" card. One renderer serves both the live preview (small) and the exported image
 * (large), so what you see while customising is exactly what gets shared.
 */

package com.metrolist.music.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import com.metrolist.music.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class LyricsCardBackground { GLASS, SOLID, BLUR, GRADIENT }

enum class LyricsCardRatio(val heightFactor: Float) { SQUARE(1f), STORY(16f / 9f) }

enum class LyricsCardWeight(val value: Int) { REGULAR(400), MEDIUM(500), BOLD(700), BLACK(900) }

/** Font choices. "iOS" is Inter (the closest open font to San Francisco, which Apple does not allow to be bundled). */
enum class LyricsCardFont(
    val label: String,
    val assetPath: String? = null,
    val resId: Int? = null,
) {
    IOS("iOS", assetPath = "fonts/Inter.ttf"),
    IOS_ROUND("iOS Rounded", assetPath = "fonts/Nunito.ttf"),
    SYSTEM("System"),
    PLAYFAIR("Playfair", assetPath = "fonts/PlayfairDisplay.ttf"),
    EDITORIAL("Editorial", assetPath = "fonts/Newsreader.ttf"),
    GROTESK("Grotesk", assetPath = "fonts/SpaceGrotesk.ttf"),
    RUBIK("Rubik", assetPath = "fonts/Rubik.ttf"),
    GEOMETRIC("DM Sans", assetPath = "fonts/DMSans.ttf"),
    HANDWRITING("Handwriting", assetPath = "fonts/Caveat.ttf"),
}

enum class LyricsCardAlign { LEFT, CENTER, RIGHT }

data class LyricsCardStyle(
    val background: LyricsCardBackground = LyricsCardBackground.GLASS,
    val ratio: LyricsCardRatio = LyricsCardRatio.SQUARE,
    val font: LyricsCardFont = LyricsCardFont.IOS,
    val weight: LyricsCardWeight = LyricsCardWeight.BOLD,
    val align: LyricsCardAlign = LyricsCardAlign.LEFT,
    /** Only used by [LyricsCardBackground.SOLID]; null = near black. */
    val backgroundColor: Int? = null,
    /** null = automatic (white, or black on a light solid background). */
    val textColor: Int? = null,
)

/** Colours pulled from the cover once, so previews do not redo the work on every tweak. */
data class LyricsCardPalette(
    val accent: Int,
    val dark: Int,
)

object LyricsCardRenderer {
    private val typefaceCache = HashMap<String, Typeface>()

    fun typeface(
        context: Context,
        font: LyricsCardFont,
        weight: Int,
    ): Typeface {
        val key = "${font.name}-$weight"
        typefaceCache[key]?.let { return it }
        val built: Typeface? =
            try {
                when {
                    font.assetPath != null && Build.VERSION.SDK_INT >= 26 ->
                        Typeface
                            .Builder(context.assets, font.assetPath)
                            .setFontVariationSettings("'wght' $weight")
                            .build()
                    font.resId != null -> ResourcesCompat.getFont(context, font.resId)
                    else -> null
                }
            } catch (_: Throwable) {
                null
            }
        val tf =
            built ?: if (Build.VERSION.SDK_INT >= 28) {
                Typeface.create(Typeface.DEFAULT, weight, false)
            } else {
                Typeface.create(Typeface.DEFAULT, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
            }
        typefaceCache[key] = tf
        return tf
    }

    /**
     * A genuinely smooth blurred copy of [src] for backgrounds: step-down to ~160 px, then a triple box blur
     * (which converges to a Gaussian). Drawn scaled up with filtering it shows no blocks at any export size.
     */
    fun softBackdrop(src: Bitmap): Bitmap {
        val side = min(src.width, src.height)
        val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        var cur = sq
        // halve repeatedly (filtered) instead of one huge jump, which is what makes blocks
        while (cur.width / 2 >= 160) cur = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
        cur = Bitmap.createScaledBitmap(cur, 160, 160, true)
        val w = cur.width
        val h = cur.height
        val px = IntArray(w * h)
        cur.getPixels(px, 0, w, 0, 0, w, h)
        val ch = Array(3) { IntArray(w * h) }
        for (i in px.indices) {
            ch[0][i] = (px[i] shr 16) and 0xFF
            ch[1][i] = (px[i] shr 8) and 0xFF
            ch[2][i] = px[i] and 0xFF
        }
        repeat(3) {
            for (c in 0..2) {
                boxBlur(ch[c], w, h, 12, true)
                boxBlur(ch[c], w, h, 12, false)
            }
        }
        for (i in px.indices) px[i] = (0xFF shl 24) or (ch[0][i] shl 16) or (ch[1][i] shl 8) or ch[2][i]
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun boxBlur(a: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val buf = IntArray(len)
        val win = r * 2 + 1
        for (l in 0 until lines) {
            fun idx(i: Int) = if (horizontal) l * w + i else i * w + l
            var sum = 0
            for (i in -r..r) sum += a[idx(i.coerceIn(0, len - 1))]
            for (i in 0 until len) {
                buf[i] = sum / win
                sum += a[idx((i + r + 1).coerceIn(0, len - 1))] - a[idx((i - r).coerceIn(0, len - 1))]
            }
            for (i in 0 until len) a[idx(i)] = buf[i]
        }
    }

    private fun lum(c: Int): Float = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f

    private fun withAlpha(
        c: Int,
        a: Int,
    ): Int = Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    /** Draws the sun-over-wave mark (same geometry as the app logo). */
    private fun drawMark(
        canvas: Canvas,
        x: Float,
        y: Float,
        size: Float,
        sun: Int,
        ink: Int,
    ) {
        val s = size / 432f
        fun px(vx: Float) = x + (vx - 40f) * s
        fun py(vy: Float) = y + (vy - 120f) * s
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = 256f
        val cy = 340f
        val r = 190f
        val bands = listOf(150f to 250f, 258f to 278f, 290f to 304f, 320f to 326f)
        paint.style = Paint.Style.FILL
        paint.color = sun
        for ((a, b) in bands) {
            val path = Path()
            val steps = 14
            for (i in 0..steps) {
                val yy = a + (b - a) * i / steps
                val d = sqrt(max(r * r - (yy - cy) * (yy - cy), 0f))
                if (i == 0) path.moveTo(px(cx - d), py(yy)) else path.lineTo(px(cx - d), py(yy))
            }
            for (i in steps downTo 0) {
                val yy = a + (b - a) * i / steps
                val d = sqrt(max(r * r - (yy - cy) * (yy - cy), 0f))
                path.lineTo(px(cx + d), py(yy))
            }
            path.close()
            canvas.drawPath(path, paint)
        }
        paint.color = ink
        canvas.drawRoundRect(RectF(px(96f), py(340f), px(416f), py(350f)), 5f * s, 5f * s, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 9f * s
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        val wave = Path()
        for (i in 0..60) {
            val vx = 176f + 160f * i / 60f
            val vy = 392f - 12f * sin(2.0 * Math.PI * 3.0 * i / 60.0).toFloat()
            if (i == 0) wave.moveTo(px(vx), py(vy)) else wave.lineTo(px(vx), py(vy))
        }
        canvas.drawPath(wave, paint)
    }

    suspend fun render(
        context: Context,
        cover: Bitmap?,
        palette: LyricsCardPalette?,
        title: String,
        artist: String,
        lyrics: String,
        style: LyricsCardStyle,
        width: Int,
    ): Bitmap =
        withContext(Dispatchers.Default) {
            val w = width
            val h = (width * style.ratio.heightFactor).toInt()
            val u = width / 1080f
            val bmp = createBitmap(w, h)
            val canvas = Canvas(bmp)
            val full = RectF(0f, 0f, w.toFloat(), h.toFloat())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            val accent = palette?.accent ?: ShareCardColors.ACCENT
            val darkTone = palette?.dark ?: ShareCardColors.DARK
            val solidBg = style.backgroundColor ?: 0xFF121212.toInt()

            // ---- background
            when (style.background) {
                LyricsCardBackground.SOLID -> {
                    paint.color = solidBg
                    canvas.drawRect(full, paint)
                    paint.shader = RadialGradient(w * 0.2f, h * 0.1f, w * 1.0f, withAlpha(accent, 46), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                    canvas.drawRect(full, paint)
                    paint.shader = null
                }
                LyricsCardBackground.GRADIENT -> {
                    paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), accent, darkTone, Shader.TileMode.CLAMP)
                    canvas.drawRect(full, paint)
                    paint.shader = RadialGradient(w * 0.15f, h * 0.05f, w * 0.95f, 0x59FFFFFF, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                    canvas.drawRect(full, paint)
                    paint.shader = null
                }
                LyricsCardBackground.BLUR, LyricsCardBackground.GLASS -> {
                    paint.color = ShareCardColors.BASE
                    canvas.drawRect(full, paint)
                    if (cover != null) {
                        val soft = softBackdrop(cover)
                        val cm = ColorMatrix().apply { setSaturation(1.45f) }
                        val p2 = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) }
                        canvas.drawBitmap(soft, null, full, p2)
                        paint.color = 0x4D000000
                        canvas.drawRect(full, paint)
                    } else {
                        paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), accent, darkTone, Shader.TileMode.CLAMP)
                        canvas.drawRect(full, paint)
                        paint.shader = null
                    }
                    paint.shader = RadialGradient(w * 0.1f, h * 0.05f, w * 0.9f, withAlpha(accent, 80), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                    canvas.drawRect(full, paint)
                    paint.shader = RadialGradient(w * 0.95f, h * 0.98f, w * 0.9f, withAlpha(accent, 60), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                    canvas.drawRect(full, paint)
                    paint.shader = null
                }
            }

            val onLightSolid = style.background == LyricsCardBackground.SOLID && lum(solidBg) > 0.62f
            val mainColor = style.textColor ?: if (onLightSolid) 0xFF111111.toInt() else Color.WHITE
            val subColor = withAlpha(mainColor, 184)

            // ---- glass panel
            var inset = 100f * u
            if (style.background == LyricsCardBackground.GLASS) {
                val m = 52f * u
                val panel = RectF(m, m, w - m, h - m)
                val r = 78f * u
                paint.style = Paint.Style.FILL
                paint.shader = LinearGradient(
                    panel.left, panel.top, panel.right, panel.bottom,
                    intArrayOf(0x40FFFFFF, 0x14FFFFFF, 0x26FFFFFF), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
                )
                canvas.drawRoundRect(panel, r, r, paint)
                // top sheen
                paint.shader = LinearGradient(0f, panel.top, 0f, panel.top + panel.height() * 0.45f, 0x2EFFFFFF, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                canvas.drawRoundRect(panel, r, r, paint)
                // specular rim: bright top-left, soft bottom-right glint
                paint.shader = LinearGradient(
                    panel.left, panel.top, panel.right, panel.bottom,
                    intArrayOf(0xCCFFFFFF.toInt(), 0x1AFFFFFF, 0x1AFFFFFF, 0x80FFD9C2.toInt()), floatArrayOf(0f, 0.45f, 0.6f, 1f), Shader.TileMode.CLAMP,
                )
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3.2f * u
                canvas.drawRoundRect(panel, r, r, paint)
                paint.shader = null
                paint.style = Paint.Style.FILL
                inset = m + 78f * u
            }

            val px = inset
            val top = inset
            val contentW = w - inset * 2

            // ---- header: cover + title/artist
            val cs = 160f * u
            val coverR = 38f * u
            val cRect = RectF(px, top, px + cs, top + cs)
            if (cover != null) {
                val path = Path().apply { addRoundRect(cRect, coverR, coverR, Path.Direction.CW) }
                canvas.withClip(path) {
                    val sz = min(cover.width, cover.height)
                    val src = android.graphics.Rect((cover.width - sz) / 2, (cover.height - sz) / 2, (cover.width + sz) / 2, (cover.height + sz) / 2)
                    drawBitmap(cover, src, cRect, paint)
                }
                val bp = Paint(Paint.ANTI_ALIAS_FLAG)
                bp.style = Paint.Style.STROKE
                bp.strokeWidth = 2f * u
                bp.color = withAlpha(Color.WHITE, 70)
                canvas.drawRoundRect(cRect, coverR, coverR, bp)
            } else {
                paint.color = withAlpha(accent, 220)
                canvas.drawRoundRect(cRect, coverR, coverR, paint)
            }
            val tx = px + cs + 38f * u
            val tw = (contentW - cs - 38f * u).toInt().coerceAtLeast(10)
            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = mainColor
                textSize = 52f * u
                typeface = typeface(context, style.font, max(style.weight.value, 700))
            }
            val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = subColor
                textSize = 40f * u
                typeface = typeface(context, style.font, 500)
            }
            val tl = StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, tw)
                .setMaxLines(1).setEllipsize(TextUtils.TruncateAt.END).build()
            val al = StaticLayout.Builder.obtain(artist, 0, artist.length, artistPaint, tw)
                .setMaxLines(1).setEllipsize(TextUtils.TruncateAt.END).build()
            val blockH = tl.height + al.height + 6f * u
            canvas.withTranslation(tx, top + cs / 2f - blockH / 2f) {
                tl.draw(this)
                translate(0f, tl.height + 6f * u)
                al.draw(this)
            }

            // ---- footer: hairline, mark, wordmark
            val footerH = 72f * u
            val bottom = h - inset
            val footerTop = bottom - footerH
            paint.color = withAlpha(mainColor, 44)
            canvas.drawRect(px, footerTop - 34f * u, px + contentW, footerTop - 34f * u + 2f * u, paint)
            drawMark(canvas, px - 4f * u, footerTop - 6f * u, 96f * u, accentFor(accent, mainColor), mainColor)
            val brand = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = subColor
                textSize = 40f * u
                typeface = typeface(context, style.font, 800)
                letterSpacing = 0.02f
            }
            canvas.drawText("Auralys", px + 100f * u, footerTop + footerH / 2f + 14f * u, brand)

            // ---- big quote mark (decorative, behind the text)
            val quote = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(accentFor(accent, mainColor), 60)
                textSize = 360f * u
                typeface = typeface(context, LyricsCardFont.PLAYFAIR, 900)
            }
            val lyricsTop = top + cs + 70f * u
            val lyricsBottom = footerTop - 70f * u
            val availH = lyricsBottom - lyricsTop
            val qx = when (style.align) {
                LyricsCardAlign.LEFT -> px - 8f * u
                LyricsCardAlign.CENTER -> px + contentW / 2f - quote.measureText("“") / 2f
                LyricsCardAlign.RIGHT -> px + contentW - quote.measureText("“") + 8f * u
            }
            canvas.drawText("“", qx, lyricsTop + 250f * u, quote)

            // ---- lyrics (auto-fit)
            val lp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = mainColor
                typeface = typeface(context, style.font, style.weight.value)
                letterSpacing = if (style.font == LyricsCardFont.HANDWRITING) 0f else -0.005f
            }
            val layoutAlign = when (style.align) {
                LyricsCardAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
                LyricsCardAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
                LyricsCardAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
            }
            val maxSize = (if (style.ratio == LyricsCardRatio.STORY) 132f else 118f) * u * (if (style.font == LyricsCardFont.HANDWRITING) 1.18f else 1f)
            val minSize = 36f * u
            var size = maxSize
            var layout: StaticLayout
            while (true) {
                lp.textSize = size
                layout = StaticLayout.Builder.obtain(lyrics, 0, lyrics.length, lp, contentW.toInt())
                    .setAlignment(layoutAlign).setLineSpacing(0f, 1.18f).setIncludePad(false).build()
                if (layout.height <= availH || size <= minSize) break
                size -= 2f * u
            }
            val ly = if (layout.height < availH) lyricsTop + (availH - layout.height) / 2f else lyricsTop
            canvas.withTranslation(px, ly) { layout.draw(this) }

            bmp
        }

    /** Keeps the accent readable against the text colour (a dark accent on white text would vanish). */
    private fun accentFor(accent: Int, text: Int): Int = if (lum(accent) < 0.22f && lum(text) > 0.5f) ShareCardColors.INK else accent
}
