/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.poster

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.metrolist.music.R
import com.metrolist.music.poster.cutout.CutoutOutcome
import com.metrolist.music.poster.cutout.PosterCutout
import com.metrolist.music.poster.sticker.StickerEditFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import kotlin.math.abs

/** One-shot things the screen does for the editor: a short message, the share sheet. */
sealed interface PosterEvent {
    data class Toast(val text: Int, val arg: Int? = null) : PosterEvent

    data class Share(val intent: Intent) : PosterEvent
}

/**
 * The editor of one poster (survives a screen turn, and the process: see PosterSaved.kt). Owns the [PosterState], the shared photo bitmaps,
 * the undo stack, the cut-out job and the user's sticker library; the screen only draws and forwards touches ([PosterTouch]).
 *
 * The page's view model factory (SavedStateViewModelFactory of the navigation entry) uses the (Application, SavedStateHandle) constructor.
 */
class PosterViewModel
@JvmOverloads
constructor(
    app: Application,
    private val saved: SavedStateHandle = SavedStateHandle(),
    private val lookup: PosterSongLookup = PosterSongLookup.Database,
) : AndroidViewModel(app),
    PosterTouch {
    /** LOADING = the page is being brought back after the process died (or the songs are not handed over yet). */
    enum class Stage { LOADING, PICK, EDIT, EMPTY }

    val state = PosterState()
    val assets = PosterAssets()
    private val undoStack = PosterUndo()
    private val ctx get() = getApplication<Application>()
    private val library = PosterStickerLibrary(app)
    private val events = Channel<PosterEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    var stage by mutableStateOf(Stage.LOADING)
        private set
    var title by mutableStateOf("")
        private set
    var source by mutableStateOf<List<PosterSong>>(emptyList())
        private set
    var picked by mutableStateOf<List<String>>(emptyList())
    var canUndo by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var loadingPhoto by mutableStateOf(false)
        private set
    private var changedSinceSave = false
    private var startSongs: List<PosterSong> = emptyList()
    val libraryItems = mutableStateListOf<StickerLibItem>()
    private var cutJob: Job? = null
    private var photoJob: Job? = null
    private var started = false

    /** Turning and mirroring run one after the other, each on the photo the previous one made (a quick double "Putar" turns twice). */
    private val photoOps = Mutex()

    /** The session's files (the photo and its mask in the cache) are written in order on one lane. */
    private val persistLane = Dispatchers.IO.limitedParallelism(1)
    private var session: String = UUID.randomUUID().toString().replace("-", "").take(16)
    private var files = PosterSessionFiles(app, session)

    /** While a picker page is reloaded from the database: what was saved (handed back unchanged if the system saves again meanwhile). */
    private var pendingRestore: PosterSnapshot? = null

    /** "Ubah stiker" (round 4.3.5): the user's pictures, their background taken off and touched up, into "Stiker saya" and onto the poster. */
    val stickerFlow = StickerEditFlow(app, viewModelScope, library, { files }, persistLane, { toast(it) }, ::onStickerSaved)

    init {
        val snap = PosterSnapshot.from(saved.get<Bundle>(SAVED_KEY))
        saved.setSavedStateProvider(SAVED_KEY) { snapshot().toBundle() }
        saved.setSavedStateProvider(STICKER_KEY) { stickerFlow.saveState() ?: Bundle() }
        if (snap != null) {
            restore(snap)
            // the sticker editor was open: it comes back on its picture and mask from the session cache
            if (stage == Stage.EDIT) stickerFlow.restore(saved.get<Bundle>(STICKER_KEY))
        }
    }

    /** Takes the songs handed over by a page (once). Nothing to do when the page was brought back from its saved state. */
    fun start(request: PosterRequest?) {
        if (started) return
        started = true
        if (request == null || request.songs.isEmpty()) {
            stage = Stage.EMPTY
            return
        }
        title = request.title
        source = request.songs
        picked = PosterPick.firstIds(request.songs).take(if (request.songs.size <= PosterPick.MAX) PosterPick.MAX else 0)
        stage = Stage.PICK
        viewModelScope.launch(persistLane) { runCatching { files.clearOthers() } }
        viewModelScope.launch(Dispatchers.IO) { loadLibrary() }
    }

    fun togglePick(id: String) {
        picked = PosterPick.toggle(picked, id)
    }

    fun pickFirst() {
        picked = PosterPick.firstIds(source)
    }

    fun confirmPick() {
        if (!PosterPick.canContinue(picked)) return
        val chosen = PosterPick.chosen(source, picked)
        state.setSongs(chosen)
        startSongs = chosen
        stage = Stage.EDIT
        loadCovers(chosen)
    }

    private fun loadCovers(songs: List<PosterSong>) {
        for (s in songs) {
            if (state.covers.containsKey(s.id)) continue
            viewModelScope.launch(Dispatchers.IO) {
                val r = PosterCovers.load(ctx, s.coverUrl) ?: return@launch
                withContext(Dispatchers.Main) {
                    state.covers[s.id] = r.first
                    r.second?.let { state.tints[s.id] = it }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ saved state (finding 14)

    /** What the system keeps for the page (called on the main thread when the activity saves its state). */
    internal fun snapshot(): PosterSnapshot {
        pendingRestore?.let { return it }
        val edit = stage == Stage.EDIT
        return PosterSnapshot(
            session = session,
            stage = stage.name,
            title = title,
            sourceIds = if (edit) emptyList() else source.map { it.id },
            picked = picked,
            pickedSongs = if (edit) emptyList() else PosterPick.chosen(source, picked),
            songs = if (edit) state.songs.toList() else emptyList(),
            startSongs = if (edit) startSongs else emptyList(),
            doc = if (edit) state.doc() else null,
            cutout = state.cutout,
            changedSinceSave = changedSinceSave,
            exportFormat = state.exportFormat,
            exportQuality = state.exportQuality,
        )
    }

    private fun restore(snap: PosterSnapshot) {
        started = true
        session = snap.session
        files = PosterSessionFiles(ctx, session)
        title = snap.title
        changedSinceSave = snap.changedSinceSave
        when {
            snap.stage == Stage.EDIT.name && snap.songs.isNotEmpty() -> restoreEditor(snap)
            snap.stage == Stage.PICK.name || snap.stage == Stage.LOADING.name -> restorePicker(snap)
            else -> stage = Stage.EMPTY
        }
    }

    /** The editor comes back at once (the songs are in the saved state in full), so a photo the picker returns after the restart reaches it. */
    private fun restoreEditor(snap: PosterSnapshot) {
        state.setSongs(snap.songs)
        startSongs = snap.startSongs.ifEmpty { snap.songs }
        state.exportFormat = snap.exportFormat
        state.exportQuality = snap.exportQuality
        val doc = snap.doc
        val stickers = doc?.stickers.orEmpty()
        if (doc != null) {
            // the user's own sticker pictures are read from the library below; until then only the drawn stickers are placed
            state.restore(doc.copy(songs = state.songs.toList(), stickers = stickers.filter { it.kind != StickerKind.CUSTOM }))
        }
        stage = Stage.EDIT
        loadCovers(state.songs.toList())
        viewModelScope.launch(Dispatchers.IO) { loadLibrary() }
        val keys = stickers.mapNotNull { if (it.kind == StickerKind.CUSTOM) it.imageKey else null }.distinct()
        if (keys.isNotEmpty()) {
            val placed = state.stickers.toList()
            viewModelScope.launch {
                val images = withContext(Dispatchers.IO) { keys.mapNotNull { k -> library.load(k)?.let { k to it } }.toMap() }
                images.forEach { (k, b) -> state.stickerImages[k] = b }
                // only when nothing was changed meanwhile: then the stickers come back in their order
                if (state.stickers.toList() == placed) {
                    state.stickers.clear()
                    state.stickers.addAll(stickers.filter { it.kind != StickerKind.CUSTOM || images.containsKey(it.imageKey) })
                    state.syncStickerIds()
                }
            }
        }
        restorePhoto(snap.cutout)
    }

    private fun restorePhoto(phase: CutoutPhase) {
        val f = files
        loadingPhoto = true
        photoJob =
            viewModelScope.launch {
                val loaded =
                    try {
                        withContext(Dispatchers.IO) {
                            val bmp = f.readPhoto() ?: return@withContext null
                            val img = PosterPhotoLoad.toImage(bmp)
                            bmp.recycle()
                            PosterPhoto(img, ByteArray(img.w * img.h)) to f.readMask(img.w, img.h)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Timber.w(t, "poster: saved photo")
                        null
                    }
                loadingPhoto = false
                if (loaded == null || state.photo != null) return@launch
                val (photo, mask) = loaded
                state.photo = photo
                val (x, y) = PosterEdit.clampPan(photo.w, photo.h, state.photoZoom, state.photoPanX, state.photoPanY, state.ratio)
                state.photoPanX = x
                state.photoPanY = y
                when {
                    mask != null -> {
                        photo.setMask(mask)
                        state.cutout = CutoutPhase.DONE
                    }
                    phase == CutoutPhase.FAILED -> state.cutout = CutoutPhase.FAILED
                    // the cut-out was running (or its mask was not written yet): run it again
                    else -> startCutout(photo)
                }
            }
    }

    /** A picker page: the source songs are read back from the database by id; the picked ones were saved in full (an online playlist). */
    private fun restorePicker(snap: PosterSnapshot) {
        stage = Stage.LOADING
        picked = snap.picked
        pendingRestore = snap
        viewModelScope.launch {
            val found =
                try {
                    withContext(Dispatchers.IO) { lookup.find(ctx, snap.sourceIds) }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Timber.w(t, "poster: songs from the database")
                    emptyList()
                }
            val byId = HashMap<String, PosterSong>()
            for (s in snap.pickedSongs) byId[s.id] = s
            for (s in found) byId[s.id] = s
            val list = (snap.sourceIds.mapNotNull { byId[it] } + snap.pickedSongs.filter { p -> snap.sourceIds.none { it == p.id } }).distinctBy { it.id }
            pendingRestore = null
            if (list.isEmpty()) {
                stage = Stage.EMPTY
                return@launch
            }
            source = list
            picked = picked.filter { id -> list.any { it.id == id } }
            stage = Stage.PICK
            withContext(Dispatchers.IO) { loadLibrary() }
        }
    }

    // ------------------------------------------------------------------------------------------------ undo
    /** Remembers the state before a change. */
    fun mark() {
        undoStack.mark(state)
        canUndo = undoStack.canUndo
        changedSinceSave = true
    }

    fun undo() {
        undoStack.undo(state)
        canUndo = undoStack.canUndo
        // a card brought back gets its cover (kept on removal; loaded again if it never came: finding 15)
        loadCovers(state.songs.toList())
        pruneStickerImages()
    }

    /**
     * Frees the sticker pictures nothing can show any more: not on the poster, not in a state undo can bring back, not in the library
     * (a deleted library picture stays in memory while undo can still bring its stickers back: finding 16).
     */
    private fun pruneStickerImages() {
        val keep = HashSet<String>()
        state.stickers.mapNotNullTo(keep) { it.imageKey }
        keep.addAll(undoStack.imageKeys())
        libraryItems.mapTo(keep) { it.key }
        state.stickerImages.keys.filter { it !in keep }.forEach { state.stickerImages.remove(it) }
    }

    fun needsLeaveConfirm(): Boolean = PosterExit.needsConfirm(state.photo != null, state.songs.toList() != startSongs, changedSinceSave)

    // ------------------------------------------------------------------------------------------------ the photo
    fun choosePhoto(uri: Uri) {
        cutJob?.cancel()
        // a second pick before the first one is decoded wins (the older one used to be able to land last)
        photoJob?.cancel()
        loadingPhoto = true
        photoJob =
            viewModelScope.launch {
                val loaded =
                    try {
                        withContext(Dispatchers.IO) {
                            val bmp = PosterPhotoLoad.decode(ctx, uri, PosterPhotoLoad.maxSideFor(ctx))
                            val img = PosterPhotoLoad.toImage(bmp)
                            bmp to PosterPhoto(img, ByteArray(img.w * img.h))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: PosterLoadException) {
                        loadingPhoto = false
                        events.send(PosterEvent.Toast(if (e.reason == PosterLoadException.Reason.MEMORY) R.string.poster_cut_memory else R.string.poster_photo_unreadable))
                        return@launch
                    } catch (t: Throwable) {
                        Timber.w(t, "poster: photo")
                        loadingPhoto = false
                        events.send(PosterEvent.Toast(if (t is OutOfMemoryError) R.string.poster_cut_memory else R.string.poster_photo_unreadable))
                        return@launch
                    }
                val (bmp, photo) = loaded
                // the photo goes into the cache now: the picker's read permission ends with the process (finding 14)
                persist { runCatching { files.writePhoto(bmp) }.onFailure { Timber.w(it, "poster: cache photo") }; bmp.recycle() }
                mark()
                state.photo = photo
                state.photoZoom = 1f
                state.photoPanX = 0f
                state.photoPanY = 0f
                loadingPhoto = false
                startCutout(photo)
            }
    }

    /** Runs [block] on the session's file lane (in order, never cancelled half way). */
    private fun persist(block: () -> Unit) {
        CoroutineScope(persistLane).launch { block() }
    }

    private fun startCutout(photo: PosterPhoto) {
        cutJob?.cancel()
        state.cutout = CutoutPhase.RUNNING
        state.cutoutProgress = 0f
        cutJob =
            viewModelScope.launch {
                val outcome =
                    try {
                        withContext(Dispatchers.Default) {
                            PosterCutout.cut(ctx, photo.image.argb, photo.w, photo.h) { p -> state.cutoutProgress = p.coerceIn(0f, 1f) }
                        }
                    } catch (e: CancellationException) {
                        if (state.photo === photo) state.cutout = CutoutPhase.FAILED
                        throw e
                    } catch (t: Throwable) {
                        CutoutOutcome.Failed("error", t)
                    }
                if (state.photo !== photo) return@launch
                when (outcome) {
                    is CutoutOutcome.Done -> {
                        if (outcome.w == photo.w && outcome.h == photo.h) {
                            photo.setMask(outcome.alpha)
                            state.cutout = CutoutPhase.DONE
                            followPhotoLight(photo)
                            val f = files
                            persist { runCatching { f.writeMask(outcome.w, outcome.h, outcome.alpha) } }
                            Timber.i("poster: cut-out ${outcome.millis} ms at ${outcome.inputW}x${outcome.inputH}, ${(outcome.coverage * 100).toInt()} percent")
                        } else {
                            state.cutout = CutoutPhase.FAILED
                            events.send(PosterEvent.Toast(R.string.poster_cut_failed))
                        }
                    }
                    CutoutOutcome.NoSubject -> {
                        state.cutout = CutoutPhase.FAILED
                        events.send(PosterEvent.Toast(R.string.poster_cut_none))
                    }
                    is CutoutOutcome.Failed -> {
                        Timber.w(outcome.error, "poster: cut-out failed (${outcome.reason})")
                        state.cutout = CutoutPhase.FAILED
                        events.send(PosterEvent.Toast(if (outcome.reason == "memory") R.string.poster_cut_memory else R.string.poster_cut_failed))
                    }
                }
            }
    }

    /** The angle the photo's light set last (so a second photo may change it again, but a hand-set "Arah" stays). */
    private var autoAngle: Float? = null

    /**
     * Round 4.3.5: the key light follows the photo's real light. When the person in [photo] is clearly lit from one side, the cards' shadows,
     * the glints and the key light take that side, unless the user set "Arah" by hand.
     */
    private fun followPhotoLight(photo: PosterPhoto) {
        val now = state.shadowAngle
        if (now != POSTER_SHADOW_ANGLE && now != autoAngle) return
        val side = runCatching { PosterLightProbe.side(photo) }.getOrNull()
        val angle = if (side != null) PosterLightProbe.shadowAngle(side) else POSTER_SHADOW_ANGLE
        state.shadowAngle = angle
        autoAngle = angle
    }

    /** Stops the cut-out: the photo stays behind the cards. */
    fun cancelCutout() {
        cutJob?.cancel()
        cutJob = null
        if (state.cutout == CutoutPhase.RUNNING) state.cutout = CutoutPhase.FAILED
    }

    fun removePhoto() {
        mark()
        cutJob?.cancel()
        photoJob?.cancel()
        loadingPhoto = false
        state.photo = null
        state.cutout = CutoutPhase.NONE
        state.photoZoom = 1f
        state.photoPanX = 0f
        state.photoPanY = 0f
        val f = files
        persist { f.deletePhoto() }
    }

    fun setZoom(z: Float) {
        val p = state.photo ?: return
        val zoom = z.coerceIn(PosterEdit.MIN_PHOTO_ZOOM, PosterEdit.MAX_PHOTO_ZOOM)
        state.photoZoom = zoom
        val (x, y) = PosterEdit.clampPan(p.w, p.h, zoom, state.photoPanX, state.photoPanY, state.ratio)
        state.photoPanX = x
        state.photoPanY = y
    }

    fun setPan(x: Float, y: Float) {
        val p = state.photo ?: return
        val (cx, cy) = PosterEdit.clampPan(p.w, p.h, state.photoZoom, x, y, state.ratio)
        state.photoPanX = cx
        state.photoPanY = cy
    }

    fun fillScreen() {
        mark()
        state.photoZoom = 1f
        state.photoPanX = 0f
        state.photoPanY = 0f
    }

    fun fitWhole() {
        val p = state.photo ?: return
        mark()
        state.photoZoom = PosterEdit.fitZoom(p.w, p.h, state.ratio)
        state.photoPanX = 0f
        state.photoPanY = 0f
    }

    fun rotatePhoto() = transformPhoto { PosterPhotoOps.rotateRight(it) }

    fun mirrorPhoto() = transformPhoto { PosterPhotoOps.mirror(it) }

    private fun transformPhoto(op: (PosterPhoto) -> PosterPhoto) {
        viewModelScope.launch {
            photoOps.withLock {
                // the photo as it is NOW (after the turn queued before this one), not as it was at the tap
                val p = state.photo ?: return@withLock
                val running = state.cutout == CutoutPhase.RUNNING
                val q = withContext(Dispatchers.Default) { runCatching { op(p) }.getOrNull() }
                if (q == null) {
                    toast(R.string.poster_cut_memory)
                    return@withLock
                }
                if (state.photo !== p) return@withLock
                changedSinceSave = true
                state.photo = q
                val (x, y) = PosterEdit.clampPan(q.w, q.h, state.photoZoom, if (q.w == p.w) -state.photoPanX else 0f, if (q.w == p.w) state.photoPanY else 0f, state.ratio)
                state.photoPanX = x
                state.photoPanY = y
                // the cached copy follows the turned photo (and its mask, which turned with it)
                val f = files
                val masked = q.hasMask && !running
                persist {
                    runCatching {
                        f.writePhoto(q.image)
                        if (masked) f.writeMask(q.w, q.h, q.alpha)
                    }.onFailure { Timber.w(it, "poster: cache turned photo") }
                }
                // a cut-out still running works on the old pixels: start it again on the new ones
                if (running) startCutout(q)
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ the cards
    fun selectCard(i: Int) {
        state.selected = i
        if (i >= 0) {
            state.selectedSticker = -1
            state.selectedText = -1
        }
    }

    fun updateCard(f: (CardPose) -> CardPose) {
        val i = state.selected
        if (i !in state.poses.indices) return
        state.poses[i] = f(state.poses[i])
    }

    fun resetCard(i: Int) {
        val p = PosterEdit.layoutPose(state, i) ?: return
        mark()
        if (i in state.poses.indices) state.poses[i] = p
    }

    fun applyLayout(l: PosterLayout) {
        mark()
        state.applyLayout(l)
    }

    /** The poster's shape (round 4.3.6): the content moves with it (see [PosterState.changeRatio]). */
    fun setRatio(r: PosterRatio) {
        if (r == state.ratio) return
        mark()
        state.changeRatio(r)
    }

    /** "Susun ulang": the cards back to the ready-made places of the arrangement on this shape (undo brings the hand-made ones back). */
    fun rearrange() {
        mark()
        state.changeRatio(state.ratio, rearrange = true)
    }

    /** "Acak desain": a fresh look in one tap (undo brings the old one back). */
    fun shuffleDesign() {
        mark()
        PosterShuffle.apply(state)
    }

    fun setBackground(b: PosterBackground) {
        if (b == state.background) return
        mark()
        state.background = b
    }

    fun setExportFormat(f: PosterFormat) {
        state.exportFormat = f
    }

    fun setExportQuality(q: Float) {
        state.exportQuality = q.coerceIn(0.5f, 1f)
    }

    // ------------------------------------------------------------------------------------------------ the texts
    fun selectText(i: Int) {
        state.selectedText = i
        if (i >= 0) {
            state.selected = -1
            state.selectedSticker = -1
        }
    }

    /** A new text in the middle of the poster; the colour is the first cover's. Returns false at the limit or for an empty text. */
    fun addText(text: String, style: PosterTextStyle = PosterTextStyle.BERSIH, color: Int = PosterTexts.WHITE): Boolean {
        mark()
        val i = state.addText(text, style, color)
        if (i < 0 && state.texts.size >= PosterTexts.MAX_COUNT) toast(R.string.poster_text_limit, PosterTexts.MAX_COUNT)
        return i >= 0
    }

    fun updateText(f: (PosterText) -> PosterText) {
        val i = state.selectedText
        if (i !in state.texts.indices) return
        state.texts[i] = f(state.texts[i])
    }

    /** The words of the selected text were edited. An empty text is ignored (the old words stay). */
    fun editSelectedText(text: String) {
        val clean = PosterTexts.clean(text) ?: return
        val i = state.selectedText
        if (i !in state.texts.indices || state.texts[i].text == clean) return
        mark()
        state.texts[i] = state.texts[i].copy(text = clean)
    }

    fun duplicateText() {
        mark()
        if (state.duplicateText(state.selectedText) < 0 && state.texts.size >= PosterTexts.MAX_COUNT) toast(R.string.poster_text_limit, PosterTexts.MAX_COUNT)
    }

    fun deleteText() {
        mark()
        state.removeText(state.selectedText)
    }

    fun moveSong(i: Int, delta: Int) {
        mark()
        PosterEdit.moveSong(state, i, delta)
    }

    fun removeSong(i: Int) {
        mark()
        PosterEdit.removeSong(state, i)
    }

    // ------------------------------------------------------------------------------------------------ the stickers
    fun addSticker(kind: StickerKind) {
        mark()
        if (state.addSticker(kind) < 0) toast(R.string.poster_sticker_limit, PosterStickers.MAX_COUNT)
    }

    /**
     * The user's own pictures from the photo picker: each opens in the sticker editor in turn ("Hapus latar" runs at once), and "Selesai" puts it
     * into the library (PNG, at most 1024 px, its original kept for a later "Ubah") and onto the poster.
     */
    fun importStickers(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (state.stickers.size >= PosterStickers.MAX_COUNT) {
            toast(R.string.poster_sticker_limit, PosterStickers.MAX_COUNT)
            return
        }
        stickerFlow.open(uris)
    }

    /** "Ubah" on a sticker of the library (or on the poster): the editor opens on the picture it was made from. */
    fun editLibrarySticker(key: String) = stickerFlow.openLibrary(key)

    /** A sticker came out of the editor: into the library list, and (a new one) onto the poster; an edited one changes on the poster at once. */
    private fun onStickerSaved(key: String, bmp: Bitmap, thumb: Bitmap?, isNew: Boolean) {
        state.stickerImages[key] = bmp
        libraryItems.removeAll { it.key == key }
        thumb?.let { libraryItems.add(0, StickerLibItem(key, it)) }
        while (libraryItems.size > PosterStickerLibrary.MAX) libraryItems.removeAt(libraryItems.lastIndex)
        changedSinceSave = true
        if (isNew) {
            mark()
            if (state.addSticker(StickerKind.CUSTOM, key) < 0) toast(R.string.poster_sticker_limit, PosterStickers.MAX_COUNT)
        }
    }

    fun placeLibrarySticker(key: String) {
        viewModelScope.launch {
            val bmp = state.stickerImages[key] ?: withContext(Dispatchers.IO) { library.load(key) }
            if (bmp == null) {
                toast(R.string.poster_sticker_unreadable)
                return@launch
            }
            state.stickerImages[key] = bmp
            mark()
            if (state.addSticker(StickerKind.CUSTOM, key) < 0) toast(R.string.poster_sticker_limit, PosterStickers.MAX_COUNT)
        }
    }

    /**
     * "Hapus dari stiker saya": the file goes, the stickers with that picture leave the poster. The bitmap stays in memory while "Urungkan"
     * can still bring those stickers back (it used to be dropped at once, so undo brought back invisible stickers: finding 16).
     */
    fun removeFromLibrary(key: String) {
        mark()
        PosterEdit.removeStickersWithKey(state, key)
        libraryItems.removeAll { it.key == key }
        pruneStickerImages()
        viewModelScope.launch(Dispatchers.IO) { library.remove(key) }
    }

    private suspend fun loadLibrary() {
        val items = library.list().mapNotNull { k -> library.thumb(k)?.let { StickerLibItem(k, it) } }
        withContext(Dispatchers.Main) {
            libraryItems.clear()
            libraryItems.addAll(items)
        }
    }

    fun updateSticker(f: (PosterSticker) -> PosterSticker) {
        val i = state.selectedSticker
        if (i !in state.stickers.indices) return
        state.stickers[i] = f(state.stickers[i])
    }

    fun duplicateSticker() {
        mark()
        if (state.duplicateSticker(state.selectedSticker) < 0 && state.stickers.size >= PosterStickers.MAX_COUNT) toast(R.string.poster_sticker_limit, PosterStickers.MAX_COUNT)
    }

    fun deleteSticker() {
        mark()
        state.removeSticker(state.selectedSticker)
    }

    // ------------------------------------------------------------------------------------------------ touches on the picture
    override fun hit(x: Float, y: Float): PosterTouchTarget {
        val aspects = state.stickerAspects()
        val st = state.selectedText
        if (st in state.texts.indices && PosterTexts.onHandle(state.texts[st], x, y)) return PosterTouchTarget.TextHandle(st)
        val ss = state.selectedSticker
        if (ss in state.stickers.indices && PosterEdit.onStickerHandle(state.stickers[ss], x, y, state.stickerAspect(state.stickers[ss]))) return PosterTouchTarget.StickerHandle(ss)
        val t = PosterTexts.hit(state.texts.toList(), x, y, slop = 10f)
        if (t >= 0) return PosterTouchTarget.Text(t)
        val s = PosterGeometry.hitSticker(state.stickers.toList(), x, y, aspects, slop = 12f)
        if (s >= 0) return PosterTouchTarget.Sticker(s)
        val c = PosterGeometry.hitCard(state.poses.toList(), x, y, PosterGeometry.cameraFor(state.cardPerspective))
        if (c >= 0) return PosterTouchTarget.Card(c)
        return if (state.photo != null) PosterTouchTarget.Photo else PosterTouchTarget.None
    }

    override fun begin(target: PosterTouchTarget) {
        if (target == PosterTouchTarget.None) return
        mark()
        when (target) {
            is PosterTouchTarget.Card -> selectCard(target.index)
            is PosterTouchTarget.Sticker -> {
                state.selectedSticker = target.index
                state.selected = -1
                state.selectedText = -1
            }
            is PosterTouchTarget.Text -> selectText(target.index)
            else -> Unit
        }
    }

    override fun move(target: PosterTouchTarget, x: Float, y: Float, cx: Float, cy: Float, dx: Float, dy: Float, zoom: Float, turn: Float) {
        when (target) {
            is PosterTouchTarget.Card ->
                if (target.index in state.poses.indices) state.poses[target.index] = PosterEdit.pinchedCard(state.poses[target.index], dx, dy, zoom, turn, state.ratio)
            is PosterTouchTarget.Sticker ->
                if (target.index in state.stickers.indices) state.stickers[target.index] = PosterEdit.pinchedSticker(state.stickers[target.index], dx, dy, zoom, turn, state.ratio)
            is PosterTouchTarget.Text ->
                if (target.index in state.texts.indices) state.texts[target.index] = PosterTexts.pinched(state.texts[target.index], dx, dy, zoom, turn, state.ratio)
            is PosterTouchTarget.TextHandle ->
                if (target.index in state.texts.indices) state.texts[target.index] = PosterTexts.draggedHandle(state.texts[target.index], x, y)
            is PosterTouchTarget.StickerHandle ->
                if (target.index in state.stickers.indices) {
                    val s = state.stickers[target.index]
                    state.stickers[target.index] = PosterEdit.draggedHandle(s, x, y, state.stickerAspect(s))
                }
            PosterTouchTarget.Photo -> {
                val p = state.photo ?: return
                val (z, px, py) = PosterEdit.pinchedPhoto(p.w, p.h, state.photoZoom, state.photoPanX, state.photoPanY, cx, cy, zoom, dx, dy, state.ratio)
                if (abs(z - state.photoZoom) > 1e-4f) state.photoZoom = z
                state.photoPanX = px
                state.photoPanY = py
            }
            PosterTouchTarget.None -> Unit
        }
    }

    override fun end(target: PosterTouchTarget) = Unit

    override fun tap(target: PosterTouchTarget) {
        when (target) {
            is PosterTouchTarget.Card -> selectCard(if (state.selected == target.index) -1 else target.index)
            is PosterTouchTarget.Sticker -> {
                state.selectedSticker = if (state.selectedSticker == target.index) -1 else target.index
                state.selected = -1
                state.selectedText = -1
            }
            is PosterTouchTarget.Text -> selectText(if (state.selectedText == target.index) -1 else target.index)
            is PosterTouchTarget.StickerHandle, is PosterTouchTarget.TextHandle -> Unit
            else -> {
                state.selected = -1
                state.selectedSticker = -1
                state.selectedText = -1
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ save and share
    /** The frame is taken on the main thread (the editor keeps working while the PNG is painted), then painted on a worker. */
    private suspend fun exportBitmap(): Bitmap {
        val frame = state.frame()
        return withContext(Dispatchers.Default) { renderPosterBitmap(ctx, frame, assets) }
    }

    fun save() {
        if (saving) return
        saving = true
        viewModelScope.launch {
            try {
                val format = state.exportFormat
                val quality = (state.exportQuality * 100f).toInt()
                val bmp = exportBitmap()
                withContext(Dispatchers.IO) { PosterSave.saveToGallery(ctx, bmp, format, quality) }
                bmp.recycle()
                changedSinceSave = false
                toast(R.string.poster_saved)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.w(t, "poster: save")
                toast(R.string.poster_save_failed)
            } finally {
                saving = false
            }
        }
    }

    fun share() {
        if (saving) return
        saving = true
        viewModelScope.launch {
            try {
                val format = state.exportFormat
                val quality = (state.exportQuality * 100f).toInt()
                val bmp = exportBitmap()
                val intent = withContext(Dispatchers.IO) { PosterSave.shareIntent(ctx, bmp, format, quality) }
                bmp.recycle()
                events.send(PosterEvent.Share(intent))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.w(t, "poster: share")
                toast(R.string.poster_save_failed)
            } finally {
                saving = false
            }
        }
    }

    private fun toast(res: Int, arg: Int? = null) {
        events.trySend(PosterEvent.Toast(res, arg))
    }

    override fun onCleared() {
        cutJob?.cancel()
        photoJob?.cancel()
        stickerFlow.clear()
        assets.clear()
        // the user left the poster: its cached photo is not needed any more (after a process death this is not called, so it stays)
        val f = files
        persist { runCatching { f.delete() } }
        super.onCleared()
    }

    companion object {
        const val SAVED_KEY = "auralys.poster"
        const val STICKER_KEY = "auralys.poster.sticker"
    }
}
