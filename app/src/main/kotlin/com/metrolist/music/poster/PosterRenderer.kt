/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.poster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.metrolist.music.R
import com.metrolist.music.poster.PosterFx.glow
import com.metrolist.music.poster.PosterFx.mark
import com.metrolist.music.poster.PosterGlints.streak
import com.metrolist.music.ui.theme.HorizonFontFamily
import java.nio.IntBuffer
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ====================================================================================================================
// THE PICTURE. One renderer paints the whole poster for the preview (at the preview's pixel size, on a background thread) and for the
// export (1080 x 1920): the same code and the same state snapshot ([PosterFrame]), so what is edited is what is saved. It paints on a
// SOFTWARE canvas into a Bitmap, which works the same on Android 8 to 16 (mask-filter blurs, perspective, soft light, bloom).
//
// Layers, back to front:
//   1. the ground: photo (cover-fit, zoom/pan, dimmed, optionally blurred; a blurred copy where it does not cover) with the covers' faint gel light,
//      or (no photo) the studio backdrop in a tone of the covers. Painted once into a bitmap and reused until the photo, its place or the colours
//      change (moving a card never repaints it).
//   2. the cards (tilted ink glass, one projective matrix each) showing a frosted copy of layer 1; cards further back on a blurred layer (depth of field)
//   3. with a cut-out: the person's soft shadow, on the cards only (they float right behind the person; the photo's own background already has
//      its own light and gets none), then the person
//      ("Kilau", PosterGlints) the glints where a card's glass edge catches the key light or the photo has a real specular highlight: added
//      light, behind the person (a highlight on the person itself is laid over the person)
//   4. the stickers
//   5. the finish (PosterFinish, one CPU pass): the grade's tone curves, the light field (natural vignette and the key light), the dither;
//      then the halation of the real highlights (and of the glints' cores), the streak of the strongest real highlight, the film grain;
//      last of all the Auralys mark (always).
// Round 4.3.5 removed the pasted light leaks, the glowing card halos, the lens flare and the scattered stars ("no AI look").

/** An immutable snapshot of everything the picture shows. Built on the main thread by [PosterState.frame], read by the render thread. */
class PosterFrame(
    val songs: List<PosterSong>,
    val covers: Map<String, Bitmap>,
    val tints: Map<String, Color>,
    val photo: PosterPhoto?,
    val photoVersion: Int,
    val cutout: CutoutPhase,
    val poses: List<CardPose>,
    val glow: Float,
    val glowTint: Color?,
    val dim: Float,
    val blur: Float,
    val photoZoom: Float,
    val photoPanX: Float,
    val photoPanY: Float,
    val cardPerspective: Float,
    val cardThickness: Float,
    val cardFrost: Float,
    val cardOpacity: Float,
    val cardCorner: Float,
    val cardGloss: Float,
    val cardDof: Float,
    val shadowStrength: Float,
    val shadowBlur: Float,
    val shadowDistance: Float,
    val shadowAngle: Float,
    val shadowTone: ShadowTone,
    val stickers: List<PosterSticker>,
    val stickerImages: Map<String, Bitmap>,
    val grade: PosterGrade,
    val fxBloom: Float,
    val fxLeak: Float,
    val fxStreak: Float,
    val fxSparkle: Float,
    val fxGrain: Float,
    val fxRim: Float,
    val ratio: PosterRatio = PosterRatio.DEFAULT,
    val texts: List<PosterText> = emptyList(),
    val background: PosterBackground = PosterBackground.ASLI,
    val fxVignette: Float = PosterFxDefaults.VIGNETTE,
)

/** Reads every field of the state (this is what makes a preview re-render when any of them changes). */
fun PosterState.frame(): PosterFrame {
    val p = photo
    return PosterFrame(
        songs = songs.toList(),
        covers = covers.toMap(),
        tints = tints.toMap(),
        photo = p,
        photoVersion = p?.version ?: -1,
        cutout = cutout,
        poses = poses.toList(),
        glow = glow,
        glowTint = glowTint,
        dim = dim,
        blur = blur,
        photoZoom = photoZoom,
        photoPanX = photoPanX,
        photoPanY = photoPanY,
        cardPerspective = cardPerspective,
        cardThickness = cardThickness,
        cardFrost = cardFrost,
        cardOpacity = cardOpacity,
        cardCorner = cardCorner,
        cardGloss = cardGloss,
        cardDof = cardDof,
        shadowStrength = shadowStrength,
        shadowBlur = shadowBlur,
        shadowDistance = shadowDistance,
        shadowAngle = shadowAngle,
        shadowTone = shadowTone,
        stickers = stickers.toList(),
        stickerImages = stickerImages.toMap(),
        grade = grade,
        fxBloom = fxBloom,
        fxLeak = fxLeak,
        fxStreak = fxStreak,
        fxSparkle = fxSparkle,
        fxGrain = fxGrain,
        fxRim = fxRim,
        ratio = ratio,
        texts = texts.toList(),
        background = background,
        fxVignette = fxVignette,
    )
}

/**
 * The photo-sized bitmaps of one poster (the photo, the cut-out), shared by the preview's and the export's renderer so a 2048 px photo is held once.
 * Built on first use on the calling (render) thread; read-only afterwards; thread-safe.
 */
class PosterAssets {
    private class Entry(val uid: Int, val version: Int, val chain: MipChain)

    private var photo: Entry? = null
    private var cut: Entry? = null

    @Synchronized
    internal fun photoChain(p: PosterPhoto): MipChain {
        photo?.takeIf { it.uid == p.uid }?.let { return it.chain }
        // the old bitmaps are not recycled by hand: the other renderer may still be drawing them (the GC frees them)
        val bmp = Bitmap.createBitmap(p.image.argb, p.w, p.h, Bitmap.Config.ARGB_8888)
        return MipChain(bmp).also { photo = Entry(p.uid, 0, it) }
    }

    /** The cut-out for the photo's current mask, or null when the photo has none. */
    @Synchronized
    internal fun cutChain(p: PosterPhoto, version: Int): MipChain? {
        if (!p.hasMask) return null
        cut?.takeIf { it.uid == p.uid && it.version == version }?.let { return it.chain }
        val bmp = CutoutPixels.buildBitmap(p)
        return MipChain(bmp).also { cut = Entry(p.uid, version, it) }
    }

    @Synchronized
    fun clear() {
        photo = null
        cut = null
    }
}

