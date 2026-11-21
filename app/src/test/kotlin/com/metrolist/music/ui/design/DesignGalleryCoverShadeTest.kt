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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrolist.music.ui.component.TopAppBar
import com.metrolist.music.ui.screens.settings.CoverShadeCard
import com.metrolist.music.ui.theme.CoverShade
import com.metrolist.music.ui.theme.CoverShadeLevel
import com.metrolist.music.ui.theme.HorizonGlass
import com.metrolist.music.ui.theme.HorizonRadius
import com.metrolist.music.ui.theme.LocalCoverShade
import com.metrolist.music.ui.theme.MetrolistTheme
import com.metrolist.music.ui.theme.UiStyle
import com.metrolist.music.ui.theme.coverShade
import com.metrolist.music.ui.theme.drawCoverShade
import com.metrolist.music.ui.theme.occludingPageBackground
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * "Efek berbayang" without a device: a Home-like page (top bar, chips, list rows, a row of tiles, a mini player and a
 * navigation bar, the last two as flat mocks of the glass tier G1 because Robolectric cannot blur) drawn by the REAL wash
 * code, at the three levels, in light, dark and pure black, over five different covers. Each picture is the three levels
 * side by side (Mati, Lembut, Kuat). Also the theme page section, a seam check between the real top bar and the page, and
 * a measure of the gradient (banding). Output: build/design-gallery/cover-shade.
 *
 * Not shown: real glass blur, scrolling, the cross-fade, real panels.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w1240dp-h880dp-hdpi")
class DesignGalleryCoverShadeTest {
    @get:Rule
    val rule = createComposeRule()

    /** One composition per test; a picture sets the scene and the composition swaps to it. */
    private var scene by mutableStateOf<(@Composable () -> Unit)?>(null)

    @Before
    fun host() {
        rule.setContent { scene?.invoke() }
    }

    private val covers = linkedMapOf(
        "red" to Color(0xFFE8454B),
        "blue" to Color(0xFF1E4FD8),
        "yellow" to Color(0xFFF2B705),
        "green" to Color(0xFF2E9E57),
        "grey" to Color(0xFF8A8A8E),
    )

    private val levels = listOf(CoverShadeLevel.OFF, CoverShadeLevel.SOFT, CoverShadeLevel.STRONG)

