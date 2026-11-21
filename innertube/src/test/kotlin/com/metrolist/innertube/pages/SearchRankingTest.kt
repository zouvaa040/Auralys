package com.metrolist.innertube.pages

import com.metrolist.innertube.YouTubeConstants
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_UGC
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixture data only: no network. Titles follow the owner's "negoro angin" example. */
class SearchRankingTest {
    private val medleyUpload =
        song("medley", "Laut Kidul / Crito Mustahil / Negoro Angin", "Warung Kopi", 590, MUSIC_VIDEO_TYPE_UGC)
    private val realSong = song("real", "Negoro Angin", "Denny Caknan", 224, MUSIC_VIDEO_TYPE_ATV)
    private val officialVideo = song("omv", "Negoro Angin (Official Music Video)", "Denny Caknan", 240, MUSIC_VIDEO_TYPE_OMV)
    private val cover = song("cover", "Negoro Angin (Cover)", "Nina", 230, MUSIC_VIDEO_TYPE_ATV)

    private fun song(
        id: String,
        title: String,
        artist: String,
        seconds: Int?,
        type: String? = null,
        wide: Boolean = false,
        label: Boolean = false,
    ) = SongItem(
        id = id,
        title = title,
        artists = listOf(Artist(artist, null)),
        duration = seconds,
        musicVideoType = type,
        thumbnail = "https://example.com/$id.jpg",
        isWideThumb = wide,
        hasVideoLabel = label,
    )

    private fun page(vararg summaries: SearchSummary) = SearchSummaryPage(summaries.toList())

    private fun top(vararg items: com.metrolist.innertube.models.YTItem) =
        SearchSummary(YouTubeConstants.DEFAULT_TOP_RESULT, items.toList(), isTopResult = true)

    @Test
    fun `video flags come from music video type, wide thumbnail or label`() {
        assertTrue(song("a", "x", "y", 10, MUSIC_VIDEO_TYPE_UGC).looksLikeVideo)
        assertTrue(song("b", "x", "y", 10, null, wide = true).looksLikeVideo)
        assertTrue(song("c", "x", "y", 10, null, label = true).looksLikeVideo)
        assertFalse(song("d", "x", "y", 10, MUSIC_VIDEO_TYPE_ATV).looksLikeVideo)
        assertFalse(song("e", "x", "y", 10, null).looksLikeVideo)
    }

    @Test
    fun `flat list keeps songs and drops every kind of video row`() {
        val artist = ArtistItem("UC1", "Denny Caknan", null, shuffleEndpoint = null, radioEndpoint = null)
        val items =
            listOf(
                medleyUpload,
                realSong,
                song("wide", "Negoro Angin Lirik", "Fan", 200, null, wide = true),
                song("label", "Negoro Angin Live", "Fan", 200, null, label = true),
                artist,
            )
        assertEquals(listOf("real", "UC1"), SearchRanking.musicOnly(items).map { it.id })
    }

    @Test
    fun `negoro angin - medley video card is dropped and the real song leads`() {
        val result =
            page(
                top(medleyUpload, song("row", "Crito Mustahil", "Warung Kopi", 300, MUSIC_VIDEO_TYPE_ATV)),
                SearchSummary("Songs", listOf(realSong, cover)),
                SearchSummary("Videos", listOf(medleyUpload, officialVideo)),
            ).musicOnly("negoro angin")

        val topSummary = result.summaries.first()
        assertTrue(topSummary.isTopResult)
        assertEquals(listOf("real"), topSummary.items.map { it.id })
        // The video shelf is gone, the leftover card song moved to Songs, no duplicates anywhere.
        assertEquals(listOf("Top result", "Songs"), result.summaries.map { it.title })
        assertEquals(listOf("cover", "row"), result.summaries[1].items.map { it.id })
        assertFalse(result.summaries.flatMap { it.items }.any { it.id == "medley" || it.id == "omv" })
    }

