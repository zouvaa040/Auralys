package com.metrolist.kugou

import com.metrolist.kugou.models.DownloadLyricsResponse
import com.metrolist.kugou.models.Keyword
import com.metrolist.kugou.models.SearchLyricsResponse
import com.metrolist.kugou.models.SearchSongResponse
import com.metrolist.music.lyrics.SongMatch
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.ContentType
import io.ktor.http.encodeURLParameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import java.lang.Integer.min
import kotlin.math.abs

@OptIn(ExperimentalSerializationApi::class, ExperimentalEncodingApi::class)
private val client = HttpClient {
    expectSuccess = true

    install(ContentNegotiation) {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }
        json(json)
        json(json, ContentType.Text.Html)
        json(json, ContentType.Text.Plain)
    }

    install(ContentEncoding) {
        gzip()
        deflate()
    }
}

private const val PAGE_SIZE = 8
private const val HEAD_CUT_LIMIT = 30

/**
 * KuGou Lyrics Library
 * Modified from [ViMusic](https://github.com/vfsfitvnm/ViMusic)
 */
object KuGou {
    var useTraditionalChinese: Boolean = false

    suspend fun getLyrics(title: String, artist: String, duration: Int, album: String? = null): Result<String> =
        runCatching {
            val keyword = generateKeyword(title, artist, album)
            getLyricsCandidate(keyword, duration)?.let { candidate ->
                Base64.Default.decode(downloadLyrics(candidate.id, candidate.accesskey).content).decodeToString()
                    .normalize()
            } ?: throw IllegalStateException("No lyrics candidate")
        }

    /** What KuGou matched: the lyrics and the name, singer and length (seconds) of the song they belong to. */
    class Found(val lyrics: String, val song: String, val singer: String, val durationSeconds: Double?)

