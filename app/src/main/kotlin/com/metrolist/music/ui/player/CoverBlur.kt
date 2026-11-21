/**
 * Auralys: the blurred cover behind the full player, blurred ONCE on the CPU instead of every frame.
 *
 * `Modifier.blur(72.dp)` is a RenderEffect: the GPU re-runs a full-screen blur for every frame the window draws,
 * and the player window draws all the time (progress text ticks, sheets slide over it). The cover is decoded at
 * about 48 px, blurred here with a 3-pass box blur (close to a gaussian) and drawn as a plain scaled image.
 * Same idea as BitChord's pre-blurred backdrop bitmap (GPLv3 like Auralys); the code is Auralys' own.
 */

package com.metrolist.music.ui.player

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Separable box blur on an ARGB image, [passes] times, edges clamped. [radius] <= 0 returns a copy.
 * Runs in O(width * height * passes) whatever the radius.
 */
fun boxBlurArgb(
    src: IntArray,
    width: Int,
    height: Int,
    radius: Int,
    passes: Int = 3,
): IntArray {
    require(width > 0 && height > 0 && src.size >= width * height) { "bad image size" }
    val cur = src.copyOf(width * height)
    if (radius <= 0) return cur
    val tmp = IntArray(width * height)
    repeat(passes.coerceAtLeast(1)) {
        blurLine(cur, tmp, width, height, radius, horizontal = true)
        blurLine(tmp, cur, width, height, radius, horizontal = false)
    }
    return cur
}

private fun blurLine(
    src: IntArray,
    dst: IntArray,
    width: Int,
    height: Int,
    radius: Int,
    horizontal: Boolean,
) {
    val lines = if (horizontal) height else width
    val len = if (horizontal) width else height
    val window = 2 * radius + 1
    for (line in 0 until lines) {
        fun index(i: Int): Int {
            val c = i.coerceIn(0, len - 1)
            return if (horizontal) line * width + c else c * width + line
        }
        var a = 0L
        var r = 0L
        var g = 0L
        var b = 0L
        for (i in -radius..radius) {
            val p = src[index(i)]
            a += (p ushr 24) and 0xFF
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }
        for (i in 0 until len) {
            dst[index(i)] =
                (((a / window).toInt()) shl 24) or
                (((r / window).toInt()) shl 16) or
                (((g / window).toInt()) shl 8) or
                ((b / window).toInt())
            val out = src[index(i - radius)]
            val inn = src[index(i + radius + 1)]
            a += ((inn ushr 24) and 0xFF) - ((out ushr 24) and 0xFF)
            r += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
            g += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
            b += (inn and 0xFF) - (out and 0xFF)
        }
    }
}

private const val BLUR_SOURCE_PX = 48
private const val BLUR_RADIUS_PX = 7
private val blurCache = LruCache<String, ImageBitmap>(6)

/**
 * The pre-blurred cover for [url], null until it is ready. While the next cover is being prepared the
 * previous one stays, so a song change never flashes an empty background.
 */
@Composable
fun rememberBlurredCover(url: String?): State<ImageBitmap?> {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(initialValue = url?.let { blurCache.get(it) }, url) {
        if (url == null) {
            value = null
            return@produceState
        }
        blurCache.get(url)?.let {
            value = it
            return@produceState
        }
        val blurred =
            withContext(Dispatchers.Default) {
                runCatching {
                    val result =
                        context.imageLoader.execute(
                            ImageRequest
                                .Builder(context)
                                .data(url)
                                .size(BLUR_SOURCE_PX)
                                .allowHardware(false)
                                .build(),
                        )
                    val bitmap = result.image?.toBitmap() ?: return@runCatching null
                    val w = bitmap.width
                    val h = bitmap.height
                    val pixels = IntArray(w * h)
                    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                    val out = boxBlurArgb(pixels, w, h, BLUR_RADIUS_PX)
                    Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
                }.getOrNull()
            }
        if (blurred != null) {
            blurCache.put(url, blurred)
            value = blurred
        }
    }
}
