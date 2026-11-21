package com.metrolist.music.poster

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.max

/**
 * What a frame costs while the user moves something on the poster (Robolectric's native Skia on the PC, not a phone: only the
 * proportions carry over), and that the caches behind it paint the same picture. Review 2026-11-12 finding 24: moving the photo or the
 * "Gelap / Kabur / Cahaya kartu" sliders used to repaint every card layer and the glow in every frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PosterGestureTimingTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun frames(name: String, st: PosterState, w: Int, h: Int, level: Int, n: Int = 8, warm: Boolean = true, step: (Int) -> Unit) {
        val r = PosterRenderer(context, PosterAssets())
        var out: Bitmap? = null
        out = r.render(st.frame(), w, h, level, out)
        val cold = r.lastMillis
        // what the preview does when the editor is idle for a moment (PosterPreview -> PosterRenderer.warm)
        var warmMs = 0L
        if (warm) {
            val t0 = System.nanoTime()
            r.warm(st.frame(), w, h, level) { false }
            warmMs = (System.nanoTime() - t0) / 1_000_000
        }
        val times = ArrayList<Long>()
        val stages = Array(8) { ArrayList<Long>() }
        repeat(n) { k ->
            step(k)
            out = r.render(st.frame(), w, h, level, out)
            times.add(r.lastMillis)
            for (i in 0 until 8) stages[i].add(r.stageMillis[i])
        }
        val med = times.sorted()[times.size / 2]
        val stageMed = stages.joinToString("/") { it.sorted()[it.size / 2].toString() }
        println(
            "poster gesture $name ${w}x$h level $level${if (warm) "" else " (not warmed)"}: cold $cold ms, warm-up $warmMs ms, first frame ${times[0]} ms, " +
                "warm median $med ms (stages ground/backdrop/cards/person/light/grade/bloom/post = $stageMed), card layers painted ${r.cardLayerBuilds}, " +
                "split ${r.cardSplits}, recomposed ${r.cardRecomposes}, glow layers built ${r.glowBuilds}, glints ${r.lastGlints.size}, glint pictures made ${r.sprites.made}, photo searched ${r.spotSearches}x",
        )
    }

    private fun scene(): PosterState {
        val st = PosterSamples.state(5, photo = PosterSamples.syntheticPhoto(1536, 2048))
        st.photoZoom = 1.2f
        return st
    }

    @Test
    fun timing_photoGesturesAndSliders() {
        for ((w, h) in listOf(720 to 1280, 1080 to 1920)) {
            scene().let { st -> frames("photo-pan", st, w, h, 0) { st.photoPanX = -40f + it * 7f; st.photoPanY = 30f - it * 5f } }
            scene().let { st -> frames("photo-pinch", st, w, h, 0) { st.photoZoom = 1.2f + it * 0.012f } }
            scene().let { st -> frames("slider-dim", st, w, h, 0) { st.dim = 0.12f + it * 0.02f } }
            scene().let { st -> frames("slider-glow", st, w, h, 0) { st.glow = 0.6f - it * 0.03f } }
            scene().let { st -> frames("slider-blur", st, w, h, 0) { st.blur = 0.2f + it * 0.03f } }
            scene().let { st -> frames("card-drag", st, w, h, 0) { st.poses[1] = PosterEdit.moved(st.poses[1], 9f, 5f) } }
            // round K: the same drag with "Kilau" off, in the same run (what the glints cost per frame)
            scene().let { st ->
                st.fxSparkle = 0f
                frames("card-drag-kilau-off", st, w, h, 0) { st.poses[1] = PosterEdit.moved(st.poses[1], 9f, 5f) }
            }
            scene().let { st ->
                st.fxSparkle = 1f
                frames("card-drag-kilau-full", st, w, h, 0) { st.poses[1] = PosterEdit.moved(st.poses[1], 9f, 5f) }
            }
        }
        scene().let { st -> frames("photo-pan", st, 720, 1280, 0, warm = false) { st.photoPanX = -40f + it * 7f } }
        scene().let { st -> frames("photo-pan", st, 720, 1280, 1) { st.photoPanX = -40f + it * 7f } }
    }

    /** Largest and mean difference of the colour channels of two pictures of the same size. */
    private fun diff(a: Bitmap, b: Bitmap): Pair<Int, Double> {
        assertEquals(a.width, b.width)
        val w = a.width
        val h = a.height
        val pa = IntArray(w * h).also { a.getPixels(it, 0, w, 0, 0, w, h) }
        val pb = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        var worst = 0
        var sum = 0L
        for (i in pa.indices) {
            for (sh in intArrayOf(0, 8, 16)) {
                val d = abs(((pa[i] shr sh) and 255) - ((pb[i] shr sh) and 255))
                worst = max(worst, d)
                sum += d
            }
        }
        return worst to sum.toDouble() / (pa.size * 3)
    }

    private fun fresh(st: PosterState, w: Int, h: Int): Bitmap = PosterRenderer(context, PosterAssets()).render(st.frame(), w, h, 0)

    /** No visibly cheaper draft (the owner's rule): every frame painted from the caches is the picture a fresh renderer paints. */
    @Test
    fun gestureCaches_paintTheSamePicture_asAFreshRenderer() {
        val w = 540
        val h = 960
        val st = PosterSamples.state(4, photo = PosterSamples.syntheticPhoto(720, 1280))
        st.photoZoom = 1.25f
        // one card further back with depth of field (its layer is blurred: the recompose path that blurs again)
        st.cardDof = 1f
        st.poses[2] = st.poses[2].copy(lift = 0f, scale = 0.6f)
        val r = PosterRenderer(context, PosterAssets())
        var out: Bitmap? = null
        out = r.render(st.frame(), w, h, 0, out)
        assertTrue(r.warm(st.frame(), w, h, 0) { false })
        val painted = r.cardLayerBuilds
        assertEquals("every card split once", 4, r.cardSplits)
        assertEquals("the glow's layers built once", 1, r.glowBuilds)
        val results = ArrayList<String>()
        fun check(what: String) {
            out = r.render(st.frame(), w, h, 0, out)
            val (worst, mean) = diff(out!!, fresh(st, w, h))
            results.add("$what: worst $worst, mean ${"%.3f".format(mean)}")
            assertTrue("$what: worst channel difference $worst", worst <= 8)
            assertTrue("$what: mean difference $mean", mean < 0.35)
        }
        for (k in 0 until 3) {
            st.photoPanX = -30f + 14f * k
            st.photoPanY = 12f * k
            check("pan $k")
        }
        st.photoZoom = 1.4f
        check("pinch")
        st.dim = 0.33f
        check("dim")
        st.blur = 0.25f
        check("blur")
        println("poster gesture caches vs a fresh renderer: $results")
        assertEquals("no card was painted again while the ground moved", painted, r.cardLayerBuilds)
        assertTrue(r.cardRecomposes >= 6 * 4)
        assertEquals("the glow was never painted again for a moved photo", 1, r.glowBuilds)
        // the glow itself changes: painted straight while it changes (its layers would be stale at once)
        st.glow = 0.35f
        check("glow")
        st.glow = 0.3f
        check("glow 2")
        // a card moved: only that card is painted again; its new layer is split again only when the ground moves under it
        st.poses[0] = PosterEdit.moved(st.poses[0], 30f, 10f)
        check("card")
        assertEquals(painted + 1, r.cardLayerBuilds)
        st.photoPanX = 5f
        check("pan after the card")
        assertEquals(5, r.cardSplits)
        // without warming: the first frame of a gesture splits the cards then and there, with the same picture
        val cold = PosterRenderer(context, PosterAssets())
        cold.render(st.frame(), w, h, 0)
        st.photoPanX = -8f
        val (worst, mean) = diff(cold.render(st.frame(), w, h, 0), fresh(st, w, h))
        println("poster gesture, not warmed: worst $worst mean $mean, splits ${cold.cardSplits}")
        assertTrue(worst <= 8 && mean < 0.35)
    }
}