/**
 * [level] 0 = full quality (the export, and the preview on capable phones), 1 = lighter (no depth of field, smaller blurs: the preview on weak phones).
 * Not thread-safe: one renderer per thread.
 *
 * Moving the photo or a ground slider ("Gelap", "Kabur", "Cahaya kartu") changes the ground under everything (review 2026-11-12, finding 24:
 * every frame of such a gesture used to repaint all card layers and the glow). The picture stays exactly the same, but the work is cut:
 *  - the glow is laid on the photo from two cached layers (what it adds, and how much of the photo shows through it, per channel), instead
 *    of painting its radial gradients again;
 *  - a card layer is split once into the part that does not depend on the frosted ground and the share of the ground its glass shows;
 *    a new ground is then put into the glass with one multiply-add per pixel, nothing of the card is painted again.
 * [warm] prepares both while the editor is idle, so the first frame of a gesture is fast too.
 */
class PosterRenderer(context: Context, private val assets: PosterAssets) {
    private val app = context.applicationContext
    private val measurer = TextMeasurer(createFontFamilyResolver(app), Density(1f), LayoutDirection.Ltr, 128)
    private val cards = CardPainter(measurer, HorizonFontFamily)
    private val wordStyle = TextStyle(fontFamily = HorizonFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = 44.sp, letterSpacing = (-0.2).sp)
    private val stickerShadows = StickerShadowCache()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val logo: ImageBitmap? by lazy {
        runCatching { AppCompatResources.getDrawable(app, R.drawable.app_logo)?.toBitmap(128, 128, Bitmap.Config.ARGB_8888)?.asImageBitmap() }.getOrNull()
    }

    /** [photo] = the photo that is the ground, -1 = the studio backdrop, -2 = a styled ground ([bg]). */
    private data class BaseKey(
        val bw: Int, val bh: Int, val photo: Int, val zoom: Float, val panX: Float, val panY: Float, val dim: Float, val blur: Float, val glow: Float, val colors: List<Color>, val tone: DominantHue?, val level: Int,
        val ratio: PosterRatio = PosterRatio.DEFAULT, val bg: PosterBackground = PosterBackground.ASLI, val bgCover: Int = 0, val tints: List<Color> = emptyList(),
    )

    private data class GlowKey(val bw: Int, val bh: Int, val glow: Float, val colors: List<Color>, val level: Int, val ratio: PosterRatio = PosterRatio.DEFAULT)

    private data class FieldKey(val w: Int, val h: Int, val angle: Float, val spill: Float, val grade: PosterGrade, val vignette: Float = 0.16f, val ratio: PosterRatio = PosterRatio.DEFAULT)

    private var baseKey: BaseKey? = null
    private var base: Bitmap? = null
    private var backdrop: ImageBitmap? = null
    private var backdropBmp: Bitmap? = null
    private var backdropFor: Bitmap? = null
    private var backdropFrost = -1f
    private var scene: Bitmap? = null
    private val cardLayers = ArrayList<CardLayer?>()
    private var backdropVersion = 0

    /** The cards alone (with a cut-out: the person's shadow falls on them, not on the photo behind). */
    private var cardsOnly: Bitmap? = null
    private var postPx: IntArray? = null

    // the backdrop's hue from the covers (lead review 2026-11-13), kept until a cover changes: moving or sizing a card never repaints the ground
    private var toneKey: List<Int>? = null
    private var tone: DominantHue? = null
    private var fieldKey: FieldKey? = null
    private var field: LightField? = null

    // the photo's specular spots (photo pixels), found once per photo; the glints' pictures by size
    private var spotsOf = -1
    private var spots: List<PhotoSpot> = emptyList()
    internal val sprites = GlintSprites()

    /** The glints of the last [render] (poster pixels), for the tests. */
    internal var lastGlints: List<Glint> = emptyList()
        private set

    /** How many times the photo was searched for its specular spots (a test checks that moving it or a card does not). */
    var spotSearches = 0
        private set
    private val bloomBuffers = BloomBuffers()
    private var meanOf = -1
    private var mean: Color? = null
    private var shadowKey = ""
    private var shadow: Bitmap? = null
    private val blurScratch = PosterBlur.Scratch()

    // the glow as two layers (see renderBase)
    private var lastGlowKey: GlowKey? = null
    private var glowFor: GlowKey? = null
    private var glowAdd: Bitmap? = null
    private var glowKeep: Bitmap? = null

    // where the person is on a coarse grid and the box of the head (finding 13: no light on the face; round 4.3.5: no glint there either)
    private class Light(val key: String, val grid: SubjectGrid?)

    private var light: Light? = null

    // card splits: a plain black and a plain white "ground" for the two paints of a split, a bitmap for those paints, the compose buffer
    private val solidBlack: ImageBitmap by lazy { solid(android.graphics.Color.BLACK) }
    private val solidWhite: ImageBitmap by lazy { solid(android.graphics.Color.WHITE) }
    private var splitBmp: Bitmap? = null
    private var composePx: IntArray? = null

    /** How many times the ground was repainted (a test checks that moving a card does not). */
    var baseBuilds = 0
        private set

    /** How many card layers were painted (a test checks that moving one card repaints only that one). */
    var cardLayerBuilds = 0
        private set

    /** How many card layers were split into their ground-free part and their glass share (two paints each). */
    var cardSplits = 0
        private set

    /** How many times a card layer got a new ground in its glass without being painted. */
    var cardRecomposes = 0
        private set

    /** How many times the glow's two layers were built. */
    var glowBuilds = 0
        private set

    /** Milliseconds the last [render] took. */
    var lastMillis = 0L
        private set

    /** Where the last [render]'s time went (ms): ground, backdrop, cards, person, stickers, finish (grade + light), halation, glints + grain + mark. */
    val stageMillis = LongArray(8)
    private var stageStart = 0L

    private fun stage(i: Int) {
        val now = System.nanoTime()
        stageMillis[i] = (now - stageStart) / 1_000_000
        stageStart = now
    }

    private fun solid(color: Int): ImageBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.asImageBitmap()

    private fun colorsOf(f: PosterFrame, tintsShown: List<Color>): List<Color> {
        val photo = f.photo
        val photoMean =
            photo?.let { p ->
                if (meanOf != p.uid) {
                    meanOf = p.uid
                    mean = PosterFx.meanColor(p.image)
                }
                mean
            }
        return PosterFx.lightColors(f.glowTint, tintsShown, photoMean)
    }

    private fun styleOf(f: PosterFrame, level: Int) =
        CardStyle(
            camera = PosterGeometry.cameraFor(f.cardPerspective),
            radius = PosterGeometry.cornerFor(f.cardCorner),
            thickness = PosterGeometry.thicknessFor(f.cardThickness),
            opacity = f.cardOpacity,
            frost = f.cardFrost,
            gloss = f.cardGloss,
            dof = f.cardDof,
            shadowStrength = f.shadowStrength,
            shadowBlur = f.shadowBlur,
            shadowDistance = f.shadowDistance,
            shadowAngle = f.shadowAngle,
            shadowTone = f.shadowTone,
            rim = f.fxRim,
            level = level,
            pw = f.ratio.w,
            ph = f.ratio.h,
        )

