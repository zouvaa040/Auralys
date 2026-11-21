/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

/**
 * A YouTube title as a lyrics database would have it indexed (idea from BitChord, GPLv3).
 *
 * Only credits and labels are taken off: "(feat. X)", "ft. X", "(Official Video)", "[Lyrics]", "(Remastered 2011)",
 * "(Bonus Track)", "(From "Film")", " - 2011 Remaster". Brackets that name another recording, such as "(Live)",
 * "(Remix)", "(Sped Up)" or "(Japanese Ver.)", stay, because dropping them turns a search for one recording into a
 * search for another and the lyrics that come back are confidently wrong. What counts as a label and what as a version
 * is decided in one place, [LyricsHitMatch.isNeutralPart], so the query and the match check always agree.
 */
internal object LyricsQuery {
    private val WHITESPACE = Regex("""\s+""")

    /**
     * Round, square and the full-width brackets (（）【】［］〔〕). The Japanese quotes 「」『』 are not brackets here: they
     * often sit inside one ("(映画「X」主題歌)"), and the text compare drops them anyway.
     */
    const val OPEN = "(\\[（【［〔"
    const val CLOSE = ")\\]）】］〕"

    /** One bracket and its text. */
    val BRACKET = Regex("""[$OPEN][^$CLOSE]*[$CLOSE]""")

    private val SPACE_BEFORE_CLOSE = Regex("""\s+([$CLOSE])""")
    private val SPACE_AFTER_OPEN = Regex("""([$OPEN])\s+""")

    private val CREDITS =
        listOf(
            // (feat. X), [ft. X], (with X), （feat. X）
            Regex("""\s*[$OPEN]\s*(feat|ft|featuring|with)\b[^$CLOSE]*[$CLOSE]""", RegexOption.IGNORE_CASE),
            // feat. X up to the next bracket, the end of this one, the next " - " part or the end: "Song ft. X (Remix)"
            // keeps "(Remix)", "(Remix feat. X)" is "(Remix)"
            Regex("""\s+(feat|ft|featuring)\.?\s+[^$OPEN$CLOSE]+?(?=\s*[$OPEN]|[$CLOSE]|\s+-\s+|$)""", RegexOption.IGNORE_CASE),
        )

    fun title(raw: String): String {
        var name = raw
        CREDITS.forEach { name = it.replace(name, " ") }
        // labels of the upload or the release, in brackets
        name = BRACKET.replace(name) { m -> if (LyricsHitMatch.isNeutralPart(m.value)) " " else m.value }
        // and as " - " parts at the end ("Song - 2011 Remaster", "Song - Official Video"); a first part is never one
        val parts = name.split(" - ").toMutableList()
        while (parts.size > 1 && LyricsHitMatch.isNeutralPart(parts.last())) parts.removeAt(parts.lastIndex)
        name = parts.joinToString(" - ")
        return name
            .replace(WHITESPACE, " ")
            .replace(SPACE_BEFORE_CLOSE, "$1")
            .replace(SPACE_AFTER_OPEN, "$1")
            .trim()
            .trimEnd(',', '-', '–', '—', '|')
            .trim()
            .ifBlank { raw.trim() }
    }

    /** YouTube's auto-generated artist channels are named "Artist - Topic". */
    fun artist(raw: String): String = raw.removeSuffix(" - Topic").trim().ifBlank { raw.trim() }
}
