/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Asks the Auralys download page which Android version is the newest, so the app can say "there is an update" and send the
 * user to that page. Works in every build flavor (the GitHub updater of the gms flavor is a separate thing).
 */

package com.metrolist.music.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object WebUpdate {
    const val PAGE = "https://auralys.my.id/"

    private val fileName = Regex("""Auralys-(\d+(?:\.\d+){1,3})\.apk""", RegexOption.IGNORE_CASE)
    private val label = Regex("""Android\s*</span>\s*<span[^>]*>\s*v?(\d+(?:\.\d+){1,3})""", RegexOption.IGNORE_CASE)

    /** Compares dotted versions part by part, so 4.3.10 is newer than 4.3.9. */
    fun compare(
        a: String,
        b: String,
    ): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /** The newest Android version the page mentions (the "Android v4.3.2" label and the .apk link name), or null. */
    fun parseAndroid(html: String): String? = (fileName.findAll(html) + label.findAll(html)).map { it.groupValues[1] }.maxWithOrNull(::compare)

    /** The newest Android version the page lists, or null when the page cannot be read (offline, blocked, changed). */
    suspend fun latest(): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL(PAGE).openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 12000
                connection.setRequestProperty("User-Agent", AuralysIdentity.userAgent)
                try {
                    if (connection.responseCode !in 200..299) return@runCatching null
                    parseAndroid(connection.inputStream.bufferedReader().use { it.readText() })
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
        }

    /** The version on the page when it is newer than [current]; null when up to date, offline or unreadable. */
    suspend fun newerThan(current: String): String? = latest()?.takeIf { compare(it, current) > 0 }
}
