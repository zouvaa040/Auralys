/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.LibraryFilter
import com.metrolist.music.db.entities.Artist
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.ui.component.ChipsRow
import com.metrolist.music.ui.component.GridItem
import com.metrolist.music.ui.component.HeroAuthorRow
import com.metrolist.music.ui.component.HeroHeader
import com.metrolist.music.ui.component.HeroIconButton
import com.metrolist.music.ui.component.HeroPillButton
import com.metrolist.music.ui.component.HeroPlayButton
import com.metrolist.music.ui.component.ListItem
import com.metrolist.music.ui.component.NavigationTitle
import com.metrolist.music.ui.component.RowDragGrip
import com.metrolist.music.ui.component.RowMoreButton
import com.metrolist.music.ui.component.SearchSourceSwitch
import com.metrolist.music.ui.component.painterResource
import com.metrolist.music.ui.player.QueueTrackRow
import com.metrolist.music.ui.screens.CommunityPlaylistTile
import com.metrolist.music.ui.screens.DailyDiscoverTile
import com.metrolist.music.ui.screens.MoodAndGenresButton
import com.metrolist.music.ui.screens.library.CollectionEntry
import com.metrolist.music.ui.screens.library.CollectionIndex
import com.metrolist.music.ui.screens.library.LibraryTabs
import com.metrolist.music.ui.screens.search.SearchDiscoveryContent
import com.metrolist.music.ui.screens.search.SearchField
import com.metrolist.music.ui.screens.search.SuggestionItem
import com.metrolist.music.ui.theme.ColorMode
import com.metrolist.music.ui.theme.MetrolistTheme
import com.metrolist.music.ui.theme.UiStyle
import com.metrolist.music.ui.theme.clampCoverTint
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The pages of the media sweep (Horizon sweep B) drawn with the real components and fake data: the playlist / album /
 * artist header, the home shelves, the search page, the library, the queue and the song rows. Light and dark, Kaca and
 * Datar. Output: build/design-gallery (one PNG per test). No device: the backdrop blur and the lens do not render here.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h860dp-xhdpi")
class DesignGalleryMediaTest {
    @get:Rule
    val rule = createComposeRule()

