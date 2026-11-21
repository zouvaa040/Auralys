/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * One small request that tells whether a fresh stream address serves the WHOLE song.
 *
 * Live harness 2026-11-12 (forced ANDROID_VR client, 2 songs): the address answered the first 1 MiB (two 512 KiB ranges,
 * about one minute of music) and then 403 for every later range, while a request without a range was refused at once.
 * googlevideo does that when the address' `n` value was not transformed correctly (a cipher that is out of date) or a token
 * is missing. In the app the song then played about a minute and stopped ("lagu belum selesai sudah di-skip", "berhenti
 * sendiri"). Asking for one byte at the end first costs one short request per NEW address and lets the resolver take
 * another client before anything is played.
 */
internal class StreamProbe(
    private val client: OkHttpClient,
) {
    /** The HTTP status of a one-byte range at [position], or null when the probe itself could not be made (network). */
    fun statusAt(
        url: String,
        headers: Map<String, String>,
        position: Long,
    ): Int? =
        try {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .apply { headers.forEach { (name, value) -> header(name, value) } }
                    .header("Range", "bytes=$position-$position")
                    .build()
            client.newCall(request).execute().use { it.code }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    internal companion object {
        /** Streams up to this size are served whole even with a bad `n`: nothing to check. */
        const val SERVED_WITHOUT_CHECK_BYTES = 1_100_000L

        /** The byte to ask for: the last one of the stream, or null when the stream is short or its size unknown. */
        fun positionFor(contentLength: Long?): Long? = contentLength?.takeIf { it > SERVED_WITHOUT_CHECK_BYTES }?.minus(1)
    }
}
