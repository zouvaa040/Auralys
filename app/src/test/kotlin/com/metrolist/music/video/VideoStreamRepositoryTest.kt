package com.metrolist.music.video

import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrFormatId
import com.metrolist.music.utils.InnerTubeXPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class VideoStreamRepositoryTest {
    private fun bootstrap(id: String = "vid") =
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

    private fun source(
        mime: String? = "video/webm",
        codecs: String? = "vp9",
        expires: Int = 6 * 3600,
        id: String = "vid",
    ) = VideoPlaybackSource(bootstrap(id), mime, codecs, 640, 360, 500_000, 200_000, 7_000_000L, expires)

    private val allDecoders = DecoderSupport { _, _, _ -> true }

    @Test
    fun `a playable format comes back as Ready with the format info`() =
        runBlocking {
            val repo = VideoStreamRepository(extractor = { _, _ -> Result.success(source()) }, decoders = allDecoders)
            val r = repo.get("vid", 360)
            assertTrue(r is VideoStreamResult.Ready)
            val info = (r as VideoStreamResult.Ready).info
            assertEquals(VideoContainer.WEBM, info.format.container)
            assertEquals(VideoCodec.VP9, info.format.codec)
            assertEquals(200_000L, info.durationMs)
            assertEquals(360, info.maxHeight)
        }

    @Test
    fun `the extraction asks for the cap it was given`() =
        runBlocking {
            var asked = -1
            val repo = VideoStreamRepository(extractor = { _, h -> asked = h; Result.success(source()) }, decoders = allDecoders)
            repo.get("vid", 720)
            assertEquals(720, asked)
        }

    @Test
    fun `a codec the phone cannot decode is NoVideo, not an error`() =
        runBlocking {
            val repo = VideoStreamRepository(extractor = { _, _ -> Result.success(source()) }, decoders = { _, _, _ -> false })
            val r = repo.get("vid", 360)
            assertEquals(NoVideoReason.UNSUPPORTED_CODEC, (r as VideoStreamResult.NoVideo).reason)
        }

    @Test
    fun `the decoder is asked for the mime of the chosen codec`() =
        runBlocking {
            val asked = mutableListOf<String>()
            val repo =
                VideoStreamRepository(
                    extractor = { _, _ -> Result.success(source("video/mp4", "avc1.4D401E")) },
                    decoders = { m, _, _ -> asked += m; true },
                )
            repo.get("vid", 360)
            assertEquals(listOf("video/avc"), asked)
        }

    @Test
    fun `an unknown container or codec is NoVideo`() =
        runBlocking {
            val repo = VideoStreamRepository(extractor = { _, _ -> Result.success(source("video/x-flv", "h263")) }, decoders = allDecoders)
            assertEquals(NoVideoReason.UNKNOWN_FORMAT, ((repo.get("vid", 360)) as VideoStreamResult.NoVideo).reason)
        }

    @Test
    fun `no SABR video from the library is NoVideo`() =
        runBlocking {
            val repo =
                VideoStreamRepository(
                    extractor = { _, _ -> Result.failure(InnerTubeXPlayer.NoSabrVideoException("none")) },
                    decoders = allDecoders,
                )
            assertEquals(NoVideoReason.NO_STREAM, (repo.get("vid", 360) as VideoStreamResult.NoVideo).reason)
        }

    @Test
    fun `unavailable and age restricted songs are NoVideo, a network error is Failed`() =
        runBlocking {
            var error: Throwable = StreamResolveException(StreamResolveException.Reason.AGE_RESTRICTED, "age")
            val repo = VideoStreamRepository(extractor = { _, _ -> Result.failure(error) }, decoders = allDecoders)
            assertEquals(NoVideoReason.UNAVAILABLE, (repo.get("a", 360) as VideoStreamResult.NoVideo).reason)
            error = StreamResolveException(StreamResolveException.Reason.UNAVAILABLE, "gone")
            assertEquals(NoVideoReason.UNAVAILABLE, (repo.get("b", 360) as VideoStreamResult.NoVideo).reason)
            error = StreamResolveException(StreamResolveException.Reason.NETWORK, "net")
            assertTrue(repo.get("c", 360) is VideoStreamResult.Failed)
            error = IOException("boom")
            assertTrue(repo.get("d", 360) is VideoStreamResult.Failed)
        }

    @Test
    fun `an extractor that throws is Failed`() =
        runBlocking {
            val repo = VideoStreamRepository(extractor = { _, _ -> throw IllegalStateException("x") }, decoders = allDecoders)
            assertTrue(repo.get("vid", 360) is VideoStreamResult.Failed)
        }

    @Test
    fun `a failure is not cached, the next ask tries again`() =
        runBlocking {
            val calls = AtomicInteger()
            val repo =
                VideoStreamRepository(
                    extractor = { _, _ -> if (calls.incrementAndGet() == 1) Result.failure(IOException("x")) else Result.success(source()) },
                    decoders = allDecoders,
                )
            assertTrue(repo.get("vid", 360) is VideoStreamResult.Failed)
            assertTrue(repo.get("vid", 360) is VideoStreamResult.Ready)
            assertEquals(2, calls.get())
        }

    @Test
    fun `an answer is kept until it expires`() =
        runBlocking {
            var now = 0L
            val calls = AtomicInteger()
            val repo =
                VideoStreamRepository(
                    extractor = { _, _ -> calls.incrementAndGet(); Result.success(source(expires = 3600)) },
                    decoders = allDecoders,
                    now = { now },
                )
            repo.get("vid", 360)
            now = 49 * 60 * 1000L // 3600 s minus the 10 minute margin = 50 minutes
            repo.get("vid", 360)
            assertEquals(1, calls.get())
            now = 51 * 60 * 1000L
            repo.get("vid", 360)
            assertEquals(2, calls.get())
        }

    @Test
    fun `a stream address with a very short life is still kept for a minute`() =
        runBlocking {
            var now = 0L
            val calls = AtomicInteger()
            val repo =
                VideoStreamRepository(
                    extractor = { _, _ -> calls.incrementAndGet(); Result.success(source(expires = 30)) },
                    decoders = allDecoders,
                    now = { now },
                )
            repo.get("vid", 360)
            now = 59_000L
            repo.get("vid", 360)
            assertEquals(1, calls.get())
            now = 61_000L
            repo.get("vid", 360)
            assertEquals(2, calls.get())
        }

    @Test
    fun `a song without video is not asked again for 10 minutes`() =
        runBlocking {
            var now = 0L
            val calls = AtomicInteger()
            val repo =
                VideoStreamRepository(
                    extractor = { _, _ -> calls.incrementAndGet(); Result.failure(InnerTubeXPlayer.NoSabrVideoException("none")) },
                    decoders = allDecoders,
                    now = { now },
                )
            repo.get("vid", 360)
            now = 9 * 60 * 1000L
            repo.get("vid", 360)
            assertEquals(1, calls.get())
            now = 10 * 60 * 1000L + 1
            repo.get("vid", 360)
            assertEquals(2, calls.get())
        }

    @Test
    fun `another cap is another answer`() =
        runBlocking {
            val calls = AtomicInteger()
            val repo = VideoStreamRepository(extractor = { _, _ -> calls.incrementAndGet(); Result.success(source()) }, decoders = allDecoders)
            repo.get("vid", 360)
            repo.get("vid", 720)
            repo.get("vid", 360)
            assertEquals(2, calls.get())
        }

    @Test
    fun `force fresh and invalidate drop the stored answer`() =
        runBlocking {
            val calls = AtomicInteger()
            val repo = VideoStreamRepository(extractor = { _, _ -> calls.incrementAndGet(); Result.success(source()) }, decoders = allDecoders)
            repo.get("vid", 360)
            repo.get("vid", 360, forceFresh = true)
            assertEquals(2, calls.get())
            repo.invalidate("vid")
            assertNull(repo.peek("vid", 360))
            repo.get("vid", 360)
            assertEquals(3, calls.get())
        }

    @Test
    fun `two callers share one extraction`() =
        runBlocking {
            val calls = AtomicInteger()
            val gate = CompletableDeferred<Unit>()
            val repo =
                VideoStreamRepository(
                    extractor = { _, _ ->
                        calls.incrementAndGet()
                        gate.await()
                        Result.success(source())
                    },
                    decoders = allDecoders,
                )
            val a = async { repo.get("vid", 360) }
            val b = async { repo.get("vid", 360) }
            // both are waiting on the one extraction
            while (calls.get() == 0) kotlinx.coroutines.yield()
            gate.complete(Unit)
            assertTrue(a.await() is VideoStreamResult.Ready)
            assertTrue(b.await() is VideoStreamResult.Ready)
            assertEquals(1, calls.get())
        }

    @Test
    fun `a caller that leaves does not stop the extraction, its answer lands in the cache`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val done = CompletableDeferred<Unit>()
            val repo =
                VideoStreamRepository(
                    extractor = { _, _ ->
                        gate.await()
                        done.complete(Unit)
                        Result.success(source())
                    },
                    decoders = allDecoders,
                )
            val waiter = async { repo.get("vid", 360) }
            kotlinx.coroutines.yield()
            waiter.cancel()
            gate.complete(Unit)
            done.await()
            // the answer is stored a moment after the extractor returned
            var stored: VideoStreamResult? = null
            repeat(100) {
                stored = repo.peek("vid", 360)
                if (stored == null) Thread.sleep(10)
            }
            assertNotNull(stored)
        }

    @Test
    fun `the cache is bounded`() =
        runBlocking {
            val repo = VideoStreamRepository(extractor = { id, _ -> Result.success(source(id = id)) }, decoders = allDecoders, maxEntries = 2)
            repo.get("a", 360)
            repo.get("b", 360)
            repo.get("c", 360)
            assertNull(repo.peek("a", 360))
            assertNotNull(repo.peek("c", 360))
        }

    // ---------------------------------------------------------------- REVIEW-2026-11-07 F3: hardware or software decoder

    private fun hdSource(id: String = "vid") = VideoPlaybackSource(bootstrap(id), "video/webm", "vp9", 1280, 720, 1_500_000, 200_000, 20_000_000L, 6 * 3600)

    private fun decoders(kind: DecoderKind) =
        object : DecoderSupport {
            override fun supports(
                mime: String,
                width: Int,
                height: Int,
            ) = kind != DecoderKind.NONE

            override fun kind(
                mime: String,
                width: Int,
                height: Int,
            ) = kind
        }

    @Test
    fun `a hardware decoder plays 720 p with one extraction`() =
        runBlocking {
            val asked = mutableListOf<Int>()
            val repo = VideoStreamRepository(extractor = { _, h -> asked += h; Result.success(hdSource()) }, decoders = decoders(DecoderKind.HARDWARE))
            val r = repo.get("vid", 720)
            assertTrue(r is VideoStreamResult.Ready)
            assertEquals(listOf(720), asked)
            assertEquals(720, (r as VideoStreamResult.Ready).info.format.height)
        }

    @Test
    fun `only a software decoder - the 720 p pick is asked again at 360 p and that one plays`() =
        runBlocking {
            val asked = mutableListOf<Int>()
            val repo =
                VideoStreamRepository(
                    extractor = { _, h -> asked += h; Result.success(if (h > 360) hdSource() else source()) },
                    decoders = decoders(DecoderKind.SOFTWARE),
                )
            val r = repo.get("vid", 720)
            assertEquals(listOf(720, 360), asked)
            assertTrue(r is VideoStreamResult.Ready)
            val info = (r as VideoStreamResult.Ready).info
            assertEquals(360, info.format.height)
            assertEquals("the stream was asked for at 360 p", 360, info.maxHeight)
            // the answer is kept for the 720 p question: no third extraction
            repo.get("vid", 720)
            assertEquals(2, asked.size)
        }

    @Test
    fun `only a software decoder and the library still gives 720 p - no video, not an error`() =
        runBlocking {
            val asked = mutableListOf<Int>()
            val repo =
                VideoStreamRepository(
                    extractor = { _, h -> asked += h; Result.success(hdSource()) },
                    decoders = decoders(DecoderKind.SOFTWARE),
                )
            val r = repo.get("vid", 720)
            assertEquals(listOf(720, 360), asked)
            assertEquals(NoVideoReason.UNSUPPORTED_CODEC, (r as VideoStreamResult.NoVideo).reason)
        }

    @Test
    fun `only a software decoder and 360 p asked - plays at once, one extraction`() =
        runBlocking {
            val asked = mutableListOf<Int>()
            val repo = VideoStreamRepository(extractor = { _, h -> asked += h; Result.success(source()) }, decoders = decoders(DecoderKind.SOFTWARE))
            assertTrue(repo.get("vid", 360) is VideoStreamResult.Ready)
            assertEquals(listOf(360), asked)
        }

    @Test
    fun `only a software decoder and a 720 p result when 360 p was asked - no video`() =
        runBlocking {
            val asked = mutableListOf<Int>()
            val repo = VideoStreamRepository(extractor = { _, h -> asked += h; Result.success(hdSource()) }, decoders = decoders(DecoderKind.SOFTWARE))
            val r = repo.get("vid", 360)
            assertEquals("no second try when 360 p was the question", listOf(360), asked)
            assertEquals(NoVideoReason.UNSUPPORTED_CODEC, (r as VideoStreamResult.NoVideo).reason)
        }

    @Test
    fun `the second extraction at 360 p that fails is a failure, not a verdict, and is not cached`() =
        runBlocking {
            var calls = 0
            val repo =
                VideoStreamRepository(
                    extractor = { _, h ->
                        calls++
                        if (h > 360) Result.success(hdSource()) else Result.failure(IOException("net"))
                    },
                    decoders = decoders(DecoderKind.SOFTWARE),
                )
            assertTrue(repo.get("vid", 720) is VideoStreamResult.Failed)
            assertNull(repo.peek("vid", 720))
            assertEquals(2, calls)
        }

    @Test
    fun `no decoder at all is still NoVideo with one extraction`() =
        runBlocking {
            var calls = 0
            val repo = VideoStreamRepository(extractor = { _, _ -> calls++; Result.success(hdSource()) }, decoders = decoders(DecoderKind.NONE))
            assertEquals(NoVideoReason.UNSUPPORTED_CODEC, (repo.get("vid", 720) as VideoStreamResult.NoVideo).reason)
            assertEquals(1, calls)
        }

    @Test
    fun `the decoder is asked for the size of the format the library picked`() =
        runBlocking {
            val sizes = mutableListOf<Pair<Int, Int>>()
            val repo =
                VideoStreamRepository(
                    extractor = { _, _ -> Result.success(hdSource()) },
                    decoders = { _, w, h -> sizes += w to h; true },
                )
            repo.get("vid", 720)
            assertEquals(listOf(1280 to 720), sizes)
        }
}