    private fun shot(
        name: String,
        dark: Boolean,
        style: UiStyle = UiStyle.GLASS,
        colorMode: ColorMode = ColorMode.NETRAL,
        coverColor: Color? = null,
        staticClock: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        if (staticClock) rule.mainClock.autoAdvance = false
        rule.setContent {
            MetrolistTheme(darkTheme = dark, style = style, colorMode = colorMode, coverColor = coverColor) {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) { content() }
            }
        }
        if (staticClock) rule.mainClock.advanceTimeBy(600) else rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/design-gallery").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(bmp.width > 100 && bmp.height > 100)
    }

    // ------------------------------------------------------------------ fake covers

    /** A picture-like cover: a gradient, a sun and a horizon, so the fade and the text contrast can be judged. */
    @Composable
    private fun FakeCover(a: Long, b: Long, sun: Long, modifier: Modifier = Modifier) {
        Canvas(modifier.background(Brush.linearGradient(listOf(Color(a), Color(b))))) {
            drawCircle(Color(sun), radius = size.width * 0.22f, center = Offset(size.width * 0.66f, size.height * 0.34f))
            drawRect(Color.Black.copy(alpha = 0.22f), topLeft = Offset(0f, size.height * 0.62f), size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.38f))
        }
    }

    private fun song(id: String, title: String, artist: String): Song =
        Song(song = SongEntity(id = id, title = title, duration = 215), artists = listOf(ArtistEntity(id = "a_$id", name = artist)))

    // ------------------------------------------------------------------ the header page

    @Composable
    private fun HeroPage(a: Long, b: Long, sun: Long, tint: Color? = null) {
        val inset = 88.dp
        CompositionLocalProvider(LocalPlayerAwareWindowInsets provides WindowInsets(top = inset)) {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(contentPadding = PaddingValues(top = inset, bottom = 100.dp)) {
                    item {
                        HeroHeader(
                            title = "Negoro Angin",
                            cover = { FakeCover(a, b, sun, Modifier.fillMaxSize()) },
                            meta = "2024 • 18 lagu • 1 j 9 mnt",
                            subtitle = { HeroAuthorRow(name = "Denny Caknan", avatarUrl = null) },
                            tint = tint,
                            topInset = inset,
                        ) {
                            HeroPlayButton(label = "Putar", icon = R.drawable.play, onClick = {}, modifier = Modifier.weight(1f))
                            HeroIconButton(icon = R.drawable.favorite_border, contentDescription = "Suka", onClick = {})
                            HeroIconButton(icon = R.drawable.more_horiz, contentDescription = "Opsi lainnya", onClick = {})
                        }
                    }
                    item { SongRow("Muak - Sped Up", "Aziz Harun", 0xFF3A7B5A, 0xFFB9D26A, active = true) }
                    item { SongRow("Favorite Girl", "Justin Bieber", 0xFF2F8FE0, 0xFF6AD1F2) }
                    item { SongRow("Hannah Montana", "Rex Orange County", 0xFFE08A3C, 0xFFF2D06A) }
                    item { SongRow("Cinta dan Rahasia", "Yura Yunita", 0xFF7B5BD6, 0xFFE0508A) }
                }
                // the page's top bar: status bar, then the bar with a back arrow and one action
                Row(Modifier.fillMaxWidth().height(inset).padding(top = 24.dp, start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.arrow_back), null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(12.dp))
                    Spacer(Modifier.weight(1f))
                    Icon(painterResource(R.drawable.search), null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(12.dp))
                }
            }
        }
    }

    @Composable
    private fun SongRow(title: String, artist: String, c1: Long, c2: Long, active: Boolean = false, selected: Boolean = false) {
        ListItem(
            title = title,
            subtitle = AnnotatedString(artist),
            thumbnailContent = {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Brush.linearGradient(listOf(Color(c1), Color(c2)))))
            },
            trailingContent = { RowMoreButton(onClick = {}) },
            isActive = active,
            isSelected = selected,
        )
    }

    @Test fun heroKacaLightBright() = shot("hero-kaca-light-bright", false) { HeroPage(0xFFF4C95D, 0xFFE8743B, 0xFFFFF3C4) }
    @Test fun heroKacaDarkDarkCover() = shot("hero-kaca-dark-darkcover", true) { HeroPage(0xFF0B1B3A, 0xFF1F3B73, 0xFF6AD1F2) }
    @Test fun heroKacaLightDarkCover() = shot("hero-kaca-light-darkcover", false) { HeroPage(0xFF0B1B3A, 0xFF1F3B73, 0xFF6AD1F2) }
    @Test fun heroDatarLight() = shot("hero-datar-light", false, UiStyle.EDITORIAL) { HeroPage(0xFF7B5BD6, 0xFFE0508A, 0xFFFFD6E8) }
    @Test fun heroSampulDark() =
        shot("hero-sampul-dark", true, colorMode = ColorMode.SAMPUL, coverColor = Color(0xFF3F7AC2)) {
            HeroPage(0xFF2F8FE0, 0xFF1F3B73, 0xFF6AD1F2, tint = clampCoverTint(Color(0xFF3F7AC2)))
        }
    @Test fun heroSampulLight() =
        shot("hero-sampul-light", false, colorMode = ColorMode.SAMPUL, coverColor = Color(0xFFE08A3C)) {
            HeroPage(0xFFE08A3C, 0xFFB5482A, 0xFFFFE2A8, tint = clampCoverTint(Color(0xFFE08A3C)))
        }

    // ------------------------------------------------------------------ home shelves

    @Composable
    private fun HomePage() {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 40.dp, bottom = 40.dp)) {
            item {
                Text("Beranda", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            item { NavigationTitle(title = "Dengar lagi", onPlayAllClick = {}) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                    items(listOf(0xFF7B5BD6 to 0xFFE0508A, 0xFF2F8FE0 to 0xFF6AD1F2, 0xFF3A7B5A to 0xFFB9D26A)) { (c1, c2) ->
                        GridItem(
                            title = "Judul lagu yang panjang sekali",
                            subtitle = "Artis • 3:42",
                            thumbnailContent = {
                                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Color(c1), Color(c2)))))
                            },
                        )
                    }
                }
            }
            item { NavigationTitle(title = "Dari komunitas") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(2) {
                        CommunityPlaylistTile(
                            coverUrls = listOf(null, null, null, null),
                            title = "Dangdut koplo santai untuk perjalanan",
                            author = "Dapz",
                            onClick = {},
                            onLongClick = {},
                            onPlay = {},
                        )
                    }
                }
            }
            item { NavigationTitle(title = "Baru untuk kamu") }
            item {
                Box(Modifier.fillMaxWidth().height(400.dp).padding(horizontal = 20.dp)) {
                    DailyDiscoverTile(
                        cover = null,
                        reason = "Mirip dengan Favorite Girl • Justin Bieber",
                        title = "Negoro Angin",
                        byline = "Denny Caknan • 12 putaran",
                        onClick = {},
                        onLongClick = {},
                        modifier = Modifier.width(320.dp),
                    )
                }
            }
            item { NavigationTitle(title = "Suasana dan genre", onClick = {}) }
            item {
                Column(Modifier.padding(horizontal = 14.dp)) {
                    listOf("Santai" to "Semangat", "Fokus" to "Tidur").forEach { (l, r) ->
                        Row {
                            MoodAndGenresButton(l, {}, Modifier.weight(1f).padding(6.dp))
                            MoodAndGenresButton(r, {}, Modifier.weight(1f).padding(6.dp))
                        }
                    }
                }
            }
        }
    }

    @Test fun homeLight() = shot("home-light", false) { HomePage() }
    @Test fun homeDark() = shot("home-dark", true) { HomePage() }
    @Test fun homeDatarLight() = shot("home-datar-light", false, UiStyle.EDITORIAL) { HomePage() }

    // ------------------------------------------------------------------ search

    @Composable
    private fun SearchPage() {
        Column(Modifier.fillMaxSize().padding(top = 32.dp)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.arrow_back), null, modifier = Modifier.padding(end = 12.dp))
                SearchField(
                    value = TextFieldValue(""),
                    onValueChange = {},
                    placeholder = "Cari di YouTube Music",
                    onSearch = {},
                    modifier = Modifier.weight(1f),
                )
            }
            SearchSourceSwitch(options = listOf("YouTube", "Perangkat"), selectedIndex = 0, onSelect = {}, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            LazyColumn(Modifier.weight(1f)) {
                item { SuggestionItem(query = "negoro angin", online = false, onClick = {}, onFillTextField = {}, onDelete = {}, pureBlack = false) }
                item { SuggestionItem(query = "denny caknan", online = false, onClick = {}, onFillTextField = {}, onDelete = {}, pureBlack = false) }
                item {
                    SearchDiscoveryContent(
                        recent = listOf(song("1", "Negoro Angin", "Denny Caknan"), song("2", "Favorite Girl", "Justin Bieber"), song("3", "Muak", "Aziz Harun")),
                        top = listOf(song("4", "Cinta dan Rahasia", "Yura Yunita"), song("5", "Hannah Montana", "Rex Orange County")),
                        artists = listOf("Denny Caknan", "Yura Yunita", "Aziz Harun").map { Artist(ArtistEntity(id = it, name = it), songCount = 3) },
                        onPlay = { _, _ -> },
                        onArtist = {},
                        onSearch = {},
                    )
                }
            }
        }
    }

    @Test fun searchLight() = shot("search-light", false) { SearchPage() }
    @Test fun searchDark() = shot("search-dark", true) { SearchPage() }
    @Test fun searchDatarLight() = shot("search-datar-light", false, UiStyle.EDITORIAL) { SearchPage() }

    // ------------------------------------------------------------------ library

    @Composable
    private fun LibraryPage() {
        Column(Modifier.fillMaxSize().padding(top = 40.dp)) {
            Text("Pustaka", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            LibraryTabs(current = LibraryFilter.PLAYLISTS, onSelect = {})
            CollectionIndex(
                listOf(
                    CollectionEntry(R.string.liked, R.drawable.favorite) {},
                    CollectionEntry(R.string.offline, R.drawable.offline) {},
                    CollectionEntry(R.string.cached_playlist, R.drawable.cached) {},
                    CollectionEntry(R.string.my_top, R.drawable.trending_up, titleSuffix = " 50") {},
                ),
            )
            Text("Playlist", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            SongRow("Santai sore", "18 lagu", 0xFF7B5BD6, 0xFFE0508A)
            SongRow("Perjalanan", "42 lagu", 0xFF3A7B5A, 0xFFB9D26A)
        }
    }

    @Test fun libraryLight() = shot("library-light", false) { LibraryPage() }
    @Test fun libraryDark() = shot("library-dark", true) { LibraryPage() }

    // ------------------------------------------------------------------ queue and song rows

    private fun meta(id: String, title: String, artist: String) =
        MediaMetadata(id = id, title = title, artists = listOf(MediaMetadata.Artist(null, artist)), duration = 215)

    @Composable
    private fun QueuePage() {
        Column(Modifier.fillMaxSize().padding(top = 32.dp)) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 20.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Antrean", style = MaterialTheme.typography.headlineSmall)
                    Text("Radio Negoro Angin", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("12 lagu • 45 mnt", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            QueueTrackRow(index = 1, mediaMetadata = meta("1", "Muak - Sped Up", "Aziz Harun"))
            QueueTrackRow(index = 2, mediaMetadata = meta("2", "Negoro Angin", "Denny Caknan"), isActive = true, isPlaying = false)
            QueueTrackRow(index = 3, mediaMetadata = meta("3", "Favorite Girl", "Justin Bieber"))
            QueueTrackRow(index = 4, mediaMetadata = meta("4", "Cinta dan Rahasia", "Yura Yunita"), trailingContent = { RowDragGrip() })
            Spacer(Modifier.height(12.dp))
            SongRow("Dipilih", "Rex Orange County", 0xFFE08A3C, 0xFFF2D06A, selected = true)
            SongRow("Sedang diputar", "Denny Caknan", 0xFF7B5BD6, 0xFFE0508A, active = true)
        }
    }

    @Test fun queueLight() = shot("queue-light", false) { QueuePage() }
    @Test fun queueDark() = shot("queue-dark", true) { QueuePage() }

    // ------------------------------------------------------------------ artist page action row

    @Composable
    private fun ArtistActions() {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                HeroPillButton(label = "Berlangganan", onClick = {}, filled = false, modifier = Modifier.weight(1f))
                HeroIconButton(icon = R.drawable.radio, contentDescription = "Radio", onClick = {})
                HeroIconButton(icon = R.drawable.shuffle, contentDescription = "Acak", onClick = {})
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                HeroPillButton(label = "Terlangganan", onClick = {}, filled = true, modifier = Modifier.weight(1f))
                HeroIconButton(icon = R.drawable.radio, contentDescription = "Radio", onClick = {})
                HeroIconButton(icon = R.drawable.shuffle, contentDescription = "Acak", onClick = {})
            }
        }
    }

    @Test fun artistActionsLight() = shot("artist-actions-light", false) { ArtistActions() }
    @Test fun artistActionsDark() = shot("artist-actions-dark", true) { ArtistActions() }
}
