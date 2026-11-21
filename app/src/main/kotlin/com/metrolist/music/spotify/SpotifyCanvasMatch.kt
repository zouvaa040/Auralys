/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Decides which of Spotify's search results is the same recording as the song that is playing, before
 * a Canvas (the short looping video) is asked for. A canvas belongs to one Spotify track, so a wrong
 * match shows another song's video: here "no match" is always better than a guess.
 *
 * Rules (the ideas come from BitChord's TrackMatcher, https://github.com/kushagrasinghx/BitChord,
 * GPL-3.0; re-implemented and made stricter for Auralys):
 *  - the title without packaging must be identical (case, accents, punctuation and "feat." ignored);
 *  - the markers of another take (remix, live, acoustic, instrumental, sped up ... and everything said in
 *    the same bracket) must be identical in both directions, so "Song" never takes the canvas of
 *    "Song - Live at X" or the other way round; "remastered", "radio edit", "official video" and the like
 *    are packaging and ignored;
 *  - at least one credited artist must be the same name (not a part of it: "Drake" is not "Drake Bell");
 *  - when both lengths are known they must agree (30 s, 90 s for songs that are a music video).
 * Several results can pass (the same song on a single and on an album); the album and the length only
 * put the best one first.
 */

package com.metrolist.music.spotify

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** One track of Spotify's search answer. */
data class SpotifyTrackHit(
    /** `spotify:track:<id>` */
    val uri: String,
    val title: String,
    /** Credited artists, one name each. */
    val artists: List<String>,
    val album: String?,
    val durationSec: Int?,
)

object SpotifyCanvasMatch {
    const val DURATION_LIMIT_SEC = 30
    const val VIDEO_DURATION_LIMIT_SEC = 90

    internal class TitleParts(
        /** The words that carry the identity of the song, joined. */
        val core: String,
        /** What marks another take of the song; empty for the plain studio track. */
        val versions: Set<String>,
        /** Other words said in brackets or after a dash that are not packaging ("Piano", "Choir"). */
        val context: Set<String>,
        /** The title as it is typed into a search box (packaging removed, take markers kept). */
        val search: String,
    )

    // Title

    private val bracketed = Regex("""[(\[]([^()\[\]]*)[)\]]""")
    private val dash = Regex("""\s+[-–—|]+\s+""")
    private val featuring = Regex("""\b(?:feat|ft|featuring)\b\.?.*""")
    private val wordSplit = Regex("""[\s.·~〜]+""")
    private val nonAlnum = Regex("""[^\p{L}\p{N}]""")
    private val spaces = Regex("""\s+""")

    private val versionWords = setOf(
        "remix", "remixes", "remixed", "rmx", "refix", "flip", "bootleg", "mashup", "medley",
        "live", "concert", "unplugged", "acoustic", "instrumental", "karaoke",
        "vocals", "vocal", "acapella", "acappella", "backing", "stems", "stem",
        "cover", "demo", "reprise", "remake", "rework", "extended", "edit",
        "version", "mix", "dub", "vip", "session", "sessions", "rerecorded",
        "sped", "slowed", "reverb", "nightcore", "lofi", "orchestral", "symphonic",
        "part", "pt", "chapter",
        "カバー", "カラオケ", "リミックス", "ライブ", "アコースティック", "インスト", "バージョン",
        "歌ってみた", "踊ってみた", "弾いてみた", "翻唱", "现场", "混音",
        "커버", "라이브", "리믹스",
    )

    /** Said after the title without a bracket ("Song Instrumental"): a take, like the bracketed form. */
    private val trailingTakes = setOf("instrumental", "karaoke", "acapella", "acappella", "remix", "rmx", "nightcore")

    /** Packaging of a release, not another recording. A segment made only of these is ignored. */
    private val packagingWords = setOf(
        "remaster", "remastered", "remastering", "digital", "version", "edition", "anniversary", "deluxe",
        "expanded", "stereo", "mono", "original", "album", "single", "radio", "explicit", "clean", "bonus",
        "track", "studio", "full", "standard", "official", "video", "audio", "lyrics", "lyric", "lyrical",
        "visualizer", "visualiser", "hd", "hq", "4k", "mp3", "the", "music", "song", "songs", "new", "latest",
        "motion", "picture", "soundtrack", "ost", "score",
    )
    private val packagingJoined = setOf("radioedit", "singleedit", "albumedit", "originalmix", "radiomix")

    /** A bracket that only credits a guest or a producer, or says which film or show the song is from. */
    private val ignoredLeads = setOf("feat", "ft", "featuring", "with", "prod", "produced", "from", "theme")

