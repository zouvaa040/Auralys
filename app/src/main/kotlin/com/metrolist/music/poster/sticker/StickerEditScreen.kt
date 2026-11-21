/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.poster.sticker

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.poster.Chip
import com.metrolist.music.poster.GroupTitle
import com.metrolist.music.poster.Pill
import com.metrolist.music.poster.SliderRow
import com.metrolist.music.poster.TopBar
import com.metrolist.music.ui.component.painterResource
import com.metrolist.music.ui.theme.LocalHorizon
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

// ====================================================================================================================
// "Ubah stiker" (round 4.3.5): the user's picture over a checkerboard (the see-through parts show as such), zoom and move with two
// fingers (or one with "Geser"), the brush "Hapus" / "Pulihkan" with "Ukuran kuas" and "Kelembutan", "Hapus latar" (Orang / Warna with
// "Toleransi" and "Haluskan tepi"), "Kembalikan asli", undo and redo, "Selesai". Horizon look: neutral ink controls, no accent colour.

/** The brush on the screen, whatever the zoom: from 6 to 70 dp across the radius. */
internal fun brushRadiusPx(brush: Float, density: Density): Float = with(density) { (6f + 64f * brush.coerceIn(0f, 1f)).dp.toPx() }

/** The view of the picture: [zoom] 1 = the whole picture fits, [offset] = where its centre sits from the box's centre (pixels). */
internal class StickerView(val zoom: Float, val offset: Offset) {
    /** Picture pixels per screen pixel at this zoom in a box of [box]. */
    fun scale(box: IntSize, w: Int, h: Int): Float = min(box.width * 0.92f / w, box.height * 0.92f / h).coerceAtLeast(1e-3f) * zoom

    fun topLeft(box: IntSize, w: Int, h: Int): Offset {
        val k = scale(box, w, h)
        return Offset(box.width / 2f + offset.x - w * k / 2f, box.height / 2f + offset.y - h * k / 2f)
    }

    fun toPicture(p: Offset, box: IntSize, w: Int, h: Int): Offset {
        val k = scale(box, w, h)
        val tl = topLeft(box, w, h)
        return Offset((p.x - tl.x) / k, (p.y - tl.y) / k)
    }

    /** Zoomed by [factor] about the screen point [c] and moved by [pan]; the picture's centre never leaves the picture's own half size. */
    fun pinched(box: IntSize, w: Int, h: Int, c: Offset, factor: Float, pan: Offset): StickerView {
        val z = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        val f = z / zoom
        val centre = Offset(box.width / 2f, box.height / 2f)
        var o = (offset - (c - centre)) * f + (c - centre) + pan
        val k = min(box.width * 0.92f / w, box.height * 0.92f / h) * z
        o = Offset(o.x.coerceIn(-w * k / 2f, w * k / 2f), o.y.coerceIn(-h * k / 2f, h * k / 2f))
        return StickerView(z, o)
    }

