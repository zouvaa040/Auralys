/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.poster

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import java.util.IdentityHashMap
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// ====================================================================================================================
// The stickers (Windows 1.9.3 rule: only two built-in ones, a bird and a glint; every other sticker is the user's own picture, drawn as it
// is with a soft shadow along the key light, no border).
//
// Round 4.3.5 ("no AI look"): the built-ins are no longer die-cut clip-art with a white border.
//  * "Burung" is the silhouette of a swallow in flight as a camera sees a bird against the light: one dark shape (long pointed wings, a
//    forked tail with streamers, a small head), soft at the edge, no outline, no shadow (a bird in the air throws none on the picture).
//  * "Kilau" is the lens's star-filter glint (the same light PosterGlints lays on the cards' edges and the photo's highlights): added
//    light, not a white star shape. Round 4.3.5 K: added light alone vanishes on a bright ground, so over a bright ground it gets a soft dark
//    under-light (as a sparkle sticker has its soft shadow), stronger the brighter the ground; over a dark ground none.

internal object PosterStickerArt {
    /** The bird's ink: a near-black with a touch of the sky's blue, as a silhouette against bright light looks. */
    val BIRD = Color(0xFF15191F)

    /** The swallow, facing right, in the unit box (-1..1, y down): one closed outline (beak, crown, far wing, tail streamers, near wing, chest). */
    val bird: Path by lazy {
        Path().apply {
            moveTo(0.58f, 0.055f)
            // crown and nape
            cubicTo(0.53f, 0.0f, 0.45f, -0.03f, 0.38f, -0.025f)
            cubicTo(0.33f, -0.02f, 0.29f, -0.01f, 0.25f, 0.0f)
            // the far wing, raised and swept back: leading edge out to the pointed tip
            cubicTo(0.17f, -0.22f, 0.0f, -0.55f, -0.42f, -0.87f)
            // trailing edge back to the body (a long crescent)
            cubicTo(-0.26f, -0.62f, -0.12f, -0.30f, -0.05f, 0.015f)
            // back, to the root of the tail
            cubicTo(-0.12f, 0.03f, -0.18f, 0.04f, -0.25f, 0.05f)
            // the upper streamer, its tip, the fork
            cubicTo(-0.48f, 0.0f, -0.74f, -0.05f, -0.97f, -0.075f)
            cubicTo(-0.78f, -0.02f, -0.62f, 0.05f, -0.50f, 0.115f)
            // the lower streamer
            cubicTo(-0.64f, 0.2f, -0.78f, 0.27f, -0.92f, 0.33f)
            cubicTo(-0.70f, 0.29f, -0.45f, 0.21f, -0.23f, 0.16f)
            // belly to the near wing's root
            cubicTo(-0.15f, 0.165f, -0.07f, 0.17f, 0.0f, 0.165f)
            // the near wing, lowered: trailing edge out to its tip, leading edge back to the shoulder
            cubicTo(-0.06f, 0.34f, -0.14f, 0.55f, -0.27f, 0.76f)
            cubicTo(-0.02f, 0.55f, 0.13f, 0.33f, 0.21f, 0.15f)
            // chest, throat, chin
            cubicTo(0.29f, 0.15f, 0.37f, 0.14f, 0.44f, 0.12f)
            cubicTo(0.5f, 0.105f, 0.55f, 0.085f, 0.58f, 0.055f)
            close()
        }
    }

