/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** One lyrics provider as the race sees it: a name and a call that gives a rated answer or null. */
internal class RaceEntry(
    val name: String,
    val fetch: suspend () -> LyricsSelection.Candidate?,
)

/**
 * Which lyrics to show, kept free of Android so it can be tested with plain data.
 *
 * Every answer is rated by what can be checked about it:
 *  - what the source says it matched (title with its version words, guests, artist, length; see [rate]);
 *  - the timestamps against the length of the playing song.
 * Ranks, best first: [Quality.VERIFIED] word timed, [Quality.VERIFIED] line timed, [Quality.LOOSE] (timed, the source's
 * recording is 3 to 8 s longer or shorter), [Quality.DISTANT] (timed, a music video upload whose length is further off
 * than that but whose lyrics fit inside it), [Quality.SHORT] (timed but stops before half of the song), [Quality.PLAIN]
 * (no timing). [Quality.REJECTED] (another song, another version, timing past the end) is never chosen automatically;
 * the source picker still lists it as "another version". Among equals the user's provider order decides.
 */
object LyricsSelection {
    private val SYNCED_LINE = Regex("""\[(\d{1,3}):(\d{2})(?:[.:]\d+)?\]""")
    // "<00:12.34>" inline stamps (rich sync), or the app's own word block line "<word:1.42:1.80|word:...>"
    private val WORD_STAMP =
        Regex("""<\d{1,3}:\d{2}[.:]\d{2,3}>|^<[^\n]*?:\d+(?:\.\d+)?:\d+(?:\.\d+)?[^\n]*>\s*$""", RegexOption.MULTILINE)

    /** Lyrics may end this long after the song (outro, rounding) before they count as another cut. */
    const val OVERRUN_MS = 8_000L

    /** The source's recording may be this much longer or shorter than the playing song and still be the same one. */
    const val STRICT_GAP_SECONDS = 3.0

    /** Up to this gap the lyrics are kept as a fallback ([Quality.LOOSE]): often only silence at the end differs. */
    const val LOOSE_GAP_SECONDS = 8.0

    /** A source that looked the song up by its video id may be a little further off (its own length rounding). */
    const val VIDEO_GAP_SECONDS = 10.0

    /**
     * How much longer or shorter than the audio a music video upload may be and still take its lyrics (an intro, a skit,
     * an outro): 15 % of the video, between 30 and 45 s. Only for [LyricsRequest.isVideo]; catalogue songs keep
     * [LOOSE_GAP_SECONDS].
     */
    fun videoUploadGapSeconds(durationSeconds: Int): Double = (durationSeconds * 0.15).coerceIn(30.0, 45.0)

    enum class Quality { VERIFIED, LOOSE, DISTANT, SHORT, PLAIN, REJECTED }

    enum class Sync { WORD, LINE, PLAIN }

    data class Candidate(
        val provider: String,
        val lyrics: String,
        val quality: Quality,
        val sync: Sync,
    ) {
        /** Smaller is better. */
        val rank: Int
            get() =
                when (quality) {
                    Quality.VERIFIED -> if (sync == Sync.WORD) 0 else 1
                    Quality.LOOSE -> 2
                    Quality.DISTANT -> 3
                    Quality.SHORT -> 4
                    Quality.PLAIN -> 5
                    Quality.REJECTED -> 9
                }

        val usable: Boolean get() = quality != Quality.REJECTED
    }

    fun isSynced(lyrics: String): Boolean = SYNCED_LINE.containsMatchIn(lyrics)

    fun sync(lyrics: String): Sync =
        when {
            !isSynced(lyrics) -> Sync.PLAIN
            WORD_STAMP.containsMatchIn(lyrics) -> Sync.WORD
            else -> Sync.LINE
        }

    fun lastStampMs(lyrics: String): Long {
        var last = 0L
        SYNCED_LINE.findAll(lyrics).forEach { m ->
            val ms = m.groupValues[1].toLong() * 60_000L + m.groupValues[2].toLong() * 1000L
            if (ms > last) last = ms
        }
        return last
    }

    /** The timing check alone: the timestamps against the length of the song. */
    fun quality(
        lyrics: String,
        durationSeconds: Int,
    ): Quality {
        if (lyrics.isBlank()) return Quality.REJECTED
        if (!isSynced(lyrics)) return Quality.PLAIN
        if (durationSeconds <= 0) return Quality.VERIFIED
        val last = lastStampMs(lyrics)
        return when {
            last > durationSeconds * 1000L + OVERRUN_MS -> Quality.REJECTED
            last < durationSeconds * 500L -> Quality.SHORT
            else -> Quality.VERIFIED
        }
    }