    companion object {
        const val MAX_ZOOM = 10f
        val FIT = StickerView(1f, Offset.Zero)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StickerEditScreen(flow: StickerEditFlow) {
    val hz = LocalHorizon.current
    val s = flow.session
    var askDiscard by remember { mutableStateOf(false) }
    val close = { if (s != null && s.changed) askDiscard = true else flow.cancel() }
    BackHandler { close() }
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val panel: Dp = (screenH * 0.36f).coerceIn(210.dp, 340.dp)
    val bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding().coerceAtLeast(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
    Column(Modifier.fillMaxSize().background(hz.bg).padding(bottom = bottom)) {
        TopBar(stringResource(R.string.poster_sticker_edit_title), { close() }) {
            IconButton(onClick = { s?.undo() }, enabled = s?.canUndo == true) {
                Icon(painterResource(R.drawable.replay), stringResource(R.string.poster_undo), tint = if (s?.canUndo == true) hz.ink else hz.text3)
            }
            IconButton(onClick = { s?.redo() }, enabled = s?.canRedo == true) {
                Icon(painterResource(R.drawable.replay), stringResource(R.string.poster_sticker_redo), tint = if (s?.canRedo == true) hz.ink else hz.text3, modifier = Modifier.graphicsLayer(scaleX = -1f))
            }
            Pill(stringResource(R.string.poster_sticker_done), onClick = { flow.done() }, filled = true, enabled = s != null && !s.busy && !flow.saving)
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            if (s != null) StickerCanvas(s, Modifier.fillMaxSize())
            if (s == null || flow.opening) {
                CircularProgressIndicator(Modifier.size(28.dp).align(Alignment.Center), color = hz.ink, strokeWidth = 2.5.dp)
            }
            if (s != null && (s.busy || flow.saving)) {
                Row(
                    Modifier.align(Alignment.TopCenter).padding(top = 10.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.62f)).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (s.busy && s.progress > 0f) {
                        CircularProgressIndicator(progress = { s.progress }, modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp, trackColor = Color.White.copy(alpha = 0.25f))
                    } else {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    }
                    Spacer(Modifier.width(8.dp))
                    val pct = if (s.busy && s.progress > 0f) " ${(s.progress * 100).roundToInt()}%" else ""
                    Text((if (s.busy) stringResource(R.string.poster_sticker_removing) else stringResource(R.string.poster_saving)) + pct, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
                    if (s.busy) {
                        TextButton(onClick = { flow.stopBackground() }) { Text(stringResource(R.string.poster_cancel), color = Color.White, style = MaterialTheme.typography.labelLarge) }
                    } else {
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(panel).background(hz.surface1)) {
            if (s != null) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                    GroupTitle(stringResource(R.string.poster_background))
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        Pill(stringResource(R.string.poster_sticker_remove_bg), onClick = { flow.removeBackground(null) }, filled = true, enabled = !s.busy)
                        Chip(stringResource(R.string.poster_sticker_method_person), s.method == StickerMethod.PERSON) { if (!s.busy) flow.removeBackground(StickerMethod.PERSON) }
                        Chip(stringResource(R.string.poster_sticker_method_color), s.method == StickerMethod.COLOR) { if (!s.busy) flow.removeBackground(StickerMethod.COLOR) }
                    }
                    if (s.method == StickerMethod.COLOR) {
                        SliderRow(stringResource(R.string.poster_sticker_tolerance), s.tolerance, 0f..1f, "${(s.tolerance * 100).roundToInt()}%", {}, enabled = !s.busy, onEnd = { flow.tuneDone() }) {
                            s.tolerance = it
                            flow.tune()
                        }
                        SliderRow(stringResource(R.string.poster_sticker_smooth_edge), s.smooth, 0f..1f, "${(s.smooth * 100).roundToInt()}%", {}, enabled = !s.busy, onEnd = { flow.tuneDone() }) {
                            s.smooth = it
                            flow.tune()
                        }
                    }
                    GroupTitle(stringResource(R.string.poster_sticker_brush))
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(stringResource(R.string.poster_sticker_move), s.tool == StickerTool.MOVE) { s.selectTool(StickerTool.MOVE) }
                        Chip(stringResource(R.string.poster_sticker_erase), s.tool == StickerTool.ERASE) { s.selectTool(StickerTool.ERASE) }
                        Chip(stringResource(R.string.poster_sticker_restore), s.tool == StickerTool.RESTORE) { s.selectTool(StickerTool.RESTORE) }
                    }
                    SliderRow(stringResource(R.string.poster_sticker_brush_size), s.brush, 0f..1f, "${(s.brush * 100).roundToInt()}%", {}) { s.brush = it }
                    SliderRow(stringResource(R.string.poster_sticker_softness), s.softness, 0f..1f, "${(s.softness * 100).roundToInt()}%", {}) { s.softness = it }
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Pill(stringResource(R.string.poster_sticker_reset), onClick = { flow.resetToOriginal() }, enabled = !s.busy)
                    }
                }
            }
        }
    }
    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text(stringResource(R.string.poster_sticker_discard_title)) },
            confirmButton = {
                TextButton(onClick = {
                    askDiscard = false
                    flow.cancel()
                }) { Text(stringResource(R.string.poster_sticker_discard)) }
            },
            dismissButton = { TextButton(onClick = { askDiscard = false }) { Text(stringResource(R.string.poster_stay)) } },
        )
    }
}