    private fun save(name: String): android.graphics.Bitmap {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1500) // the previews of the theme page fade in over 700 ms
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/design-gallery/cover-shade").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(bmp.width > 100 && bmp.height > 100)
        return bmp
    }

    // ---------------------------------------------------------------- the page

    private val phoneWidth = 360.dp
    private val phoneHeight = 720.dp

    @Composable
    private fun Cover(color: Color, size: androidx.compose.ui.unit.Dp, radius: androidx.compose.ui.unit.Dp = HorizonRadius.Sm) {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(radius))
                .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(color, color.copy(red = color.red * 0.6f, green = color.green * 0.6f, blue = color.blue * 0.6f)))),
        )
    }

    @Composable
    private fun SongRow(title: String, artist: String, time: String, cover: Color) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(cover, 52.dp)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                Text(artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        }
    }

    @Composable
    private fun Chip(label: String, selected: Boolean) {
        Box(
            Modifier.clip(RoundedCornerShape(50))
                .background(if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface)
        }
    }

    /** One page of the app with the shade behind it. The bars are the flat G1 look; the wash is the real code. */
    @Composable
    private fun HomePage(shade: CoverShade, cover: Color, pageHeightPx: Float, dark: Boolean) {
        val page = MaterialTheme.colorScheme.surface
        Box(Modifier.width(phoneWidth).height(phoneHeight).clip(RoundedCornerShape(HorizonRadius.Xl)).background(page).coverShade(shade, pageHeightPx)) {
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(30.dp)) // status bar
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Beranda", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Box(Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh))
                }
                Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Semua", true)
                    Chip("Santai", false)
                    Chip("Fokus", false)
                }
                Spacer(Modifier.height(10.dp))
                Text("Putar ulang", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                SongRow("Sore di Pantai", "Nama Artis", "3:45", cover)
                SongRow("Lagu Tanpa Judul", "Artis Lain", "4:02", cover)
                SongRow("Jalan Pulang", "Band Kota", "2:58", cover)
                Spacer(Modifier.height(14.dp))
                Text("Album untukmu", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(3) { i ->
                        Column(Modifier.width(96.dp)) {
                            Cover(cover.copy(alpha = 1f), 96.dp, HorizonRadius.Md)
                            Text("Album ${i + 1}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                            Text("Artis", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
            // mini player and navigation bar: the G1 veil over the page, the way the glass tier looks when it samples a calm backdrop
            val veil = HorizonGlass.G1.veil(dark)
            Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(HorizonRadius.Xl)).background(veil).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(cover, 40.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text("Sore di Pantai", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                        Text("Nama Artis", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Box(Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface))
                }
                Row(
                    Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(HorizonRadius.Xl)).background(veil),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf("Beranda", "Cari", "Pustaka", "Kamu").forEachIndexed { i, label ->
                        Text(label, style = MaterialTheme.typography.labelLarge, color = if (i == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    private fun sheet(name: String, dark: Boolean, pureBlack: Boolean, cover: Color, style: UiStyle = UiStyle.GLASS): android.graphics.Bitmap {
        scene = {
            MetrolistTheme(darkTheme = dark, pureBlack = pureBlack, style = style) {
                val px = with(LocalDensity.current) { phoneHeight.toPx() }
                Row(
                    Modifier.fillMaxSize().background(if (dark) Color(0xFF303236) else Color(0xFFC9CCD2)).padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    for (level in levels) {
                        val shade = CoverShade.fixed(level, cover, dark, pureBlack, flat = style == UiStyle.EDITORIAL)
                        HomePage(shade, cover, px, dark)
                    }
                }
            }
        }
        return save(name)
    }

    @Test
    fun `pages in light, dark and pure black over five covers`() {
        for ((coverName, cover) in covers) {
            sheet("home-light-$coverName", dark = false, pureBlack = false, cover = cover)
            sheet("home-dark-$coverName", dark = true, pureBlack = false, cover = cover)
            sheet("home-black-$coverName", dark = true, pureBlack = true, cover = cover)
        }
    }

    @Test
    fun `the flat Datar style is quieter`() {
        sheet("datar-light-blue", dark = false, pureBlack = false, cover = covers.getValue("blue"), style = UiStyle.EDITORIAL)
        sheet("datar-dark-red", dark = true, pureBlack = false, cover = covers.getValue("red"), style = UiStyle.EDITORIAL)
    }

    // ---------------------------------------------------------------- the real top bar against the page

    @Test
    fun `the Datar top bar and the page are one surface`() {
        val cover = covers.getValue("blue")
        var barBottomPx = 0
        scene = {
            MetrolistTheme(darkTheme = false, style = UiStyle.EDITORIAL) {
                val shade = CoverShade.fixed(CoverShadeLevel.STRONG, cover, dark = false, pureBlack = false, flat = true)
                val heightPx = LocalWindowInfo.current.containerSize.height.toFloat()
                val density = LocalDensity.current
                CompositionLocalProvider(LocalCoverShade provides shade) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).drawBehind { drawCoverShade(shade, heightPx) }) {
                        Column {
                            TopAppBar(title = { Text("Beranda") })
                            repeat(4) { SongRow("Sore di Pantai", "Nama Artis", "3:45", cover) }
                        }
                    }
                }
                barBottomPx = with(density) { 64.dp.roundToPx() }
            }
        }
        val bmp = save("topbar-datar-light-blue")
        // the pixel just above and just below the bottom edge of the bar, far from any text: same colour within one level
        for (x in listOf(bmp.width / 2, bmp.width - 40, bmp.width / 3)) {
            val above = bmp.getPixel(x, barBottomPx - 3)
            val below = bmp.getPixel(x, barBottomPx + 3)
            val diff = maxOf(
                abs(android.graphics.Color.red(above) - android.graphics.Color.red(below)),
                abs(android.graphics.Color.green(above) - android.graphics.Color.green(below)),
                abs(android.graphics.Color.blue(above) - android.graphics.Color.blue(below)),
            )
            assertTrue("a seam of $diff levels at x=$x between the bar and the page (bar bottom $barBottomPx)", diff <= 2)
        }
        // and the wash is really there: the top is bluer than the bottom of the picture
        val top = bmp.getPixel(bmp.width - 40, 10)
        val low = bmp.getPixel(bmp.width - 40, bmp.height - 10)
        assertTrue("no wash at the top: ${Integer.toHexString(top)} vs ${Integer.toHexString(low)}", android.graphics.Color.blue(top) - android.graphics.Color.red(top) > android.graphics.Color.blue(low) - android.graphics.Color.red(low) + 6)
    }

    // ---------------------------------------------------------------- pages stacked over each other (the search bug)

    /**
     * Bug report 2026-11-07 (screenshot): with the shade on, the search suggestions, the results and the browse grid were
     * drawn on top of each other, because the layers that lie over another one had been made transparent. A page that lies
     * over another one asks [occludingPageBackground]: it hides what is under it (and still carries the wash).
     */
    @Test
    fun `a page that lies over another one hides it while the shade is on`() {
        val cover = covers.getValue("blue")
        scene = {
            MetrolistTheme(darkTheme = false) {
                val shade = CoverShade.fixed(CoverShadeLevel.SOFT, cover, dark = false, pureBlack = false, flat = false)
                CompositionLocalProvider(LocalCoverShade provides shade) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().background(Color(0xFFE53935))) // the layer underneath: a loud red
                        Box(Modifier.fillMaxSize().occludingPageBackground()) // the page that lies over it
                    }
                }
            }
        }
        val bmp = save("occluding-page-light-blue")
        for ((x, y) in listOf(bmp.width / 2 to bmp.height / 2, 40 to bmp.height - 40, bmp.width - 40 to 300, 40 to 40)) {
            val p = bmp.getPixel(x, y)
            // the red layer has green = 57; the page (light, with a soft blue wash at the top) is far above that
            assertTrue("the layer underneath shows through at ($x, $y): ${Integer.toHexString(p)}", android.graphics.Color.green(p) > 150)
        }
        // and the wash is on the page: the top is bluer than the bottom
        val top = bmp.getPixel(bmp.width - 40, 10)
        val low = bmp.getPixel(bmp.width - 40, bmp.height - 10)
        assertTrue(
            "no wash on the occluding page: ${Integer.toHexString(top)} vs ${Integer.toHexString(low)}",
            android.graphics.Color.blue(top) - android.graphics.Color.red(top) > android.graphics.Color.blue(low) - android.graphics.Color.red(low) + 4,
        )
    }

    // ---------------------------------------------------------------- banding: how smooth is the ramp

    @Test
    fun `the ramp has no step larger than one level between neighbouring rows`() {
        val cover = covers.getValue("blue")
        for (dark in listOf(false, true)) {
            scene = {
                MetrolistTheme(darkTheme = dark, style = UiStyle.GLASS) {
                    val shade = CoverShade.fixed(CoverShadeLevel.STRONG, cover, dark, pureBlack = false, flat = false)
                    val heightPx = LocalWindowInfo.current.containerSize.height.toFloat()
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).drawBehind { drawCoverShade(shade, heightPx) })
                }
            }
            val bmp = save("ramp-${if (dark) "dark" else "light"}-blue")
            val x = bmp.width / 2
            var maxStep = 0
            var changes = 0
            var run = 0
            var longestRun = 0
            var prev = bmp.getPixel(x, 0)
            for (y in 1 until bmp.height) {
                val c = bmp.getPixel(x, y)
                val step = maxOf(
                    abs(android.graphics.Color.red(c) - android.graphics.Color.red(prev)),
                    abs(android.graphics.Color.green(c) - android.graphics.Color.green(prev)),
                    abs(android.graphics.Color.blue(c) - android.graphics.Color.blue(prev)),
                )
                if (step > maxStep) maxStep = step
                if (step == 0) run++ else { changes++; longestRun = maxOf(longestRun, run); run = 0 }
                prev = c
            }
            println("RAMP ${if (dark) "dark" else "light"}: height ${bmp.height}px, max step $maxStep, rows that change $changes, longest flat run ${longestRun}px")
            assertTrue("a step of $maxStep levels in the ramp", maxStep <= 1)
        }
    }

    // ---------------------------------------------------------------- the section of the theme page

    @Test
    fun `the theme page section in light, dark and pure black`() {
        for ((label, dark, black) in listOf(Triple("light", false, false), Triple("dark", true, false), Triple("black", true, true))) {
            scene = {
                MetrolistTheme(darkTheme = dark, pureBlack = black, style = UiStyle.GLASS) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(20.dp)) {
                        Column(Modifier.width(380.dp)) { CoverShadeCard() }
                    }
                }
            }
            save("settings-card-$label")
        }
    }
}
