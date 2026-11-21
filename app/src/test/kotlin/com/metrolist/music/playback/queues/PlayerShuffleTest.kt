/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.extensions.getQueueWindows
import com.metrolist.music.extensions.metadata
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.PersistQueue
import com.metrolist.music.playback.PersistQueueWindow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import kotlin.random.Random

/**
 * Shuffle on a REAL ExoPlayer (its timeline, its shuffle order, its "next" button), with the queues users have: a local
 * playlist of 731 songs. Nothing is prepared, so nothing is loaded; skipping is [ExoPlayer.seekToNextMediaItem], which
 * follows exactly the order that playback follows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class PlayerShuffleTest {
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

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun song(i: Int) = MediaMetadata(id = "id$i", title = "Song $i", artists = listOf(MediaMetadata.Artist(null, "Artist ${i % 40}")), duration = 180)

    private fun items(n: Int): List<MediaItem> = (0 until n).map { song(it).toMediaItem() }

    /** A player with [n] songs, [start] playing, shuffle on with a new pass that starts at [start] (what playQueue does). */
    private fun shuffled(
        n: Int,
        start: Int,
        seed: Int = 1,
    ): ExoPlayer {
        val player = newPlayer()
        player.setMediaItems(items(n), start, 0L)
        player.shuffleModeEnabled = true
        PlayerShuffle.startPass(player, random = Random(seed))
        idle()
        return player
    }

    /** Presses "next" [times] times and returns the songs (timeline indexes) that played, the first one included. */
    private fun skip(
        player: ExoPlayer,
        times: Int,
    ): List<Int> {
        val heard = mutableListOf(player.currentMediaItemIndex)
        repeat(times) {
            player.seekToNextMediaItem()
            idle()
            heard += player.currentMediaItemIndex
        }
        return heard
    }

    private fun ids(player: Player) = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

    @Test
    fun `a shuffled 731-song playlist plays every song once, from the song it started with, top and bottom mixed`() {
        val player = shuffled(731, start = 400)
        val heard = skip(player, 730)
        assertEquals(400, heard.first())
        assertEquals(731, heard.toSet().size)
        assertFalse("the pass ends: nothing after the last song", player.hasNextMediaItem())
        // the bottom of the list is not left for the end: in the first 100 songs about half come from the bottom half
        val bottom = heard.take(100).count { it >= 365 }
        assertTrue("$bottom of the first 100 from the bottom half", bottom in 30..70)
    }

    @Test
    fun `a tapped song plays first while shuffle is on and the rest follows at random`() {
        val player = shuffled(731, start = 123, seed = 9)
        assertEquals(123, player.currentMediaItemIndex)
        assertEquals("nothing above the tapped song", 0, PlayerShuffle.played(player).size)
        val next = skip(player, 5).drop(1)
        assertNotEquals(listOf(124, 125, 126, 127, 128), next)
    }

    @Test
    fun `repeat all starts a new random pass and every song plays once in it too`() {
        val player = shuffled(50, start = 0, seed = 3)
        player.repeatMode = Player.REPEAT_MODE_ALL
        idle()
        val pass1 = skip(player, 49)
        assertEquals(50, pass1.toSet().size)
        // the last song of the pass started: what MusicService.onMediaItemTransition does
        assertTrue(PlayerShuffle.newPassIfLast(player, Random(4)))
        val pass2 = skip(player, 50).drop(1)
        assertEquals("pass 2 has every song once", 50, pass2.toSet().size)
        assertEquals(50, pass2.size)
        assertNotEquals("pass 2 is a new order", pass1, pass2)
        assertNotEquals("pass 2 does not open like pass 1", pass1.first(), pass2.first())
        // not the last song, or repeat off: nothing changes
        val other = shuffled(50, start = 0, seed = 4)
        other.repeatMode = Player.REPEAT_MODE_ALL
        assertFalse(PlayerShuffle.newPassIfLast(other))
        val noRepeat = shuffled(5, start = 0, seed = 4)
        skip(noRepeat, 4)
        assertFalse(PlayerShuffle.newPassIfLast(noRepeat))
    }

    @Test
    fun `adding a song to a shuffled queue never brings back the songs already heard`() {
        val player = shuffled(731, start = 10, seed = 5)
        val heard = skip(player, 300)
        val playedBefore = PlayerShuffle.played(player).toSet()
        assertEquals(heard.dropLast(1).toSet(), playedBefore)
        // "Tambah ke antrean" with shuffle on (what addToQueueNow does now)
        PlayerShuffle.addItems(player, listOf(song(9999).toMediaItem()), PlayerShuffle.Join.END)
        idle()
        assertEquals(playedBefore, PlayerShuffle.played(player).toSet())
        val rest = skip(player, 431).drop(1)
        assertEquals(431, rest.size)
        assertTrue("a song heard before came back", rest.none { it in playedBefore })
        assertEquals("the added song plays after the waiting ones", 731, rest.last())
    }

    @Test
    fun `the old behaviour, measured - why songs kept repeating`() {
        // 1) DefaultShuffleOrder put an added song at a random place of the WHOLE order: often before the playing song,
        //    where this pass never reaches it
        var landedInHeardPart = 0
        repeat(20) { seed ->
            val player = newPlayer()
            player.setMediaItems(items(200), 0, 0L)
            player.shuffleModeEnabled = true
            player.setShuffleOrder(DefaultShuffleOrder(ShuffleOrders.fresh(200, 0, Random(seed)), seed.toLong()))
            idle()
            skip(player, 100)
            player.addMediaItems(listOf(song(900).toMediaItem()))
            idle()
            if (200 in PlayerShuffle.played(player)) landedInHeardPart++
            // 2) so the app reshuffled the whole queue with the playing song first: the songs heard came back
            val heard = PlayerShuffle.played(player).toSet() - 200
            val old = ShuffleOrders.fresh(player.mediaItemCount, player.currentMediaItemIndex, Random(seed))
            assertEquals("all heard songs are in front again", heard, old.drop(1).filter { it in heard }.toSet())
            // the new way keeps them heard
            PlayerShuffle.afterAdd(player, 200 until 201, PlayerShuffle.Join.END)
            idle()
            assertEquals(heard, PlayerShuffle.played(player).toSet())
            player.release()
            players.remove(player)
        }
        assertTrue("$landedInHeardPart of 20 additions landed where the pass never goes", landedInHeardPart >= 4)
    }

    @Test
    fun `the next page of a shuffled YouTube playlist joins the waiting songs, the heard ones stay heard`() {
        val player = shuffled(100, start = 0, seed = 8)
        skip(player, 30)
        val heard = PlayerShuffle.played(player).toSet() + player.currentMediaItemIndex
        val next = player.nextMediaItemIndex
        PlayerShuffle.addItems(player, (100 until 200).map { song(it).toMediaItem() }, PlayerShuffle.Join.MIX, Random(2))
        idle()
        assertEquals("the next song stays next", next, player.nextMediaItemIndex)
        val rest = skip(player, 169).drop(1)
        assertEquals(169, rest.toSet().size)
        assertTrue(rest.none { it in heard })
        // page 2 is mixed in, not left for the end
        assertTrue(rest.take(60).count { it >= 100 } > 20)
    }

    @Test
    fun `play next with shuffle on plays the new song next and keeps the rest`() {
        val player = shuffled(30, start = 2, seed = 6)
        skip(player, 10)
        val heard = PlayerShuffle.played(player).map { player.getMediaItemAt(it).mediaId }
        val waiting = ShuffleOrders.playOrder(player).drop(heard.size + 1).map { player.getMediaItemAt(it).mediaId }
        val at = player.currentMediaItemIndex + 1
        player.addMediaItems(at, listOf(song(500).toMediaItem()))
        PlayerShuffle.afterAdd(player, at until at + 1, PlayerShuffle.Join.NEXT)
        idle()
        assertEquals("id500", player.getMediaItemAt(player.nextMediaItemIndex).mediaId)
        assertEquals(heard, PlayerShuffle.played(player).map { player.getMediaItemAt(it).mediaId })
        // the waiting songs keep their order after the new one
        val afterNew = ShuffleOrders.playOrder(player).drop(heard.size + 2).map { player.getMediaItemAt(it).mediaId }
        assertEquals(waiting, afterNew)
    }

    @Test
    fun `remove played songs removes exactly the rows above the playing one`() {
        val player = shuffled(731, start = 50, seed = 12)
        skip(player, 300)
        val current = player.currentMediaItem!!.mediaId
        val waiting = ShuffleOrders.playOrder(player).drop(301).map { player.getMediaItemAt(it).mediaId }
        val removed = PlayerShuffle.removePlayed(player)
        idle()
        assertEquals(300, removed)
        assertEquals(431, player.mediaItemCount)
        assertEquals(current, player.currentMediaItem!!.mediaId)
        assertEquals(0, PlayerShuffle.played(player).size)
        // the queue the screen shows: the playing song on top, then the waiting songs in the same order
        val shown = player.getQueueWindows().map { it.mediaItem.mediaId }
        assertEquals(listOf(current) + waiting, shown)
        // nothing heard: nothing to remove
        assertEquals(0, PlayerShuffle.removePlayed(player))
    }

    @Test
    fun `remove played songs in a queue in list order`() {
        val player = newPlayer()
        player.setMediaItems(items(10), 6, 0L)
        idle()
        assertEquals(6, PlayerShuffle.removePlayed(player))
        idle()
        assertEquals(listOf("id6", "id7", "id8", "id9"), ids(player))
        assertEquals(0, player.currentMediaItemIndex)
    }

    @Test
    fun `a 731-song shuffled queue comes back whole after a restart, in the same order, heard songs still heard`() {
        val player = shuffled(731, start = 3, seed = 21)
        skip(player, 250)
        val heard = PlayerShuffle.played(player).map { player.getMediaItemAt(it).mediaId }
        val waiting = ShuffleOrders.playOrder(player).drop(251).map { player.getMediaItemAt(it).mediaId }
        val current = player.currentMediaItem!!.mediaId

        // MusicService.snapshotQueue + writeQueueSnapshot
        val all = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        val plan = PersistQueueWindow.plan(all.size, player.currentMediaItemIndex, ShuffleOrders.playOrder(player), keep = { all[it].metadata != null })
        val queue = PersistQueue(title = "Big", items = plan.kept.map { all[it].metadata!! }, mediaItemIndex = plan.currentIndex, position = 0L)
        val cursor =
            PersistQueueCursor(
                signature = QueueSignature.of(queue.title, queue.items.map { it.id }),
                size = queue.items.size,
                mediaItemIndex = queue.mediaItemIndex,
                currentId = queue.items[queue.mediaItemIndex].id,
                position = 61_000L,
                shuffleOrder = plan.order,
            )
        val (queueBack, cursorBack) = roundTrip(queue) to roundTrip(cursor)
        assertEquals("nothing is cut", 731, queueBack.items.size)

        // the restore block + playQueue(restoringQueue = true, restoredShuffleOrder)
        val resolvedOrNull = cursorBack.resolve(queueBack)
        assertNotNull(resolvedOrNull)
        val resolved = resolvedOrNull!!
        val restored = newPlayer()
        restored.setMediaItems(queueBack.items.map { it.toMediaItem() }, resolved.mediaItemIndex, resolved.position)
        restored.shuffleModeEnabled = true
        assertTrue(PlayerShuffle.setPlayOrder(restored, resolved.shuffleOrder!!))
        idle()
        assertEquals(current, restored.currentMediaItem!!.mediaId)
        assertEquals(61_000L, resolved.position)
        assertEquals(heard, PlayerShuffle.played(restored).map { restored.getMediaItemAt(it).mediaId })
        val rest = skip(restored, 480).drop(1).map { restored.getMediaItemAt(it).mediaId }
        assertEquals(waiting, rest)
    }

    @Test
    fun `a cursor of another queue is ignored`() {
        val a = PersistQueue(title = "A", items = (0 until 5).map(::song), mediaItemIndex = 2, position = 0L)
        val b = a.copy(items = (0 until 5).map { song(it + 1) })
        val cursor = PersistQueueCursor(QueueSignature.of("A", a.items.map { it.id }), 5, 2, "id2", 10L, intArrayOf(2, 0, 1, 3, 4))
        assertNotNull(cursor.resolve(a))
        assertEquals(null, cursor.resolve(b))
        assertEquals(null, cursor.resolve(a.copy(title = "B")))
        assertEquals(null, cursor.copy(mediaItemIndex = 3).resolve(a))
        // a broken order is dropped, the song and position are kept
        val broken = cursor.copy(shuffleOrder = intArrayOf(0, 0, 1, 2, 3)).resolve(a)!!
        assertEquals(null, broken.shuffleOrder)
        assertEquals(2, broken.mediaItemIndex)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : java.io.Serializable> roundTrip(value: T): T {
        val bytes = ByteArrayOutputStream().also { out -> ObjectOutputStream(out).use { it.writeObject(value) } }.toByteArray()
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() } as T
    }
}
