package com.metrolist.music.poster.sticker

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.poster.PosterRequest
import com.metrolist.music.poster.PosterSessionFiles
import com.metrolist.music.poster.PosterSong
import com.metrolist.music.poster.PosterSongLookup
import com.metrolist.music.poster.PosterStickerLibrary
import com.metrolist.music.poster.PosterViewModel
import com.metrolist.music.poster.StickerKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.math.hypot

/**
 * Round 4.3.5, "Ubah stiker" for real (Robolectric, native graphics): the session's display and its "Pulihkan" ghost, the finished sticker,
 * the whole road (pick -> background off by colour -> brush -> Selesai -> "Stiker saya" + the poster -> "Ubah" again from the original), and
 * the editor after the process died.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StickerEditorTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val src = List(4) { PosterSong("s$it", "Lagu $it", "Artis $it", null, 200 + it) }
    private val noDatabase = PosterSongLookup { _, _ -> emptyList() }

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (!cond()) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
            if (cond()) return
            if (System.currentTimeMillis() > end) fail("timed out: $what")
            Thread.sleep(10)
        }
    }

    private fun editor(handle: SavedStateHandle = SavedStateHandle()): PosterViewModel {
        val vm = PosterViewModel(app, handle, noDatabase)
        vm.start(PosterRequest("Tes", src))
        vm.togglePick("s0")
        vm.confirmPick()
        return vm
    }

    /** A logo picture (red disc on white, as a phone screenshot or a logo file would be) written as a PNG, as the photo picker hands it over. */
    private fun logoUri(name: String = "logo.png"): Uri {
        val w = 240
        val h = 180
        val px = IntArray(w * h) { i ->
            val d = hypot(i % w + 0.5f - 120f, i / w + 0.5f - 90f)
            val k = (50.5f - d).coerceIn(0f, 1f)
            val r = (210 * k + 252 * (1 - k)).toInt()
            val g = (40 * k + 252 * (1 - k)).toInt()
            val b = (50 * k + 252 * (1 - k)).toInt()
            (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        val dir = File(app.cacheDir, "test-stickers").apply { mkdirs() }
        val f = File(dir, name)
        val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(f)
    }

    private fun session(w: Int = 60, h: Int = 40): StickerEditSession {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.eraseColor(0xFF3366AA.toInt())
        return StickerEditSession.of(b, null, null)
    }

    @Test
    fun session_displayFollowsTheMask_andRestoreShowsAGhost() {
        val s = session()
        assertEquals(255, s.display.getPixel(30, 20) ushr 24)
        s.selectTool(StickerTool.ERASE)
        s.beginStroke(30f, 20f, 6f)
        s.strokeTo(40f, 20f)
        s.endStroke()
        assertTrue(s.canUndo)
        assertTrue(s.changed)
        assertEquals(0, s.mask[20 * 60 + 35].toInt() and 255)
        assertEquals(0, s.display.getPixel(35, 20) ushr 24)
        // "Pulihkan" shows what was taken off faintly (a quarter), the mask itself stays 0
        s.selectTool(StickerTool.RESTORE)
        assertEquals(63, s.display.getPixel(35, 20) ushr 24)
        assertEquals(0, s.mask[20 * 60 + 35].toInt() and 255)
        s.beginStroke(35f, 20f, 3f)
        s.endStroke()
        assertEquals(255, s.mask[20 * 60 + 35].toInt() and 255)
        s.undo()
        assertEquals(0, s.mask[20 * 60 + 35].toInt() and 255)
        s.undo()
        assertEquals(255, s.mask[20 * 60 + 35].toInt() and 255)
        assertFalse(s.canUndo)
        assertTrue(s.canRedo)
        // the move tool never draws
        s.selectTool(StickerTool.MOVE)
        s.beginStroke(10f, 10f, 8f)
        assertFalse(s.stroking)
        s.release()
    }

    @Test
    fun session_aSecondFingerTakesTheStrokeBack() {
        val s = session()
        val before = s.maskCopy()
        s.beginStroke(10f, 10f, 5f)
        s.strokeTo(50f, 30f)
        s.cancelStroke()
        assertArrayEquals(before, s.mask)
        assertFalse(s.canUndo)
        s.release()
    }

    @Test
    fun session_resultIsCroppedToWhatIsKept_andEmptyIsNull() {
        val s = session()
        val m = ByteArray(60 * 40)
        for (y in 10 until 20) for (x in 5 until 25) m[y * 60 + x] = 255.toByte()
        s.setMask(m)
        val r = s.result()!!
        assertEquals(20 + 4, r.width)
        assertEquals(10 + 4, r.height)
        assertEquals(255, r.getPixel(12, 7) ushr 24)
        assertEquals(0, r.getPixel(0, 0) ushr 24)
        s.setMask(ByteArray(60 * 40))
        assertTrue(s.isEmpty())
        assertNull(s.result())
        // "Kembalikan asli": the picture as it came
        s.resetToOriginal()
        assertEquals(255, s.mask[0].toInt() and 255)
        s.release()
    }

    @Test
    fun theWholeRoad_pickRemoveBrushSave_thenEditAgainFromTheOriginal() {
        val vm = editor()
        vm.importStickers(listOf(logoUri()))
        waitUntil("editor open") { vm.stickerFlow.session != null }
        val s = vm.stickerFlow.session!!
        // a logo on a flat background: the colour fill was chosen and ran by itself
        waitUntil("background removed") { !s.busy && s.method != null }
        assertEquals(StickerMethod.COLOR, s.method)
        assertEquals(0, s.mask[0].toInt() and 255)
        assertEquals(255, s.mask[90 * 240 + 120].toInt() and 255)
        // a stroke of "Hapus" across the disc
        s.selectTool(StickerTool.ERASE)
        s.beginStroke(80f, 90f, 6f)
        s.strokeTo(160f, 90f)
        s.endStroke()
        assertEquals(0, s.mask[90 * 240 + 120].toInt() and 255)
        val finalMask = s.maskCopy()
        vm.stickerFlow.done()
        waitUntil("saved") { vm.stickerFlow.session == null && !vm.stickerFlow.saving }
        // into "Stiker saya" and onto the poster
        val st = vm.state.stickers.single()
        assertEquals(StickerKind.CUSTOM, st.kind)
        val key = st.imageKey!!
        assertTrue(vm.libraryItems.any { it.key == key })
        val pic = vm.state.stickerImages[key]!!
        assertTrue("cropped to the disc: ${pic.width} x ${pic.height}", pic.width in 100..110 && pic.height in 100..110)
        // edited again: the original picture and the mask it ended with
        vm.editLibrarySticker(key)
        waitUntil("editor again") { vm.stickerFlow.session != null }
        val again = vm.stickerFlow.session!!
        assertEquals(key, again.editKey)
        assertEquals(240, again.w)
        assertArrayEquals(finalMask, again.mask)
        // "Kembalikan asli" and Selesai: the same key gets the whole picture
        vm.stickerFlow.resetToOriginal()
        vm.stickerFlow.done()
        waitUntil("saved again") { vm.stickerFlow.session == null && !vm.stickerFlow.saving }
        assertEquals(1, vm.state.stickers.size)
        val whole = vm.state.stickerImages[key]!!
        assertEquals(240, whole.width)
        assertEquals(180, whole.height)
        // removing it from the library takes its original along
        val lib = PosterStickerLibrary(app)
        lib.remove(key)
        val left = File(app.filesDir, "poster_stickers").walkTopDown().filter { it.isFile && it.name.startsWith(key.removeSuffix(".png")) }.map { it.name }.toList()
        assertTrue("left after remove: $left", left.isEmpty())
        assertNull(lib.loadForEdit(key))
    }

    @Test
    fun anEmptyStickerIsNotSaved() {
        val vm = editor()
        vm.importStickers(listOf(logoUri("empty.png")))
        waitUntil("editor open") { vm.stickerFlow.session?.busy == false && vm.stickerFlow.session?.method != null }
        val s = vm.stickerFlow.session!!
        s.setMask(ByteArray(s.w * s.h))
        vm.stickerFlow.done()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull("still open: nothing to save", vm.stickerFlow.session)
        assertEquals(0, vm.state.stickers.size)
        vm.stickerFlow.cancel()
        assertNull(vm.stickerFlow.session)
    }

    @Test
    fun severalPictures_openOneAfterTheOther() {
        val vm = editor()
        vm.importStickers(listOf(logoUri("a.png"), logoUri("b.png")))
        waitUntil("first") { vm.stickerFlow.session != null && !vm.stickerFlow.session!!.busy }
        assertEquals(1, vm.stickerFlow.waiting)
        vm.stickerFlow.cancel()
        waitUntil("second") { vm.stickerFlow.session != null }
        assertEquals(0, vm.stickerFlow.waiting)
    }

    /** What the system does with a page's state: through a Parcel, into a new process. */
    private fun parcelled(b: Bundle): Bundle {
        val p = Parcel.obtain()
        try {
            p.writeBundle(b)
            p.setDataPosition(0)
            return p.readBundle(javaClass.classLoader)!!
        } finally {
            p.recycle()
        }
    }

    @Test
    fun theStickerEditor_comesBack_afterTheProcessDied() {
        val vm = editor()
        vm.importStickers(listOf(logoUri("death.png")))
        waitUntil("editor open") { vm.stickerFlow.session?.busy == false && vm.stickerFlow.session?.method != null }
        val s = vm.stickerFlow.session!!
        s.selectTool(StickerTool.RESTORE)
        s.brush = 0.8f
        s.beginStroke(20f, 20f, 15f)
        s.strokeTo(60f, 40f)
        s.endStroke()
        val want = s.maskCopy()
        val files = PosterSessionFiles(app, vm.snapshot().session)
        // the system saves the page's state: the editor's last mask is written to the cache at once (no waiting for the usual short delay).
        // The test waits for the flow's own signal and only then reads the file: it never polls the file while it is being replaced (on the
        // Windows test host an open reader makes the replace fail, lead review 2026-11-13; on Android a rename replaces an open file).
        val sticker = parcelled(vm.stickerFlow.saveState()!!)
        runBlocking { withTimeout(30_000) { vm.stickerFlow.flushPersist() } }
        assertArrayEquals("the mask written for the saved state", want, files.readStickerMask(s.w, s.h))
        val main = parcelled(vm.snapshot().toBundle())
        val again = PosterViewModel(app, SavedStateHandle(mapOf(PosterViewModel.SAVED_KEY to main, PosterViewModel.STICKER_KEY to sticker)), noDatabase).also { it.start(null) }
        waitUntil("editor back") { again.stickerFlow.session != null }
        val back = again.stickerFlow.session!!
        assertEquals(StickerTool.RESTORE, back.tool)
        assertEquals(0.8f, back.brush)
        assertEquals(StickerMethod.COLOR, back.method)
        assertTrue(back.changed)
        assertArrayEquals("the mask comes back exactly", want, back.mask)
        // the picture comes back as it was (a PNG: exact)
        assertArrayEquals(s.argb, back.argb)
    }

    /** Without a saved state, the debounced write still lands (on the session's file lane, whatever the main thread's clock does). */
    @Test
    fun aStroke_reachesTheCache_afterTheShortDelay() {
        val vm = editor()
        vm.importStickers(listOf(logoUri("debounce.png")))
        waitUntil("editor open") { vm.stickerFlow.session?.busy == false && vm.stickerFlow.session?.method != null }
        val s = vm.stickerFlow.session!!
        s.selectTool(StickerTool.ERASE)
        s.beginStroke(120f, 90f, 20f)
        s.endStroke()
        val want = s.maskCopy()
        runBlocking { withTimeout(30_000) { vm.stickerFlow.flushPersist() } }
        val files = PosterSessionFiles(app, vm.snapshot().session)
        assertArrayEquals(want, files.readStickerMask(s.w, s.h))
    }

    /**
     * Lead review 2026-11-13, the race found while looking for the cause: a live "Toleransi" re-run that finishes AFTER the user started a
     * stroke (right after letting the slider go) used to be shown over the stroke and wipe it. It is dropped now; the live result shown
     * before the stroke became its own undo step.
     */
    @Test
    fun aLateLiveResult_neverOverwritesAStroke() {
        val vm = editor()
        vm.importStickers(listOf(logoUri("live.png")))
        waitUntil("editor open") { vm.stickerFlow.session?.busy == false && vm.stickerFlow.session?.method == StickerMethod.COLOR }
        val s = vm.stickerFlow.session!!
        // the slider moves: a re-run starts (its result reaches the main thread only when the test lets the looper run)
        s.tolerance = 0.9f
        vm.stickerFlow.tune()
        // ... and before it lands, a stroke of "Hapus" across the disc
        s.selectTool(StickerTool.ERASE)
        s.beginStroke(80f, 90f, 6f)
        s.strokeTo(160f, 90f)
        s.endStroke()
        val afterStroke = s.maskCopy()
        assertEquals(0, afterStroke[90 * 240 + 120].toInt() and 255)
        vm.stickerFlow.tuneDone()
        waitUntil("re-run finished") { !vm.stickerFlow.tuning }
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        assertFalse(s.live)
        assertArrayEquals("the stroke stays", afterStroke, s.mask)
        // and undo takes back exactly the stroke
        s.undo()
        assertEquals(255, s.mask[90 * 240 + 120].toInt() and 255)
    }

    /**
     * The mechanism of the full-suite failure, on the test host: while a reader holds the mask file open, replacing it fails on Windows (the
     * rename cannot replace an open file) and succeeds where the app runs (Linux). The flow logs and keeps the old file in that case, so a
     * test that polls the file can wait forever; the tests above read only after the flow's own signal.
     */
    @Test
    fun replacingAnOpenMaskFile_failsOnlyOnWindows() {
        val f = PosterSessionFiles(app, "openfile")
        val a = ByteArray(16) { 1 }
        val b = ByteArray(16) { 2 }
        f.writeStickerMask(4, 4, a)
        val file = File(app.cacheDir, PosterSessionFiles.DIR + "/openfile-sticker-mask.bin")
        assertTrue(file.isFile)
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
        val replaced = file.inputStream().use { runCatching { f.writeStickerMask(4, 4, b) }.isSuccess }
        println("replace while open on ${System.getProperty("os.name")}: $replaced")
        assertEquals("replace while a reader holds the file", !windows, replaced)
        // once the reader is gone, the replace works everywhere
        f.writeStickerMask(4, 4, b)
        assertArrayEquals(b, f.readStickerMask(4, 4))
    }
}
