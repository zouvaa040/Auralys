package com.metrolist.innertube.pages

import com.metrolist.innertube.YouTubeConstants
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import java.text.Normalizer

/**
 * Pure logic that cleans the search results of the "All" and "Songs" views.
 *
 * Idea from BitChord (GPLv3): it never re-ranks, it only throws away every row that looks like a video
 * upload (a "Video" word in the subtitle or a non-square thumbnail), so the first song left is the real
 * song. The medley guard at the bottom is our own addition on top of that.
 */
object SearchRanking {
    // Labels only: a section is recognised by its kind (items), never by these English words.
    private const val SONGS_LABEL = "Songs"

    /** Words in the query that mean the user really wants a medley or a long mix. */
    private val MEDLEY_INTENT = setOf("medley", "mashup", "mix", "nonstop", "kompilasi", "compilation", "full", "album")

    /** A song this much longer than the matching songs is not "the" song (a mix or a live set). */
    private const val FAR_LONGER_FACTOR = 2.5
    private const val FAR_LONGER_MIN_SECONDS = 360

    private val WORD_SPLIT = Regex("[^\\p{L}\\p{N}]+")
    private val MEDLEY_WORD = Regex("\\b(medley|mashup|non-?stop)\\b", RegexOption.IGNORE_CASE)

    private fun YTItem.isVideoRow() = this is SongItem && !isEpisode && looksLikeVideo

    /** Drops video rows from a flat list (Songs chip). Albums, artists and playlists are kept. */
    fun musicOnly(items: List<YTItem>): List<YTItem> = items.filterNot { it.isVideoRow() }

    /**
     * All view: no videos anywhere (the whole "Videos" section goes), and the "Top result" must be a song
     * that fits the query. [query] may be null when it is not known: then only the video rows are removed.
     * [sameRecording] tells whether a catalogue song is the audio of a music video; when the Top result
     * card is a video, the song that is the same recording as that video leads (title, version and artist
     * decide, see the app's TrackMatcher). Without it the first ordinary song leads.
     */
    fun musicOnly(
        page: SearchSummaryPage,
        query: String?,
        sameRecording: ((video: SongItem, candidate: SongItem) -> Boolean)? = null,
    ): SearchSummaryPage {
        val topOriginal = page.summaries.firstOrNull { it.isTopResult }
        val cardWasVideo = topOriginal?.items?.firstOrNull()?.isVideoRow() == true

        val cleaned =
            page.summaries
                .mapNotNull { summary ->
                    if (summary.kind == SearchSectionKind.VIDEOS) return@mapNotNull null
                    val kept = summary.items.filterNot { it.isVideoRow() }
                    if (kept.isEmpty()) null else summary.copy(items = kept)
                }.toMutableList()

        if (topOriginal == null) return SearchSummaryPage(cleaned)

        val topIndex = cleaned.indexOfFirst { it.isTopResult }
        val topKept = cleaned.getOrNull(topIndex)
        val songsIndex = cleaned.indexOfFirst { !it.isTopResult && it.kind == SearchSectionKind.SONGS }
        val songsKept = cleaned.getOrNull(songsIndex)

        // An artist, album or playlist card is a fine top result: leave it alone.
        if (!cardWasVideo && topKept?.items?.firstOrNull() !is SongItem) return SearchSummaryPage(cleaned)

        // Candidate order: the card's own song first (if it is a real song), then the rest of the card,
        // then the Songs section in YouTube's order. When the card was a video the Songs section leads.
        val cardSongs = topKept?.items?.filterIsInstance<SongItem>().orEmpty()
        val shelfSongs = songsKept?.items?.filterIsInstance<SongItem>().orEmpty()
        val videoCard = topOriginal.items.firstOrNull() as? SongItem
        val sameAsVideo =
            if (cardWasVideo && videoCard != null && sameRecording != null) {
                shelfSongs.filter { sameRecording(videoCard, it) }
            } else {
                emptyList()
            }
        val candidates =
            (if (cardWasVideo) sameAsVideo + shelfSongs + cardSongs else cardSongs + shelfSongs).distinctBy { it.id }

        val best = pickTopSong(query, candidates) ?: return SearchSummaryPage(cleaned)
        val currentFirst = topKept?.items?.firstOrNull()
        if (!cardWasVideo && currentFirst?.id == best.id) return SearchSummaryPage(cleaned)

        // Leftover card songs of a dropped video card belong to the Songs list, not to the Top result.
        val leftoverSongs = if (cardWasVideo) cardSongs.filter { it.id != best.id } else emptyList()
        val newSongsItems =
            (songsKept?.items.orEmpty().filter { it.id != best.id } + leftoverSongs).distinctBy { it.id }
        // What stays under the top result: not the new leader, not a song that is already in Songs.
        val songIds = newSongsItems.mapTo(HashSet()) { it.id }
        val topRest =
            topKept
                ?.items
                .orEmpty()
                .filter { it.id != best.id && it.id !in songIds && !(cardWasVideo && it is SongItem) }
        val newTop =
            (topKept ?: SearchSummary(YouTubeConstants.DEFAULT_TOP_RESULT, emptyList(), isTopResult = true, kind = SearchSectionKind.TOP))
                .copy(items = listOf<YTItem>(best) + topRest)

        val result = cleaned.toMutableList()
        // Replace the Songs section first (its index is still valid), then the top section.
        if (songsIndex >= 0) {
            if (newSongsItems.isEmpty()) {
                result.removeAt(songsIndex)
            } else {
                result[songsIndex] = songsKept!!.copy(items = newSongsItems)
            }
        } else if (newSongsItems.isNotEmpty()) {
            result.add(topIndex.coerceAtLeast(-1) + 1, SearchSummary(SONGS_LABEL, newSongsItems, kind = SearchSectionKind.SONGS))
        }
        val topNow = result.indexOfFirst { it.isTopResult }
        if (topNow >= 0) result[topNow] = newTop else result.add(0, newTop)
        return SearchSummaryPage(result)
    }

