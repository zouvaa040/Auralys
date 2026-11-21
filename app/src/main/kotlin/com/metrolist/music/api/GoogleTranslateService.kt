/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Keyless lyrics translation through Google Translate's public web endpoint, so translating works out of the box without
 * the user pasting an API key. Each lyric line is translated on its own (a handful in parallel), which keeps the output
 * lined up 1:1 with the lyrics, and a failed line falls back to the original text instead of failing the whole song.
 */

package com.metrolist.music.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

object GoogleTranslateService {
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

    private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"

    private fun languageCode(code: String): String =
        when (code.lowercase()) {
            "zh", "zh-cn", "zh-hans" -> "zh-CN"
            "zh-tw", "zh-hant" -> "zh-TW"
            "he" -> "iw"
            else -> code
        }

    /**
     * @param transliterate when true the result is the source line written in Latin letters (romanisation) instead of a
     * translation; lines that are already Latin come back unchanged.
     */
    suspend fun translate(
        text: String,
        targetLanguage: String,
        transliterate: Boolean = false,
    ): Result<List<String>> =
        withContext(Dispatchers.IO) {
            if (text.isBlank()) return@withContext Result.failure(Exception("Input text is empty"))
            val lines = text.lines()
            val gate = Semaphore(6)
            val failed = java.util.concurrent.atomic.AtomicInteger(0)
            try {
                val out =
                    coroutineScope {
                        lines
                            .map { line ->
                                async {
                                    if (line.isBlank()) {
                                        line
                                    } else {
                                        gate.withPermit {
                                            fetchLine(line, targetLanguage, transliterate) ?: run {
                                                failed.incrementAndGet()
                                                line
                                            }
                                        }
                                    }
                                }
                            }.awaitAll()
                    }
                // every single line failing means we are offline or blocked, so say so rather than echoing the lyrics
                if (failed.get() > 0 && failed.get() >= lines.count { it.isNotBlank() }) {
                    Result.failure(Exception("Translation service unreachable"))
                } else {
                    Result.success(out)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private suspend fun fetchLine(
        line: String,
        targetLanguage: String,
        transliterate: Boolean,
    ): String? {
        val url =
            ENDPOINT
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("client", "gtx")
                .addQueryParameter("sl", "auto")
                .addQueryParameter("tl", languageCode(targetLanguage))
                .addQueryParameter("dt", "t")
                .addQueryParameter("dt", "rm")
                .addQueryParameter("q", line)
                .build()
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        repeat(3) { attempt ->
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        return parse(body, line, transliterate)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            delay(300L * (attempt + 1))
        }
        return null
    }

    private fun parse(
        body: String,
        original: String,
        transliterate: Boolean,
    ): String? {
        val segments = JSONArray(body).optJSONArray(0) ?: return null
        val translated = StringBuilder()
        var romanised: String? = null
        for (i in 0 until segments.length()) {
            val seg = segments.optJSONArray(i) ?: continue
            if (!seg.isNull(0)) {
                translated.append(seg.optString(0))
            } else if (seg.length() > 3 && !seg.isNull(3)) {
                romanised = seg.optString(3)
            }
        }
        return if (transliterate) {
            romanised?.takeIf { it.isNotBlank() } ?: original
        } else {
            translated.toString().trim().ifBlank { null }
        }
    }
}
