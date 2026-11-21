package com.metrolist.music.poster.sticker

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.poster.PosterRequest
import com.metrolist.music.poster.PosterSong
import com.metrolist.music.poster.PosterSongLookup
import com.metrolist.music.poster.PosterViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.hypot

/** Round 4.3.5: the sticker editor's screen drawn for real (labels, tools, the picture over the checkerboard), written to build/poster-gallery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StickerEditScreenTest {
    @get:Rule val compose = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun logo(): Uri {
        val w = 300
        val h = 300
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val d = hypot(x + 0.5f - 150f, y + 0.5f - 160f)
            val k = (90.5f - d).coerceIn(0f, 1f)
            // a mug-like object: a dark disc with a lighter rim, on a pale wall
            val r = (60 * k + 236 * (1 - k)).toInt()
            val g = (70 * k + 232 * (1 - k)).toInt()
            val b = (88 * k + 226 * (1 - k)).toInt()
            (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        val dir = File(app.cacheDir, "test-stickers").apply { mkdirs() }
        val f = File(dir, "screen.png")
        f.outputStream().use { Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(f)
    }

    @Test
    fun theEditor_showsThePictureAndItsTools() {
        val vm = PosterViewModel(app, SavedStateHandle(), PosterSongLookup { _, _ -> emptyList() })
        vm.start(PosterRequest("Tes", listOf(PosterSong("s0", "Lagu", "Artis", null, 200))))
        vm.togglePick("s0")
        vm.confirmPick()
        vm.importStickers(listOf(logo()))
        compose.setContent {
            CompositionLocalProvider(LocalPlayerAwareWindowInsets provides WindowInsets(0, 0, 0, 0)) {
                Box(Modifier.size(390.dp, 844.dp)) { if (vm.stickerFlow.session != null || vm.stickerFlow.opening) StickerEditScreen(vm.stickerFlow) }
            }
        }
        compose.waitUntil(30_000) { vm.stickerFlow.session?.let { !it.busy && it.method != null } == true }
        val s = vm.stickerFlow.session!!
        assertEquals(StickerMethod.COLOR, s.method)
        // a stroke of the brush, then the restore tool (the ghost of what was taken off shows)
        compose.runOnIdle {
            s.beginStroke(90f, 160f, 14f)
            s.strokeTo(150f, 120f)
            s.endStroke()
            s.selectTool(StickerTool.RESTORE)
        }
        compose.waitForIdle()
        compose.onAllNodesWithText("Remove background")[0].assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
        val img = compose.onRoot().captureToImage().asAndroidBitmap()
        File("build/poster-gallery").mkdirs()
        File("build/poster-gallery/sticker-editor.png").outputStream().use { img.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(img.width > 300)
        // the tool chips work (further down the panel)
        compose.onNodeWithText("Erase").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(StickerTool.ERASE, s.tool)
        compose.onNodeWithText("Move").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(StickerTool.MOVE, s.tool)
    }
}