    private val birdPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** The swallow, centred on the current origin, [size] pixels across. [soft] = a slight blur of the edge (the renderer's software canvas only). */
    fun DrawScope.drawBird(size: Float, soft: Boolean) {
        val k = size / 2f
        drawIntoCanvas { c ->
            val p = android.graphics.Path(bird.asAndroidPath())
            p.transform(android.graphics.Matrix().apply { setScale(k, k) })
            birdPaint.reset()
            birdPaint.isAntiAlias = true
            birdPaint.color = BIRD.toArgb()
            // a bird is never razor sharp in a photo, but a near one is still crisp: a little softness (focus, a little motion), capped
            if (soft) birdPaint.maskFilter = blurMask((0.7f + size * 0.0015f).coerceAtMost(1.6f))
            c.nativeCanvas.drawPath(p, birdPaint)
            birdPaint.maskFilter = null
        }
    }

    /**
     * How dark the glint sticker's under-light is over a ground of mean luminance [luma] (0..1): none over a dark ground (the light shows by
     * itself), up to 0.6 over a white one.
     */
    fun glintShadow(luma: Float): Float {
        val t = ((luma - 0.35f) / 0.5f).coerceIn(0f, 1f)
        return 0.6f * t * t * (3f - 2f * t)
    }

    /**
     * The glint sticker centred on ([cx], [cy]) of [canvas]: the lens's glint, main spikes [size] / 2 long, turned with the sticker, over a
     * ground of mean luminance [luma] (see [glintShadow]). [unit] = device pixels per canvas unit.
     */
    fun drawGlint(canvas: android.graphics.Canvas, sprites: GlintSprites, cx: Float, cy: Float, size: Float, unit: Float, rotation: Float, tint: Color, luma: Float, plain: Boolean = false) {
        sprites.draw(canvas, cx, cy, size / 2f, unit, 1f, tint, rotation, glintShadow(luma), plain, bold = true)
    }
}

/** The blurred silhouettes of the user's sticker pictures (one small mask per picture, the shadow scales with the sticker). */
internal class StickerShadowCache {
    private val map = IdentityHashMap<Bitmap, Pair<Bitmap, FloatArray>>()

    /** The mask (ALPHA_8) and where it lands relative to the picture: left, top, right, bottom as fractions of the picture's size. */
    fun get(bmp: Bitmap): Pair<Bitmap, FloatArray>? {
        map[bmp]?.let { return it }
        if (bmp.isRecycled) return null
        val n = 128
        val sw = if (bmp.width >= bmp.height) n else max(4, n * bmp.width / bmp.height)
        val sh = if (bmp.width >= bmp.height) max(4, n * bmp.height / bmp.width) else n
        val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        // sigma = 2.2 percent of the long side, like the Windows sticker shadow
        val sigma = 0.022f * n
        val p = Paint().apply { maskFilter = blurMask(sigma) }
        val off = IntArray(2)
        val mask = small.extractAlpha(p, off)
        if (small !== bmp) small.recycle()
        val frac = floatArrayOf(off[0] / sw.toFloat(), off[1] / sh.toFloat(), (off[0] + mask.width) / sw.toFloat(), (off[1] + mask.height) / sh.toFloat())
        val out = mask to frac
        map[bmp] = out
        return out
    }

    fun retainOnly(live: Collection<Bitmap>) {
        if (map.isEmpty()) return
        val keep = IdentityHashMap<Bitmap, Boolean>()
        for (b in live) keep[b] = true
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (!keep.containsKey(e.key)) {
                e.value.first.recycle()
                it.remove()
            }
        }
    }
}

/**
 * Draws one sticker of the poster (placement, turn, mirror, size). [canvas] is the renderer's software canvas in poster space ([unit] device
 * pixels per poster pixel). The user's pictures get a soft shadow along the key light (a print lying on the poster); the bird is in the air
 * and gets none; the glint gets its soft under-light over a bright ground only ([groundLuma]: the mean luminance around a point, 0..1).
 */
