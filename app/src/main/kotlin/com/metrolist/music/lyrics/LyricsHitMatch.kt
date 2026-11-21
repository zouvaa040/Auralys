/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Decides whether a search hit of lyrics provider is the recording that is playing.
 *
 * Idea from BitChord's lrc.red matcher (GPLv3), written again for Auralys: a miss is harmless because the
 * next provider gets its turn, but the wrong words scrolling in time with the right song are not. So
 * every rule here is a requirement, not a score:
 *  - the title outside brackets and any " - " suffix is the same;
 *  - the words that name a different recording (live, remix, acoustic, cover, "Japanese Ver."...) are the same on both
 *    sides; labels of the release or the upload (Remastered 2011, Bonus Track, Explicit, Deluxe, Official Video, Lyric
 *    Video, From "Film"...) are ignored; guests are checked by [guestConflict] / [guestMissing], not here;
 *  - the artist matches (see [SongMatch.sameArtist]);
 *  - the length is within the tolerance, when both lengths are known.
 */
internal object LyricsHitMatch {
    private val COMBINING_MARKS = Regex("""\p{Mn}+""")
    private val WHITESPACE = Regex("""\s+""")
    // \p{script=Han}, not the JDK spelling \p{IsHan}: Android 8 refuses IsHan (PatternSyntaxException in this object's init)
    private val LATIN_HAN = Regex("""(?<=[a-z0-9])(?=\p{script=Han})|(?<=\p{script=Han})(?=[a-z0-9])""")

    /** Words that make a title another recording. A bare "version" is not one of them (see [GENERIC]). */
    private val VERSION_WORDS =
        setOf(
            "live", "remix", "remixed", "rmx", "mix", "mixed", "acoustic", "unplugged", "instrumental", "inst", "karaoke",
            "cappella", "acapella", "demo", "edit", "cover", "sped", "slowed", "reverb", "nightcore", "lofi", "orchestral",
            "extended", "session", "sessions", "stripped", "piano", "alternate", "rework", "reprise", "chopped", "screwed",
            "mashup", "bootleg", "akustik", "8d", "boosted", "remake",
            // Chinese: accompaniment (instrumental), live, cover
            "伴奏", "现场", "現場", "翻唱",
        )

    /** One word for the different spellings of the same version. */
    private val SAME_AS =
        mapOf(
            "speed" to "sped", "spedup" to "sped", "rmx" to "remix", "remixed" to "remix", "akustik" to "acoustic",
            "acapella" to "cappella", "inst" to "instrumental", "伴奏" to "instrumental", "现场" to "live", "現場" to "live",
            "翻唱" to "cover", "vivo" to "live",
        )

    /** Words that only say "a version": "Acoustic Version" is the acoustic one, "Japanese Ver." the Japanese one. */
    private val GENERIC = setOf("version", "versi", "ver", "versao", "versione", "版")

    /**
     * Words that label the release or the upload, never the recording. A part made of these (and of [WEAK] words and
     * numbers) is a label; it must hold at least one of these to be taken off the search title.
     */
    private val LABEL_WORDS =
        setOf(
            "remaster", "remastered", "remasterizado", "remasterizada", "remasterise", "remasterised", "remasterized",
            "explicit", "clean", "dirty", "censored", "uncensored", "deluxe", "edition", "bonus", "official", "oficial",
            "audio", "video", "videoclip", "clip", "lyric", "lyrics", "lirik", "letra", "visualizer", "visualiser", "mv",
            "hd", "hq", "4k", "mono", "stereo", "album", "single", "lp", "ep", "anniversary", "expanded", "ost",
            "soundtrack",
        )

    /** Small words that may sit in a label or a version part without making it anything. */
    private val WEAK =
        setOf(
            "the", "a", "an", "of", "and", "by", "at", "in", "on", "for", "to", "full", "song", "music", "track", "original",
            "main", "special", "standard", "digital", "new", "m", "v",
        )

    /** Whole parts that name the usual recording ("Original Mix" is the mix everyone means). */
    private val NEUTRAL_PHRASES = setOf("original mix", "original version", "album mix", "main mix", "original")

    private val GUEST_TAIL = Regex("""\b(feat|ft|featuring|with)\b.*$""")