    @Test
    fun `all candidates are videos - top result disappears instead of showing a video`() {
        val result =
            page(top(medleyUpload), SearchSummary("Videos", listOf(medleyUpload, officialVideo)))
                .musicOnly("negoro angin")
        assertTrue(result.summaries.isEmpty())
    }

    @Test
    fun `song card that is a medley is replaced by the first matching ordinary song`() {
        // An upload tagged as a normal track but with three titles in one.
        val taggedMedley = medleyUpload.copy(musicVideoType = MUSIC_VIDEO_TYPE_ATV)
        val result =
            page(
                top(taggedMedley),
                SearchSummary("Songs", listOf(taggedMedley, song("other", "Dalan Liyane", "Guyon Waton", 250), realSong, cover)),
            ).musicOnly("negoro angin")

        assertEquals("real", result.summaries.first().items.first().id)
        // The medley is not thrown away, it just does not lead.
        assertTrue(result.summaries.flatMap { it.items }.any { it.id == "medley" })
        assertEquals(1, result.summaries.flatMap { it.items }.count { it.id == "real" })
    }

    @Test
    fun `far longer song than the matching songs does not lead`() {
        val mix = song("mix", "Negoro Angin", "Some Channel", 1500, MUSIC_VIDEO_TYPE_ATV)
        val picked = SearchRanking.pickTopSong("negoro angin", listOf(mix, realSong, cover))
        assertEquals("real", picked?.id)
    }

    @Test
    fun `a normal long song with no short match is kept`() {
        val long = song("long", "Negoro Angin", "Denny Caknan", 500, MUSIC_VIDEO_TYPE_ATV)
        assertEquals("long", SearchRanking.pickTopSong("negoro angin", listOf(long))?.id)
    }

    @Test
    fun `guard stays off when the query asks for a medley or a long mix`() {
        val medley = song("m", "Dangdut Medley Terbaru", "X", 700, MUSIC_VIDEO_TYPE_ATV)
        val normal = song("n", "Dangdut Medley Cover", "Y", 200, MUSIC_VIDEO_TYPE_ATV)
        assertEquals("m", SearchRanking.pickTopSong("dangdut medley", listOf(medley, normal))?.id)
        assertEquals("medley", SearchRanking.pickTopSong("laut kidul / negoro angin", listOf(taggedMedley(), realSong))?.id)
    }

    private fun taggedMedley() = medleyUpload.copy(musicVideoType = MUSIC_VIDEO_TYPE_ATV)

    @Test
    fun `guard keeps the first song when nothing else matches the query`() {
        val other = song("o", "Something Else", "Z", 200)
        assertEquals("medley", SearchRanking.pickTopSong("negoro angin", listOf(taggedMedley(), other))?.id)
    }

    @Test
    fun `artist card stays the top result and only videos are removed`() {
        val artist = ArtistItem("UC1", "Denny Caknan", null, shuffleEndpoint = null, radioEndpoint = null)
        val result =
            page(
                top(artist, realSong),
                SearchSummary("Songs", listOf(realSong, cover)),
                SearchSummary("Videos", listOf(officialVideo)),
            ).musicOnly("denny caknan")
        assertEquals(listOf("UC1", "real"), result.summaries.first().items.map { it.id })
        assertEquals(listOf("Top result", "Songs"), result.summaries.map { it.title })
    }

    @Test
    fun `healthy page is left alone`() {
        val input =
            page(
                top(realSong, cover),
                SearchSummary("Songs", listOf(realSong, cover)),
            )
        assertEquals(input, input.musicOnly("negoro angin"))
    }

    @Test
    fun `episodes are not treated as videos`() {
        val episode = song("ep", "Podcast", "Host", 1800, MUSIC_VIDEO_TYPE_UGC).copy(isEpisode = true)
        assertEquals(listOf("ep"), SearchRanking.musicOnly(listOf(episode)).map { it.id })
    }

