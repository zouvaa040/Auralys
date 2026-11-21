/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException

/**
 * What one lookup of a song's lyrics came to.
 *
 * Only [Found] and [NotFound] may be stored. [NoConnection] means the sources could not be asked (offline, DNS, timeouts,
 * busy servers): storing "no lyrics" then would keep the song without lyrics for good, even after the phone is back
 * online (review 2026-11-12, finding 1). 4.3.3 stored it.
 */
sealed interface LyricsLookup {
    data class Found(val lyrics: LyricsWithProvider) : LyricsLookup

    /** The sources answered and none had usable lyrics. */
    data object NotFound : LyricsLookup

    /** Too few sources could be reached to say there are no lyrics. */
    data object NoConnection : LyricsLookup

    companion object {
        /**
         * The outcome of a lookup that found nothing usable, from the answers of the sources that were to be asked. It
         * is a real "not found" only when at least half of them answered (found something, or said they have nothing);
         * when most of them could not be reached, nothing can be concluded. No source to ask at all is "not found".
         */
        fun withoutLyrics(answers: List<LyricsSources.Answer?>): LyricsLookup {
            if (answers.isEmpty()) return NotFound
            val answered = answers.count { it is LyricsSources.Answer.Found || it == LyricsSources.Answer.Missing }
            return if (answered > 0 && answered * 2 >= answers.size) NotFound else NoConnection
        }

        /**
         * True when [e] means the source could not be asked, not that it has nothing: a failure of the connection
         * (offline, DNS, refused, reset, TLS, timeout: all [IOException]s, Ktor's request timeout included), a timeout of
         * the provider's own, or a busy server (HTTP 5xx, 429). Looked for along the causes.
         */
        fun isNetworkFailure(e: Throwable): Boolean {
            var cause: Throwable? = e
            var depth = 0
            while (cause != null && depth < 8) {
                when {
                    cause is IOException -> return true
                    cause is TimeoutCancellationException -> return true
                    cause is ResponseException && cause.response.status.value.let { it >= 500 || it == 429 } -> return true
                }
                cause = cause.cause
                depth++
            }
            return false
        }

        /**
         * For the catch-all blocks of the providers: a cancellation and a failure to reach the source are passed on,
         * so the lookup sees them (a race that settled must not leave sources "not found", a dead connection must not
         * become "no lyrics"). Anything else is the provider's own miss.
         */
        fun rethrowIfNotAMiss(e: Throwable) {
            if (e is CancellationException || isNetworkFailure(e)) throw e
        }
    }
}
