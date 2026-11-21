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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrolist.music.R
import com.metrolist.music.ui.component.Material3MenuGroup
import com.metrolist.music.ui.component.Material3MenuItemData
import com.metrolist.music.ui.component.VolumeControl
import com.metrolist.music.ui.component.VolumeControlDefaults
import com.metrolist.music.ui.component.VolumeControlSizes
import com.metrolist.music.ui.component.glassOverlay
import com.metrolist.music.ui.component.painterResource
import com.metrolist.music.ui.theme.MetrolistTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The volume control rendered to PNG without a device: on the player menu sheet (light and dark) at 0, 40 and 100
 * percent and while held (thumb grown, percent label), the whole menu head for context, and the player page row over
 * a dark and a light cover. Output: build/design-gallery/volume-*.png.
 *
 * The pictures cannot show: touch feel, the haptic tick, the real glass behind the sheet.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h860dp-xhdpi")
class DesignGalleryVolumeTest {
    @get:Rule
    val rule = createComposeRule()

    private fun save(name: String) {
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/design-gallery").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(bmp.width > 100 && bmp.height > 100)
    }

    private fun show(dark: Boolean, content: @Composable () -> Unit) {
        rule.setContent {
            MetrolistTheme(darkTheme = dark) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        rule.waitForIdle()
    }

    /** The menu sheet as the app draws it (G3), with the volume control exactly where PlayerMenu puts it. */
    @Composable
    private fun Sheet(caption: String, level: Float, tag: String, sizes: VolumeControlSizes = VolumeControlDefaults.Menu) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
            val state = remember { mutableFloatStateOf(level) }
            Column(
                Modifier
                    .fillMaxWidth()
                    .glassOverlay(RoundedCornerShape(28.dp))
                    .padding(horizontal = 8.dp)
                    .padding(top = 24.dp, bottom = 12.dp),
            ) {
                VolumeControl(
                    value = { state.floatValue },
                    onValueChange = { state.floatValue = it },
                    sizes = sizes,
                    modifier = Modifier.testTag(tag),
                )
            }
        }
    }

    @Composable
    private fun SheetPage() {
        Column(Modifier.fillMaxSize().padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Sheet("0 persen", 0f, "v0")
            Sheet("40 persen", 0.4f, "v40")
            Sheet("100 persen", 1f, "v100")
            Sheet("40 persen, jari di atas bar", 0.4f, "held")
        }
    }

    /** The point of a level on the bar. The tagged node is the whole row: 48 dp mute button, the bar, 48 dp loud icon. */
    private fun TouchInjectionScope.thumb(fraction: Float, thumbDp: Float): Offset {
        val pad = thumbDp.dp.toPx() / 2f
        val bar = width - 96.dp.toPx()
        return Offset(48.dp.toPx() + pad + fraction * (bar - 2 * pad), centerY)
    }

    private fun hold(fraction: Float = 0.4f, thumbDp: Float = 22f) {
        rule.onNodeWithTag("held").performTouchInput {
            down(thumb(fraction, thumbDp))
            moveBy(Offset(2f, 0f))
        }
    }

    // ------------------------------------------------------------------ the control on the sheet

    @Test fun menuLight() {
        show(false) { SheetPage() }
        hold()
        save("volume-menu-light")
    }

    @Test fun menuDark() {
        show(true) { SheetPage() }
        hold()
        save("volume-menu-dark")
    }

    // ------------------------------------------------------------------ the head of the real menu

    @Composable
    private fun MenuHead(initial: Float) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f)), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(520.dp)
                    .glassOverlay(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
            ) {
                // the handle box and the content padding are the ones of BottomSheetMenu and PlayerMenu
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.padding(vertical = 12.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)))
                }
                val state = remember { mutableFloatStateOf(initial) }
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 24.dp, bottom = 6.dp)) {
                    VolumeControl(value = { state.floatValue }, onValueChange = { state.floatValue = it }, modifier = Modifier.testTag("held"))
                }
                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Column(Modifier.padding(horizontal = 20.dp)) {
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
    }

    @Test fun headDark() {
        show(true) { MenuHead(0.4f) }
        save("volume-head-dark")
    }

    @Test fun headLightHeld() {
        show(false) { MenuHead(0.62f) }
        hold(0.62f)
        save("volume-head-light-held")
    }

    // ------------------------------------------------------------------ the player page row

    @Composable
    private fun PlayerPage(cover: Brush, dark: Boolean) {
        Box(Modifier.fillMaxSize().background(cover)) {
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((tag, lvl) in listOf("p0" to 0f, "p40" to 0.4f, "p100" to 1f, "held" to 0.4f)) {
                    val state = remember { mutableFloatStateOf(lvl) }
                    Row(Modifier.width(411.dp).padding(horizontal = 8.dp)) {
                        VolumeControl(
                            value = { state.floatValue },
                            onValueChange = { state.floatValue = it },
                            sizes = VolumeControlDefaults.Player,
                            modifier = Modifier.testTag(tag),
                        )
                    }
                }
            }
        }
    }

    @Test fun playerDark() {
        show(true) { PlayerPage(Brush.verticalGradient(listOf(Color(0xFF3B4636), Color(0xFF1C2219))), true) }
        hold(0.4f, 18f)
        save("volume-player-dark")
    }

    @Test fun playerLight() {
        show(false) { PlayerPage(Brush.verticalGradient(listOf(Color(0xFFF5F6F8), Color(0xFFDFE2E8))), false) }
        save("volume-player-light")
    }
}
