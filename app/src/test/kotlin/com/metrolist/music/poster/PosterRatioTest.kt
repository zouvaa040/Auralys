package com.metrolist.music.poster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Round 4.3.6: the shape of the poster (ratios), the arrangements per shape, the texts, the backgrounds, the export options. Logic tests, and the
 * sample posters of EVERY shape written to build/poster-gallery for looking at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PosterRatioTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dir = File("build/poster-gallery").apply { mkdirs() }

    private fun save(name: String, b: Bitmap) {
        File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A person in front of a wall for a photo of [w] x [h]: the same figure, sized by the photo's height, whatever the photo's shape. */
    private fun personPhoto(w: Int, h: Int): PosterPhoto {
        val px = IntArray(w * h)
        val a = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val t = y / h.toFloat()
                val r = (226 - 86 * t).toInt()
                val g = (196 - 104 * t).toInt()
                val b = (158 - 116 * t).toInt()
                val dx = (x - w * 0.5f) / (h * 0.146f)
                val dy = (y - h * 0.80f) / (h * 0.30f)
                val body = 1f - sqrt(dx * dx + dy * dy)
                val hx = (x - w * 0.5f) / (h * 0.062f)
                val hy = (y - h * 0.44f) / (h * 0.075f)
                val head = 1f - sqrt(hx * hx + hy * hy)
                val alpha = (max(body, head) * 12f).coerceIn(0f, 1f)
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

    private fun photoFor(r: PosterRatio): PosterPhoto = if (r.landscape) personPhoto(960, 540) else personPhoto(540, 960)

    private fun stateFor(r: PosterRatio, count: Int, layout: PosterLayout = PosterLayout.ORBIT, photo: Boolean = false): PosterState {
        val st = PosterState()
        st.ratio = r
        st.layout = layout
        st.setSongs(List(count) { PosterSamples.song(it) })
        for (k in 0 until count) {
            st.covers["sample-$k"] = PosterSamples.cover(k)
            st.tints["sample-$k"] = listOf(Color0, Color1, Color2, Color3, Color4)[k % 5]
        }
        if (photo) {
            st.photo = photoFor(r)
            st.cutout = CutoutPhase.DONE
        }
        return st
    }

    private fun render(st: PosterState, scale: Float = 1f, level: Int = 0): Bitmap {
        val w = (st.ratio.w * scale).toInt()
        val h = (st.ratio.h * scale).toInt()
        val rr = PosterRenderer(context, PosterAssets())
        return rr.render(st.frame(), w, h, level)
    }

    /** Pictures side by side on a dark sheet, each [cell] px wide (its own shape kept), [cols] to a row. */
    private fun sheet(images: List<Bitmap>, cols: Int, cell: Int): Bitmap {
        val gap = 12
        val heights = images.map { (cell * it.height / it.width.toFloat()).toInt() }
        val rows = (images.size + cols - 1) / cols
        val rowH = (0 until rows).map { r -> (0 until cols).mapNotNull { c -> heights.getOrNull(r * cols + c) }.maxOrNull() ?: 0 }
        val out = Bitmap.createBitmap(cols * cell + (cols + 1) * gap, rowH.sum() + (rows + 1) * gap, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFF1A1C20.toInt())
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        var y = gap
        for (r in 0 until rows) {
            for (col in 0 until cols) {
                val i = r * cols + col
                val img = images.getOrNull(i) ?: continue
                c.drawBitmap(img, null, android.graphics.Rect(gap + col * (cell + gap), y, gap + col * (cell + gap) + cell, y + heights[i]), p)
            }
            y += rowH[r] + gap
        }
        return out
    }

    // ------------------------------------------------------------------------------------------------ the shapes

    @Test
    fun ratios_shortSideIs1080_andNineSixteenIsTheDefault() {
        for (r in PosterRatio.entries) {
            assertEquals("${r.name} short side", 1080, min(r.w, r.h))
            assertTrue(r.w <= 1920 && r.h <= 1920)
            assertEquals(r, PosterRatio.parse(r.name))
        }
        assertEquals(PosterRatio.R9_16, PosterRatio.DEFAULT)
        assertEquals(PosterRatio.R9_16, PosterRatio.parse(null))
        assertEquals(PosterRatio.R9_16, PosterRatio.parse("nonsense"))
        assertEquals(1080 to 1920, PosterExportSize.of(PosterRatio.R9_16))
        assertEquals(1920 to 1080, PosterExportSize.of(PosterRatio.R16_9))
        assertEquals(1440 to 1080, PosterExportSize.of(PosterRatio.R4_3))
        // the 9:16 numbers of the mark and its band are the old ones
        assertEquals(1660f, PosterRatio.R9_16.markBand, 0f)
        assertEquals(POSTER_H - 92f, PosterRatio.R9_16.markCy, 0f)
        assertEquals(PosterFx.MARK_CY, PosterRatio.R9_16.markCy, 0f)
        assertEquals(PosterGlints.MARK_BAND, PosterRatio.R9_16.markBand, 0f)
    }

    @Test
    fun posterBox_followsTheShape() {
        for (r in PosterRatio.entries) {
            val b = posterBox(1000, 700, r)
            assertTrue("${r.name} fits", b[2] <= 1000 && b[3] <= 700)
            assertEquals("${r.name} aspect", r.aspect, b[2] / b[3].toFloat(), 0.01f)
        }
        // the old call is the 9:16 box
        assertEquals(posterBox(900, 1200).toList(), posterBox(900, 1200, PosterRatio.R9_16).toList())
    }

    @Test
    fun layouts_everyShape_everyArrangement_staysInTheRoom() {
        val cam = PosterGeometry.cameraFor(0.5f)
        for (r in PosterRatio.entries) {
            for (l in PosterLayout.entries) {
                for (n in 1..POSTER_MAX_SONGS) {
                    val poses = PosterLayouts.poses(l, n, r)
                    assertEquals("${r.name} ${l.name} $n", n, poses.size)
                    for ((i, p) in poses.withIndex()) {
                        val tag = "${r.name} ${l.name} n=$n card $i"
                        assertTrue("$tag scale ${p.scale}", p.scale in 0.3f..1.2f)
                        assertFalse("$tag NaN", p.x.isNaN() || p.y.isNaN())
                        val b = PosterGeometry.bounds(PosterGeometry.corners(p, cam))
                        if (r != PosterRatio.R9_16 || l == PosterLayout.KOLASE || l == PosterLayout.BESAR) {
                            assertTrue("$tag left ${b[0]}", b[0] >= -30f)
                            assertTrue("$tag right ${b[2]} of ${r.w}", b[2] <= r.w + 30f)
                            assertTrue("$tag top ${b[1]}", b[1] >= -30f)
                            // the mark's band stays free of the cards (a small tolerance for the perspective at the closer camera)
                            assertTrue("$tag bottom ${b[3]} band ${r.markBand}", b[3] <= r.markBand + 30f)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun layouts_nineSixteen_keepsItsFourOldArrangements_bitForBit() {
        // the story shape must give exactly the old poses (the old tests pin their values; here the generator is checked against the default path)
        for (l in listOf(PosterLayout.ORBIT, PosterLayout.TUMPUK, PosterLayout.DERET, PosterLayout.KOLOM)) {
            for (n in 1..5) assertEquals(PosterLayouts.poses(l, n), PosterLayouts.poses(l, n, PosterRatio.R9_16))
        }
        val orbit3 = PosterLayouts.poses(PosterLayout.ORBIT, 3)
        assertEquals(CardPose(275f, 540f, 0.86f, -5f, 0f, 26f), orbit3[0])
    }

    @Test
    fun changeRatio_untouchedCardsGetTheArrangementOfTheNewShape() {
        val st = stateFor(PosterRatio.R9_16, 4)
        assertTrue(st.changeRatio(PosterRatio.R16_9))
        assertEquals(PosterRatio.R16_9, st.ratio)
        assertEquals(PosterLayouts.poses(st.layout, 4, PosterRatio.R16_9), st.poses.toList())
        assertFalse("the same shape again does nothing", st.changeRatio(PosterRatio.R16_9))
    }

    @Test
    fun changeRatio_keepsWhatTheUserPlaced() {
        val st = stateFor(PosterRatio.R9_16, 3)
        st.poses[0] = PosterEdit.moved(st.poses[0], 60f, 90f)
        st.addSticker(StickerKind.KILAU)
        st.addText("Judulku")
        val sticker = st.stickers[0]
        val text = st.texts[0]
        val moved = st.poses[0]
        st.changeRatio(PosterRatio.R1_1)
        // a hand-placed card keeps its share of the picture and stays in it
        val p = st.poses[0]
        assertEquals(moved.x * 1080f / 1080f, p.x, 80f)
        assertEquals(moved.y * 1080f / 1920f, p.y, 80f)
        assertTrue(p.y <= PosterRatio.R1_1.markBand)
        // the sticker and the text follow their share of the picture
        assertEquals(sticker.x * 1f, st.stickers[0].x, 0.01f)
        assertEquals(sticker.y * 1080f / 1920f, st.stickers[0].y, 0.01f)
        assertEquals(text.y * 1080f / 1920f, st.texts[0].y, 0.01f)
        assertEquals(sticker.scale, st.stickers[0].scale, 0f)
        // "Susun ulang" puts every card in its ready-made place on this shape
        st.changeRatio(PosterRatio.R1_1, rearrange = true)
        assertEquals(PosterLayouts.poses(st.layout, 3, PosterRatio.R1_1), st.poses.toList())
    }

    @Test
    fun changeRatio_photoPanStaysInsideTheNewShape() {
        val st = stateFor(PosterRatio.R9_16, 2, photo = true)
        st.photoZoom = 1.6f
        st.photoPanX = 120f
        st.photoPanY = -300f
        st.changeRatio(PosterRatio.R16_9)
        val photo = st.photo!!
        val (mx, my) = PosterEdit.panLimit(photo.w, photo.h, st.photoZoom, PosterRatio.R16_9)
        assertTrue(abs(st.photoPanX) <= mx + 0.01f)
        assertTrue(abs(st.photoPanY) <= my + 0.01f)
    }

    @Test
    fun undo_bringsBackTheOldShapeAndEverythingOnIt() {
        val st = stateFor(PosterRatio.R9_16, 3)
        st.addText("Halo")
        val undo = PosterUndo()
        val before = st.doc()
        undo.mark(st)
        st.changeRatio(PosterRatio.R4_5)
        assertNotEquals(before, st.doc())
        assertTrue(undo.undo(st))
        assertEquals(before, st.doc())
        assertEquals(PosterRatio.R9_16, st.ratio)
        assertEquals(before.poses, st.poses.toList())
    }

    @Test
    fun photoGeometry_followsTheShape() {
        for (r in PosterRatio.entries) {
            val f = PosterPhotoGeometry.scale(540, 960, 1f, r)
            assertTrue("${r.name} covers", 540 * f >= r.w - 0.01f && 960 * f >= r.h - 0.01f)
            val (x, y) = PosterPhotoGeometry.toPoster(100f, 200f, 540, 960, 1.3f, 12f, -9f, r)
            val (px, py) = PosterPhotoGeometry.toPhoto(x, y, 540, 960, 1.3f, 12f, -9f, r)
            assertEquals(100f, px, 0.01f)
            assertEquals(200f, py, 0.01f)
        }
        assertEquals(PosterPhotoGeometry.scale(540, 960, 1f), PosterPhotoGeometry.scale(540, 960, 1f, PosterRatio.R9_16), 0f)
    }

    // ------------------------------------------------------------------------------------------------ the texts

    @Test
    fun texts_geometryAndTheHandle() {
        val t = PosterText(1, "Judul lagu", PosterTextStyle.TEBAL, 540f, 600f, 1f, 0f, PosterTexts.WHITE)
        val (hw, hh) = PosterTexts.half(t)
        assertTrue("text has a width $hw x $hh", hw > 60f && hh > 20f)
        assertTrue(PosterTexts.hit(t, 540f, 600f))
        assertFalse(PosterTexts.hit(t, 540f + hw + 80f, 600f))
        // bigger text = bigger box
        assertEquals(hw * 2f, PosterTexts.half(t.copy(scale = 2f)).first, 0.5f)
        // the handle sits at the bottom right corner and dragging it further out makes the text bigger
        val (hx, hy) = PosterTexts.handle(t)
        assertEquals(540f + hw, hx, 0.01f)
        assertEquals(600f + hh, hy, 0.01f)
        assertTrue(PosterTexts.onHandle(t, hx + 10f, hy - 10f))
        val big = PosterTexts.draggedHandle(t, 540f + (hx - 540f) * 1.6f, 600f + (hy - 600f) * 1.6f)
        assertEquals(1.6f, big.scale, 0.02f)
        assertEquals(0f, big.rotation, 0.5f)
        // a turned text is hit where it is turned to
        val turned = t.copy(rotation = 90f)
        assertTrue(PosterTexts.hit(turned, 540f, 600f + hw - 4f))
        assertFalse(PosterTexts.hit(turned, 540f + hw - 4f, 600f + 4f + hh))
        // the front-most text wins
        val list = listOf(t, t.copy(id = 2))
        assertEquals(1, PosterTexts.hit(list, 540f, 600f))
    }

    @Test
    fun texts_limitsAndCleaning() {
        assertNull(PosterTexts.clean("   \n  "))
        assertEquals("a\nb\nc", PosterTexts.clean("a\n\nb\n c \nd\ne"))
        assertEquals(PosterTexts.MAX_CHARS, PosterTexts.clean("x".repeat(500))!!.length)
        val st = stateFor(PosterRatio.R9_16, 1)
        // a long text starts smaller, so it fits the width of the picture
        st.addText("LAGU YANG SANGAT PANJANG SEKALI JUDULNYA", PosterTextStyle.TEBAL)
        assertTrue("fits ${st.texts[0].scale}", st.texts[0].scale < 1f && PosterTexts.half(st.texts[0]).first * 2f <= 1080f * 0.86f)
        st.removeText(0)
        assertEquals(-1, st.addText("   "))
        repeat(PosterTexts.MAX_COUNT) { assertTrue(st.addText("Teks $it") >= 0) }
        assertEquals(-1, st.addText("satu lagi"))
        assertEquals(PosterTexts.MAX_COUNT, st.texts.size)
        assertEquals(PosterTexts.MAX_COUNT, st.texts.map { it.id }.toSet().size)
        st.removeText(0)
        assertEquals(PosterTexts.MAX_COUNT - 1, st.texts.size)
        val keep = st.texts.map { it.id }.toSet()
        assertTrue("a new text never reuses an id", st.addText("baru") >= 0 && st.texts.last().id !in keep)
    }

    @Test
    fun texts_arePaintedOnThePoster() {
        val st = stateFor(PosterRatio.R9_16, 1)
        val before = render(st, 0.5f)
        st.addText("TERDENGAR", PosterTextStyle.TEBAL)
        st.texts[0] = st.texts[0].copy(y = 1500f)
        val after = render(st, 0.5f)
        var changed = 0
        for (y in 700 until 800) for (x in 100 until 440) if (before.getPixel(x, y) != after.getPixel(x, y)) changed++
        assertTrue("the text changes the picture around it ($changed px)", changed > 800)
    }

    // ------------------------------------------------------------------------------------------------ the saved state

    @Test
    fun savedState_carriesTheShapeTheTextsAndTheBackground() {
        val st = stateFor(PosterRatio.R16_9, 3)
        st.addText("Satu\nDua", PosterTextStyle.SOROT, 0xFFFFC857.toInt())
        st.background = PosterBackground.GRADIEN
        st.fxVignette = 0.8f
        val snap =
            PosterSnapshot(
                session = "s", stage = "EDIT", title = "t", sourceIds = emptyList(), picked = emptyList(), pickedSongs = emptyList(),
                songs = st.songs.toList(), startSongs = st.songs.toList(), doc = st.doc(), cutout = CutoutPhase.NONE, changedSinceSave = true,
                exportFormat = PosterFormat.JPEG, exportQuality = 0.8f,
            )
        val back = PosterSnapshot.from(Bundle(snap.toBundle()))!!
        val d = back.doc!!
        assertEquals(PosterRatio.R16_9, d.ratio)
        assertEquals(PosterBackground.GRADIEN, d.background)
        assertEquals(0.8f, d.vignette, 0f)
        assertEquals(1, d.texts.size)
        assertEquals("Satu\nDua", d.texts[0].text)
        assertEquals(PosterTextStyle.SOROT, d.texts[0].style)
        assertEquals(0xFFFFC857.toInt(), d.texts[0].color)
        assertEquals(st.texts[0], d.texts[0])
        assertEquals(PosterFormat.JPEG, back.exportFormat)
        assertEquals(0.8f, back.exportQuality, 0f)
        // the poses came back as they were
        assertEquals(st.poses.toList(), d.poses)
    }

    @Test
    fun savedState_anOldOneIsANineSixteenPosterWithoutTexts() {
        val st = stateFor(PosterRatio.R9_16, 2)
        val snap =
            PosterSnapshot(
                session = "s", stage = "EDIT", title = "t", sourceIds = emptyList(), picked = emptyList(), pickedSongs = emptyList(),
                songs = st.songs.toList(), startSongs = st.songs.toList(), doc = st.doc(), cutout = CutoutPhase.NONE, changedSinceSave = false,
            )
        val b = snap.toBundle()
        // what an older version wrote: none of the new keys
        val doc = b.getBundle("doc")!!
        for (k in listOf("ratio", "background", "vignette", "textIds", "textWords", "textStyles", "textColors", "textValues")) doc.remove(k)
        b.remove("exportFormat")
        b.remove("exportQuality")
        val back = PosterSnapshot.from(b)!!
        assertEquals(PosterRatio.R9_16, back.doc!!.ratio)
        assertTrue(back.doc!!.texts.isEmpty())
        assertEquals(PosterBackground.ASLI, back.doc!!.background)
        assertEquals(PosterFxDefaults.VIGNETTE, back.doc!!.vignette, 0f)
        assertEquals(PosterFormat.PNG, back.exportFormat)
    }

    // ------------------------------------------------------------------------------------------------ shuffle

    @Test
    fun shuffle_changesTheLookButKeepsTheContent() {
        val st = stateFor(PosterRatio.R4_5, 4, photo = true)
        st.addText("Tetap")
        st.addSticker(StickerKind.BURUNG)
        val songs = st.songs.toList()
        val texts = st.texts.toList()
        val stickers = st.stickers.toList()
        val photo = st.photo
        val undo = PosterUndo()
        val before = st.doc()
        undo.mark(st)
        val looks = HashSet<String>()
        for (seed in 0 until 12) {
            PosterShuffle.apply(st, kotlin.random.Random(seed))
            looks.add("${st.layout} ${st.material} ${st.preset} ${st.background}")
            assertEquals(PosterRatio.R4_5, st.ratio)
            assertEquals(songs, st.songs.toList())
            assertEquals(texts, st.texts.toList())
            assertEquals(stickers, st.stickers.toList())
            assertTrue(photo === st.photo)
            assertEquals(4, st.poses.size)
        }
        assertTrue("twelve shuffles give several looks (${looks.size})", looks.size >= 5)
        assertTrue(undo.undo(st))
        assertEquals(before, st.doc())
    }

    @Test
    fun shuffle_keepsAPhotoWithoutACutoutVisible() {
        val st = stateFor(PosterRatio.R9_16, 3)
        st.photo = photoFor(PosterRatio.R9_16)
        st.cutout = CutoutPhase.FAILED
        for (seed in 0 until 20) {
            PosterShuffle.apply(st, kotlin.random.Random(seed))
            assertEquals(PosterBackground.ASLI, st.background)
        }
    }

    // ------------------------------------------------------------------------------------------------ through the real view model

    private fun vm(songs: Int = 3): PosterViewModel {
        val vm = PosterViewModel(ApplicationProvider.getApplicationContext<android.app.Application>())
        vm.start(PosterRequest("Tes", List(8) { PosterSong("s$it", "Lagu $it", "Artis", null, 200) }))
        repeat(songs) { vm.togglePick("s$it") }
        vm.confirmPick()
        return vm
    }

    @Test
    fun viewModel_changingTheShape_movesTheTouchesWithIt_andUndoBringsTheOldShapeBack() {
        val vm = vm()
        val before = vm.state.doc()
        vm.setRatio(PosterRatio.R16_9)
        assertEquals(PosterRatio.R16_9, vm.state.ratio)
        assertTrue(vm.canUndo)
        // a card is hit where it stands on the new shape, and a drag may not carry it far out of the (wider) picture
        val p = vm.state.poses[0]
        val t = vm.hit(p.x, p.y)
        assertTrue("hit $t", t is PosterTouchTarget.Card)
        vm.begin(t)
        vm.move(t, p.x, p.y, p.x, p.y, 9000f, 9000f, 1f, 0f)
        vm.end(t)
        val q = vm.state.poses[(t as PosterTouchTarget.Card).index]
        assertTrue("x ${q.x}", q.x <= PosterRatio.R16_9.w + 120f && q.x > 1920f)
        assertTrue("y ${q.y}", q.y <= PosterRatio.R16_9.h + 120f)
        vm.undo()
        vm.undo()
        assertEquals(PosterRatio.R9_16, vm.state.ratio)
        assertEquals(before, vm.state.doc())
        // the same shape again is not an edit
        val n = vm.canUndo
        vm.setRatio(PosterRatio.R9_16)
        assertEquals(n, vm.canUndo)
    }

    @Test
    fun viewModel_textsTakeTouches_dragMoves_theHandleSizes_undoRemoves() {
        val vm = vm()
        vm.setRatio(PosterRatio.R1_1)
        assertTrue(vm.addText("Halo dunia", PosterTextStyle.TEBAL))
        val i = vm.state.selectedText
        assertEquals(0, i)
        val x0 = vm.state.texts[0]
        assertEquals(PosterTouchTarget.Text(0), vm.hit(x0.x, x0.y))
        vm.begin(PosterTouchTarget.Text(0))
        vm.move(PosterTouchTarget.Text(0), x0.x, x0.y, x0.x, x0.y, 40f, -25f, 1f, 0f)
        assertEquals(x0.x + 40f, vm.state.texts[0].x, 0.01f)
        assertEquals(x0.y - 25f, vm.state.texts[0].y, 0.01f)
        // the handle (bottom right corner) of the selected text
        val moved = vm.state.texts[0]
        val (hx, hy) = PosterTexts.handle(moved)
        val h = vm.hit(hx, hy)
        assertEquals(PosterTouchTarget.TextHandle(0), h)
        vm.begin(h)
        vm.move(h, moved.x + (hx - moved.x) * 1.5f, moved.y + (hy - moved.y) * 1.5f, 0f, 0f, 0f, 0f, 1f, 0f)
        assertEquals(moved.scale * 1.5f, vm.state.texts[0].scale, 0.03f)
        // a text may not leave the square picture
        vm.begin(PosterTouchTarget.Text(0))
        vm.move(PosterTouchTarget.Text(0), 0f, 0f, 0f, 0f, 99999f, 99999f, 1f, 0f)
        assertEquals(1080f, vm.state.texts[0].x, 0.01f)
        assertEquals(1080f, vm.state.texts[0].y, 0.01f)
        // a tap on empty picture clears the selection, a tap on the text selects it again
        vm.tap(PosterTouchTarget.None)
        assertEquals(-1, vm.state.selectedText)
        vm.tap(PosterTouchTarget.Text(0))
        assertEquals(0, vm.state.selectedText)
        // editing the words, copying, deleting
        vm.editSelectedText("Halo lagi")
        assertEquals("Halo lagi", vm.state.texts[0].text)
        vm.editSelectedText("   ")
        assertEquals("Halo lagi", vm.state.texts[0].text)
        vm.duplicateText()
        assertEquals(2, vm.state.texts.size)
        vm.deleteText()
        assertEquals(1, vm.state.texts.size)
    }

    @Test
    fun viewModel_rearrangeAndShuffleAreEditsUndoCanTake() {
        val vm = vm(4)
        vm.setRatio(PosterRatio.R4_3)
        val arranged = vm.state.poses.toList()
        vm.state.poses[0] = PosterEdit.moved(vm.state.poses[0], 200f, 120f, PosterRatio.R4_3)
        val dragged = vm.state.poses.toList()
        vm.rearrange()
        assertEquals(arranged, vm.state.poses.toList())
        vm.undo()
        assertEquals(dragged, vm.state.poses.toList())
        vm.shuffleDesign()
        assertTrue(vm.canUndo)
        assertEquals(PosterRatio.R4_3, vm.state.ratio)
        assertEquals(4, vm.state.poses.size)
    }

    @Test
    fun viewModel_exportOptionsAreKeptInTheSavedState() {
        val vm = vm()
        vm.setExportFormat(PosterFormat.JPEG)
        vm.setExportQuality(0.7f)
        vm.setRatio(PosterRatio.R3_2)
        vm.addText("Simpan")
        val snap = vm.snapshot()
        assertEquals(PosterFormat.JPEG, snap.exportFormat)
        assertEquals(0.7f, snap.exportQuality, 0f)
        assertEquals(PosterRatio.R3_2, snap.doc!!.ratio)
        // the system keeps it through a Parcel and a new process builds a new view model from it
        val parcel = android.os.Parcel.obtain()
        val back =
            try {
                parcel.writeBundle(snap.toBundle())
                parcel.setDataPosition(0)
                parcel.readBundle(javaClass.classLoader)!!
            } finally {
                parcel.recycle()
            }
        val again =
            PosterViewModel(
                ApplicationProvider.getApplicationContext<android.app.Application>(),
                androidx.lifecycle.SavedStateHandle(mapOf(PosterViewModel.SAVED_KEY to back)),
                PosterSongLookup { _, _ -> emptyList() },
            ).also { it.start(null) }
        assertEquals(PosterViewModel.Stage.EDIT, again.stage)
        assertEquals(PosterRatio.R3_2, again.state.ratio)
        assertEquals(listOf("Simpan"), again.state.texts.map { it.text })
        assertEquals(PosterFormat.JPEG, again.state.exportFormat)
        assertEquals(0.7f, again.state.exportQuality, 0f)
        // quality is kept within the range the sliders offer
        vm.setExportQuality(0.1f)
        assertEquals(0.5f, vm.state.exportQuality, 0f)
    }

    @Test
    fun save_encodesPngAndJpeg_atTheChosenQuality() {
        val st = stateFor(PosterRatio.R4_5, 2)
        val bmp = render(st, 0.3f)
        val png = java.io.ByteArrayOutputStream().also { PosterSave.encode(bmp, PosterFormat.PNG, 92, it) }.toByteArray()
        val high = java.io.ByteArrayOutputStream().also { PosterSave.encode(bmp, PosterFormat.JPEG, 98, it) }.toByteArray()
        val low = java.io.ByteArrayOutputStream().also { PosterSave.encode(bmp, PosterFormat.JPEG, 50, it) }.toByteArray()
        assertEquals(0x89, png[0].toInt() and 255)
        assertEquals(0xFF, high[0].toInt() and 255)
        assertEquals(0xD8, high[1].toInt() and 255)
        assertTrue("lower quality is smaller (${low.size} < ${high.size})", low.size < high.size)
        assertEquals("png", PosterSave.extension(PosterFormat.PNG))
        assertEquals("jpg", PosterSave.extension(PosterFormat.JPEG))
        assertEquals("image/jpeg", PosterSave.mime(PosterFormat.JPEG))
        assertTrue(PosterSave.fileName(format = PosterFormat.JPEG).endsWith(".jpg"))
        assertTrue(PosterSave.fileName().endsWith(".png"))
    }

    // ------------------------------------------------------------------------------------------------ the pictures

    @Test
    fun export_everyShape_hasItsSizeAndTheMark() {
        for (r in PosterRatio.entries) {
            val st = stateFor(r, 3, photo = true)
            val b = renderPosterBitmap(context, st.frame(), PosterAssets())
            assertEquals("${r.name} width", r.w, b.width)
            assertEquals("${r.name} height", r.h, b.height)
            // the Auralys word: bright pixels at the mark's row, centred
            var bright = 0
            val cy = r.markCy.toInt()
            for (y in cy - 22..cy + 22) for (x in r.w / 2 - 160 until r.w / 2 + 160) {
                val c = b.getPixel(x, y)
                if ((((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000 > 215) bright++
            }
            assertTrue("${r.name} mark pixels $bright", bright > 250)
            b.recycle()
        }
    }

    @Test
    fun gallery_everyShape_withAndWithoutAPhoto() {
        for (r in PosterRatio.entries) {
            val half = if (r.landscape) 0.5f else 0.55f
            val withPhoto = stateFor(r, 4, photo = true)
            save("ratio-${r.name.lowercase()}-photo4", render(withPhoto, half))
            val plain = stateFor(r, 5)
            save("ratio-${r.name.lowercase()}-plain5", render(plain, half))
        }
    }

    @Test
    fun gallery_arrangementsOnEveryShape() {
        for (r in PosterRatio.entries) {
            val cell = if (r.landscape) 520 else 330
            val pics = ArrayList<Bitmap>()
            for (l in PosterLayout.entries) {
                val st = stateFor(r, 5, l, photo = true)
                pics.add(render(st, cell / r.w.toFloat(), level = 1))
            }
            save("layouts-${r.name.lowercase()}-n5", sheet(pics, if (r.landscape) 3 else 6, cell))
            val pics3 = ArrayList<Bitmap>()
            for (l in PosterLayout.entries) {
                val st = stateFor(r, 3, l)
                pics3.add(render(st, cell / r.w.toFloat(), level = 1))
            }
            save("layouts-${r.name.lowercase()}-n3", sheet(pics3, if (r.landscape) 3 else 6, cell))
        }
    }

    @Test
    fun gallery_backgroundsAndTexts() {
        for (r in listOf(PosterRatio.R9_16, PosterRatio.R16_9)) {
            val cell = if (r.landscape) 600 else 330
            val pics = ArrayList<Bitmap>()
            for (b in PosterBackground.entries) {
                val st = stateFor(r, 3, PosterLayout.ORBIT, photo = true)
                st.background = b
                pics.add(render(st, cell / r.w.toFloat()))
            }
            for (b in listOf(PosterBackground.KABUR, PosterBackground.GRADIEN, PosterBackground.POLOS)) {
                val st = stateFor(r, 3)
                st.background = b
                pics.add(render(st, cell / r.w.toFloat()))
            }
            save("backgrounds-${r.name.lowercase()}", sheet(pics, if (r.landscape) 2 else 7, cell))
        }
        val styles = ArrayList<Bitmap>()
        for ((k, s) in PosterTextStyle.entries.withIndex()) {
            val st = stateFor(PosterRatio.R9_16, 2, photo = true)
            st.addText(if (k % 2 == 0) "Lagu Favorit\nBulan Ini" else "Hujan Sore Hari", s, if (s == PosterTextStyle.SOROT) 0xFFFFC857.toInt() else PosterTexts.WHITE)
            st.texts[0] = st.texts[0].copy(y = 1500f, rotation = if (k == 2) -6f else 0f)
            st.selectedText = -1
            styles.add(render(st, 0.4f))
        }
        save("texts-styles", sheet(styles, 5, 430))
    }

    @Test
    fun timing_dragOnOtherShapes() {
        for (r in listOf(PosterRatio.R16_9, PosterRatio.R1_1, PosterRatio.R4_5)) {
            val st = stateFor(r, 5, photo = true)
            st.addSticker(StickerKind.KILAU)
            // about the pixels of the old 720 x 1280 benchmark (0.92 million): what a phone's preview box holds on any shape
            val scale = sqrt(920_000f / (r.w * r.h))
            val rr = PosterRenderer(context, PosterAssets())
            var out: Bitmap? = null
            val times = ArrayList<Long>()
            repeat(6) { k ->
                st.poses[1] = PosterEdit.moved(st.poses[1], 12f, 6f, r)
                out = rr.render(st.frame(), (r.w * scale).toInt(), (r.h * scale).toInt(), 0, out)
                if (k > 0) times.add(rr.lastMillis)
            }
            println("poster drag ${r.name} ${(r.w * scale).toInt()}x${(r.h * scale).toInt()}: median ${times.sorted()[times.size / 2]} ms, stages ${rr.stageMillis.joinToString("/")}")
        }
    }

    private companion object {
        val Color0 = androidx.compose.ui.graphics.Color(0xFFD94A6A)
        val Color1 = androidx.compose.ui.graphics.Color(0xFF0F5C8C)
        val Color2 = androidx.compose.ui.graphics.Color(0xFFE0713F)
        val Color3 = androidx.compose.ui.graphics.Color(0xFF5A6F8A)
        val Color4 = androidx.compose.ui.graphics.Color(0xFF8E62D0)
    }
}
