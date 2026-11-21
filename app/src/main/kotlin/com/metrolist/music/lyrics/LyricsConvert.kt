/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import com.metrolist.music.betterlyrics.TTMLParser

internal object LyricsConvert {
    /** Apple style TTML (word or line timing) to the app's LRC text; null when nothing could be read. */
    fun ttmlToLrc(ttml: String): String? {
        val lines = TTMLParser.parseTTML(ttml)
        if (lines.isEmpty()) return null
        return TTMLParser.toLRC(lines).trim().takeIf { it.isNotBlank() }
    }

    private val BODY_DUR = Regex("""<body\b[^>]*\bdur\s*=\s*"([^"]+)"""")

    /**
     * The length of the recording an Apple style TTML document was timed for: `<body dur="3:28.511">`. The lyrics of
     * "Dracula" (3:25) and "Dracula (feat. JENNIE)" (3:29) are two documents; this is how the right one is told apart.
     * Null when the document does not say.
     */
    fun ttmlDurationSeconds(ttml: String): Double? =
        BODY_DUR.find(ttml.take(64 * 1024))?.groupValues?.get(1)?.let { clockSeconds(it) }

    /** "3:28.511", "01:03:28.5", "208.511s", "208.5" in seconds; null for anything else. */
    fun clockSeconds(value: String): Double? {
        val v = value.trim()
        if (v.isEmpty()) return null
        if (v.endsWith("ms")) return v.removeSuffix("ms").toDoubleOrNull()?.div(1000.0)
        if (v.endsWith("s")) return v.removeSuffix("s").toDoubleOrNull()
        val parts = v.split(':')
        if (parts.size > 3) return null
        var total = 0.0
        for (part in parts) {
            val n = part.toDoubleOrNull() ?: return null
            if (n < 0) return null
            total = total * 60.0 + n
        }
        return total
    }
}
