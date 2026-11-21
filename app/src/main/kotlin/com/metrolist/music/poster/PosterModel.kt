/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.poster

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

// ====================================================================================================================
// "Poster lagu" (song poster), ported from the Windows app (Desktop/app/.../poster, 1.9.3): up to five songs as tilted 3D glass
// now-playing cards, the user's photo with the person cut out in front of them, cinematic light, stickers, and the Auralys mark
// (mandatory, always drawn last). Everything the editor shows and the export draws is in [PosterState]; ONE renderer
// ([PosterRenderer]) paints the preview and the 1080 x 1920 PNG, so what is edited is what is saved.
//
// Package: PosterModel (this file, the contract), PosterGeometry (pure maths), PosterEditRules (editor rules, undo), PosterRenderer +
// PosterCardPaint + PosterFx + PosterStickerArt + PosterPixels (the picture), PosterCanvas (the composable), PosterPhotoLoad,
// PosterCovers, PosterExport, PosterScreen + PosterPicker (UI), PosterEntry (hooks for the playlist pages), cutout/ (the network).

const val POSTER_W = 1080
const val POSTER_H = 1920
const val POSTER_MAX_SONGS = 5

/** The key light's default: the cards' shadows fall down and a little to the left (degrees, "Arah"). A photo's own light can change it (PosterLightProbe). */
const val POSTER_SHADOW_ANGLE = 115f

/** What a card shows. Plain values, so the picture never depends on the player or the database. */
data class PosterSong(val id: String, val title: String, val artist: String, val coverUrl: String?, val durationSec: Int)

/** NONE = no photo, RUNNING = the network is cutting, DONE = the mask is in, FAILED = it could not be cut (the photo stays as the background). */
enum class CutoutPhase { NONE, RUNNING, DONE, FAILED }

/** Ready-made glass for the cards: each one sets [PosterState.cardFrost], [PosterState.cardOpacity] and [PosterState.cardGloss]. */
enum class CardMaterial { ASAP, BENING, SUSU, GELAP }

/** The colour of the cards' shadow: neutral black, warm brown, or taken from the cover. */
enum class ShadowTone { NETRAL, HANGAT, SAMPUL }

/** The colour grade laid over the whole picture. */
enum class PosterGrade { ASLI, HANGAT, DINGIN, NEON, FILM }

/** Ready-made looks: each one sets the grade and every effect slider (see [PosterState.applyPreset]). BERSIH (the default) = a clean editorial poster. */
enum class PosterFxPreset { BERSIH, SINEMA, FILM }

/** The ready-made arrangements of the cards (KOLASE = a grid, BESAR = one large card and the others small: round 4.3.6). */
enum class PosterLayout { ORBIT, TUMPUK, DERET, KOLOM, KOLASE, BESAR }

/**
 * What lies behind the cards (round 4.3.6). ASLI = the photo, or the studio backdrop without one (how it always was); the others replace
 * it: the first cover blurred, a gradient of the covers' colours, one deep tone. A cut-out person stands in front of any of them.
 */
enum class PosterBackground { ASLI, KABUR, GRADIEN, POLOS }

/** The file the poster is saved as. */
enum class PosterFormat { PNG, JPEG }

/** Built-in stickers (a bird, a glint) and the user's own pictures ([CUSTOM], drawn from [PosterState.stickerImages]). */
enum class StickerKind { BURUNG, KILAU, CUSTOM }

/**
 * One sticker: [x], [y] = its centre in poster pixels, [scale] 1 = [PosterStickers.BASE] pixels on the long side, [rotation] in degrees clockwise,
 * [flip] mirrors it left-right. [id] is unique within a poster.
 */
data class PosterSticker(val id: Int, val kind: StickerKind, val x: Float, val y: Float, val scale: Float = 1f, val rotation: Float = 0f, val flip: Boolean = false, val imageKey: String? = null)

/** "Kilau" in each ready-made look (round 4.3.5 K: on by default, clearly visible, never off in a preset). */
object PosterFxDefaults {
    /** "Vinyet" at 0.5 = the vignette the poster always had (PosterField.VIGNETTE). */
    const val VIGNETTE = 0.5f
    const val SPARKLE_BERSIH = 0.45f
    const val SPARKLE_SINEMA = 0.7f
    const val SPARKLE_FILM = 0.4f
}