    /**
     * Like [getLyrics], but every hit must pass [accepts] (song name, singer, length in seconds or null) and the
     * caller gets the metadata of the hit back. The query still drops the brackets (KuGou's search is loose); the check
     * is what keeps "(Acoustic Version)" from taking the original's lyrics.
     */
    suspend fun findLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
        accepts: (song: String, singer: String, seconds: Double?) -> Boolean,
    ): Result<Found> =
        runCatching {
            val keyword = generateKeyword(title, artist, album)
            val songs = searchSongs(keyword).data.info
            // closest length first among the accepted songs
            songs
                .filter { duration == -1 || abs(it.duration - duration) <= DURATION_TOLERANCE }
                .filter { accepts(it.songName, it.singerName, it.duration.toDouble()) }
                .sortedBy { if (duration > 0) abs(it.duration - duration) else 0 }
                .forEach { song ->
                    val candidate = searchLyricsByHash(song.hash).candidates.firstOrNull() ?: return@forEach
                    val text = download(candidate)?.let { stripHeaderAndLabels(it, title, artist) } ?: return@forEach
                    return@runCatching Found(text, song.songName, song.singerName, song.duration.toDouble())
                }
            val candidate =
                searchLyricsByKeyword(keyword, duration).candidates.firstOrNull {
                    accepts(it.song, it.singer, it.duration.takeIf { d -> d > 0 }?.div(1000.0))
                } ?: throw IllegalStateException("No lyrics candidate")
            val text = download(candidate)?.let { stripHeaderAndLabels(it, title, artist) } ?: throw IllegalStateException("Empty lyrics")
            Found(text, candidate.song, candidate.singer, candidate.duration.takeIf { it > 0 }?.div(1000.0))
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }

    private val LINE_TEXT = Regex("""^\[\d\d:\d\d\.\d{2,3}\](.*)$""")

    /**
     * KuGou puts a title line in front of many lyrics ("Untitled (Explicit) - Rex Orange County" at 0:00, live probe
     * 2026-11-12) and singer labels ("DaBaby：") between verses; both were shown as sung lines. The title line is
     * dropped when it is one of the first three lines and names the song and the artist; a label is a short line that
     * ends with a colon.
     */
    fun stripHeaderAndLabels(
        lrc: String,
        title: String,
        artist: String,
    ): String {
        val norm = { s: String -> s.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim() }
        val wantedTitle = norm(title.replace(Regex("""[(\[（【].*?[)\]）】]"""), " "))
        val artists = artist.split(Regex("""\s*(?:,|&|、| x | and | feat\.? | ft\.? )\s*""", RegexOption.IGNORE_CASE)).map(norm).filter { it.isNotEmpty() }
        val lines = lrc.lines()
        return lines
            .filterIndexed { i, line ->
                val text = LINE_TEXT.find(line)?.groupValues?.get(1)?.trim() ?: return@filterIndexed true
                val isHeader =
                    i < 3 && text.contains(" - ") && wantedTitle.isNotEmpty() &&
                        norm(text).contains(wantedTitle) && artists.any { norm(text).contains(it) }
                val isLabel = text.length in 2..30 && (text.endsWith(":") || text.endsWith("："))
                !isHeader && !isLabel
            }.joinToString("\n")
    }

    // "纯音乐，请欣赏" ("instrumental, enjoy") is KuGou's way of saying there are no words
    private val INSTRUMENTAL_NOTICE = Regex("""纯音乐|請欣賞|请欣赏""")

    private suspend fun download(candidate: SearchLyricsResponse.Candidate): String? =
        Base64.Default.decode(downloadLyrics(candidate.id, candidate.accesskey).content).decodeToString()
            .normalize()
            .takeIf { text -> text.isNotBlank() && !(text.lines().count { it.isNotBlank() } <= 3 && INSTRUMENTAL_NOTICE.containsMatchIn(text)) }

    suspend fun getAllPossibleLyricsOptions(
        title: String, artist: String, duration: Int, album: String? = null, callback: (String) -> Unit
    ) {
        val keyword = generateKeyword(title, artist, album)
        searchSongs(keyword).data.info.forEach {
            if (duration == -1 || abs(it.duration - duration) <= DURATION_TOLERANCE) {
                searchLyricsByHash(it.hash).candidates.firstOrNull()?.let { candidate ->
                    Base64.Default.decode(downloadLyrics(candidate.id, candidate.accesskey).content).decodeToString()
                        .normalize().let(callback)
                }
            }
        }
        searchLyricsByKeyword(keyword, duration).candidates.forEach { candidate ->
            Base64.Default.decode(downloadLyrics(candidate.id, candidate.accesskey).content).decodeToString()
                .normalize().let(callback)
        }
    }

    suspend fun getLyricsCandidate(
        keyword: Keyword, duration: Int
    ): SearchLyricsResponse.Candidate? {
        searchSongs(keyword).data.info.forEach { song ->
            // if duration == -1, we don't care duration; a hit must also be the same song, not just one of a similar length
            if ((duration == -1 || abs(song.duration - duration) <= DURATION_TOLERANCE) &&
                SongMatch.sameSong(song.songName, song.singerName, keyword.title, keyword.artist)
            ) {
                val candidate = searchLyricsByHash(song.hash).candidates.firstOrNull()
                if (candidate != null) return candidate
            }
        }
        return searchLyricsByKeyword(keyword, duration).candidates.firstOrNull {
            SongMatch.sameSong(it.song, it.singer, keyword.title, keyword.artist)
        }
    }

    suspend fun searchSongs(keyword: Keyword) =
        client.get("https://mobileservice.kugou.com/api/v3/search/song") {
            parameter("version", 9108)
            parameter("plat", 0)
            parameter("pagesize", PAGE_SIZE)
            parameter("showtype", 0)
            val searchQuery = buildString {
                append(keyword.title)
                append(" - ")
                append(keyword.artist)
                if (!keyword.album.isNullOrBlank()) {
                    append(" ")
                    append(keyword.album)
                }
            }
            url.encodedParameters.append(
                "keyword",
                searchQuery.encodeURLParameter(spaceToPlus = false)
            )
        }.body<SearchSongResponse>()

    private suspend fun searchLyricsByKeyword(keyword: Keyword, duration: Int) =
        client.get("https://lyrics.kugou.com/search") {
            parameter("ver", 1)
            parameter("man", "yes")
            parameter("client", "pc")
            parameter(
                "duration", duration.takeIf { it != -1 }?.times(1000)
            ) // if duration == -1, we don't care duration
            val searchQuery = buildString {
                append(keyword.title)
                append(" - ")
                append(keyword.artist)
                if (!keyword.album.isNullOrBlank()) {
                    append(" ")
                    append(keyword.album)
                }
            }
            url.encodedParameters.append(
                "keyword",
                searchQuery.encodeURLParameter(spaceToPlus = false)
            )
        }.body<SearchLyricsResponse>()

    private suspend fun searchLyricsByHash(hash: String) =
        client.get("https://lyrics.kugou.com/search") {
            parameter("ver", 1)
            parameter("man", "yes")
            parameter("client", "pc")
            parameter("hash", hash)
        }.body<SearchLyricsResponse>()

    private suspend fun downloadLyrics(id: Long, accessKey: String) =
        client.get("https://lyrics.kugou.com/download") {
            parameter("fmt", "lrc")
            parameter("charset", "utf8")
            parameter("client", "pc")
            parameter("ver", 1)
            parameter("id", id)
            parameter("accesskey", accessKey)
        }.body<DownloadLyricsResponse>()

    private fun normalizeTitle(title: String) =
        title.replace("\\(.*\\)".toRegex(), "").replace("（.*）".toRegex(), "")
            .replace("「.*」".toRegex(), "").replace("『.*』".toRegex(), "")
            .replace("<.*>".toRegex(), "").replace("《.*》".toRegex(), "")
            .replace("〈.*〉".toRegex(), "").replace("＜.*＞".toRegex(), "")

    private fun normalizeArtist(artist: String) =
        artist.replace(", ", "、").replace(" & ", "、").replace(".", "").replace("和", "、")
            .replace("\\(.*\\)".toRegex(), "").replace("（.*）".toRegex(), "")

    fun generateKeyword(title: String, artist: String, album: String? = null) =
        Keyword(normalizeTitle(title), normalizeArtist(artist), album)

    private fun String.normalize(): String =
        lines().filter { line -> line.matches(ACCEPTED_REGEX) }
            .let { lines ->
                // Remove useless information such as singer, writer, composer, guitar, etc.
                var headCutLine = 0
                for (i in min(HEAD_CUT_LIMIT, lines.lastIndex) downTo 0) {
                    if (lines[i].matches(BANNED_REGEX)) {
                        headCutLine = i + 1
                        break
                    }
                }
                val filteredLines = lines.drop(headCutLine)

                var tailCutLine = 0
                for (i in min(lines.size - HEAD_CUT_LIMIT, lines.lastIndex) downTo 0) {
                    if (lines[lines.lastIndex - i].matches(BANNED_REGEX)) {
                        tailCutLine = i + 1
                        break
                    }
                }
                val finalLines = filteredLines.dropLast(tailCutLine)

                return@let finalLines.joinToString("\n")
            }

    @Suppress("RegExpRedundantEscape")
    private val ACCEPTED_REGEX = "\\[(\\d\\d):(\\d\\d)\\.(\\d{2,3})\\].*".toRegex()
    private val BANNED_REGEX = ".+\\].+[:：].+".toRegex()

    private const val DURATION_TOLERANCE = 8
}
