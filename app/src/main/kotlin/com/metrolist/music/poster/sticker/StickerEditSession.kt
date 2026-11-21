/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.poster.sticker

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.max
import kotlin.math.min

/** What a finger does on the picture: move it (and pinch to zoom), erase, or bring back. */
enum class StickerTool { MOVE, ERASE, RESTORE }

/**
 * One sticker being edited ("Ubah stiker"): the picture ([argb], unpremultiplied, at most 1024 px on the long side), its own alpha
 * ([original], what "Pulihkan" brings back and "Kembalikan asli" returns to), the [mask] being edited, and [display] = the picture with the
 * mask as alpha, kept up to date box by box (what the screen draws over the checkerboard). Main thread only, except [maskCopy] / [setMask]
 * results computed elsewhere and handed in on the main thread.
 *
 * [editKey] = the library sticker this edits (null for a new picture). Memory: the picture 4 MB + the display 4 MB + three masks 3 MB for a
 * 1024 x 1024 picture, the history at most 16 MB deflated.
 */
class StickerEditSession(val w: Int, val h: Int, val argb: IntArray, val original: ByteArray, mask: ByteArray, val editKey: String?) {
    val mask: ByteArray = mask
    val display: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    private val history = StickerHistory()
    private var stroke: StickerStroke? = null
    private var liveBefore: ByteArray? = null

    /** Bumped on every change of [display]: the screen reads it to draw again. */
    var version by mutableIntStateOf(0)
        private set

    /** Bumped on every change of [mask] that should be kept (written to the session cache). */
    var committed by mutableIntStateOf(0)
        private set
    var tool by mutableStateOf(StickerTool.ERASE)
        private set

    /** "Ukuran kuas" 0..1 (on the screen: 6..70 dp, whatever the zoom). */
    var brush by mutableFloatStateOf(0.35f)

    /** "Kelembutan" 0..1. */
    var softness by mutableFloatStateOf(0.35f)

    /** The way of the last "Hapus latar" (null: not run yet). */
    var method by mutableStateOf<StickerMethod?>(null)
    var tolerance by mutableFloatStateOf(0.35f)
    var smooth by mutableFloatStateOf(0.25f)

    /** The background is being taken off (the network or the fill runs). */
    var busy by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** Called after every kept change of the mask (the flow writes it to the session cache). */
    var onCommit: (() -> Unit)? = null

    /** Something was changed (leaving asks first). */
    var changed by mutableStateOf(false)

    init {
        require(w > 0 && h > 0 && argb.size >= w * h && original.size >= w * h && mask.size >= w * h) { "bad sticker" }
        refresh(0, 0, w, h)
    }

    fun selectTool(t: StickerTool) {
        if (t == tool) return
        val ghost = t == StickerTool.RESTORE || tool == StickerTool.RESTORE
        tool = t
        // "Pulihkan" shows what was taken off faintly, so the finger knows where it can bring something back
        if (ghost) refresh(0, 0, w, h)
    }

    /** Recomputes [display] in the box [x0, x1) x [y0, y1). */
    fun refresh(x0: Int, y0: Int, x1: Int, y1: Int) {
        val l = max(0, x0)
        val t = max(0, y0)
        val r = min(w, x1)
        val b = min(h, y1)
        if (r <= l || b <= t) return
        val bw = r - l
        val row = IntArray(bw)
        val ghost = tool == StickerTool.RESTORE
        for (y in t until b) {
            val base = y * w
            for (x in 0 until bw) {
                val i = base + l + x
                var a = mask[i].toInt() and 255
                if (ghost) a = max(a, (original[i].toInt() and 255) / 4)
                row[x] = (a shl 24) or (argb[i] and 0xFFFFFF)
            }
            display.setPixels(row, 0, bw, l, y, bw, 1)
        }
        version++
    }

    private fun refresh(box: IntArray?) {
        if (box != null) refresh(box[0], box[1], box[2], box[3])
    }

    // ------------------------------------------------------------------------------------------------ the brush

    /**
     * Bumped by every edit of the user's (a stroke, undo, redo, a whole new mask). A live re-run of the colour fill that started before it is
     * stale: the flow drops it instead of showing it over the user's stroke (lead review 2026-11-13).
     */
    var editGeneration = 0
        private set

