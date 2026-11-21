/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.poster

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** How long the preview waits without a change before it prepares the caches of the next gesture. */
private const val WARM_AFTER_MS = 350L

/** Where the poster (9:16 unless [ratio] says otherwise) sits inside a box of [w] x [h] pixels: left, top, width, height (whole pixels). */
fun posterBox(w: Int, h: Int, ratio: PosterRatio = PosterRatio.DEFAULT): IntArray {
    val bw = min(w.toFloat(), h * ratio.w / ratio.h.toFloat()).toInt().coerceAtLeast(2)
    val bh = (bw * ratio.h / ratio.w.toFloat()).roundToInt().coerceIn(2, h.coerceAtLeast(2))
    return intArrayOf((w - bw) / 2, (h - bh) / 2, bw, bh)
}

/** The preview's quality on this phone: 0 = like the export, 1 = lighter (a low-memory phone or few cores). The export is always 0. */
fun previewLevel(context: Context): Int {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val weak = am?.isLowRamDevice == true || (am?.memoryClass ?: 256) < 192 || Runtime.getRuntime().availableProcessors() < 6
    return if (weak) 1 else 0
}

/** What a touch on the picture works on. */
sealed interface PosterTouchTarget {
    data class TextHandle(val index: Int) : PosterTouchTarget
    data class Text(val index: Int) : PosterTouchTarget
    data class StickerHandle(val index: Int) : PosterTouchTarget
    data class Sticker(val index: Int) : PosterTouchTarget
    data class Card(val index: Int) : PosterTouchTarget
    data object Photo : PosterTouchTarget
    data object None : PosterTouchTarget
}

/** The editor's side of the touches, in poster pixels. */
interface PosterTouch {
    fun hit(x: Float, y: Float): PosterTouchTarget

    fun begin(target: PosterTouchTarget)

    /** One step of a drag or a pinch: the fingers moved by ([dx], [dy]), spread by [zoom], turned by [turn] degrees about ([cx], [cy]); ([x], [y]) = the first finger. */
    fun move(target: PosterTouchTarget, x: Float, y: Float, cx: Float, cy: Float, dx: Float, dy: Float, zoom: Float, turn: Float)

    fun end(target: PosterTouchTarget)

    fun tap(target: PosterTouchTarget)
}

/**
 * The poster as the editor shows it. The picture is painted by a [PosterRenderer] on a background thread whenever the state changes (only the latest state
 * is painted, so a slow phone skips states instead of falling behind), into one of three bitmaps in turn; the outline of the selected card or sticker is drawn
 * on top straight from the state, so it follows the finger at once. [touch] null = no gestures (the tests).
 */
@Composable
fun PosterPreview(state: PosterState, assets: PosterAssets, modifier: Modifier = Modifier, touch: PosterTouch? = null, level: Int = -1, onRendered: ((Long) -> Unit)? = null) {
    val context = LocalContext.current
    val lvl = remember(level) { if (level >= 0) level else previewLevel(context) }
    val renderer = remember { PosterRenderer(context, assets) }
    // every paint of this preview runs on ONE lane, so an old paint still finishing never overlaps a new one or the release
    val lane = remember { Dispatchers.Default.limitedParallelism(1) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }
    DisposableEffect(renderer) { onDispose { CoroutineScope(lane).launch { renderer.release() } } }
    val ratio = state.ratio
    val box = remember(size, ratio) { posterBox(size.width, size.height, ratio) }
    LaunchedEffect(renderer, box[2], box[3], lvl) {
        if (box[2] < 8 || box[3] < 8) return@LaunchedEffect
        val buffers = arrayOfNulls<Bitmap>(3)
        var n = 0
        var warm: Pair<Job, AtomicBoolean>? = null
        snapshotFlow { state.frame() }.conflate().collect { f ->
            // a new picture is wanted: the idle preparation stops after its current step
            warm?.let {
                it.second.set(true)
                it.first.cancel()
            }
            val slot = n % 3
            // a picture that cannot be painted (memory) is skipped: the editor and the music must never go down for a preview
            val out =
                try {
                    withContext(lane) { renderer.render(f, box[2], box[3], lvl, buffers[slot]) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    timber.log.Timber.w(t, "poster: preview paint failed")
                    null
                } ?: return@collect
            buffers[slot] = out
            n++
            shown = out.asImageBitmap()
            onRendered?.invoke(renderer.lastMillis)
            // one picture per screen frame: a bitmap is painted again only after the screen has moved on from it
            withFrameNanos { }
            // idle for a moment: get the caches of a gesture on the photo or a ground slider ready (PosterRenderer.warm), so its first frame is fast too
            val stop = AtomicBoolean(false)
            val job =
                launch {
                    delay(WARM_AFTER_MS)
                    withContext(lane) {
                        if (!stop.get()) {
                            try {
                                renderer.warm(f, box[2], box[3], lvl) { stop.get() }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                timber.log.Timber.w(t, "poster: preview warm-up failed")
                            }
                        }
                    }
                }
            warm = job to stop
        }
    }
    val slop = LocalViewConfiguration.current.touchSlop
    val gestures =
        if (touch == null) {
            Modifier
        } else {
            Modifier.pointerInput(touch, box[2], box[3], ratio) {
                val k = box[2] / ratio.w.toFloat()
                fun toPoster(o: Offset) = Offset((o.x - box[0]) / k, (o.y - box[1]) / k)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = toPoster(down.position)
                    val target = touch.hit(start.x, start.y)
                    var dragging = false
                    var travel = Offset.Zero
                    var began = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val changes: List<PointerInputChange> = event.changes
                        if (changes.none { it.pressed }) break
                        val pan = event.calculatePan()
                        val zoom = event.calculateZoom()
                        val turn = event.calculateRotation()
                        val pointers = changes.count { it.pressed }
                        if (!dragging) {
                            travel += pan
                            if (travel.getDistance() > slop || (pointers > 1 && (abs(zoom - 1f) > 0.01f || abs(turn) > 0.5f))) dragging = true
                        }
                        if (dragging) {
                            if (!began) {
                                touch.begin(target)
                                began = true
                            }
                            val c = toPoster(event.calculateCentroid(useCurrent = true))
                            val first = toPoster(changes.first { it.pressed }.position)
                            touch.move(target, first.x, first.y, c.x, c.y, pan.x / k, pan.y / k, if (pointers > 1) zoom else 1f, if (pointers > 1) turn else 0f)
                            changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    }
                    if (began) touch.end(target) else touch.tap(target)
                }
            }
        }
    Box(modifier.onSizeChanged { size = it }.then(gestures)) {
        Canvas(Modifier.fillMaxSize()) {
            val img = shown
            if (img != null) {
                drawImage(img, dstOffset = IntOffset(box[0], box[1]), dstSize = IntSize(box[2], box[3]))
            } else {
                drawRect(PosterBrand.Navy900, topLeft = Offset(box[0].toFloat(), box[1].toFloat()), size = androidx.compose.ui.geometry.Size(box[2].toFloat(), box[3].toFloat()))
            }
            if (touch != null) drawHandles(state, box, ratio)
        }
    }
}

