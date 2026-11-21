/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrolist.music.R
import com.metrolist.music.ui.component.ChipsRow
import com.metrolist.music.ui.component.EditorialDivider
import com.metrolist.music.ui.component.Material3MenuGroup
import com.metrolist.music.ui.component.Material3MenuItemData
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.NavigationTitle
import com.metrolist.music.ui.component.NewAction
import com.metrolist.music.ui.component.NewActionGrid
import com.metrolist.music.ui.component.SearchSourceSwitch
import com.metrolist.music.ui.component.Switch
import com.metrolist.music.ui.component.glass
import com.metrolist.music.ui.component.glassCard
import com.metrolist.music.ui.component.glassOverlay
import com.metrolist.music.ui.component.glassRimBorder
import com.metrolist.music.ui.component.painterResource
import com.metrolist.music.ui.screens.Screens
import com.metrolist.music.ui.theme.HorizonColors
import com.metrolist.music.ui.theme.HorizonDark
import com.metrolist.music.ui.theme.HorizonGlass
import com.metrolist.music.ui.theme.HorizonLight
import com.metrolist.music.ui.theme.HorizonScale
import com.metrolist.music.ui.theme.MetrolistTheme
import com.metrolist.music.ui.theme.UiStyle
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A gallery of the real shared components (settings group, buttons, chips, segmented control, switch, section header,
 * song rows, menu) rendered to PNG without a device, with Robolectric native graphics. Light and dark, Kaca and Datar.
 * Output: the folder build/design-gallery (one PNG per test).
 *
 * What the pictures cannot show: the backdrop blur and the lens (RenderEffect and AGSL do not render here, so the bars
 * and controls show their designed no-blur fallback), the real glass over real covers, touch feel, and the exact weight
 * of the font if the host ignores the weight axis.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h860dp-xhdpi")
class DesignGalleryTest {
    @get:Rule
    val rule = createComposeRule()