    private val noiseWords = setOf(
        "official", "video", "audio", "lyrics", "lyric", "lyrical", "visualizer", "visualiser", "song", "songs",
        "full", "music", "the", "and", "from", "feat", "ft", "featuring", "with", "new", "latest", "free",
        "download", "remaster", "remastered", "explicit", "clean", "bonus", "track", "deluxe", "original",
        "album", "single", "hd", "hq", "4k", "mp3",
    )
    private val trailingNoise = setOf(
        "song", "songs", "video", "audio", "lyrics", "lyric", "lyrical", "official", "full", "hd", "hq", "4k", "mp3", "ost", "soundtrack",
    )
    private val genericInVersion = setOf("version", "the", "a", "an", "of", "at", "in", "on", "by", "from", "with", "and")
    private val albumNoise = setOf("album", "deluxe", "edition", "expanded", "remaster", "remastered", "version", "explicit", "clean", "bonus", "anniversary")

    internal fun parseTitle(raw: String, artist: String = ""): TitleParts {
        val versions = linkedSetOf<String>()
        val context = linkedSetOf<String>()
        var text = fold(raw)

        repeat(3) {
            if (bracketed.containsMatchIn(text)) {
                text = bracketed.replace(text) { match ->
                    classify(match.groupValues[1], versions, context)
                    " "
                }
            }
        }
        val open = text.indexOfFirst { it == '(' || it == '[' }
        if (open >= 0) {
            classify(text.substring(open + 1), versions, context)
            text = text.substring(0, open)
        }

        repeat(3) {
            val match = dash.find(text) ?: return@repeat
            val head = text.substring(0, match.range.first)
            val tail = text.substring(match.range.last + 1)
            if (isArtistName(head, artist)) {
                text = tail // "Artist - Song"
            } else {
                classify(tail, versions, context) // "Song - Remastered", "Song - Live at X"
                text = head
            }
        }

        text = featuring.replace(text, " ")
        val search = text.replace(spaces, " ").trim()

        var words = text.split(wordSplit).map { it.replace(nonAlnum, "") }.filter { it.isNotEmpty() && it != "and" }
        while (words.size > 1) {
            val last = words.last()
            if (last in trailingNoise) {
                words = words.dropLast(1)
            } else if (last in trailingTakes) {
                versions += last
                words = words.dropLast(1)
            } else {
                break
            }
        }
        return TitleParts(core = words.joinToString(""), versions = versions, context = context, search = search)
    }

    private fun wordsOf(text: String): List<String> =
        text.split(wordSplit).map { it.replace(nonAlnum, "") }.filter { it.isNotEmpty() }

    private fun classify(segment: String, versions: MutableSet<String>, context: MutableSet<String>) {
        val words = wordsOf(segment)
        if (words.isEmpty()) return
        if (words.first() in ignoredLeads) return
        if (words.joinToString("") in packagingJoined) return
        if (words.all { it in packagingWords || it.all(Char::isDigit) }) return
        val said = words.filter { it !in genericInVersion && it !in noiseWords }
        if (words.any { it in versionWords }) {
            versions += said
        } else {
            context += said.filter { it.length > 2 }
        }
    }

    private fun isArtistName(text: String, artist: String): Boolean {
        if (artist.isBlank()) return false
        val words = wordsOf(text)
        if (words.isEmpty()) return false
        val credited = wordsOf(fold(artist)).toSet()
        return words.all { it in credited }
    }

    // Artists

    private val artistSplit = Regex("""\s*(?:,|;|/|&|×|\s+x\s+|\band\b|\bfeat(?:uring)?\b\.?|\bft\b\.?|\bwith\b|\+)\s*""")
    private val topicSuffix = Regex("""\s*-\s*topic$""")

    private fun cleanArtist(name: String): String = fold(name).replace(topicSuffix, "").trim()

    /** Letters and digits only: "Jay-Z", "JAY Z" and "jayz" are the same name. */
    internal fun artistKey(name: String): String {
        val key = cleanArtist(name).filter { it.isLetterOrDigit() }
        return if (key.length > 7 && key.endsWith("vevo")) key.dropLast(4) else key
    }

    /** The key and, for "The Weeknd", the key without the article. */
    private fun keyVariants(name: String): Set<String> {
        val key = artistKey(name)
        if (key.isEmpty()) return emptySet()
        val bare = key.removePrefix("the")
        return if (bare != key && bare.length >= 3) setOf(key, bare) else setOf(key)
    }

    /** Each credited name, plus the whole string ("Simon & Garfunkel" is one act). */
    internal fun artistNames(credit: String): List<String> {
        val names = fold(credit).split(artistSplit).map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (names.size > 1) names += credit
        return names
    }

    internal fun primaryArtist(credit: String): String =
        fold(credit).split(artistSplit).map { it.trim() }.firstOrNull { it.isNotEmpty() }?.let { cleanArtistDisplay(it) }.orEmpty()