    /**
     * The All view order the owner asked for: the top song, the artist of that song right below it, then the
     * remaining sections (Songs, Albums, ...). [musicOnly] first when [onlyMusic] is set, then the artist
     * is moved up. [extraArtist] is an artist row fetched separately when the page had none for the top song.
     */
    fun arrange(
        page: SearchSummaryPage,
        query: String?,
        onlyMusic: Boolean,
        sameRecording: ((video: SongItem, candidate: SongItem) -> Boolean)? = null,
        extraArtist: ArtistItem? = null,
    ): SearchSummaryPage {
        val base = if (onlyMusic) musicOnly(page, query, sameRecording) else page
        return promoteArtist(base, extraArtist)
    }

    /**
     * Puts the artist of the top song directly under it (second row of the Top result section). The artist is
     * taken from the page's Artists section (id first, else the same normalised name) or from [extra]; it is
     * removed from its old place so it never shows twice. Nothing happens when the top result is not a song,
     * when an artist already follows it, or when no artist row is available.
     */
    fun promoteArtist(
        page: SearchSummaryPage,
        extra: ArtistItem? = null,
    ): SearchSummaryPage {
        val topIndex = page.summaries.indexOfFirst { it.isTopResult }
        if (topIndex < 0) return page
        val top = page.summaries[topIndex]
        val song = top.items.firstOrNull() as? SongItem ?: return page
        if (top.items.getOrNull(1) is ArtistItem) return page

        val wantedIds = song.artists.mapNotNull { it.id }.toSet()
        val wantedNames = song.artists.map { normalizeName(it.name) }.filter { it.isNotEmpty() }.toSet()
        if (wantedIds.isEmpty() && wantedNames.isEmpty()) return page

        val artistsIndex = page.summaries.indexOfFirst { !it.isTopResult && it.kind == SearchSectionKind.ARTISTS }
        val pool =
            page.summaries
                .getOrNull(artistsIndex)
                ?.items
                .orEmpty()
                .filterIsInstance<ArtistItem>() + listOfNotNull(extra)
        val found =
            pool.firstOrNull { it.id in wantedIds }
                ?: pool.firstOrNull { normalizeName(it.title) in wantedNames }
                ?: return page

        val result = page.summaries.toMutableList()
        result[topIndex] = top.copy(items = listOf(song, found) + top.items.drop(1).filter { it.id != found.id })
        if (artistsIndex >= 0) {
            val rest = result[artistsIndex].items.filter { it.id != found.id }
            if (rest.isEmpty()) result.removeAt(artistsIndex) else result[artistsIndex] = result[artistsIndex].copy(items = rest)
        }
        return SearchSummaryPage(result)
    }

