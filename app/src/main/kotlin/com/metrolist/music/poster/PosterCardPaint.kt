/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.poster

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// ====================================================================================================================
// One Auralys card: a smoked-glass now-playing widget in a real light (ported from the Windows PosterCardPaint, 1.9.3).
//  * ONE key light for the whole poster (opposite to the shadow angle): bevel, gloss, rim light and shadow agree.
//  * The glass has a thickness (the extruded edge shows when tilted), frosts a soft copy of the poster behind it, has an inner shadow and a
//    two-pane window reflection; a card further back is drawn slightly soft (depth of field, done by the renderer on a layer).
//  * The face: cover, the "Auralys" device line, title + artist + heart, a slim bar with knob and times, transport glyphs, a volume row.
// Mask-filter blurs are only used on the renderer's SOFTWARE canvas (BlurMaskFilter is not supported by the hardware renderer before API 28).

internal data class CardStyle(
    val camera: Float,
    val radius: Float,
    val thickness: Float,
    val opacity: Float,
    val frost: Float,
    val gloss: Float,
    val dof: Float,
    val shadowStrength: Float,
    val shadowBlur: Float,
    val shadowDistance: Float,
    val shadowAngle: Float,
    val shadowTone: ShadowTone,
    val rim: Float,
    val level: Int,
    /** The size of the poster in poster pixels (the glass shows the frosted ground over the whole picture). */
    val pw: Int = POSTER_W,
    val ph: Int = POSTER_H,
)

internal class CardArgs(
    val song: PosterSong,
    val cover: Bitmap?,
    val tint: Color?,
    val pose: CardPose,
    val backdrop: ImageBitmap?,
    val style: CardStyle,
)

internal object CardLook {
    const val HW = 220f
    const val HH = 330f
    const val COVER = 352f
    val FOAM = Color(0xFFF2F4F7)
    val MUTED = Color(0xFFB4BAC6)
    val INK_TOP = Color(0xFF171A20)
    val INK_BOTTOM = Color(0xFF0A0B0F)
    val EDGE_DARK = Color(0xFF12161C)
    val EDGE_LIT = Color(0xFFC4D6DD)
    const val COVER_TOP = -302f
    const val DEVICE_TOP = 64f
    const val TITLE_TOP = 82f
    const val ARTIST_TOP = 120f
    const val BAR_Y = 184f
    const val TIMES_TOP = 196f
    const val TRANSPORT_Y = 252f
    const val VOLUME_Y = 300f
}

/** Android's BlurMaskFilter takes a radius and turns it into sigma = 0.57735 r + 0.5: this asks for a sigma. Null when it is too small to matter. */
internal fun blurMask(sigma: Float): BlurMaskFilter? = if (sigma <= 0.6f) null else BlurMaskFilter((sigma - 0.5f) / 0.57735f, BlurMaskFilter.Blur.NORMAL)

internal fun androidMatrix(m: FloatArray): Matrix = Matrix().apply { setValues(m) }

