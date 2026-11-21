package com.metrolist.music.poster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The poster's state and editor rules: limits (5 songs, 30 stickers), presets and materials, undo, the picker, leaving. */
class PosterStateTest {
    private fun song(i: Int) = PosterSong("s$i", "Lagu $i", "Artis $i", null, 180 + i)

    @Test
    fun songs_atMostFive_distinct_withOnePosePerCard() {
        val st = PosterState()
        st.setSongs(List(8) { song(it) } + song(0))
        assertEquals(5, st.songs.size)
        assertEquals(5, st.poses.size)
        assertEquals(listOf("s0", "s1", "s2", "s3", "s4"), st.songs.map { it.id })
        st.setSongs(listOf(song(1), song(1), song(2)))
        assertEquals(2, st.songs.size)
        assertEquals(PosterLayouts.poses(st.layout, 2), st.poses.toList())
    }

    @Test
    fun defaults_areTheCleanEditorialLook_withGlints_butNoGrainOrStreak() {
        val st = PosterState()
        // round 4.3.5: the default is the clean editorial poster (no colour grade, no streak; grain 0 by the owner's rule); round K: with
        // "Kilau" on (the owner: "kenapa efek kilauan nya jadi ilang"), in every preset
        assertEquals(PosterFxPreset.BERSIH, st.preset)
        assertEquals(PosterFxPreset.BERSIH, PosterFxPreset.entries.first())
        assertEquals(0f, st.fxGrain)
        assertEquals(PosterFxDefaults.SPARKLE_BERSIH, st.fxSparkle)
        assertTrue(st.fxSparkle >= 0.4f)
        assertEquals(0f, st.fxStreak)
        assertEquals(PosterGrade.ASLI, st.grade)
        for (p in PosterFxPreset.entries) {
            val q = PosterState()
            q.applyPreset(p)
            assertTrue("$p has no glints", q.fxSparkle >= 0.4f)
        }
        // the defaults are exactly the preset
        val p = PosterState()
        p.fxBloom = 0.9f
        p.applyPreset(PosterFxPreset.BERSIH)
        assertEquals(listOf(st.grade, st.fxBloom, st.fxLeak, st.fxStreak, st.fxSparkle, st.fxGrain, st.fxRim), listOf(p.grade, p.fxBloom, p.fxLeak, p.fxStreak, p.fxSparkle, p.fxGrain, p.fxRim))
        st.applyPreset(PosterFxPreset.FILM)
        assertEquals(0.5f, st.fxGrain)
        assertEquals(PosterGrade.FILM, st.grade)
        st.applyPreset(PosterFxPreset.SINEMA)
        assertEquals(0f, st.fxGrain)
        assertEquals(PosterGrade.HANGAT, st.grade)
        PosterFxEdit.setSlider(st, FxSlider.BLOOM, 0.9f)
        assertEquals(null, st.preset)
        PosterFxEdit.setPreset(st, PosterFxPreset.BERSIH)
        PosterFxEdit.setGrade(st, PosterGrade.NEON)
        assertEquals(null, st.preset)
    }

    @Test
    fun materials_setTheGlass_andAHandMoveUnlightsTheChip() {
        val st = PosterState()
        PosterStyleEdit.setMaterial(st, CardMaterial.GELAP)
        assertEquals(0.97f, st.cardOpacity)
        assertTrue(PosterStyleEdit.isLit(st, CardMaterial.GELAP))
        PosterStyleEdit.setSlider(st, CardSlider.KACA_BURAM, 0.2f)
        assertFalse(PosterStyleEdit.isLit(st, CardMaterial.GELAP))
        PosterStyleEdit.setSlider(st, CardSlider.PERSPEKTIF, 0.9f)
        PosterStyleEdit.setShadowAngle(st, -30f)
        assertEquals(330f, st.shadowAngle)
        PosterStyleEdit.reset(st)
        assertEquals(0.5f, st.cardPerspective)
        assertEquals(115f, st.shadowAngle)
        assertTrue(PosterStyleEdit.isLit(st, CardMaterial.ASAP))
    }

    @Test
    fun stickers_limitDuplicateRemove() {
        val st = PosterState()
        assertEquals(-1, st.addSticker(StickerKind.CUSTOM, "missing"))
        repeat(PosterStickers.MAX_COUNT) { assertEquals(it, st.addSticker(if (it % 2 == 0) StickerKind.BURUNG else StickerKind.KILAU)) }
        assertEquals(-1, st.addSticker(StickerKind.BURUNG))
        assertEquals(-1, st.duplicateSticker(0))
        st.removeSticker(3)
        assertEquals(PosterStickers.MAX_COUNT - 1, st.stickers.size)
        assertEquals(-1, st.selectedSticker)
        val d = st.duplicateSticker(0)
        assertEquals(st.stickers.lastIndex, d)
        assertNotEquals(st.stickers[0].id, st.stickers[d].id)
        assertEquals(st.stickers[0].x + 56f, st.stickers[d].x)
        assertEquals(st.stickers.map { it.id }.distinct().size, st.stickers.size)
        // selecting a sticker drops the card selection (one place freed first: the list is full again)
        st.removeSticker(0)
        st.selected = 0
        assertTrue(st.addSticker(StickerKind.BURUNG) >= 0)
        assertEquals(-1, st.selected)
    }