    private fun cleanArtistDisplay(name: String): String = name.replace(topicSuffix, "").trim()

    internal fun artistMatches(wantedCredit: String, credited: List<String>): Boolean {
        val wanted = artistNames(wantedCredit).map(::keyVariants).filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return false
        val have = credited.map(::keyVariants).filter { it.isNotEmpty() }
        return wanted.any { w -> have.any { h -> w.any { it in h } } }
    }

    // Judging

    private class Scored(val hit: SpotifyTrackHit, val albumMatch: Boolean, val drift: Int)

    /**
     * The results that are the same recording as the song asked for, best first (same album, then the
     * closest length, otherwise Spotify's own order). Empty when nothing is certain.
     */
    fun rank(
        candidates: List<SpotifyTrackHit>,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int?,
        isVideo: Boolean = false,
    ): List<SpotifyTrackHit> {
        val wanted = parseTitle(title, artist)
        if (wanted.core.isEmpty() || artistNames(artist).none { keyVariants(it).isNotEmpty() }) return emptyList()
        val wantedAlbum = albumKey(album)
        val limit = if (isVideo) VIDEO_DURATION_LIMIT_SEC else DURATION_LIMIT_SEC
        val wantedSec = durationSec?.takeIf { it > 0 }

        val scored = candidates.mapNotNull { hit ->
            if (!isTrackUri(hit.uri)) return@mapNotNull null
            val got = parseTitle(hit.title, hit.artists.joinToString(", "))
            if (got.core != wanted.core || got.versions != wanted.versions) return@mapNotNull null
            // Spotify's titles are clean: a word it adds that is not packaging ("Piano") is another recording.
            // Words only the playing title has are decoration from the video site.
            if (!wanted.context.containsAll(got.context)) return@mapNotNull null
            if (!artistMatches(artist, hit.artists)) return@mapNotNull null
            val gotSec = hit.durationSec?.takeIf { it > 0 }
            val drift = if (wantedSec != null && gotSec != null) abs(wantedSec - gotSec) else 0
            if (drift > limit) return@mapNotNull null
            Scored(hit, albumMatch = wantedAlbum != null && wantedAlbum == albumKey(hit.album), drift = drift)
        }
        return scored.sortedWith(compareByDescending<Scored> { it.albumMatch }.thenBy { it.drift }).map { it.hit }
    }

    private fun albumKey(album: String?): String? {
        var text = fold(album.orEmpty())
        repeat(3) { text = bracketed.replace(text, " ") }
        val key = wordsOf(text).filter { it !in albumNoise }.joinToString("")
        return key.ifEmpty { null }
    }

    internal fun isTrackUri(uri: String): Boolean = uri.startsWith("spotify:track:") && uri.length > 14 && uri.drop(14).all { it.isLetterOrDigit() }

    // Cache key and search term

    /**
     * One key per Spotify TRACK as the song is named here: the primary artist, the title core and the
     * take markers (so "Song" and "Song (Acoustic)" never share an answer). Null when the song cannot be
     * identified safely (no usable title or artist).
     */
    fun cacheKey(title: String, artist: String): String? {
        val parts = parseTitle(title, artist)
        val artistKey = artistNames(artist).firstOrNull()?.let(::artistKey).orEmpty()
        if (parts.core.isEmpty() || artistKey.isEmpty()) return null
        return "spotify:$artistKey|${parts.core}|${parts.versions.sorted().joinToString("+")}"
    }

    /** What is put to Spotify's search: the clean title, its take markers and the main artist. */
    fun searchTerm(title: String, artist: String): String {
        val parts = parseTitle(title, artist)
        val said = parts.versions.joinToString(" ")
        return listOf(parts.search, said, primaryArtist(artist)).filter { it.isNotBlank() }.joinToString(" ").take(200)
    }

    // Text folding

    private val latinFolds = mapOf('ø' to "o", 'ł' to "l", 'đ' to "d", 'ß' to "ss", 'æ' to "ae", 'œ' to "oe", 'ı' to "i", 'ð' to "d", 'þ' to "th")

    /** Lower case, compatibility forms (full-width, ligatures) unified, accents of Latin letters removed, CJK brackets as ( ). */
    internal fun fold(raw: String): String {
        val base = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace("&", " and ")
        val decomposed = Normalizer.normalize(base, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        var latinBase = false
        for (c in decomposed) {
            when {
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> if (!latinBase) out.append(c)
                else -> {
                    latinBase = c in 'a'..'z'
                    val folded = latinFolds[c]
                    when {
                        folded != null -> out.append(folded)
                        c in "「『【〈《〔" -> out.append('(')
                        c in "」』】〉》〕" -> out.append(')')
                        else -> out.append(c)
                    }
                }
            }
        }
        return Normalizer.normalize(out, Normalizer.Form.NFC)
    }
}
