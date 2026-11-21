/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0
 *
 * The idea (identity = title proper + version markers, packaging thrown away, shared artist required,
 * duration only ranks) follows BitChord's TrackMatcher (GPLv3); the code is written for Auralys.
 */

package com.metrolist.music.version

import com.metrolist.music.utils.PlaylistLinkParser
import java.text.Normalizer
import kotlin.math.abs

/** What the matcher needs to know about one recording. */
data class TrackInfo(
    val id: String,
    val title: String,
    val artists: List<String>,
    val durationSec: Int?,
    /** A music video or any other video upload (not the catalogue audio with the square cover). */
    val isVideo: Boolean,
    /** The artist's own music video (musicVideoType OMV), preferred over other uploads. */
    val isOfficialVideo: Boolean = false,
    val isEpisode: Boolean = false,
)

/**
 * Decides whether two uploads are the same recording: exact title proper, the same version markers
 * (live, remix, slowed ...), a shared artist, and a length within [MAX_DURATION_DIFF_SEC]. Pure, no network.
 */
object TrackMatcher {
    /** Beyond this the other upload is another take (a long intro, a mix, a re-recording). */
    const val MAX_DURATION_DIFF_SEC = 15

    /** A lyric video of a fan channel must be this close in length to be taken for the same recording. */
    const val UGC_MAX_DIFF_SEC = 4

    /** [core] is the title proper, [versions] the markers that mean a different take. */
    data class TitleParts(
        val core: String,
        val versions: Set<String>,
    )

    private val BRACKET = Regex("[(\\[{]([^)\\]}]*)[)\\]}]")
    private val SEGMENT_SPLIT = Regex("\\s+[-\u2013\u2014]\\s+|\\s*\\|\\s*")
    private val MARKS = Regex("\\p{Mn}+")
    private val WORD_SPLIT = Regex("[^\\p{L}\\p{N}]+")
    private val TOPIC = Regex("\\s*-\\s*topic$")

    /** Words that mean a different take of the song (token -> canonical marker). */
    private val VERSION_WORDS =
        mapOf(
            "live" to "live",
            "remix" to "remix",
            "remixed" to "remix",
            "instrumental" to "instrumental",
            "acoustic" to "acoustic",
            "akustik" to "acoustic",
            "slowed" to "slowed",
            "reverb" to "reverb",
            "spedup" to "spedup",
            "nightcore" to "nightcore",
            "8d" to "8d",
            "karaoke" to "karaoke",
            "cover" to "cover",
            "lofi" to "lofi",
            "unplugged" to "unplugged",
            "demo" to "demo",
            "mashup" to "mashup",
            "reprise" to "reprise",
            "bassboosted" to "bassboosted",
            "koplo" to "koplo",
            "dj" to "dj",
            "extended" to "extended",
        )

    /** Packaging around a title: it says what kind of upload this is, not which song. */
    private val PACKAGING_WORDS =
        setOf(
            "official", "music", "video", "videoclip", "clip", "lyric", "lyrics", "lirik", "audio", "mv", "hd", "hq",
            "4k", "visualizer", "visualiser", "full", "resmi", "oficial", "officiel", "original", "version", "versi",
            "explicit", "clean", "remastered", "remaster", "topic", "terbaru", "new", "mix", "edit", "radio", "single",
            "album", "song", "lagu", "with", "on", "screen",
        )

    private val CREDIT_WORDS = setOf("feat", "ft", "featuring", "prod", "from")

    private fun preNormalize(text: String): String =
        Normalizer
            .normalize(text, Normalizer.Form.NFD)
            .replace(MARKS, "")
            .lowercase()
            .replace("&", " and ")
            .replace(Regex("\\b(sped|speed|speeded)\\s*-?\\s*up\\b"), "spedup")
            .replace(Regex("\\blo\\s*-\\s*fi\\b"), "lofi")
            .replace(Regex("\\bbass\\s+boosted\\b"), "bassboosted")

    private fun tokens(text: String): List<String> = text.split(WORD_SPLIT).filter { it.isNotEmpty() }

    fun normalizeArtist(name: String): String =
        tokens(preNormalize(name).replace(TOPIC, "")).joinToString(" ")