    /**
     * Rates what [provider] found for [request]. The metadata of the hit, when the provider reports it, must be the
     * playing recording: same title and version words, no guest the song does not have, an artist in common, a length
     * close enough. Then the timestamps must fit the song.
     */
    fun rate(
        provider: String,
        request: LyricsRequest,
        hit: LyricsHit,
    ): Candidate {
        val text = hit.text
        val sync = sync(text)
        fun result(q: Quality) = Candidate(provider, text, q, sync)

        if (text.isBlank()) return result(Quality.REJECTED)
        if (hit.title != null) {
            if (!LyricsHitMatch.sameTitle(hit.title, request.title)) return result(Quality.REJECTED)
            if (LyricsHitMatch.guestConflict(hit.title, hit.artist, request.rawTitle, request.artist)) return result(Quality.REJECTED)
        }
        if (hit.artist != null && !SongMatch.sameArtist(hit.artist, request.artist)) return result(Quality.REJECTED)

        var timing = quality(text, request.duration)
        if (timing == Quality.REJECTED) return result(Quality.REJECTED)

        val hitSeconds = hit.durationSeconds
        if (hitSeconds != null && hitSeconds > 0 && request.duration > 0) {
            val gap = abs(hitSeconds - request.duration)
            val strict = if (hit.keyedOnVideo) VIDEO_GAP_SECONDS else STRICT_GAP_SECONDS
            val guestMissing = LyricsHitMatch.guestMissing(hit.title, hit.artist, request.rawTitle)
            when {
                gap <= strict -> Unit
                // a song with a guest whose hit does not name the guest: only a close length vouches for it
                guestMissing -> return result(Quality.REJECTED)
                gap <= LOOSE_GAP_SECONDS && timing == Quality.VERIFIED -> timing = Quality.LOOSE
                gap <= LOOSE_GAP_SECONDS -> Unit
                // a music video upload is often longer than the audio the lyrics were timed on (intro, skit, outro).
                // 4.3.3 showed such lyrics (BetterLyrics had no length check); they are kept, below every closer answer.
                // The timestamps were checked above: they fit inside the video.
                request.isVideo && gap <= videoUploadGapSeconds(request.duration) ->
                    if (timing == Quality.VERIFIED) timing = Quality.DISTANT
                // further off is another recording, timed or not: probe 2026-11-12, LRCLIB's only "Untitled" with
                // lyrics for Rex Orange County's 2:16 song was a 3:21 song of a Ukrainian band (plain text)
                else -> return result(Quality.REJECTED)
            }
        }
        // A song with version words ("Remix", "Acoustic", "Live", "Sped Up") from a source that does not say which
        // recording it matched: it may be the original (BetterLyrics gave the original Despacito for "Despacito (Remix)",
        // with a length 1 s apart). It is kept, below every source that did check the version.
        if (hit.title == null && !hit.keyedOnVideo && timing == Quality.VERIFIED && LyricsHitMatch.versionWords(request.title).isNotEmpty()) {
            timing = Quality.LOOSE
        }
        return result(timing)
    }

    private val LRC_LINE = Regex("""^\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\](.*)$""")
    // Android's ICU regex rejects an unescaped literal '}' (the JVM accepts it): 4.3.4 crashed on this line.
    // Every literal '{', '}', '[', ']' in a pattern gets a backslash (AndroidRegexGuardTest checks the sources).
    private val TAGS = Regex("""\{[^}]*\}""")

    /** Start of the first line that has words, in ms; null for untimed text. */
    fun firstSungMs(lyrics: String): Long? {
        for (line in lyrics.lineSequence()) {
            val m = LRC_LINE.find(line.trim()) ?: continue
            if (m.groupValues[4].replace(TAGS, "").isBlank()) continue
            val frac = m.groupValues[3]
            val fracMs = if (frac.isEmpty()) 0L else (frac + "00").take(3).toLong()
            return m.groupValues[1].toLong() * 60_000L + m.groupValues[2].toLong() * 1000L + fracMs
        }
        return null
    }

    /** A timed answer whose singing starts AND ends this far from where the others agree is another version. */
    const val OUTLIER_MS = 4_000L

    private const val AGREE_MS = 3_000L

    /**
     * The timed answers whose first and last sung lines are both far from where at least two other answers agree they
     * are (within 3 s of each other). Live probe 2026-11-12: for the studio "Hotel California" one community entry sang
     * from 2:19 to 5:41 (a live version), three others from 0:52 to 4:15. Both ends must be off: an ad-lib line that one
     * source has and another leaves out ("Yeah" at 0:14 in Blinding Lights) only moves the first one.
     */
    fun outliers(candidates: List<Candidate>): Set<Candidate> {
        class Span(val c: Candidate, val first: Long, val last: Long)
        val timed =
            candidates
                .filter { it.usable && it.sync != Sync.PLAIN }
                .mapNotNull { c -> firstSungMs(c.lyrics)?.let { Span(c, it, lastStampMs(c.lyrics)) } }
        if (timed.size < 3) return emptySet()
        fun agree(a: Span, b: Span) = abs(a.first - b.first) <= AGREE_MS && abs(a.last - b.last) <= AGREE_MS
        return timed
            .filter { me ->
                val others = timed.filter { it !== me }
                val agreeing = others.filter { o -> others.count { agree(it, o) } >= 2 }
                agreeing.isNotEmpty() &&
                    agreeing.all { abs(it.first - me.first) > OUTLIER_MS && abs(it.last - me.last) > OUTLIER_MS }
            }.map { it.c }
            .toSet()
    }