    /**
     * Paints [f] into [out] (or a new bitmap) of [bw] x [bh] device pixels (the poster's 9:16; other sizes are letterboxed by the caller).
     * Returns the bitmap painted into.
     */
    fun render(f: PosterFrame, bw: Int, bh: Int, level: Int, out: Bitmap? = null): Bitmap {
        val t0 = System.nanoTime()
        stageStart = t0
        val w = max(2, bw)
        val h = max(2, bh)
        val ratio = f.ratio
        val s = w / ratio.w.toFloat()
        val target = out?.takeIf { it.width == w && it.height == h && it.isMutable && !it.isRecycled } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val photo = f.photo
        val tintsShown = f.songs.mapNotNull { f.tints[it.id] }
        val colors = colorsOf(f, tintsShown)
        // a styled background (not ASLI) replaces the photo as the ground; the cut-out person still stands in front of it
        val styled = f.background != PosterBackground.ASLI
        val groundPhoto = if (styled) null else photo
        val bgTone = if (groundPhoto == null) toneFor(f) else null
        val styledCover = if (f.background == PosterBackground.KABUR) f.songs.firstNotNullOfOrNull { f.covers[it.id]?.takeIf { b -> !b.isRecycled && b.width > 0 && b.height > 0 } } else null
        val groundBg = if (f.background == PosterBackground.KABUR && styledCover == null) PosterBackground.GRADIEN else f.background
        val key =
            BaseKey(
                w, h, if (styled) -2 else (photo?.uid ?: -1), f.photoZoom, f.photoPanX, f.photoPanY, f.dim, f.blur, f.glow, colors, bgTone, level,
                ratio, groundBg, System.identityHashCode(styledCover), if (groundBg == PosterBackground.GRADIEN || groundBg == PosterBackground.POLOS) tintsShown else emptyList(),
            )
        if (key != baseKey || base == null) {
            val b = base?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { base?.recycle() }
            renderBase(b, key, s, groundPhoto, styledCover)
            base = b
            baseKey = key
            backdropFor = null
            baseBuilds++
        }
        stage(0)
        val ground = base!!
        if (level < 2 && (backdropFor !== ground || backdropFrost != f.cardFrost || backdrop == null)) {
            val bd = renderBackdrop(ground, f.cardFrost)
            backdropBmp = bd
            backdrop = bd.asImageBitmap()
            backdropVersion++
            backdropFor = ground
            backdropFrost = f.cardFrost
        }
        stage(1)

        // ---- the scene
        val sc = scene?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { scene?.recycle(); scene = it }
        val scCanvas = android.graphics.Canvas(sc)
        paint.reset()
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        scCanvas.drawBitmap(ground, 0f, 0f, paint)
        paint.reset()
        val style = styleOf(f, level)
        val bd = if (level < 2) backdrop else null
        val subject = photo != null && f.cutout == CutoutPhase.DONE && photo.hasMask
        val ratioW = ratio.w
        // with a cut-out the cards go on a layer of their own first: the person's shadow falls on them (they float right behind the person),
        // never on the photo's own background (which already has its light and shadows)
        val shadowOnCards = subject && level < 2
        val cardsBmp =
            if (shadowOnCards) {
                (cardsOnly?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { cardsOnly?.recycle(); cardsOnly = it }).apply { eraseColor(0) }
            } else {
                null
            }
        val cardCanvas = if (cardsBmp != null) android.graphics.Canvas(cardsBmp) else scCanvas
        // each card (with its shadow and depth of field) is painted on its own layer and kept until something of THAT card changes: dragging one
        // card or moving an effect slider repaints no other card. Laid on with source-over in card order, which is the same picture as painting them in place.
        for ((i, song) in f.songs.withIndex()) {
            val pose = f.poses.getOrNull(i) ?: continue
            val l = cardLayer(i, CardArgs(song, f.covers[song.id], f.tints[song.id], pose, bd, style), s, w, h) ?: continue
            paint.reset()
            cardCanvas.drawBitmap(l.bmp!!, l.x0.toFloat(), l.y0.toFloat(), paint)
        }
        while (cardLayers.size > f.songs.size) cardLayers.removeAt(cardLayers.lastIndex)?.split?.box?.recycle()
        stage(2)
        val grid = if (subject) lightFor(photo!!, f).grid else null
        // "Kilau": the photo's real highlights (only with a cut-out: without one nobody knows where the face is) and the cards' lit edges
        val glintTint = PosterGlints.tint(f.grade)
        val photoGlints = if (level < 2 && subject && (f.fxSparkle > 0.01f || f.fxStreak > 0.01f)) photoHighlights(f, photo!!, style, grid) else emptyList()
        lastGlints = if (level < 2 && f.fxSparkle > 0.01f) placeGlints(f, style, grid, photoGlints) else emptyList()
        if (subject) {
            if (cardsBmp != null) {
                drawPersonShadow(cardCanvas, f, photo!!, level, s)
                paint.reset()
                scCanvas.drawBitmap(cardsBmp, 0f, 0f, paint)
            }
            // behind the person, then the person, then a highlight on the person itself
            drawGlints(scCanvas, s, glintTint, front = false)
            draw(scCanvas, w, h) { scale(s, s, Offset.Zero) { drawSubject(this, f, photo!!, s) } }
            drawGlints(scCanvas, s, glintTint, front = true)
        } else {
            drawGlints(scCanvas, s, glintTint, front = false)
        }
        stage(3)
        if (f.stickers.isNotEmpty()) {
            val bg = { x: Float, y: Float, r: Float -> lumaAround(sc, x * s, y * s, r * s) }
            draw(scCanvas, w, h) { scale(s, s, Offset.Zero) { for (st in f.stickers) drawSticker(st, f.shadowAngle, f.stickerImages, stickerShadows, paint, glintTint, sprites, s, bg) } }
        }
        // the texts (round 4.3.6) lie over the stickers, under the finish: they take the grade and the light like everything else
        if (f.texts.isNotEmpty()) {
            scCanvas.save()
            scCanvas.scale(s, s)
            for (t in f.texts) PosterTexts.draw(scCanvas, t)
            scCanvas.restore()
        }
        stickerShadows.retainOnly(f.stickerImages.values)
        stage(4)

        // ---- the finish: tone curves, light field and dither in one CPU pass
        val outCanvas = android.graphics.Canvas(target)
        val px = pixels(w * h)
        sc.getPixels(px, 0, w, 0, 0, w, h)
        val vig = PosterField.vignetteFor(f.fxVignette)
        val fk = FieldKey(w, h, f.shadowAngle, f.fxLeak, f.grade, vig, ratio)
        val fl = field?.takeIf { fieldKey == fk } ?: PosterField.build(w, h, f.shadowAngle, f.fxLeak, f.grade, vig, PosterField.bandStart(ratio)).also { field = it; fieldKey = fk }
        PosterFinish.apply(px, w, h, PosterTone.spec(f.grade), fl)
        target.setPixels(px, 0, w, 0, 0, w, h)
        stage(5)
        if (level < 2) PosterLook.bloom(outCanvas, target, f.fxBloom, level, bloomBuffers)
        stage(6)
        if (level < 2 && f.fxStreak > 0.01f) {
            PosterGlints.streakSource(photoGlints)?.let { g ->
                draw(outCanvas, w, h) {
                    scale(s, s, Offset.Zero) {
                        val (l, r) = PosterGlints.streakReach(g, grid, ratioW * (0.1f + 0.32f * f.fxStreak) * g.strength, ratioW.toFloat())
                        streak(g.x, g.y, l, r, f.fxStreak * (0.5f + 0.5f * g.strength), glintTint)
                    }
                }
            }
        }
        if (level < 2 && f.fxGrain > 0.01f) {
            target.getPixels(px, 0, w, 0, 0, w, h)
            PosterFinish.grain(px, w, h, f.fxGrain, s)
            target.setPixels(px, 0, w, 0, 0, w, h)
        }
        draw(outCanvas, w, h) {
            scale(s, s, Offset.Zero) {
                // the Auralys mark is part of every poster: drawn last, above the person and every effect (owner rule, no switch)
                mark(logo, measurer.measure("Auralys", wordStyle), ratio)
            }
        }
        stage(7)
        lastMillis = (System.nanoTime() - t0) / 1_000_000
        return target
    }

