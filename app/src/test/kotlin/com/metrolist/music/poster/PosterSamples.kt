package com.metrolist.music.poster

import android.graphics.Bitmap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.sqrt

/** Made-up material for the poster tests (ported from the Windows PosterSamples): drawn covers, invented titles, a synthetic photo with its mask. */
internal object PosterSamples {
    private class Spec(val title: String, val artist: String, val seconds: Int)

    private val specs =
        listOf(
            Spec("Senja di Ujung Jalan", "Rara Wulandari", 213),
            Spec("Arah Pulang", "Dimas & Kala", 187),
            Spec("Lampu Kota Menyala Sampai Pagi Hari Tiba", "Langit Timur & Teman Lama", 241),
            Spec("Hujan Sore Hari", "Nadira", 199),
            Spec("Pelan Pelan Saja", "Kawan Lama", 226),
        )

    fun song(i: Int): PosterSong = specs[i % specs.size].let { PosterSong("sample-$i", it.title, it.artist, null, it.seconds) }

    private fun draw(w: Int, h: Int, block: DrawScope.() -> Unit): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(android.graphics.Canvas(bmp)), Size(w.toFloat(), h.toFloat()), block)
        return bmp
    }

    fun cover(i: Int): Bitmap {
        val n = 544
        return draw(n, n) {
            val full = Size(n.toFloat(), n.toFloat())
            when (i % 5) {
                0 -> {
                    drawRect(Brush.verticalGradient(listOf(Color(0xFFFFB347), Color(0xFFD94A6A), Color(0xFF6A2C70))), size = full)
                    drawCircle(Color(0xFFFFE9C7), 170f, Offset(272f, 330f))
                    for (k in 0 until 4) drawRect(Color(0xFF6A2C70), Offset(90f, 330f + k * 26f), Size(364f, 8f + k * 4f))
                }
                1 -> {
                    drawRect(Brush.radialGradient(listOf(Color(0xFF22C7B8), Color(0xFF0F5C8C), Color(0xFF071E45)), center = Offset(272f, 272f), radius = 420f), size = full)
                    for (k in 1..4) drawCircle(Color.White.copy(alpha = 0.22f), 50f + k * 52f, Offset(272f, 272f), style = Stroke(5f))
                    drawCircle(Color.White.copy(alpha = 0.9f), 26f, Offset(272f, 272f))
                }
                2 -> {
                    drawRect(Brush.verticalGradient(listOf(Color(0xFF0B1230), Color(0xFF30204F), Color(0xFFE0713F))), size = full)
                    var seed = 12345L
                    fun next(): Float {
                        seed = seed * 6364136223846793005L + 1442695040888963407L
                        return ((seed ushr 40) and 0xFFFFFFL) / 16777216f
                    }
                    repeat(60) {
                        val y = 250f + next() * 270f
                        drawCircle(Color(0xFFFFD27A).copy(alpha = 0.25f + next() * 0.6f), 2f + next() * 7f, Offset(next() * n, y))
                    }
                    drawRect(Color(0xFF0B1230), Offset(0f, 430f), Size(n.toFloat(), 114f))
                }
                3 -> {
                    drawRect(Brush.linearGradient(listOf(Color(0xFF8FA6BF), Color(0xFF3B4C66)), start = Offset.Zero, end = Offset(n.toFloat(), n.toFloat())), size = full)
                    for (k in 0 until 14) drawLine(Color.White.copy(alpha = 0.35f), Offset(k * 46f - 40f, 0f), Offset(k * 46f - 190f, n.toFloat()), 4f)
                    drawRoundRect(Color(0xFF1B2433), Offset(160f, 190f), Size(224f, 170f), CornerRadius(30f))
                }
                else -> {
                    drawRect(Brush.linearGradient(listOf(Color(0xFFFF9BC6), Color(0xFF6E4BD8)), start = Offset.Zero, end = Offset(n.toFloat(), n.toFloat())), size = full)
                    drawCircle(Color(0xFFFFE3F1), 200f, Offset(380f, 190f))
                    drawCircle(Color.White.copy(alpha = 0.85f), 36f, Offset(150f, 400f), style = Stroke(10f))
                }
            }
        }
    }

    /** A simple album cover: a vertical gradient and a disc. */
    fun art(top: Color, bottom: Color, accent: Color): Bitmap {
        val n = 544
        return draw(n, n) {
            drawRect(Brush.verticalGradient(listOf(top, bottom)), size = Size(n.toFloat(), n.toFloat()))
            drawCircle(accent, 150f, Offset(272f, 290f))
        }
    }

    /**
     * Cover sets for the backdrop sheet (lead review 2026-11-13): "blue" (all blue covers), "greenred" (green and red covers mixed), "grey"
     * (black, white and grey covers). Each cover with its tint.
     */
    fun coverSet(name: String): List<Pair<Bitmap, Color>> =
        when (name) {
            "blue" -> listOf(
                art(Color(0xFF0B3D91), Color(0xFF1E88E5), Color(0xFFBBDEFB)) to Color(0xFF1565C0),
                art(Color(0xFF4FC3F7), Color(0xFF01579B), Color(0xFFFFFFFF)) to Color(0xFF0288D1),
                art(Color(0xFF283593), Color(0xFF5C6BC0), Color(0xFF1A237E)) to Color(0xFF3949AB),
                art(Color(0xFF1565C0), Color(0xFF0D47A1), Color(0xFF90CAF9)) to Color(0xFF1565C0),
                art(Color(0xFF00838F), Color(0xFF006064), Color(0xFFB2EBF2)) to Color(0xFF00838F),
            )
            "greenred" -> listOf(
                art(Color(0xFF1B5E20), Color(0xFF43A047), Color(0xFFC8E6C9)) to Color(0xFF2E7D32),
                art(Color(0xFFC62828), Color(0xFFE53935), Color(0xFFFFCDD2)) to Color(0xFFC62828),
                art(Color(0xFF2E7D32), Color(0xFF66BB6A), Color(0xFF1B5E20)) to Color(0xFF388E3C),
                art(Color(0xFFB71C1C), Color(0xFFEF5350), Color(0xFF212121)) to Color(0xFFD32F2F),
                art(Color(0xFF33691E), Color(0xFF7CB342), Color(0xFFF1F8E9)) to Color(0xFF558B2F),
            )
            else -> listOf(
                art(Color(0xFF101010), Color(0xFF808080), Color(0xFFFFFFFF)) to Color(0xFF555555),
                art(Color(0xFFFFFFFF), Color(0xFFC8C8C8), Color(0xFF000000)) to Color(0xFFDDDDDD),
                art(Color(0xFF3A3A3C), Color(0xFF2A2A2C), Color(0xFF9A9A9A)) to Color(0xFF333333),
                art(Color(0xFF9A9A9A), Color(0xFF5A5A5A), Color(0xFF202020)) to Color(0xFF777777),
                art(Color(0xFF202020), Color(0xFF000000), Color(0xFFE0E0E0)) to Color(0xFF111111),
            )
        }

    /** A no-photo poster of five songs with the covers of [set]. */
    fun stateWithCovers(set: String): PosterState {
        val st = state(5)
        for ((k, c) in coverSet(set).withIndex()) {
            st.covers["sample-$k"] = c.first
            st.tints["sample-$k"] = c.second
        }
        return st
    }

    /** A made-up sticker picture with a see-through background (a round badge). */
    fun customSticker(): Bitmap =
        draw(512, 512) {
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFD27A), Color(0xFFFF8A3C), Color(0xFFD9442B)), center = Offset(210f, 200f), radius = 310f), 240f, Offset(256f, 256f))
            drawCircle(Color.White.copy(alpha = 0.85f), 190f, Offset(256f, 256f), style = Stroke(9f))
            drawCircle(Color(0xFFFFF4DD), 45f, Offset(256f, 256f))
        }

    private val tints = listOf(Color(0xFFD94A6A), Color(0xFF0F5C8C), Color(0xFFE0713F), Color(0xFF5A6F8A), Color(0xFF8E62D0))

    fun state(count: Int, layout: PosterLayout = PosterLayout.ORBIT, photo: PosterPhoto? = null): PosterState {
        val st = PosterState()
        st.layout = layout
        st.setSongs(List(count) { song(it) })
        for (k in 0 until count) {
            st.covers["sample-$k"] = cover(k)
            st.tints["sample-$k"] = tints[k % tints.size]
        }
        if (photo != null) {
            st.photo = photo
            st.cutout = CutoutPhase.DONE
        }
        return st
    }

    /** Where [nightPhoto] has its small point lights (photo pixels) and its pale patches on the person's clothes (not lights). */
    val nightLights = listOf(270 to 60, 280 to 200, 60 to 450, 480 to 430, 525 to 150)
    val palePatches = listOf(250 to 640, 300 to 700, 230 to 760)

    /**
     * A 540 x 960 night photo: a dark street with five small point lights (near white, a few pixels: what a star filter turns into stars) and
     * a person in pale clothes with a few paler patches on them (bright, but no light: never a glint), with the person's mask.
     */
    fun nightPhoto(w: Int = 540, h: Int = 960): PosterPhoto {
        val px = IntArray(w * h)
        val a = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val t = y / h.toFloat()
                var r = (26 + 20 * t).toInt()
                var g = (30 + 14 * t).toInt()
                var b = (44 + 6 * t).toInt()
                val dx = (x - w * 0.5f) / (w * 0.24f)
                val dy = (y - h * 0.72f) / (h * 0.30f)
                val hx = (x - w * 0.5f) / (w * 0.11f)
                val hy = (y - h * 0.40f) / (h * 0.075f)
                val m = maxOf(1f - sqrt(dx * dx + dy * dy), 1f - sqrt(hx * hx + hy * hy))
                val alpha = (m * 12f).coerceIn(0f, 1f)
                r = (200 * alpha + r * (1 - alpha)).toInt()
                g = (204 * alpha + g * (1 - alpha)).toInt()
                b = (214 * alpha + b * (1 - alpha)).toInt()
                px[y * w + x] = (255 shl 24) or (r shl 16) or (g shl 8) or b
                a[y * w + x] = (alpha * 255f + 0.5f).toInt().toByte()
            }
        }
        fun spot(cx: Int, cy: Int, rad: Int, c: Int) {
            for (y in cy - rad..cy + rad) for (x in cx - rad..cx + rad) if (x in 0 until w && y in 0 until h && (x - cx) * (x - cx) + (y - cy) * (y - cy) <= rad * rad) px[y * w + x] = c
        }
        for ((x, y) in nightLights) spot(x, y, 2, (255 shl 24) or (255 shl 16) or (250 shl 8) or 240)
        for ((x, y) in palePatches) spot(x, y, 3, (255 shl 24) or (226 shl 16) or (231 shl 8) or 242)
        return PosterPhoto(PosterImage(w, h, px), a)
    }

    /** A 540 x 960 stand-in photo: a warm wall and a soft elliptical "person" with its mask, so the tests need no network. */
    fun syntheticPhoto(w: Int = 540, h: Int = 960): PosterPhoto {
        val px = IntArray(w * h)
        val a = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val t = y / h.toFloat()
                val r = (230 - 90 * t).toInt()
                val g = (200 - 110 * t).toInt()
                val b = (160 - 120 * t).toInt()
                val dx = (x - w * 0.5f) / (w * 0.26f)
                val dy = (y - h * 0.72f) / (h * 0.30f)
                val body = 1f - sqrt(dx * dx + dy * dy)
                val hx = (x - w * 0.5f) / (w * 0.11f)
                val hy = (y - h * 0.40f) / (h * 0.075f)
                val head = 1f - sqrt(hx * hx + hy * hy)
                val m = maxOf(body, head)
                val alpha = (m * 12f).coerceIn(0f, 1f)
                val person = 0x6A3B22
                val rr = (((person shr 16) and 255) * alpha + r * (1 - alpha)).toInt()
                val gg = (((person shr 8) and 255) * alpha + g * (1 - alpha)).toInt()
                val bb = ((person and 255) * alpha + b * (1 - alpha)).toInt()
                px[y * w + x] = (255 shl 24) or (rr shl 16) or (gg shl 8) or bb
                a[y * w + x] = (alpha * 255f + 0.5f).toInt().toByte()
            }
        }
        return PosterPhoto(PosterImage(w, h, px), a)
    }
}
