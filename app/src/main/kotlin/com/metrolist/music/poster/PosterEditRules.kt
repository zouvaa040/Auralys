/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.poster

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The pure rules of the editor (ported from the Windows PosterEdit / PosterStyleEdit / PosterFxEdit / PosterPick), plus the touch rules of the phone
 * (pinch = size and turn) and a cheap undo. No Android views here, so the JVM tests run them.
 */
object PosterEdit {
    const val MIN_CARD_SCALE = 0.3f
    const val MAX_CARD_SCALE = 1.8f
    /** 0.3 (Windows: 0.5) so "Muat penuh" fits a whole landscape phone photo (4:3 needs 0.42, 16:9 needs 0.32) on the tall poster. */
    const val MIN_PHOTO_ZOOM = 0.3f
    const val MAX_PHOTO_ZOOM = 3f
    private const val CARD_OVERSHOOT = 120f

    fun moved(p: CardPose, dx: Float, dy: Float, ratio: PosterRatio = PosterRatio.DEFAULT): CardPose =
        p.copy(x = (p.x + dx).coerceIn(-CARD_OVERSHOOT, ratio.w + CARD_OVERSHOOT), y = (p.y + dy).coerceIn(-CARD_OVERSHOOT, ratio.h + CARD_OVERSHOOT))

    /** A pinch on a card: moved by the fingers' pan, sized by their spread, turned by their turn. */
    fun pinchedCard(p: CardPose, dx: Float, dy: Float, zoom: Float, turnDeg: Float, ratio: PosterRatio = PosterRatio.DEFAULT): CardPose {
        val m = moved(p, dx, dy, ratio)
        return m.copy(scale = (m.scale * zoom).coerceIn(MIN_CARD_SCALE, MAX_CARD_SCALE), rotation = clampCardRotation(m.rotation + turnDeg))
    }

    /** The card's own turn is kept to the slider's range. */
    fun clampCardRotation(deg: Float): Float = normalizeDegrees(deg).coerceIn(-45f, 45f)

    fun panLimit(photoW: Int, photoH: Int, zoom: Float, ratio: PosterRatio = PosterRatio.DEFAULT): Pair<Float, Float> {
        val f = PosterPhotoGeometry.scale(photoW, photoH, zoom, ratio)
        return abs(photoW * f - ratio.w) / 2f to abs(photoH * f - ratio.h) / 2f
    }

    fun clampPan(photoW: Int, photoH: Int, zoom: Float, panX: Float, panY: Float, ratio: PosterRatio = PosterRatio.DEFAULT): Pair<Float, Float> {
        val (mx, my) = panLimit(photoW, photoH, zoom, ratio)
        return panX.coerceIn(-mx, mx) to panY.coerceIn(-my, my)
    }

    /** The zoom at which the WHOLE photo is inside the poster ("Muat penuh"). */
    fun fitZoom(photoW: Int, photoH: Int, ratio: PosterRatio = PosterRatio.DEFAULT): Float {
        val a = ratio.w.toFloat() / photoW.coerceAtLeast(1)
        val b = ratio.h.toFloat() / photoH.coerceAtLeast(1)
        return (minOf(a, b) / maxOf(a, b)).coerceIn(MIN_PHOTO_ZOOM, MAX_PHOTO_ZOOM)
    }

    /** The photo zoomed by [factor] with the poster point ([cx], [cy]) kept in place, then panned by ([dx], [dy]). Returns (zoom, panX, panY). */
    fun pinchedPhoto(photoW: Int, photoH: Int, zoom: Float, panX: Float, panY: Float, cx: Float, cy: Float, factor: Float, dx: Float, dy: Float, ratio: PosterRatio = PosterRatio.DEFAULT): Triple<Float, Float, Float> {
        val z = (zoom * factor).coerceIn(MIN_PHOTO_ZOOM, MAX_PHOTO_ZOOM)
        val k = z / zoom
        val nx = (cx - ratio.w / 2f) * (1f - k) + panX * k + dx
        val ny = (cy - ratio.h / 2f) * (1f - k) + panY * k + dy
        val (px, py) = clampPan(photoW, photoH, z, nx, ny, ratio)
        return Triple(z, px, py)
    }

    // ---- stickers
    /** How close to a sticker's corner handle a touch counts as on it, in poster pixels (a finger is wider than a mouse). */
    const val HANDLE_RADIUS = 64f