    private fun pixels(n: Int): IntArray = postPx?.takeIf { it.size == n } ?: IntArray(n).also { postPx = it }

    /**
     * The photo's real specular highlights at the photo's place on the poster (searched once per photo, then only moved with it): inside the
     * picture, above the mark's band, not under a card, not on the head (with a margin). One on the person must be a strong one (metal,
     * jewelry) and is drawn over the person, small. Its strength is lowered by the "Gelap" dim and the "Kabur" blur (a blurred highlight is no
     * point of light any more).
     */
    private fun photoHighlights(f: PosterFrame, photo: PosterPhoto, style: CardStyle, grid: SubjectGrid?): List<Glint> {
        if (spotsOf != photo.uid) {
            spots = PosterGlints.photoSpots(photo.image)
            spotsOf = photo.uid
            spotSearches++
        }
        val k = (1f - f.dim.coerceIn(0f, 0.7f)) * (1f - min(0.7f, 2f * f.blur))
        val head = grid?.head
        val out = ArrayList<Glint>()
        for (sp in spots) {
            val (x, y) = PosterPhotoGeometry.toPoster(sp.x, sp.y, photo.w, photo.h, f.photoZoom, f.photoPanX, f.photoPanY, f.ratio)
            if (x < 8f || x > f.ratio.w - 8f || y < 8f || y > f.ratio.markBand) continue
            if (head != null && x >= head[0] - 24f && x <= head[2] + 24f && y >= head[1] - 24f && y <= head[3] + 24f) continue
            if (f.poses.any { PosterGeometry.hit(it, x, y, style.camera, slop = 6f) }) continue
            val onPerson = (grid?.at(x, y) ?: 0) > 32
            // a styled background hides the photo's own surroundings: only the highlights on the person itself remain
            if (f.background != PosterBackground.ASLI && !onPerson) continue
            if (onPerson && sp.strength < PosterGlints.PERSON_MIN_STRENGTH) continue
            out.add(Glint(x, y, sp.strength * k, GlintSource.PHOTO, front = onPerson))
        }
        return out
    }

    /**
     * The glints of this picture ("Kilau"): the cards' lit edges (not under a later card, not behind the person, not on the head) and the
     * photo's highlights, chosen and sized by [PosterGlints.place]; no spike reaches the head, a card's text or controls, or the mark's band.
     */
    private fun placeGlints(f: PosterFrame, style: CardStyle, grid: SubjectGrid?, photoGlints: List<Glint>): List<Glint> {
        val cam = style.camera
        val soft = { i: Int -> f.poses.getOrNull(i)?.let { (cards.dofSigma(it, style) / 4f).coerceIn(0f, 1f) } ?: 0f }
        val head = grid?.head
        fun inHead(x: Float, y: Float, m: Float) = head != null && x >= head[0] - m && x <= head[2] + m && y >= head[1] - m && y <= head[3] + m
        val edges =
            PosterGlints.cardCandidates(f.poses, cam, style.radius, f.shadowAngle, f.cardGloss, f.fxRim, soft).filter { g ->
                PosterGlints.cardVisible(g, f.poses, cam, f.ratio) && (grid?.at(g.x, g.y) ?: 0) <= 32 && !inHead(g.x, g.y, 16f)
            }
        val invs = f.poses.map { PosterGeometry.invert(PosterGeometry.cardMatrix(it, cam)) }
        val blocked = { x: Float, y: Float -> y > f.ratio.markBand || inHead(x, y, 6f) || invs.any { it != null && PosterGlints.onCardText(it, x, y) } }
        return PosterGlints.place(edges + photoGlints, f.fxSparkle, blocked)
    }

    /** Lays the glints of [lastGlints] on the scene ([front] = the ones on the person, else all the others): added light, device pixels. */
    private fun drawGlints(canvas: android.graphics.Canvas, s: Float, tint: Color, front: Boolean) {
        for (g in lastGlints) if (g.front == front) sprites.draw(canvas, g.x * s, g.y * s, g.length * s, 1f, g.intensity, tint)
    }

    /** The mean luminance (0..1) of [b] in a disc of radius [r] around ([cx], [cy]) (device pixels), from 49 samples. */
    private fun lumaAround(b: Bitmap, cx: Float, cy: Float, r: Float): Float {
        var sum = 0f
        var n = 0
        for (j in -3..3) for (i in -3..3) {
            val x = (cx + i * r / 3f).roundToInt()
            val y = (cy + j * r / 3f).roundToInt()
            if (x < 0 || y < 0 || x >= b.width || y >= b.height) continue
            val c = b.getPixel(x, y)
            sum += (((c shr 16) and 255) * 0.299f + ((c shr 8) and 255) * 0.587f + (c and 255) * 0.114f) / 255f
            n++
        }
        return if (n == 0) 0f else sum / n
    }

    /**
     * The covers' shared hue for the backdrop ([PosterFx.dominantHue]): each card's cover sampled at 48 x 48, all cards the same weight but the
     * first a little more (it wins a tie). Kept until a cover changes. The user's "Warna cahaya" colours the gel lights, not the backdrop.
     */
    private fun toneFor(f: PosterFrame): DominantHue? {
        val covers = f.songs.map { f.covers[it.id]?.takeIf { b -> !b.isRecycled && b.width > 0 && b.height > 0 } }
        val key = covers.map { System.identityHashCode(it) }
        if (key == toneKey) return tone
        val samples = ArrayList<IntArray>()
        val weights = ArrayList<Float>()
        for ((i, b) in covers.withIndex()) {
            if (b == null) continue
            val small = Bitmap.createScaledBitmap(b, 48, 48, true)
            val px = IntArray(48 * 48)
            small.getPixels(px, 0, 48, 0, 0, 48, 48)
            if (small !== b) small.recycle()
            samples.add(px)
            weights.add(if (i == 0) 1.15f else 1f)
        }
        tone = PosterFx.dominantHue(samples, weights)
        toneKey = key
        return tone
    }

