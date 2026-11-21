/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import android.util.LruCache
import com.metrolist.music.constants.LyricsProviderOrderKey
import com.metrolist.music.db.entities.LyricsEntity
import com.metrolist.music.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.utils.NetworkConnectivityObserver
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.reportException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// Providers run at the same time: one slow one costs at most PER_PROVIDER_TIMEOUT_MS, and the whole search is cut at MAX_LYRICS_FETCH_MS.
private const val MAX_LYRICS_FETCH_MS = 10000L
private const val PER_PROVIDER_TIMEOUT_MS = 7000L
private const val LOCAL_ID_PREFIX = "LOCAL_"
private const val PROVIDER_NONE = ""

@Singleton
class LyricsHelper
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val networkConnectivity: NetworkConnectivityObserver,
    private val database: MusicDatabase,
) {
    val preferred =
        context.dataStore.data
            .map { preferences ->
                resolveLyricsProviders(preferences)
            }.distinctUntilChanged()

    private val cache = LruCache<String, List<LyricsResult>>(MAX_CACHE_SIZE)
    private val foundCache = LruCache<String, LyricsWithProvider>(MAX_CACHE_SIZE)
    private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetches = SharedFetches<LyricsLookup>(fetchScope)
    private var currentLyricsJob: Job? = null

    /** What every source gave for the last songs (see [LyricsSources]): the source picker reads it, nothing is asked twice. */
    private val sources = LruCache<String, MutableStateFlow<LyricsSources.State>>(MAX_CACHE_SIZE)
    private val singleFetches = SharedFetches<LyricsSources.Answer>(fetchScope)

    val choices = LyricsSourceChoices(context)

    /** The text lyrics had before they were replaced: "Previous lyrics" in the picker. */
    val backup = LyricsBackup(File(context.filesDir, LyricsBackup.DIR))

    private val recheckList = LyricsRecheckList(File(context.filesDir, LyricsRecheck434.FILE))

    private val store =
        LyricsStore(
            dao = database,
            backup = backup,
            isHandMade = { id -> LyricsHandMade.contains(context, id) },
            onReplacedByFound = { id -> LyricsHandMade.unmark(context, id) },
        )

    /**
     * Once after the 4.3.4 update, the lyrics older versions found are listed to be checked again (see
     * [LyricsRecheck434]). Nothing is deleted: a listed song keeps its lyrics until a check finds better ones.
     */
    private val listing: Job =
        fetchScope.launch {
            runCatching {
                val keep = choices.all.first().keys + LyricsHandMade.ids(context)
                LyricsRecheck434.listOnce(context.dataStore, database, recheckList, keep)?.let { listed ->
                    Timber.tag("LyricsHelper").i("4.3.4: $listed stored lyrics will be checked again when they are shown")
                }
            }.onFailure { Timber.tag("LyricsHelper").w(it, "4.3.4 lyrics re-check list failed") }
        }

    /** True while the phone has a network (it may still fail to carry a lookup). */
    val online: StateFlow<Boolean> get() = networkConnectivity.networkStatus

    /** The answers of every source for [mediaId] as they come. */
    fun sourcesOf(mediaId: String): StateFlow<LyricsSources.State> = sourceFlow(mediaId).asStateFlow()

    private fun sourceFlow(mediaId: String): MutableStateFlow<LyricsSources.State> =
        synchronized(sources) {
            sources.get(mediaId) ?: MutableStateFlow(LyricsSources.State()).also { sources.put(mediaId, it) }
        }

    private fun record(
        mediaId: String,
        provider: String,
        answer: LyricsSources.Answer,
    ) {
        sourceFlow(mediaId).update { it.with(provider, answer) }
    }

    /** Every provider in the user's order, enabled or not (the picker lists all of them). */
    suspend fun orderedProviders(): List<LyricsProvider> =
        context.dataStore.data
            .map { preferences -> resolveLyricsProviders(preferences) }
            .first()

    /** True when the provider cannot be asked for this song (a file from the phone has no YouTube video id). */
    fun unavailableFor(
        mediaId: String,
        provider: LyricsProvider,
    ): Boolean = mediaId.startsWith(LOCAL_ID_PREFIX) && provider.name in LyricsSources.VIDEO_ID_PROVIDERS

    private fun requestOf(mediaMetadata: MediaMetadata): LyricsRequest =
        LyricsRequest.of(
            id = mediaMetadata.id,
            rawTitle = mediaMetadata.title,
            artists = mediaMetadata.artists.map { it.name },
            duration = mediaMetadata.duration,
            album = mediaMetadata.album?.title,
            isVideo = mediaMetadata.isVideoSong,
        )

    fun isOnline(): Boolean =
        try {
            networkConnectivity.isCurrentlyConnected()
        } catch (e: Exception) {
            true
        }

    /**
     * One provider asked once for one song: the rated answer (also recorded for the picker). A provider that could not
     * be reached is [LyricsSources.Answer.Failed], one that answered nothing [LyricsSources.Answer.Missing]. A cancelled
     * ask (the race settled, the song was skipped) is recorded "not asked" even when the provider swallowed the
     * cancellation in its own catch-all: before, such sources showed "Not found" and could not be tapped (review
     * 2026-11-12, finding 5).
     */
    private suspend fun ask(
        provider: LyricsProvider,
        request: LyricsRequest,
    ): LyricsSources.Answer =
        LyricsSources.askOnce(provider.name, request, { answer -> record(request.id, provider.name, answer) }) {
            provider.fetch(context, request)
        }

    /**
     * Lyrics for the playing song, for the music service. Waits through network failures (offline, DNS, timeouts) and
     * asks again when the network is back, so it returns either found lyrics or a real "not found" (the sources
     * answered): a song is never stored as "no lyrics" because the phone was offline. The caller is cancelled when the
     * song changes. Found lyrics are kept per song id; [forceRefresh] skips that memory.
     */
    suspend fun getLyrics(
        mediaMetadata: MediaMetadata,
        forceRefresh: Boolean = false,
    ): LyricsWithProvider {
        var attempt = 0
        while (true) {
            when (val outcome = lookup(mediaMetadata, forceRefresh)) {
                is LyricsLookup.Found -> return outcome.lyrics
                LyricsLookup.NotFound -> return LyricsWithProvider(LYRICS_NOT_FOUND, PROVIDER_NONE)
                LyricsLookup.NoConnection -> awaitRetry(attempt++)
            }
        }
    }

    /**
     * [getLyrics] and the store in one, for the music service's automatic lookup of a song without lyrics: waits
     * through network failures like [getLyrics], then stores by [LyricsStore.Mode.FILL] (lyrics the user picked while
     * it waited are not overwritten, "not found" only where there were no lyrics).
     */
    suspend fun fillLyrics(mediaMetadata: MediaMetadata): LyricsStore.Result {
        var attempt = 0
        while (true) {
            val outcome = lookup(mediaMetadata)
            if (outcome !is LyricsLookup.NoConnection) return store.apply(mediaMetadata.id, outcome, LyricsStore.Mode.FILL)
            awaitRetry(attempt++)
        }
    }

    /** Offline: until a network is there (at most 10 min per wait); then a pause that grows with every failed try. */
    private suspend fun awaitRetry(attempt: Int) {
        if (!isOnline()) withTimeoutOrNull(10 * 60_000L) { networkConnectivity.networkStatus.first { it } }
        delay(RETRY_DELAYS_MS[attempt.coerceAtMost(RETRY_DELAYS_MS.lastIndex)])
    }

    /**
     * One lookup of the song's lyrics: when the user picked a source for this song, that source is asked first.
     * Otherwise all enabled providers are asked at the same time and the answer is picked by [LyricsSelection]: checked
     * against the song (title, version, guests, artist, length, timing), word timing before line timing, then the user's
     * order. Callers that ask for the same song while a lookup runs share it. Nothing is stored here (see [LyricsStore]).
     */
    suspend fun lookup(
        mediaMetadata: MediaMetadata,
        forceRefresh: Boolean = false,
    ): LyricsLookup {
        if (!forceRefresh) foundCache.get(mediaMetadata.id)?.let { return LyricsLookup.Found(it) }
        // a caller that is cancelled (the song was skipped) must not leave up to 12 providers busy for nobody
        return fetches.await(mediaMetadata.id) { fetch(mediaMetadata, forceRefresh, usePick = true) }
    }

    private suspend fun fetch(
        mediaMetadata: MediaMetadata,
        forceRefresh: Boolean,
        usePick: Boolean,
    ): LyricsLookup {
        if (!isOnline()) return LyricsLookup.NoConnection

        val request = requestOf(mediaMetadata)
        val ordered = orderedProviders()
        if (forceRefresh) sourceFlow(request.id).value = LyricsSources.State()
        val asked = HashSet<String>()

        // the user's own pick for this song comes first
        val chosen = if (usePick) choices.get(mediaMetadata.id) else null
        if (chosen != null) {
            val provider = ordered.firstOrNull { it.name == chosen }
            if (provider != null && !unavailableFor(request.id, provider)) {
                asked += provider.name
                when (val answer = withTimeoutOrNull(PER_PROVIDER_TIMEOUT_MS) { ask(provider, request) }) {
                    // the user took it on purpose, so even another version is shown
                    is LyricsSources.Answer.Found -> {
                        val found = LyricsWithProvider(answer.candidate.lyrics, answer.candidate.provider)
                        foundCache.put(mediaMetadata.id, found)
                        return LyricsLookup.Found(found)
                    }
                    // the picked source has nothing for this song any more: back to the automatic choice, so a refetch
                    // does not wait for it again every time
                    LyricsSources.Answer.Missing -> choices.set(mediaMetadata.id, null)
                    // could not be reached or too slow: the pick stays, the others are asked
                    else -> Unit
                }
            }
        }

        val enabledProviders = ordered.filter { it.isEnabled(context) && !unavailableFor(request.id, it) }
        Timber.tag("LyricsHelper").d("Lyrics for: ${request.title} by ${request.artist} (${request.duration}s), order ${enabledProviders.joinToString { it.name }}")

        val entries =
            enabledProviders
                .filter { it.name !in asked }
                .map { provider -> RaceEntry(provider.name) { (ask(provider, request) as? LyricsSources.Answer.Found)?.candidate } }
        val best =
            LyricsSelection.race(
                entries = entries,
                perProviderMs = PER_PROVIDER_TIMEOUT_MS,
                totalMs = MAX_LYRICS_FETCH_MS,
            )
        // the ones the race did not wait for are "not asked yet" for the picker
        sourceFlow(request.id).update { it.settleLoading() }
        sourceFlow(request.id).update { it.copy(auto = best?.provider) }

        if (best == null) {
            val answers = sourceFlow(request.id).value.answers
            val outcome = LyricsLookup.withoutLyrics(enabledProviders.map { answers[it.name] })
            Timber.tag("LyricsHelper").w("No lyrics found after checking all providers: $outcome")
            return outcome
        }
        Timber.tag("LyricsHelper").i("Using lyrics from ${best.provider} (${best.quality}, ${best.sync})")
        val found = LyricsWithProvider(best.lyrics, best.provider)
        foundCache.put(mediaMetadata.id, found)
        return LyricsLookup.Found(found)
    }

    /**
     * Looks the song up and stores the outcome by the rules of [LyricsStore] (nothing stored without a connection).
     * Used for songs without a lyrics row yet ([LyricsStore.Mode.FILL]) and for Refetch ([LyricsStore.Mode.REPLACE]).
     */
    suspend fun lookupAndStore(
        mediaMetadata: MediaMetadata,
        mode: LyricsStore.Mode,
        forceRefresh: Boolean = mode == LyricsStore.Mode.REPLACE,
    ): LyricsStore.Result = store.apply(mediaMetadata.id, lookup(mediaMetadata, forceRefresh), mode)

    /** Refetch in the lyrics menu: forgets what the sources said and looks the song up again; old lyrics stay unless better ones come. */
    suspend fun refetch(mediaMetadata: MediaMetadata): LyricsStore.Result {
        if (choices.get(mediaMetadata.id) == LyricsSources.PREVIOUS) choices.set(mediaMetadata.id, null)
        forgetSources(mediaMetadata.id)
        return lookupAndStore(mediaMetadata, LyricsStore.Mode.REPLACE)
    }

    private val rechecking = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * The 4.3.4 re-check of [row], when the song is on the list ([LyricsRecheck434]): the new lookup runs and the row is
     * replaced only by usable lyrics it found (the old text goes to "Previous lyrics"). Offline, with every source
     * failing or with nothing found, the row stays. The song leaves the list once a lookup really answered.
     */
    suspend fun recheckIfListed(
        mediaMetadata: MediaMetadata,
        row: LyricsEntity,
    ) {
        val id = mediaMetadata.id
        if (row.id != id || !LyricsStore.hasText(row)) return
        listing.join()
        if (!rechecking.add(id)) return
        try {
            LyricsRecheck434
                .recheck(
                    row = row,
                    list = recheckList,
                    store = store,
                    keptByUser = { choices.get(id) != null || LyricsHandMade.contains(context, id) },
                    online = ::isOnline,
                    lookup = { lookup(mediaMetadata) },
                )?.let { Timber.tag("LyricsHelper").i("4.3.4 re-check of $id: $it") }
        } finally {
            rechecking.remove(id)
        }
    }

    private val retriedNotFound = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * A song stored as "no lyrics" is looked up again once per app run (a source may be back, the matching got better).
     * It counts as done only when the sources really answered: offline it is tried again later. True when lyrics came.
     */
    suspend fun retryNotFound(mediaMetadata: MediaMetadata): Boolean {
        val id = mediaMetadata.id
        if (id in retriedNotFound || !isOnline()) return false
        val outcome = lookup(mediaMetadata, forceRefresh = true)
        if (outcome !is LyricsLookup.NoConnection) retriedNotFound.add(id)
        return store.apply(id, outcome, LyricsStore.Mode.FILL) == LyricsStore.Result.STORED
    }

    /** "Try again" on the not-found screen: a lookup now, whatever was tried before. */
    suspend fun retryNow(mediaMetadata: MediaMetadata): LyricsStore.Result {
        forgetSources(mediaMetadata.id)
        val result = lookupAndStore(mediaMetadata, LyricsStore.Mode.FILL, forceRefresh = true)
        if (result != LyricsStore.Result.NO_CONNECTION) retriedNotFound.add(mediaMetadata.id)
        return result
    }

    /** One source for one song, for the picker: asked once, then answered from memory. A failed source is asked again. */
    suspend fun fetchSource(
        mediaMetadata: MediaMetadata,
        providerName: String,
    ): LyricsSources.Answer {
        val now = sourceFlow(mediaMetadata.id).value.answers[providerName]
        val state =
            if (now == LyricsSources.Answer.Loading) {
                // the automatic lookup is asking it right now: wait for that answer instead of asking twice
                withTimeoutOrNull(PER_PROVIDER_TIMEOUT_MS + 2_000L) {
                    sourceFlow(mediaMetadata.id).first { it.answers[providerName] != LyricsSources.Answer.Loading }
                }?.answers?.get(providerName)
            } else {
                now
            }
        when (state) {
            is LyricsSources.Answer.Found -> return state
            LyricsSources.Answer.Missing -> return LyricsSources.Answer.Missing
            else -> Unit
        }
        val provider = orderedProviders().firstOrNull { it.name == providerName } ?: return LyricsSources.Answer.Missing
        if (unavailableFor(mediaMetadata.id, provider)) return LyricsSources.Answer.Missing
        if (!isOnline()) return LyricsSources.Answer.Failed
        val request = requestOf(mediaMetadata)
        return singleFetches.await("${mediaMetadata.id}\u0000$providerName") {
            withTimeoutOrNull(PER_PROVIDER_TIMEOUT_MS + 2_000L) { ask(provider, request) } ?: LyricsSources.Answer.Failed
        }
    }

    /**
     * Asks, in the background, every ENABLED source that has not answered for this song yet (when the picker opens).
     * A source the user switched off is listed but only asked on a tap.
     */
    fun fetchAllSources(mediaMetadata: MediaMetadata) {
        fetchScope.launch {
            if (!isOnline()) return@launch
            val state = sourceFlow(mediaMetadata.id).value
            orderedProviders()
                .filter { p -> state.answers[p.name].let { it == null || it == LyricsSources.Answer.NotAsked || it == LyricsSources.Answer.Failed } }
                .filter { it.isEnabled(context) && !unavailableFor(mediaMetadata.id, it) }
                .forEach { provider -> launch { runCatching { fetchSource(mediaMetadata, provider.name) } } }
        }
    }

    /** What a tap on a source in the picker came to. */
    enum class PickResult {
        /** Shown and remembered for this song. */
        APPLIED,

        /** The source has lyrics of another version: nothing changed; the row now offers "Use anyway". */
        OTHER_VERSION,

        NOT_FOUND,
        NO_CONNECTION,
    }

    /**
     * The picker's tap on [providerName]: its lyrics are shown at once (they are kept per song) and the pick is
     * remembered. An answer the matcher rejected (another version) is only taken with [useAnyway], the explicit second
     * tap; before, a first tap applied and saved it (review 2026-11-12, finding 11).
     */
    suspend fun choose(
        mediaMetadata: MediaMetadata,
        providerName: String,
        useAnyway: Boolean,
    ): PickResult =
        fetchSource(mediaMetadata, providerName).let { answer ->
            val decision = decide(answer, useAnyway)
            if (decision == PickResult.APPLIED && answer is LyricsSources.Answer.Found) {
                val candidate = answer.candidate
                choices.set(mediaMetadata.id, providerName)
                store.put(mediaMetadata.id, candidate.lyrics, candidate.provider, byHand = false)
                recheckList.remove(mediaMetadata.id)
                foundCache.put(mediaMetadata.id, LyricsWithProvider(candidate.lyrics, candidate.provider))
            }
            decision
        }

    /** What "Automatic" in the picker came to. */
    enum class AutoResult {
        APPLIED,

        /** The automatic choice found nothing usable: the lyrics on screen and the pick stay. */
        NOTHING_FOUND,

        NO_CONNECTION,
    }

    /**
     * The automatic choice again, after the user went back to "Automatic" in the picker. The pick is dropped and the
     * row replaced only when the automatic choice has lyrics; offline or with nothing found, both stay (before, the
     * song's lyrics were replaced by "not found": review 2026-11-12, finding 2).
     */
    suspend fun automatic(mediaMetadata: MediaMetadata): AutoResult {
        val id = mediaMetadata.id
        val state = sourceFlow(id).value
        val enabled = orderedProviders().filter { it.isEnabled(context) && !unavailableFor(id, it) }
        val answers = enabled.map { state.answers[it.name] }
        val outcome =
            if (enabled.isNotEmpty() && answers.all { it is LyricsSources.Answer.Found || it == LyricsSources.Answer.Missing }) {
                val best = LyricsSelection.best(answers.filterIsInstance<LyricsSources.Answer.Found>().map { it.candidate })
                sourceFlow(id).update { it.copy(auto = best?.provider) }
                if (best == null) LyricsLookup.NotFound else LyricsLookup.Found(LyricsWithProvider(best.lyrics, best.provider))
            } else {
                fetches.await("$id\u0000auto") { fetch(mediaMetadata, forceRefresh = true, usePick = false) }
            }
        return when (outcome) {
            is LyricsLookup.Found -> {
                choices.set(id, null)
                foundCache.put(id, outcome.lyrics)
                store.apply(id, outcome, LyricsStore.Mode.REPLACE)
                AutoResult.APPLIED
            }
            LyricsLookup.NotFound -> AutoResult.NOTHING_FOUND
            LyricsLookup.NoConnection -> AutoResult.NO_CONNECTION
        }
    }

    /** The text the song had before its lyrics were replaced, or null. */
    suspend fun previousOf(mediaId: String): LyricsBackup.Saved? = withContext(Dispatchers.IO) { backup.read(mediaId) }

    /** Takes "Previous lyrics" back for the song: shown, remembered, and never replaced by a re-check. */
    suspend fun restorePrevious(mediaMetadata: MediaMetadata): Boolean {
        val id = mediaMetadata.id
        val saved = previousOf(id) ?: return false
        store.put(id, saved.text, saved.provider, byHand = true)
        LyricsHandMade.markNow(context, id)
        choices.set(id, LyricsSources.PREVIOUS)
        recheckList.remove(id)
        foundCache.remove(id)
        return true
    }

    /** A result from the lyrics search list, taken by hand (the text it replaces is kept as "Previous lyrics"). */
    suspend fun saveByHand(
        mediaId: String,
        lyrics: String,
        provider: String,
    ) {
        store.put(mediaId, lyrics, provider, byHand = true)
        LyricsHandMade.markNow(context, mediaId)
        recheckList.remove(mediaId)
        foundCache.remove(mediaId)
    }

    /** Forgets what the sources said for this song (Refetch). */
    fun forgetSources(mediaId: String) {
        foundCache.remove(mediaId)
        sourceFlow(mediaId).value = LyricsSources.State()
    }

    suspend fun getAllLyrics(
        mediaId: String,
        songTitle: String,
        songArtists: String,
        duration: Int,
        album: String? = null,
        callback: (LyricsResult) -> Unit,
    ) {
        currentLyricsJob?.cancel()

        val cacheKey = "$songArtists-$songTitle".replace(" ", "")
        cache.get(cacheKey)?.let { results ->
            results.forEach { callback(it) }
            return
        }

        if (!isOnline()) return

        val allResult = mutableListOf<LyricsResult>()
        currentLyricsJob = CoroutineScope(SupervisorJob()).launch {
            val cleanedTitle = LyricsUtils.cleanTitleForSearch(songTitle)
            val allProviders = orderedProviders()
            val enabledProviders = allProviders.filter { it.isEnabled(context) }

            val otherProviders = enabledProviders.filter { it.name != "LyricsPlus" }
            val lyricsPlusProvider = enabledProviders.find { it.name == "LyricsPlus" }

            val callbackMutex = Any()

            val otherJobs = otherProviders.map { provider ->
                launch {
                    try {
                        provider.getAllLyrics(context, mediaId, cleanedTitle, songArtists, duration, album) { lyrics ->
                            val filteredLyrics = LyricsUtils.filterLyricsCreditLines(lyrics)
                            val result = LyricsResult(provider.name, filteredLyrics)
                            synchronized(callbackMutex) {
                                allResult += result
                                callback(result)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (e !is IOException) reportException(e)
                    }
                }
            }
            otherJobs.forEach { it.join() }

            val otherLyricsCount = allResult.count { it.providerName != "LyricsPlus" }
            if (lyricsPlusProvider != null && otherLyricsCount <= 2) {
                launch {
                    try {
                        lyricsPlusProvider.getAllLyrics(context, mediaId, cleanedTitle, songArtists, duration, album) { lyrics ->
                            val filteredLyrics = LyricsUtils.filterLyricsCreditLines(lyrics)
                            val result = LyricsResult(lyricsPlusProvider.name, filteredLyrics)
                            synchronized(callbackMutex) {
                                allResult += result
                                callback(result)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (e !is IOException) reportException(e)
                    }
                }.join()
            }

            cache.put(cacheKey, allResult)
        }

        currentLyricsJob?.join()
    }

    private fun resolveLyricsProviders(preferences: androidx.datastore.preferences.core.Preferences): List<LyricsProvider> {
        val providerOrder = preferences[LyricsProviderOrderKey].orEmpty()
        if (providerOrder.isNotBlank()) {
            return LyricsProviderRegistry.getOrderedProviders(providerOrder)
        }

        return LyricsProviderRegistry.getDefaultProviderOrder()
            .mapNotNull { LyricsProviderRegistry.getProviderByName(it) }
    }

    companion object {
        // Found lyrics for the last songs: going back and forth in the queue does not ask the network again.
        private const val MAX_CACHE_SIZE = 20

        /**
         * What a tap on a source in the picker does with its answer: usable lyrics are applied; another version only
         * with [useAnyway] (the row's own "Use anyway"); nothing found and could-not-ask change nothing.
         */
        fun decide(
            answer: LyricsSources.Answer,
            useAnyway: Boolean,
        ): PickResult =
            when (answer) {
                is LyricsSources.Answer.Found ->
                    if (answer.candidate.usable || useAnyway) PickResult.APPLIED else PickResult.OTHER_VERSION
                LyricsSources.Answer.Missing -> PickResult.NOT_FOUND
                else -> PickResult.NO_CONNECTION
            }

        /** Pauses between lookups that could not reach the sources (the music service's wait for a song's lyrics). */
        private val RETRY_DELAYS_MS = longArrayOf(3_000L, 15_000L, 60_000L, 180_000L, 600_000L)
    }
}

data class LyricsResult(
    val providerName: String,
    val lyrics: String,
)

data class LyricsWithProvider(
    val lyrics: String,
    val provider: String,
)