object PosterStickers {
    const val BASE = 240f
    const val MIN_SCALE = 0.25f
    const val MAX_SCALE = 4f
    const val MAX_COUNT = 30
}

/**
 * Where one card sits on the 1080 x 1920 poster: [x], [y] = the centre in poster pixels, [scale] 1 = [PosterCards.BASE_W] wide, [rotation] = the turn in the
 * picture's plane (degrees), [tiltX] / [tiltY] = the 3D tilt (degrees, around the horizontal / vertical axis), [lift] = how high it floats (shadow).
 */
data class CardPose(val x: Float, val y: Float, val scale: Float = 1f, val rotation: Float = 0f, val tiltX: Float = 0f, val tiltY: Float = 0f, val lift: Float = 0.5f)

object PosterCards {
    const val BASE_W = 440f
    const val BASE_H = 660f
}

object PosterLayouts {
    fun poses(layout: PosterLayout, count: Int, ratio: PosterRatio = PosterRatio.DEFAULT): List<CardPose> = PosterLayoutMath.poses(layout, count, ratio)
}

/** A decoded photo: opaque ARGB ints, row-major. */
class PosterImage(val w: Int, val h: Int, val argb: IntArray) {
    init {
        require(w > 0 && h > 0 && argb.size >= w * h) { "bad image" }
    }
}

/**
 * The photo and its cut-out. [alpha] has one byte per pixel (0 = background, 255 = subject). Every change of the mask bumps [version], which the picture
 * reads. The Windows brush was removed by the owner (2026-11-09): the mask only comes from the automatic cut-out.
 */
class PosterPhoto(val image: PosterImage, alpha: ByteArray) {
    val w: Int get() = image.w
    val h: Int get() = image.h
    val alpha: ByteArray = alpha

    /** Unique per photo object (a turned or mirrored copy is a new photo). The picture's caches use it. */
    val uid: Int = nextUid.incrementAndGet()

    var version by mutableIntStateOf(0)
        private set

    /** True once a mask came from the cut-out (or a test). */
    var hasMask by mutableStateOf(alpha.any { it.toInt() != 0 })
        private set

    /** Puts a whole new mask in (what the cut-out made) and redraws. (No second copy is kept: it was never read, review item 32.) */
    fun setMask(src: ByteArray) {
        System.arraycopy(src, 0, alpha, 0, minOf(src.size, alpha.size))
        hasMask = true
        version++
    }

    private companion object {
        val nextUid = AtomicInteger(0)
    }
}

/** Everything of one poster. Plain Compose state: the editor writes it, the picture reads it (through [PosterFrame]). */
class PosterState {
    val songs = mutableStateListOf<PosterSong>()

    /** Covers by song id (software bitmaps, at most 544 px); a card without one shows a plain ink tile. */
    val covers = mutableStateMapOf<String, Bitmap>()

    /** The main colour of each cover, by song id. */
    val tints = mutableStateMapOf<String, Color>()

    var photo by mutableStateOf<PosterPhoto?>(null)
    var cutout by mutableStateOf(CutoutPhase.NONE)

    /** 0..1 while the cut-out runs. */
    var cutoutProgress by mutableFloatStateOf(0f)
    var layout by mutableStateOf(PosterLayout.ORBIT)
    val poses = mutableStateListOf<CardPose>()

    /** The shape of the poster (9:16 by default); every position of the poster is in this shape's pixels. */
    var ratio by mutableStateOf(PosterRatio.DEFAULT)

    /** What lies behind the cards, "Vinyet" (0..1, 0.5 = the old look) and how the poster is saved (the last two are not part of the undo's document). */
    var background by mutableStateOf(PosterBackground.ASLI)
    var fxVignette by mutableFloatStateOf(PosterFxDefaults.VIGNETTE)
    var exportFormat by mutableStateOf(PosterFormat.PNG)
    var exportQuality by mutableFloatStateOf(0.92f)

