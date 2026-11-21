package com.metrolist.innertube.pages

import com.metrolist.innertube.models.response.BrowseResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pieces of the history page, as made-up JSON in the shapes YouTube Music uses for lists: the first page with day
 * shelves, a section continuation, a shelf continuation and appended items. The real responses were NOT checked here.
 */
class HistoryChunkTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun item(id: String, seconds: String = "3:20") =
        """{"musicResponsiveListItemRenderer":{
            "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song $id"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist $id","navigationEndpoint":{"browseEndpoint":{"browseId":"UC$id"}}}]}}}
            ],
            "fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":[{"text":"$seconds"}]}}}],
            "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://t/$id","width":60,"height":60}]}}},
            "playlistItemData":{"videoId":"$id"}
        }}"""

    private fun token(value: String) = """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$value"}}}}"""

    private fun shelf(title: String?, items: List<String>, shelfToken: String? = null, nextData: String? = null): String {
        val titlePart = if (title == null) "" else """"title":{"runs":[{"text":"$title"}]},"""
        val all = items + listOfNotNull(shelfToken?.let(::token))
        val continuations = if (nextData == null) "" else ""","continuations":[{"nextContinuationData":{"continuation":"$nextData"}}]"""
        return """{"musicShelfRenderer":{$titlePart"contents":[${all.joinToString(",")}]$continuations}}"""
    }

    private fun firstPage(shelves: List<String>, sectionToken: String? = null): String {
        val continuations = if (sectionToken == null) "" else ""","continuations":[{"nextContinuationData":{"continuation":"$sectionToken"}}]"""
        return """{"responseContext":{},"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[${shelves.joinToString(",")}]$continuations}}}}]}}}"""
    }

    private fun parse(text: String): HistoryChunk = HistoryPage.parseChunk(json.decodeFromString<BrowseResponse>(text))

    @Test
    fun `the first page gives its day sections and both tokens`() {
        val chunk =
            parse(
                firstPage(
                    listOf(
                        shelf("Today", listOf(item("a"), item("b"))),
                        shelf("Yesterday", listOf(item("c")), shelfToken = "SHELF1"),
                    ),
                    sectionToken = "SEC1",
                ),
            )
        assertEquals(listOf("Today", "Yesterday"), chunk.sections.map { it.title })
        assertEquals(listOf("a", "b"), chunk.sections[0].songs.map { it.id })
        assertEquals(listOf("c"), chunk.sections[1].songs.map { it.id })
        assertEquals(200, chunk.sections[0].songs[0].duration)
        assertEquals("Song a", chunk.sections[0].songs[0].title)
        assertEquals("SEC1", chunk.sectionContinuation)
        assertEquals("SHELF1", chunk.shelfContinuation)
        assertEquals(emptyList<Any>(), chunk.moreSongs)
    }

    @Test
    fun `a shelf's own continuation list is read too`() {
        val chunk = parse(firstPage(listOf(shelf("Today", listOf(item("a")), nextData = "SHELF9")), sectionToken = null))
        assertEquals("SHELF9", chunk.shelfContinuation)
        assertNull(chunk.sectionContinuation)
    }

    @Test
    fun `a shelf without a title continues the section above it`() {
        val chunk = parse(firstPage(listOf(shelf("Today", listOf(item("a"))), shelf(null, listOf(item("b"), item("c"))))))
        assertEquals(1, chunk.sections.size)
        assertEquals(listOf("a", "b", "c"), chunk.sections[0].songs.map { it.id })
    }

    @Test
    fun `a title-less shelf at the very start goes to the songs of the section before`() {
        val chunk = parse(firstPage(listOf(shelf(null, listOf(item("b"))), shelf("Today", listOf(item("a"))))))
        assertEquals(listOf("b"), chunk.moreSongs.map { it.id })
        assertEquals(listOf("Today"), chunk.sections.map { it.title })
    }

    @Test
    fun `a section continuation gives the next days`() {
        val text =
            """{"responseContext":{},"continuationContents":{"sectionListContinuation":{
                "contents":[${shelf("Monday", listOf(item("d")))},${shelf("Sunday", listOf(item("e")), shelfToken = "SHELF2")}],
                "continuations":[{"nextContinuationData":{"continuation":"SEC2"}}]}}}"""
        val chunk = parse(text)
        assertEquals(listOf("Monday", "Sunday"), chunk.sections.map { it.title })
        assertEquals("SEC2", chunk.sectionContinuation)
        assertEquals("SHELF2", chunk.shelfContinuation)
    }

    @Test
    fun `a shelf continuation gives more songs of the last section`() {
        val text =
            """{"responseContext":{},"continuationContents":{"musicShelfContinuation":{
                "contents":[${item("f")},${item("g")}],
                "continuations":[{"nextContinuationData":{"continuation":"SHELF3"}}]}}}"""
        val chunk = parse(text)
        assertEquals(emptyList<Any>(), chunk.sections)
        assertEquals(listOf("f", "g"), chunk.moreSongs.map { it.id })
        assertEquals("SHELF3", chunk.shelfContinuation)
        assertNull(chunk.sectionContinuation)
    }

    @Test
    fun `appended items give more songs and a token`() {
        val text =
            """{"responseContext":{},"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[${item("h")},${token("SHELF4")}]}}]}"""
        val chunk = parse(text)
        assertEquals(listOf("h"), chunk.moreSongs.map { it.id })
        assertEquals("SHELF4", chunk.shelfContinuation)
    }

    @Test
    fun `a response with no history gives nothing, and nothing throws`() {
        for (text in listOf("""{"responseContext":{}}""", firstPage(emptyList()), """{"responseContext":{},"continuationContents":{}}""")) {
            val chunk = parse(text)
            assertEquals(emptyList<Any>(), chunk.sections)
            assertEquals(emptyList<Any>(), chunk.moreSongs)
            assertNull(chunk.sectionContinuation)
            assertNull(chunk.shelfContinuation)
        }
    }

    @Test
    fun `an entry without an id or a thumbnail is left out and the rest is kept`() {
        val broken = """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"No id"}]}}}]}}"""
        val chunk = parse(firstPage(listOf(shelf("Today", listOf(broken, item("a"), broken)))))
        assertEquals(listOf("a"), chunk.sections.single().songs.map { it.id })
    }

    @Test
    fun `a shelf with a title and no contents is an empty section`() {
        val chunk = parse(firstPage(listOf("""{"musicShelfRenderer":{"title":{"runs":[{"text":"Today"}]}}}""")))
        assertEquals(listOf("Today"), chunk.sections.map { it.title })
        assertEquals(emptyList<Any>(), chunk.sections.single().songs)
    }
}