    /**
     * Splits a title into the title proper and its version markers. Brackets and "- suffix" parts that only
     * hold packaging ("Official Music Video", "Lirik", "feat. X", "From ...") are dropped, an "Artist - Title"
     * prefix is dropped when it names one of [artists].
     */
    fun parseTitle(
        title: String,
        artists: List<String> = emptyList(),
    ): TitleParts {
        val norm = preNormalize(title)
        val versions = linkedSetOf<String>()
        val artistNames = artists.map(::normalizeArtist).filter { it.isNotEmpty() }.toSet()
        val coreSegments = mutableListOf<List<String>>()

        fun isExtra(tokens: List<String>): Boolean =
            tokens.isNotEmpty() &&
                (
                    tokens.first() in CREDIT_WORDS ||
                        tokens.all { it in VERSION_WORDS || it in PACKAGING_WORDS || it.all(Char::isDigit) }
                )

        fun collectVersions(tokens: List<String>) {
            tokens.forEach { t -> VERSION_WORDS[t]?.let(versions::add) }
        }

        // 1. bracket groups
        val brackets = BRACKET.findAll(norm).map { tokens(it.groupValues[1]) }.toList()
        for (b in brackets) {
            if (b.isEmpty()) continue
            val hasMarker = b.any { it in VERSION_WORDS || it in PACKAGING_WORDS || it in CREDIT_WORDS }
            if (hasMarker || isExtra(b)) {
                collectVersions(b)
            } else if (b.all { it.all(Char::isDigit) }) {
                // a year
            } else {
                coreSegments += b
            }
        }

        // 2. the text outside the brackets, split on " - " and "|"
        val outside = BRACKET.replace(norm, " ")
        val segments = outside.split(SEGMENT_SPLIT).map(::tokens).filter { it.isNotEmpty() }
        val kept = mutableListOf<List<String>>()
        for (seg in segments) {
            val joined = seg.joinToString(" ")
            when {
                joined in artistNames -> Unit
                isExtra(seg) -> collectVersions(seg)
                else -> kept += seg
            }
        }
        // the first part is the title when several remain ("Artist X - Title" with an unknown artist stays joined)
        val main = kept.map { trimTail(it, versions) }.filter { it.isNotEmpty() }
        val all = main + coreSegments
        // A title that is nothing but marker words ("Live") is the title itself, not a marker.
        if (all.isEmpty() && segments.isNotEmpty()) return TitleParts(segments.first().joinToString(" "), emptySet())
        val core = all.joinToString(" ") { it.joinToString(" ") }
        return TitleParts(core, versions)
    }

    /** Removes a feat. clause and trailing packaging / version words ("Negoro Angin Official Video"). */
    private fun trimTail(
        tokens: List<String>,
        versions: MutableSet<String>,
    ): List<String> {
        var t = tokens
        val creditAt = t.indexOfFirst { it in CREDIT_WORDS }
        if (creditAt > 0) t = t.subList(0, creditAt)
        while (t.size > 1) {
            val last = t.last()
            val marker = VERSION_WORDS[last]
            when {
                marker != null -> versions += marker
                last in PACKAGING_WORDS -> Unit
                else -> break
            }
            t = t.dropLast(1)
        }
        return t
    }

    /** The title names one of the [artists] as whole words ("DENNY CAKNAN - NEGORO ANGIN", "Negoro Angin - Denny Caknan | Lirik"). */
    internal fun namesArtist(
        artists: List<String>,
        title: String,
    ): Boolean {
        val words = " " + tokens(preNormalize(title)).joinToString(" ") + " "
        return artists.map(::normalizeArtist).filter { it.length >= 3 }.any { " $it " in words }
    }

    /** At least one credited artist is the same on both sides. */
    fun sharesArtist(
        wanted: List<String>,
        got: List<String>,
    ): Boolean {
        val w = wanted.map { it.replace(TOPIC, "") }.filter { it.isNotBlank() }
        val g = got.map { it.replace(TOPIC, "") }.filter { it.isNotBlank() }
        if (w.isEmpty() || g.isEmpty()) return false
        return PlaylistLinkParser.artistMatches(w, g)
    }