    /**
     * The artist of the top song that [promoteArtist] could not place from the page itself (so the caller can
     * look the artist up with one extra search), or null when nothing is missing.
     */
    fun missingTopArtist(page: SearchSummaryPage): Artist? {
        val top = page.summaries.firstOrNull { it.isTopResult } ?: return null
        val song = top.items.firstOrNull() as? SongItem ?: return null
        if (top.items.getOrNull(1) is ArtistItem) return null
        val artist = song.artists.firstOrNull { it.name.isNotBlank() } ?: return null
        return if (promoteArtist(page, null) == page) artist else null
    }

    /** True when [item] could be the artist [artist] (same id, or the same normalised name). */
    fun isArtist(
        item: ArtistItem,
        artist: Artist,
    ): Boolean = (artist.id != null && item.id == artist.id) || normalizeName(item.title) == normalizeName(artist.name)

    private fun normalizeName(name: String): String =
        Normalizer
            .normalize(name.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("\\s*-\\s*topic$"), "")
            .split(WORD_SPLIT)
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    /**
     * Medley guard. [candidates] are the songs in the order YouTube gave them; the first one is the current
     * top result. Returns the song that should lead: the first candidate unless it is a medley or far longer
     * than the songs that match the query, in which case it is the first matching, ordinary song. If there is
     * no such song, or the query itself asks for a medley, the first candidate stays.
     */
    fun pickTopSong(
        query: String?,
        candidates: List<SongItem>,
    ): SongItem? {
        val first = candidates.firstOrNull() ?: return null
        val tokens = tokens(query.orEmpty())
        if (tokens.isEmpty() || query.orEmpty().contains('/') || tokens.any { it in MEDLEY_INTENT }) return first
        if (!isSuspicious(first, candidates, tokens)) return first
        return candidates.drop(1).firstOrNull { matches(it, tokens) && !isSuspicious(it, candidates, tokens) }
            ?: first
    }

    /** "A / B / C" (three or more titles in one) or the word medley/mashup/nonstop in the title. */
    fun looksLikeMedley(title: String): Boolean {
        if (title.split(" / ").count { it.isNotBlank() } >= 3) return true
        return MEDLEY_WORD.containsMatchIn(title)
    }

    private fun isSuspicious(
        song: SongItem,
        all: List<SongItem>,
        queryTokens: Set<String>,
    ): Boolean {
        if (looksLikeMedley(song.title)) return true
        val length = song.duration ?: return false
        if (length < FAR_LONGER_MIN_SECONDS) return false
        val reference =
            all
                .filter { it.id != song.id && matches(it, queryTokens) && !looksLikeMedley(it.title) }
                .mapNotNull { it.duration }
                .sorted()
        if (reference.isEmpty()) return false
        val median = reference[reference.size / 2]
        return length > median * FAR_LONGER_FACTOR
    }

    /** Every query word appears in the title, or else in title plus artist names. */
    private fun matches(
        song: SongItem,
        queryTokens: Set<String>,
    ): Boolean {
        val titleTokens = tokens(song.title)
        if (titleTokens.containsAll(queryTokens)) return true
        val withArtists = titleTokens + song.artists.flatMap { tokens(it.name) }
        return withArtists.containsAll(queryTokens)
    }

    private fun tokens(text: String): Set<String> =
        Normalizer
            .normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .split(WORD_SPLIT)
            .filter { it.isNotEmpty() }
            .toSet()
}
