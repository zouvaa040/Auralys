/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * What every lyrics source gave for one song, for the source picker in the lyrics view (idea from BitChord's lyrics
 * provider sheet, GPLv3, written again for Auralys). Plain data and pure functions, so the picker logic is unit tested.
 */
object LyricsSources {
    /** These look the song up by its YouTube video id, which a file from the phone does not have. */
    val VIDEO_ID_PROVIDERS = setOf("SimpMusic", "YouTube Subtitle", "YouTube Music", "Zemer")

    /** The saved choice of a song whose lyrics the user took back from "Previous lyrics" (see [LyricsBackup]). */
    const val PREVIOUS = "\u0000previous"

    sealed interface Answer {
        /** Not asked for this song yet (the automatic choice did not need it, or stopped waiting for it). */
        data object NotAsked : Answer

        data object Loading : Answer

        /** The source answered: it has nothing for this song. */
        data object Missing : Answer

        /** The source could not be reached (offline, timeout, busy server): asked again on a tap. */
        data object Failed : Answer

        data class Found(val candidate: LyricsSelection.Candidate) : Answer
    }

    /** [auto] is the source the automatic choice took, null when it found nothing or has not run. */
    data class State(
        val answers: Map<String, Answer> = emptyMap(),
        val auto: String? = null,
    ) {
        fun with(
            provider: String,
            answer: Answer,
        ): State = copy(answers = answers + (provider to answer))

        /** After the race: the ones it did not wait for were not really asked. */
        fun settleLoading(): State = copy(answers = answers.mapValues { (_, a) -> if (a == Answer.Loading) Answer.NotAsked else a })
    }

    /**
     * One source asked once for one song ([fetch] is the provider's lookup): the rated answer, also given to [record]
     * (Loading first). Could not be reached = [Answer.Failed], answered nothing = [Answer.Missing]. A cancelled ask (the
     * race settled, the song was skipped) is recorded [Answer.NotAsked] and the cancellation goes on, also when the
     * provider swallowed it in its own catch-all (`runCatching`): before, such sources read "Not found" in the picker
     * and could not be tapped (review 2026-11-12, finding 5).
     */
    internal suspend fun askOnce(
        provider: String,
        request: LyricsRequest,
        record: (Answer) -> Unit,
        fetch: suspend () -> Result<LyricsHit>,
    ): Answer {
        record(Answer.Loading)
        val answer =
            try {
                val result = fetch()
                currentCoroutineContext().ensureActive()
                val failure = result.exceptionOrNull()
                when {
                    // a timeout of the provider's own while this ask is still wanted
                    failure != null && (LyricsLookup.isNetworkFailure(failure) || failure is CancellationException) -> Answer.Failed
                    failure != null -> Answer.Missing
                    else -> {
                        val hit = result.getOrThrow().let { it.copy(text = LyricsUtils.filterLyricsCreditLines(it.text)) }
                        if (hit.text.isBlank()) Answer.Missing else Answer.Found(LyricsSelection.rate(provider, request, hit))
                    }
                }
            } catch (e: CancellationException) {
                // a timeout inside the provider while this ask is still wanted is a failure to reach it
                if (currentCoroutineContext().isActive) {
                    Answer.Failed
                } else {
                    record(Answer.NotAsked)
                    throw e
                }
            } catch (e: Exception) {
                if (LyricsLookup.isNetworkFailure(e)) Answer.Failed else Answer.Missing
            }
        record(answer)
        return answer
    }

    /** How one row of the picker reads. */
    enum class RowState {
        /** Lyrics found with a time per word. */
        WORD,

        /** Lyrics found with a time per line. */
        LINE,

        /** Lyrics found without timing. */
        PLAIN,

        /**
         * Lyrics found, but for another version of the song (other length or title): never taken by itself, and in the
         * picker only by the explicit "Use anyway" of that row.
         */
        OTHER_VERSION,
        LOADING,
        NOT_FOUND,

        /** Not asked yet, or could not be reached: a tap asks it. */
        IDLE,

        /** Cannot be asked for this song (a file from the phone and a source that needs a video id). */
        UNAVAILABLE,
    }