    @Test
    fun `medley title detection is conservative`() {
        assertTrue(SearchRanking.looksLikeMedley("Laut Kidul / Crito Mustahil / Negoro Angin"))
        assertTrue(SearchRanking.looksLikeMedley("Koplo Nonstop Terbaru"))
        assertTrue(SearchRanking.looksLikeMedley("Medley Campursari"))
        assertFalse(SearchRanking.looksLikeMedley("AC/DC - Thunderstruck"))
        assertFalse(SearchRanking.looksLikeMedley("Judul A / Judul B"))
        assertFalse(SearchRanking.looksLikeMedley("Negoro Angin"))
    }

    @Test
    fun `null or blank query only removes videos`() {
        assertNull(SearchRanking.pickTopSong(null, emptyList()))
        assertEquals("medley", SearchRanking.pickTopSong("  ", listOf(taggedMedley(), realSong))?.id)
    }

    // ---- language independence: YouTube translates the shelf titles (hl=id: "Lagu", "Video", "Artis") ----

    private val artistRow = ArtistItem("UCden", "Denny Caknan", "https://example.com/a.jpg", shuffleEndpoint = null, radioEndpoint = null)
    private val otherArtist = ArtistItem("UCnik", "Niken Salindry", null, shuffleEndpoint = null, radioEndpoint = null)

    @Test
    fun `section kind comes from the items, not from the title`() {
        assertEquals(SearchSectionKind.SONGS, SearchSummary("Lagu", listOf(realSong, cover)).kind)
        assertEquals(SearchSectionKind.VIDEOS, SearchSummary("Video", listOf(medleyUpload, officialVideo)).kind)
        assertEquals(SearchSectionKind.ARTISTS, SearchSummary("Artis", listOf(artistRow)).kind)
        assertEquals(SearchSectionKind.TOP, SearchSummary("Hasil teratas", listOf(realSong), isTopResult = true).kind)
        assertEquals(SearchSectionKind.OTHER, SearchSummary("Lainnya", listOf(realSong, artistRow)).kind)
        assertEquals(SearchSectionKind.SONGS, SearchSummary("Songs", listOf(realSong)).kind)
    }

    @Test
    fun `kind survives copy so a section keeps its role after rows were removed`() {
        val shelf = SearchSummary("Lagu", listOf(realSong, cover, medleyUpload))
        assertEquals(SearchSectionKind.SONGS, shelf.copy(items = listOf(medleyUpload)).kind)
    }

    @Test
    fun `indonesian shelf titles - video card is replaced by the real song`() {
        val result =
            page(
                SearchSummary(
                    "Hasil teratas",
                    listOf(medleyUpload, song("row", "Crito Mustahil", "Warung Kopi", 300, MUSIC_VIDEO_TYPE_ATV)),
                    isTopResult = true,
                ),
                SearchSummary("Lagu", listOf(realSong, cover)),
                SearchSummary("Video", listOf(medleyUpload, officialVideo)),
            ).musicOnly("negoro angin")

        assertEquals(listOf("real"), result.summaries.first().items.map { it.id })
        assertEquals(listOf("Hasil teratas", "Lagu"), result.summaries.map { it.title })
        assertEquals(listOf("cover", "row"), result.summaries[1].items.map { it.id })
        assertFalse(result.summaries.flatMap { it.items }.any { it.id == "medley" || it.id == "omv" })
    }

    @Test
    fun `indonesian shelf titles - medley guard still finds the Lagu section`() {
        val taggedMedley = medleyUpload.copy(musicVideoType = MUSIC_VIDEO_TYPE_ATV)
        val result =
            page(
                SearchSummary("Hasil teratas", listOf(taggedMedley), isTopResult = true),
                SearchSummary("Lagu", listOf(taggedMedley, realSong, cover)),
            ).musicOnly("negoro angin")
        assertEquals("real", result.summaries.first().items.first().id)
    }

    @Test
    fun `the song that is the same recording as the video card leads, not just the first song`() {
        val other = song("other", "Dalan Liyane", "Guyon Waton", 250, MUSIC_VIDEO_TYPE_ATV)
        val input = page(top(officialVideo), SearchSummary("Lagu", listOf(other, realSong, cover)))
        val matched =
            SearchRanking.musicOnly(input, "negoro angin") { video, candidate -> candidate.id == "real" && video.id == "omv" }
        assertEquals("real", matched.summaries.first().items.first().id)
        // without the matcher the first shelf song would lead
        val plain = SearchRanking.musicOnly(input, "negoro angin")
        assertEquals("other", plain.summaries.first().items.first().id)
    }