    // ---- texts
    val texts = mutableStateListOf<PosterText>()
    var selectedText by mutableIntStateOf(-1)
    private var nextTextId = 1

    /** The card being edited, or -1. */
    var selected by mutableIntStateOf(-1)

    /** "Cahaya kartu": the covers' colours as two soft gel lights on the ground (faint on a photo). */
    var glow by mutableFloatStateOf(0.35f)
    var glowTint by mutableStateOf<Color?>(null)
    var dim by mutableFloatStateOf(0.12f)
    var blur by mutableFloatStateOf(0f)
    var photoZoom by mutableFloatStateOf(1f)
    var photoPanX by mutableFloatStateOf(0f)
    var photoPanY by mutableFloatStateOf(0f)

    // ---- the cards' look and 3D (0..1 unless said otherwise)
    var material by mutableStateOf(CardMaterial.ASAP)
    var materialCustom by mutableStateOf(false)
    var cardPerspective by mutableFloatStateOf(0.5f)
    var cardThickness by mutableFloatStateOf(0.4f)
    var cardFrost by mutableFloatStateOf(0.6f)
    var cardOpacity by mutableFloatStateOf(0.85f)
    var cardCorner by mutableFloatStateOf(0.5f)
    var cardGloss by mutableFloatStateOf(0.5f)
    var cardDof by mutableFloatStateOf(0.25f)
    var shadowStrength by mutableFloatStateOf(0.6f)
    var shadowBlur by mutableFloatStateOf(0.5f)
    var shadowDistance by mutableFloatStateOf(0.5f)
    var shadowAngle by mutableFloatStateOf(POSTER_SHADOW_ANGLE)
    var shadowTone by mutableStateOf(ShadowTone.NETRAL)

    fun applyMaterial(m: CardMaterial) {
        when (m) {
            CardMaterial.ASAP -> setGlass(0.6f, 0.85f, 0.5f)
            CardMaterial.BENING -> setGlass(0.35f, 0.45f, 0.7f)
            CardMaterial.SUSU -> setGlass(0.8f, 0.7f, 0.4f)
            CardMaterial.GELAP -> setGlass(0.1f, 0.97f, 0.3f)
        }
        material = m
    }

    private fun setGlass(frost: Float, opacity: Float, gloss: Float) {
        cardFrost = frost
        cardOpacity = opacity
        cardGloss = gloss
    }

    // ---- stickers
    val stickers = mutableStateListOf<PosterSticker>()

    /** The user's own sticker pictures by key (software bitmaps with alpha, long side at most 1024). */
    val stickerImages = mutableStateMapOf<String, Bitmap>()
    var selectedSticker by mutableIntStateOf(-1)
    private var nextStickerId = 1

    /** Adds a sticker near the middle (a little off the previous one) and selects it. Returns its index, or -1 at the limit / for a missing picture. */
    fun addSticker(kind: StickerKind, imageKey: String? = null): Int {
        if (stickers.size >= PosterStickers.MAX_COUNT) return -1
        if (kind == StickerKind.CUSTOM && (imageKey == null || !stickerImages.containsKey(imageKey))) return -1
        val n = stickers.size
        stickers.add(PosterSticker(nextStickerId++, kind, ratio.w / 2f + (n % 5 - 2) * 48f, ratio.h / 2f - 180f * ratio.unit + (n % 5) * 36f, imageKey = imageKey))
        selected = -1
        selectedText = -1
        selectedSticker = stickers.lastIndex
        return stickers.lastIndex
    }

    fun removeSticker(index: Int) {
        if (index !in stickers.indices) return
        stickers.removeAt(index)
        selectedSticker = -1
    }

    fun duplicateSticker(index: Int): Int {
        val s = stickers.getOrNull(index) ?: return -1
        if (stickers.size >= PosterStickers.MAX_COUNT) return -1
        stickers.add(s.copy(id = nextStickerId++, x = (s.x + 56f).coerceAtMost(ratio.w - 20f), y = (s.y + 56f).coerceAtMost(ratio.h - 20f)))
        selected = -1
        selectedText = -1
        selectedSticker = stickers.lastIndex
        return stickers.lastIndex
    }