    fun normalizeDegrees(degrees: Float): Float {
        var d = degrees % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    fun onStickerHandle(s: PosterSticker, x: Float, y: Float, aspect: Float = 1f): Boolean {
        val (hx, hy) = PosterGeometry.stickerHandle(s, aspect)
        return hypot(x - hx, y - hy) <= HANDLE_RADIUS
    }

    fun movedSticker(s: PosterSticker, dx: Float, dy: Float, ratio: PosterRatio = PosterRatio.DEFAULT): PosterSticker = s.copy(x = (s.x + dx).coerceIn(0f, ratio.w.toFloat()), y = (s.y + dy).coerceIn(0f, ratio.h.toFloat()))

    fun pinchedSticker(s: PosterSticker, dx: Float, dy: Float, zoom: Float, turnDeg: Float, ratio: PosterRatio = PosterRatio.DEFAULT): PosterSticker {
        val m = movedSticker(s, dx, dy, ratio)
        return m.copy(scale = (m.scale * zoom).coerceIn(PosterStickers.MIN_SCALE, PosterStickers.MAX_SCALE), rotation = normalizeDegrees(m.rotation + turnDeg))
    }

    /** The sticker with its corner handle dragged to ([x], [y]): the size follows the distance from the centre and the turn the direction. */
    fun draggedHandle(s: PosterSticker, x: Float, y: Float, aspect: Float = 1f): PosterSticker {
        val a = aspect.coerceIn(0.02f, 50f)
        val unitW = if (a >= 1f) 1f else a
        val unitH = if (a >= 1f) 1f / a else 1f
        val d = hypot(x - s.x, y - s.y)
        val scale = (d / (PosterStickers.BASE / 2f * hypot(unitW, unitH))).coerceIn(PosterStickers.MIN_SCALE, PosterStickers.MAX_SCALE)
        val corner = Math.toDegrees(atan2(unitH.toDouble(), unitW.toDouble())).toFloat()
        val angle = Math.toDegrees(atan2((y - s.y).toDouble(), (x - s.x).toDouble())).toFloat() - corner
        // a mirrored sticker's handle is still its box corner (half width, half height) in the turned frame, so the same angle holds
        return s.copy(scale = scale, rotation = normalizeDegrees(angle))
    }

    fun removeStickersWithKey(state: PosterState, key: String) {
        var any = false
        for (i in state.stickers.indices.reversed()) {
            if (state.stickers[i].imageKey == key) {
                state.stickers.removeAt(i)
                any = true
            }
        }
        if (any) state.selectedSticker = -1
    }

    // ---- the songs
    fun moveSong(state: PosterState, i: Int, delta: Int) {
        val j = i + delta
        if (i !in state.songs.indices || j !in state.songs.indices) return
        val a = state.songs[i]
        state.songs[i] = state.songs[j]
        state.songs[j] = a
        val sel = state.selected
        if (sel == i) state.selected = j else if (sel == j) state.selected = i
    }

    /**
     * Removes song [i] and its card; at least one stays. Untouched cards move to the ready-made places for the smaller number. The cover and
     * its tint stay in [PosterState.covers] / [PosterState.tints] (a few hundred KB at most): "Urungkan" brings the card back with them, not
     * with the placeholder tile (review 2026-11-12, finding 15).
     */
    fun removeSong(state: PosterState, i: Int): Boolean {
        if (state.songs.size <= 1 || i !in state.songs.indices) return false
        val untouched = state.poses.toList() == PosterLayouts.poses(state.layout, state.songs.size, state.ratio)
        state.songs.removeAt(i)
        if (i in state.poses.indices) state.poses.removeAt(i)
        val sel = state.selected
        state.selected = if (sel == i) -1 else if (sel > i) sel - 1 else sel
        if (untouched) state.applyLayout(state.layout)
        return true
    }

    fun layoutPose(state: PosterState, i: Int): CardPose? = PosterLayouts.poses(state.layout, state.songs.size, state.ratio).getOrNull(i)
}

/** The sliders of "Gaya kartu" (order of the screen). */
enum class CardSlider { PERSPEKTIF, KETEBALAN, KACA_BURAM, KEKERUHAN, SUDUT, PANTULAN, KEDALAMAN }

enum class ShadowSlider { KEKUATAN, KABUR, JARAK }

object PosterStyleEdit {
    fun value(state: PosterState, s: CardSlider): Float =
        when (s) {
            CardSlider.PERSPEKTIF -> state.cardPerspective
            CardSlider.KETEBALAN -> state.cardThickness
            CardSlider.KACA_BURAM -> state.cardFrost
            CardSlider.KEKERUHAN -> state.cardOpacity
            CardSlider.SUDUT -> state.cardCorner
            CardSlider.PANTULAN -> state.cardGloss
            CardSlider.KEDALAMAN -> state.cardDof
        }

