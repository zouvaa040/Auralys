/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrolist.music.R
import com.metrolist.music.ui.theme.MetrolistTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A contact sheet of EVERY icon the app draws, in each family (Garis, iOS, Bulat), light and dark: a grid with the
 * drawable name under each glyph, rendered with Robolectric native graphics through the real wrapper
 * ([painterResource]) and the real theme. A glyph that has no twin in the family is labelled in red: it would show its
 * normal drawable there. Output: build/design-gallery/icons/<family>-<light|dark>-<page>.png, and two rows that mirror
 * the owner's screenshots (playlist menu, player menu) per family.
 *
 * What the pictures cannot show: the real screen, real anti-aliasing at the device density, the font.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h860dp-xhdpi")
class IconContactSheetTest {
    @get:Rule
    val rule = createComposeRule()

    private val outDir = File("build/design-gallery/icons").apply { mkdirs() }

    private class Page(val family: IconFamily, val dark: Boolean, val content: @Composable () -> Unit)

    private var page by mutableStateOf<Page?>(null)
    private var started = false

    /** `setContent` may only run once per test: later shots swap the page through state. */
    private fun shot(name: String, family: IconFamily, dark: Boolean, content: @Composable () -> Unit) {
        if (!started) {
            started = true
            rule.setContent {
                val p = page
                if (p != null) {
                    MetrolistTheme(darkTheme = p.dark, iconFamily = p.family) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background,
                            contentColor = MaterialTheme.colorScheme.onBackground,
                        ) { p.content() }
                    }
                }
            }
        }
        rule.runOnIdle { page = Page(family, dark, content) }
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(bmp.width > 100 && bmp.height > 100)
    }

    /** Every drawable that is drawn as an icon: used in code, or named by one of the two tables. */
    private fun catalog(): List<Pair<String, Int>> {
        val names = linkedSetOf<String>()
        names += IconSources.drawableUses.keys.filter { IconSources.isFamilyIcon(it) }
        names += IosIconTable.twins.keys.mapNotNull { IconSources.drawableNames[it] }
        names += BulatIconTable.entries.mapNotNull { IconSources.drawableNames[it.resId] }
        return names.filter { IconSources.isFamilyIcon(it) }
            .mapNotNull { n -> IconSources.drawableIds[n]?.let { n to it } }
            .sortedBy { it.first }
    }

    private fun hasTwin(family: IconFamily, id: Int): Boolean =
        when (family) {
            IconFamily.LINE -> true
            IconFamily.IOS -> IosIconTable.twinFor(id) != null
            IconFamily.ROUND -> BulatIconTable.vectorFor(id) != null
        }

    @Composable
    private fun Cell(name: String, id: Int, family: IconFamily) {
        val twin = hasTwin(family, id)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(66.dp).padding(vertical = 3.dp),
        ) {
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                Icon(painter = painterResource(id), contentDescription = null, modifier = Modifier.size(30.dp))
            }
            Text(
                text = name,
                fontSize = 7.5.sp,
                lineHeight = 9.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                color = if (twin) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFE53935),
            )
        }
    }

    private val perRow = 6
    private val rowsPerPage = 11

    @Test
    fun `contact sheets of every icon in every family`() {
        val all = catalog()
        assertTrue("the catalog is empty", all.size > 100)
        val pages = all.chunked(perRow * rowsPerPage)
        for (family in IconFamily.values()) {
            for (dark in listOf(false, true)) {
                pages.forEachIndexed { index, page ->
                    val tag = "${family.name.lowercase()}-${if (dark) "dark" else "light"}-${index + 1}"
                    shot(tag, family, dark) {
                        Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 24.dp)) {
                            Text(
                                "${family.name}  ${if (dark) "dark" else "light"}  page ${index + 1}/${pages.size}  (${all.size} icons)",
                                fontSize = 9.sp,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                            for (row in page.chunked(perRow)) {
                                Row(horizontalArrangement = Arrangement.Start) {
                                    for ((name, id) in row) Cell(name, id, family)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ the owner's two screens, icon rows only

    @Composable
    private fun MenuRow(icon: Int, title: String, subtitle: String? = null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 9.dp),
        ) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
            Column(Modifier.padding(start = 20.dp)) {
                Text(title, fontSize = 15.sp)
                if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun Tile(icon: Int, title: String) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(92.dp).padding(vertical = 8.dp),
        ) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
            Text(title, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        }
    }

    @Test
    fun `the playlist menu and the player menu per family`() {
        for (family in IconFamily.values()) {
            for (dark in listOf(false, true)) {
                val mode = if (dark) "dark" else "light"
                shot("menu-playlist-${family.name.lowercase()}-$mode", family, dark) {
                    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
                        MenuRow(R.drawable.edit, "Ubah", "Ubah playlist")
                        MenuRow(R.drawable.queue_music, "Tambah ke antrean", "Tambahkan ke akhir antrean")
                        MenuRow(R.drawable.download, "Unduh", "Unduh semua lagu untuk diputar offline")
                        MenuRow(R.drawable.share, "Bagikan", "Bagikan playlist ini kepada orang lain")
                        MenuRow(R.drawable.export_playlist, "Ekspor playlist")
                        MenuRow(R.drawable.delete, "Hapus", "Hapus playlist ini secara permanen")
                    }
                }
                shot("menu-player-${family.name.lowercase()}-$mode", family, dark) {
                    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            Icon(painter = painterResource(R.drawable.volume_down), contentDescription = null, modifier = Modifier.size(24.dp))
                            Box(Modifier.weight(1f).padding(horizontal = 12.dp).size(width = 100.dp, height = 4.dp))
                            Icon(painter = painterResource(R.drawable.volume_up), contentDescription = null, modifier = Modifier.size(24.dp))
                        }
                        Row {
                            Tile(R.drawable.radio, "Mulai radio")
                            Tile(R.drawable.playlist_add, "Tambah ke playlist")
                            Tile(R.drawable.link, "Salin link")
                            Tile(R.drawable.share, "Bagikan kartu lagu")
                        }
                        MenuRow(R.drawable.artist, "Lihat artis", "Denny Caknan")
                        MenuRow(R.drawable.library_add, "Tambah ke pustaka")
                        MenuRow(R.drawable.add, "Sematkan ke speed dial")
                        MenuRow(R.drawable.download, "Unduh")
                        MenuRow(R.drawable.group, "Dengar bersama")
                        MenuRow(R.drawable.info, "Rincian", "Lihat detail lagu")
                        MenuRow(R.drawable.fullscreen, "Mode ambient", "Lagu yang sedang diputar ditampilkan layar penuh")
                    }
                }
            }
        }
    }
}