/** The checkerboard behind the picture: two quiet greys, 8 dp squares, fixed to the screen (it does not zoom). */
private fun checker(density: Density): ShaderBrush {
    val cell = with(density) { 8.dp.toPx() }.roundToInt().coerceAtLeast(2)
    val b = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    c.drawColor(0xFFF1F2F4.toInt())
    val p = android.graphics.Paint().apply { color = 0xFFD8DBE0.toInt() }
    c.drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), p)
    c.drawRect(cell.toFloat(), cell.toFloat(), cell * 2f, cell * 2f, p)
    return ShaderBrush(ImageShader(b.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

@Composable
private fun StickerCanvas(s: StickerEditSession, modifier: Modifier) {
    val hz = LocalHorizon.current
    val density = LocalDensity.current
    var box by remember { mutableStateOf(IntSize.Zero) }
    var view by remember(s) { mutableStateOf(StickerView.FIT) }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    var cursorRadius by remember { mutableFloatStateOf(0f) }
    val img: ImageBitmap = remember(s) { s.display.asImageBitmap() }
    val checkerBrush = remember(density) { checker(density) }
    Box(
        modifier
            .onSizeChanged { box = it }
            .clipToBounds()
            .pointerInput(s) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val size = box
                    if (size.width < 8 || size.height < 8) return@awaitEachGesture
                    val draws = s.tool != StickerTool.MOVE && !s.busy
                    var pinch = false
                    if (draws) {
                        val v = view
                        val q = v.toPicture(down.position, size, s.w, s.h)
                        val r = brushRadiusPx(s.brush, density)
                        s.beginStroke(q.x, q.y, r / v.scale(size, s.w, s.h))
                        cursor = down.position
                        cursorRadius = r
                    }
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (pressed >= 2 && !pinch) {
                            // a second finger: it is a pinch, the stroke so far is taken back
                            pinch = true
                            s.cancelStroke()
                            cursor = null
                        }
                        if (pinch || !draws) {
                            val zoom = ev.calculateZoom()
                            val pan = ev.calculatePan()
                            if (abs(zoom - 1f) > 0.0005f || pan.getDistance() > 0.01f) view = view.pinched(size, s.w, s.h, ev.calculateCentroid(useCurrent = true), zoom, pan)
                        } else if (s.stroking) {
                            val ch = ev.changes.firstOrNull { it.id == down.id && it.pressed } ?: ev.changes.first { it.pressed }
                            val v = view
                            for (hst in ch.historical) {
                                val q = v.toPicture(hst.position, size, s.w, s.h)
                                s.strokeTo(q.x, q.y)
                            }
                            val q = v.toPicture(ch.position, size, s.w, s.h)
                            s.strokeTo(q.x, q.y)
                            cursor = ch.position
                        }
                        ev.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                    if (s.stroking) s.endStroke()
                    cursor = null
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // read on every change of the picture (the bitmap is updated in place)
            if (s.version < 0) return@Canvas
            val area = IntSize(size.width.toInt(), size.height.toInt())
            val k = view.scale(area, s.w, s.h)
            val tl = view.topLeft(area, s.w, s.h)
            val dw = s.w * k
            val dh = s.h * k
            clipRect(tl.x, tl.y, tl.x + dw, tl.y + dh) { drawRect(checkerBrush, topLeft = tl, size = Size(dw, dh)) }
            drawImage(
                img,
                dstOffset = IntOffset(tl.x.roundToInt(), tl.y.roundToInt()),
                dstSize = IntSize(dw.roundToInt().coerceAtLeast(1), dh.roundToInt().coerceAtLeast(1)),
                // zoomed in, the pixels show as they are (the brush works on them); otherwise smooth
                filterQuality = if (k >= 3f) FilterQuality.None else FilterQuality.Low,
            )
            drawRect(hz.divider, topLeft = tl, size = Size(dw, dh), style = Stroke(1.dp.toPx()))
            cursor?.let { c ->
                drawCircle(Color.Black.copy(alpha = 0.45f), cursorRadius + 1.5.dp.toPx(), c, style = Stroke(1.dp.toPx()))
                drawCircle(Color.White, cursorRadius, c, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}