    /**
     * Same title proper, same version markers and a shared artist. [strictDuration] adds the length window
     * (both lengths known); search ranking uses it off because a video may carry a long intro.
     */
    fun sameRecording(
        a: TrackInfo,
        b: TrackInfo,
        strictDuration: Boolean = true,
    ): Boolean {
        if (a.id == b.id) return true
        val pa = parseTitle(a.title, a.artists)
        val pb = parseTitle(b.title, b.artists)
        if (pa.core.isEmpty() || pa.core != pb.core || pa.versions != pb.versions) return false
        if (!sharesArtist(a.artists, b.artists)) return false
        return !strictDuration || durationDiff(a, b)?.let { it <= MAX_DURATION_DIFF_SEC } != false
    }

    /** [c] minus [a] in seconds; null when either length is unknown. */
    private fun signedDuration(
        a: TrackInfo,
        c: TrackInfo,
    ): Int? {
        val x = a.durationSec?.takeIf { it > 0 } ?: return null
        val y = c.durationSec?.takeIf { it > 0 } ?: return null
        return y - x
    }

    private fun durationDiff(
        a: TrackInfo,
        b: TrackInfo,
    ): Int? {
        val x = a.durationSec?.takeIf { it > 0 } ?: return null
        val y = b.durationSec?.takeIf { it > 0 } ?: return null
        return abs(x - y)
    }

    /**
     * The best of [candidates] that is the other recording of [target]: a video when [wantVideo], the catalogue
     * audio otherwise. Null when none passes (the common, safe answer). The upload itself is never returned.
     */
    fun best(
        target: TrackInfo,
        candidates: List<TrackInfo>,
        wantVideo: Boolean,
        /**
         * How much LONGER than [target] a candidate may be (a music video with an intro). Shorter than [target] is never
         * allowed by more than [MAX_DURATION_DIFF_SEC]. The default keeps the old symmetric window.
         */
        maxLongerSec: Int = MAX_DURATION_DIFF_SEC,
        /**
         * A video whose UPLOADER is not the artist (a lyric video by a fan channel: its "artist" is the channel) still counts
         * when its title names the artist, the title proper and the version markers are the same and its length is within
         * [UGC_MAX_DIFF_SEC] of the song (same recording, same length). Only meaningful with [wantVideo]; the real artist
         * match always ranks higher.
         */
        titleNamesArtist: Boolean = false,
    ): TrackInfo? {
        val wanted = parseTitle(target.title, target.artists)
        if (wanted.core.isEmpty()) return null
        return candidates
            .distinctBy { it.id }
            .withIndex()
            .mapNotNull { (position, c) ->
                if (c.id == target.id || c.isEpisode || c.isVideo != wantVideo) return@mapNotNull null
                val direct = sharesArtist(target.artists, c.artists)
                val viaTitle = !direct && titleNamesArtist && wantVideo && namesArtist(target.artists, c.title)
                if (!direct && !viaTitle) return@mapNotNull null
                // a title that names the artist is read with the artist names dropped (the uploader is a channel)
                val got = parseTitle(c.title, if (viaTitle) c.artists + target.artists else c.artists)
                if (got.core != wanted.core || got.versions != wanted.versions) return@mapNotNull null
                val diff = durationDiff(target, c)
                if (viaTitle && (diff == null || diff > UGC_MAX_DIFF_SEC)) return@mapNotNull null
                if (diff != null) {
                    val longerBy = signedDuration(target, c) ?: 0
                    if (longerBy > maxLongerSec || (longerBy < 0 && diff > MAX_DURATION_DIFF_SEC)) return@mapNotNull null
                }
                // closer length first, then the artist's own video, then the order of the search result
                val score = 1000 - (diff ?: 8) * 20 - position * 5 + (if (wantVideo && c.isOfficialVideo) 80 else 0) - (if (viaTitle) 300 else 0)
                c to score
            }.maxByOrNull { it.second }
            ?.first
    }

    /** Search words, best first: title proper (+ markers) with the first artist, then the title alone. */
    fun queries(target: TrackInfo): List<String> {
        val parts = parseTitle(target.title, target.artists)
        if (parts.core.isEmpty()) return emptyList()
        val title =
            (listOf(parts.core) + parts.versions)
                .joinToString(" ")
                .replace("spedup", "sped up")
                .replace("bassboosted", "bass boosted")
        val artist = target.artists.firstOrNull { it.isNotBlank() }?.let(::normalizeArtist).orEmpty()
        return if (artist.isEmpty()) listOf(title) else listOf("$title $artist", title)
    }
}
