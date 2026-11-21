/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.metrolist.music.playback.VolumeDuckGuard.RequestResult
import com.metrolist.music.playback.VolumeDuckGuard.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The duck state machine on its own, with a clock the test moves by hand. */
class VolumeDuckGuardTest {
    private var now = 1_000L
    private val guard = VolumeDuckGuard(clock = { now })

    private fun held() {
        guard.onRequestResult(RequestResult.GRANTED)
    }

    @Test
    fun `starts without focus and without a duck`() {
        assertFalse(guard.holdsFocus)
        assertFalse(guard.ducked)
        assertTrue(guard.needsRequest)
        assertEquals(1f, guard.focusMultiplier, 0f)
    }

    @Test
    fun `a duck lowers the focus multiplier and the gain brings it back to 1`() {
        held()
        guard.onSignal(Signal.DUCK)
        assertTrue(guard.ducked)
        assertEquals(0.2f, guard.focusMultiplier, 0f)

        guard.onSignal(Signal.GAIN)
        assertFalse(guard.ducked)
        assertEquals(1f, guard.focusMultiplier, 0f)
        assertTrue(guard.holdsFocus)
    }

    @Test
    fun `a ducked app still holds the focus so a play or a buffering event does not ask again`() {
        // The root cause of "the song stays quiet": the old code marked the focus as lost on a duck, asked again on the next
        // player event and Android answered "granted" without ever sending the gain callback.
        held()
        guard.onSignal(Signal.DUCK)

        assertTrue(guard.holdsFocus)
        assertFalse(guard.needsRequest)
        assertTrue(guard.ducked)
    }

    @Test
    fun `a granted request lifts a duck because Android sends no gain for a request`() {
        // focus was paused away by a call, a notification ducked us on top of that, the user then presses play
        guard.onSignal(Signal.LOSS_TRANSIENT)
        guard.onSignal(Signal.DUCK)
        assertTrue(guard.ducked)
        assertTrue(guard.needsRequest)

        guard.onRequestResult(RequestResult.GRANTED)

        assertFalse(guard.ducked)
        assertEquals(1f, guard.focusMultiplier, 0f)
        assertTrue(guard.holdsFocus)
    }

    @Test
    fun `a delayed or failed request neither lifts the duck nor claims the focus`() {
        guard.onSignal(Signal.LOSS_TRANSIENT)
        guard.onSignal(Signal.DUCK)

        guard.onRequestResult(RequestResult.DELAYED)
        assertTrue(guard.ducked)
        assertFalse(guard.holdsFocus)

        guard.onRequestResult(RequestResult.FAILED)
        assertTrue(guard.ducked)
        assertFalse(guard.holdsFocus)

        // the gain callback of the delayed request finally comes
        guard.onSignal(Signal.GAIN)
        assertFalse(guard.ducked)
        assertTrue(guard.holdsFocus)
    }

    @Test
    fun `a duck followed by a transient loss ends the duck and the later gain restores everything`() {
        held()
        guard.onSignal(Signal.DUCK)
        guard.onSignal(Signal.LOSS_TRANSIENT) // a call comes in while the notification still ducks us
        assertFalse(guard.ducked)
        assertFalse(guard.holdsFocus)
        assertEquals(1f, guard.focusMultiplier, 0f)

        guard.onSignal(Signal.GAIN)
        assertTrue(guard.holdsFocus)
        assertEquals(1f, guard.focusMultiplier, 0f)
    }

    @Test
    fun `a duck while already paused by a transient loss keeps the focus flag false and the gain clears both`() {
        held()
        guard.onSignal(Signal.LOSS_TRANSIENT)
        guard.onSignal(Signal.DUCK) // the second event the old comment talks about
        assertFalse(guard.holdsFocus)
        assertTrue(guard.ducked)

        guard.onSignal(Signal.GAIN)
        assertTrue(guard.holdsFocus)
        assertFalse(guard.ducked)
    }

    @Test
    fun `a permanent loss clears the duck and the focus`() {
        held()
        guard.onSignal(Signal.DUCK)
        guard.onSignal(Signal.LOSS)
        assertFalse(guard.ducked)
        assertFalse(guard.holdsFocus)
        assertEquals(1f, guard.focusMultiplier, 0f)
    }

    @Test
    fun `focus lost then gained leaves no duck`() {
        held()
        guard.onSignal(Signal.LOSS_TRANSIENT)
        guard.onSignal(Signal.GAIN)
        assertFalse(guard.ducked)
        assertTrue(guard.holdsFocus)
    }

    @Test
    fun `abandoning the focus clears a duck`() {
        held()
        guard.onSignal(Signal.DUCK)
        guard.onAbandoned()
        assertFalse(guard.ducked)
        assertFalse(guard.holdsFocus)
        assertTrue(guard.needsRequest)
    }

    @Test
    fun `a duck whose gain was lost is lifted after the maximum duck time and not before`() {
        held()
        guard.onSignal(Signal.DUCK)

        now += VolumeDuckGuard.MAX_DUCK_MS - 1
        assertFalse(guard.isDuckStale())
        assertFalse(guard.expireStaleDuck())
        assertTrue(guard.ducked)
        assertEquals(1L, guard.msUntilStale())

        now += 1
        assertTrue(guard.isDuckStale())
        assertTrue(guard.expireStaleDuck())
        assertFalse(guard.ducked)
        assertEquals(1f, guard.focusMultiplier, 0f)
        assertEquals(0L, guard.msUntilStale())
        assertTrue("it still holds the focus", guard.holdsFocus)
    }

