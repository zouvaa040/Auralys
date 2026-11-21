/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.extensions.getCurrentQueueIndex
import com.metrolist.music.extensions.getQueueWindows
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * "Putar berikutnya" and the Undo of a removal on a REAL ExoPlayer (its timeline, its shuffle order), for the rows the
 * queue screen shows: the play order. Nothing is prepared, so nothing is loaded; the timeline and the shuffle order are
 * the player's own.
 *
 * What the screen shows is [getQueueWindows]; what plays next is [ExoPlayer.getNextMediaItemIndex]. Both are checked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QueueSwipePlayerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val players = mutableListOf<ExoPlayer>()

    private fun newPlayer(): ExoPlayer =
        ExoPlayer
            .Builder(context, RenderersFactory { _, _, _, _, _ -> arrayOf<Renderer>(SilentRenderer()) })
            .setLooper(Looper.getMainLooper())
            .build()
            .also { players += it }

    private class SilentRenderer : BaseRenderer(C.TRACK_TYPE_AUDIO) {
        override fun getName() = "Silent"

        override fun supportsFormat(format: Format): Int = RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)

        override fun render(
            positionUs: Long,
            elapsedRealtimeUs: Long,
        ) {}

        override fun isReady() = true

        override fun isEnded() = true
    }

    @After
    fun release() {
        players.forEach { it.release() }
        players.clear()
    }

    private fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri("https://example.invalid/$id.mp3").build()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** A player with songs [ids] in the timeline, [current] playing, and shuffle off. */
    private fun queue(
        ids: List<String>,
        current: String,
    ): ExoPlayer =
        newPlayer().also {
            it.setMediaItems(ids.map(::item))
            it.seekToDefaultPosition(ids.indexOf(current))
            idle()
        }

    /** The same with shuffle on and the play order set to [shuffled] (song ids, the playing one included). */
    private fun shuffledQueue(
        timeline: List<String>,
        shuffled: List<String>,
        current: String,
    ): ExoPlayer =
        queue(timeline, current).also {
            it.shuffleModeEnabled = true
            it.setShuffleOrder(DefaultShuffleOrder(IntArray(shuffled.size) { i -> timeline.indexOf(shuffled[i]) }, 7L))
            idle()
        }

    private fun ExoPlayer.shown(): List<String> = getQueueWindows().map { it.mediaItem.mediaId }

    private fun ExoPlayer.uidOf(id: String): Any = getQueueWindows().first { it.mediaItem.mediaId == id }.uid

    private fun ExoPlayer.nextId(): String? = if (nextMediaItemIndex == C.INDEX_UNSET) null else getMediaItemAt(nextMediaItemIndex).mediaId

    private fun ExoPlayer.timelineIds(): List<String> = (0 until mediaItemCount).map { getMediaItemAt(it).mediaId }

    private val abcde = listOf("A", "B", "C", "D", "E")

    // ---------------------------------------------------------------------------------------------- shuffle off

    @Test
    fun `the test player shows the songs in timeline order with the playing one found`() {
        val p = queue(abcde, "B")
        assertEquals(abcde, p.shown())
        assertEquals(1, p.getCurrentQueueIndex())
        assertEquals("C", p.nextId())
    }

    @Test
    fun `play next moves a row down to right after the playing song`() {
        val p = queue(abcde, "B")
        val slot = p.playNextInQueue(p.uidOf("D"))
        assertNotNull(slot)
        idle()
        assertEquals(listOf("A", "B", "D", "C", "E"), p.shown())
        assertEquals("D", p.nextId())
        assertEquals("B", p.currentMediaItem?.mediaId)
        assertEquals(5, p.mediaItemCount) // one copy, not two
    }

    @Test
    fun `play next on a row above the playing song moves it after the playing song`() {
        val p = queue(abcde, "C")
        assertNotNull(p.playNextInQueue(p.uidOf("A")))
        idle()
        assertEquals(listOf("B", "C", "A", "D", "E"), p.shown())
        assertEquals("C", p.currentMediaItem?.mediaId)
        assertEquals("A", p.nextId())
        assertEquals(1, p.getCurrentQueueIndex()) // the playing song is one row higher now
    }

    @Test
    fun `play next on the last row`() {
        val p = queue(abcde, "A")
        assertNotNull(p.playNextInQueue(p.uidOf("E")))
        idle()
        assertEquals(listOf("A", "E", "B", "C", "D"), p.shown())
        assertEquals("E", p.nextId())
    }

    @Test
    fun `the playing song and the song already next are left alone`() {
        val p = queue(abcde, "B")
        assertNull(p.playNextInQueue(p.uidOf("B")))
        assertNull(p.playNextInQueue(p.uidOf("C")))
        idle()
        assertEquals(abcde, p.shown())
        assertEquals(abcde, p.timelineIds())
    }

    @Test
    fun `a row that is gone does nothing`() {
        val p = queue(abcde, "B")
        val ghost = p.uidOf("D")
        p.removeMediaItem(3)
        idle()
        assertNull(p.playNextInQueue(ghost))
        assertEquals(listOf("A", "B", "C", "E"), p.shown())
    }

    @Test
    fun `undo puts the row back between its old neighbours`() {
        val p = queue(abcde, "B")
        val d = p.uidOf("D")
        val slot = p.playNextInQueue(d)!!
        idle()
        assertTrue(p.undoPlayNext(d, slot))
        idle()
        assertEquals(abcde, p.shown())
        assertEquals("B", p.currentMediaItem?.mediaId)
        assertEquals("C", p.nextId())
    }

    @Test
    fun `undo after other rows moved or went away`() {
        val p = queue(abcde, "B")
        val d = p.uidOf("D")
        val slot = p.playNextInQueue(d)!! // A B D C E, D came from between C and E
        idle()
        p.removeMediaItem(0) // A goes away: indices shift by one
        p.addMediaItem(item("F"))
        idle()
        assertEquals(listOf("B", "D", "C", "E", "F"), p.shown())
        assertTrue(p.undoPlayNext(d, slot))
        idle()
        assertEquals(listOf("B", "C", "D", "E", "F"), p.shown())
    }

    @Test
    fun `undo does nothing when the row is the playing song by then`() {
        val p = queue(abcde, "B")
        val d = p.uidOf("D")
        val slot = p.playNextInQueue(d)!!
        idle()
        p.seekToDefaultPosition(p.timelineIds().indexOf("D")) // the song ended, D plays
        idle()
        assertFalse(p.undoPlayNext(d, slot))
        assertEquals("D", p.currentMediaItem?.mediaId)
    }

    // ------------------------------------------------------------------------------------------------ shuffle on

    // the timeline is A B C D E F, the play order (what the screen shows) is A E C F B D
    private val timeline = listOf("A", "B", "C", "D", "E", "F")
    private val shuffled = listOf("A", "E", "C", "F", "B", "D")

    @Test
    fun `the shuffled test player shows the play order`() {
        val p = shuffledQueue(timeline, shuffled, "C")
        assertEquals(shuffled, p.shown())
        assertEquals(2, p.getCurrentQueueIndex())
        assertEquals("F", p.nextId())
    }

    @Test
    fun `with shuffle on play next rewrites the shuffle order and leaves the timeline alone`() {
        val p = shuffledQueue(timeline, shuffled, "C")
        assertNotNull(p.playNextInQueue(p.uidOf("B")))
        idle()
        assertEquals(listOf("A", "E", "C", "B", "F", "D"), p.shown())
        assertEquals("B", p.nextId()) // what really plays after C
        assertEquals(timeline, p.timelineIds()) // moving the item in the timeline would not have changed what plays next
        assertEquals("C", p.currentMediaItem?.mediaId)
        assertTrue(p.shuffleModeEnabled)
    }

    @Test
    fun `with shuffle on a row above the playing song plays next too`() {
        val p = shuffledQueue(timeline, shuffled, "C")
        assertNotNull(p.playNextInQueue(p.uidOf("A")))
        idle()
        assertEquals(listOf("E", "C", "A", "F", "B", "D"), p.shown())
        assertEquals("A", p.nextId())
        assertEquals(1, p.getCurrentQueueIndex())
    }

    @Test
    fun `with shuffle on the playing song and the next song are left alone`() {
        val p = shuffledQueue(timeline, shuffled, "C")
        assertNull(p.playNextInQueue(p.uidOf("C")))
        assertNull(p.playNextInQueue(p.uidOf("F"))) // F is next in the play order although it is far away in the timeline
        idle()
        assertEquals(shuffled, p.shown())
    }

    @Test
    fun `with shuffle on a row that is next in the timeline is not next in the play order`() {
        val p = shuffledQueue(timeline, shuffled, "C")
        // D follows C in the timeline but is the LAST song of the play order: it must move
        assertNotNull(p.playNextInQueue(p.uidOf("D")))
        idle()
        assertEquals("D", p.nextId())
        assertEquals(listOf("A", "E", "C", "D", "F", "B"), p.shown())
    }

    @Test
    fun `with shuffle on undo puts the row back in the play order`() {
        val p = shuffledQueue(timeline, shuffled, "C")
        val b = p.uidOf("B")
        val slot = p.playNextInQueue(b)!!
        idle()
        assertTrue(p.undoPlayNext(b, slot))
        idle()
        assertEquals(shuffled, p.shown())
        assertEquals("F", p.nextId())
    }

    @Test
    fun `with shuffle on the next song after the played-next one is the old next song`() {
        val p = shuffledQueue(timeline, shuffled, "C")
        p.playNextInQueue(p.uidOf("B"))
        idle()
        p.seekToNextMediaItem() // B plays
        idle()
        assertEquals("B", p.currentMediaItem?.mediaId)
        assertEquals("F", p.nextId())
    }

    // ----------------------------------------------------------------------------------------- undo of a removal

    @Test
    fun `undo of a removal puts the song back where it was`() {
        val p = queue(abcde, "A")
        val uid = p.uidOf("C")
        val slot = p.slotOfRow(uid)
        val removed = p.getQueueWindows().first { it.uid == uid }
        val mediaItem = removed.mediaItem
        p.removeMediaItem(removed.firstPeriodIndex)
        idle()
        assertEquals(listOf("A", "B", "D", "E"), p.shown())
        p.reinsertInQueue(mediaItem, slot)
        idle()
        assertEquals(abcde, p.shown())
        assertEquals("A", p.currentMediaItem?.mediaId)
    }

    @Test
    fun `undo of a removal after the indices shifted`() {
        val p = queue(abcde, "A")
        val uid = p.uidOf("C")
        val slot = p.slotOfRow(uid)
        val removed = p.getQueueWindows().first { it.uid == uid }
        p.removeMediaItem(removed.firstPeriodIndex) // A B D E
        p.removeMediaItem(1) // B goes too: A D E
        idle()
        p.reinsertInQueue(removed.mediaItem, slot)
        idle()
        // B is gone, so C goes before its old lower neighbour D
        assertEquals(listOf("A", "C", "D", "E"), p.shown())
    }

    @Test
    fun `undo of a removal with shuffle on lands in the same place of the play order`() {
        val p = shuffledQueue(timeline, shuffled, "C")
        val uid = p.uidOf("B") // between F and D in the play order
        val slot = p.slotOfRow(uid)
        val removed = p.getQueueWindows().first { it.uid == uid }
        p.removeMediaItem(removed.firstPeriodIndex)
        idle()
        assertEquals(listOf("A", "E", "C", "F", "D"), p.shown())
        p.reinsertInQueue(removed.mediaItem, slot)
        idle()
        assertEquals(shuffled, p.shown())
        assertEquals("C", p.currentMediaItem?.mediaId)
        assertEquals("F", p.nextId())
    }

    @Test
    fun `undo of the removal of the last song`() {
        val p = queue(abcde, "A")
        val uid = p.uidOf("E")
        val slot = p.slotOfRow(uid)
        val removed = p.getQueueWindows().first { it.uid == uid }
        p.removeMediaItem(removed.firstPeriodIndex)
        idle()
        p.reinsertInQueue(removed.mediaItem, slot)
        idle()
        assertEquals(abcde, p.shown())
    }

    @Test
    fun `undo of the removal of the first song`() {
        val p = queue(abcde, "C")
        val uid = p.uidOf("A")
        val slot = p.slotOfRow(uid)
        val removed = p.getQueueWindows().first { it.uid == uid }
        p.removeMediaItem(removed.firstPeriodIndex)
        idle()
        p.reinsertInQueue(removed.mediaItem, slot)
        idle()
        assertEquals(abcde, p.shown())
        assertEquals("C", p.currentMediaItem?.mediaId)
    }
}
