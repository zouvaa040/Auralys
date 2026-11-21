/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.poster.sticker

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.music.R
import com.metrolist.music.poster.PosterPhotoLoad
import com.metrolist.music.poster.PosterSessionFiles
import com.metrolist.music.poster.PosterStickerLibrary
import com.metrolist.music.poster.cutout.CutoutOutcome
import com.metrolist.music.poster.cutout.PosterCutout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.coroutines.coroutineContext

/**
 * The sticker editor's work for the poster's view model (round 4.3.5): opening the user's pictures one after the other (several can be
 * picked), "Hapus latar" (the picture's own alpha, the cut-out network for a person, or the colour fill; chosen by [StickerMask.chooseMethod]
 * and switchable), the live "Toleransi" / "Haluskan tepi", "Selesai" (the sticker into "Stiker saya", its original and mask kept for a later
 * "Ubah"), and the session cache + saved state that bring the editor back after the process died.
 *
 * Main thread for everything public; the pixel work runs on [Dispatchers.Default], files on [persistLane] / IO.
 */
class StickerEditFlow(
    private val context: Context,
    private val scope: CoroutineScope,
    private val library: PosterStickerLibrary,
    private val files: () -> PosterSessionFiles,
    private val persistLane: CoroutineDispatcher,
    private val toast: (Int) -> Unit,
    /** A finished sticker: its library [key], the picture, a thumbnail, and whether it is new (else an edit of [key]). */
    private val onSaved: (key: String, bitmap: Bitmap, thumb: Bitmap?, isNew: Boolean) -> Unit,
) {
    var session by mutableStateOf<StickerEditSession?>(null)
        private set

    /** A picture is being opened (decoded) or brought back. */
    var opening by mutableStateOf(false)
        private set

    /** "Selesai" is writing the sticker. */
    var saving by mutableStateOf(false)
        private set

    /** How many pictures wait after this one (several were picked). */
    var waiting by mutableStateOf(0)
        private set

    private val queue = ArrayDeque<Uri>()
    private var openJob: Job? = null
    private var autoJob: Job? = null

    /** Counts the runs of "Hapus latar": only the newest one clears [StickerEditSession.busy]. */
    private var autoGen = 0
    private var liveJob: Job? = null
    private var persistJob: Job? = null

    /** The pictures the user picked: each opens in the editor in turn. */
    fun open(uris: List<Uri>) {
        queue.addAll(uris)
        waiting = queue.size
        if (session == null && !opening) next()
    }

    private fun next() {
        val uri = queue.removeFirstOrNull()
        waiting = queue.size
        if (uri == null) return
        opening = true
        openJob =
            scope.launch {
                val s =
                    try {
                        withContext(Dispatchers.IO) {
                            val bmp = PosterPhotoLoad.decode(context, uri, PosterPhotoLoad.STICKER_SIDE)
                            try {
                                StickerEditSession.of(bmp, null, null)
                            } finally {
                                bmp.recycle()
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Timber.w(t, "poster: sticker picture")
                        null
                    }
                opening = false
                if (s == null) {
                    toast(R.string.poster_sticker_unreadable)
                    next()
                    return@launch
                }
                show(s, writePicture = true)
                // a new picture: its background goes at once (the right way chosen for it); "Kembalikan asli" brings it back
                removeBackground(null)
            }
    }

    /** "Ubah" on a sticker of "Stiker saya": the editor opens on the picture it was made from, with the mask it ended with. */
    fun openLibrary(key: String) {
        if (session != null || opening) return
        opening = true
        openJob =
            scope.launch {
                val s =
                    try {
                        withContext(Dispatchers.IO) {
                            val (bmp, mask) = library.loadForEdit(key) ?: return@withContext null
                            try {
                                StickerEditSession.of(bmp, mask, key)
                            } finally {
                                bmp.recycle()
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Timber.w(t, "poster: sticker for edit")
                        null
                    }
                opening = false
                if (s == null) {
                    toast(R.string.poster_sticker_unreadable)
                    return@launch
                }
                show(s, writePicture = true)
            }
    }

    private fun show(s: StickerEditSession, writePicture: Boolean) {
        session?.release()
        session = s
        s.onCommit = { persistMask() }
        if (writePicture) {
            val f = files()
            val px = s.argb
            val mask = s.maskCopy()
            scope.launch(persistLane) {
                runCatching {
                    f.writeStickerPicture(px, s.w, s.h)
                    f.writeStickerMask(s.w, s.h, mask)
                }.onFailure { Timber.w(it, "poster: sticker cache") }
            }
        }
    }

    /**
     * The mask goes to the session cache a moment after the last change (a stroke, an undo, "Hapus latar"). The copy is taken NOW, on the main
     * thread where the mask changes; the wait and the write run on the session's file lane (one at a time, in order: a later change is always
     * written after an earlier one), so nothing here depends on the main thread's clock (lead review 2026-11-13).
     */
    private fun persistMask(delayMs: Long = PERSIST_DELAY_MS) {
        val s = session ?: return
        persistJob?.cancel()
        val mask = s.maskCopy()
        val f = files()
        persistJob =
            scope.launch(persistLane) {
                if (delayMs > 0) delay(delayMs)
                runCatching { f.writeStickerMask(s.w, s.h, mask) }.onFailure { Timber.w(it, "poster: sticker mask cache") }
            }
    }

    /**
     * Waits until the mask's last write to the session cache is done (none pending: returns at once). For the tests, and for anything that must
     * read the cache back.
     */
    internal suspend fun flushPersist() {
        persistJob?.join()
    }

    /** "Hapus latar" with [method], or (null) the way that fits the picture. */
    fun removeBackground(method: StickerMethod?) {
        val s = session ?: return
        autoJob?.cancel()
        liveJob?.cancel()
        s.cancelStroke()
        val m = method ?: StickerMask.chooseMethod(s.argb, s.w, s.h)
        if (m == StickerMethod.KEEP) {
            autoGen++
            s.busy = false
            if (method == null) toast(R.string.poster_sticker_already_clear)
            s.setMask(s.original.copyOf())
            s.method = StickerMethod.KEEP
            return
        }
        val gen = ++autoGen
        s.busy = true
        s.progress = 0f
        autoJob =
            scope.launch {
                try {
                    val (mask, used) = withContext(Dispatchers.Default) { compute(s, m) }
                    if (session !== s) return@launch
                    if (mask == null) {
                        toast(R.string.poster_sticker_failed)
                        return@launch
                    }
                    if (used != m) toast(R.string.poster_sticker_no_person)
                    s.method = used
                    s.setMask(mask)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Timber.w(t, "poster: sticker background")
                    toast(if (t is OutOfMemoryError) R.string.poster_cut_memory else R.string.poster_sticker_failed)
                } finally {
                    // a newer run (or "Batal") owns the flag now
                    if (gen == autoGen) s.busy = false
                }
            }
    }

    /** The new mask for [method] (and the way really used: no person found falls back to the colour fill), or null when it failed. */
    private suspend fun compute(s: StickerEditSession, method: StickerMethod): Pair<ByteArray?, StickerMethod> {
        val ctx = coroutineContext
        if (method == StickerMethod.COLOR) return StickerMask.colorCut(s.argb, s.w, s.h, s.tolerance, s.smooth) { !ctx.isActive }.also { ctx.ensureActive() } to StickerMethod.COLOR
        // the network wants an opaque picture: see-through pixels on white
        val opaque = IntArray(s.w * s.h) { i ->
            val p = s.argb[i]
            val a = p ushr 24
            if (a == 255) {
                p
            } else {
                fun ch(v: Int) = (v * a + 255 * (255 - a)) / 255
                (255 shl 24) or (ch((p shr 16) and 255) shl 16) or (ch((p shr 8) and 255) shl 8) or ch(p and 255)
            }
        }
        return when (val out = PosterCutout.cut(context, opaque, s.w, s.h) { p -> s.progress = p.coerceIn(0f, 1f) }) {
            is CutoutOutcome.Done -> {
                val a = out.alpha
                for (i in a.indices) a[i] = minOf(a[i].toInt() and 255, s.original[i].toInt() and 255).toByte()
                a to StickerMethod.PERSON
            }
            CutoutOutcome.NoSubject -> StickerMask.colorCut(s.argb, s.w, s.h, s.tolerance, s.smooth) { !ctx.isActive } to StickerMethod.COLOR
            is CutoutOutcome.Failed -> {
                if (out.reason == "memory") throw OutOfMemoryError("cut-out")
                null to StickerMethod.PERSON
            }
        }
    }

    /** "Batal" on the progress: the background removal stops, the mask stays as it was. */
    fun stopBackground() {
        autoJob?.cancel()
        autoJob = null
        autoGen++
        session?.busy = false
    }

    /** "Kembalikan asli": the picture as it came (one undo step). */
    fun resetToOriginal() {
        val s = session ?: return
        autoJob?.cancel()
        liveJob?.cancel()
        autoGen++
        s.busy = false
        s.cancelStroke()
        s.resetToOriginal()
    }

    /** "Toleransi" or "Haluskan tepi" moved: the colour fill runs again on the latest values and is shown (one undo step when the slider stops). */
    fun tune() {
        val s = session ?: return
        if (s.method != StickerMethod.COLOR || s.busy) return
        // conflated: one fill at a time, always on the newest values (a fast slider never piles up work)
        tuneWanted = s.tolerance to s.smooth
        if (tuneRunning) return
        tuneRunning = true
        liveJob =
            scope.launch {
                try {
                    while (true) {
                        val (tol, sm) = tuneWanted ?: break
                        tuneWanted = null
                        // a result computed while the user edited (a stroke right after letting the slider go) is stale: never shown over it
                        val gen = s.editGeneration
                        val mask = withContext(Dispatchers.Default) {
                            val ctx = coroutineContext
                            StickerMask.colorCut(s.argb, s.w, s.h, tol, sm) { !ctx.isActive }.also { ctx.ensureActive() }
                        }
                        if (session !== s || s.editGeneration != gen) {
                            tuneWanted = null
                            break
                        }
                        s.showLive(mask)
                    }
                } finally {
                    tuneRunning = false
                }
            }
    }

    private var tuneWanted: Pair<Float, Float>? = null
    private var tuneRunning = false

    /** A live re-run of the colour fill is computing (for the tests). */
    internal val tuning: Boolean get() = tuneRunning

    /** The slider was let go: the last fill becomes one undo step (nothing when the user already edited: then it was committed before the edit). */
    fun tuneDone() {
        val s = session ?: return
        val job = liveJob
        scope.launch {
            job?.join()
            if (session === s) s.commitLive()
        }
    }

    /** "Selesai": the sticker into "Stiker saya" (new, or the edited one replaced) and onto the poster; the next picked one opens. */
    fun done() {
        val s = session ?: return
        if (saving || s.busy) return
        s.cancelStroke()
        if (s.isEmpty()) {
            toast(R.string.poster_sticker_empty)
            return
        }
        saving = true
        scope.launch {
            try {
                val mask = s.maskCopy()
                val saved =
                    withContext(Dispatchers.IO) {
                        val bmp = s.result(mask) ?: return@withContext null
                        val key = s.editKey?.takeIf { library.replace(it, bmp) } ?: library.add(bmp)
                        runCatching { library.saveOriginal(key, s.argb, s.w, s.h, mask, picture = s.editKey == null) }.onFailure { Timber.w(it, "poster: sticker original") }
                        Triple(key, bmp, library.thumb(key))
                    }
                if (saved == null) {
                    toast(R.string.poster_sticker_empty)
                    return@launch
                }
                onSaved(saved.first, saved.second, saved.third, s.editKey == null)
                close()
                next()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.w(t, "poster: sticker save")
                toast(R.string.poster_sticker_unreadable)
            } finally {
                saving = false
            }
        }
    }

    /** Back / "Buang": this picture is dropped; the next picked one opens. */
    fun cancel() {
        close()
        next()
    }

    /** Everything stops (the poster is left). */
    fun clear() {
        queue.clear()
        waiting = 0
        openJob?.cancel()
        close()
    }

    private fun close() {
        autoJob?.cancel()
        liveJob?.cancel()
        persistJob?.cancel()
        session?.release()
        session = null
        val f = files()
        scope.launch(persistLane) { runCatching { f.deleteSticker() } }
    }

    // ------------------------------------------------------------------------------------------------ after the process died

    /** What the system keeps for the open editor (the picture and the mask are in the session cache), or null. */
    fun saveState(): Bundle? {
        val s = session ?: return null
        // the system is saving: the last mask goes to the cache now, not after the usual short wait (the process may die soon after)
        persistMask(0)
        return Bundle().apply {
            putInt("w", s.w)
            putInt("h", s.h)
            putString("key", s.editKey)
            putString("tool", s.tool.name)
            putFloat("brush", s.brush)
            putFloat("soft", s.softness)
            putString("method", s.method?.name)
            putFloat("tol", s.tolerance)
            putFloat("smooth", s.smooth)
            putBoolean("changed", s.changed)
        }
    }

    /** Brings the editor back from [b] and the session cache (nothing when the cache is gone). */
    fun restore(b: Bundle?) {
        if (b == null || !b.containsKey("w")) return
        val w = b.getInt("w")
        val h = b.getInt("h")
        opening = true
        openJob =
            scope.launch {
                val s =
                    try {
                        withContext(Dispatchers.IO) {
                            val f = files()
                            val bmp = f.readStickerPicture() ?: return@withContext null
                            try {
                                if (bmp.width != w || bmp.height != h) return@withContext null
                                StickerEditSession.of(bmp, f.readStickerMask(w, h), b.getString("key"))
                            } finally {
                                bmp.recycle()
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Timber.w(t, "poster: sticker editor after restart")
                        null
                    }
                opening = false
                if (s == null) return@launch
                runCatching { StickerTool.valueOf(b.getString("tool").orEmpty()) }.getOrNull()?.let { s.selectTool(it) }
                s.brush = b.getFloat("brush", s.brush)
                s.softness = b.getFloat("soft", s.softness)
                s.method = b.getString("method")?.let { runCatching { StickerMethod.valueOf(it) }.getOrNull() }
                s.tolerance = b.getFloat("tol", s.tolerance)
                s.smooth = b.getFloat("smooth", s.smooth)
                s.changed = b.getBoolean("changed")
                show(s, writePicture = false)
            }
    }

    private companion object {
        /** How long after the last change the mask is written to the session cache (a burst of strokes is written once). */
        const val PERSIST_DELAY_MS = 500L
    }
}