    data class Row(
        val provider: String,
        val state: RowState,
        val current: Boolean,
        val candidate: LyricsSelection.Candidate?,
    ) {
        /**
         * Rows a tap acts on: usable lyrics (switch) and sources not asked yet (ask). Another version is not switched to
         * by a tap on the row: only by its own "Use anyway" ([canUseAnyway]), so a rejected answer is never applied and
         * saved by accident (review 2026-11-12, finding 11).
         */
        val tappable: Boolean
            get() = !current && state in TAPPABLE

        /** Another version that the user may still take on purpose. */
        val canUseAnyway: Boolean
            get() = !current && state == RowState.OTHER_VERSION && candidate != null
    }

    private val TAPPABLE = setOf(RowState.WORD, RowState.LINE, RowState.PLAIN, RowState.IDLE)

    fun rowState(answer: Answer?): RowState =
        when (answer) {
            null, Answer.NotAsked, Answer.Failed -> RowState.IDLE
            Answer.Loading -> RowState.LOADING
            Answer.Missing -> RowState.NOT_FOUND
            is Answer.Found ->
                when {
                    answer.candidate.quality == LyricsSelection.Quality.REJECTED -> RowState.OTHER_VERSION
                    answer.candidate.sync == LyricsSelection.Sync.WORD -> RowState.WORD
                    answer.candidate.sync == LyricsSelection.Sync.LINE -> RowState.LINE
                    else -> RowState.PLAIN
                }
        }

    /**
     * The rows of the picker, in the user's provider order. [shownProvider] is the source of the lyrics on screen and
     * [choice] the source the user picked for this song (null: the automatic choice, whose row is then current). A row is
     * current only when it is both: a pick whose source failed (the automatic answer is shown instead) marks nothing.
     */
    fun rows(
        providers: List<String>,
        state: State,
        shownProvider: String?,
        choice: String?,
        unavailable: (String) -> Boolean,
    ): List<Row> =
        providers.map { name ->
            val answer = state.answers[name]
            Row(
                provider = name,
                state = if (unavailable(name)) RowState.UNAVAILABLE else rowState(answer),
                current = choice == name && name == shownProvider,
                candidate = (answer as? Answer.Found)?.candidate,
            )
        }

    /** True when the "Automatic" row is the one in use: no pick, or a pick that is not shown (it had nothing). */
    fun automaticIsCurrent(
        choice: String?,
        shownProvider: String?,
        previousShown: Boolean,
    ): Boolean =
        when (choice) {
            null -> !previousShown
            PREVIOUS -> !previousShown
            else -> choice != shownProvider && !previousShown
        }
}

/**
 * The source the user picked per song, kept in the preferences (no database change): "songId<TAB>provider" lines, the
 * newest last, at most [MAX] songs. A song with no line uses the automatic choice.
 */
class LyricsSourceChoices(
    private val context: Context,
) {
    val all: Flow<Map<String, String>> = context.dataStore.data.map { decode(it[KEY].orEmpty()) }

    suspend fun get(mediaId: String): String? = all.first()[mediaId]

    suspend fun set(
        mediaId: String,
        provider: String?,
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY] = encode(update(decode(prefs[KEY].orEmpty()), mediaId, provider))
        }
    }

    companion object {
        const val MAX = 400
        private val KEY = stringPreferencesKey("auralys_lyrics_source_choices")

        fun decode(text: String): LinkedHashMap<String, String> {
            val map = LinkedHashMap<String, String>()
            text.lineSequence().forEach { line ->
                val tab = line.indexOf('\t')
                if (tab > 0 && tab < line.length - 1) map[line.substring(0, tab)] = line.substring(tab + 1)
            }
            return map
        }

        fun encode(map: Map<String, String>): String = map.entries.joinToString("\n") { "${it.key}\t${it.value}" }

        /** Sets or (with null) removes the pick of one song; the newest goes last and the oldest fall off past [MAX]. */
        fun update(
            map: LinkedHashMap<String, String>,
            mediaId: String,
            provider: String?,
        ): LinkedHashMap<String, String> {
            val safeId = mediaId.replace('\t', ' ').replace('\n', ' ')
            map.remove(safeId)
            if (provider != null) map[safeId] = provider.replace('\t', ' ').replace('\n', ' ')
            while (map.size > MAX) map.remove(map.keys.first())
            return map
        }
    }
}