    /** For tests and simple callers: rates a bare text with no metadata. */
    fun candidate(
        provider: String,
        lyrics: String,
        durationSeconds: Int,
    ): Candidate = Candidate(provider, lyrics, quality(lyrics, durationSeconds), sync(lyrics))

    /**
     * [candidates] must be in the user's priority order. The best usable one, or null. An answer whose singing starts
     * where no other answer does ([outliers]) is only taken when nothing else is usable.
     */
    fun best(candidates: List<Candidate>): Candidate? {
        val odd = outliers(candidates)
        return candidates.withIndex()
            .filter { it.value.usable }
            .minWithOrNull(
                compareBy<IndexedValue<Candidate>> { if (it.value in odd) 1 else 0 }
                    .thenBy { it.value.rank }
                    .thenBy { it.index },
            )?.value
    }

    /** Old form kept for callers that only have texts: rates them on timing alone. */
    fun bestOfTexts(
        candidates: List<Pair<String, String>>,
        durationSeconds: Int,
    ): Candidate? = best(candidates.map { (p, t) -> candidate(p, t, durationSeconds) })

    /**
     * True when nothing still running can beat [best]: a better rank needs a word timed answer, which can come from any
     * entry, and an equal rank needs an entry higher in the list.
     */
    internal fun settled(
        best: IndexedValue<Candidate>?,
        finished: BooleanArray,
        graceOver: Boolean,
    ): Boolean {
        if (best == null) return finished.all { it }
        val higherDone = (0 until best.index).all { finished[it] }
        return when (best.value.rank) {
            0 -> higherDone
            else -> higherDone && (graceOver || finished.all { it })
        }
    }

    /**
     * Asks every entry at the same time. A word timed, verified answer is taken as soon as every entry above it has
     * answered. Anything less waits for the others (a word timed answer from lower in the list beats a line timed one)
     * but at most [graceMs] after the first verified answer, and the whole call is over after [totalMs]. Each entry gets
     * [perProviderMs]; one that throws or times out is a plain miss. [onAnswer] sees every answer as it comes (null for
     * a miss), in the order they arrive.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal suspend fun race(
        entries: List<RaceEntry>,
        perProviderMs: Long,
        totalMs: Long,
        graceMs: Long = 2_500L,
        onAnswer: (String, Candidate?) -> Unit = { _, _ -> },
    ): Candidate? =
        coroutineScope {
            val start = System.nanoTime()
            fun elapsedMs() = (System.nanoTime() - start) / 1_000_000L
            val jobs =
                entries.map { entry ->
                    async {
                        try {
                            withTimeoutOrNull(perProviderMs) { entry.fetch() }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            null
                        }
                    }
                }
            val finished = BooleanArray(entries.size)
            val answers = arrayOfNulls<Candidate>(entries.size)
            var verifiedAt = -1L

            while (true) {
                val best =
                    answers.withIndex()
                        .mapNotNull { (i, c) -> c?.takeIf { it.usable }?.let { IndexedValue(i, it) } }
                        .minWithOrNull(compareBy<IndexedValue<Candidate>> { it.value.rank }.thenBy { it.index })
                if (best != null && best.value.quality == Quality.VERIFIED && verifiedAt < 0) verifiedAt = elapsedMs()
                val graceOver = verifiedAt >= 0 && elapsedMs() >= verifiedAt + graceMs
                if (settled(best, finished, graceOver)) break
                val left = totalMs - elapsedMs()
                if (left <= 0) break
                val wait = if (verifiedAt >= 0 && !graceOver) minOf(left, verifiedAt + graceMs - elapsedMs()) else left
                val pending = jobs.indices.filter { !finished[it] }
                if (pending.isEmpty()) break
                select<Unit> {
                    pending.forEach { i ->
                        jobs[i].onAwait { value ->
                            finished[i] = true
                            answers[i] = value
                            onAnswer(entries[i].name, value)
                        }
                    }
                    onTimeout(wait.coerceAtLeast(1L)) {}
                }
            }
            jobs.forEach { it.cancel() }
            // answers is in priority order, so the order of the usable ones still is
            best(answers.filterNotNull())
        }
}
