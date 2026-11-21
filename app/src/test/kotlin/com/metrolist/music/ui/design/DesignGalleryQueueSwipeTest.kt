/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrolist.music.R
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.painterResource
import com.metrolist.music.ui.player.QueueSwipeLogic
import com.metrolist.music.ui.player.QueueSwipeLogic.RightAction
import com.metrolist.music.ui.player.QueueSwipeRow
import com.metrolist.music.ui.player.QueueTrackRow
import com.metrolist.music.ui.theme.MetrolistTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * The queue row swipes rendered to PNG without a device: the real [QueueSwipeRow] around the real [QueueTrackRow],
 * driven by touch input and captured while the finger is still down, at 20, 60 and 100 percent of the way to the
 * threshold, to the right ("Putar berikutnya") and to the left ("Hapus"), light and dark, plus the rows where the right
 * swipe only says "playing" or "already next". Output: build/design-gallery/queue-swipe, one PNG per scene and one
 * contact sheet per theme.
 *
 * The pictures cannot show: touch feel, the haptics, the real glass behind the sheet, the speed of the spring back.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h860dp-xhdpi")
class DesignGalleryQueueSwipeTest {
    @get:Rule
    val rule = createComposeRule()

    private val songs =
        listOf(
            "What If I Call" to "Alec Crichton",
            "Klebus" to "Guyon Waton",
            "Mary On A Cross" to "Ghost",
            "End of Beginning" to "Djo",
            "Let Down + Choir (best part)" to "Elliot Sutton dan Nenadist",
        )

    private fun meta(i: Int) =
        MediaMetadata(
            id = "s$i",
            title = songs[i].first,
            artists = listOf(MediaMetadata.Artist(null, songs[i].second)),
            duration = 150 + i * 17,
        )

    /** Row 0 plays, row 1 is next, rows 2..4 are further down. */
    private val rights = listOf(RightAction.IsCurrent, RightAction.AlreadyNext, RightAction.PlayNext, RightAction.PlayNext, RightAction.PlayNext)

    @Composable
    private fun Stage() {
        Box(Modifier.width(411.dp).height(64.dp * 5 + 16.dp).background(MaterialTheme.colorScheme.surface).testTag("stage")) {
            Column(Modifier.padding(top = 8.dp)) {
                for (i in songs.indices) {
                    QueueSwipeRow(
                        rightAction = rights[i],
                        leftEnabled = true,
                        onPlayNext = {},
                        onRemove = {},
                        modifier = Modifier.testTag("row$i"),
                    ) {
                        QueueTrackRow(
                            index = i + 1,
                            mediaMetadata = meta(i),
                            isActive = i == 0,
                            isPlaying = i == 0,
                            trailingContent = {
                                IconButton(onClick = {}) { Icon(painterResource(R.drawable.more_vert), contentDescription = null) }
                                IconButton(onClick = {}) { Icon(painterResource(R.drawable.drag_handle), contentDescription = null) }
                            },
                        )
                    }
                }
            }
        }
    }

    private class Shot(
        val label: String,
        val bitmap: android.graphics.Bitmap,
    )

    private val shots = mutableListOf<Shot>()
    private val dir = File("build/design-gallery/queue-swipe").apply { mkdirs() }

    private fun snap(name: String, label: String): android.graphics.Bitmap {
        rule.waitForIdle()
        val bmp = rule.onNodeWithTag("stage").captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        shots += Shot(label, bmp)
        assertTrue(bmp.width > 100 && bmp.height > 100)
        return bmp
    }

    private fun rowWidthPx(row: Int): Float = rule.onNodeWithTag("row$row").fetchSemanticsNode().size.width.toFloat()

