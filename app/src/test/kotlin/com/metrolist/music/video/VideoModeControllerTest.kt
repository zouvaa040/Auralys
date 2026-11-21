package com.metrolist.music.video

import android.view.TextureView
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrFormatId
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.media3.common.PlaybackException
import com.metrolist.innertubex.extraction.StreamResolveException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * The controller with fakes for the app, the second player and the extraction: when the second player exists, when it
 * plays, what happens on errors. A manual clock stands for SystemClock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VideoModeControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var now = 10_000L

    @After
    fun tearDown() {
        scope.cancel()
        VideoDiagnostics.clear()
    }

    // ------------------------------------------------------------------------------------------------ fakes

    private class FakePlayer(
        val info: VideoStreamInfo,
        val startMs: Long,
        val useStartHint: Boolean,
        val events: VideoPlayerEvents,
    ) : VideoPlayerHandle {
        override var positionMs: Long = startMs
        override var durationMs: Long = 0L
        override var isReady: Boolean = false
        override var isBuffering: Boolean = false
        override var playWhenReady: Boolean = false
        override var speed: Float = 1f
        var released = false
        var attachedSurface: TextureView? = null
        val seeks = ArrayList<Long>()
        var rateChanges = 0

        override fun seekTo(positionMs: Long) {
            seeks += positionMs
            this.positionMs = positionMs
        }

        override fun setSurface(view: TextureView?) {
            attachedSurface = view
        }

        override fun release() {
            released = true
        }
    }

    private inner class FakeHost : VideoModeHost {
        var audio: AudioSnapshot? = AudioSnapshot("song", 30_000, playing = true, buffering = false, speed = 1f)
        var casting = false
        var cap = 360
        val toasts = ArrayList<VideoToast>()
        val persisted = ArrayList<VideoMode>()

        /** What the search finds. */
        var counterpart: CounterpartVideo? = null
        var counterpartCalls = 0
        var counterpartError: Throwable? = null

        /** While set, the search waits for it (a search that is still running). */
        var hold: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        /** An answer the resolver already has. */
        var known: KnownCounterpart? = null

        override fun knownCounterpart(song: MediaMetadata): KnownCounterpart? = known

        /** The data saver on a metered network switches the preparing off. */
        var prefetch = true

        override fun prefetchAllowed(): Boolean = prefetch

        override fun audio(): AudioSnapshot? = audio

        override fun isCasting(): Boolean = casting

        override fun maxHeight(): Int = cap

        override fun toast(kind: VideoToast) {
            toasts += kind
        }

        override fun persistMode(mode: VideoMode) {
            persisted += mode
        }

        override suspend fun counterpartVideo(song: MediaMetadata): CounterpartVideo? {
            counterpartCalls++
            hold?.await()
            counterpartError?.let { throw it }
            return counterpart
        }
    }

    private inner class Rig(
        val host: FakeHost = FakeHost(),
        val memory: VideoModeMemory = VideoModeMemory(now = { now }),
        val session: VideoSession = VideoSession(clock = { now }, random = { 0.5 }),
        /** The controller's scope: the default runs everything at once; a [VirtualTimeDispatcher] scope gives real waits. */
        val controllerScope: CoroutineScope = this@VideoModeControllerTest.scope,
    ) {
        val extractions = ArrayList<Pair<String, Int>>()
        var extractResult: (String) -> Result<VideoPlaybackSource> = { id -> Result.success(source(id)) }
        val repository =
            VideoStreamRepository(
                extractor = { id, cap ->
                    extractions += id to cap
                    extractResult(id)
                },
                decoders = { _, _, _ -> true },
                now = { now },
                scope = CoroutineScope(Dispatchers.Unconfined),
            )
        val players = ArrayList<FakePlayer>()
        val controller =
            VideoModeController(
                host = host,
                scope = controllerScope,
                repository = repository,
                playerFactory = { info, startMs, hint, _, events -> FakePlayer(info, startMs, hint, events).also { players += it } },
                memory = memory,
                session = session,
                now = { now },
                retryDelayMs = 1_000L,
                stallLimitMs = 20_000L,
                random = { 0.5 },
            )
        var expanded = true

        init {
            controller.expanded = { expanded }
        }

        fun inputs(
            song: MediaMetadata? = video("song"),
            enabled: Boolean = true,
            stored: VideoMode = VideoMode.VIDEO,
            auto: Boolean = true,
            guest: Boolean = false,
            started: Boolean = true,
        ) = VideoModeInputs(song, enabled, stored, auto, guest, started)

        val state get() = controller.state.value
        val player get() = players.last()
        val live get() = players.filter { !it.released }
    }

    private fun bootstrap(id: String) =
        SabrBootstrap(
            videoId = id,
            serverAbrStreamingUrl = "https://rr1.googlevideo.com/videoplayback",
            videoPlaybackUstreamerConfig = ByteArray(1),
            clientName = 1,
            clientVersion = "1",
            audioFormat = SabrFormatId(140),
            discardVideoFormat = SabrFormatId(160),
            discardVideoHeight = 144,
            durationMs = 200_000,
            contentLengthBytes = null,
            mimeType = "audio/mp4",
        )

    private fun source(id: String) = VideoPlaybackSource(bootstrap(id), "video/webm", "vp9", 640, 360, 500_000, 200_000, 7_000_000L, 6 * 3600)

    private fun video(
        id: String,
        sec: Int = 200,
    ) = MediaMetadata(id = id, title = "T", artists = listOf(MediaMetadata.Artist(null, "A")), duration = sec, musicVideoType = "MUSIC_VIDEO_TYPE_OMV")

    private fun audioSong(
        id: String,
        sec: Int = 200,
    ) = MediaMetadata(id = id, title = "T", artists = listOf(MediaMetadata.Artist(null, "A")), duration = sec, musicVideoType = "MUSIC_VIDEO_TYPE_ATV")

    // ------------------------------------------------------------------------------------------------ when the player exists

    @Test
    fun `a music video in video mode, sheet open, app on screen - the second player starts at the audio position`() {
        val r = Rig()
        r.controller.update(r.inputs())
        assertEquals(1, r.players.size)
        assertEquals(30_000L, r.player.startMs)
        assertEquals("song", r.player.info.videoId)
        assertEquals(360, r.extractions.single().second)
        assertTrue(r.state.pillVisible)
        assertEquals(VideoMode.VIDEO, r.state.mode)
        assertFalse("the picture shows only after the first frame", r.state.showing)
        r.player.events.onFirstFrame()
        assertTrue(r.state.showing)
    }

    @Test
    fun `audio mode - the pill shows, no second player exists`() {
        val r = Rig()
        r.controller.update(r.inputs(stored = VideoMode.AUDIO))
        assertTrue(r.state.pillVisible)
        assertEquals(VideoMode.AUDIO, r.state.mode)
        assertTrue(r.players.isEmpty())
        assertTrue(r.extractions.isEmpty())
    }

    @Test
    fun `nothing is extracted or created while the sheet is closed, it starts when the sheet opens`() {
        val r = Rig()
        r.expanded = false
        r.controller.update(r.inputs())
        r.controller.tick()
        assertTrue(r.players.isEmpty())
        assertTrue(r.extractions.isEmpty())
        r.expanded = true
        r.controller.tick()
        assertEquals(1, r.players.size)
    }

    @Test
    fun `no second player while the app is not on screen`() {
        val r = Rig()
        r.controller.update(r.inputs(started = false))
        assertTrue(r.players.isEmpty())
        r.controller.update(r.inputs(started = true))
        assertEquals(1, r.players.size)
    }

    @Test
    fun `closing the sheet pauses at once and lets the player go after 3 seconds`() {
        val r = Rig()
        r.controller.update(r.inputs())
        val p = r.player
        p.isReady = true
        p.playWhenReady = true
        r.expanded = false
        r.controller.tick()
        assertFalse("paused at once", p.playWhenReady)
        assertFalse(p.released)
        now += 2_900
        r.controller.tick()
        assertFalse(p.released)
        now += 200
        r.controller.tick()
        assertTrue(p.released)
        assertTrue(r.live.isEmpty())
    }

    @Test
    fun `opening the sheet again within 3 seconds keeps the same player`() {
        val r = Rig()
        r.controller.update(r.inputs())
        val p = r.player
        r.expanded = false
        r.controller.tick()
        now += 1_000
        r.expanded = true
        r.controller.tick()
        now += 5_000
        r.controller.tick()
        assertFalse(p.released)
        assertEquals(1, r.players.size)
    }

    @Test
    fun `the app going to the background is the same as closing the sheet`() {
        val r = Rig()
        r.controller.update(r.inputs())
        val p = r.player
        r.controller.update(r.inputs(started = false))
        assertFalse(p.playWhenReady)
        assertFalse(p.released)
        now += 3_100
        r.controller.tick()
        assertTrue(p.released)
    }

    @Test
    fun `choosing Audio lets the player go at once and stores the choice`() {
        val r = Rig()
        r.controller.update(r.inputs())
        val p = r.player
        r.controller.onUserPick(VideoMode.AUDIO)
        assertTrue(p.released)
        assertEquals(listOf(VideoMode.AUDIO), r.host.persisted)
        assertEquals(VideoMode.AUDIO, r.state.mode)
        assertTrue(r.state.pillVisible)
    }

    @Test
    fun `choosing Video starts the player and stores the choice`() {
        val r = Rig()
        r.controller.update(r.inputs(stored = VideoMode.AUDIO))
        assertTrue(r.players.isEmpty())
        r.controller.onUserPick(VideoMode.VIDEO)
        assertEquals(1, r.players.size)
        assertEquals(listOf(VideoMode.VIDEO), r.host.persisted)
        assertEquals(VideoMode.VIDEO, r.state.mode)
    }

    @Test
    fun `a pick belongs to the song, the next song follows the stored mode again`() {
        val r = Rig()
        r.controller.update(r.inputs(song = video("a"), stored = VideoMode.VIDEO))
        r.controller.onUserPick(VideoMode.AUDIO)
        // the app stores the choice and passes it in
        r.controller.update(r.inputs(song = video("a"), stored = VideoMode.AUDIO))
        r.controller.update(r.inputs(song = video("b"), stored = VideoMode.AUDIO))
        assertEquals(VideoMode.AUDIO, r.state.mode)
        r.controller.update(r.inputs(song = video("b"), stored = VideoMode.VIDEO))
        assertEquals(VideoMode.VIDEO, r.state.mode)
    }

    @Test
    fun `a song change lets the player go at once and the next song gets its own`() {
        val r = Rig()
        r.controller.update(r.inputs(song = video("a")))
        val first = r.player
        r.host.audio = AudioSnapshot("b", 1_000, playing = true, buffering = false, speed = 1f)
        r.controller.update(r.inputs(song = video("b")))
        assertTrue(first.released)
        assertEquals(2, r.players.size)
        assertEquals("b", r.player.info.videoId)
        assertEquals(1_000L, r.player.startMs)
    }

    @Test
    fun `dispose lets the player go`() {
        val r = Rig()
        r.controller.update(r.inputs())
        r.controller.dispose()
        assertTrue(r.player.released)
    }

    // ------------------------------------------------------------------------------------------------ which songs offer video

    @Test
    fun `device songs and episodes never offer video`() {
        val r = Rig()
        r.controller.update(r.inputs(song = video("LOCAL_123")))
        assertFalse(r.state.pillVisible)
        assertTrue(r.players.isEmpty())
        r.controller.update(r.inputs(song = video("ep1").copy(isEpisode = true)))
        assertFalse(r.state.pillVisible)
        assertTrue(r.players.isEmpty())
    }

    @Test
    fun `a Listen Together guest never gets video`() {
        val r = Rig()
        r.controller.update(r.inputs(guest = true))
        assertFalse(r.state.pillVisible)
        assertTrue(r.players.isEmpty())
    }

    @Test
    fun `casting hides the pill and lets the player go at once`() {
        val r = Rig()
        r.controller.update(r.inputs())
        val p = r.player
        r.host.casting = true
        r.controller.tick()
        assertTrue(p.released)
        assertFalse(r.state.pillVisible)
    }

    @Test
    fun `the experiment switch off hides the pill and creates nothing`() {
        val r = Rig()
        r.controller.update(r.inputs(enabled = false))
        assertFalse(r.state.pillVisible)
        assertTrue(r.players.isEmpty())
        // switching it off while a picture runs ends it
        val r2 = Rig()
        r2.controller.update(r2.inputs())
        val p = r2.player
        r2.controller.update(r2.inputs(enabled = false))
        assertTrue(p.released)
    }

    private fun clip(
        id: String = "clipId",
        sec: Int? = 200,
    ) = CounterpartVideo(id, sec)

    @Test
    fun `an audio recording is looked up when the sheet opens, and offers the video of its counterpart`() {
        val r = Rig()
        r.host.counterpart = clip()
        r.expanded = false
        r.controller.update(r.inputs(song = audioSong("sound"), stored = VideoMode.VIDEO))
        assertEquals("no search while the sheet is closed", 0, r.host.counterpartCalls)
        r.expanded = true
        r.controller.tick()
        assertEquals(1, r.host.counterpartCalls)
        assertTrue(r.state.pillVisible)
        assertEquals("clipId", r.player.info.videoId)
        // the audio recording keeps playing: the video player follows the audio of "sound"
        assertEquals(30_000L, r.player.startMs)
    }

    @Test
    fun `an audio recording without a matching video offers nothing and is looked up once`() {
        val r = Rig()
        r.host.counterpart = null
        r.controller.update(r.inputs(song = audioSong("sound")))
        r.controller.tick()
        r.controller.tick()
        assertEquals(1, r.host.counterpartCalls)
        assertFalse(r.state.pillVisible)
        assertTrue(r.players.isEmpty())
    }

    @Test
    fun `a song from the Video chip is its own video even when its type says nothing`() {
        val r = Rig()
        r.memory.requestPlayAsVideo("upload1")
        r.controller.update(r.inputs(song = audioSong("upload1").copy(musicVideoType = null), stored = VideoMode.AUDIO))
        assertTrue(r.state.pillVisible)
        assertEquals(0, r.host.counterpartCalls)
    }

    // ------------------------------------------------------------------------------------------------ the request of the Video chip

    @Test
    fun `a tap on a Video chip result opens that song as video once, whatever the stored mode`() {
        val r = Rig()
        r.memory.requestPlayAsVideo("v1")
        r.controller.update(r.inputs(song = video("v1"), stored = VideoMode.AUDIO))
        assertEquals(VideoMode.VIDEO, r.state.mode)
        assertEquals(1, r.players.size)
        // used up: back to the song and again it follows the stored mode... the pick for this run of the song stays
        r.controller.update(r.inputs(song = video("other"), stored = VideoMode.AUDIO))
        assertEquals(VideoMode.AUDIO, r.state.mode)
        assertFalse(r.memory.peekFresh("v1"))
    }

    @Test
    fun `the setting off ignores the request`() {
        val r = Rig()
        r.memory.requestPlayAsVideo("v1")
        r.controller.update(r.inputs(song = video("v1"), stored = VideoMode.AUDIO, auto = false))
        assertEquals(VideoMode.AUDIO, r.state.mode)
        assertTrue(r.players.isEmpty())
    }

    @Test
    fun `an old request is ignored`() {
        val r = Rig()
        r.memory.requestPlayAsVideo("v1")
        now += 6 * 60 * 1000L
        r.controller.update(r.inputs(song = video("v1"), stored = VideoMode.AUDIO))
        assertEquals(VideoMode.AUDIO, r.state.mode)
    }

    // ------------------------------------------------------------------------------------------------ errors

    @Test
    fun `a player error is retried once with a fresh extraction and no start hint, then the song falls back to audio`() {
        val r = Rig()
        r.controller.update(r.inputs())
        val first = r.player
        assertTrue(first.useStartHint)
        first.events.onError(IOException("decoder"))
        assertTrue(first.released)
        assertEquals(1, r.players.size) // the retry waits one second
        assertTrue(r.host.toasts.isEmpty())
        now += 1_100
        r.controller.tick()
        assertEquals(2, r.players.size)
        assertFalse("the retry starts without the start hint", r.player.useStartHint)
        assertEquals(2, r.extractions.size)
        r.player.events.onError(IOException("again"))
        assertTrue(r.player.released)
        assertEquals(listOf(VideoToast.FAILED), r.host.toasts)
        assertEquals(VideoMode.AUDIO, r.state.mode)
        assertFalse("no pill for the song that failed", r.state.pillVisible)
        assertTrue(r.session.isNoVideo("song"))
        now += 5_000
        r.controller.tick()
        assertEquals(2, r.players.size)
        // and the stored mode is not touched
        assertTrue(r.host.persisted.isEmpty())
    }

    @Test
    fun `a failed extraction counts the same way`() {
        val r = Rig()
        r.extractResult = { Result.failure(IOException("net")) }
        r.controller.update(r.inputs())
        assertTrue(r.players.isEmpty())
        now += 1_100
        r.controller.tick()
        assertEquals(2, r.extractions.size)
        assertTrue(r.players.isEmpty())
        assertEquals(listOf(VideoToast.FAILED), r.host.toasts)
        assertFalse(r.state.pillVisible)
    }

    @Test
    fun `a song with no video hides the pill without a message unless the user asked for video`() {
        val r = Rig()
        r.extractResult = { Result.failure(com.metrolist.music.utils.InnerTubeXPlayer.NoSabrVideoException("none")) }
        r.controller.update(r.inputs(stored = VideoMode.VIDEO))
        assertFalse(r.state.pillVisible)
        assertTrue(r.host.toasts.isEmpty())
        // the user taps Video on another song that turns out to have none
        r.controller.update(r.inputs(song = video("other"), stored = VideoMode.AUDIO))
        assertTrue(r.state.pillVisible)
        r.controller.onUserPick(VideoMode.VIDEO)
        assertFalse(r.state.pillVisible)
        assertEquals(listOf(VideoToast.UNAVAILABLE), r.host.toasts)
    }

    @Test
    fun `three songs in a row that fail switch the pill off for the whole session`() {
        val r = Rig()
        r.extractResult = { Result.failure(IOException("net")) }
        for (i in 1..3) {
            r.controller.update(r.inputs(song = video("s$i")))
            now += 1_100
            r.controller.tick()
        }
        assertTrue(r.session.killed)
        r.controller.update(r.inputs(song = video("s4")))
        assertFalse(r.state.pillVisible)
        assertTrue(r.players.isEmpty())
        assertEquals(3, r.host.toasts.size)
    }

    @Test
    fun `a first frame ends the run of failures`() {
        val r = Rig()
        r.session.recordFailure()
        r.session.recordFailure()
        r.controller.update(r.inputs())
        r.player.events.onFirstFrame()
        assertEquals(0, r.session.consecutiveFailures)
    }

    @Test
    fun `a picture that never gets going while the audio plays is a failure after 20 seconds`() {
        val r = Rig()
        r.controller.update(r.inputs())
        val p = r.player
        r.controller.tick()
        now += 19_000
        r.controller.tick()
        assertFalse(p.released)
        now += 2_000
        r.controller.tick()
        assertTrue(p.released)
    }

    @Test
    fun `a picture that waits while the audio is paused is not a failure`() {
        val r = Rig()
        r.host.audio = r.host.audio!!.copy(playing = false)
        r.controller.update(r.inputs())
        val p = r.player
        now += 60_000
        r.controller.tick()
        assertFalse(p.released)
    }

    // ------------------------------------------------------------------------------------------------ following the audio

    private fun ready(r: Rig): FakePlayer {
        r.controller.update(r.inputs())
        val p = r.player
        p.isReady = true
        p.durationMs = 200_000
        return p
    }

    @Test
    fun `the picture plays while the audio plays, pauses when it pauses or buffers`() {
        val r = Rig()
        val p = ready(r)
        p.positionMs = 30_000
        r.controller.tick()
        assertTrue(p.playWhenReady)
        r.host.audio = r.host.audio!!.copy(playing = false)
        r.controller.tick()
        assertFalse(p.playWhenReady)
        r.host.audio = r.host.audio!!.copy(playing = true, buffering = true)
        r.controller.tick()
        assertFalse(p.playWhenReady)
        r.host.audio = r.host.audio!!.copy(playing = true, buffering = false)
        r.controller.tick()
        assertTrue(p.playWhenReady)
    }

    @Test
    fun `a picture that is too far off is jumped to the audio position, not more than once per 1_5 s`() {
        val r = Rig()
        val p = ready(r)
        p.positionMs = 28_000 // 2 s behind
        r.controller.tick()
        assertEquals(listOf(30_000L), p.seeks)
        // the player did not get there yet (still loading): no second jump within the gap
        p.positionMs = 28_000
        now += 1_000
        r.controller.tick()
        assertEquals(1, p.seeks.size)
        now += 600
        r.controller.tick()
        assertEquals(2, p.seeks.size)
    }

    @Test
    fun `an audio seek is followed at once`() {
        val r = Rig()
        val p = ready(r)
        p.positionMs = 30_000
        r.controller.tick()
        assertTrue(p.seeks.isEmpty())
        // the user seeks the audio to 90 s
        r.host.audio = r.host.audio!!.copy(positionMs = 90_000)
        r.controller.onAudioEvent(discontinuity = true)
        assertEquals(listOf(90_000L), p.seeks)
        // and again right after: the throttle does not hold back a second seek event
        r.host.audio = r.host.audio!!.copy(positionMs = 20_000)
        r.controller.onAudioEvent(discontinuity = true)
        assertEquals(listOf(90_000L, 20_000L), p.seeks)
    }

    @Test
    fun `a small error changes the speed of the picture, the audio speed is copied`() {
        val r = Rig()
        val p = ready(r)
        r.host.audio = r.host.audio!!.copy(speed = 1.5f)
        p.positionMs = 30_000
        r.controller.tick()
        assertEquals(1.5f, p.speed, 0.0001f)
        p.positionMs = 29_850 // 150 ms behind: sped up
        r.controller.tick()
        assertTrue(p.speed > 1.5f)
        assertTrue(p.seeks.isEmpty())
        p.positionMs = 30_000
        r.controller.tick()
        assertEquals(1.5f, p.speed, 0.0001f)
    }

    @Test
    fun `the first look after the picture is ready jumps to the audio at once`() {
        val r = Rig()
        r.controller.update(r.inputs())
        val p = r.player
        r.controller.tick() // not ready: no jump, only the play flag
        assertTrue(p.seeks.isEmpty())
        r.host.audio = r.host.audio!!.copy(positionMs = 33_000)
        p.positionMs = 30_000
        p.isReady = true
        r.controller.tick()
        assertEquals(listOf(33_000L), p.seeks)
    }

    @Test
    fun `the clock for SABR follows the player position and a jump sets it before the seek`() {
        val r = Rig()
        val p = ready(r)
        p.positionMs = 30_080
        r.controller.tick()
        assertEquals(30_080L, r.controller.clock.positionMs)
        r.host.audio = r.host.audio!!.copy(positionMs = 90_000)
        r.controller.onAudioEvent(true)
        assertEquals(90_000L, r.controller.clock.positionMs)
    }

    @Test
    fun `nothing is followed while the audio player is on another song`() {
        val r = Rig()
        val p = ready(r)
        r.host.audio = AudioSnapshot("someone else", 5_000, playing = true, buffering = false, speed = 1f)
        p.positionMs = 30_000
        r.controller.tick()
        assertTrue(p.seeks.isEmpty())
    }

    @Test
    fun `the surface is handed to the player, also when it was bound first`() {
        val r = Rig()
        val view = TextureView(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        r.controller.bindSurface(view)
        r.controller.update(r.inputs())
        assertNotNull(r.player.attachedSurface)
        r.controller.bindSurface(null)
        assertNull(r.player.attachedSurface)
    }

    @Test
    fun `the aspect of the picture is published`() {
        val r = Rig()
        r.controller.update(r.inputs())
        r.player.events.onVideoAspect(1.0f)
        assertEquals(1.0f, r.state.aspect, 0.0001f)
    }

    @Test
    fun `busy while a player, a start or a retry wait is there, idle otherwise`() {
        val r = Rig()
        assertFalse(r.controller.busy)
        r.controller.update(r.inputs())
        assertTrue(r.controller.busy)
        r.controller.onUserPick(VideoMode.AUDIO)
        assertFalse(r.controller.busy)
    }

    // ------------------------------------------------------------------------------------------------ REVIEW-2026-11-07 F1: the surface

    private fun textureView() = TextureView(androidx.test.core.app.ApplicationProvider.getApplicationContext())

    @Test
    fun `a late release of the old view does not take the new surface away`() {
        val r = Rig()
        val old = textureView()
        val new = textureView()
        r.controller.update(r.inputs())
        r.controller.bindSurface(old)
        // the song changed: the new carousel item binds its view BEFORE the old item releases its own
        r.controller.bindSurface(new)
        r.controller.unbindSurface(old)
        assertTrue("the new view is still the surface", r.player.attachedSurface === new)
        // the release of the view that IS bound unbinds
        r.controller.unbindSurface(new)
        assertNull(r.player.attachedSurface)
    }

    @Test
    fun `a release of a view that was never bound changes nothing`() {
        val r = Rig()
        val bound = textureView()
        r.controller.update(r.inputs())
        r.controller.bindSurface(bound)
        r.controller.unbindSurface(textureView())
        assertTrue(r.player.attachedSurface === bound)
    }

    @Test
    fun `a player that starts after the old view was released gets the surface of the new view`() {
        val r = Rig()
        val old = textureView()
        val new = textureView()
        r.controller.bindSurface(old)
        r.controller.bindSurface(new)
        r.controller.unbindSurface(old)
        r.controller.update(r.inputs())
        assertTrue(r.player.attachedSurface === new)
    }

    // ------------------------------------------------------------------------------------------------ REVIEW-2026-11-07 F2: the lifecycle

    private fun timedRig(session: VideoSession = VideoSession()): Pair<Rig, VirtualTimeDispatcher> {
        val vt = VirtualTimeDispatcher()
        val r = Rig(session = session, controllerScope = CoroutineScope(SupervisorJob() + vt))
        return r to vt
    }

    private fun advance(
        vt: VirtualTimeDispatcher,
        ms: Long,
    ) {
        now += ms
        vt.advanceBy(ms)
    }

    @Test
    fun `leaving the app pauses at once and lets the player go after 3 seconds with no other call`() {
        val (r, vt) = timedRig()
        r.controller.update(r.inputs())
        vt.runCurrent()
        val p = r.player
        p.isReady = true
        p.playWhenReady = true
        // the lifecycle STOP: no recomposition, no tick loop (both stop in the background)
        r.controller.onAppStarted(false)
        assertFalse("paused at once", p.playWhenReady)
        assertFalse(p.released)
        advance(vt, 2_900)
        assertFalse(p.released)
        advance(vt, 200)
        assertTrue("let go by the timer alone", p.released)
        assertTrue(r.live.isEmpty())
    }

    @Test
    fun `coming back within 3 seconds keeps the same player`() {
        val (r, vt) = timedRig()
        r.controller.update(r.inputs())
        vt.runCurrent()
        val p = r.player
        r.controller.onAppStarted(false)
        advance(vt, 1_000)
        r.controller.onAppStarted(true)
        advance(vt, 10_000)
        assertFalse("the timer was cancelled", p.released)
        assertEquals(1, r.players.size)
    }

    @Test
    fun `coming back after the player was let go starts a new one`() {
        val (r, vt) = timedRig()
        r.controller.update(r.inputs())
        vt.runCurrent()
        r.controller.onAppStarted(false)
        advance(vt, 3_100)
        assertTrue(r.player.released)
        r.controller.onAppStarted(true)
        vt.runCurrent()
        assertEquals(2, r.players.size)
        assertFalse(r.player.released)
    }

    @Test
    fun `a song change in the background lets the old player go and starts nothing until the app is back`() {
        val (r, vt) = timedRig()
        r.controller.update(r.inputs(song = video("a")))
        vt.runCurrent()
        val first = r.player
        r.controller.onAppStarted(false)
        r.host.audio = AudioSnapshot("b", 0, playing = true, buffering = false, speed = 1f)
        // the player's own flow reports the new song; no recomposition happens in the background
        r.controller.setSong(video("b"))
        vt.runCurrent()
        assertTrue("the old picture is let go with the old song", first.released)
        assertEquals(1, r.players.size)
        r.controller.onAppStarted(true)
        vt.runCurrent()
        assertEquals(2, r.players.size)
        assertEquals("b", r.player.info.videoId)
    }

    @Test
    fun `the song flow is followed`() {
        val r = Rig()
        val songs = kotlinx.coroutines.flow.MutableStateFlow<MediaMetadata?>(video("a"))
        r.controller.update(r.inputs(song = null))
        val job = scope.launch { r.controller.followSongs(songs) }
        assertEquals("a", r.player.info.videoId)
        r.host.audio = AudioSnapshot("b", 0, playing = true, buffering = false, speed = 1f)
        songs.value = video("b")
        assertEquals("b", r.player.info.videoId)
        job.cancel()
    }

    @Test
    fun `the lifecycle observer hands STOP and START to the controller`() {
        val r = Rig()
        r.controller.update(r.inputs())
        val p = r.player
        p.playWhenReady = true
        val owner =
            object : androidx.lifecycle.LifecycleOwner {
                val registry = androidx.lifecycle.LifecycleRegistry(this)
                override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
            }
        owner.registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
        owner.lifecycle.addObserver(videoLifecycleObserver(r.controller))
        assertTrue("still running while the app is on screen", p.playWhenReady)
        owner.registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP)
        assertFalse("STOP pauses the picture", p.playWhenReady)
        now += 3_100
        r.controller.tick()
        assertTrue("and the 3 s rule lets the player go", p.released)
        owner.registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
        assertEquals("START starts it again", 2, r.players.size)
    }

    // ------------------------------------------------------------------------------------------------ the pill is there at once

    @Test
    fun `an audio recording shows the pill the moment the sheet is open, without any menu, on a default setup`() {
        // the case of the owner: a catalogue song (4:23) whose music video is 4:41, default settings, Wi-Fi
        val r = Rig()
        r.host.counterpart = clip("clipId", sec = 281)
        r.host.hold = kotlinx.coroutines.CompletableDeferred()
        r.controller.update(r.inputs(song = audioSong("kepada", sec = 263), stored = VideoMode.AUDIO))
        // the search runs right away: the pill is there, the puck on Audio, the Video half spins
        assertTrue("pill at once", r.state.pillVisible)
        assertEquals(VideoMode.AUDIO, r.state.mode)
        assertTrue("spinner while searching", r.state.loading)
        assertEquals(1, r.host.counterpartCalls)
        r.host.hold!!.complete(Unit)
        // found: the pill stays, the spinner stops, nothing plays yet (Audio mode), the playing item was never touched
        assertTrue(r.state.pillVisible)
        assertFalse(r.state.loading)
        assertEquals(VideoMode.AUDIO, r.state.mode)
        assertTrue(r.players.isEmpty())
        assertTrue(r.extractions.isEmpty())
        // a tap on Video plays that video silently over the same audio, 18 s ahead (the intro)
        r.controller.onUserPick(VideoMode.VIDEO)
        assertEquals("clipId", r.player.info.videoId)
        assertEquals(30_000L + 18_000L, r.player.startMs)
        assertEquals(listOf(VideoMode.VIDEO), r.host.persisted)
    }

    @Test
    fun `the search starts without a wait and is not held back by a small cap on a metered network or the data saver`() {
        val r = Rig()
        r.host.cap = 360 // metered, data saver or low RAM: only the sharpness is capped
        r.host.counterpart = clip()
        r.controller.update(r.inputs(song = audioSong("sound"), stored = VideoMode.VIDEO))
        assertEquals(1, r.host.counterpartCalls)
        assertEquals(360, r.extractions.single().second)
        assertEquals("clipId", r.player.info.videoId)
    }

    @Test
    fun `the Video half spins while a wanted video is prepared and stops with the first frame`() {
        val r = Rig()
        r.controller.update(r.inputs(song = video("song"), stored = VideoMode.VIDEO))
        assertTrue(r.state.loading)
        r.player.events.onFirstFrame()
        assertFalse(r.state.loading)
        assertTrue(r.state.showing)
    }

    @Test
    fun `a song with its own video offers the pill at once, Audio mode just shows its cover`() {
        val r = Rig()
        r.controller.update(r.inputs(song = video("song"), stored = VideoMode.AUDIO))
        assertTrue(r.state.pillVisible)
        assertEquals(VideoMode.AUDIO, r.state.mode)
        assertFalse(r.state.loading)
        assertTrue(r.players.isEmpty())
        assertEquals(0, r.host.counterpartCalls)
    }

    @Test
    fun `when no video exists the pill goes`() {
        val r = Rig()
        r.host.hold = kotlinx.coroutines.CompletableDeferred()
        r.controller.update(r.inputs(song = audioSong("sound"), stored = VideoMode.AUDIO))
        assertTrue(r.state.pillVisible)
        r.host.counterpart = null
        r.host.hold!!.complete(Unit)
        assertFalse(r.state.pillVisible)
        assertFalse(r.state.loading)
    }

    @Test
    fun `a failed search takes the pill away for this song`() {
        val r = Rig()
        r.host.counterpartError = java.io.IOException("offline")
        r.controller.update(r.inputs(song = audioSong("sound"), stored = VideoMode.AUDIO))
        assertFalse(r.state.pillVisible)
        assertEquals(1, r.host.counterpartCalls)
    }

    @Test
    fun `a video of a very different length is no video, and says so when the user asked for it`() {
        val r = Rig()
        r.host.counterpart = clip(sec = 400) // the song is 200 s
        r.controller.update(r.inputs(song = audioSong("sound", sec = 200), stored = VideoMode.AUDIO))
        assertFalse(r.state.pillVisible)
        assertTrue(r.host.toasts.isEmpty())

        val r2 = Rig()
        r2.host.counterpart = clip(sec = 400)
        r2.host.hold = kotlinx.coroutines.CompletableDeferred()
        r2.controller.update(r2.inputs(song = audioSong("sound", sec = 200), stored = VideoMode.AUDIO))
        r2.controller.onUserPick(VideoMode.VIDEO) // while the search runs
        r2.host.hold!!.complete(Unit)
        assertFalse(r2.state.pillVisible)
        assertEquals(listOf(VideoToast.UNAVAILABLE), r2.host.toasts)
    }

    @Test
    fun `a tap on Video while the search runs plays the video when it is found`() {
        val r = Rig()
        r.host.counterpart = clip()
        r.host.hold = kotlinx.coroutines.CompletableDeferred()
        r.controller.update(r.inputs(song = audioSong("sound"), stored = VideoMode.AUDIO))
        r.controller.onUserPick(VideoMode.VIDEO)
        assertTrue(r.players.isEmpty())
        assertTrue("still searching", r.state.loading)
        r.host.hold!!.complete(Unit)
        assertEquals("clipId", r.player.info.videoId)
    }

    @Test
    fun `the answer of a search for a song that is no longer playing is ignored`() {
        val r = Rig()
        r.host.counterpart = clip("clipA")
        r.host.hold = kotlinx.coroutines.CompletableDeferred()
        r.controller.update(r.inputs(song = audioSong("a"), stored = VideoMode.VIDEO))
        r.host.hold = null
        r.host.counterpart = null
        r.controller.update(r.inputs(song = audioSong("b"), stored = VideoMode.VIDEO))
        assertFalse("b has no video", r.state.pillVisible)
        assertTrue(r.players.isEmpty())
    }

    @Test
    fun `the stored mode Video starts the next song with a video in video mode by itself`() {
        val r = Rig()
        r.host.counterpart = clip("clipB")
        r.controller.update(r.inputs(song = video("a"), stored = VideoMode.VIDEO))
        assertEquals("a", r.player.info.videoId)
        r.host.audio = AudioSnapshot("b", 0, playing = true, buffering = false, speed = 1f)
        r.controller.update(r.inputs(song = audioSong("b"), stored = VideoMode.VIDEO))
        assertEquals("clipB", r.player.info.videoId)
        assertEquals(2, r.players.size)
        assertTrue(r.players.first().released)
    }

    @Test
    fun `with Mode video off there is never a search and no pill`() {
        val r = Rig()
        r.controller.update(r.inputs(song = audioSong("sound"), enabled = false))
        r.controller.tick()
        assertEquals(0, r.host.counterpartCalls)
        assertFalse(r.state.pillVisible)
    }

    @Test
    fun `an answer that is already known costs no search, also while the sheet is closed`() {
        val r = Rig()
        r.expanded = false
        r.host.known = KnownCounterpart(clip("clipId"))
        r.controller.update(r.inputs(song = audioSong("sound")))
        assertEquals(0, r.host.counterpartCalls)
        assertTrue(r.state.pillVisible)
        assertTrue("nothing plays while the sheet is closed", r.players.isEmpty())
        r.expanded = true
        r.controller.tick()
        assertEquals("clipId", r.player.info.videoId)
        assertEquals(0, r.host.counterpartCalls)
    }

    @Test
    fun `a known none offers nothing and searches nothing`() {
        val r = Rig()
        r.host.known = KnownCounterpart(null)
        r.controller.update(r.inputs(song = audioSong("sound")))
        r.controller.tick()
        assertEquals(0, r.host.counterpartCalls)
        assertFalse(r.state.pillVisible)
    }

    @Test
    fun `a video with an intro follows the audio at its offset`() {
        val r = Rig()
        r.host.audio = AudioSnapshot("sound", 30_000, playing = true, buffering = false, speed = 1f)
        r.host.counterpart = clip(sec = 218) // 18 s longer than the 200 s song
        r.controller.update(r.inputs(song = audioSong("sound", sec = 200), stored = VideoMode.VIDEO))
        val p = r.player
        assertEquals(30_000L + 18_000L, p.startMs)
        p.isReady = true
        p.durationMs = 218_000
        // in step: the video is at the audio position plus the offset, nothing to correct
        p.positionMs = 48_050
        r.controller.tick()
        assertTrue(p.seeks.isEmpty())
        // the user seeks the audio to 100 s: the picture goes to 118 s
        r.host.audio = r.host.audio!!.copy(positionMs = 100_000)
        r.controller.onAudioEvent(discontinuity = true)
        assertEquals(listOf(118_000L), p.seeks)
        // a video of about the same length has no offset
        val r2 = Rig()
        r2.host.counterpart = clip(sec = 205)
        r2.controller.update(r2.inputs(song = audioSong("sound", sec = 200), stored = VideoMode.VIDEO))
        assertEquals(30_000L, r2.player.startMs)
    }

    // ------------------------------------------------------------------------------------------------ REVIEW-2026-11-07 F13: the sound goes first

    @Test
    fun `no video extraction while the audio player is still loading the song`() {
        val r = Rig()
        r.host.audio = AudioSnapshot("song", 0, playing = false, buffering = true, speed = 1f)
        r.controller.update(r.inputs())
        r.controller.tick()
        assertTrue("nothing asked for yet", r.extractions.isEmpty())
        assertTrue(r.players.isEmpty())
        // the audio is ready: its state event ticks at once
        r.host.audio = AudioSnapshot("song", 0, playing = true, buffering = false, speed = 1f)
        r.controller.onAudioEvent(false)
        assertEquals(1, r.extractions.size)
        assertEquals(1, r.players.size)
    }

    // ------------------------------------------------------------------------------------------------ video stability: failures are told apart

    private fun failWith(
        r: Rig,
        error: Throwable,
    ) {
        r.extractResult = { Result.failure(error) }
    }

    @Test
    fun `a network failure waits 1 then 3 then 8 seconds and tries again by itself, with no message and no punishment`() {
        val r = Rig()
        failWith(r, java.net.UnknownHostException("offline"))
        r.controller.update(r.inputs())
        assertEquals(1, r.extractions.size)
        assertTrue(r.host.toasts.isEmpty())
        assertTrue("the pill stays", r.state.pillVisible)
        // random = 0.5 gives the exact steps
        for ((i, wait) in listOf(1_000L, 3_000L, 8_000L).withIndex()) {
            now += wait - 100
            r.controller.tick()
            assertEquals("still waiting before attempt ${i + 2}", i + 1, r.extractions.size)
            now += 200
            r.controller.tick()
            assertEquals(i + 2, r.extractions.size)
        }
        assertTrue(r.host.toasts.isEmpty())
        assertEquals(0, r.session.consecutiveFailures)
        assertFalse(r.session.isNoVideo("song"))
        assertEquals(VideoStatus.Failed(VideoFailureKind.OFFLINE, retrying = true), VideoDiagnostics.statusFor("song"))
    }

    @Test
    fun `when the network comes back the video starts without anyone asking`() {
        val r = Rig()
        failWith(r, java.net.UnknownHostException("offline"))
        r.controller.update(r.inputs())
        r.extractResult = { id -> Result.success(source(id)) }
        now += 1_100
        r.controller.tick()
        assertEquals(1, r.players.size)
        assertFalse("the fresh extraction replaced the failed start", r.player.useStartHint)
        r.player.events.onFirstFrame()
        assertEquals(VideoStatus.Available, VideoDiagnostics.statusFor("song"))
    }

    @Test
    fun `six network failures leave the song alone for a while but never count against the whole mode`() {
        val r = Rig()
        failWith(r, java.net.SocketTimeoutException("slow"))
        r.controller.update(r.inputs())
        repeat(5) {
            now += 100_000
            r.controller.tick()
        }
        assertEquals(6, r.extractions.size)
        assertEquals(listOf(VideoToast.FAILED), r.host.toasts)
        assertTrue(r.session.isNoVideo("song"))
        assertEquals("the network is not a failure of the video path", 0, r.session.consecutiveFailures)
        assertFalse(r.session.killed)
        assertFalse(r.state.pillVisible)
        assertEquals(VideoStatus.Failed(VideoFailureKind.TIMEOUT, retrying = false), VideoDiagnostics.statusFor("song"))
    }

    @Test
    fun `three real failures in a row rest the whole mode and it comes back by itself`() {
        val r = Rig()
        failWith(r, IOException("net")) // nothing recognisable: a real failure of the path
        for (i in 1..3) {
            r.controller.update(r.inputs(song = video("s$i")))
            now += 1_100
            r.controller.tick()
        }
        assertTrue(r.session.killed)
        assertEquals(1, r.session.strikes)
        r.controller.update(r.inputs(song = video("s4")))
        assertFalse(r.state.pillVisible)
        assertTrue(r.session.cooldownRemainingMs() in 60_000L..90_000L)
        // 75 s with random = 0.5; a little later the mode is back
        now += 91_000
        assertFalse(r.session.killed)
        r.extractResult = { id -> Result.success(source(id)) }
        r.controller.update(r.inputs(song = video("s5")))
        assertTrue(r.state.pillVisible)
        assertEquals(1, r.players.size)
        r.player.events.onFirstFrame()
        assertEquals("a first frame resets the length of the next rest", 0, r.session.strikes)
    }

    @Test
    fun `a song that failed for real rests for minutes and then gets its pill back`() {
        val r = Rig()
        failWith(r, IOException("net"))
        r.controller.update(r.inputs())
        now += 1_100
        r.controller.tick()
        assertTrue(r.session.isNoVideo("song"))
        assertFalse(r.state.pillVisible)
        assertTrue(r.session.songRestRemainingMs("song") in 119_000L..181_000L)
        now += 190_000
        r.controller.tick()
        assertFalse(r.session.isNoVideo("song"))
        assertTrue("the pill is back", r.state.pillVisible)
    }

    @Test
    fun `a picture that cannot start is a network class failure and is not counted`() {
        val r = Rig()
        r.controller.update(r.inputs())
        val p = r.player
        r.controller.tick()
        now += 21_000
        r.controller.tick()
        assertTrue(p.released)
        assertEquals(0, r.session.consecutiveFailures)
        assertTrue(r.host.toasts.isEmpty())
        assertEquals(VideoStatus.Failed(VideoFailureKind.STALL, retrying = true), VideoDiagnostics.statusFor("song"))
    }

    @Test
    fun `an ExoPlayer network error waits and retries, a decoder error is a real failure`() {
        val r = Rig()
        r.controller.update(r.inputs())
        r.player.events.onError(PlaybackException("net", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals(VideoStatus.Failed(VideoFailureKind.OFFLINE, retrying = true), VideoDiagnostics.statusFor("song"))
        assertEquals(0, r.session.consecutiveFailures)
        assertTrue(r.host.toasts.isEmpty())

        val r2 = Rig()
        r2.controller.update(r2.inputs())
        r2.player.events.onError(PlaybackException("d", null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        now += 1_100
        r2.controller.tick()
        r2.player.events.onError(PlaybackException("d", null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertEquals(1, r2.session.consecutiveFailures)
        assertEquals(VideoStatus.Failed(VideoFailureKind.DECODER, retrying = false), VideoDiagnostics.statusFor("song"))
        assertEquals(listOf(VideoToast.FAILED), r2.host.toasts)
    }

    @Test
    fun `a network that gives one frame and dies again keeps growing its waits`() {
        val r = Rig()
        r.controller.update(r.inputs())
        r.player.events.onFirstFrame()
        r.player.events.onError(PlaybackException("net", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        now += 1_100 // the first wait is 1 s
        r.controller.tick()
        assertEquals(2, r.players.size)
        r.player.events.onFirstFrame()
        r.player.events.onError(PlaybackException("net", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        now += 1_100
        r.controller.tick()
        assertEquals("the second wait is 3 s, one frame did not reset it", 2, r.players.size)
        now += 2_000
        r.controller.tick()
        assertEquals(3, r.players.size)
    }

    @Test
    fun `a picture that held for half a minute ends the trouble of its song`() {
        val r = Rig()
        r.controller.update(r.inputs())
        r.player.events.onError(PlaybackException("net", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        now += 1_100
        r.controller.tick()
        val p = r.player
        p.isReady = true
        p.durationMs = 200_000
        p.events.onFirstFrame()
        repeat(7) {
            now += 5_000
            p.positionMs = r.host.audio!!.positionMs
            r.controller.tick()
        }
        // a new network failure starts again from the first step (1 s)
        p.events.onError(PlaybackException("net", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        now += 1_100
        r.controller.tick()
        assertEquals(3, r.players.size)
    }

    // ------------------------------------------------------------------------------------------------ video stability: the search for the video

    @Test
    fun `a search that could not be answered is tried again and never says no video`() {
        val r = Rig()
        r.host.counterpartError = CounterpartLookupFailed(VideoFailureKind.TIMEOUT)
        r.controller.update(r.inputs(song = audioSong("sound"), stored = VideoMode.AUDIO))
        assertEquals(1, r.host.counterpartCalls)
        assertTrue("the pill stays, the song is not 'no video'", r.state.pillVisible)
        assertEquals(VideoStatus.Failed(VideoFailureKind.TIMEOUT, retrying = true), VideoDiagnostics.statusFor("sound"))
        // the wait before the next search is 3 s (random = 0.5)
        now += 2_900
        r.controller.tick()
        assertEquals(1, r.host.counterpartCalls)
        now += 200
        r.host.counterpartError = null
        r.host.counterpart = clip("clipId", sec = 205)
        r.controller.tick()
        assertEquals(2, r.host.counterpartCalls)
        assertTrue(r.state.pillVisible)
        r.controller.onUserPick(VideoMode.VIDEO)
        assertEquals("clipId", r.player.info.videoId)
    }

    @Test
    fun `four searches in a row that cannot be answered leave the song alone for two minutes`() {
        val r = Rig()
        r.host.counterpartError = CounterpartLookupFailed(VideoFailureKind.OFFLINE)
        r.controller.update(r.inputs(song = audioSong("sound"), stored = VideoMode.AUDIO))
        repeat(3) {
            now += 30_000
            r.controller.tick()
        }
        assertEquals(4, r.host.counterpartCalls)
        assertTrue(r.session.isNoVideo("sound"))
        assertFalse(r.state.pillVisible)
        assertEquals(VideoStatus.Failed(VideoFailureKind.OFFLINE, retrying = false), VideoDiagnostics.statusFor("sound"))
        assertEquals(0, r.session.consecutiveFailures)
        now += 130_000
        r.controller.tick()
        assertEquals("after the rest it asks again", 5, r.host.counterpartCalls)
    }

    @Test
    fun `YouTube's own link with a measured offset is used as measured, whatever the lengths`() {
        val r = Rig()
        r.host.counterpart = CounterpartVideo("clipId", durationSec = 400, offsetMs = 12_000L, official = true)
        r.controller.update(r.inputs(song = audioSong("sound", sec = 200), stored = VideoMode.VIDEO))
        assertEquals(30_000L + 12_000L, r.player.startMs)
        // and the sync follows that offset
        val p = r.player
        p.isReady = true
        p.durationMs = 400_000
        p.positionMs = 42_050
        r.controller.tick()
        assertTrue(p.seeks.isEmpty())
    }

    @Test
    fun `an official link without a measured offset goes through the length rule, without a length it is trusted`() {
        val a = Rig()
        a.host.counterpart = CounterpartVideo("clipId", durationSec = 400, offsetMs = null, official = true)
        a.controller.update(a.inputs(song = audioSong("sound", sec = 200), stored = VideoMode.AUDIO))
        assertFalse("400 s against 200 s with nothing measured is no video", a.state.pillVisible)

        val b = Rig()
        b.host.counterpart = CounterpartVideo("clipId", durationSec = null, offsetMs = null, official = true)
        b.controller.update(b.inputs(song = audioSong("sound", sec = 200), stored = VideoMode.VIDEO))
        assertEquals(30_000L, b.player.startMs)

        val c = Rig()
        c.host.counterpart = CounterpartVideo("clipId", durationSec = 218, offsetMs = null, official = true)
        c.controller.update(c.inputs(song = audioSong("sound", sec = 200), stored = VideoMode.VIDEO))
        assertEquals(30_000L + 18_000L, c.player.startMs)
    }

    // ------------------------------------------------------------------------------------------------ video stability: the stream is prepared ahead

    @Test
    fun `in Video mode with the sheet closed the stream is prepared once the song has played a moment, and the tap is then instant`() {
        val r = Rig()
        r.expanded = false
        r.controller.update(r.inputs())
        assertTrue(r.extractions.isEmpty())
        now += 2_000
        r.controller.tick()
        assertTrue("not before the song has played 2.5 s", r.extractions.isEmpty())
        now += 600
        r.controller.tick()
        assertEquals(1, r.extractions.size)
        assertTrue("only the stream is prepared, no second player", r.players.isEmpty())
        r.controller.tick()
        assertEquals("prepared once", 1, r.extractions.size)
        // the sheet opens: the player starts from the stored answer
        r.expanded = true
        r.controller.tick()
        assertEquals(1, r.players.size)
        assertEquals("no second extraction for the start", 1, r.extractions.size)
    }

    @Test
    fun `with the sheet open the stream is prepared before the user taps Video, with the sheet closed in Audio mode it is not`() {
        val open = Rig()
        open.controller.update(open.inputs(stored = VideoMode.AUDIO))
        now += 3_000
        open.controller.tick()
        assertEquals(1, open.extractions.size)
        assertTrue(open.players.isEmpty())
        open.controller.onUserPick(VideoMode.VIDEO)
        assertEquals(1, open.players.size)
        assertEquals(1, open.extractions.size)

        val closed = Rig()
        closed.expanded = false
        closed.controller.update(closed.inputs(stored = VideoMode.AUDIO))
        now += 10_000
        closed.controller.tick()
        assertTrue(closed.extractions.isEmpty())
    }

    @Test
    fun `nothing is prepared while the audio is paused or loading, in the background, or when the data saver says no`() {
        val paused = Rig()
        paused.host.audio = paused.host.audio!!.copy(playing = false)
        paused.expanded = false
        paused.controller.update(paused.inputs())
        now += 5_000
        paused.controller.tick()
        assertTrue(paused.extractions.isEmpty())

        val loading = Rig()
        loading.host.audio = loading.host.audio!!.copy(buffering = true)
        loading.expanded = false
        loading.controller.update(loading.inputs())
        now += 5_000
        loading.controller.tick()
        assertTrue(loading.extractions.isEmpty())

        val background = Rig()
        background.expanded = false
        background.controller.update(background.inputs(started = false))
        now += 5_000
        background.controller.tick()
        assertTrue(background.extractions.isEmpty())

        val saver = Rig()
        saver.host.prefetch = false
        saver.expanded = false
        saver.controller.update(saver.inputs())
        now += 5_000
        saver.controller.tick()
        assertTrue(saver.extractions.isEmpty())
        // the tap itself is never held back by the saver (only the picture size is capped)
        saver.expanded = true
        saver.controller.tick()
        assertEquals(1, saver.players.size)
    }

    @Test
    fun `a stream prepared ahead that has no video hides the pill without a message`() {
        val r = Rig()
        r.expanded = false
        r.extractResult = { Result.failure(StreamResolveException(StreamResolveException.Reason.UNAVAILABLE, "x")) }
        r.controller.update(r.inputs())
        now += 3_000
        r.controller.tick()
        assertEquals(1, r.extractions.size)
        assertFalse(r.state.pillVisible)
        assertTrue(r.host.toasts.isEmpty())
        assertEquals(VideoStatus.Failed(VideoFailureKind.UNPLAYABLE, retrying = false), VideoDiagnostics.statusFor("song"))
    }

    @Test
    fun `a failed preparing is only a log line - no count, no message, no rest, and a later start still works`() {
        val r = Rig()
        r.expanded = false
        failWith(r, java.net.UnknownHostException("offline"))
        r.controller.update(r.inputs())
        now += 3_000
        r.controller.tick()
        assertEquals(1, r.extractions.size)
        assertEquals(0, r.session.consecutiveFailures)
        assertTrue(r.host.toasts.isEmpty())
        assertFalse(r.session.isNoVideo("song"))
        assertTrue(r.state.pillVisible)
        // it does not hammer: the next try is after a wait
        now += 500
        r.controller.tick()
        assertEquals(1, r.extractions.size)
        r.extractResult = { id -> Result.success(source(id)) }
        r.expanded = true
        r.controller.tick()
        assertEquals(1, r.players.size)
    }

    // ------------------------------------------------------------------------------------------------ video stability: what the media-info line can say

    @Test
    fun `the status of the song follows what happened to it`() {
        val own = Rig()
        own.controller.update(own.inputs(song = video("own"), stored = VideoMode.AUDIO))
        assertEquals(VideoStatus.Available, VideoDiagnostics.statusFor("own"))

        val none = Rig()
        none.host.counterpart = null
        none.controller.update(none.inputs(song = audioSong("lonely"), stored = VideoMode.AUDIO))
        assertEquals(VideoStatus.NoMatch, VideoDiagnostics.statusFor("lonely"))

        val found = Rig()
        found.host.counterpart = clip("clipId", sec = 205)
        found.controller.update(found.inputs(song = audioSong("paired"), stored = VideoMode.AUDIO))
        assertEquals(VideoStatus.Available, VideoDiagnostics.statusFor("paired"))

        val formatless = Rig()
        formatless.extractResult = { Result.failure(com.metrolist.music.utils.InnerTubeXPlayer.NoSabrVideoException("none")) }
        formatless.controller.update(formatless.inputs(song = video("nofmt")))
        assertEquals(VideoStatus.Failed(VideoFailureKind.NO_FORMAT, retrying = false), VideoDiagnostics.statusFor("nofmt"))
    }
}
