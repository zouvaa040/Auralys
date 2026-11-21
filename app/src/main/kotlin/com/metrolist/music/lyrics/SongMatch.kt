/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

/**
 * Whether a lyrics hit is for the song that is playing. A length that is close is not enough: KuGou and LrcLib both return
 * other songs of a similar length, and their words would then be sung over the wrong track.
 */
internal object SongMatch {
    private val notLetters = Regex("""[^\p{L}\p{N}]+""")
    private val artistSeparators = Regex("""[,、&;/]| and | feat\.? | ft\.? | x """, RegexOption.IGNORE_CASE)

    fun normalize(text: String): String = text.lowercase().replace(notLetters, " ").trim()

    /** True for Latin script (including accents). Names in different scripts, such as 周杰伦 and Jay Chou, cannot be compared as text. */
    private fun isLatin(text: String): Boolean = text.none { it.isLetter() && it.code > 0x24F }

    /** [found] is what the source calls the song; true when it is [wanted], or when the two cannot be compared. */
    fun sameTitle(
        found: String?,
        wanted: String,
    ): Boolean {
        if (found.isNullOrBlank()) return true
        val a = normalize(found)
        val b = normalize(wanted)
        if (a.isEmpty() || b.isEmpty() || isLatin(found) != isLatin(wanted)) return true
        return a.contains(b) || b.contains(a)
    }

    /** [found] is what the source calls the singer; any one of the artists in [wanted] being in it is enough. */
    fun sameArtist(
        found: String?,
        wanted: String,
    ): Boolean {
        if (found.isNullOrBlank() || wanted.isBlank()) return true
        if (isLatin(found) != isLatin(wanted)) return true
        val a = normalize(found)
        if (a.isEmpty()) return true
        val names = wanted.split(artistSeparators).map { normalize(it) }.filter { it.isNotEmpty() }
        if (names.isEmpty() || names.any { a.contains(it) || it.contains(a) }) return true
        // "G.E.M. 邓紫棋" (KuGou, simplified) and "G.E.M. 鄧紫棋" (YouTube, traditional): the same Latin name on both sides
        val latinFound = latinLetters(found)
        return latinFound.length >= 2 && wanted.split(artistSeparators).any { latinLetters(it) == latinFound }
    }

    /** The Latin letters of a name that also has other script in it, lower case and joined ("G.E.M. 鄧紫棋" gives "gem"). */
    private fun latinLetters(name: String): String =
        if (isLatin(name)) "" else name.lowercase().filter { it in 'a'..'z' }

    fun sameSong(
        foundTitle: String?,
        foundArtist: String?,
        wantedTitle: String,
        wantedArtist: String,
    ): Boolean = sameTitle(foundTitle, wantedTitle) && sameArtist(foundArtist, wantedArtist)
}