    @Test
    fun `a new duck event restarts the stale timer`() {
        held()
        guard.onSignal(Signal.DUCK)
        now += 20_000
        guard.onSignal(Signal.DUCK) // a second notification
        now += 20_000
        assertFalse("40 s after the first duck but only 20 s after the last one", guard.isDuckStale())
        now += 10_000
        assertTrue(guard.isDuckStale())
    }

    @Test
    fun `expiring without a duck does nothing`() {
        held()
        assertFalse(guard.expireStaleDuck())
        assertFalse(guard.isDuckStale())
    }

    @Test
    fun `the restore guard leaves a ducked player alone`() {
        held()
        guard.onSignal(Signal.DUCK)
        assertNull(guard.volumeToRestore(actual = 0.16f, expected = 0.8f))
    }

    @Test
    fun `the restore guard raises a player that is quieter than the user volume when no duck is active`() {
        held()
        assertEquals(0.8f, guard.volumeToRestore(actual = 0.16f, expected = 0.8f)!!, 0f)
        assertEquals(1f, guard.volumeToRestore(actual = 0.2f, expected = 1f)!!, 0f)
    }

    @Test
    fun `the restore guard ignores equal, louder and rounding-sized differences`() {
        held()
        assertNull(guard.volumeToRestore(actual = 0.8f, expected = 0.8f))
        assertNull(guard.volumeToRestore(actual = 1f, expected = 0.8f))
        assertNull(guard.volumeToRestore(actual = 0.7999f, expected = 0.8f))
        assertNull(guard.volumeToRestore(actual = 0f, expected = 0f))
    }

    @Test
    fun `effective volume is user x sleep fade x focus, clamped, and silent when muted`() {
        assertEquals(0.8f, VolumeDuckGuard.effectiveVolume(0.8f, false, 1f, 1f), 0f)
        assertEquals(0.16f, VolumeDuckGuard.effectiveVolume(0.8f, false, 1f, 0.2f), 1e-6f)
        assertEquals(0.4f, VolumeDuckGuard.effectiveVolume(0.8f, false, 0.5f, 1f), 1e-6f)
        assertEquals(0.08f, VolumeDuckGuard.effectiveVolume(0.8f, false, 0.5f, 0.2f), 1e-6f)
        assertEquals(0f, VolumeDuckGuard.effectiveVolume(0.8f, true, 1f, 1f), 0f)
        assertEquals(1f, VolumeDuckGuard.effectiveVolume(2f, false, 1f, 1f), 0f)
        assertEquals(0f, VolumeDuckGuard.effectiveVolume(-1f, false, 1f, 1f), 0f)
    }

    @Test
    fun `the user volume is only an input, a duck and its end never change it`() {
        val user = 0.65f
        held()
        val before = VolumeDuckGuard.effectiveVolume(user, false, 1f, guard.focusMultiplier)
        guard.onSignal(Signal.DUCK)
        val ducked = VolumeDuckGuard.effectiveVolume(user, false, 1f, guard.focusMultiplier)
        guard.onSignal(Signal.GAIN)
        val after = VolumeDuckGuard.effectiveVolume(user, false, 1f, guard.focusMultiplier)

        assertEquals(user, before, 0f)
        assertEquals(user * 0.2f, ducked, 1e-6f)
        assertEquals(user, after, 0f)
    }

    @Test
    fun `whatever Android sends in whatever order, a gain or an abandon always ends with full volume`() {
        val random = Random(20261107)
        repeat(500) {
            val g = VolumeDuckGuard(clock = { now })
            repeat(random.nextInt(1, 40)) {
                when (random.nextInt(8)) {
                    0 -> g.onSignal(Signal.GAIN)
                    1 -> g.onSignal(Signal.LOSS)
                    2 -> g.onSignal(Signal.LOSS_TRANSIENT)
                    3, 4 -> g.onSignal(Signal.DUCK)
                    5 -> g.onRequestResult(RequestResult.entries[random.nextInt(3)])
                    6 -> g.onAbandoned()
                    else -> now += random.nextLong(0, 20_000)
                }
                // invariants at every step
                assertEquals(if (g.ducked) g.duckMultiplier else 1f, g.focusMultiplier, 0f)
                if (g.ducked) assertTrue(g.msUntilStale() <= g.maxDuckMs)
            }

            g.onSignal(Signal.GAIN)
            assertEquals(1f, g.focusMultiplier, 0f)
            assertTrue(g.holdsFocus)
            assertFalse(g.ducked)

            g.onSignal(Signal.DUCK)
            g.onAbandoned()
            assertEquals(1f, g.focusMultiplier, 0f)
        }
    }

    @Test
    fun `after any sequence a stale duck is lifted by the watchdog and a granted request lifts it too`() {
        val random = Random(7)
        repeat(300) {
            val g = VolumeDuckGuard(clock = { now })
            repeat(random.nextInt(1, 25)) {
                when (random.nextInt(5)) {
                    0 -> g.onSignal(Signal.GAIN)
                    1 -> g.onSignal(Signal.LOSS_TRANSIENT)
                    2, 3 -> g.onSignal(Signal.DUCK)
                    else -> g.onRequestResult(RequestResult.entries[random.nextInt(3)])
                }
            }
            val ducked = g.ducked
            now += VolumeDuckGuard.MAX_DUCK_MS
            assertEquals(ducked, g.expireStaleDuck())
            assertFalse(g.ducked)

            g.onSignal(Signal.DUCK)
            g.onRequestResult(RequestResult.GRANTED)
            assertFalse(g.ducked)
        }
    }
}
