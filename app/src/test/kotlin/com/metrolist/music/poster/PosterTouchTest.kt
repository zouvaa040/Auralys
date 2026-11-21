package com.metrolist.music.poster

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The editor's touch rules through the real view model: what a finger lands on, drag, pinch, the sticker handle, taps, undo of a gesture, the picker. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class PosterTouchTest {
    private fun vm(songs: Int = 3): PosterViewModel {
        val vm = PosterViewModel(ApplicationProvider.getApplicationContext<Application>())
        vm.start(PosterRequest("Tes", List(8) { PosterSong("s$it", "Lagu $it", "Artis", null, 200) }))
        repeat(songs) { vm.togglePick("s$it") }
        vm.confirmPick()
        return vm
    }

    @Test
    fun picker_startsEmptyForBigLists_andConfirmsInPlaylistOrder() {
        val vm = PosterViewModel(ApplicationProvider.getApplicationContext<Application>())
        vm.start(PosterRequest("Tes", List(8) { PosterSong("s$it", "Lagu $it", "Artis", null, 200) }))
        assertEquals(PosterViewModel.Stage.PICK, vm.stage)
        assertEquals(0, vm.picked.size)
        vm.togglePick("s5")
        vm.togglePick("s1")
        vm.confirmPick()
        assertEquals(PosterViewModel.Stage.EDIT, vm.stage)
        assertEquals(listOf("s1", "s5"), vm.state.songs.map { it.id })
        val small = PosterViewModel(ApplicationProvider.getApplicationContext<Application>())
        small.start(PosterRequest("Tes", List(3) { PosterSong("t$it", "Lagu", "A", null, 1) }))
        assertEquals(3, small.picked.size)
        val none = PosterViewModel(ApplicationProvider.getApplicationContext<Application>())
        none.start(null)
        assertEquals(PosterViewModel.Stage.EMPTY, none.stage)
    }

    @Test
    fun dragOnACard_selectsAndMovesIt_pinchSizesAndTurns_undoBringsItBack() {
        val vm = vm()
        val p = vm.state.poses[1]
        val t = vm.hit(p.x, p.y)
        assertEquals(PosterTouchTarget.Card(1), t)
        vm.begin(t)
        assertEquals(1, vm.state.selected)
        vm.move(t, p.x, p.y, p.x, p.y, 30f, 20f, 1f, 0f)
        vm.move(t, p.x, p.y, p.x, p.y, 0f, 0f, 1.25f, 4f)
        vm.end(t)
        val q = vm.state.poses[1]
        assertEquals(p.x + 30f, q.x, 0.01f)
        assertEquals(p.y + 20f, q.y, 0.01f)
        assertEquals(p.scale * 1.25f, q.scale, 0.01f)
        assertEquals(p.rotation + 4f, q.rotation, 0.01f)
        assertTrue(vm.canUndo)
        vm.undo()
        assertEquals(p, vm.state.poses[1])
    }

    @Test
    fun tap_selectsAndDeselects() {
        val vm = vm()
        val p = vm.state.poses[0]
        vm.tap(vm.hit(p.x, p.y))
        assertEquals(0, vm.state.selected)
        vm.tap(vm.hit(p.x, p.y))
        assertEquals(-1, vm.state.selected)
        vm.tap(vm.hit(p.x, p.y))
        // a tap on nothing clears the selection
        vm.tap(vm.hit(540f, 1880f))
        assertEquals(-1, vm.state.selected)
    }

    @Test
    fun stickers_takeTouchesBeforeCards_andTheHandleSizesAndTurns() {
        val vm = vm()
        vm.addSticker(StickerKind.KILAU)
        val i = vm.state.selectedSticker
        val s = vm.state.stickers[i]
        assertEquals(PosterTouchTarget.Sticker(i), vm.hit(s.x, s.y))
        val (hx, hy) = PosterGeometry.stickerHandle(s)
        val h = vm.hit(hx, hy)
        assertEquals(PosterTouchTarget.StickerHandle(i), h)
        vm.begin(h)
        // drag the handle twice as far out, at a right angle: size x2, turned 90 degrees
        val dx = hx - s.x
        val dy = hy - s.y
        vm.move(h, s.x - dy * 2f, s.y + dx * 2f, 0f, 0f, 0f, 0f, 1f, 0f)
        val r = vm.state.stickers[i]
        assertEquals(2f, r.scale, 0.01f)
        assertEquals(90f, r.rotation, 0.1f)
        vm.deleteSticker()
        assertEquals(0, vm.state.stickers.size)
    }

    @Test
    fun photoGestures_needAPhoto() {
        val vm = vm()
        assertEquals(PosterTouchTarget.None, vm.hit(540f, 1880f))
        vm.state.photo = PosterSamples.syntheticPhoto(600, 800)
        assertEquals(PosterTouchTarget.Photo, vm.hit(540f, 1880f))
        vm.begin(PosterTouchTarget.Photo)
        vm.move(PosterTouchTarget.Photo, 540f, 960f, 540f, 960f, 0f, 0f, 1.5f, 0f)
        assertEquals(1.5f, vm.state.photoZoom, 0.001f)
        vm.fitWhole()
        assertTrue(vm.state.photoZoom < 1f)
        vm.fillScreen()
        assertEquals(1f, vm.state.photoZoom)
    }
}