    // ---- texts (round 4.3.6)

    /** Adds a text near the middle (a little off the previous one) and selects it. Returns its index, or -1 at the limit / for an empty text. */
    fun addText(text: String, style: PosterTextStyle = PosterTextStyle.BERSIH, color: Int = PosterTexts.WHITE): Int {
        if (texts.size >= PosterTexts.MAX_COUNT) return -1
        val clean = PosterTexts.clean(text) ?: return -1
        val n = texts.size
        val probe = PosterText(0, clean, style, 0f, 0f)
        // a long text starts smaller, so it fits the picture's width (the handle makes it larger again)
        val fit = (0.84f * ratio.w / (2f * PosterTexts.half1(probe).first)).coerceIn(PosterTexts.MIN_SCALE, 1f)
        texts.add(PosterText(nextTextId++, clean, style, ratio.w / 2f + (n % 3 - 1) * 40f, ratio.h * 0.42f + (n % 4) * 90f * ratio.unit, fit, 0f, color))
        selected = -1
        selectedSticker = -1
        selectedText = texts.lastIndex
        return texts.lastIndex
    }

    fun removeText(index: Int) {
        if (index !in texts.indices) return
        texts.removeAt(index)
        selectedText = -1
    }

    fun duplicateText(index: Int): Int {
        val t = texts.getOrNull(index) ?: return -1
        if (texts.size >= PosterTexts.MAX_COUNT) return -1
        texts.add(t.copy(id = nextTextId++, x = (t.x + 56f).coerceAtMost(ratio.w - 20f), y = (t.y + 56f).coerceAtMost(ratio.h - 20f)))
        selected = -1
        selectedSticker = -1
        selectedText = texts.lastIndex
        return texts.lastIndex
    }

    /** Keeps the id counter above every text id (after an undo brought old texts back). */
    internal fun syncTextIds() {
        nextTextId = max(nextTextId, (texts.maxOfOrNull { it.id } ?: 0) + 1)
    }

    /** Keeps the id counter above every sticker id (after an undo brought old stickers back). */
    internal fun syncStickerIds() {
        nextStickerId = max(nextStickerId, (stickers.maxOfOrNull { it.id } ?: 0) + 1)
    }

    // ---- the look (round 4.3.5, "no AI look"); defaults = the BERSIH preset, a clean editorial poster (film grain 0 by the owner's rule).
    // The fields keep their old names (saved state, undo); what they draw now:
    //   fxLeak    "Cahaya utama": the key light's gentle exposure falloff across the frame (PosterField), no pasted leaks
    //   fxStreak  "Garis lensa": the thin anamorphic streak of the strongest REAL highlight (none when the picture has none)
    //   fxSparkle "Kilau": star-filter glints where the cards' glass edges catch the key light and on the photo's real specular highlights
    //             (on in every preset since K, 2026-11-13: the owner missed them; how many and how large follows the slider)
    //   fxBloom   "Pendar": halation of the real highlights only
    //   fxRim     "Tepi kartu": the lit hairline of the glass edge (no glow halo)
    var grade by mutableStateOf(PosterGrade.ASLI)
    var fxBloom by mutableFloatStateOf(0.15f)
    var fxLeak by mutableFloatStateOf(0.3f)
    var fxStreak by mutableFloatStateOf(0f)
    var fxSparkle by mutableFloatStateOf(PosterFxDefaults.SPARKLE_BERSIH)
    var fxGrain by mutableFloatStateOf(0f)
    var fxRim by mutableFloatStateOf(0.5f)
    var preset by mutableStateOf<PosterFxPreset?>(PosterFxPreset.BERSIH)

    fun applyPreset(p: PosterFxPreset) {
        when (p) {
            PosterFxPreset.BERSIH -> setFx(PosterGrade.ASLI, 0.15f, 0.3f, 0f, PosterFxDefaults.SPARKLE_BERSIH, 0f, 0.5f)
            PosterFxPreset.SINEMA -> setFx(PosterGrade.HANGAT, 0.35f, 0.5f, 0.35f, PosterFxDefaults.SPARKLE_SINEMA, 0f, 0.6f)
            PosterFxPreset.FILM -> setFx(PosterGrade.FILM, 0.25f, 0.35f, 0f, PosterFxDefaults.SPARKLE_FILM, 0.5f, 0.35f)
        }
        preset = p
    }