    /**
     * Holds a finger on [row] and drags until the row has moved [fraction] of the way to the threshold (to the right or
     * the left), takes the picture, and lets go below the threshold again so that nothing is committed.
     */
    private fun scene(name: String, label: String, row: Int, right: Boolean, fraction: Float) {
        val w = rowWidthPx(row)
        val threshold = QueueSwipeLogic.ThresholdFraction * w
        val sign = if (right) 1f else -1f
        rule.onNodeWithTag("row$row").performTouchInput {
            val slop = viewConfiguration.touchSlop
            down(Offset(if (right) 6f else width - 6f, centerY))
            moveBy(Offset(sign * (fraction * threshold + slop), 0f), delayMillis = 400)
        }
        snap(name, label)
        rule.onNodeWithTag("row$row").performTouchInput {
            moveBy(Offset(-sign * 4000f, 0f), delayMillis = 400)
            up()
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    private fun pixel(bmp: android.graphics.Bitmap, xPx: Float, yPx: Float): Int = bmp.getPixel(xPx.toInt().coerceIn(0, bmp.width - 1), yPx.toInt().coerceIn(0, bmp.height - 1))

    private fun close(a: Int, b: Int, tolerance: Int = 6): Boolean =
        abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) <= tolerance &&
            abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) <= tolerance &&
            abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)) <= tolerance

    private fun sheet(name: String, columns: Int, dark: Boolean) {
        val pad = 24
        val labelH = 44
        val cellW = shots.maxOf { it.bitmap.width }
        val cellH = shots.maxOf { it.bitmap.height }
        val rows = (shots.size + columns - 1) / columns
        val out = android.graphics.Bitmap.createBitmap(columns * cellW + (columns + 1) * pad, rows * (cellH + labelH) + (rows + 1) * pad, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(if (dark) android.graphics.Color.rgb(0x30, 0x32, 0x38) else android.graphics.Color.rgb(0xB8, 0xBC, 0xC4))
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 28f
            color = if (dark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        }
        shots.forEachIndexed { i, shot ->
            val col = i % columns
            val r = i / columns
            val x = pad + col * (cellW + pad)
            val y = pad + r * (cellH + labelH + pad)
            canvas.drawText(shot.label, x.toFloat(), y + 30f, paint)
            canvas.drawBitmap(shot.bitmap, x.toFloat(), (y + labelH).toFloat(), null)
        }
        File(dir, "$name.png").outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun run(dark: Boolean) {
        val tag = if (dark) "dark" else "light"
        var surface = 0
        var high = 0
        var highest = 0
        rule.setContent {
            MetrolistTheme(darkTheme = dark) {
                surface = MaterialTheme.colorScheme.surface.toArgb()
                high = MaterialTheme.colorScheme.surfaceContainerHigh.toArgb()
                highest = MaterialTheme.colorScheme.surfaceContainerHighest.toArgb()
                // a Surface gives the icons their ink colour, as the sheet does in the app
                androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surface) { Stage() }
            }
        }
        rule.waitForIdle()

        val rest = snap("rest-$tag", "$tag: at rest")
        val density = rule.density.density
        val rowTopPx = (8 + 2 * 64 + 32) * density // middle of row 2
        val rowW = rowWidthPx(2)

        // to the right: 20, 60 and 100 percent of the way to the threshold, on a row further down
        scene("right-20-$tag", "$tag: Putar berikutnya, 20 persen", row = 2, right = true, fraction = 0.2f)
        scene("right-60-$tag", "$tag: Putar berikutnya, 60 persen", row = 2, right = true, fraction = 0.6f)
        scene("right-100-$tag", "$tag: Putar berikutnya, 100 persen (siap)", row = 2, right = true, fraction = 1.02f)
        val right60 = shots.first { it.label.contains("60 persen") && it.label.contains("Putar") }.bitmap
        val right100 = shots.first { it.label.contains("100 persen") && it.label.contains("Putar") }.bitmap

        // to the left
        scene("left-20-$tag", "$tag: Hapus, 20 persen", row = 3, right = false, fraction = 0.2f)
        scene("left-60-$tag", "$tag: Hapus, 60 persen", row = 3, right = false, fraction = 0.6f)
        scene("left-100-$tag", "$tag: Hapus, 100 persen (siap)", row = 3, right = false, fraction = 1.02f)
        val left100 = shots.first { it.label.contains("100 persen") && it.label.contains("Hapus") }.bitmap

        // the right swipe on the playing row and on the row that is already next
        scene("note-current-$tag", "$tag: baris yang diputar, geser kanan", row = 0, right = true, fraction = 1.5f)
        scene("note-next-$tag", "$tag: baris berikutnya, geser kanan", row = 1, right = true, fraction = 1.5f)

        sheet("sheet-$tag", columns = 3, dark = dark)

        // the pictures are measured, not only looked at
        fun mid(row: Int) = (8 + row * 64 + 32) * density
        val thresholdPx = QueueSwipeLogic.ThresholdFraction * rowW
        // at rest nothing is drawn behind the rows
        assertTrue("no pill at rest", close(pixel(rest, 16 * density, mid(2)), surface))
        // at 60 percent to the right: the pill is on the base tier, and the strip between the pill and the row is page colour
        assertTrue("pill (60) is surfaceContainerHigh", close(pixel(right60, 16 * density, mid(2)), high))
        assertTrue("gap (60) is the page", close(pixel(right60, 0.6f * thresholdPx - 4 * density, mid(2)), surface))
        // the note row stretches a little and its pill stays on the base tier (it never arms)
        val noteNext = shots.first { it.label.contains("baris berikutnya") }.bitmap
        assertTrue("pill (note) is surfaceContainerHigh", close(pixel(noteNext, 16 * density, mid(1)), high))
        // armed: one tier up
        assertTrue("pill (100) is surfaceContainerHighest", close(pixel(right100, 16 * density, mid(2)), highest))
        // to the left the pill sits on the right edge, one tier up when armed
        assertTrue("pill (left, 100) is surfaceContainerHighest", close(pixel(left100, rowW - 18 * density, mid(3)), highest))
    }

    @Test
    fun light() = run(dark = false)

    @Test
    fun dark() = run(dark = true)
}