    fun setSlider(state: PosterState, s: CardSlider, v: Float) {
        val x = v.coerceIn(0f, 1f)
        when (s) {
            CardSlider.PERSPEKTIF -> state.cardPerspective = x
            CardSlider.KETEBALAN -> state.cardThickness = x
            CardSlider.KACA_BURAM -> state.cardFrost = x
            CardSlider.KEKERUHAN -> state.cardOpacity = x
            CardSlider.SUDUT -> state.cardCorner = x
            CardSlider.PANTULAN -> state.cardGloss = x
            CardSlider.KEDALAMAN -> state.cardDof = x
        }
        if (s == CardSlider.KACA_BURAM || s == CardSlider.KEKERUHAN || s == CardSlider.PANTULAN) state.materialCustom = true
    }

    fun value(state: PosterState, s: ShadowSlider): Float =
        when (s) {
            ShadowSlider.KEKUATAN -> state.shadowStrength
            ShadowSlider.KABUR -> state.shadowBlur
            ShadowSlider.JARAK -> state.shadowDistance
        }

    fun setSlider(state: PosterState, s: ShadowSlider, v: Float) {
        val x = v.coerceIn(0f, 1f)
        when (s) {
            ShadowSlider.KEKUATAN -> state.shadowStrength = x
            ShadowSlider.KABUR -> state.shadowBlur = x
            ShadowSlider.JARAK -> state.shadowDistance = x
        }
    }

    fun setShadowAngle(state: PosterState, degrees: Float) {
        var d = degrees % 360f
        if (d < 0f) d += 360f
        state.shadowAngle = d
    }

    fun setMaterial(state: PosterState, m: CardMaterial) {
        state.applyMaterial(m)
        state.materialCustom = false
    }

    fun isLit(state: PosterState, m: CardMaterial): Boolean = !state.materialCustom && state.material == m

    /** "Atur ulang gaya kartu". */
    fun reset(state: PosterState) {
        val d = PosterState()
        setMaterial(state, d.material)
        state.cardPerspective = d.cardPerspective
        state.cardThickness = d.cardThickness
        state.cardCorner = d.cardCorner
        state.cardDof = d.cardDof
        state.shadowStrength = d.shadowStrength
        state.shadowBlur = d.shadowBlur
        state.shadowDistance = d.shadowDistance
        state.shadowAngle = d.shadowAngle
        state.shadowTone = d.shadowTone
    }
}

/** The six effect sliders (order of the screen): Cahaya utama, Pendar, Kilau, Garis lensa, Butiran film, Tepi kartu. */
enum class FxSlider { LEAK, BLOOM, SPARKLE, STREAK, GRAIN, RIM }

object PosterFxEdit {
    fun value(state: PosterState, s: FxSlider): Float =
        when (s) {
            FxSlider.LEAK -> state.fxLeak
            FxSlider.STREAK -> state.fxStreak
            FxSlider.SPARKLE -> state.fxSparkle
            FxSlider.BLOOM -> state.fxBloom
            FxSlider.GRAIN -> state.fxGrain
            FxSlider.RIM -> state.fxRim
        }

    fun setSlider(state: PosterState, s: FxSlider, v: Float) {
        val x = v.coerceIn(0f, 1f)
        when (s) {
            FxSlider.LEAK -> state.fxLeak = x
            FxSlider.STREAK -> state.fxStreak = x
            FxSlider.SPARKLE -> state.fxSparkle = x
            FxSlider.BLOOM -> state.fxBloom = x
            FxSlider.GRAIN -> state.fxGrain = x
            FxSlider.RIM -> state.fxRim = x
        }
        state.preset = null
    }

    fun setGrade(state: PosterState, g: PosterGrade) {
        state.grade = g
        state.preset = null
    }

