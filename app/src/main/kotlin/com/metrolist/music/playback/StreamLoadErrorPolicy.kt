/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * ExoPlayer's load retries, with one change: a stream address the server REFUSES (401, 403, 404, 410) is not asked again.
 *
 * The default policy retried it three times (0, 1 and 2 s apart) with the same cached address, which can only be refused
 * again, before the service heard of it. Reported at once, the service gets a fresh address from another client and resumes
 * the song where it was (PlaybackRecovery). Everything else (a dropped connection, a timeout, a 5xx) keeps the default
 * retries, which are right for a short network hiccup.
 */
@UnstableApi
internal class StreamLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (isRefusal(PlaybackFailures.httpStatus(loadErrorInfo.exception))) return C.TIME_UNSET
        return super.getRetryDelayMsFor(loadErrorInfo)
    }

    internal companion object {
        fun isRefusal(status: Int?): Boolean = status == 401 || status == 403 || status == 404 || status == 410
    }
}