    /** Starts a stroke at ([x], [y]) (picture pixels) with a brush of [radius] picture pixels; nothing for the move tool. */
    fun beginStroke(x: Float, y: Float, radius: Float) {
        stroke?.let { endStroke() }
        if (tool == StickerTool.MOVE || busy) return
        // a live "Toleransi" result on screen becomes its own undo step first, and any re-run still computing is now stale
        commitLive()
        editGeneration++
        stroke = StickerStroke(w, h, mask, original, tool == StickerTool.ERASE, radius.coerceIn(1f, max(w, h).toFloat()), softness).also { refresh(it.to(x, y)) }
    }

    fun strokeTo(x: Float, y: Float) {
        val s = stroke ?: return
        refresh(s.to(x, y))
    }

    /** The finger left: the stroke is one undo step. */
    fun endStroke() {
        val s = stroke ?: return
        stroke = null
        s.patch()?.let { push(it) }
    }

    /** A second finger came (a pinch, not a stroke): the stroke is taken back. */
    fun cancelStroke() {
        val s = stroke ?: return
        stroke = null
        refresh(s.cancel())
    }

    val stroking: Boolean get() = stroke != null

    // ------------------------------------------------------------------------------------------------ whole-mask changes

    /** A copy of the mask (for work off the main thread). */
    fun maskCopy(): ByteArray = mask.copyOf()

    /** Puts a whole new mask in as one undo step (the result of "Hapus latar", "Kembalikan asli"). */
    fun setMask(next: ByteArray) {
        if (next.size < w * h) return
        cancelStroke()
        editGeneration++
        val before = liveBefore ?: mask.copyOf()
        liveBefore = null
        System.arraycopy(next, 0, mask, 0, w * h)
        refresh(0, 0, w, h)
        if (!before.contentEquals(mask)) push(StickerPatch.of(before, mask, w, 0, 0, w, h))
    }

    /** While a slider re-runs the fill ("Toleransi", "Haluskan tepi"): the mask changes without an undo step until [commitLive] ends it. */
    fun showLive(next: ByteArray) {
        if (next.size < w * h) return
        if (liveBefore == null) liveBefore = mask.copyOf()
        System.arraycopy(next, 0, mask, 0, w * h)
        refresh(0, 0, w, h)
    }

    /** The live result on screen (if any) becomes one undo step (and is written to the session cache like every kept change). */
    fun commitLive() {
        val before = liveBefore ?: return
        liveBefore = null
        if (!before.contentEquals(mask)) push(StickerPatch.of(before, mask, w, 0, 0, w, h))
    }

    /** True while a live result is on screen and not yet an undo step. */
    val live: Boolean get() = liveBefore != null

    /** "Kembalikan asli": the picture as it came (its own alpha). */
    fun resetToOriginal() {
        setMask(original.copyOf())
        method = null
    }

    fun undo() {
        cancelStroke()
        commitLive()
        editGeneration++
        refresh(history.undo(mask, w))
        afterHistory()
    }

    fun redo() {
        cancelStroke()
        commitLive()
        editGeneration++
        refresh(history.redo(mask, w))
        afterHistory()
    }

    private fun push(p: StickerPatch) {
        history.push(p)
        afterHistory()
    }

    private fun afterHistory() {
        canUndo = history.canUndo
        canRedo = history.canRedo
        changed = true
        committed++
        onCommit?.invoke()
    }

    /** True when nothing of the picture is kept (Selesai would save an empty sticker). */
    fun isEmpty(): Boolean = StickerMask.bounds(mask, w, h) == null

    /** The finished sticker: cropped to what is kept, edge colours cleaned, alpha = the mask. Null when nothing is kept. */
    fun result(m: ByteArray = mask): Bitmap? {
        val box = StickerMask.bounds(m, w, h) ?: return null
        val px = StickerMask.compose(argb, m, w, h, box)
        val bw = box[2] - box[0]
        val bh = box[3] - box[1]
        return Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, bw, 0, 0, bw, bh) }
    }

    /** Frees the history. The display bitmap is left to the garbage collector: the screen's last frame may still be drawing it. */
    fun release() {
        history.clear()
        onCommit = null
    }

    companion object {
        /** A session for [bitmap] (any config; converted to unpremultiplied ARGB ints); [mask] null = the picture's own alpha. */
        fun of(bitmap: Bitmap, mask: ByteArray?, editKey: String?): StickerEditSession {
            val w = bitmap.width
            val h = bitmap.height
            val px = IntArray(w * h)
            bitmap.getPixels(px, 0, w, 0, 0, w, h)
            val own = StickerMask.alphaOf(px, w * h)
            val m = mask?.takeIf { it.size == w * h } ?: own.copyOf()
            return StickerEditSession(w, h, px, own, m, editKey)
        }
    }
}