    fun setPreset(state: PosterState, p: PosterFxPreset) = state.applyPreset(p)
}

/**
 * "Acak desain" (round 4.3.6): a fresh look in one tap. Another arrangement of the cards, another glass, effect look, perspective, corner and shadow, and
 * (when it cannot hide the user's photo) another background. The songs, the photo, the stickers, the texts and the shape are never touched.
 */
object PosterShuffle {
    fun apply(state: PosterState, rnd: kotlin.random.Random = kotlin.random.Random.Default) {
        val layouts = PosterLayout.entries.filter { it != state.layout }
        state.applyLayout(layouts[rnd.nextInt(layouts.size)])
        PosterStyleEdit.setMaterial(state, CardMaterial.entries[rnd.nextInt(CardMaterial.entries.size)])
        PosterFxEdit.setPreset(state, PosterFxPreset.entries[rnd.nextInt(PosterFxPreset.entries.size)])
        state.cardPerspective = 0.3f + 0.45f * rnd.nextFloat()
        state.cardCorner = 0.2f + 0.7f * rnd.nextFloat()
        state.shadowTone = ShadowTone.entries[rnd.nextInt(ShadowTone.entries.size)]
        state.glow = 0.2f + 0.4f * rnd.nextFloat()
        state.fxVignette = 0.35f + 0.4f * rnd.nextFloat()
        // a photo with no cut-out would be hidden by a styled background and nothing would stand in front of it: keep the photo then
        val photoOnly = state.photo != null && state.cutout != CutoutPhase.DONE
        state.background = if (photoOnly) PosterBackground.ASLI else PosterBackground.entries[rnd.nextInt(PosterBackground.entries.size)]
        state.selected = -1
    }
}

/** Choosing up to five songs (plain functions; the picker screen only draws them). Selections are song ids in the order they were made. */
object PosterPick {
    const val MAX = POSTER_MAX_SONGS

    fun toggle(selected: List<String>, id: String, max: Int = MAX): List<String> =
        when {
            id in selected -> selected - id
            selected.size >= max -> selected
            else -> selected + id
        }

    fun isDisabled(selected: List<String>, id: String, max: Int = MAX): Boolean = id !in selected && selected.size >= max

    fun firstIds(songs: List<PosterSong>, max: Int = MAX): List<String> = songs.distinctBy { it.id }.take(max).map { it.id }

    /** The chosen songs in the order of the list they came from (so the cards follow the playlist order). */
    fun chosen(songs: List<PosterSong>, selected: List<String>): List<PosterSong> = songs.distinctBy { it.id }.filter { it.id in selected }

