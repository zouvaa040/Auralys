/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Import a public Spotify / Apple Music playlist (or album) by link.
 * The Apple Music link handling and the title normalisation ideas are adapted from Convx
 * (https://github.com/cosmictaserdev-creator/Convx, GPL-3.0); see NOTICE.
 */

package com.metrolist.music.utils

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.net.URI
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Where a pasted link comes from. */
enum class PlaylistLinkSource { SPOTIFY, APPLE_MUSIC }

/** One track read from the source service. [durationMs] is null when unknown. */
data class ExternalTrack(
    val title: String,
    val artists: List<String>,
    val durationMs: Long? = null,
) {
    val displayName: String
        get() = if (artists.isEmpty()) title else "$title - ${artists.joinToString(", ")}"
}

/**
 * A playlist or album read from a public page.
 * [declaredCount] is the size the service reports (Apple only), [mayBeTruncated] is true when the
 * public page probably returned only part of the list (Spotify's embed stops at 100 tracks; [SpotifyWebReader]
 * then reads the rest from the web page).
 */
data class ExternalPlaylist(
    val source: PlaylistLinkSource,
    val title: String,
    val tracks: List<ExternalTrack>,
    val coverUrl: String? = null,
    val declaredCount: Int? = null,
    val mayBeTruncated: Boolean = false,
    val skipped: Int = 0,
    /** The resolved Spotify link, kept so a truncated embed result can be completed from the web page. */
    val spotifyLink: SpotifyLink? = null,
)

/** A recognised link: [kind] is "playlist" or "album". */
data class SpotifyLink(val kind: String, val id: String) {
    val embedUrl: String get() = "https://open.spotify.com/embed/$kind/$id"
    val pageUrl: String get() = "https://open.spotify.com/$kind/$id"
}

data class AppleMusicLink(val storefront: String, val kind: String, val id: String) {
    val pageUrl: String get() = "https://music.apple.com/$storefront/$kind/$id"
}

/** Thrown inside [PlaylistLinkFetcher]; never leaves it (it returns a [Result]). */
class PlaylistLinkException(val reason: Reason, message: String? = null) : Exception(message ?: reason.name) {
    enum class Reason { INVALID_LINK, FETCH_FAILED, EMPTY }
}

/**
 * Pure Kotlin parsing and matching helpers. Nothing here throws: every entry point returns null /
 * an empty value on malformed input.
 */
object PlaylistLinkParser {
    const val SPOTIFY_EMBED_LIMIT = 100

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val spotifyId = Regex("^[A-Za-z0-9]{22}$")
    private val spotifyUri = Regex("""^spotify:(playlist|album):([A-Za-z0-9]{22})$""")
    private val appleStorefront = Regex("^[a-z]{2}$")
    private val applePlaylistId = Regex("""^pl\.[A-Za-z0-9.\-]+$""")
    private val appleAlbumId = Regex("^[0-9]+$")

    // ---------------------------------------------------------------- links

    /** Pulls the first http(s) URL or spotify: URI out of pasted text ("Check this out https://..."). */
    fun extractLink(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        Regex("""https?://\S+""").find(text)?.let { return it.value.trimEnd('.', ',', ')', '"', '\'') }
        Regex("""spotify:(playlist|album):[A-Za-z0-9]{22}""").find(text)?.let { return it.value }
        return null
    }

    fun isSpotifyShortLink(input: String): Boolean {
        val host = runCatching { URI(extractLink(input) ?: return false).host }.getOrNull()?.lowercase(Locale.ROOT)
            ?: return false
        return host == "spotify.link" || host == "spoti.fi" || host.endsWith(".app.link")
    }

    fun parseSpotifyLink(input: String): SpotifyLink? = runCatching {
        val link = extractLink(input) ?: return null
        spotifyUri.find(link)?.let { return SpotifyLink(it.groupValues[1], it.groupValues[2]) }
        val uri = URI(link)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (host != "open.spotify.com" && host != "play.spotify.com") return null
        val parts = (uri.path ?: "").split('/').filter { it.isNotEmpty() }
        val kindIndex = parts.indexOfFirst { it == "playlist" || it == "album" }
        if (kindIndex < 0 || kindIndex + 1 >= parts.size) return null
        val id = parts[kindIndex + 1]
        if (!spotifyId.matches(id)) return null
        SpotifyLink(parts[kindIndex], id)
    }.getOrNull()

    fun parseAppleMusicLink(input: String): AppleMusicLink? = runCatching {
        val link = extractLink(input) ?: return null
        val uri = URI(link)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (host != "music.apple.com" && host != "embed.music.apple.com" && host != "geo.music.apple.com") return null
        if (uri.userInfo != null) return null
        val parts = (uri.path ?: "").split('/').filter { it.isNotEmpty() }
        val kindIndex = parts.indexOfFirst { it == "playlist" || it == "album" }
        if (kindIndex < 0 || kindIndex + 1 >= parts.size) return null
        val kind = parts[kindIndex]
        val storefront = parts.getOrNull(kindIndex - 1)?.lowercase(Locale.ROOT)
            ?.takeIf { kindIndex == 1 && appleStorefront.matches(it) } ?: "us"
        val id = parts.last()
        val valid = if (kind == "playlist") applePlaylistId.matches(id) else appleAlbumId.matches(id)
        if (!valid) return null
        AppleMusicLink(storefront, kind, id)
    }.getOrNull()

    // ---------------------------------------------------------------- pages

    private fun scriptContent(html: String, id: String): String? {
        val start = Regex("""<script[^>]*\bid=["']${Regex.escape(id)}["'][^>]*>""").find(html) ?: return null
        val from = start.range.last + 1
        val end = html.indexOf("</script>", from)
        if (end < 0) return null
        return html.substring(from, end).trim().takeIf { it.isNotEmpty() }
    }

    private fun parseJson(text: String): JsonElement? = runCatching { json.parseToJsonElement(text) }.getOrNull()

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
    private fun JsonElement?.str(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    private fun JsonElement?.num(): Long? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.toLong()
    private fun JsonElement?.path(vararg keys: String): JsonElement? =
        keys.fold(this) { acc, key -> acc.obj()?.get(key) }

    private fun splitArtists(raw: String?): List<String> =
        raw.orEmpty()
            .replace(' ', ' ')
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** Parses https://open.spotify.com/embed/{playlist|album}/{id}. Returns null when the page has no track list. */
    fun parseSpotifyEmbed(html: String): ExternalPlaylist? = runCatching {
        val root = parseJson(scriptContent(html, "__NEXT_DATA__") ?: return null) ?: return null
        val entity = root.path("props", "pageProps", "state", "data", "entity").obj() ?: return null
        val list = entity["trackList"].arr() ?: return null
        var skipped = 0
        val tracks = list.mapNotNull { element ->
            val item = element.obj()
            val type = item?.get("entityType").str()
            val title = item?.get("title").str()
            if (item == null || title == null || (type != null && type != "track")) {
                skipped++
                return@mapNotNull null
            }
            ExternalTrack(
                title = title,
                artists = splitArtists(item["subtitle"].str()),
                durationMs = item["duration"].num()?.takeIf { it > 0 },
            )
        }
        val kind = entity["type"].str()
        ExternalPlaylist(
            source = PlaylistLinkSource.SPOTIFY,
            title = entity["name"].str() ?: entity["title"].str() ?: "Spotify",
            tracks = tracks,
            coverUrl = entity.path("coverArt", "sources").arr()?.firstOrNull()?.path("url").str(),
            declaredCount = null,
            mayBeTruncated = kind != "album" && list.size >= SPOTIFY_EMBED_LIMIT,
            skipped = skipped,
        )
    }.onFailure { Timber.w(it, "Spotify embed parse failed") }.getOrNull()

    /** Collects every object with itemKind == [kind] (sections of the Apple page), depth limited. */
    private fun findSections(element: JsonElement?, kind: String, out: MutableList<JsonObject>, depth: Int = 0) {
        if (depth > 8 || element == null) return
        when (element) {
            is JsonObject -> {
                if (element["itemKind"].str() == kind && element["items"] is JsonArray) {
                    out += element
                    return
                }
                element.values.forEach { findSections(it, kind, out, depth + 1) }
            }
            is JsonArray -> element.forEach { findSections(it, kind, out, depth + 1) }
            else -> Unit
        }
    }

    private fun appleArtwork(element: JsonElement?): String? {
        val url = element.path("dictionary", "url").str() ?: element.path("url").str() ?: return null
        return url.replace("{w}", "600").replace("{h}", "600").replace("{c}", "bb").replace("{f}", "jpg")
    }

    /** Parses a public music.apple.com playlist/album page (serialized-server-data JSON). */
    fun parseAppleMusicPage(html: String): ExternalPlaylist? = runCatching {
        val root = parseJson(scriptContent(html, "serialized-server-data") ?: return null) ?: return null
        val headers = mutableListOf<JsonObject>()
        findSections(root, "containerDetailHeaderLockup", headers)
        val header = headers.firstOrNull()?.get("items").arr()?.firstOrNull().obj()
        val sections = mutableListOf<JsonObject>()
        findSections(root, "trackLockup", sections)
        if (sections.isEmpty()) return null
        var skipped = 0
        val tracks = sections.flatMap { section ->
            section["items"].arr().orEmpty().mapNotNull { element ->
                val item = element.obj()
                val title = item?.get("title").str()
                if (item == null || title == null) {
                    skipped++
                    return@mapNotNull null
                }
                val artists = item["artistName"].str()?.let { listOf(it) }
                    ?: item["subtitleLinks"].arr()?.mapNotNull { it.path("title").str() }
                    ?: emptyList()
                ExternalTrack(
                    title = title,
                    artists = artists,
                    durationMs = item["duration"].num()?.takeIf { it > 0 },
                )
            }
        }
        val declared = header?.get("trackCount").num()?.toInt()
        ExternalPlaylist(
            source = PlaylistLinkSource.APPLE_MUSIC,
            title = header?.get("title").str() ?: "Apple Music",
            tracks = tracks,
            coverUrl = appleArtwork(header?.get("artwork")),
            declaredCount = declared,
            mayBeTruncated = declared != null && declared > tracks.size + skipped,
            skipped = skipped,
        )
    }.onFailure { Timber.w(it, "Apple Music page parse failed") }.getOrNull()

    // ---------------------------------------------------------------- matching

    private val bracketGroup = Regex("""\s*[(\[{]([^)\]}]*)[)\]}]""")
    private val dashSuffix = Regex("""\s+[-–—]\s+(.+)$""")
    private val inlineFeat = Regex("""\s+(feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val decoration = Regex(
        """\b(feat|ft|featuring|featured|with|prod|remaster|remastered|version|edit|explicit|clean|deluxe|bonus|""" +
            """mono|stereo|from|anniversary|live|demo|single|radio|original|soundtrack|ost|theme)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val marks = Regex("""\p{Mn}+""")
    private val nonWord = Regex("""[^\p{L}\p{N}]+""")

    /** Drops "(feat. X)", "[Remastered 2011]", "- 2004 Remaster", " feat. X" style decorations; keeps "(Remix)". */
    fun stripDecorations(title: String): String {
        var s = title.replace(bracketGroup) { m ->
            if (decoration.containsMatchIn(m.groupValues[1])) "" else m.value
        }
        dashSuffix.find(s)?.let { m ->
            if (decoration.containsMatchIn(m.groupValues[1])) s = s.substring(0, m.range.first)
        }
        s = s.replace(inlineFeat, "")
        return s.trim().ifEmpty { title.trim() }
    }

    /** Lowercase, accent-free, punctuation-free. */
    fun normalize(s: String): String {
        val base = Normalizer.normalize(s.replace("&", " and "), Normalizer.Form.NFD).replace(marks, "")
        return base.lowercase(Locale.ROOT).replace(nonWord, " ").trim()
    }

    private fun bigrams(s: String): List<String> {
        val compact = s.replace(" ", "")
        if (compact.length < 2) return listOf(compact)
        return (0 until compact.length - 1).map { compact.substring(it, it + 2) }
    }

    /** 0..1 similarity of two already normalised strings (max of word Dice and character-bigram Dice). */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val ta = a.split(' ').toSet()
        val tb = b.split(' ').toSet()
        val wordDice = 2.0 * ta.intersect(tb).size / (ta.size + tb.size)
        val ba = bigrams(a)
        val bb = bigrams(b).toMutableList()
        var common = 0
        for (g in ba) {
            val i = bb.indexOf(g)
            if (i >= 0) {
                common++
                bb.removeAt(i)
            }
        }
        val charDice = 2.0 * common / (ba.size + bigrams(b).size)
        // "Back in Time" vs "Back in Time featured in Men in Black 3": whole-word prefix counts as close.
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a
        val prefix = if (shorter.length >= 4 && longer.startsWith("$shorter ")) 0.75 else 0.0
        return maxOf(wordDice, charDice, prefix)
    }

    fun artistMatches(expected: List<String>, candidate: List<String>): Boolean {
        if (expected.isEmpty() || candidate.isEmpty()) return false
        val want = expected.map(::normalize).filter { it.isNotEmpty() }
        val have = candidate.flatMap { splitArtists(it) }.map(::normalize).filter { it.isNotEmpty() }
        val haveJoined = " ${have.joinToString(" ")} "
        return want.any { w ->
            haveJoined.contains(" $w ") || have.any { h -> " $w ".contains(" $h ") || similarity(w, h) >= 0.8 }
        }
    }

    /** Search query for [track]: cleaned title plus up to two artists. */
    fun queryFor(track: ExternalTrack): String =
        (stripDecorations(track.title) + " " + track.artists.take(2).joinToString(" ")).trim()

    /** True when one of [expected] shows up as whole words in an already normalised candidate title ("BTS | Spring Day"). */
    private fun artistInTitle(expected: List<String>, normalizedTitle: String): Boolean {
        val padded = " $normalizedTitle "
        return expected.map(::normalize).any { it.length >= 2 && padded.contains(" $it ") }
    }

    /** Title similarity (0..1) and artist agreement of [candidate] against an already normalised wanted title. */
    private fun assess(
        track: ExternalTrack,
        want: String,
        wantWords: Set<String>,
        candidate: SongItem,
    ): Pair<Double, Boolean> {
        val candidateTitle = normalize(stripDecorations(candidate.title))
        val artistOk = artistMatches(track.artists, candidate.artists.map { it.name }) ||
            artistInTitle(track.artists, candidateTitle)
        var sim = similarity(want, candidateTitle)
        // "HIVI! - Pelangi", "Pelangi (Official Video)": every wanted word is there and the artist agrees.
        if (artistOk && wantWords.all { it in candidateTitle.split(' ') }) sim = maxOf(sim, 0.75)
        return sim to artistOk
    }

    /**
     * Last resort when [chooseMatch] found nothing, usually because the duration differs a lot (an extended,
     * radio or re-recorded edit): the same title by the same artist is still the song the user wants.
     * Picks the one whose duration is closest, songs before videos.
     */
    fun chooseClosestMatch(track: ExternalTrack, candidates: List<SongItem>): SongItem? {
        val want = normalize(stripDecorations(track.title))
        if (want.isEmpty()) return null
        val wantWords = want.split(' ').toSet()
        val sourceSec = track.durationMs?.let { (it / 1000).toInt() }?.takeIf { it > 0 }
        return candidates
            .distinctBy { it.id }
            .filter { !it.isEpisode }
            .filter { candidate ->
                val (sim, artistOk) = assess(track, want, wantWords, candidate)
                artistOk && sim >= 0.75
            }
            .minWithOrNull(
                compareBy<SongItem> { candidate ->
                    val sec = candidate.duration?.takeIf { it > 0 }
                    if (sourceSec != null && sec != null) abs(sourceSec - sec) else Int.MAX_VALUE
                }.thenBy { it.isVideoSong },
            )
    }

    /**
     * Picks the best YouTube Music item for [track] from [candidates]: every acceptable candidate is scored
     * on title similarity, artist agreement and how close the duration is, and the highest score wins.
     *
     * A candidate is acceptable when its title is similar enough (the artist must agree unless the title is
     * near-identical) and, when both durations are known, the duration is within [maxDurationDiffSec]
     * (5 s more for music videos, which often carry an intro). A song by the same artist whose length is
     * within 2 s is accepted even with a different title, which covers translated or transliterated titles
     * ("Spring Day" vs "봄날").
     */
    fun chooseMatch(
        track: ExternalTrack,
        candidates: List<SongItem>,
        minTitleSimilarity: Double = 0.6,
        maxDurationDiffSec: Int = 10,
    ): SongItem? {
        val want = normalize(stripDecorations(track.title))
        if (want.isEmpty()) return null
        val wantWords = want.split(' ').toSet()
        val sourceSec = track.durationMs?.let { (it / 1000).toInt() }?.takeIf { it > 0 }
        var best: SongItem? = null
        var bestScore = 0.0
        for (candidate in candidates.distinctBy { it.id }.take(10)) {
            val (sim, artistOk) = assess(track, want, wantWords, candidate)
            val candidateSec = candidate.duration?.takeIf { it > 0 }
            val diff = if (sourceSec != null && candidateSec != null) abs(sourceSec - candidateSec) else null
            val limit = maxDurationDiffSec + if (candidate.isVideoSong) 5 else 0
            if (diff != null && diff > limit) continue
            val titleOk = sim >= minTitleSimilarity && (artistOk || sim >= 0.85)
            val sameRecording = artistOk && !candidate.isVideoSong && diff != null && diff <= 2
            if (!titleOk && !sameRecording) continue
            val durationScore = if (diff == null) 0.5 else 1.0 - diff.toDouble() / (limit + 1)
            val score = sim * 0.5 + (if (artistOk) 0.3 else 0.0) + durationScore * 0.2 +
                (if (candidate.isVideoSong) 0.0 else 0.05)
            if (score > bestScore) {
                best = candidate
                bestScore = score
            }
        }
        return best
    }
}

/** Network side: reads public pages. All functions return [Result] and never throw (except cancellation). */
object PlaylistLinkFetcher {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private const val MAX_BODY_CHARS = 12_000_000

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()
    }

    /** Returns (final URL, body) or null on HTTP error. */
    private suspend fun get(url: String): Pair<String, String>? = runInterruptible(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runInterruptible null
            val body = response.body?.string() ?: return@runInterruptible null
            if (body.length > MAX_BODY_CHARS) return@runInterruptible null
            response.request.url.toString() to body
        }
    }

    /** Recognises the link without any network access (short links need [fetch]). */
    fun looksValid(source: PlaylistLinkSource, input: String): Boolean = when (source) {
        PlaylistLinkSource.SPOTIFY ->
            PlaylistLinkParser.parseSpotifyLink(input) != null || PlaylistLinkParser.isSpotifyShortLink(input)
        PlaylistLinkSource.APPLE_MUSIC -> PlaylistLinkParser.parseAppleMusicLink(input) != null
    }

    suspend fun fetch(source: PlaylistLinkSource, input: String): Result<ExternalPlaylist> = try {
        Result.success(
            when (source) {
                PlaylistLinkSource.SPOTIFY -> fetchSpotify(input)
                PlaylistLinkSource.APPLE_MUSIC -> fetchApple(input)
            },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: PlaylistLinkException) {
        Result.failure(e)
    } catch (e: Exception) {
        Timber.w(e, "Playlist link fetch failed")
        Result.failure(PlaylistLinkException(PlaylistLinkException.Reason.FETCH_FAILED, e.message))
    }

    private suspend fun fetchSpotify(input: String): ExternalPlaylist {
        val link = PlaylistLinkParser.parseSpotifyLink(input)
            ?: resolveSpotifyShortLink(input)
            ?: throw PlaylistLinkException(PlaylistLinkException.Reason.INVALID_LINK)
        val (_, html) = get(link.embedUrl)
            ?: throw PlaylistLinkException(PlaylistLinkException.Reason.FETCH_FAILED)
        val playlist = PlaylistLinkParser.parseSpotifyEmbed(html)
            ?: throw PlaylistLinkException(PlaylistLinkException.Reason.FETCH_FAILED)
        if (playlist.tracks.isEmpty()) throw PlaylistLinkException(PlaylistLinkException.Reason.EMPTY)
        return playlist.copy(spotifyLink = link)
    }

    private suspend fun resolveSpotifyShortLink(input: String): SpotifyLink? {
        if (!PlaylistLinkParser.isSpotifyShortLink(input)) return null
        val url = PlaylistLinkParser.extractLink(input) ?: return null
        val (finalUrl, body) = runCatching { get(url) }.getOrNull() ?: return null
        PlaylistLinkParser.parseSpotifyLink(finalUrl)?.let { return it }
        val inBody = Regex("""https://open\.spotify\.com/(?:[a-z-]+/)?(?:playlist|album)/[A-Za-z0-9]{22}""")
            .find(body)?.value
        return inBody?.let { PlaylistLinkParser.parseSpotifyLink(it) }
    }

    private suspend fun fetchApple(input: String): ExternalPlaylist {
        val link = PlaylistLinkParser.parseAppleMusicLink(input)
            ?: throw PlaylistLinkException(PlaylistLinkException.Reason.INVALID_LINK)
        val (_, html) = get(link.pageUrl)
            ?: throw PlaylistLinkException(PlaylistLinkException.Reason.FETCH_FAILED)
        val playlist = PlaylistLinkParser.parseAppleMusicPage(html)
            ?: throw PlaylistLinkException(PlaylistLinkException.Reason.FETCH_FAILED)
        if (playlist.tracks.isEmpty()) throw PlaylistLinkException(PlaylistLinkException.Reason.EMPTY)
        return playlist
    }
}

/** Finds a YouTube Music song for an external track through the app's existing YouTube search wrapper. */
object PlaylistTrackMatcher {
    private suspend fun searchSongs(query: String, filter: YouTube.SearchFilter): List<SongItem>? {
        repeat(2) { attempt ->
            val result = YouTube.search(query, filter)
            val items = result.getOrNull()
            if (items != null) return items.items.filterIsInstance<SongItem>()
            val error = result.exceptionOrNull()
            if (error is CancellationException) throw error
            if (attempt == 0) delay(600)
        }
        return null
    }

    private suspend fun searchSummaryItems(query: String): List<SongItem> {
        val result = YouTube.searchSummary(query)
        val error = result.exceptionOrNull()
        if (error is CancellationException) throw error
        return result.getOrNull()?.summaries.orEmpty().flatMap { it.items }.filterIsInstance<SongItem>()
    }

    /** Never throws except for cancellation; null means "not found". */
    suspend fun match(track: ExternalTrack): SongItem? {
        return try {
            val mainArtist = track.artists.firstOrNull().orEmpty()
            // Cleaned title with up to two artists, then looser variants with the main artist only.
            val queries = listOf(
                PlaylistLinkParser.queryFor(track),
                (PlaylistLinkParser.stripDecorations(track.title) + " " + mainArtist).trim(),
                (track.title + " " + mainArtist).trim(),
            ).distinctBy { it.lowercase() }
            // Songs first; tracks that only exist as a music video on YouTube Music are found in a second round.
            val seen = mutableListOf<SongItem>()
            var first = true
            for (filter in listOf(YouTube.SearchFilter.FILTER_SONG, YouTube.SearchFilter.FILTER_VIDEO)) {
                for (query in queries) {
                    if (!first) delay(120)
                    first = false
                    val items = searchSongs(query, filter) ?: continue
                    seen += items
                    PlaylistLinkParser.chooseMatch(track, items)?.let { return it }
                }
            }
            // The mixed "All" results (top result, songs, videos) are what the search screen shows.
            delay(120)
            val summary = searchSummaryItems(queries.first())
            seen += summary
            PlaylistLinkParser.chooseMatch(track, summary)?.let { return it }
            // Nothing within the duration limits: take the closest edit of the same title and artist.
            PlaylistLinkParser.chooseClosestMatch(track, seen)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Track match failed for ${track.title}")
            null
        }
    }
}
