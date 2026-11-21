/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

/**
 * Round 4.3.5: the last resort when no client gives an address for a song (what other open-source YouTube Music players
 * do as well). The same song is looked for among the OFFICIAL uploads of YouTube (the artist's "Official Audio" or video
 * upload, or another YouTube Music track), and only taken when it is clearly the same recording:
 *  - the uploaded title contains the song title (without its brackets, e.g. "(feat. ...)");
 *  - the artist matches (the uploader or a name in the title);
 *  - the length is within max(3 s, 2 %) of the song's length (a music video with an intro is refused);
 *  - no word of another version that the song title does not have (live, remix, cover, sped up, slowed, acoustic, ...);
 *  - official uploads only (MUSIC_VIDEO_TYPE_OMV / ATV): never a fan upload.
 * No regex (Android's ICU regex differs from the JVM's, see AndroidRegexGuardTest).
 */
object AlternativeMatch {
    class Song(
        val videoId: String,
        val title: String,
        val artists: List<String>,
        val durationSeconds: Int?,
    )

    class Candidate(
        val videoId: String,
        val title: String,
        val artists: List<String>,
        val durationSeconds: Int?,
        val musicVideoType: String?,
    )

    private val OFFICIAL_TYPES = setOf("MUSIC_VIDEO_TYPE_OMV", "MUSIC_VIDEO_TYPE_ATV")

    private val OTHER_VERSION_WORDS =
        setOf(
            "live", "remix", "rmx", "cover", "karaoke", "instrumental", "sped", "slowed", "nightcore", "reverb", "acoustic",
            "mix", "dj", "extended", "mashup", "medley", "loop", "hour", "8d", "reaction", "tutorial", "minus",
        )

    fun pick(
        song: Song,
        candidates: List<Candidate>,
    ): Candidate? {
        val length = song.durationSeconds?.takeIf { it > 0 } ?: return null
        val coreTitle = normalize(withoutBrackets(song.title)).ifBlank { normalize(song.title) }
        if (coreTitle.isBlank()) return null
        val titleWords = normalize(song.title).split(' ').toSet()
        val artists = song.artists.map(::normalize).filter(String::isNotBlank)
        if (artists.isEmpty()) return null
        val tolerance = maxOf(3, (length * 0.02).toInt())
        return candidates
            .asSequence()
            .filter { it.videoId != song.videoId && it.videoId.isNotBlank() }
            .filter { it.musicVideoType in OFFICIAL_TYPES }
            .filter { c -> c.durationSeconds?.let { kotlin.math.abs(it - length) <= tolerance } == true }
            .filter { c -> containsPhrase(normalize(c.title), coreTitle) }
            .filter { c ->
                val where = normalize(c.title + " " + c.artists.joinToString(" "))
                artists.any { containsPhrase(where, it) }
            }.filter { c -> normalize(c.title).split(' ').none { it in OTHER_VERSION_WORDS && it !in titleWords } }
            .minByOrNull { c -> kotlin.math.abs((c.durationSeconds ?: 0) - length) * 10 + if (c.musicVideoType == "MUSIC_VIDEO_TYPE_ATV") 0 else 1 }
    }

    /** Lower case, letters and digits only, single spaces. */
    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        var space = true
        for (ch in text.lowercase()) {
            if (ch.isLetterOrDigit()) {
                out.append(ch)
                space = false
            } else if (!space) {
                out.append(' ')
                space = true
            }
        }
        return out.toString().trim()
    }

    /** The text without its parts in (), [] or full-width brackets. */
    fun withoutBrackets(text: String): String {
        val out = StringBuilder(text.length)
        var depth = 0
        for (ch in text) {
            when (ch) {
                '(', '[', '（', '【' -> depth++
                ')', ']', '）', '】' -> if (depth > 0) depth--
                else -> if (depth == 0) out.append(ch)
            }
        }
        return out.toString()
    }

    /** [phrase] appears in [text] as whole words (both normalized). */
    private fun containsPhrase(
        text: String,
        phrase: String,
    ): Boolean = " $text ".contains(" $phrase ")
}