    private fun shot(name: String, dark: Boolean, style: UiStyle, pureBlack: Boolean = false, content: @Composable () -> Unit) {
        rule.setContent {
            MetrolistTheme(darkTheme = dark, style = style, pureBlack = pureBlack) {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) { content() }
            }
        }
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/design-gallery").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(bmp.width > 100 && bmp.height > 100)
    }

    private fun cover(a: Long, b: Long): Brush = Brush.linearGradient(listOf(Color(a), Color(b)))

    // ------------------------------------------------------------------ pages

    @Composable
    private fun SettingsPage() {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(40.dp))
            Text("Tampilan", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 16.dp))
            Material3SettingsGroup(
                title = "Tema",
                items = listOf(
                    Material3SettingsItem(icon = painterResource(R.drawable.palette), title = { Text("Gaya") }, trailingContent = { Text("Kaca") }, onClick = {}),
                    Material3SettingsItem(icon = painterResource(R.drawable.tune), title = { Text("Warna") }, trailingContent = { Text("Netral") }, onClick = {}),
                    Material3SettingsItem(icon = painterResource(R.drawable.grid_view), title = { Text("Gaya ikon") }, trailingContent = { Text("Bulat") }, onClick = {}),
                    Material3SettingsItem(icon = painterResource(R.drawable.palette), title = { Text("Hitam murni") }, trailingContent = { Switch(checked = true, onCheckedChange = {}) }, onClick = {}),
                    Material3SettingsItem(icon = painterResource(R.drawable.tune), title = { Text("Blur kaca") }, trailingContent = { Switch(checked = false, onCheckedChange = {}) }, onClick = {}),
                ),
            )
            Spacer(Modifier.height(24.dp))
            Material3SettingsGroup(
                title = "Sinkron",
                items = listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.share),
                        title = { Text("Sinkron ke Auralys PC") },
                        description = { Text("Pindai kode QR dari aplikasi PC") },
                        onClick = {},
                    ),
                    Material3SettingsItem(title = { Text("Riwayat putar") }, trailingContent = { Text("Aktif") }, onClick = {}),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.download),
                        title = { Text("Unduhan") },
                        description = { Text("Tidak tersedia saat mode hemat data") },
                        enabled = false,
                        onClick = {},
                    ),
                ),
            )
        }
    }

    @Composable
    private fun ComponentsPage() {
        Column(Modifier.fillMaxSize().padding(top = 32.dp)) {
            NavigationTitle(title = "Terakhir diputar", onPlayAllClick = {}, onClick = {})
            Spacer(Modifier.height(4.dp))
            ChipsRow(
                chips = listOf("semua" to "Semua", "lagu" to "Lagu", "album" to "Album", "artis" to "Artis", "playlist" to "Playlist"),
                currentValue = "lagu",
                onValueUpdate = {},
            )
            Spacer(Modifier.height(16.dp))
            SearchSourceSwitch(options = listOf("YouTube", "Perangkat"), selectedIndex = 0, onSelect = {}, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(16.dp))
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                com.metrolist.music.ui.component.Button(onClick = {}) { Text("Putar") }
                OutlinedButton(onClick = {}, shape = CircleShape) { Text("Acak") }
                TextButton(onClick = {}) { Text("Batal") }
                com.metrolist.music.ui.component.Button(onClick = {}, enabled = false) { Text("Simpan") }
            }
            Spacer(Modifier.height(8.dp))
            SongRow("Negoro Angin", "Denny Caknan", 0xFF7B5BD6, 0xFFE0508A, active = true)
            SongRow("Favorite Girl", "Justin Bieber", 0xFF2F8FE0, 0xFF6AD1F2)
            SongRow("Cinta dan Rahasia", "Yura Yunita", 0xFF3A7B5A, 0xFFB9D26A)
            Spacer(Modifier.height(8.dp))
            Column(Modifier.padding(horizontal = 20.dp)) {
                NewActionGrid(
                    actions = listOf(
                        NewAction(icon = { Icon(painterResource(R.drawable.radio), null) }, text = "Radio", onClick = {}),
                        NewAction(icon = { Icon(painterResource(R.drawable.playlist_add), null) }, text = "Playlist", onClick = {}),
                        NewAction(icon = { Icon(painterResource(R.drawable.share), null) }, text = "Bagikan", onClick = {}),
                    ),
                )
                Spacer(Modifier.height(8.dp))
                Material3MenuGroup(
                    items = listOf(
                        Material3MenuItemData(icon = { Icon(painterResource(R.drawable.queue_music), null) }, title = { Text("Putar berikutnya") }, onClick = {}),
                        Material3MenuItemData(icon = { Icon(painterResource(R.drawable.library_add), null) }, title = { Text("Tambah ke pustaka") }, onClick = {}),
                    ),
                )
                Material3MenuGroup(
                    items = listOf(
                        Material3MenuItemData(icon = { Icon(painterResource(R.drawable.download), null) }, title = { Text("Unduh") }, onClick = {}),
                    ),
                )
            }
        }
    }

    @Composable
    private fun SongRow(title: String, artist: String, c1: Long, c2: Long, active: Boolean = false) {
        com.metrolist.music.ui.component.ListItem(
            title = title,
            subtitle = androidx.compose.ui.text.AnnotatedString(artist),
            thumbnailContent = {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(cover(c1, c2)))
            },
            trailingContent = { Icon(painterResource(R.drawable.more_vert), null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            isActive = active,
        )
    }

    /** A cover behind everything, then the floating parts as the app composes them. Blur does not render here: this is the no-blur fallback. */
    @Composable
    private fun FloatingPage(dark: Boolean, glassStyle: Boolean) {
        val tokens: HorizonColors = if (dark) HorizonDark else HorizonLight
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(300.dp).background(cover(0xFF6A4FD0, 0xFFE0508A)))
                Column(Modifier.padding(20.dp)) {
                    Text("Negoro Angin", style = MaterialTheme.typography.headlineMedium)
                    Text("Denny Caknan  -  18 lagu  -  1 j 9 mnt", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SongRow("Muak - Sped Up", "Aziz Harun", 0xFF3A7B5A, 0xFFB9D26A)
                SongRow("Favorite Girl", "Justin Bieber", 0xFF2F8FE0, 0xFF6AD1F2, active = true)
                SongRow("Hannah Montana", "Rex Orange County", 0xFFE08A3C, 0xFFF2D06A)
            }
            // controls over the cover (G2)
            Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.size(40.dp).glass(CircleShape), contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.skip_previous), null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                Box(Modifier.size(40.dp).glass(CircleShape), contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.more_horiz), null, tint = Color.White, modifier = Modifier.size(20.dp)) }
            }
            // dock: mini player + navigation bar, both G1 (the veil of the tier, no blur in this render)
            Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val barShape = RoundedCornerShape(50)
                val veil = HorizonGlass.G1.veil(dark)
                Row(
                    Modifier.fillMaxWidth().height(64.dp)
                        .shadow(10.dp, barShape, clip = false, ambientColor = Color.Black.copy(alpha = if (dark) 0.35f else 0.10f), spotColor = Color.Black.copy(alpha = if (dark) 0.45f else 0.14f))
                        .clip(barShape).background(tokens.bg).background(veil).glassRimBorder(barShape)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(cover(0xFF2F8FE0, 0xFF6AD1F2)))
                    Column(Modifier.weight(1f)) {
                        Text("Favorite Girl", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text("Justin Bieber", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Icon(painterResource(R.drawable.favorite_border), null, modifier = Modifier.size(24.dp))
                    Icon(painterResource(R.drawable.pause), null, modifier = Modifier.size(28.dp))
                    Icon(painterResource(R.drawable.skip_next), null, modifier = Modifier.size(24.dp))
                }
                Row(
                    Modifier.fillMaxWidth().height(64.dp)
                        .shadow(10.dp, barShape, clip = false, ambientColor = Color.Black.copy(alpha = if (dark) 0.35f else 0.10f), spotColor = Color.Black.copy(alpha = if (dark) 0.45f else 0.14f))
                        .clip(barShape).background(tokens.bg).background(veil).glassRimBorder(barShape)
                        .padding(4.dp),
                ) {
                    val tabs = listOf(Screens.Home to "Beranda", Screens.Search to "Cari", Screens.Library to "Pustaka", Screens.You to "Kamu")
                    tabs.forEachIndexed { i, (screen, label) ->
                        val selected = i == 0
                        val tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                        Column(
                            Modifier.weight(1f).fillMaxSize().then(if (selected) Modifier.clip(barShape).glass(barShape, strong = true) else Modifier),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(painterResource(if (selected) screen.iconIdActive else screen.iconIdInactive), null, tint = tint, modifier = Modifier.size(24.dp))
                            Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DialogPage() {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.padding(24.dp).fillMaxWidth()
                    .glassOverlay(RoundedCornerShape(28.dp))
                    .padding(24.dp),
            ) {
                Text("Hapus playlist?", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text("Playlist Santai akan dihapus dari pustaka. Lagu di dalamnya tidak ikut terhapus.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {}) { Text("Batal", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    com.metrolist.music.ui.component.Button(onClick = {}) { Text("Hapus") }
                }
            }
        }
    }

    @Composable
    private fun TypePage(tokens: HorizonColors) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Spacer(Modifier.height(24.dp))
            val t = MaterialTheme.typography
            Text("Auralys", style = t.displayLarge)
            Text("Beranda", style = t.headlineLarge)
            Text("Terakhir diputar", style = t.headlineSmall)
            Text("Negoro Angin (Live Version)", style = t.titleMedium)
            Text("Denny Caknan, 3:42", style = t.titleSmall)
            Text("Paragraf biasa dengan teks yang cukup panjang untuk memperlihatkan irama baris dan keterbacaan di layar kecil.", style = t.bodyLarge)
            Text("Artis, album, 18 lagu - 1 j 9 mnt", style = t.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("0:50  -6:04  12 Nov 2026  0123456789", style = t.bodySmall, color = MaterialTheme.colorScheme.outline)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Putar semua", style = t.labelLarge)
                Text("Lagu", style = t.labelMedium)
                Text("Beranda", style = t.labelSmall)
            }
            Spacer(Modifier.height(8.dp))
            EditorialDivider()
            Spacer(Modifier.height(8.dp))
            Text("Warna", style = t.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val swatches = listOf(
                "bg" to tokens.bg, "s1" to tokens.surface1, "s2" to tokens.surface2, "s3" to tokens.surface3,
                "ink" to tokens.ink, "t2" to tokens.text2, "t3" to tokens.text3, "bahaya" to tokens.danger,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((label, c) in swatches) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(c).shadow(0.dp, RoundedCornerShape(8.dp)))
                        Text(label, style = t.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Skala", style = t.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (role in HorizonScale.roles) {
                Text("${role.name}  ${role.sizeSp.toInt()}/${role.lineSp.toInt()}  ${role.weight.weight}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    // ------------------------------------------------------------------ tests

    @Test fun settingsLightKaca() = shot("settings-light-kaca", false, UiStyle.GLASS) { SettingsPage() }
    @Test fun settingsDarkKaca() = shot("settings-dark-kaca", true, UiStyle.GLASS) { SettingsPage() }
    @Test fun settingsLightDatar() = shot("settings-light-datar", false, UiStyle.EDITORIAL) { SettingsPage() }
    @Test fun settingsDarkDatar() = shot("settings-dark-datar", true, UiStyle.EDITORIAL) { SettingsPage() }
    @Test fun settingsPureBlack() = shot("settings-pureblack-kaca", true, UiStyle.GLASS, pureBlack = true) { SettingsPage() }

    @Test fun componentsLightKaca() = shot("components-light-kaca", false, UiStyle.GLASS) { ComponentsPage() }
    @Test fun componentsDarkKaca() = shot("components-dark-kaca", true, UiStyle.GLASS) { ComponentsPage() }
    @Test fun componentsLightDatar() = shot("components-light-datar", false, UiStyle.EDITORIAL) { ComponentsPage() }
    @Test fun componentsDarkDatar() = shot("components-dark-datar", true, UiStyle.EDITORIAL) { ComponentsPage() }

    @Test fun floatingLightKaca() = shot("floating-light-kaca", false, UiStyle.GLASS) { FloatingPage(false, true) }
    @Test fun floatingDarkKaca() = shot("floating-dark-kaca", true, UiStyle.GLASS) { FloatingPage(true, true) }

    @Test fun dialogLight() = shot("dialog-light", false, UiStyle.GLASS) { DialogPage() }
    @Test fun dialogDark() = shot("dialog-dark", true, UiStyle.GLASS) { DialogPage() }

    @Test fun typeLight() = shot("type-light", false, UiStyle.GLASS) { TypePage(HorizonLight) }
    @Test fun typeDark() = shot("type-dark", true, UiStyle.GLASS) { TypePage(HorizonDark) }
}