    @Test
    fun `artist of the top song moves directly under it and leaves the Artists section`() {
        val album = com.metrolist.innertube.models.AlbumItem("MPREb_1", "OLAK1", title = "Album", artists = null, thumbnail = "x")
        val result =
            SearchRanking.arrange(
                page(
                    top(realSong),
                    SearchSummary("Lagu", listOf(cover)),
                    SearchSummary("Album", listOf(album)),
                    SearchSummary("Artis", listOf(otherArtist, artistRow)),
                ),
                "negoro angin",
                onlyMusic = true,
            )

        val topItems = result.summaries.first().items
        assertEquals("real", topItems[0].id)
        assertEquals("UCden", topItems[1].id)
        // the same artist is not listed twice, the other artist stays in its own section
        assertEquals(1, result.summaries.flatMap { it.items }.count { it.id == "UCden" })
        assertEquals(listOf("UCnik"), result.summaries.single { it.kind == SearchSectionKind.ARTISTS }.items.map { it.id })
        assertEquals(
            listOf(SearchSectionKind.TOP, SearchSectionKind.SONGS, SearchSectionKind.ALBUMS, SearchSectionKind.ARTISTS),
            result.summaries.map { it.kind },
        )
    }

    @Test
    fun `artist is matched by name when the song row carries no artist id`() {
        val noIdSong = realSong.copy(artists = listOf(Artist("DENNY  Caknan - Topic", null)))
        val result = SearchRanking.promoteArtist(page(top(noIdSong), SearchSummary("Artis", listOf(artistRow))))
        assertEquals(listOf("real", "UCden"), result.summaries.first().items.map { it.id })
        assertTrue(result.summaries.none { it.kind == SearchSectionKind.ARTISTS })
    }

    @Test
    fun `no artist row on the page leaves it alone and reports the missing artist`() {
        val input = page(top(realSong), SearchSummary("Lagu", listOf(cover)), SearchSummary("Artis", listOf(otherArtist)))
        assertEquals(input, SearchRanking.promoteArtist(input))
        assertEquals("Denny Caknan", SearchRanking.missingTopArtist(input)?.name)
        // an extra row fetched separately is placed under the song
        val fixed = SearchRanking.promoteArtist(input, artistRow)
        assertEquals(listOf("real", "UCden"), fixed.summaries.first().items.map { it.id })
        assertNull(SearchRanking.missingTopArtist(fixed))
    }

    @Test
    fun `artist top result is never rearranged`() {
        val input = page(top(artistRow, realSong), SearchSummary("Lagu", listOf(realSong, cover)))
        assertEquals(input, SearchRanking.promoteArtist(input))
        assertNull(SearchRanking.missingTopArtist(input))
    }

    @Test
    fun `arrange without the music-only setting still promotes the artist and keeps videos`() {
        val input =
            page(
                top(officialVideo),
                SearchSummary("Lagu", listOf(realSong)),
                SearchSummary("Video", listOf(officialVideo)),
                SearchSummary("Artis", listOf(artistRow)),
            )
        val out = SearchRanking.arrange(input, "negoro angin", onlyMusic = false)
        assertEquals(listOf("omv", "UCden"), out.summaries.first().items.map { it.id })
        assertTrue(out.summaries.any { it.kind == SearchSectionKind.VIDEOS })
    }

    @Test
    fun `arrange is idempotent`() {
        val input = page(top(realSong), SearchSummary("Lagu", listOf(cover)), SearchSummary("Artis", listOf(artistRow, otherArtist)))
        val once = SearchRanking.arrange(input, "negoro angin", onlyMusic = true)
        assertEquals(once, SearchRanking.arrange(once, "negoro angin", onlyMusic = true))
    }
}