internal class CardPainter(private val measurer: TextMeasurer, font: FontFamily) {
    private val titleStyle = TextStyle(fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.3).sp)
    private val artistStyle = TextStyle(fontFamily = font, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 30.sp)
    private val deviceStyle = TextStyle(fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp)
    private val clockStyle = TextStyle(fontFamily = font, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 22.sp, fontFeatureSettings = "tnum")

    private val coverShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = blurMask(11f) }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rectF = RectF()
    private val srcRect = android.graphics.Rect()

    private fun lightX(st: CardStyle) = (-cos(Math.toRadians(st.shadowAngle.toDouble()))).toFloat()

    private fun lightY(st: CardStyle) = (-sin(Math.toRadians(st.shadowAngle.toDouble()))).toFloat()

    private fun lightInCard(st: CardStyle, rotation: Float): Pair<Float, Float> {
        val r = Math.toRadians(-rotation.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        val lx = lightX(st)
        val ly = lightY(st)
        return (lx * c - ly * s) to (lx * s + ly * c)
    }

    /** The depth-of-field softness of a card in poster pixels (0 = sharp): further back (low lift, small) = softer. Only at full quality. */
    fun dofSigma(pose: CardPose, st: CardStyle): Float = if (st.level == 0) st.dof * 5.5f * (1f - pose.lift) * (1.25f - pose.scale.coerceIn(0.3f, 1.2f)) else 0f

    fun outline(pose: CardPose, st: CardStyle): FloatArray = PosterGeometry.outline(pose, st.camera, 0f, st.radius, if (st.level >= 1) 5 else 9)

    /**
     * Draws the card (shadow first, then extrusion, glass, face, lit edge). The renderer wraps it in a blurred layer for depth of field.
     * Round 4.3.5: no coloured glow halo around the card any more (a glass card does not shine); the edge is lit by the key light only.
     */
    fun draw(scope: DrawScope, a: CardArgs) {
        val st = a.style
        val front = outline(a.pose, st)
        with(scope) { shadow(a, front) }
        val m = PosterGeometry.cardMatrix(a.pose, st.camera)
        val am = androidMatrix(m)
        scope.drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            if (st.level < 2 && st.thickness > 0.6f) with(scope) { extrusion(a, front) }
            nc.save()
            nc.concat(am)
            with(scope) {
                glass(a, m)
                content(a)
                rimLine(a)
            }
            nc.restore()
        }
    }

    // ---------------------------------------------------------------- the projected shadow
    private fun DrawScope.shadow(a: CardArgs, front: FloatArray) {
        val st = a.style
        if (st.shadowStrength <= 0.01f) return
        val lift = a.pose.lift.coerceIn(0f, 1f)
        val dx = cos(Math.toRadians(st.shadowAngle.toDouble())).toFloat()
        val dy = sin(Math.toRadians(st.shadowAngle.toDouble())).toFloat()
        val len = (4f + 80f * st.shadowDistance) * (0.2f + 1.6f * lift) * (0.6f + 0.4f * a.pose.scale.coerceIn(0.4f, 1.4f))
        val sigmaFar = (3f + 34f * st.shadowBlur) * (0.5f + 1.1f * lift)
        val tone =
            when (st.shadowTone) {
                ShadowTone.NETRAL -> Color.Black
                ShadowTone.HANGAT -> lerp(Color.Black, Color(0xFF4A2810), 0.75f)
                ShadowTone.SAMPUL -> a.tint?.let { lerp(Color.Black, it, 0.5f) } ?: Color.Black
            }
        val alphaFar = (st.shadowStrength * (0.66f - 0.30f * lift)).coerceIn(0f, 1f)
        val alphaContact = (st.shadowStrength * (0.62f * (1f - lift) + 0.16f)).coerceIn(0f, 1f)
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            fun cast(ox: Float, oy: Float, sigma: Float, color: Color, alpha: Float) {
                val p = android.graphics.Path()
                val n = front.size / 2
                for (i in 0 until n) {
                    val x = front[i * 2] + ox
                    val y = front[i * 2 + 1] + oy
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
                shadowPaint.color = color.copy(alpha = alpha).toArgb()
                shadowPaint.maskFilter = blurMask(sigma)
                nc.drawPath(p, shadowPaint)
            }
            when (st.level) {
                0 -> {
                    cast(dx * len, dy * len, sigmaFar, tone, alphaFar)
                    cast(dx * len * 0.22f, dy * len * 0.22f, 2f + sigmaFar * 0.16f, Color.Black, alphaContact)
                }
                1 -> cast(dx * len, dy * len, sigmaFar * 0.6f, tone, alphaFar)
                else -> {
                    cast(dx * len, dy * len, 0f, tone, alphaFar * 0.45f)
                    cast(dx * len * 0.5f, dy * len * 0.5f, 0f, tone, alphaFar * 0.3f)
                }
            }
        }
    }

    // ---------------------------------------------------------------- the lit edge
    /** The light on the glass edge is the key light's (near white), with only a breath of the cover's colour seen through the glass. */
    private fun rimColor(a: CardArgs): Color = a.tint?.let { lerp(CardLook.FOAM, PosterFx.glowColor(it), 0.18f) } ?: CardLook.FOAM

    // ---------------------------------------------------------------- the thickness of the glass
    private fun DrawScope.extrusion(a: CardArgs, front: FloatArray) {
        val st = a.style
        val steps = if (st.level >= 1) 5 else 9
        val back = PosterGeometry.outline(a.pose, st.camera, st.thickness, st.radius, steps)
        val o = PosterGeometry.rrectOutline(CardLook.HW, CardLook.HH, st.radius, steps)
        val (lx, ly) = lightInCard(st, a.pose.rotation)
        val n = front.size / 2
        val quad = Path()
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ex = front[j * 2] - front[i * 2]
            val ey = front[j * 2 + 1] - front[i * 2 + 1]
            val ddx = back[i * 2] - front[i * 2]
            val ddy = back[i * 2 + 1] - front[i * 2 + 1]
            if (ex * ddy - ey * ddx >= 0f) continue
            val nx = (o.nx[i] + o.nx[j]) / 2f
            val ny = (o.ny[i] + o.ny[j]) / 2f
            val t = nx * lx + ny * ly
            val k = ((t + 0.35f) / 1.25f).coerceIn(0f, 1f)
            val color = lerp(CardLook.EDGE_DARK, CardLook.EDGE_LIT, k * k)
            quad.reset()
            quad.moveTo(front[i * 2], front[i * 2 + 1])
            quad.lineTo(front[j * 2], front[j * 2 + 1])
            quad.lineTo(back[j * 2], back[j * 2 + 1])
            quad.lineTo(back[i * 2], back[i * 2 + 1])
            quad.close()
            drawPath(quad, color)
            drawPath(quad, color, style = Stroke(0.9f))
        }
    }

    // ---------------------------------------------------------------- the glass
    private fun glassPath(r: Float) = Path().apply { addRoundRect(RoundRect(-CardLook.HW, -CardLook.HH, CardLook.HW, CardLook.HH, CornerRadius(r))) }

    private fun DrawScope.glass(a: CardArgs, m: FloatArray) {
        val st = a.style
        val r = st.radius
        val (lx, ly) = lightInCard(st, a.pose.rotation)
        val withBackdrop = st.level < 2 && a.backdrop != null
        val topA = (st.opacity * 0.92f).coerceIn(0f, 1f)
        val botA = (topA + 0.12f).coerceAtMost(1f)
        clipPath(glassPath(r)) {
            if (withBackdrop) {
                val inv = PosterGeometry.invert(m)
                if (inv != null) {
                    drawIntoCanvas { c ->
                        c.nativeCanvas.save()
                        c.nativeCanvas.concat(androidMatrix(inv))
                        drawImage(a.backdrop!!, dstOffset = IntOffset.Zero, dstSize = IntSize(st.pw, st.ph), filterQuality = FilterQuality.Low)
                        c.nativeCanvas.restore()
                    }
                }
            }
            drawRect(
                Brush.verticalGradient(listOf(CardLook.INK_TOP.copy(alpha = topA), CardLook.INK_BOTTOM.copy(alpha = botA)), startY = -CardLook.HH, endY = CardLook.HH),
                topLeft = Offset(-CardLook.HW, -CardLook.HH),
                size = Size(CardLook.HW * 2f, CardLook.HH * 2f),
            )
            a.tint?.let { t ->
                // the cover's colour carried a little into the glass under it (light passing through tinted glass), faint
                drawRect(
                    Brush.radialGradient(listOf(t.copy(alpha = 0.12f), t.copy(alpha = 0.04f), Color.Transparent), center = Offset(0f, CardLook.HH + 30f), radius = 420f),
                    topLeft = Offset(-CardLook.HW, -CardLook.HH),
                    size = Size(CardLook.HW * 2f, CardLook.HH * 2f),
                )
            }
            val milk = (st.frost * (1f - st.opacity) * 0.9f).coerceIn(0f, 0.4f)
            if (milk > 0.01f) drawRect(Color.White.copy(alpha = milk), topLeft = Offset(-CardLook.HW, -CardLook.HH), size = Size(CardLook.HW * 2f, CardLook.HH * 2f))
            val g = st.gloss
            if (g > 0.01f) {
                val slide = (-a.pose.tiltY * 4.5f).coerceIn(-90f, 90f)
                val skew = 0.34f
                fun pane(x0: Float, x1: Float) =
                    Path().apply {
                        val top = -CardLook.HH
                        val bottom = 150f
                        moveTo(x0 + slide, top)
                        lineTo(x1 + slide, top)
                        lineTo(x1 + slide - skew * (bottom - top), bottom)
                        lineTo(x0 + slide - skew * (bottom - top), bottom)
                        close()
                    }
                val reflect = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.20f * g), 0.55f to Color.White.copy(alpha = 0.05f * g), 1f to Color.Transparent, startY = -CardLook.HH, endY = 150f)
                drawPath(pane(-120f, -18f), reflect)
                drawPath(pane(-2f, 120f), reflect)
                drawRect(
                    Brush.linearGradient(
                        0f to Color.White.copy(alpha = 0.10f * g),
                        0.34f to Color.White.copy(alpha = 0.02f * g),
                        0.6f to Color.Transparent,
                        start = Offset(-CardLook.HW * lx.coerceIn(-1f, 1f) * 1.1f - 20f, -CardLook.HH * ly.coerceIn(-1f, 1f)),
                        end = Offset(CardLook.HW * 0.7f, CardLook.HH * 0.5f),
                    ),
                    topLeft = Offset(-CardLook.HW, -CardLook.HH),
                    size = Size(CardLook.HW * 2f, CardLook.HH * 2f),
                )
            }
            drawRoundRect(
                Brush.linearGradient(0f to Color.Black.copy(alpha = 0.04f), 1f to Color.Black.copy(alpha = 0.34f), start = Offset(lx * CardLook.HW, ly * CardLook.HH), end = Offset(-lx * CardLook.HW, -ly * CardLook.HH)),
                topLeft = Offset(-CardLook.HW + 3.5f, -CardLook.HH + 3.5f),
                size = Size(CardLook.HW * 2f - 7f, CardLook.HH * 2f - 7f),
                cornerRadius = CornerRadius(r - 3.5f),
                style = Stroke(9f),
            )
        }
        // the glass edge: a hairline that is bright only where it faces the key light
        val edge = 0.35f + 0.5f * st.rim.coerceIn(0f, 1f)
        drawRoundRect(
            Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.07f),
                0.5f to Color.White.copy(alpha = 0.16f),
                1f to Color.White.copy(alpha = edge),
                start = Offset(-lx * CardLook.HW, -ly * CardLook.HH),
                end = Offset(lx * CardLook.HW, ly * CardLook.HH),
            ),
            topLeft = Offset(-CardLook.HW + 1.2f, -CardLook.HH + 1.2f),
            size = Size(CardLook.HW * 2f - 2.4f, CardLook.HH * 2f - 2.4f),
            cornerRadius = CornerRadius(r - 1.2f),
            style = Stroke(2.4f),
        )
    }

    private fun DrawScope.rimLine(a: CardArgs) {
        val st = a.style
        if (st.rim <= 0.01f) return
        val c = rimColor(a)
        val (lx, ly) = lightInCard(st, a.pose.rotation)
        drawRoundRect(
            Brush.linearGradient(0f to c.copy(alpha = 0.04f * st.rim), 0.6f to c.copy(alpha = 0.12f * st.rim), 1f to c.copy(alpha = (0.5f * st.rim).coerceAtMost(1f)), start = Offset(-lx * CardLook.HW, -ly * CardLook.HH), end = Offset(lx * CardLook.HW, ly * CardLook.HH)),
            topLeft = Offset(-CardLook.HW + 2.4f, -CardLook.HH + 2.4f),
            size = Size(CardLook.HW * 2f - 4.8f, CardLook.HH * 2f - 4.8f),
            cornerRadius = CornerRadius(st.radius - 2.4f),
            style = Stroke(2.2f),
        )
    }

    // ---------------------------------------------------------------- the face
    private fun DrawScope.content(a: CardArgs) {
        val song = a.song
        val st = a.style
        val left = -CardLook.COVER / 2f
        val right = CardLook.COVER / 2f
        val cover = a.cover
        val coverR = (st.radius * 0.62f).coerceAtLeast(10f)
        val coverTop = CardLook.COVER_TOP
        val coverPath = Path().apply { addRoundRect(RoundRect(left, coverTop, right, coverTop + CardLook.COVER, CornerRadius(coverR))) }
        if (st.level < 2) {
            coverShadow.color = Color.Black.copy(alpha = 0.42f).toArgb()
            rectF.set(left + 8f, coverTop + 12f, right - 8f, coverTop + CardLook.COVER + 10f)
            drawIntoCanvas { it.nativeCanvas.drawRoundRect(rectF, coverR, coverR, coverShadow) }
        }
        clipPath(coverPath) {
            if (cover != null && !cover.isRecycled && cover.width > 1 && cover.height > 1) {
                val side = min(cover.width, cover.height)
                srcRect.set((cover.width - side) / 2, (cover.height - side) / 2, (cover.width - side) / 2 + side, (cover.height - side) / 2 + side)
                rectF.set(left, coverTop, right, coverTop + CardLook.COVER)
                drawIntoCanvas { it.nativeCanvas.drawBitmap(cover, srcRect, rectF, bitmapPaint) }
            } else {
                drawRect(
                    Brush.linearGradient(listOf(Color(0xFF2B303A), Color(0xFF14171D)), start = Offset(left, coverTop), end = Offset(right, coverTop + CardLook.COVER)),
                    topLeft = Offset(left, coverTop),
                    size = Size(CardLook.COVER, CardLook.COVER),
                )
                with(PosterBrand) { stripedSun(-90f, coverTop + 90f, 180f, Color.White, Color.White, Color.White, alpha = 0.26f) }
            }
        }
        drawRoundRect(Color.White.copy(alpha = 0.14f), Offset(left + 0.7f, coverTop + 0.7f), Size(CardLook.COVER - 1.4f, CardLook.COVER - 1.4f), CornerRadius(coverR - 0.7f), style = Stroke(1.4f))

        drawWave(left + 7f, CardLook.DEVICE_TOP + 10f, CardLook.MUTED.copy(alpha = 0.8f))
        val device = measurer.measure("Auralys", deviceStyle, maxLines = 1, softWrap = false, constraints = Constraints(maxWidth = 160))
        drawText(device, CardLook.MUTED.copy(alpha = 0.8f), Offset(left + 22f, CardLook.DEVICE_TOP))
        val textWidth = (CardLook.COVER - 52f).toInt()
        val title = measurer.measure(song.title.ifBlank { " " }, titleStyle, overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1, constraints = Constraints(maxWidth = textWidth))
        drawText(title, CardLook.FOAM, Offset(left, CardLook.TITLE_TOP))
        val artist = measurer.measure(song.artist.ifBlank { " " }, artistStyle, overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1, constraints = Constraints(maxWidth = textWidth))
        drawText(artist, CardLook.MUTED, Offset(left, CardLook.ARTIST_TOP))
        drawHeart(right - 15f, CardLook.TITLE_TOP + 24f, 14f)

        val progress = PosterGeometry.progressOf(song.id)
        val barY = CardLook.BAR_Y
        val kx = left + CardLook.COVER * progress
        drawLine(CardLook.FOAM.copy(alpha = 0.26f), Offset(left, barY), Offset(right, barY), 4.5f, StrokeCap.Round)
        drawLine(CardLook.FOAM, Offset(left, barY), Offset(kx, barY), 4.5f, StrokeCap.Round)
        drawCircle(Color.Black.copy(alpha = 0.28f), 9.5f, Offset(kx, barY + 1.5f))
        drawCircle(CardLook.FOAM, 7.8f, Offset(kx, barY))
        val total = PosterGeometry.shownDuration(song)
        val elapsed = (total * progress).toInt()
        val elapsedText = measurer.measure(PosterGeometry.clock(elapsed), clockStyle, constraints = Constraints(maxWidth = 120))
        val remainText = measurer.measure("-" + PosterGeometry.clock(total - elapsed), clockStyle, constraints = Constraints(maxWidth = 120))
        drawText(elapsedText, CardLook.MUTED.copy(alpha = 0.85f), Offset(left, CardLook.TIMES_TOP))
        drawText(remainText, CardLook.MUTED.copy(alpha = 0.85f), Offset(right - remainText.size.width, CardLook.TIMES_TOP))

        val ty = CardLook.TRANSPORT_Y
        drawTriangles(-92f, ty, true)
        drawPause(0f, ty)
        drawTriangles(92f, ty, false)

        val vy = CardLook.VOLUME_Y
        drawSpeaker(left + 4f, vy, 1)
        drawSpeaker(right - 16f, vy, 2)
        val vl = left + 38f
        val vr = right - 40f
        val vp = 0.42f + PosterGeometry.progressOf(song.id + "v") * 0.4f
        drawLine(CardLook.FOAM.copy(alpha = 0.26f), Offset(vl, vy), Offset(vr, vy), 4f, StrokeCap.Round)
        drawLine(CardLook.FOAM.copy(alpha = 0.92f), Offset(vl, vy), Offset(vl + (vr - vl) * vp, vy), 4f, StrokeCap.Round)
    }

    private fun DrawScope.drawWave(x: Float, y: Float, color: Color) {
        val p = Path()
        p.moveTo(x - 7f, y)
        var xx = x - 7f
        while (xx < x + 7f) {
            xx += 1f
            p.lineTo(xx, y + sin((xx - (x - 7f)) / 7f * Math.PI).toFloat() * 3.4f)
        }
        drawPath(p, color, style = Stroke(2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    private fun DrawScope.drawHeart(x: Float, y: Float, r: Float) {
        val h = Path().apply {
            moveTo(x, y - 0.42f * r)
            cubicTo(x + 0.12f * r, y - 0.98f * r, x + r, y - 0.98f * r, x + r, y - 0.28f * r)
            cubicTo(x + r, y + 0.34f * r, x + 0.3f * r, y + 0.7f * r, x, y + r)
            cubicTo(x - 0.3f * r, y + 0.7f * r, x - r, y + 0.34f * r, x - r, y - 0.28f * r)
            cubicTo(x - r, y - 0.98f * r, x - 0.12f * r, y - 0.98f * r, x, y - 0.42f * r)
            close()
        }
        drawPath(h, CardLook.FOAM.copy(alpha = 0.85f), style = Stroke(2.8f, join = StrokeJoin.Round))
    }

    private fun DrawScope.drawTriangles(cx: Float, cy: Float, left: Boolean) {
        val d = if (left) -1f else 1f
        for (k in 0..1) {
            val x0 = cx - d * 22f + d * k * 21f
            val p = Path().apply {
                moveTo(x0 - d * 11f, cy - 14f)
                lineTo(x0 + d * 11f, cy)
                lineTo(x0 - d * 11f, cy + 14f)
                close()
            }
            drawPath(p, CardLook.FOAM, style = Fill)
            drawPath(p, CardLook.FOAM, style = Stroke(3.2f, join = StrokeJoin.Round))
        }
    }

    private fun DrawScope.drawPause(cx: Float, cy: Float) {
        drawRoundRect(CardLook.FOAM, Offset(cx - 17f, cy - 19f), Size(12f, 38f), CornerRadius(3.6f))
        drawRoundRect(CardLook.FOAM, Offset(cx + 5f, cy - 19f), Size(12f, 38f), CornerRadius(3.6f))
    }

    private fun DrawScope.drawSpeaker(x: Float, cy: Float, waves: Int) {
        val body = Path().apply {
            moveTo(x, cy - 4.5f)
            lineTo(x + 5f, cy - 4.5f)
            lineTo(x + 12f, cy - 10.5f)
            lineTo(x + 12f, cy + 10.5f)
            lineTo(x + 5f, cy + 4.5f)
            lineTo(x, cy + 4.5f)
            close()
        }
        drawPath(body, CardLook.FOAM.copy(alpha = 0.9f), style = Fill)
        drawPath(body, CardLook.FOAM.copy(alpha = 0.9f), style = Stroke(1.8f, join = StrokeJoin.Round))
        for (w in 1..waves) {
            val rr = 6f + w * 4.5f
            val arc = Path().apply { arcTo(Rect(x + 12f - rr * 0.35f, cy - rr, x + 12f + rr * 1.65f, cy + rr), -48f, 96f, true) }
            drawPath(arc, CardLook.FOAM.copy(alpha = 0.9f), style = Stroke(2.2f, cap = StrokeCap.Round))
        }
    }
}