    private fun setFx(g: PosterGrade, bloom: Float, leak: Float, streak: Float, sparkle: Float, grain: Float, rim: Float) {
        grade = g
        fxBloom = bloom
        fxLeak = leak
        fxStreak = streak
        fxSparkle = sparkle
        fxGrain = grain
        fxRim = rim
    }

    fun setSongs(list: List<PosterSong>) {
        songs.clear()
        songs.addAll(list.distinctBy { it.id }.take(POSTER_MAX_SONGS))
        applyLayout(layout)
    }

    /** Puts the cards of [l] in their ready-made places (dropping any hand-made moves). */
    fun applyLayout(l: PosterLayout) {
        layout = l
        poses.clear()
        poses.addAll(PosterLayouts.poses(l, songs.size, ratio).take(songs.size))
        selected = -1
    }

    /**
     * Moves the poster to another shape and keeps everything on it. Untouched cards get the ready-made arrangement of THIS shape; cards the user
     * placed keep their share of the picture (and size by area); the stickers, the texts and the photo's pan follow their share of the picture.
     * [rearrange] = also drop the hand-made places (the "Susun ulang" button). Returns false when nothing was to be done.
     */
    fun changeRatio(to: PosterRatio, rearrange: Boolean = false): Boolean {
        val from = ratio
        if (to == from && !rearrange) return false
        val fresh = rearrange || PosterRemap.untouched(poses.toList(), layout, songs.size, from)
        ratio = to
        if (fresh) {
            applyLayout(layout)
        } else {
            val old = poses.toList()
            poses.clear()
            poses.addAll(old.map { PosterRemap.card(it, from, to) })
        }
        if (to != from) {
            for (i in stickers.indices) {
                val s = stickers[i]
                val (x, y) = PosterRemap.point(s.x, s.y, from, to)
                stickers[i] = s.copy(x = x, y = y)
            }
            for (i in texts.indices) {
                val t = texts[i]
                val (x, y) = PosterRemap.point(t.x, t.y, from, to)
                texts[i] = t.copy(x = x, y = y)
            }
            photoPanX *= PosterRemap.sx(from, to)
            photoPanY *= PosterRemap.sy(from, to)
            photo?.let {
                val (px, py) = PosterEdit.clampPan(it.w, it.h, photoZoom, photoPanX, photoPanY, to)
                photoPanX = px
                photoPanY = py
            }
        }
        return true
    }
}

/**
 * Where the photo sits on the poster: scaled to just cover it at zoom 1, centred, zoomed about its centre by [PosterState.photoZoom] and shifted by the pan
 * (poster pixels). The picture and the gestures agree on this.
 */
object PosterPhotoGeometry {
    fun scale(photoW: Int, photoH: Int, zoom: Float, ratio: PosterRatio = PosterRatio.DEFAULT): Float = max(ratio.w.toFloat() / photoW.coerceAtLeast(1), ratio.h.toFloat() / photoH.coerceAtLeast(1)) * zoom

    fun toPoster(px: Float, py: Float, photoW: Int, photoH: Int, zoom: Float, panX: Float, panY: Float, ratio: PosterRatio = PosterRatio.DEFAULT): Pair<Float, Float> {
        val f = scale(photoW, photoH, zoom, ratio)
        return (ratio.w / 2f + panX + (px - photoW / 2f) * f) to (ratio.h / 2f + panY + (py - photoH / 2f) * f)
    }

    fun toPhoto(x: Float, y: Float, photoW: Int, photoH: Int, zoom: Float, panX: Float, panY: Float, ratio: PosterRatio = PosterRatio.DEFAULT): Pair<Float, Float> {
        val f = scale(photoW, photoH, zoom, ratio)
        return (photoW / 2f + (x - ratio.w / 2f - panX) / f) to (photoH / 2f + (y - ratio.h / 2f - panY) / f)
    }
}
