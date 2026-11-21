package com.metrolist.music.poster

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Review 2026-11-12: the poster after the process died (finding 14: the page came back empty and the picked photo was lost), undo after
 * removing a song (finding 15: the card came back without its cover) and after deleting a library picture (finding 16: invisible stickers).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PosterSavedStateTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val src = List(8) { PosterSong("s$it", "Lagu $it", "Artis $it", "https://example.invalid/$it.jpg", 200 + it) }
    private val noDatabase = PosterSongLookup { _, _ -> emptyList() }

    private fun editor(songs: Int = 3): PosterViewModel {
        val vm = PosterViewModel(app, SavedStateHandle(), noDatabase)
        vm.start(PosterRequest("Tes", src))
        repeat(songs) { vm.togglePick("s$it") }
        vm.confirmPick()
        return vm
    }

    /** What the system does with a page's state: through a Parcel, into a new process (a new view model). */
    private fun reborn(b: Bundle, lookup: PosterSongLookup = noDatabase): PosterViewModel {
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(b)
            parcel.setDataPosition(0)
            val back = parcel.readBundle(javaClass.classLoader)!!
            return PosterViewModel(app, SavedStateHandle(mapOf(PosterViewModel.SAVED_KEY to back)), lookup).also { it.start(null) }
        } finally {
            parcel.recycle()
        }
    }

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (!cond()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            if (System.currentTimeMillis() > end) fail("timed out: $what")
            Thread.sleep(10)
        }
    }

    private fun PosterDoc.plainZeros(): PosterDoc = copy(poses = poses.map { it.copy(x = it.x + 0f, y = it.y + 0f, scale = it.scale + 0f, rotation = it.rotation + 0f, tiltX = it.tiltX + 0f, tiltY = it.tiltY + 0f, lift = it.lift + 0f) })

    private fun bitmap(color: Int): Bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test
    fun undoAfterRemovingASong_bringsTheCardBackWithItsCover() {
        val vm = editor(3)
        val cover = bitmap(0xFF3366AA.toInt())
        vm.state.covers["s1"] = cover
        vm.removeSong(1)
        assertEquals(listOf("s0", "s2"), vm.state.songs.map { it.id })
        vm.undo()
        assertEquals(listOf("s0", "s1", "s2"), vm.state.songs.map { it.id })
        assertTrue("the same cover, not the placeholder", vm.state.covers["s1"] === cover)
        // the frame the picture is painted from has it too
        assertTrue(vm.state.frame().covers["s1"] === cover)
    }

    @Test
    fun undoAfterDeletingALibraryPicture_bringsVisibleStickersBack() {
        val vm = editor(2)
        val key = "s00000000000010001.png"
        val pic = bitmap(0xFFCC8844.toInt())
        vm.state.stickerImages[key] = pic
        vm.libraryItems.add(StickerLibItem(key, pic))
        vm.state.addSticker(StickerKind.CUSTOM, key)
        vm.state.addSticker(StickerKind.KILAU)
        vm.state.addSticker(StickerKind.CUSTOM, key)
        vm.removeFromLibrary(key)
        assertEquals(listOf(StickerKind.KILAU), vm.state.stickers.map { it.kind })
        assertTrue("kept while undo can bring it back", vm.state.stickerImages.containsKey(key))
        vm.undo()
        assertEquals(listOf(StickerKind.CUSTOM, StickerKind.KILAU, StickerKind.CUSTOM), vm.state.stickers.map { it.kind })
        for (s in vm.state.stickers) if (s.kind == StickerKind.CUSTOM) assertTrue("visible", vm.state.stickerImages.containsKey(s.imageKey))
    }

    @Test
    fun restoredUndo_leavesOutStickersWhosePictureIsGone() {
        val st = PosterState()
        st.setSongs(src.take(2))
        st.stickerImages["k"] = bitmap(-1)
        st.addSticker(StickerKind.CUSTOM, "k")
        st.addSticker(StickerKind.BURUNG)
        val undo = PosterUndo()
        undo.mark(st)
        assertEquals(setOf("k"), undo.imageKeys())
        st.stickers.clear()
        st.stickerImages.clear()
        undo.undo(st)
        // the bird comes back; the picture is gone, so no invisible sticker that takes touches and counts toward the limit
        assertEquals(listOf(StickerKind.BURUNG), st.stickers.map { it.kind })
    }

    @Test
    fun theEditor_comesBack_afterTheProcessDied() {
        val vm = editor(3)
        vm.mark()
        vm.state.poses[1] = PosterEdit.moved(vm.state.poses[1], 40f, 25f)
        vm.state.dim = 0.31f
        vm.state.glowTint = androidx.compose.ui.graphics.Color(0xFFFFC857)
        vm.state.applyPreset(PosterFxPreset.FILM)
        vm.state.applyLayout(PosterLayout.TUMPUK)
        vm.state.poses[0] = vm.state.poses[0].copy(rotation = 12f, tiltX = 7f)
        vm.addSticker(StickerKind.KILAU)
        vm.state.stickers[0] = vm.state.stickers[0].copy(x = 300f, y = 410f, scale = 1.7f, flip = true)
        val want = vm.state.doc()
        val again = reborn(vm.snapshot().toBundle())
        assertEquals(PosterViewModel.Stage.EDIT, again.stage)
        assertEquals("Tes", again.title)
        assertEquals(vm.state.songs.toList(), again.state.songs.toList())
        // (a -0.0 tilt of a ready-made layout comes back as 0.0: the same pose)
        assertEquals(want.plainZeros(), again.state.doc().plainZeros())
        // the songs it started with (leaving asks only when something would be lost)
        assertEquals("photo ${again.state.photo != null}, songs ${again.state.songs.map { it.id }}", vm.needsLeaveConfirm(), again.needsLeaveConfirm())
    }

    /**
     * The real road: the default factory of a page (SavedStateViewModelFactory, the one a navigation entry uses) creates the editor with its
     * SavedStateHandle, the activity's state is saved, and a NEW activity (a new process) gets the editor back from it.
     */
    @Test
    fun theDefaultFactory_givesTheEditorItsSavedState() {
        val first = org.robolectric.Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup()
        val vm = androidx.lifecycle.ViewModelProvider(first.get())[PosterViewModel::class.java]
        vm.start(PosterRequest("Pabrik", src))
        vm.togglePick("s1")
        vm.togglePick("s4")
        vm.confirmPick()
        vm.state.dim = 0.27f
        val out = Bundle()
        first.saveInstanceState(out)
        val second = org.robolectric.Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup(out)
        val again = androidx.lifecycle.ViewModelProvider(second.get())[PosterViewModel::class.java]
        again.start(null)
        assertTrue("a new editor, not the old object", again !== vm)
        assertEquals(PosterViewModel.Stage.EDIT, again.stage)
        assertEquals(listOf("s1", "s4"), again.state.songs.map { it.id })
        assertEquals(0.27f, again.state.dim)
        assertEquals("Pabrik", again.title)
    }

    @Test
    fun thePicker_comesBack_withTheSongsFromTheDatabase() {
        val vm = PosterViewModel(app, SavedStateHandle(), noDatabase)
        vm.start(PosterRequest("Daftar", src))
        vm.togglePick("s2")
        vm.togglePick("s7")
        // the database has the first six songs; s7 (an online song never stored) comes from the saved picked songs
        val asked = ArrayList<String>()
        val again = reborn(vm.snapshot().toBundle(), PosterSongLookup { _, ids -> asked.addAll(ids); src.take(6).filter { it.id in ids } })
        assertEquals(PosterViewModel.Stage.LOADING, again.stage)
        waitUntil("picker") { again.stage != PosterViewModel.Stage.LOADING }
        assertEquals(PosterViewModel.Stage.PICK, again.stage)
        assertEquals(src.map { it.id }, asked)
        assertEquals(listOf("s0", "s1", "s2", "s3", "s4", "s5", "s7"), again.source.map { it.id })
        assertEquals(listOf("s2", "s7"), again.picked)
        assertEquals("Daftar", again.title)
        again.confirmPick()
        assertEquals(listOf("s2", "s7"), again.state.songs.map { it.id })
    }

    @Test
    fun aPickerWhoseSongsAreAllGone_showsTheEmptyPage() {
        val vm = PosterViewModel(app, SavedStateHandle(), noDatabase)
        vm.start(PosterRequest("Daftar", src))
        val again = reborn(vm.snapshot().toBundle())
        waitUntil("empty") { again.stage != PosterViewModel.Stage.LOADING }
        assertEquals(PosterViewModel.Stage.EMPTY, again.stage)
        // and a page with no saved state and no songs handed over is empty at once
        val none = PosterViewModel(app, SavedStateHandle(), noDatabase)
        none.start(null)
        assertEquals(PosterViewModel.Stage.EMPTY, none.stage)
    }

    @Test
    fun thePhotoAndItsCutout_comeBack_fromTheCache() {
        val vm = editor(2)
        val s = vm.snapshot()
        val photo = PosterSamples.syntheticPhoto(120, 200)
        val files = PosterSessionFiles(app, s.session)
        files.writePhoto(photo.image)
        files.writeMask(photo.w, photo.h, photo.alpha)
        val snap = PosterSnapshot(s.session, s.stage, s.title, s.sourceIds, s.picked, s.pickedSongs, s.songs, s.startSongs, s.doc, CutoutPhase.DONE, true)
        val again = reborn(snap.toBundle())
        assertEquals(PosterViewModel.Stage.EDIT, again.stage)
        waitUntil("photo") { again.state.photo != null }
        val p = again.state.photo!!
        assertEquals(120, p.w)
        assertEquals(200, p.h)
        assertEquals(CutoutPhase.DONE, again.state.cutout)
        assertArrayEquals("the mask is kept exactly", photo.alpha, p.alpha)
        assertTrue(p.hasMask)
        // a JPEG copy: close to the photo
        var worst = 0
        for (i in p.image.argb.indices step 37) worst = maxOf(worst, kotlin.math.abs((p.image.argb[i] and 255) - (photo.image.argb[i] and 255)))
        assertTrue("worst blue difference $worst", worst < 24)
    }

    @Test
    fun aPickedPhoto_goesIntoTheCacheAtOnce() {
        val vm = editor(2)
        val dir = File(app.cacheDir, "test-photos").apply { mkdirs() }
        val jpg = File(dir, "pick.jpg")
        val pic = PosterSamples.syntheticPhoto(160, 240)
        val b = Bitmap.createBitmap(pic.image.argb, pic.w, pic.h, Bitmap.Config.ARGB_8888)
        jpg.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        vm.choosePhoto(Uri.fromFile(jpg))
        waitUntil("photo picked") { vm.state.photo != null }
        vm.cancelCutout()
        val files = PosterSessionFiles(app, vm.snapshot().session)
        waitUntil("photo cached") { files.hasPhoto() }
        val cached = files.readPhoto()
        assertNotNull(cached)
        assertEquals(160, cached!!.width)
        // leaving the poster deletes the session's files
        val clear = PosterViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }
        clear.invoke(vm)
        waitUntil("files deleted") { !files.hasPhoto() }
        assertFalse(files.hasPhoto())
    }

    @Test
    fun snapshot_isSmall_evenForABigPlaylist() {
        val big = List(2000) { PosterSong("id%09d".format(it), "Judul lagu yang cukup panjang $it", "Artis $it", "https://lh3.googleusercontent.com/abcdefghijklmnopqrstuvwxyz/$it", 180) }
        val vm = PosterViewModel(app, SavedStateHandle(), noDatabase)
        vm.start(PosterRequest("Besar", big))
        repeat(5) { vm.togglePick(big[it * 300].id) }
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(vm.snapshot().toBundle())
            println("poster saved state of a 2000-song picker: ${parcel.dataSize()} bytes")
            assertTrue("${parcel.dataSize()} bytes", parcel.dataSize() < 60_000)
        } finally {
            parcel.recycle()
        }
    }
}