    /** The person's grid (and head box) for the photo at its place, kept until the photo, its mask or its place change. */
    private fun lightFor(photo: PosterPhoto, f: PosterFrame): Light {
        val k = "${photo.uid}/${f.photoVersion}/${f.photoZoom}/${f.photoPanX}/${f.photoPanY}/${f.ratio}"
        light?.takeIf { it.key == k }?.let { return it }
        return Light(k, SubjectGrid.build(photo, f.photoZoom, f.photoPanX, f.photoPanY, f.ratio)).also { light = it }
    }

    /**
     * Gets the caches of a gesture on the ground ready while the editor is idle (on the render thread, after [render] painted [f] at the same
     * size): the glow's layers and the split of every card layer. [stop] is asked between the steps (a new frame is waiting). True when all is ready.
     */
    fun warm(f: PosterFrame, bw: Int, bh: Int, level: Int, stop: () -> Boolean): Boolean {
        val w = max(2, bw)
        val h = max(2, bh)
        val bk = baseKey ?: return false
        if (bk.bw != w || bk.bh != h || bk.level != level || base == null) return false
        val gk = GlowKey(w, h, bk.glow, bk.colors, level, bk.ratio)
        // the glow's cached layers serve a photo or a styled ground (the studio backdrop has the gels in it)
        if (bk.photo != -1 && glowOn(gk) && glowFor != gk) {
            if (stop()) return false
            if (!buildGlow(gk, w / f.ratio.w.toFloat(), stop)) return false
        }
        val bd = backdrop
        if (level >= 2 || bd == null) return true
        val style = styleOf(f, level)
        val s = w / f.ratio.w.toFloat()
        for ((i, song) in f.songs.withIndex()) {
            val layer = cardLayers.getOrNull(i) ?: continue
            if (layer.bmp == null || layer.split != null) continue
            val pose = f.poses.getOrNull(i) ?: continue
            val a = CardArgs(song, f.covers[song.id], f.tints[song.id], pose, bd, style)
            if (layer.key != cardKey(a, w)) continue
            if (stop()) return false
            layer.split = split(layer, a, s, stop) ?: return false
        }
        return true
    }