    /** Lower case, accents off, only letters and digits. */
    fun normalized(value: String): String =
        Normalizer
            .normalize(value, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .replace(LATIN_HAN, " ")
            .replace(WHITESPACE, " ")
            .trim()

    /** The words of a part, a Chinese "...版" split into the word and the "version" sign. */
    private fun words(segment: String): List<String> =
        normalized(segment)
            .split(' ')
            .filter { it.isNotEmpty() }
            .flatMap { if (it.length > 1 && it.endsWith("版")) listOf(it.dropLast(1), "版") else listOf(it) }

    /** Years and counts: "2011", "50th", "90s". */
    private val NUMBER = Regex("""^\d+(st|nd|rd|th|s)?$""")

    private fun isLabelWord(word: String): Boolean = word in LABEL_WORDS || word in WEAK || word in GENERIC || NUMBER.matches(word)

    /**
     * True when a bracket or a " - " part only labels the release or the upload: "(Remastered 2011)", "[Official Video]",
     * "(Bonus Track)", "(Album Version)", "(From "Barbie")", "[Prod. X]", "- 2011 Remaster". A part that names a version
     * ("(Live)", "(Japanese Ver.)", "(Remix)"), a guest or anything else is not.
     */
    fun isNeutralPart(part: String): Boolean {
        val w = words(part)
        if (w.isEmpty()) return true
        if (w.first() == "from" || w.first() == "prod" || (w.first() == "taken" && w.getOrNull(1) == "from")) return true
        if (w.joinToString(" ") in NEUTRAL_PHRASES || w.joinToString("") in LABEL_WORDS) return true
        return w.all(::isLabelWord) && w.any { it in LABEL_WORDS }
    }

    /** "Song (Live) - 2011 Remaster" gives "song". */
    fun core(title: String): String =
        normalized(LyricsQuery.BRACKET.replace(title, " ").substringBefore(" - ")).ifEmpty { normalized(title) }

    /** The version words of the brackets and " - " parts of a title ("Song (Acoustic Version)" gives {acoustic}). */
    fun versionWords(title: String): Set<String> {
        val clean = LyricsQuery.title(title)
        val brackets = LyricsQuery.BRACKET.findAll(clean).flatMap { phraseIn(it.value) }.toSet()
        val parts = LyricsQuery.BRACKET.replace(clean, " ").split(" - ")
        return brackets + parts.drop(1).flatMap { phraseIn(it) }
    }

    private fun isLatin(text: String): Boolean = text.none { it.isLetter() && it.code > 0x24F }

    /**
     * A way to read a title: the text that is compared and the words of its version parts, which must be equal on both
     * sides. A version part is a bracket or a " - " part with a version word in it, or with "version"/"ver." and a name
     * ("Japanese Ver."); all its words count ("DNMO Remix" is not "Remix"), minus labels ("Explicit", years) and the bare
     * "version". Guests in it ("Remix feat. X") do not count.
     */
    private data class Reading(val text: String, val words: Set<String>)

    private fun phraseIn(segment: String): Set<String> {
        val raw = words(normalized(segment).replace(GUEST_TAIL, " "))
        if (raw.isEmpty()) return emptySet()
        if (raw.first() == "from" || raw.first() == "prod") return emptySet()
        if (raw.joinToString(" ") in NEUTRAL_PHRASES) return emptySet()
        val w = raw.map { SAME_AS[it] ?: it }
        val hasVersion = w.any { it in VERSION_WORDS }
        val named = w.filter { it !in GENERIC && !isLabelWord(it) && it.any(Char::isLetter) }
        val hasGeneric = w.any { it in GENERIC }
        if (!hasVersion && !(hasGeneric && named.isNotEmpty())) return emptySet()
        val result = named.toMutableSet()
        // "Sped Up" and "Spedup", "Slowed" and "Slowed + Reverb" / "Slowed Down": the length check decides between them
        if ("sped" in result) result -= "up"
        if ("slowed" in result) {
            result -= "reverb"
            result -= "down"
        }
        return result
    }

    /**
     * Every sensible reading of a title. "Song - 2011 Remaster" is read as "song" (the suffix is only a label), and
     * "Artist - Song" (the usual shape of a video name) is read as "song" too, as "artist", and as the whole.
     * The version words of the other parts and of the brackets always stay attached to the reading. A title that is
     * only a bracket ("(Interlude)") is read by its bracket text.
     */
    private fun readings(rawTitle: String): List<Reading> {
        // credits and labels off first: "Song ft. X", "Song (Remastered Version)" are "Song"
        val title = LyricsQuery.title(rawTitle)
        val bracketWords = LyricsQuery.BRACKET.findAll(title).flatMap { phraseIn(it.value) }.toSet()
        val parts = LyricsQuery.BRACKET.replace(title, " ").split(" - ")
        val result = ArrayList<Reading>()
        if (parts.size == 1) {
            result += Reading(normalized(parts[0]), bracketWords)
        } else {
            val partWords = parts.map { phraseIn(it) }
            parts.forEachIndexed { i, part ->
                val others = partWords.filterIndexed { j, _ -> j != i }.flatten().toSet()
                result += Reading(normalized(part), bracketWords + others)
            }
            result += Reading(normalized(parts.joinToString(" ")), bracketWords + partWords.flatten())
        }
        val readable = result.filter { it.text.isNotEmpty() }
        return readable.ifEmpty { listOf(Reading(normalized(title), emptySet())).filter { it.text.isNotEmpty() } }
    }

    fun sameTitle(
        hitTitle: String,
        wantedTitle: String,
    ): Boolean {
        val hit = readings(hitTitle)
        val wanted = readings(wantedTitle)
        if (hit.isEmpty() || wanted.isEmpty()) return false
        // names in different scripts cannot be compared as text: the length check has to decide
        if (isLatin(hitTitle) != isLatin(wantedTitle)) return true
        return hit.any { a -> wanted.any { b -> a == b } }
    }

    /**
     * True when the hit is the wanted recording. [hitSeconds] may be null (unknown, passes);
     * [wantedSeconds] of zero or less means the length of the playing song is unknown.
     */
    fun isSameRecording(
        hitTitle: String?,
        hitArtist: String?,
        hitSeconds: Double?,
        wantedTitle: String,
        wantedArtist: String,
        wantedSeconds: Int,
        toleranceSeconds: Double,
    ): Boolean {
        if (hitTitle.isNullOrBlank()) return false
        if (!sameTitle(hitTitle, wantedTitle)) return false
        if (!SongMatch.sameArtist(hitArtist, wantedArtist)) return false
        return gap(hitSeconds, wantedSeconds) <= toleranceSeconds
    }

    private val FEAT_BRACKET =
        Regex("""[${LyricsQuery.OPEN}]\s*(?:feat\.?|ft\.?|featuring|with)\s+([^${LyricsQuery.CLOSE}]+)[${LyricsQuery.CLOSE}]""", RegexOption.IGNORE_CASE)
    private val FEAT_TAIL =
        Regex(
            """\s(?:feat\.?|ft\.?|featuring)\s+([^${LyricsQuery.OPEN}${LyricsQuery.CLOSE}]+?)(?=\s*[${LyricsQuery.OPEN}]|[${LyricsQuery.CLOSE}]|\s+-\s+|$)""",
            RegexOption.IGNORE_CASE,
        )
    private val NAME_SEPARATORS = Regex("""\s*(?:,|&|/|;|\band\b|\bx\b|\bwith\b)\s*""", RegexOption.IGNORE_CASE)

    /** The guests a title names: "Dracula (feat. JENNIE)" gives {"jennie"}; "Song ft. A & B" gives {"a", "b"}. */
    fun featuredNames(title: String): Set<String> {
        val found = FEAT_BRACKET.findAll(title).map { it.groupValues[1] } + listOfNotNull(FEAT_TAIL.find(title.replace(FEAT_BRACKET, " "))?.groupValues?.get(1))
        return found
            .flatMap { it.split(NAME_SEPARATORS) }
            .map { normalized(it) }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /**
     * True when the hit names a guest that the playing song does not have anywhere (its raw title or its artists): then
     * it is another recording ("Dracula (feat. JENNIE)" is not "Dracula"). A hit that names no guest is not a conflict.
     */
    fun guestConflict(
        hitTitle: String?,
        hitArtist: String?,
        wantedRawTitle: String,
        wantedArtist: String,
    ): Boolean {
        if (hitTitle.isNullOrBlank()) return false
        val guests = featuredNames(hitTitle)
        if (guests.isEmpty()) return false
        val wanted = normalized("$wantedRawTitle $wantedArtist")
        if (!isLatin(wanted) || guests.any { !isLatin(it) }) return false
        return guests.any { !wanted.contains(it) }
    }

    /**
     * True when the playing song names a guest ("feat. JENNIE") and the hit does not mention that guest in its title or
     * artist. Such a hit may still be the right recording (many databases leave guests out), so only a close length
     * may vouch for it.
     */
    fun guestMissing(
        hitTitle: String?,
        hitArtist: String?,
        wantedRawTitle: String,
    ): Boolean {
        val guests = featuredNames(wantedRawTitle)
        if (guests.isEmpty()) return false
        val hit = normalized("${hitTitle.orEmpty()} ${hitArtist.orEmpty()}")
        if (hit.isBlank()) return true
        return guests.none { hit.contains(it) }
    }

    /** Difference in seconds; zero when either length is unknown. */
    fun gap(
        hitSeconds: Double?,
        wantedSeconds: Int,
    ): Double = if (hitSeconds == null || wantedSeconds <= 0) 0.0 else abs(hitSeconds - wantedSeconds)
}
