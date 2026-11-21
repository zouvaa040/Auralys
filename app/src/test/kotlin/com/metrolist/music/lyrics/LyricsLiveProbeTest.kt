package com.metrolist.music.lyrics

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.lrclib.LrcLib
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * LIVE PROBE of the lyrics providers. Skipped unless `LIVE_LYRICS=1`, so it is never part of a normal run.
 *
 * For each song: the top YouTube Music catalogue song of the query (the same id, title, artists, length and album the app
 * would have), then every provider of the registry is asked the way the app asks it, and the answer is summed up (sync
 * kind, number of lines, first and last stamp, first words). The automatic choice is printed next to it.
 *
 * Env: `LIVE_LYRICS_OUT` report file; `LIVE_LYRICS_DUMP` folder for the fixtures (timing skeletons and hit lists, no
 * lyric text, see [LyricsProbeFixtures]); `LIVE_LYRICS_ONLY` a substring to run one song.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LyricsLiveProbeTest {
    private data class Pick(
        val query: String,
        val expectTitle: String,
        val expectArtist: String,
        val exact: Boolean = false,
        /** a music video upload (searched among videos), not the catalogue audio */
        val video: Boolean = false,
    )

    private val picks =
        listOf(
            Pick("Untitled Rex Orange County", "Untitled", "Rex Orange County"),
            Pick("La La Lost You Acoustic Version NIKI", "La La Lost You (Acoustic", "NIKI"),
            Pick("La La Lost You NIKI", "La La Lost You", "NIKI", exact = true),
            Pick("Dracula Tame Impala", "Dracula", "Tame Impala", exact = true),
            Pick("Dracula feat JENNIE Tame Impala", "Dracula (feat", "Tame Impala"),
            Pick("The Less I Know The Better Tame Impala", "The Less I Know", "Tame Impala"),
            Pick("Bahagia Lagi Piche Kota", "Bahagia Lagi", "Piche"),
            Pick("Monolog Pamungkas", "Monolog", "Pamungkas"),
            Pick("penjaga hati Nadhif Basalamah", "penjaga hati", "Nadhif"),
            Pick("Tak Ingin Usai Keisya Levronka", "Tak Ingin Usai", "Keisya"),
            Pick("Komang Raim Laode", "Komang", "Raim"),
            Pick("Sial Mahalini", "Sial", "Mahalini"),
            Pick("Rumah ke Rumah Hindia", "Rumah ke Rumah", "Hindia"),
            Pick("Snooze SZA", "Snooze", "SZA"),
            Pick("Blinding Lights The Weeknd", "Blinding Lights", "Weeknd"),
            Pick("Die With A Smile Lady Gaga Bruno Mars", "Die With A Smile", "Lady Gaga"),
            Pick("Levitating feat DaBaby Dua Lipa", "Levitating", "Dua Lipa"),
            Pick("Bohemian Rhapsody Remastered 2011 Queen", "Bohemian Rhapsody", "Queen"),
            Pick("Despacito Remix Luis Fonsi Justin Bieber", "Despacito", "Luis Fonsi"),
            Pick("Hotel California Live Eagles", "Hotel California", "Eagles"),
            Pick("Die For You sped up The Weeknd", "Die For You", "Weeknd"),
            Pick("Glimpse of Us Joji", "Glimpse of Us", "Joji"),
            Pick("Seasons wave to earth", "seasons", "wave to earth"),
            Pick("Somewhere Only We Know Keane", "Somewhere Only We Know", "Keane"),
            Pick("Espresso Sabrina Carpenter", "Espresso", "Sabrina"),
            Pick("Line Without a Hook Ricky Montgomery", "Line Without a Hook", "Ricky"),
            // fix L (review 2026-11-12): labels, bare ft., full-width brackets, music video uploads
            Pick("Here Comes The Sun Remastered 2009 The Beatles", "Here Comes The Sun", "Beatles"),
            Pick("Under Pressure Remastered 2011 Queen David Bowie", "Under Pressure", "Queen"),
            Pick("光年之外 G.E.M.", "光年之外", "G.E.M"),
            Pick("This Is What You Came For Calvin Harris ft Rihanna official video", "This Is What You Came For", "Calvin Harris", video = true),
            Pick("Blinding Lights The Weeknd official video", "Blinding Lights", "Weeknd", video = true),
            Pick("Denny Caknan Kartonyono Medot Janji official music video", "Kartonyono Medot Janji", "Denny Caknan", video = true),
            Pick("Die With A Smile Lady Gaga Bruno Mars official music video", "Die With A Smile", "Lady Gaga", video = true),
        )

    @Test
    fun probe() {
        assumeTrue("LIVE_LYRICS not set: the network probe is skipped", System.getenv("LIVE_LYRICS") == "1")
        YouTube.locale = YouTubeLocale(gl = "ID", hl = "id")
        val context = RuntimeEnvironment.getApplication()
        val out = ArrayList<String>()
        val dump = System.getenv("LIVE_LYRICS_DUMP")?.let { File(it).also(File::mkdirs) }
        val only = System.getenv("LIVE_LYRICS_ONLY")
        val summary = ArrayList<String>()
        runBlocking {
            for (pick in picks) {
                if (only != null && !pick.query.contains(only, ignoreCase = true)) continue
                val song =
                    runCatching {
                        YouTube.search(pick.query, if (pick.video) YouTube.SearchFilter.FILTER_VIDEO else YouTube.SearchFilter.FILTER_SONG)
                            .getOrThrow().items.filterIsInstance<SongItem>()
                    }.getOrNull().orEmpty()
                        .firstOrNull { s ->
                            (if (pick.exact) s.title.equals(pick.expectTitle, ignoreCase = true) else s.title.contains(pick.expectTitle, ignoreCase = true)) &&
                                s.artists.any { it.name.contains(pick.expectArtist, ignoreCase = true) }
                        }
                if (song == null) {
                    out += "### ${pick.query}: no catalogue song found"
                    continue
                }
                val duration = song.duration ?: -1
                val artist = song.artists.joinToString { LyricsQuery.artist(it.name) }
                val fullTitle = LyricsQuery.title(song.title)
                val oldTitle = LyricsUtils.cleanTitleForSearch(song.title)
                val album = song.album?.name
                out += ""
                out += "### ${song.title} | $artist | ${duration}s | album=$album | id=${song.id}"
                out += "    query old='$oldTitle' full='$fullTitle'"

                val results =
                    coroutineScope {
                        LyricsProviderRegistry.providerNames.map { key ->
                            async {
                                val provider = LyricsProviderRegistry.getProviderByName(key)!!
                                val title = if (provider.wantsFullTitle) fullTitle else oldTitle
                                val started = System.currentTimeMillis()
                                val text =
                                    runCatching {
                                        withTimeoutOrNull(15_000) {
                                            provider.getLyrics(context, song.id, title, artist, duration, album).getOrNull()
                                        }
                                    }.getOrNull()?.let { LyricsUtils.filterLyricsCreditLines(it) }?.takeIf { it.isNotBlank() }
                                Triple(key, text, System.currentTimeMillis() - started)
                            }
                        } +
                            async {
                                // the same server asked with the version words kept
                                val text =
                                    runCatching {
                                        withTimeoutOrNull(15_000) {
                                            com.metrolist.music.betterlyrics.BetterLyrics.getLyrics(fullTitle, artist, duration, album).getOrNull()
                                        }
                                    }.getOrNull()
                                Triple("BetterLyrics[full]", text, 0L)
                            }
                    }.awaitAll()

                val skeletons = LinkedHashMap<String, LyricsProbeFixtures.Skeleton?>()
                for ((key, text, ms) in results) {
                    val sk = text?.let { LyricsProbeFixtures.skeleton(it) }
                    skeletons[key] = sk
                    val q = text?.let { LyricsSelection.quality(it, duration) }
                    val first = text?.let { LyricsProbeFixtures.firstWords(it) }.orEmpty()
                    out +=
                        if (sk == null) {
                            "    %-18s miss (%d ms)".format(key, ms)
                        } else {
                            "    %-18s %-5s %3d lines  %s..%s  q=%s  (%d ms)  \"%s\"".format(
                                key, sk.kind, sk.times.size, fmt(sk.times.firstOrNull()), fmt(sk.times.lastOrNull()), q, ms, first,
                            )
                        }
                }

                // the new path: every provider asked the way LyricsHelper asks it now, every answer rated
                val isVideo = pick.video || song.musicVideoType?.let { it != "MUSIC_VIDEO_TYPE_ATV" } == true
                val request = LyricsRequest.of(song.id, song.title, song.artists.map { it.name }, duration, album, isVideo = isVideo)
                out += "    request title='${request.title}' search='${request.searchTitle}' video=$isVideo (${song.musicVideoType})"
                val newAnswers =
                    coroutineScope {
                        LyricsProviderRegistry.getDefaultProviderOrder().map { key ->
                            async {
                                val provider = LyricsProviderRegistry.getProviderByName(key)!!
                                val hit =
                                    runCatching {
                                        withTimeoutOrNull(15_000) { provider.fetch(context, request).getOrNull() }
                                    }.getOrNull()?.let { it.copy(text = LyricsUtils.filterLyricsCreditLines(it.text)) }?.takeIf { it.text.isNotBlank() }
                                Triple(key, provider.name, hit)
                            }
                        }
                    }.awaitAll()
                val newCandidates = ArrayList<LyricsSelection.Candidate>()
                val newFixture = LinkedHashMap<String, LyricsProbeFixtures.Answer?>()
                for ((key, name, hit) in newAnswers) {
                    val c = hit?.let { LyricsSelection.rate(name, request, it) }
                    if (c != null && key != "LyricsPlus") newCandidates += c
                    newFixture[key] = hit?.let { LyricsProbeFixtures.Answer(LyricsProbeFixtures.skeleton(it.text), it.title, it.artist, it.durationSeconds, it.keyedOnVideo) }
                    out +=
                        if (hit == null) {
                            "    NEW %-16s miss".format(key)
                        } else {
                            "    NEW %-16s %-8s %-5s first=%s hit='%s' / '%s' %ss \"%s\"".format(
                                key, c?.quality, c?.sync, fmt(LyricsSelection.firstSungMs(hit.text)), hit.title, hit.artist,
                                hit.durationSeconds?.let { "%.1f".format(it) }, LyricsProbeFixtures.firstWords(hit.text),
                            )
                        }
                }
                val chosen = LyricsSelection.best(newCandidates)
                out += "    CHOSEN(after): ${chosen?.provider}  outliers=${LyricsSelection.outliers(newCandidates).map { it.provider }}"
                summary +=
                    "| %s | %s | %ds | %s | %s %s | first %s |".format(
                        song.title, artist, duration, chosen?.provider ?: "none", chosen?.quality ?: "-", chosen?.sync ?: "",
                        fmt(chosen?.let { LyricsSelection.firstSungMs(it.lyrics) }),
                    )

                val lrclibHits =
                    runCatching { LrcLib.lyrics(artist, oldTitle).getOrThrow() }.getOrNull().orEmpty().take(12)
                lrclibHits.forEach { t ->
                    out += "      lrclib hit: '${t.trackName}' / '${t.artistName}' ${t.duration}s synced=${t.syncedLyrics != null} last=${fmt(t.syncedLyrics?.let { LyricsProbeFixtures.skeleton(it).times.lastOrNull() })}"
                }

                // the automatic choice of build 448: the old order, old titles, timing check only, first verified wins
                val oldOrder = listOf("BetterLyrics", "LrcLib", "KuGou", "SimpMusic", "BiniLyrics", "LrcRed", "Unison", "Paxsenix", "Zemer", "YouTubeSubtitle", "YouTube")
                val oldRated = oldOrder.mapNotNull { key -> results.firstOrNull { it.first == key }?.second?.let { key to LyricsSelection.quality(it, duration) } }
                val oldPick =
                    listOf(LyricsSelection.Quality.VERIFIED, LyricsSelection.Quality.SHORT, LyricsSelection.Quality.PLAIN)
                        .firstNotNullOfOrNull { q -> oldRated.firstOrNull { it.second == q }?.first }
                out += "    CHOSEN(before): $oldPick"

                dump?.let { dir ->
                    LyricsProbeFixtures.write(
                        File(dir, LyricsProbeFixtures.slug(pick.query) + ".txt"),
                        LyricsProbeFixtures.Song(song.id, song.title, artist, album, duration),
                        skeletons,
                        newFixture,
                        lrclibHits.map {
                            LyricsProbeFixtures.Hit(
                                it.trackName, it.artistName, it.duration,
                                it.syncedLyrics?.let { s -> LyricsProbeFixtures.skeleton(s) },
                                it.plainLyrics != null,
                            )
                        },
                    )
                }
                delay(400)
            }
        }
        out += ""
        out += "SUMMARY (fix L)"
        out += "| Song | Artist | Length | Chosen | Quality | First sung |"
        out += summary
        val report = out.joinToString("\n")
        println(report)
        System.getenv("LIVE_LYRICS_OUT")?.let { File(it).writeText(report) }
    }

    private fun fmt(ms: Long?): String = if (ms == null) "-" else "%d:%02d.%d".format(ms / 60000, ms / 1000 % 60, ms % 1000 / 100)
}