    @Test
    fun undo_bringsBackTheDocument_notTheSameStateTwice() {
        val st = PosterState()
        st.setSongs(List(3) { song(it) })
        val undo = PosterUndo()
        assertFalse(undo.canUndo)
        undo.mark(st)
        undo.mark(st)
        assertEquals(1, undo.size)
        val before = st.poses[0]
        st.poses[0] = PosterEdit.moved(before, 100f, 50f)
        undo.mark(st)
        st.addSticker(StickerKind.KILAU)
        st.fxLeak = 0.1f
        assertTrue(undo.undo(st))
        assertEquals(0, st.stickers.size)
        assertEquals(PosterState().fxLeak, st.fxLeak)
        assertEquals(before.x + 100f, st.poses[0].x)
        assertTrue(undo.undo(st))
        assertEquals(before, st.poses[0])
        assertFalse(undo.undo(st))
    }

    @Test
    fun undo_isBounded() {
        val st = PosterState()
        val undo = PosterUndo(max = 5)
        repeat(20) {
            st.dim = it / 100f
            undo.mark(st)
        }
        assertEquals(5, undo.size)
    }

    @Test
    fun songs_moveAndRemove() {
        val st = PosterState()
        st.setSongs(List(4) { song(it) })
        st.selected = 1
        PosterEdit.moveSong(st, 1, 1)
        assertEquals("s2", st.songs[1].id)
        assertEquals(2, st.selected)
        assertTrue(PosterEdit.removeSong(st, 0))
        assertEquals(3, st.songs.size)
        assertEquals(PosterLayouts.poses(st.layout, 3), st.poses.toList())
        st.poses[0] = PosterEdit.moved(st.poses[0], 10f, 0f)
        assertTrue(PosterEdit.removeSong(st, 2))
        // a card moved by hand keeps its place
        assertEquals(2, st.poses.size)
        PosterEdit.removeSong(st, 0)
        assertFalse(PosterEdit.removeSong(st, 0))
        assertEquals(1, st.songs.size)
    }

    @Test
    fun removingASong_keepsItsCoverColour_soUndoBringsTheCardBackWhole() {
        // review 2026-11-12, finding 15 (the bitmap case runs in PosterSavedStateTest)
        val st = PosterState()
        st.setSongs(List(3) { song(it) })
        st.tints["s1"] = androidx.compose.ui.graphics.Color(0xFF3366AA)
        val undo = PosterUndo()
        undo.mark(st)
        assertTrue(PosterEdit.removeSong(st, 1))
        assertTrue(st.tints.containsKey("s1"))
        assertTrue(undo.undo(st))
        assertEquals(listOf("s0", "s1", "s2"), st.songs.map { it.id })
        assertEquals(androidx.compose.ui.graphics.Color(0xFF3366AA), st.tints["s1"])
    }

    @Test
    fun picker_atMostFive_inPlaylistOrder() {
        val songs = List(9) { song(it) }
        var sel = emptyList<String>()
        for (i in listOf(7, 2, 5, 1, 8, 3)) sel = PosterPick.toggle(sel, "s$i")
        assertEquals(5, sel.size)
        assertTrue(PosterPick.isDisabled(sel, "s3"))
        assertFalse(PosterPick.isDisabled(sel, "s7"))
        assertEquals(listOf("s1", "s2", "s5", "s7", "s8"), PosterPick.chosen(songs, sel).map { it.id })
        sel = PosterPick.toggle(sel, "s7")
        assertEquals(4, sel.size)
        assertEquals(listOf("s0", "s1", "s2", "s3", "s4"), PosterPick.firstIds(songs))
        assertFalse(PosterPick.canContinue(emptyList()))
    }

    @Test
    fun leaving_asksOnlyWhenSomethingWouldBeLost() {
        assertFalse(PosterExit.needsConfirm(hasPhoto = true, songsChanged = false, changedSinceSave = false))
        assertFalse(PosterExit.needsConfirm(hasPhoto = false, songsChanged = false, changedSinceSave = true))
        assertTrue(PosterExit.needsConfirm(hasPhoto = true, songsChanged = false, changedSinceSave = true))
        assertTrue(PosterExit.needsConfirm(hasPhoto = false, songsChanged = true, changedSinceSave = true))
    }

    @Test
    fun removingALibraryPicture_takesItsStickersOff() {
        val st = PosterState()
        st.stickers.add(PosterSticker(1, StickerKind.CUSTOM, 1f, 1f, imageKey = "a"))
        st.stickers.add(PosterSticker(2, StickerKind.KILAU, 1f, 1f))
        st.stickers.add(PosterSticker(3, StickerKind.CUSTOM, 1f, 1f, imageKey = "a"))
        st.selectedSticker = 1
        PosterEdit.removeStickersWithKey(st, "a")
        assertEquals(listOf(2), st.stickers.map { it.id })
        assertEquals(-1, st.selectedSticker)
    }
}