    fun canContinue(selected: List<String>): Boolean = selected.isNotEmpty()
}

/** When leaving the poster needs a question first: something would be lost (a photo or other songs) and it changed since the last save. */
object PosterExit {
    fun needsConfirm(hasPhoto: Boolean, songsChanged: Boolean, changedSinceSave: Boolean): Boolean = changedSinceSave && (hasPhoto || songsChanged)
}

/** Everything the user edits that undo can bring back (not the photo pixels: turning or replacing the photo is not undone). */
data class PosterDoc(
    val songs: List<PosterSong>,
    val poses: List<CardPose>,
    val layout: PosterLayout,
    val stickers: List<PosterSticker>,
    val glow: Float,
    val glowTint: Color?,
    val dim: Float,
    val blur: Float,
    val photoZoom: Float,
    val photoPanX: Float,
    val photoPanY: Float,
    val material: CardMaterial,
    val materialCustom: Boolean,
    val card: FloatArray,
    val shadowTone: ShadowTone,
    val grade: PosterGrade,
    val fx: FloatArray,
    val preset: PosterFxPreset?,
    val ratio: PosterRatio = PosterRatio.DEFAULT,
    val texts: List<PosterText> = emptyList(),
    val background: PosterBackground = PosterBackground.ASLI,
    val vignette: Float = PosterFxDefaults.VIGNETTE,
) {
    override fun equals(other: Any?): Boolean {
        if (other !is PosterDoc) return false
        return songs == other.songs && poses == other.poses && layout == other.layout && stickers == other.stickers && glow == other.glow && glowTint == other.glowTint &&
            dim == other.dim && blur == other.blur && photoZoom == other.photoZoom && photoPanX == other.photoPanX && photoPanY == other.photoPanY &&
            material == other.material && materialCustom == other.materialCustom && card.contentEquals(other.card) && shadowTone == other.shadowTone &&
            grade == other.grade && fx.contentEquals(other.fx) && preset == other.preset && ratio == other.ratio && texts == other.texts && background == other.background &&
            vignette == other.vignette
    }

    override fun hashCode(): Int = poses.hashCode() * 31 + stickers.hashCode()
}

fun PosterState.doc(): PosterDoc =
    PosterDoc(
        songs.toList(), poses.toList(), layout, stickers.toList(), glow, glowTint, dim, blur, photoZoom, photoPanX, photoPanY, material, materialCustom,
        floatArrayOf(cardPerspective, cardThickness, cardFrost, cardOpacity, cardCorner, cardGloss, cardDof, shadowStrength, shadowBlur, shadowDistance, shadowAngle),
        shadowTone, grade, floatArrayOf(fxBloom, fxLeak, fxStreak, fxSparkle, fxGrain, fxRim), preset,
        ratio, texts.toList(), background, fxVignette,
    )

fun PosterState.restore(d: PosterDoc) {
    if (songs.toList() != d.songs) {
        songs.clear()
        songs.addAll(d.songs)
    }
    poses.clear()
    poses.addAll(d.poses)
    layout = d.layout
    stickers.clear()
    // a picture of the user's that is gone (its library entry was deleted and the bitmap freed) would come back as an invisible sticker
    // that still takes touches and counts toward the limit: leave those out (review 2026-11-12, finding 16)
    stickers.addAll(d.stickers.filter { it.kind != StickerKind.CUSTOM || (it.imageKey != null && stickerImages.containsKey(it.imageKey)) })
    syncStickerIds()
    ratio = d.ratio
    texts.clear()
    texts.addAll(d.texts)
    syncTextIds()
    background = d.background
    fxVignette = d.vignette
    if (selected !in poses.indices) selected = -1
    if (selectedSticker !in stickers.indices) selectedSticker = -1
    if (selectedText !in texts.indices) selectedText = -1
    glow = d.glow
    glowTint = d.glowTint
    dim = d.dim
    blur = d.blur
    photoZoom = d.photoZoom
    photoPanX = d.photoPanX
    photoPanY = d.photoPanY
    material = d.material
    materialCustom = d.materialCustom
    cardPerspective = d.card[0]; cardThickness = d.card[1]; cardFrost = d.card[2]; cardOpacity = d.card[3]; cardCorner = d.card[4]; cardGloss = d.card[5]
    cardDof = d.card[6]; shadowStrength = d.card[7]; shadowBlur = d.card[8]; shadowDistance = d.card[9]; shadowAngle = d.card[10]
    shadowTone = d.shadowTone
    grade = d.grade
    fxBloom = d.fx[0]; fxLeak = d.fx[1]; fxStreak = d.fx[2]; fxSparkle = d.fx[3]; fxGrain = d.fx[4]; fxRim = d.fx[5]
    preset = d.preset
}

/** A bounded stack of [PosterDoc]s: [mark] before a change (a gesture, a slider drag, a chip), [undo] steps back. Docs are small (no pixels). */
class PosterUndo(private val max: Int = 40) {
    private val stack = ArrayDeque<PosterDoc>()

    val canUndo: Boolean get() = stack.isNotEmpty()
    val size: Int get() = stack.size

    /** Remembers the state as it is before a change; an unchanged state is not stored twice. */
    fun mark(state: PosterState) {
        val d = state.doc()
        if (stack.lastOrNull() == d) return
        stack.addLast(d)
        while (stack.size > max) stack.removeFirst()
    }

    /** Brings back the last remembered state that differs from now. Returns false when there is none. */
    fun undo(state: PosterState): Boolean {
        val now = state.doc()
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            if (d != now) {
                state.restore(d)
                return true
            }
        }
        return false
    }

    fun clear() = stack.clear()

    /** The keys of the user's pictures that a remembered state still shows (their bitmaps must stay while undo can bring them back). */
    fun imageKeys(): Set<String> {
        val out = HashSet<String>()
        for (d in stack) for (s in d.stickers) if (s.kind == StickerKind.CUSTOM) s.imageKey?.let { out.add(it) }
        return out
    }
}
