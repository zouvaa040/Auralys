package com.metrolist.music.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * The artwork that goes to the media session is already small enough that the system never has to scale it (a Xiaomi crash
 * report: "cannot use a recycled source in createBitmap" in MediaMetadata.Builder.scaleBitmap), and every load gives a
 * picture of its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CoilBitmapLoaderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @After
    fun tearDown() = scope.cancel()

    private fun png(
        w: Int,
        h: Int,
    ): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.RED)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test
    fun `the limit follows the screen and stays in a safe range`() {
        assertEquals(256, CoilBitmapLoader.artworkLimitPx(0.5f))
        assertEquals(640, CoilBitmapLoader.artworkLimitPx(2.75f))
        assertEquals(640, CoilBitmapLoader.artworkLimitPx(4f))
        assertEquals(384, CoilBitmapLoader.artworkLimitPx(1.5f))
    }

    @Test
    fun `a big picture is scaled down to fit and a small one is left alone`() {
        assertEquals(640 to 480, CoilBitmapLoader.fitted(1600, 1200, 640))
        assertEquals(480 to 640, CoilBitmapLoader.fitted(1200, 1600, 640))
        assertEquals(640 to 640, CoilBitmapLoader.fitted(1200, 1200, 640))
        assertEquals(300 to 200, CoilBitmapLoader.fitted(300, 200, 640))
        assertEquals(1 to 640, CoilBitmapLoader.fitted(1, 5000, 640))
    }

    @Test
    fun `a decoded cover fits the limit and every call gives a new picture`() {
        val loader = CoilBitmapLoader(context, scope)
        val limit = CoilBitmapLoader.artworkLimitPx(context.resources.displayMetrics.density)
        val data = png(1600, 1600)
        val first = loader.decodeBitmap(data).get()
        val second = loader.decodeBitmap(data).get()
        assertTrue("the longest side fits", maxOf(first.width, first.height) <= limit)
        assertFalse(first.isRecycled)
        assertNotSame("a picture of its own for each call", first, second)
    }
}