    private inline fun draw(canvas: android.graphics.Canvas, w: Int, h: Int, crossinline block: DrawScope.() -> Unit) {
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(canvas), Size(w.toFloat(), h.toFloat())) { block() }
    }

    /**
     * The part of a card layer that does not depend on the frosted ground ([under]: the layer painted over a black ground, raw premultiplied
     * pixels) and how much of the ground its glass shows ([see], 0..255: the layer over a white ground minus [under]), for the rectangle
     * [l], [t], [w] x [h] of the layer: the glass's box, or the whole layer when it is blurred for depth of field. Only "over" blending follows
     * the ground inside the card, so the layer with ANY ground is `under + ground * see / 255`.
     */
    private class GlassSplit(val l: Int, val t: Int, val w: Int, val h: Int, val under: IntArray, val see: ByteArray) {
        var box: Bitmap? = null
    }

    private class CardLayer(val key: Any, val bmp: Bitmap?, val x0: Int, val y0: Int) {
        /** The backdrop version the glass of [bmp] shows. */
        var backdrop = -1
        var dof = 0f
        var split: GlassSplit? = null
    }

    private data class CardKey(val song: PosterSong, val cover: Int, val tint: Color?, val pose: CardPose, val style: CardStyle, val w: Int)

    private fun cardKey(a: CardArgs, w: Int) = CardKey(a.song, System.identityHashCode(a.cover), a.tint, a.pose, a.style, w)

    /**
     * The layer of card [i]: the card with its shadow on a transparent bitmap of its own bounds (clipped to the picture), blurred when it sits back (depth of
     * field). Null when the card is wholly outside the picture. Painted again only when the card, its cover, the style or the size changes; a new frosted
     * ground alone is put into its glass by [recompose].
     */
    private fun cardLayer(i: Int, a: CardArgs, s: Float, w: Int, h: Int): CardLayer? {
        val key = cardKey(a, w)
        while (cardLayers.size <= i) cardLayers.add(null)
        val old = cardLayers[i]
        if (old != null && old.key == key) {
            if (old.bmp == null) return null
            if (old.backdrop != backdropVersion && a.backdrop != null) recompose(old, a, s)
            return old
        }
        old?.split?.box?.recycle()
        val st = a.style
        val b = PosterGeometry.bounds(cards.outline(a.pose, st))
        val dof = cards.dofSigma(a.pose, st)
        // room for the thrown shadow (its length and blur follow CardPainter.shadow), the rim glow, the glass edge and the depth-of-field blur
        val lift = a.pose.lift.coerceIn(0f, 1f)
        val len = (4f + 80f * st.shadowDistance) * (0.2f + 1.6f * lift) * (0.6f + 0.4f * a.pose.scale.coerceIn(0.4f, 1.4f))
        val sigma = (3f + 34f * st.shadowBlur) * (0.5f + 1.1f * lift)
        val pad = len + 3f * sigma + 3f * dof + 70f
        val x0 = floor(max(0f, (b[0] - pad) * s)).toInt()
        val y0 = floor(max(0f, (b[1] - pad) * s)).toInt()
        val x1 = ceil(min(w.toFloat(), (b[2] + pad) * s)).toInt()
        val y1 = ceil(min(h.toFloat(), (b[3] + pad) * s)).toInt()
        if (x1 - x0 < 1 || y1 - y0 < 1) {
            cardLayers[i] = CardLayer(key, null, 0, 0)
            return null
        }
        val lw = x1 - x0
        val lh = y1 - y0
        // the same size while a card is dragged (the projection does not depend on where the card sits), so the bitmap is reused
        val bmp = old?.bmp?.takeIf { it.width == lw && it.height == lh } ?: Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
        paintCard(bmp, x0, y0, s, a)
        if (dof > 0.35f) PosterBlur.blurBitmap(bmp, dof * s, clamp = false, scratch = blurScratch)
        cardLayerBuilds++
        return CardLayer(key, bmp, x0, y0).also {
            it.backdrop = backdropVersion
            it.dof = dof
            cardLayers[i] = it
        }
    }

    /** Paints the card of [a] (no depth of field) into [bmp], which covers the layer at ([x0], [y0]) of the picture. */
    private fun paintCard(bmp: Bitmap, x0: Int, y0: Int, s: Float, a: CardArgs) {
        bmp.eraseColor(0)
        val c = android.graphics.Canvas(bmp)
        c.translate(-x0.toFloat(), -y0.toFloat())
        draw(c, bmp.width, bmp.height) { scale(s, s, Offset.Zero) { cards.draw(this, a) } }
    }

    /** Two paints of the card, over a black and over a white ground: see [GlassSplit]. Null when [stop] says so between the two paints. */
    private fun split(layer: CardLayer, a: CardArgs, s: Float, stop: () -> Boolean = { false }): GlassSplit? {
        val bmp = layer.bmp!!
        val lw = bmp.width
        val lh = bmp.height
        val n = lw * lh
        val tmp = splitBmp?.takeIf { it.width == lw && it.height == lh } ?: Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888).also { splitBmp?.recycle(); splitBmp = it }
        paintCard(tmp, layer.x0, layer.y0, s, CardArgs(a.song, a.cover, a.tint, a.pose, solidBlack, a.style))
        val black = IntArray(n)
        tmp.copyPixelsToBuffer(IntBuffer.wrap(black))
        // a new picture is waiting: it goes first (the split is made again when the editor is idle)
        if (stop()) return null
        paintCard(tmp, layer.x0, layer.y0, s, CardArgs(a.song, a.cover, a.tint, a.pose, solidWhite, a.style))
        val white = blurScratch.first(n)
        tmp.copyPixelsToBuffer(IntBuffer.wrap(white, 0, n))
        cardSplits++
        // the box of the glass (where any of the ground shows)
        var l = lw
        var t = lh
        var r = -1
        var b = -1
        for (y in 0 until lh) {
            val row = y * lw
            for (x in 0 until lw) {
                if (((white[row + x] ushr 8) and 255) > ((black[row + x] ushr 8) and 255)) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    if (y > b) b = y
                }
            }
        }
        if (r < 0) return GlassSplit(0, 0, 0, 0, IntArray(0), ByteArray(0))
        if (layer.dof > 0.35f) {
            // the blur mixes the glass with what is around it: the whole layer is recomposed and blurred again
            l = 0
            t = 0
            r = lw - 1
            b = lh - 1
        }
        val sw = r - l + 1
        val sh = b - t + 1
        val under = IntArray(sw * sh)
        val see = ByteArray(sw * sh)
        for (y in 0 until sh) {
            val src = (t + y) * lw + l
            val dst = y * sw
            System.arraycopy(black, src, under, dst, sw)
            for (x in 0 until sw) {
                val k = ((white[src + x] ushr 8) and 255) - ((black[src + x] ushr 8) and 255)
                see[dst + x] = k.coerceIn(0, 255).toByte()
            }
        }
        return GlassSplit(l, t, sw, sh, under, see)
    }

    /** Puts the current frosted ground into the glass of [layer] without painting the card (see [GlassSplit]). */
    private fun recompose(layer: CardLayer, a: CardArgs, s: Float) {
        val bmp = layer.bmp ?: return
        val sp = layer.split ?: split(layer, a, s)!!.also { layer.split = it }
        val ground = backdropBmp
        if (sp.w == 0 || ground == null) {
            layer.backdrop = backdropVersion
            return
        }
        val n = sp.w * sp.h
        val box = sp.box?.takeIf { it.width == sp.w && it.height == sp.h } ?: Bitmap.createBitmap(sp.w, sp.h, Bitmap.Config.ARGB_8888).also { sp.box = it }
        // the frosted ground under this part of the layer, sampled as the glass samples it (the poster rectangle at the device scale, bilinear)
        box.eraseColor(0)
        val c = android.graphics.Canvas(box)
        c.translate(-(layer.x0 + sp.l).toFloat(), -(layer.y0 + sp.t).toFloat())
        c.scale(s, s)
        paint.reset()
        paint.isFilterBitmap = true
        c.drawBitmap(ground, null, RectF(0f, 0f, a.style.pw.toFloat(), a.style.ph.toFloat()), paint)
        val px = composePx?.takeIf { it.size >= n } ?: IntArray(n).also { composePx = it }
        val buf = IntBuffer.wrap(px, 0, n)
        box.copyPixelsToBuffer(buf)
        val under = sp.under
        val see = sp.see
        for (i in 0 until n) {
            val k = see[i].toInt() and 255
            val u = under[i]
            if (k == 0) {
                px[i] = u
                continue
            }
            val g = px[i]
            val al = u ushr 24
            val c0 = min(al, (u and 255) + ((g and 255) * k + 127) / 255)
            val c1 = min(al, ((u ushr 8) and 255) + (((g ushr 8) and 255) * k + 127) / 255)
            val c2 = min(al, ((u ushr 16) and 255) + (((g ushr 16) and 255) * k + 127) / 255)
            px[i] = (al shl 24) or (c2 shl 16) or (c1 shl 8) or c0
        }
        buf.rewind()
        if (layer.dof > 0.35f) {
            PosterBlur.blur(px, sp.w, sp.h, layer.dof * s, clamp = false, work = blurScratch.second(n))
            bmp.copyPixelsFromBuffer(buf)
        } else {
            box.copyPixelsFromBuffer(buf)
            paint.reset()
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
            android.graphics.Canvas(bmp).drawBitmap(box, sp.l.toFloat(), sp.t.toFloat(), paint)
            paint.reset()
        }
        layer.backdrop = backdropVersion
        cardRecomposes++
    }

    /** The person (the cut-out), over everything behind. */
    private fun drawSubject(scope: DrawScope, f: PosterFrame, photo: PosterPhoto, s: Float) {
        val chain = assets.cutChain(photo, f.photoVersion) ?: return
        val fz = PosterPhotoGeometry.scale(photo.w, photo.h, f.photoZoom, f.ratio)
        val pw = photo.w * fz
        val ph = photo.h * fz
        val left = f.ratio.w / 2f + f.photoPanX - pw / 2f
        val top = f.ratio.h / 2f + f.photoPanY - ph / 2f
        scope.drawIntoCanvas { c ->
            val img = chain.forScale(fz * s)
            paint.reset()
            paint.isFilterBitmap = true
            paint.isAntiAlias = true
            c.nativeCanvas.drawBitmap(img, null, RectF(left, top, left + pw, top + ph), paint)
        }
    }

    /**
     * The person's soft shadow along the key light, onto the cards only: drawn into the layer of the cards with SRC_ATOP, so it darkens the
     * glass where a card is and nothing where there is none (the photo behind keeps its own light; round 4.3.5: the dark halo the shadow
     * used to leave around the person on the photo looked pasted).
     */
    private fun drawPersonShadow(canvas: android.graphics.Canvas, f: PosterFrame, photo: PosterPhoto, level: Int, s: Float) {
        val cell = if (level == 0) 6 else 12
        val k = "${photo.uid}/${f.photoVersion}/${f.photoZoom}/${f.photoPanX}/${f.photoPanY}/$cell/${f.ratio}"
        if (k != shadowKey) {
            shadow?.recycle()
            shadow = SilhouetteShadow.build(photo, f.photoZoom, f.photoPanX, f.photoPanY, cell, f.ratio)
            shadowKey = k
        }
        val sh = shadow ?: return
        val sdx = cos(Math.toRadians(f.shadowAngle.toDouble())).toFloat() * 26f
        val sdy = sin(Math.toRadians(f.shadowAngle.toDouble())).toFloat() * 26f
        canvas.save()
        canvas.scale(s, s)
        paint.reset()
        paint.isFilterBitmap = true
        paint.alpha = (0.6f * 255f).roundToInt()
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        canvas.drawBitmap(sh, null, RectF(sdx, sdy, sdx + sh.width * cell.toFloat(), sdy + sh.height * cell.toFloat()), paint)
        paint.reset()
        canvas.restore()
    }

    private fun glowOn(k: GlowKey): Boolean = k.glow > 0.01f && k.colors.isNotEmpty()

    /**
     * The ground at the device size. Without a photo: the studio backdrop (PosterFx.backdrop, per pixel with a dither, the gels in it). With a
     * photo: the photo and the covers' faint gel light. The gels (radial gradients, screen) change every ground pixel by `ground * keep + add`
     * per channel; while only the photo moves those two layers are cached and laid on with a multiply and an add (two bitmap draws) instead of
     * the gradients. They are built when the same glow is seen twice in a row (a gesture on the photo or a ground slider) or by [warm].
     */
    private fun renderBase(b: Bitmap, k: BaseKey, s: Float, photo: PosterPhoto?, cover: Bitmap? = null) {
        if (photo == null && k.photo == -1) {
            val px = pixels(b.width * b.height)
            PosterFx.backdrop(px, b.width, b.height, k.tone, k.colors, k.glow, k.ratio)
            b.setPixels(px, 0, b.width, 0, 0, b.width, b.height)
            return
        }
        b.eraseColor(android.graphics.Color.BLACK)
        val canvas = android.graphics.Canvas(b)
        if (photo == null) paintStyledGround(b, canvas, k, cover)
        val gk = GlowKey(b.width, b.height, k.glow, k.colors, k.level, k.ratio)
        val on = glowOn(gk)
        if (on && glowFor != gk && lastGlowKey == gk) buildGlow(gk, s)
        val cached = on && glowFor == gk
        draw(canvas, b.width, b.height) {
            scale(s, s, Offset.Zero) {
                if (photo != null) drawPhoto(this, photo, k, s)
                if (on && !cached) glow(k.colors, k.glow, k.level, k.ratio)
            }
        }
        if (cached) {
            paint.reset()
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            canvas.drawBitmap(glowKeep!!, 0f, 0f, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            canvas.drawBitmap(glowAdd!!, 0f, 0f, paint)
            paint.reset()
        }
        lastGlowKey = gk
    }

    /** The glow over black (what it adds) and over white minus that (how much of the ground it keeps), both opaque. False when [stop] said so. */
    private fun buildGlow(k: GlowKey, s: Float, stop: () -> Boolean = { false }): Boolean {
        val w = k.bw
        val h = k.bh
        val add = glowAdd?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { glowAdd?.recycle(); glowAdd = it }
        val keep = glowKeep?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { glowKeep?.recycle(); glowKeep = it }
        glowFor = null
        add.eraseColor(android.graphics.Color.BLACK)
        draw(android.graphics.Canvas(add), w, h) { scale(s, s, Offset.Zero) { glow(k.colors, k.glow, k.level, k.ratio) } }
        if (stop()) return false
        keep.eraseColor(android.graphics.Color.WHITE)
        draw(android.graphics.Canvas(keep), w, h) { scale(s, s, Offset.Zero) { glow(k.colors, k.glow, k.level, k.ratio) } }
        // row by row: no picture-sized work array is kept for this
        val a = IntArray(w)
        val o = IntArray(w)
        for (y in 0 until h) {
            add.getPixels(a, 0, w, 0, y, w, 1)
            keep.getPixels(o, 0, w, 0, y, w, 1)
            for (i in 0 until w) {
                val p = a[i]
                val q = o[i]
                val r = (((q shr 16) and 255) - ((p shr 16) and 255)).coerceIn(0, 255)
                val g = (((q shr 8) and 255) - ((p shr 8) and 255)).coerceIn(0, 255)
                val bl = ((q and 255) - (p and 255)).coerceIn(0, 255)
                o[i] = (255 shl 24) or (r shl 16) or (g shl 8) or bl
            }
            keep.setPixels(o, 0, w, 0, y, w, 1)
        }
        glowFor = k
        glowBuilds++
        return true
    }

    /**
     * The ground of a styled background ([BaseKey.bg]): "Kabur" = the first cover, cropped to fill and blurred hard, a little darker; "Gradien" and
     * "Polos" = the covers' colours as a deep gradient or one deep tone ([PosterFx.gradientGround], [PosterFx.solidGround]).
     */
    private fun paintStyledGround(b: Bitmap, canvas: android.graphics.Canvas, k: BaseKey, cover: Bitmap?) {
        val w = b.width
        val h = b.height
        if (k.bg == PosterBackground.KABUR && cover != null) {
            val sw = max(8, w / 8)
            val sh = max(8, h / 8)
            val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            val sc = android.graphics.Canvas(small)
            paint.reset()
            paint.isFilterBitmap = true
            // the cover fills the picture (cropped, never stretched) and is a little larger than that, so the blur has no clear edge
            val fit = max(sw / cover.width.toFloat(), sh / cover.height.toFloat()) * 1.25f
            val cw = cover.width * fit
            val ch = cover.height * fit
            sc.drawBitmap(cover, null, RectF((sw - cw) / 2f, (sh - ch) / 2f, (sw + cw) / 2f, (sh + ch) / 2f), paint)
            PosterBlur.blurBitmap(small, (if (k.level == 0) 9f else 6f) * (sw / 135f).coerceAtLeast(0.5f), clamp = true, scratch = blurScratch)
            paint.reset()
            paint.isFilterBitmap = true
            canvas.drawBitmap(small, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), paint)
            small.recycle()
            paint.reset()
            paint.color = Color.Black.copy(alpha = 0.4f).toArgb()
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            paint.reset()
            return
        }
        val px = pixels(w * h)
        if (k.bg == PosterBackground.POLOS) PosterFx.solidGround(px, w, h, k.tints, k.tone) else PosterFx.gradientGround(px, w, h, k.tints, k.tone)
        b.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun drawPhoto(scope: DrawScope, photo: PosterPhoto, k: BaseKey, s: Float) {
        val chain = assets.photoChain(photo)
        val f = PosterPhotoGeometry.scale(photo.w, photo.h, k.zoom, k.ratio)
        val w = photo.w * f
        val h = photo.h * f
        val left = k.ratio.w / 2f + k.panX - w / 2f
        val top = k.ratio.h / 2f + k.panY - h / 2f
        val partial = left > 0.5f || top > 0.5f || left + w < k.ratio.w - 0.5f || top + h < k.ratio.h - 0.5f
        scope.drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            if (partial) {
                // the rest is a blurred, darkened, enlarged copy of the photo (never stretched edge pixels), made small and blurred on the CPU
                val cover = PosterPhotoGeometry.scale(photo.w, photo.h, 1f, k.ratio) * 1.3f
                val cw = photo.w * cover
                val ch = photo.h * cover
                val sw = max(8, (k.ratio.w * s / 8f).roundToInt())
                val sh = max(8, (k.ratio.h * s / 8f).roundToInt())
                val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
                val q = sw / k.ratio.w.toFloat()
                val smallCanvas = android.graphics.Canvas(small)
                paint.reset()
                paint.isFilterBitmap = true
                smallCanvas.drawBitmap(chain.forScale(cover * q), null, RectF((k.ratio.w - cw) / 2f * q, (k.ratio.h - ch) / 2f * q, (k.ratio.w + cw) / 2f * q, (k.ratio.h + ch) / 2f * q), paint)
                PosterBlur.blurBitmap(small, if (k.level == 0) 38f * q else 20f * q, clamp = true, scratch = blurScratch)
                nc.drawBitmap(small, null, RectF(0f, 0f, k.ratio.w.toFloat(), k.ratio.h.toFloat()), paint)
                small.recycle()
                paint.reset()
                paint.color = Color.Black.copy(alpha = 0.42f).toArgb()
                nc.drawRect(0f, 0f, k.ratio.w.toFloat(), k.ratio.h.toFloat(), paint)
                // a photo smaller than the poster sits on it like a framed print: rounded corners, a soft shadow
                paint.reset()
                paint.isAntiAlias = true
                paint.color = Color.Black.copy(alpha = 0.55f).toArgb()
                if (k.level < 2) paint.maskFilter = blurMask(26f)
                nc.drawRoundRect(RectF(left + 4f, top + 18f, left + w - 4f, top + h + 10f), 34f, 34f, paint)
                paint.maskFilter = null
                nc.save()
                val clip = android.graphics.Path().apply { addRoundRect(RectF(left, top, left + w, top + h), 34f, 34f, android.graphics.Path.Direction.CW) }
                nc.clipPath(clip)
            }
            val sigma = if (k.blur > 0.01f && k.level < 2) k.blur * 36f * s else 0f
            paint.reset()
            paint.isFilterBitmap = true
            if (sigma < 0.5f) {
                nc.drawBitmap(chain.forScale(f * s), null, RectF(left, top, left + w, top + h), paint)
            } else {
                // the blur slider: the visible part of the photo drawn small (the blur hides it), blurred on the CPU, laid back
                val q = (3f / sigma).coerceIn(0.125f, 1f)
                val vx0 = max(0f, left)
                val vy0 = max(0f, top)
                val vx1 = min(k.ratio.w.toFloat(), left + w)
                val vy1 = min(k.ratio.h.toFloat(), top + h)
                if (vx1 > vx0 && vy1 > vy0) {
                    val tw = max(4, ((vx1 - vx0) * s * q).roundToInt())
                    val th = max(4, ((vy1 - vy0) * s * q).roundToInt())
                    val tmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                    val tc = android.graphics.Canvas(tmp)
                    val kx = tw / (vx1 - vx0)
                    val ky = th / (vy1 - vy0)
                    tc.drawBitmap(chain.forScale(f * kx), null, RectF((left - vx0) * kx, (top - vy0) * ky, (left + w - vx0) * kx, (top + h - vy0) * ky), paint)
                    PosterBlur.blurBitmap(tmp, sigma * q, clamp = true, scratch = blurScratch)
                    nc.drawBitmap(tmp, null, RectF(vx0, vy0, vx1, vy1), paint)
                    tmp.recycle()
                }
            }
            if (partial) nc.restore()
            if (k.dim > 0.001f) {
                paint.reset()
                paint.color = Color.Black.copy(alpha = k.dim.coerceIn(0f, 0.7f)).toArgb()
                nc.drawRect(0f, 0f, k.ratio.w.toFloat(), k.ratio.h.toFloat(), paint)
            }
        }
    }

    /** A small, frosted copy of the ground: what the glass of a card shows through its ink (an eighth of the size, halved three times, then blurred). */
    private fun renderBackdrop(src: Bitmap, frost: Float): Bitmap {
        var cur = src
        repeat(3) {
            val next = Bitmap.createScaledBitmap(cur, max(8, cur.width / 2), max(8, cur.height / 2), true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        val small = if (cur.isMutable) cur else cur.copy(Bitmap.Config.ARGB_8888, true).also { cur.recycle() }
        PosterBlur.blurBitmap(small, 0.3f + 2.9f * frost.coerceIn(0f, 1f), clamp = true)
        return small
    }

    fun release() {
        base?.recycle()
        scene?.recycle()
        shadow?.recycle()
        cardsOnly?.recycle()
        glowAdd?.recycle()
        glowKeep?.recycle()
        splitBmp?.recycle()
        cardLayers.forEach {
            it?.bmp?.recycle()
            it?.split?.box?.recycle()
        }
        cardLayers.clear()
        bloomBuffers.release()
        blurScratch.release()
        sprites.release()
        spots = emptyList()
        spotsOf = -1
        base = null
        scene = null
        shadow = null
        cardsOnly = null
        field = null
        fieldKey = null
        postPx = null
        composePx = null
        backdrop = null
        backdropBmp = null
        backdropFor = null
        baseKey = null
        glowAdd = null
        glowKeep = null
        glowFor = null
        lastGlowKey = null
        splitBmp = null
        light = null
    }
}

/** The export size. */
object PosterExportSize {
    const val W = POSTER_W
    const val H = POSTER_H

    /** The export size of a shape: its own pixels (1080 on the short side, 1920 on the long side of 9:16 and 16:9). */
    fun of(ratio: PosterRatio): Pair<Int, Int> = ratio.w to ratio.h
}

/** Renders the poster at the export size of its shape (1080 x 1920 for 9:16) with its own renderer (full quality). Heavy: call off the main thread. */
fun renderPosterBitmap(context: Context, frame: PosterFrame, assets: PosterAssets): Bitmap {
    val r = PosterRenderer(context, assets)
    try {
        return r.render(frame, frame.ratio.w, frame.ratio.h, level = 0)
    } finally {
        r.release()
    }
}
