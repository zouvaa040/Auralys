/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Reads the listening history of the YouTube Music account a page at a time and writes what the phone lacks into the
 * local play events, using the pure rules of [RemoteHistoryMerge]. Which runs happen and when is decided by
 * [RemoteHistoryMerger]; this class only does one run. It never touches the main thread, never changes the schema (the
 * plays are ordinary `event` rows) and can be cancelled at any moment: nothing is written before the reading ended, and
 * the writing is one transaction.
 */

package com.metrolist.music.utils

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.HistoryChunk
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Event
import com.metrolist.music.models.toMediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

/** Gives one piece of the account history; [continuation] is null for the first one. */
fun interface HistoryFetcher {
    suspend fun fetch(continuation: String?): Result<HistoryChunk>
}

/** The real one: asks YouTube Music in English, with a copy of the session, so the day titles are always readable. */
object YouTubeHistoryFetcher : HistoryFetcher {
    override suspend fun fetch(continuation: String?): Result<HistoryChunk> =
        YouTube.musicHistoryChunk(continuation = continuation, languageTag = "en")
}

enum class ImportMode {
    /** Read as far back as the limits allow: the first run, a new account, a run the user asked for. */
    FULL,

    /** Read back to [ImportRequest.stopBelowDay] only: the periodic runs. */
    INCREMENTAL,
}

data class ImportRequest(
    val mode: ImportMode,
    /** Stop after the page that reaches this day (or an earlier one). */
    val stopBelowDay: LocalDate? = null,
    /** The days up to and including this one are not merged (the history was cleared then). */
    val floorDay: LocalDate? = null,
    /** Plays the user removed here, see [RemovedPlays]. */
    val removed: Map<DaySong, Int> = emptyMap(),
)

data class ImportLimits(
    val maxPages: Int = 40,
    val maxEntries: Int = 2000,
    val timeoutMs: Long = 60_000L,
)

data class ImportOutcome(
    /** Events written. */
    val added: Int,
    val pages: Int,
    val entries: Int,
    /** The history was read to its end, or to the stop day. */
    val reachedEnd: Boolean,
    /** Reading stopped at the page or entry limit. */
    val capped: Boolean,
    val timedOut: Boolean,
    val error: Throwable?,
    val skippedTitles: List<String>,
    val plan: MergePlan?,
) {
    /** No error and no timeout: what was read is all there was to read within the limits. */
    val complete: Boolean get() = error == null && !timedOut
}