internal fun DrawScope.drawSticker(
    s: PosterSticker,
    shadowAngle: Float,
    images: Map<String, Bitmap>,
    shadows: StickerShadowCache,
    paint: Paint,
    glintTint: Color,
    sprites: GlintSprites,
    unit: Float,
    groundLuma: (x: Float, y: Float, r: Float) -> Float,
) {
    val size = PosterStickers.BASE * s.scale
    when (s.kind) {
        StickerKind.CUSTOM -> drawCustomSticker(s, size, shadowAngle, images, shadows, paint)
        StickerKind.BURUNG ->
            withTransform({
                translate(s.x, s.y)
                rotate(s.rotation, Offset.Zero)
                if (s.flip) scale(-1f, 1f, Offset.Zero)
            }) {
                with(PosterStickerArt) { drawBird(size, soft = true) }
            }
        StickerKind.KILAU -> {
            // the ground under the glint's core and its inner spikes
            val luma = groundLuma(s.x, s.y, size * 0.18f)
            drawIntoCanvas { c -> PosterStickerArt.drawGlint(c.nativeCanvas, sprites, s.x, s.y, size, unit, s.rotation, glintTint, luma) }
        }
    }
}

private fun DrawScope.drawCustomSticker(s: PosterSticker, size: Float, shadowAngle: Float, images: Map<String, Bitmap>, shadows: StickerShadowCache, paint: Paint) {
    val bmp = s.imageKey?.let { images[it] } ?: return
    if (bmp.isRecycled || bmp.width < 1 || bmp.height < 1) return
    val a = Math.toRadians(shadowAngle.toDouble())
    val r = Math.toRadians(-s.rotation.toDouble())
    // the shadow's direction in the sticker's own frame, so it follows the poster's key light whatever the sticker's turn
    val gx = (cos(a) * cos(r) - sin(a) * sin(r)).toFloat() * 0.06f
    val gy = (cos(a) * sin(r) + sin(a) * cos(r)).toFloat() * 0.06f
    val sx = if (s.flip) -gx else gx
    val place = android.graphics.Matrix().apply {
        postScale(if (s.flip) -1f else 1f, 1f)
        postRotate(s.rotation)
        postTranslate(s.x, s.y)
    }
    val aspect = bmp.width.toFloat() / bmp.height
    val dw = if (aspect >= 1f) size else size * aspect
    val dh = if (aspect >= 1f) size / aspect else size
    drawIntoCanvas { c ->
        val nc = c.nativeCanvas
        nc.save()
        nc.concat(place)
        val dst = RectF(-dw / 2f, -dh / 2f, dw / 2f, dh / 2f)
        shadows.get(bmp)?.let { (mask, f) ->
            val ox = sx * size / 2f
            val oy = gy * size / 2f
            paint.reset()
            paint.isFilterBitmap = true
            paint.color = Color.Black.copy(alpha = 0.32f).toArgb()
            nc.drawBitmap(mask, null, RectF(dst.left + f[0] * dw + ox, dst.top + f[1] * dh + oy, dst.left + f[2] * dw + ox, dst.top + f[3] * dh + oy), paint)
        }
        paint.reset()
        paint.isFilterBitmap = true
        paint.isAntiAlias = true
        nc.drawBitmap(bmp, null, dst, paint)
        nc.restore()
    }
}

/** A built-in sticker filling its box, for the editor's buttons (the glint on a small dark disc: light shows on dark, not on the pale button). */
@Composable
fun StickerPreview(kind: StickerKind, modifier: Modifier = Modifier) {
    val sprites = remember { GlintSprites() }
    Canvas(modifier) {
        val side = minOf(size.width, size.height)
        when (kind) {
            StickerKind.CUSTOM -> Unit
            StickerKind.BURUNG -> translate(size.width / 2f, size.height / 2f) { with(PosterStickerArt) { drawBird(side * 0.96f, soft = false) } }
            StickerKind.KILAU -> {
                drawCircle(Color(0xFF1B1E24), side * 0.46f, Offset(size.width / 2f, size.height / 2f))
                // laid with source-over on the dark disc (no blend mode on the screen's hardware canvas)
                drawIntoCanvas { c ->
                    PosterStickerArt.drawGlint(c.nativeCanvas, sprites, size.width / 2f, size.height / 2f, side * 0.86f, 1f, 0f, PosterGlints.tint(PosterGrade.ASLI), luma = 0.1f, plain = true)
                }
            }
        }
    }
}
