package com.metrolist.music.poster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.poster.cutout.CutoutOutcome
import com.metrolist.music.poster.cutout.PosterCutout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The poster drawn for real (Robolectric's native Skia, the same software canvas as on a phone) and written to PNGs in build/poster-gallery for looking at:
 * the export size, the mandatory mark, the ground cache, every look, the stickers, the preview composable, and (when a photo is put in build/poster-input)
 * the whole road photo -> cut-out network -> poster.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PosterRenderTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dir = File("build/poster-gallery").apply { mkdirs() }

    private fun save(name: String, b: Bitmap) {
        File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun render(st: PosterState, w: Int = POSTER_W, h: Int = POSTER_H, level: Int = 0): Bitmap {
        val r = PosterRenderer(context, PosterAssets())
        val t0 = System.nanoTime()
        val b = r.render(st.frame(), w, h, level)
        println("poster render ${w}x$h level $level: ${(System.nanoTime() - t0) / 1_000_000} ms")
        return b
    }

    private fun luminance(c: Int): Int = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000

    @Test
    fun export_isFullSize_andAlwaysCarriesTheMark() {
        val st = PosterSamples.state(5)
        val b = renderPosterBitmap(context, st.frame(), PosterAssets())
        assertEquals(PosterExportSize.W, b.width)
        assertEquals(PosterExportSize.H, b.height)
        save("export-orbit5-nophoto", b)
        // the word "Auralys" is drawn last in near white on the bottom band, centred
        var bright = 0
        for (y in (PosterFx.MARK_CY - 22).toInt()..(PosterFx.MARK_CY + 22).toInt()) for (x in 380..760) if (luminance(b.getPixel(x, y)) > 215) bright++
        assertTrue("bright mark pixels $bright", bright > 300)
        // the logo tile (navy) sits left of the word
        var navy = 0
        for (y in (PosterFx.MARK_CY - 20).toInt()..(PosterFx.MARK_CY + 20).toInt()) for (x in 380..520) {
            val c = b.getPixel(x, y)
            if ((c and 255) > ((c shr 16) and 255) + 12) navy++
        }
        assertTrue("logo tile pixels $navy", navy > 100)
    }

    @Test
    fun movingACard_doesNotRepaintTheGround() {
        val st = PosterSamples.state(3, photo = PosterSamples.syntheticPhoto())
        val r = PosterRenderer(context, PosterAssets())
        r.render(st.frame(), 540, 960, 0)
        assertEquals(3, r.cardLayerBuilds)
        st.poses[0] = PosterEdit.moved(st.poses[0], 80f, 40f)
        st.fxLeak = 0.2f
        r.render(st.frame(), 540, 960, 0)
        assertEquals(1, r.baseBuilds)
        // only the moved card is painted again
        assertEquals(4, r.cardLayerBuilds)
        st.dim = 0.4f
        r.render(st.frame(), 540, 960, 0)
        assertEquals(2, r.baseBuilds)
        // a new ground means new frosted glass behind every card: put into their glass, no card painted again (finding 24)
        assertEquals(4, r.cardLayerBuilds)
        assertEquals(3, r.cardRecomposes)
    }

    /** Warm frames while a card is dragged (the ground cached), at a phone preview size and at the export size; prints where the time goes. */
    @Test
    fun timing_warmFramesWhileDragging() {
        val st = PosterSamples.state(5, photo = PosterSamples.syntheticPhoto())
        st.addSticker(StickerKind.KILAU)
        for ((w, h, lvl) in listOf(Triple(720, 1280, 0), Triple(720, 1280, 1), Triple(1080, 1920, 0))) {
            val r = PosterRenderer(context, PosterAssets())
            var out: Bitmap? = null
            val times = ArrayList<Long>()
            repeat(6) { k ->
                st.poses[1] = PosterEdit.moved(st.poses[1], 12f, 6f)
                out = r.render(st.frame(), w, h, lvl, out)
                if (k > 0) times.add(r.lastMillis)
                println("poster frame ${w}x$h level $lvl #$k: ${r.lastMillis} ms, stages ground/backdrop/cards/person/light/grade/bloom/post = ${r.stageMillis.joinToString("/")}, glints ${r.lastGlints.size}, sprites made ${r.sprites.made}")
            }
            println("poster frame ${w}x$h level $lvl: median ${times.sorted()[times.size / 2]} ms, photo searched ${r.spotSearches}x")
            assertEquals("the photo is searched once, not per frame", 1, r.spotSearches)
        }
    }

    @Test
    fun gallery_looksLayoutsAndStickers() {
        val photo = PosterSamples.syntheticPhoto()
        save("photo-orbit3", render(PosterSamples.state(3, photo = photo)))
        val five = PosterSamples.state(5, photo = photo)
        save("photo-orbit5", render(five))
        for (l in listOf(PosterLayout.TUMPUK, PosterLayout.DERET, PosterLayout.KOLOM)) save("layout-${l.name.lowercase()}", render(PosterSamples.state(4, l, photo), 540, 960))
        for (p in PosterFxPreset.entries) {
            val st = PosterSamples.state(4, photo = photo)
            st.applyPreset(p)
            save("preset-${p.name.lowercase()}", render(st, 540, 960))
            // no photo: the studio backdrop from the covers
            val bare = PosterSamples.state(5)
            bare.applyPreset(p)
            save("nophoto-${p.name.lowercase()}", render(bare))
        }
        for (g in PosterGrade.entries) {
            val st = PosterSamples.state(3)
            st.grade = g
            save("grade-${g.name.lowercase()}", render(st, 540, 960))
        }
        // other cover sets without a photo (lead review 2026-11-13: the backdrop takes the covers' one shared hue, never a mud)
        for (set in listOf("blue", "greenred", "grey")) save("nophoto-set-$set", render(PosterSamples.stateWithCovers(set)))
        val glass = PosterSamples.state(3, photo = photo)
        glass.applyMaterial(CardMaterial.BENING)
        glass.cardPerspective = 0.9f
        glass.cardThickness = 1f
        glass.cardDof = 1f
        glass.poses[2] = glass.poses[2].copy(lift = 0f, scale = 0.6f)
        save("glass-clear-dof", render(glass))
        val st = PosterSamples.state(2, photo = photo)
        st.stickerImages["k1"] = PosterSamples.customSticker()
        st.addSticker(StickerKind.BURUNG)
        st.addSticker(StickerKind.KILAU)
        val i = st.addSticker(StickerKind.CUSTOM, "k1")
        st.stickers[i] = st.stickers[i].copy(x = 760f, y = 1500f, scale = 1.4f, rotation = -15f)
        st.stickers[0] = st.stickers[0].copy(x = 300f, y = 300f, scale = 1.6f, rotation = 10f, flip = true)
        st.stickers[1] = st.stickers[1].copy(x = 820f, y = 420f, scale = 0.7f)
        save("stickers", render(st))
        // the two built-in stickers large, on the plain backdrop: what the editor's Burung and Kilau make
        val big = PosterSamples.state(1)
        big.poses[0] = big.poses[0].copy(y = 1350f, scale = 0.6f)
        big.addSticker(StickerKind.BURUNG)
        big.stickers[0] = big.stickers[0].copy(x = 330f, y = 420f, scale = 2.6f)
        big.addSticker(StickerKind.BURUNG)
        big.stickers[1] = big.stickers[1].copy(x = 820f, y = 300f, scale = 0.9f, rotation = -12f, flip = true)
        big.addSticker(StickerKind.KILAU)
        big.stickers[2] = big.stickers[2].copy(x = 760f, y = 760f, scale = 1.2f)
        big.addSticker(StickerKind.KILAU)
        big.stickers[3] = big.stickers[3].copy(x = 250f, y = 900f, scale = 0.5f)
        save("stickers-builtin", render(big))
        // a photo without its cut-out yet (still running) and the lighter preview level
        val running = PosterSamples.state(3, photo = PosterPhoto(photo.image, ByteArray(photo.w * photo.h)))
        running.cutout = CutoutPhase.RUNNING
        save("cutout-running", render(running, 540, 960))
        save("preview-level1", render(PosterSamples.state(5, photo = photo), 540, 960, level = 1))
        // zoomed out: the photo sits on a blurred copy like a framed print
        val small = PosterSamples.state(2, photo = photo)
        small.photoZoom = 0.6f
        small.blur = 0.3f
        save("photo-zoomed-out", render(small, 540, 960))
    }

    @Test
    fun preview_composable_paintsThePoster() {
        val st = PosterSamples.state(4, photo = PosterSamples.syntheticPhoto())
        st.selected = 1
        val rendered = AtomicInteger(0)
        compose.setContent {
            PosterPreview(st, PosterAssets(), Modifier.size(270.dp, 480.dp), touch = null, level = 0, onRendered = { rendered.incrementAndGet() })
        }
        compose.waitUntil(30_000) { rendered.get() > 0 }
        compose.waitForIdle()
        val img = compose.onRoot().captureToImage().asAndroidBitmap()
        save("preview-composable", img)
        // the middle of the picture is painted, not the empty navy placeholder
        assertTrue(img.width > 100)
        var varied = 0
        val ref = img.getPixel(img.width / 2, img.height / 2)
        for (y in 0 until img.height step 8) for (x in 0 until img.width step 8) if (img.getPixel(x, y) != ref) varied++
        assertTrue(varied > 100)
    }

    /**
     * Review 2026-11-12, finding 13 (round 4.3.5: glints and the lens streak instead of stars and a flare): with a cut-out, nothing of that light
     * may cover the person. A portrait whose head sits high: the person's pixels are the same with the glints and the streak at full and off.
     */
    @Test
    fun glintsAndStreak_neverCoverThePerson() {
        val photo = PosterSamples.syntheticPhoto()
        fun poster(light: Boolean): PosterState {
            val st = PosterSamples.state(3, photo = photo)
            st.photoZoom = 1.3f
            st.photoPanY = -288f
            st.fxSparkle = if (light) 1f else 0f
            st.fxStreak = if (light) 1f else 0f
            return st
        }
        val r = PosterRenderer(context, PosterAssets())
        val on = r.render(poster(true).frame(), POSTER_W, POSTER_H, 0).copy(Bitmap.Config.ARGB_8888, false)
        println("poster glints on the portrait: ${r.lastGlints.map { "(${it.x.toInt()}, ${it.y.toInt()}) ${"%.2f".format(it.strength)}" }}")
        val off = render(poster(false))
        save("glints-portrait", on)
        val grid = SubjectGrid.build(photo, 1.3f, 0f, -288f)!!
        assertTrue("no glints at all", r.lastGlints.isNotEmpty())
        for (g in r.lastGlints) {
            assertTrue("glint on the head at ${g.x},${g.y}", !grid.inHead(g.x, g.y))
            // a glint behind the person (a card's edge, the background) is hidden by the person; only a highlight of the person itself is on it
            assertTrue("glint on the person at ${g.x},${g.y}", g.front || grid.at(g.x, g.y) <= 32)
        }
        var n = 0
        var sum = 0L
        var worst = 0
        for (y in 0 until POSTER_H step 3) for (x in 0 until POSTER_W step 3) {
            val (px, py) = PosterPhotoGeometry.toPhoto(x.toFloat(), y.toFloat(), photo.w, photo.h, 1.3f, 0f, -288f)
            val ix = px.toInt()
            val iy = py.toInt()
            if (ix < 6 || iy < 6 || ix >= photo.w - 6 || iy >= photo.h - 6) continue
            var solid = true
            for (d in intArrayOf(-6, 6)) if ((photo.alpha[(iy + d) * photo.w + ix].toInt() and 255) < 250 || (photo.alpha[iy * photo.w + ix + d].toInt() and 255) < 250) solid = false
            if (!solid || (photo.alpha[iy * photo.w + ix].toInt() and 255) < 250) continue
            val a = on.getPixel(x, y)
            val b = off.getPixel(x, y)
            val d = maxOf(abs(((a shr 16) and 255) - ((b shr 16) and 255)), abs(((a shr 8) and 255) - ((b shr 8) and 255)), abs((a and 255) - (b and 255)))
            sum += d
            worst = maxOf(worst, d)
            n++
        }
        val mean = sum.toDouble() / n
        println("poster glints over the person: $n samples, mean difference $mean, worst $worst")
        assertTrue(n > 1000)
        assertTrue("mean difference on the person $mean", mean < 1.0)
        assertTrue("worst difference on the person $worst", worst < 24)
    }

    /** Lead review 2026-11-13: the finished no-photo poster's backdrop is not a muddy brown with five mixed covers, and neutral with grey ones. */
    @Test
    fun noPhotoBackdrop_isNeverAMuddyBrown() {
        fun backdropHsl(st: PosterState): Hsl {
            val b = render(st, 540, 960)
            // the top middle, between the cards of the five-card layout
            var r = 0
            var g = 0
            var bl = 0
            for (y in 90 until 110) for (x in 260 until 280) {
                val c = b.getPixel(x, y)
                r += (c shr 16) and 255
                g += (c shr 8) and 255
                bl += c and 255
            }
            return PosterFx.hsl(androidx.compose.ui.graphics.Color(r / 400f / 255f, g / 400f / 255f, bl / 400f / 255f))
        }
        for (p in PosterFxPreset.entries) {
            val st = PosterSamples.state(5)
            st.applyPreset(p)
            val k = backdropHsl(st)
            println("poster backdrop ${p.name}: hue ${k.h} sat ${k.s} light ${k.l}")
            assertTrue("${p.name}: a muddy brown (hue ${k.h}, sat ${k.s})", !(k.h in 20f..45f && k.s < 0.35f))
        }
        val grey = backdropHsl(PosterSamples.stateWithCovers("grey"))
        println("poster backdrop grey covers: hue ${grey.h} sat ${grey.s} light ${grey.l}")
        assertTrue("grey covers: hue ${grey.h}", abs(grey.h - PosterFx.NEUTRAL_HUE) < 20f)
        assertTrue("grey covers: sat ${grey.s}", grey.s < 0.25f)
    }

    // ---------------------------------------------------------------- "Kilau" (round 4.3.5 K: "kenapa efek kilauan nya jadi ilang")

    private fun pixelsOf(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** The light the glints add: the sum of the luminance gained over the same poster without them, and how many pixels got clearly brighter. */
    private fun added(on: Bitmap, off: Bitmap): Pair<Long, Int> {
        val a = pixelsOf(on)
        val b = pixelsOf(off)
        var sum = 0L
        var n = 0
        for (i in a.indices) {
            val d = luminance(a[i]) - luminance(b[i])
            if (d > 0) sum += d
            if (d > 24) n++
        }
        return sum to n
    }

    private fun describe(gs: List<Glint>) = gs.joinToString { "(${it.x.toInt()}, ${it.y.toInt()}) ${it.source.name.lowercase()}${if (it.hero) " hero" else ""} L${it.length.toInt()} i${"%.2f".format(it.intensity)}" }

    /** The default poster (BERSIH) sparkles, with a photo and without one, clearly; more and larger glints with more "Kilau". */
    @Test
    fun kilau_defaultPoster_sparkles_withAndWithoutAPhoto() {
        for ((name, photo) in listOf("nophoto" to null, "photo" to PosterSamples.syntheticPhoto())) {
            val st = PosterSamples.state(5, photo = photo)
            assertEquals(PosterFxPreset.BERSIH, st.preset)
            assertTrue(st.fxSparkle > 0.01f)
            val r = PosterRenderer(context, PosterAssets())
            val on = r.render(st.frame(), 540, 960, 0).copy(Bitmap.Config.ARGB_8888, false)
            val def = r.lastGlints
            st.fxSparkle = 0f
            val off = render(st, 540, 960)
            val (sum, n) = added(on, off)
            println("poster kilau $name default: ${def.size} glints, added light $sum, $n pixels brighter; ${describe(def)}")
            assertTrue("$name: ${def.size} glints", def.size >= 5)
            assertTrue("$name: every card catches the light: ${def.map { it.card }}", (0 until 5).all { c -> def.any { it.card == c } } || def.size >= 7)
            assertTrue("$name: only $n pixels brighter", n > 120)
            assertTrue("$name: added light $sum", sum > 15_000)
            assertTrue(def.any { it.hero })
            // more with more "Kilau"
            val counts = ArrayList<Int>()
            val energy = ArrayList<Long>()
            for (a in listOf(0.5f, 1f)) {
                st.fxSparkle = a
                val rr = PosterRenderer(context, PosterAssets())
                val b = rr.render(st.frame(), 540, 960, 0)
                counts.add(rr.lastGlints.size)
                energy.add(added(b, off).first)
            }
            println("poster kilau $name: 0.5 -> ${counts[0]} glints (light ${energy[0]}), 1.0 -> ${counts[1]} glints (light ${energy[1]})")
            assertTrue(counts[0] in 5..8)
            assertTrue(counts[1] > counts[0])
            assertTrue(energy[1] > energy[0] * 3 / 2)
        }
    }

    /** No glint light on the head or in the mark's band (the person's portrait of finding 13, "Kilau" at full), no spike over a card's text. */
    @Test
    fun kilau_neverOnTheHead_theMarkBand_orACardsText() {
        val photo = PosterSamples.syntheticPhoto()
        for (p in PosterFxPreset.entries) {
            fun poster(a: Float): PosterState {
                val st = PosterSamples.state(3, photo = photo)
                st.applyPreset(p)
                st.photoZoom = 1.3f
                st.photoPanY = -288f
                st.fxStreak = 0f
                st.fxSparkle = a
                return st
            }
            val r = PosterRenderer(context, PosterAssets())
            val on = pixelsOf(r.render(poster(1f).frame(), POSTER_W, POSTER_H, 0))
            val glints = r.lastGlints
            val off = pixelsOf(render(poster(0f)))
            val head = SubjectGrid.build(photo, 1.3f, 0f, -288f)!!.head!!
            fun worstIn(x0: Int, y0: Int, x1: Int, y1: Int): Int {
                var worst = 0
                for (y in max(0, y0)..min(POSTER_H - 1, y1)) for (x in max(0, x0)..min(POSTER_W - 1, x1)) worst = max(worst, abs(luminance(on[y * POSTER_W + x]) - luminance(off[y * POSTER_W + x])))
                return worst
            }
            val inHead = worstIn(head[0].toInt(), head[1].toInt(), head[2].toInt(), head[3].toInt())
            val inBand = worstIn(0, PosterGlints.MARK_BAND.toInt() + 1, POSTER_W - 1, POSTER_H - 1)
            println("poster kilau ${p.name} portrait: ${glints.size} glints, worst change in the head box $inHead, in the mark band $inBand; ${describe(glints)}")
            assertTrue(glints.size >= 5)
            assertTrue("${p.name}: head changed by $inHead", inHead <= 3)
            assertTrue("${p.name}: mark band changed by $inBand", inBand <= 3)
            val st = poster(1f)
            val cam = PosterGeometry.cameraFor(st.cardPerspective)
            val invs = st.poses.map { PosterGeometry.invert(PosterGeometry.cardMatrix(it, cam))!! }
            for (g in glints) assertTrue("glint over a card's text at ${g.x},${g.y}", !PosterGlints.crosses(g.x, g.y, g.length) { x, y -> invs.any { PosterGlints.onCardText(it, x, y) } })
        }
    }

    /** The photo's real point lights become glints; pale patches on the person's clothes (bright, but no light) never do. */
    @Test
    fun kilau_photoHighlights_onRealLights_notOnPaleCloth() {
        val photo = PosterSamples.nightPhoto()
        val st = PosterSamples.state(5, photo = photo)
        st.fxSparkle = 1f
        val r = PosterRenderer(context, PosterAssets())
        save("K-photo-lights", r.render(st.frame(), POSTER_W, POSTER_H, 0))
        val g = r.lastGlints
        println("poster kilau night photo: ${describe(g)}")
        fun near(px: Int, py: Int, d: Float): Boolean {
            val (x, y) = PosterPhotoGeometry.toPoster(px.toFloat(), py.toFloat(), photo.w, photo.h, 1f, 0f, 0f)
            return g.any { it.source == GlintSource.PHOTO && kotlin.math.hypot(it.x - x, it.y - y) < d }
        }
        val lit = PosterSamples.nightLights.count { (x, y) -> near(x, y, 8f) }
        assertTrue("only $lit of the point lights glint", lit >= 3)
        for ((x, y) in PosterSamples.palePatches) assertTrue("a pale patch at $x,$y glints", !near(x, y, 30f))
        assertTrue(g.none { it.front && it.hero })
    }

    /** The same poster gives the same glints and the same pixels, in a fresh renderer and after a card was moved away and back. */
    @Test
    fun kilau_isDeterministic() {
        val st = PosterSamples.state(4, photo = PosterSamples.syntheticPhoto())
        st.fxSparkle = 0.8f
        val r1 = PosterRenderer(context, PosterAssets())
        val a = pixelsOf(r1.render(st.frame(), 540, 960, 0))
        val ga = describe(r1.lastGlints)
        val home = st.poses[1]
        st.poses[1] = PosterEdit.moved(home, 60f, 30f)
        r1.render(st.frame(), 540, 960, 0)
        st.poses[1] = home
        val b = pixelsOf(r1.render(st.frame(), 540, 960, 0))
        val r2 = PosterRenderer(context, PosterAssets())
        val c = pixelsOf(r2.render(st.frame(), 540, 960, 0))
        assertEquals(ga, describe(r1.lastGlints))
        assertEquals(ga, describe(r2.lastGlints))
        assertTrue(a.contentEquals(b))
        assertTrue(a.contentEquals(c))
        assertEquals(1, r1.spotSearches)
    }

    /** The "Kilau" sticker reads on a bright ground (its soft dark under-light) and on a dark one (its light). */
    @Test
    fun kilauSticker_showsOnBrightAndDarkGrounds() {
        fun poster(bright: Boolean, sticker: Boolean): PosterState {
            val photo = if (bright) PosterPhoto(PosterImage(540, 960, IntArray(540 * 960) { (255 shl 24) or (238 shl 16) or (232 shl 8) or 222 }), ByteArray(540 * 960)) else null
            val st = PosterSamples.state(1, photo = photo)
            st.dim = 0f
            st.fxSparkle = 0f
            if (sticker) {
                st.addSticker(StickerKind.KILAU)
                st.stickers[0] = st.stickers[0].copy(x = 540f, y = 330f, scale = 1f)
            }
            return st
        }
        val a = Math.toRadians(PosterGlints.STAR_ANGLE.toDouble())
        val ux = kotlin.math.cos(a).toFloat()
        val uy = kotlin.math.sin(a).toFloat()
        for (bright in listOf(true, false)) {
            val b = render(poster(bright, true))
            val plain = render(poster(bright, false))
            save(if (bright) "K-sticker-bright" else "K-sticker-dark", b)
            // along the main spike (16..44 px from the centre, both ways) and 7 px beside it, with the sticker and on the same poster without it
            var spike = 0
            var side = 0
            var spike0 = 0
            var side0 = 0
            var n = 0
            for (t in 16..44 step 4) {
                for (sgn in intArrayOf(-1, 1)) {
                    val x = 540f + sgn * ux * t
                    val y = 330f + sgn * uy * t
                    fun lum(bmp: Bitmap, px: Float, py: Float) = luminance(bmp.getPixel(px.toInt(), py.toInt()))
                    spike += lum(b, x, y)
                    spike0 += lum(plain, x, y)
                    side += (lum(b, x - uy * 7f, y + ux * 7f) + lum(b, x + uy * 7f, y - ux * 7f)) / 2
                    side0 += (lum(plain, x - uy * 7f, y + ux * 7f) + lum(plain, x + uy * 7f, y - ux * 7f)) / 2
                    n++
                }
            }
            spike /= n
            side /= n
            spike0 /= n
            side0 /= n
            println("poster kilau sticker on a ${if (bright) "bright" else "dark"} ground: spike $spike (ground $spike0), beside it $side (ground $side0)")
            // the spike stands out from what is right beside it
            assertTrue("spike $spike vs beside $side", spike - side >= 30)
            if (bright) {
                // added light alone would vanish here: the soft under-light darkens the ground beside the spike
                assertTrue("no soft under-light: beside $side, ground $side0", side <= side0 - 8)
            } else {
                // on a dark ground the light shows by itself, no dark under-light
                assertTrue(spike - spike0 >= 60)
                assertTrue("a dark halo on a dark ground: beside $side, ground $side0", side >= side0)
            }
        }
    }

    /** PNGs for the review of "Kilau" (scratchpad K): the slider at 0 / 0.5 / 1, the presets with and without a photo, a large crop. */
    @Test
    fun kilau_reviewRenders() {
        val photo = PosterSamples.syntheticPhoto()
        for (a in listOf(0f, 0.5f, 1f)) {
            val st = PosterSamples.state(5, photo = photo)
            st.fxSparkle = a
            val r = PosterRenderer(context, PosterAssets())
            save("K-photo-kilau-${(a * 10).toInt()}", r.render(st.frame(), POSTER_W, POSTER_H, 0))
            println("poster kilau review photo $a: ${describe(r.lastGlints)}")
        }
        for (p in PosterFxPreset.entries) {
            val st = PosterSamples.state(5)
            st.applyPreset(p)
            val r = PosterRenderer(context, PosterAssets())
            save("K-nophoto-${p.name.lowercase()}", r.render(st.frame(), POSTER_W, POSTER_H, 0))
            println("poster kilau review nophoto ${p.name}: ${describe(r.lastGlints)}")
        }
        // the light preview level and a three-card poster
        val st = PosterSamples.state(3)
        val r = PosterRenderer(context, PosterAssets())
        save("K-nophoto3-level1", r.render(st.frame(), 540, 960, 1))
        println("poster kilau review nophoto3 level 1: ${describe(r.lastGlints)}")
    }

    /** The whole road on a real photo: only when build/poster-input/photo.jpg exists (a local, CC0 photo; never committed). */
    @Test
    fun realPhoto_cutOutAndPoster() {
        val input = File("build/poster-input/photo.jpg")
        val model = File("src/main/assets/poster/modnet_q.bin")
        if (!input.isFile || !model.isFile) {
            println("poster: no build/poster-input/photo.jpg, real-photo poster skipped")
            return
        }
        val raw = BitmapFactory.decodeFile(input.absolutePath, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
        val bmp = PosterPhotoLoad.upright(raw, android.media.ExifInterface.ORIENTATION_NORMAL, PosterPhotoLoad.MAX_SIDE)
        val img = PosterPhotoLoad.toImage(bmp)
        val t0 = System.nanoTime()
        val out = runBlocking { model.inputStream().use { PosterCutout.cut(it, img.argb, img.w, img.h, threads = 4, maxShortSide = PosterCutout.PHONE_SHORT_SIDE) } }
        println("poster real photo ${img.w}x${img.h}: cut-out ${(System.nanoTime() - t0) / 1_000_000} ms, outcome $out")
        assertTrue(out is CutoutOutcome.Done)
        val photo = PosterPhoto(img, ByteArray(img.w * img.h))
        photo.setMask((out as CutoutOutcome.Done).alpha)
        println("poster real photo: network ${out.inputW}x${out.inputH}, coverage ${(out.coverage * 100).toInt()} percent")
        val mask = Bitmap.createBitmap(img.w, img.h, Bitmap.Config.ARGB_8888)
        mask.setPixels(IntArray(img.w * img.h) { val a = photo.alpha[it].toInt() and 255; (255 shl 24) or (a shl 16) or (a shl 8) or a }, 0, img.w, 0, 0, img.w, img.h)
        save("real-mask", mask)
        val grid = SubjectGrid.build(photo, 1f, 0f, 0f)
        println("poster real photo: head box ${grid?.head?.toList()}")
        // the default look is the clean editorial one
        for (n in listOf(3, 5)) {
            val st = PosterSamples.state(n, photo = photo)
            assertEquals(PosterFxPreset.BERSIH, st.preset)
            save("real-orbit$n", render(st))
        }
        for (p in PosterFxPreset.entries) {
            val st = PosterSamples.state(5, photo = photo)
            st.applyPreset(p)
            val r = PosterRenderer(context, PosterAssets())
            val on = r.render(st.frame(), POSTER_W, POSTER_H, 0).copy(Bitmap.Config.ARGB_8888, false)
            save("real-${p.name.lowercase()}", on)
            println("poster real ${p.name}: glints ${describe(r.lastGlints)}")
            for (g in r.lastGlints) assertTrue("glint on the face at ${g.x},${g.y}", grid?.inHead(g.x, g.y) != true)
            // round K: the default looks sparkle on the real photo too, clearly
            st.fxSparkle = 0f
            val (sum, n) = added(on, render(st))
            println("poster real ${p.name}: ${r.lastGlints.size} glints, added light $sum, $n pixels brighter")
            assertTrue(r.lastGlints.size >= 4)
            assertTrue("${p.name}: only $n pixels brighter", n > 300)
        }
        // "Kilau" at 0 / 0.5 / 1 on the real photo (review sheet C)
        for (a in listOf(0f, 0.5f, 1f)) {
            val st = PosterSamples.state(5, photo = photo)
            st.fxSparkle = a
            val r = PosterRenderer(context, PosterAssets())
            save("K-real-kilau-${(a * 10).toInt()}", r.render(st.frame(), POSTER_W, POSTER_H, 0))
            println("poster real kilau $a: ${r.lastGlints.size} glints: ${describe(r.lastGlints)}")
        }
        // the glint sticker over the light part of the photo and over a dark part
        val ks = PosterSamples.state(3, photo = photo)
        ks.addSticker(StickerKind.KILAU)
        ks.stickers[0] = ks.stickers[0].copy(x = 640f, y = 150f, scale = 1f)
        ks.addSticker(StickerKind.KILAU)
        ks.stickers[1] = ks.stickers[1].copy(x = 120f, y = 1180f, scale = 0.8f)
        save("K-real-stickers", render(ks))
        for (g in PosterGrade.entries) {
            val st = PosterSamples.state(3, photo = photo)
            st.grade = g
            save("real-grade-${g.name.lowercase()}", render(st, 540, 960))
        }
        // every light at full: the most the sliders can do on this photo
        val full = PosterSamples.state(4, PosterLayout.TUMPUK, photo)
        full.applyPreset(PosterFxPreset.SINEMA)
        full.fxSparkle = 1f
        full.fxStreak = 1f
        full.fxBloom = 1f
        full.fxLeak = 1f
        full.fxGrain = 0.6f
        save("real-tumpuk-full", render(full))
        // the built-in stickers on the photo
        val stk = PosterSamples.state(3, photo = photo)
        stk.addSticker(StickerKind.BURUNG)
        stk.stickers[0] = stk.stickers[0].copy(x = 820f, y = 260f, scale = 1.1f, rotation = -8f)
        stk.addSticker(StickerKind.BURUNG)
        stk.stickers[1] = stk.stickers[1].copy(x = 930f, y = 360f, scale = 0.55f, rotation = 6f)
        stk.addSticker(StickerKind.KILAU)
        stk.stickers[2] = stk.stickers[2].copy(x = 150f, y = 1240f, scale = 0.6f)
        save("real-stickers", render(stk))
    }
}