/** The outline of the selected card (projected, so it hugs the tilted glass) or sticker (with its corner handle), in the preview only. */
private fun DrawScope.drawHandles(state: PosterState, box: IntArray, ratio: PosterRatio) {
    val k = box[2] / ratio.w.toFloat()
    fun px(x: Float, y: Float) = Offset(box[0] + x * k, box[1] + y * k)
    val sel = state.selected
    if (sel in state.poses.indices) {
        val camera = PosterGeometry.cameraFor(state.cardPerspective)
        val pts = PosterGeometry.outline(state.poses[sel], camera, 0f, PosterGeometry.cornerFor(state.cardCorner) + 8f, 6)
        val path = Path()
        for (i in 0 until pts.size / 2) {
            val o = px(pts[i * 2], pts[i * 2 + 1])
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        path.close()
        drawPath(path, Color.Black.copy(alpha = 0.45f), style = Stroke(5f, join = StrokeJoin.Round))
        drawPath(path, Color.White.copy(alpha = 0.92f), style = Stroke(2.5f, join = StrokeJoin.Round))
    }
    val ss = state.selectedSticker
    if (ss in state.stickers.indices) {
        val s = state.stickers[ss]
        val aspect = state.stickerAspect(s)
        val b = PosterGeometry.stickerBounds(s, aspect)
        val path = Path()
        for (i in 0 until 4) {
            val o = px(b[i * 2], b[i * 2 + 1])
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        path.close()
        drawPath(path, Color.Black.copy(alpha = 0.45f), style = Stroke(5f, join = StrokeJoin.Round))
        drawPath(path, Color.White.copy(alpha = 0.95f), style = Stroke(2.5f, join = StrokeJoin.Round))
        val (hx, hy) = PosterGeometry.stickerHandle(s, aspect)
        val h = px(hx, hy)
        drawCircle(Color.Black.copy(alpha = 0.45f), 17f, h)
        drawCircle(Color.White, 13f, h)
        drawCircle(Color(0xFF0B0C0F), 4.5f, h)
    }
    val st = state.selectedText
    if (st in state.texts.indices) {
        val t = state.texts[st]
        val b = PosterTexts.bounds(t)
        val path = Path()
        for (i in 0 until 4) {
            val o = px(b[i * 2], b[i * 2 + 1])
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        path.close()
        drawPath(path, Color.Black.copy(alpha = 0.45f), style = Stroke(5f, join = StrokeJoin.Round))
        drawPath(path, Color.White.copy(alpha = 0.95f), style = Stroke(2.5f, join = StrokeJoin.Round))
        val (hx, hy) = PosterTexts.handle(t)
        val h = px(hx, hy)
        drawCircle(Color.Black.copy(alpha = 0.45f), 17f, h)
        drawCircle(Color.White, 13f, h)
        drawCircle(Color(0xFF0B0C0F), 4.5f, h)
    }
}

/** Width over height of a sticker's picture (1 for the drawn ones). */
fun PosterState.stickerAspect(s: PosterSticker): Float {
    if (s.kind != StickerKind.CUSTOM) return 1f
    val b = s.imageKey?.let { stickerImages[it] } ?: return 1f
    return b.width.toFloat() / b.height.coerceAtLeast(1)
}

fun PosterState.stickerAspects(): Map<String, Float> = stickerImages.mapValues { (_, b) -> b.width.toFloat() / b.height.coerceAtLeast(1) }