class RemoteHistoryImporter(
    private val database: MusicDatabase,
    private val fetcher: HistoryFetcher = YouTubeHistoryFetcher,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val pause: suspend () -> Unit = { delay(PAGE_PAUSE_MS) },
    private val limits: ImportLimits = ImportLimits(),
) {
    private class Page(val title: String, val songs: MutableList<SongItem>)

    suspend fun run(request: ImportRequest): ImportOutcome =
        withContext(Dispatchers.IO) {
            val today = LocalDate.now(clock)
            val now = LocalDateTime.now(clock)

            val pages = ArrayList<Page>()
            val pending = ArrayList<String>() // a stack: the token of the last section's songs is read before the next sections
            val seen = HashSet<String>()
            var pageCount = 0
            var entries = 0
            var error: Throwable? = null
            var reachedEnd = false
            var capped = false

            val finished =
                withTimeoutOrNull(limits.timeoutMs) {
                    var token: String? = null
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val result = fetcher.fetch(token)
                        // runCatching inside the fetcher also catches a cancellation: look again before going on
                        currentCoroutineContext().ensureActive()
                        val chunk = result.getOrNull()
                        if (chunk == null) {
                            error = result.exceptionOrNull() ?: IllegalStateException("empty history response")
                            return@withTimeoutOrNull
                        }
                        pageCount++
                        for (section in chunk.sections) {
                            pages += Page(section.title, section.songs.toMutableList())
                            entries += section.songs.size
                        }
                        if (chunk.moreSongs.isNotEmpty()) {
                            val last = pages.lastOrNull()
                            if (last != null) {
                                last.songs += chunk.moreSongs
                                entries += chunk.moreSongs.size
                            } else {
                                Timber.tag(TAG).w("History continuation without a section to add %d songs to", chunk.moreSongs.size)
                            }
                        }
                        chunk.sectionContinuation?.let { if (seen.add(it)) pending += it }
                        chunk.shelfContinuation?.let { if (seen.add(it)) pending += it }

                        val stop = request.stopBelowDay
                        if (stop != null) {
                            val oldest = oldestDay(chunk.sections.map { it.title }, today)
                            if (oldest != null && !oldest.isAfter(stop)) {
                                reachedEnd = true
                                return@withTimeoutOrNull
                            }
                        }
                        if (entries >= limits.maxEntries || pageCount >= limits.maxPages) {
                            capped = true
                            return@withTimeoutOrNull
                        }
                        token = pending.removeLastOrNull()
                        if (token == null) {
                            reachedEnd = true
                            return@withTimeoutOrNull
                        }
                        pause()
                    }
                }
            val timedOut = finished == null
            if (error != null) {
                Timber.tag(TAG).w(error, "History merge: reading the account history stopped after %d pages", pageCount)
            } else if (timedOut) {
                Timber.tag(TAG).w("History merge: timeout after %d pages", pageCount)
            }

            val songById = LinkedHashMap<String, SongItem>()
            val sections =
                pages.map { page ->
                    for (song in page.songs) songById.putIfAbsent(song.id, song)
                    RemoteSection(page.title, page.songs.map { RemoteSong(it.id, it.duration) })
                }
            val resolution = RemoteHistoryMerge.resolve(sections, today)
            logSkipped(resolution.skippedTitles)

            var plan: MergePlan? = null
            var added = 0
            if (resolution.days.isNotEmpty()) {
                val oldest = resolution.days.minOf { it.day }
                val newest = resolution.days.maxOf { it.day }
                val local =
                    database
                        .eventStampsBetween(oldest.minusDays(1).atStartOfDay(), newest.plusDays(2).atStartOfDay())
                        .map { LocalPlay(it.songId, it.timestamp) }
                plan =
                    RemoteHistoryMerge.plan(
                        remote = resolution.days,
                        local = local,
                        today = today,
                        now = now,
                        suppressed = request.removed,
                        floorDay = request.floorDay,
                    )
                if (plan.plays.isNotEmpty()) added = write(plan, songById)
            }
            ImportOutcome(
                added = added,
                pages = pageCount,
                entries = entries,
                reachedEnd = reachedEnd,
                capped = capped,
                timedOut = timedOut,
                error = error,
                skippedTitles = resolution.skippedTitles,
                plan = plan,
            )
        }

    /** One transaction: the songs the phone has never seen (not added to the library), then the plays oldest first. */
    private suspend fun write(plan: MergePlan, songs: Map<String, SongItem>): Int {
        var added = 0
        database.withTransaction {
            for (id in plan.plays.map { it.songId }.distinct()) {
                // insert() ignores a song that exists; the song keeps its library state and has no library date
                songs[id]?.let { insert(it.toMediaMetadata()) }
            }
            val playTime = HashMap<String, Long>()
            for (play in plan.plays) {
                try {
                    insert(Event(songId = play.songId, timestamp = play.at, playTime = play.playTimeMs))
                    playTime.merge(play.songId, play.playTimeMs, Long::plus)
                    added++
                } catch (e: android.database.SQLException) {
                    // a song that could not be stored has no row for the event to point at
                    Timber.tag(TAG).w(e, "History merge: skipped one play")
                }
            }
            for ((id, ms) in playTime) if (ms > 0) incrementTotalPlayTime(id, ms)
        }
        return added
    }

    private fun oldestDay(titles: List<String>, today: LocalDate): LocalDate? =
        titles.mapNotNull { HistoryTitleParser.parse(it, today) }.minOrNull()

    private fun logSkipped(titles: List<String>) {
        for (title in titles.distinct()) {
            if (loggedTitles.add(title)) Timber.tag(TAG).w("History merge: section title not understood, skipped: \"%s\"", title)
        }
    }

    companion object {
        private const val TAG = "HistoryMerge"
        const val PAGE_PAUSE_MS = 400L

        /** Titles already reported in this process, so a bad title is logged once, not at every run. */
        private val loggedTitles: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    }
}
