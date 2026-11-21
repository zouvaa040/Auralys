/**
 * Auralys - Dynamic Island overlay UI
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.constants.DynamicIslandButtonStyle
import com.metrolist.music.constants.DynamicIslandCompactContent
import com.metrolist.music.constants.DynamicIslandCoverShape
import com.metrolist.music.constants.DynamicIslandEqColor
import com.metrolist.music.constants.DynamicIslandEqStyle
import com.metrolist.music.constants.DynamicIslandGlowColor
import com.metrolist.music.constants.DynamicIslandLayout
import com.metrolist.music.constants.DynamicIslandStyle
import com.metrolist.music.utils.makeTimeString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Base dimensions of the island in dp (multiplied by [DynamicIslandUiState.scale]). */
object DynamicIslandDimens {
    const val EXPANDED_WIDTH = 360f

    /** Room kept around the expanded card (inside the overlay window) for the glow ring. */
    const val GLOW_PAD = 14f

    // The Liquid pill's contact shadow while compact. The compact window is the pill plus
    // IslandWindowGeometry.COMPACT_SLOP_DP (2 dp) for every style (a touchable window takes every touch in its frame, so a
    // wider frame blocks the app next to the island, review 2026-11-12 finding 29): the shadow must end inside those 2 dp.
    // Three thin rings (no blur at run time) moved down a little; the open card keeps the baked soft shadow.
    const val COMPACT_SHADOW_DROP = 0.4f
    const val COMPACT_SHADOW_STROKE = 0.5f
    val COMPACT_SHADOW_SPREADS = floatArrayOf(0.3f, 0.75f, 1.2f)
    val COMPACT_SHADOW_ALPHAS = floatArrayOf(0.62f, 0.34f, 0.14f)

    /** How far below the pill the compact shadow reaches, unscaled dp (must stay inside the compact window's frame). */
    fun compactShadowReachDp(): Float = COMPACT_SHADOW_DROP + COMPACT_SHADOW_SPREADS.last() + COMPACT_SHADOW_STROKE / 2f

    /** How long the island takes to shrink into its centre when it goes (the view is removed a little later). */
    const val HIDE_MS = 320

    /** Minimum distance between the expanded card and the screen edges. */
    const val SCREEN_MARGIN = 8f

    // "Gabung" (attached to the top edge): the flared top corners and the room the content keeps from the screen edge, where
    // the status bar and the camera are
    const val COMPACT_FLARE = 16f
    const val ATTACHED_TOP_PAD = 14f
    const val LYRIC_PILL_MIN_WIDTH = 240

    /** Extra room above the content of the open card when it is glued to the screen edge. */
    fun topPad(config: DynamicIslandConfig): Float = if (config.attached) ATTACHED_TOP_PAD else 0f

    /** Distance from the top of the card to its content. */
    fun cardTopPad(config: DynamicIslandConfig): Float = CARD_PAD_TOP + topPad(config)

    fun compactFlare(config: DynamicIslandConfig): Float = if (config.attached) minOf(COMPACT_FLARE, config.pillHeight / 2f) else 0f

    /** Width of the compact pill: a pill that shows a lyric line is at least [LYRIC_PILL_MIN_WIDTH] wide. */
    fun compactWidth(config: DynamicIslandConfig): Int =
        if (config.showLyrics &&
            (config.compactContent == DynamicIslandCompactContent.COVER_LYRICS || config.compactContent == DynamicIslandCompactContent.LYRICS_ONLY)
        ) {
            maxOf(config.pillWidth, LYRIC_PILL_MIN_WIDTH)
        } else {
            config.pillWidth
        }

    // "Card" layout
    const val CARD_PAD_H = 20f
    const val CARD_PAD_TOP = 16f
    const val CARD_PAD_BOTTOM = 12f
    const val CARD_COVER = 52f
    const val CARD_PROGRESS_GAP = 10f
    const val CARD_PROGRESS = 18f
    const val CARD_BUTTONS_GAP = 4f
    const val CARD_BUTTONS = 40f

    // "Compact row" layout
    const val ROW_HEIGHT = 72f
    const val ROW_PAD = 12f
    const val ROW_COVER = 48f
    const val ROW_BUTTON = 36f

    // Shared elements in the compact pill
    const val PILL_COVER = 26f
    const val PILL_INSET = 6f
    const val PILL_EQ_WIDTH = 20f
    const val PILL_EQ_HEIGHT = 14f
    const val PILL_EQ_END = 14f

    // Equaliser in the expanded header
    const val EQ_WIDTH = 22f
    const val EQ_HEIGHT = 16f

    /** Unscaled side of the cover in the expanded card (the "cover size" option). */
    fun cardCover(config: DynamicIslandConfig): Float = CARD_COVER * config.coverScale / 100f

    /** Unscaled side of the cover in the compact-row layout. */
    fun rowCover(config: DynamicIslandConfig): Float = ROW_COVER * config.coverScale / 100f

    /** Unscaled height of the compact-row layout; a bigger cover makes it taller. */
    fun rowHeight(config: DynamicIslandConfig): Float = maxOf(ROW_HEIGHT, rowCover(config) + 2f * ROW_PAD)

    /** Unscaled height of the expanded card for [config]. */
    fun expandedHeight(config: DynamicIslandConfig): Float {
        if (config.layout == DynamicIslandLayout.COMPACT_ROW) return rowHeight(config) + topPad(config)
        var h = cardTopPad(config) + cardCover(config)
        if (config.showProgress) h += CARD_PROGRESS_GAP + CARD_PROGRESS
        if (config.showButtons) h += (if (config.showProgress) CARD_BUTTONS_GAP else CARD_PROGRESS_GAP) + CARD_BUTTONS
        if (!config.showProgress && !config.showButtons) h += 4f
        return h + CARD_PAD_BOTTOM
    }

    /** Unscaled corner radius of the compact pill. */
    fun compactCorner(config: DynamicIslandConfig): Float = mix(8f, config.pillHeight / 2f, config.roundness / 100f)

    /** Unscaled side of the square cover shown inside a compact pill that is [pillHeight] tall. */
    fun pillCover(pillHeight: Int): Float = (pillHeight - 2f * PILL_INSET).coerceIn(12f, PILL_COVER)

    /** Unscaled gap between the compact pill's left edge and its cover. */
    fun pillInsetX(pillHeight: Int): Float = ((pillHeight - pillCover(pillHeight)) / 2f).coerceAtMost(PILL_INSET)

    /** Unscaled corner radius of the expanded card. */
    fun expandedCorner(config: DynamicIslandConfig): Float =
        mix(12f, 40f, config.cardRoundness / 100f).coerceAtMost(expandedHeight(config) / 2f)
}

val DynamicIslandDefaultAccent = Color(0xFF9DB4FF)
val DynamicIslandDefaultCoverTop = Color(0xFF2E3040)
val DynamicIslandDefaultCoverBottom = Color(0xFF15161D)

/** Snapshot-backed state shared between the overlay controller and the composable. */
@Stable
class DynamicIslandUiState {
    var visible by mutableStateOf(false)
    var expanded by mutableStateOf(false)
    var title by mutableStateOf("")
    var artist by mutableStateOf("")
    var artwork by mutableStateOf<ImageBitmap?>(null)

    /**
     * The cover's url, the key of the baked Liquid glass. A key with a null [artwork] means the cover is still loading;
     * both null means the track has no cover.
     */
    var artworkKey by mutableStateOf<String?>(null)

    /** The app's own light/dark choice (Appearance settings); null follows the system. */
    var dark by mutableStateOf<Boolean?>(null)
    var accent by mutableStateOf(DynamicIslandDefaultAccent)
    var isPlaying by mutableStateOf(false)
    var canSkipPrevious by mutableStateOf(true)
    var canSkipNext by mutableStateOf(true)
    var scale by mutableFloatStateOf(1f)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)

    /** The lyrics of the playing song (set by the overlay controller). */
    var lyrics by mutableStateOf(IslandLyrics.Off)

    /** The line being sung (-1 = none yet) and its sung part 0..1: written once per frame while a line is shown, the sweep is read in the draw phase only. */
    var lyricIndex by mutableIntStateOf(-1)
    var lyricSweep by mutableFloatStateOf(0f)

    /** The song's own lyric offset in ms (Lyrics menu of the player). */
    var lyricOffsetMs by mutableLongStateOf(0L)

    /** The open card shows the lyric page instead of the progress bar and the buttons. */
    var lyricPage by mutableStateOf(false)

    /**
     * Lite look: the screen is landscape (a game is probably running) or the phone saves power. The equaliser then ticks
     * about 20 times a second instead of once per screen refresh, the lyric sweep about 30, and the glow is off, so the
     * overlay costs almost nothing next to a game.
     */
    var lite by mutableStateOf(false)

    /**
     * Where the middle of the camera hole at the top of the screen is, in screen px (from the display cut-out; its reported
     * width is not used, phones report a far bigger area than the lens). [cameraKnown] = the phone was asked (the island
     * runs); -1 then means there is no hole there. The settings preview does not know it.
     */
    var cameraKnown by mutableStateOf(false)
    var cameraCenterPx by mutableIntStateOf(-1)

    /** A new song started a moment ago: the lyric pill shows "title - artist" for a few seconds before it follows the lyrics. */
    var titleFlash by mutableStateOf(false)

    /** Look and behaviour options. */
    var config by mutableStateOf(DynamicIslandConfig())

    /** The app's theme colour (glow colour "App accent"). */
    var appAccent by mutableStateOf(DynamicIslandDefaultAccent)

    /** The app's icon family (Appearance > Icon style): this overlay lives outside MetrolistTheme, so it brings its own. */
    var iconFamily by mutableStateOf(IconFamily.DEFAULT)

    /** Deep gradient derived from the artwork (style "Cover colour"). */
    var coverTop by mutableStateOf(DynamicIslandDefaultCoverTop)
    var coverBottom by mutableStateOf(DynamicIslandDefaultCoverBottom)

    /** Final width of the expanded card in dp (already scaled); 0 = default width. */
    var expandedWidth by mutableFloatStateOf(0f)

    /** Compact pill top-left minus expanded card top-left, in px (the morph starts/ends there). */
    var compactShiftX by mutableFloatStateOf(0f)
    var compactShiftY by mutableFloatStateOf(0f)

    /**
     * Where the island and its two overlay windows are (set by the overlay controller; null in the settings previews).
     * The window is the pill while compact and the card while open (see [IslandGeometry]); the overlay root picks the
     * in-window offsets from the size the window REALLY has in each layout pass ([IslandGeometry.windowFor]), so the
     * offsets can never belong to the other window, not even in the frame in which the system resizes it.
     */
    var geometry by mutableStateOf<IslandGeometry?>(null)

    /** The controller wants the open-card window (tie-break when both windows have the same size). Plain field. */
    var expandedWindowWanted: Boolean = false

    /** Card top-left inside the window picked in the last layout pass (kept while the window size matches neither). */
    internal var lastOriginX: Int = 0
    internal var lastOriginY: Int = 0
}

interface DynamicIslandActions {
    fun onTap()
    fun onLongPress()
    fun onOpenApp()
    fun onPrevious()
    fun onTogglePlay()
    fun onNext()

    /** Returns `[position, duration]` in ms, or null when unknown. Polled only while expanded. */
    fun readProgress(): LongArray?

    fun onSeek(positionMs: Long) = Unit

    /** The seek bar started/stopped being dragged. */
    fun onSliderDrag(active: Boolean) = Unit

    /** The collapse morph has fully finished (the card is a compact pill again). */
    fun onCollapseSettled() = Unit

    /** Switch the open card between the player page and the lyric page. */
    fun onToggleLyrics() = Unit

    /** Where the song is, for the lyrics (null in the settings preview). */
    fun playhead(): LyricsPlayheadSource? = null
}

object NoOpDynamicIslandActions : DynamicIslandActions {
    override fun onTap() = Unit
    override fun onLongPress() = Unit
    override fun onOpenApp() = Unit
    override fun onPrevious() = Unit
    override fun onTogglePlay() = Unit
    override fun onNext() = Unit
    override fun readProgress(): LongArray? = null
}

/** The lite look: the equaliser moves every 50 ms, the lyric sweep every 33 ms. */
private const val LITE_EQ_MS = 50L
private const val LITE_LYRIC_MS = 33L
private const val EQ_PERIOD_MS = 2400
private const val EQ_EASE_MS = 450
private const val COVER_SPIN_MS = 14_000
private const val WAVE_SEGMENTS = 16
private val EqFrequencies = floatArrayOf(2f, 3f, 1f, 2f, 3f)
private val EqOffsets = floatArrayOf(0f, 0.27f, 0.5f, 0.13f, 0.71f)
private val EqStill = floatArrayOf(0.35f, 0.65f, 0.45f, 0.75f, 0.4f)
private val GlowAlphas = floatArrayOf(0.55f, 0.28f, 0.12f)
private const val TWO_PI = 6.2831855f
private const val PI_F = 3.1415927f

private val GlowFallback = Color.White.copy(alpha = 0.35f)
private val TabularDigits = TextStyle(fontFeatureSettings = "tnum")
private val RoundedCover = RoundedCornerShape(percent = 27)

private fun mix(
    a: Float,
    b: Float,
    t: Float,
): Float = a + (b - a) * t

/** Resolved colours of one island style. */
@Immutable
private class IslandColors(
    val background: Brush,
    val content: Color,
    val rim: Brush,
    val placeholder: Color,
    /** Colour of the sheen that sweeps across the card when it opens (transparent = none). */
    val sheen: Color,
    val glass: Boolean,
    val light: Boolean,
    /** The blurred cover shows through the background (Liquid style). */
    val liquid: Boolean = false,
    /** The user's accent colour for the progress bar; null = the text colour. */
    val accent: Color? = null,
) {
    val secondary = content.copy(alpha = 0.65f)
    val track = content.copy(alpha = 0.2f)
    val fill = accent ?: content.copy(alpha = 0.92f)
}

/** Ink of the Liquid style on a light glass (the app's onSurface in the light theme). */
private val IslandInkDark = Color(0xFF16151A)

/** What the Liquid style takes from the glass itself: the appearance it was baked for and the ink that reads on it. */
@Immutable
private class LiquidLook(
    val dark: Boolean,
    val ink: Color,
)

private val DefaultLiquidLook = LiquidLook(dark = true, ink = Color.White)

/** The style's own colours with the user's text, accent and border colours laid over them. */
private fun islandColors(
    config: DynamicIslandConfig,
    coverTop: Color,
    coverBottom: Color,
    look: LiquidLook = DefaultLiquidLook,
): IslandColors {
    val base = styleColors(config, coverTop, coverBottom, look)
    val text = config.textColor.takeIf { it != 0 }?.let { Color(it) }
    val accent = config.accentColor.takeIf { it != 0 }?.let { Color(it) }
    val edge = config.borderColor.takeIf { it != 0 }?.let { SolidColor(Color(it)) }
    if (text == null && accent == null && edge == null) return base
    return IslandColors(
        background = base.background,
        content = text ?: base.content,
        rim = edge ?: base.rim,
        placeholder = base.placeholder,
        sheen = base.sheen,
        glass = base.glass,
        light = if (text != null) text.luminance() < 0.4f else base.light,
        liquid = base.liquid,
        accent = accent,
    )
}

private fun styleColors(
    config: DynamicIslandConfig,
    coverTop: Color,
    coverBottom: Color,
    look: LiquidLook = DefaultLiquidLook,
): IslandColors =
    when (config.style) {
        DynamicIslandStyle.LIQUID -> {
            // the glass itself is the baked skin (IslandGlass); here are its ink, the rim and what shows until it is ready
            val rim = IslandGlass.rim(look.dark, config.shine)
            val veil = Color(0xFF000000.toInt() or IslandGlass.veilRgb(look.dark))
            IslandColors(
                background = SolidColor(veil.copy(alpha = if (look.dark) IslandGlass.DARK_VEIL_ALPHA else IslandGlass.LIGHT_VEIL_ALPHA)),
                content = look.ink,
                // glassRim(): bright where the light enters (top-left), a glint where it leaves (bottom-right)
                rim =
                    Brush.linearGradient(
                        colors =
                            listOf(
                                Color.White.copy(alpha = rim.hi),
                                Color.White.copy(alpha = rim.lo),
                                Color.White.copy(alpha = rim.lo),
                                Color.White.copy(alpha = rim.glint),
                            ),
                        start = Offset.Zero,
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                    ),
                placeholder = look.ink.copy(alpha = 0.14f),
                sheen = Color.White.copy(alpha = if (look.dark) 0.2f else 0.45f),
                glass = true,
                light = look.ink.luminance() < 0.4f,
                liquid = true,
            )
        }
        DynamicIslandStyle.CUSTOM -> {
            val top = Color(config.customTop)
            val bottom = Color(config.customBottom)
            val dark = lerp(top, bottom, 0.5f).luminance() <= 0.5f
            val content = if (dark) Color.White else Color(0xFF111114)
            IslandColors(
                background = Brush.verticalGradient(listOf(top, bottom)),
                content = content,
                rim = SolidColor((if (dark) Color.White else Color.Black).copy(alpha = 0.12f)),
                placeholder = content.copy(alpha = 0.14f),
                sheen = if (dark) Color.White.copy(alpha = 0.1f) else Color.Transparent,
                glass = false,
                light = !dark,
            )
        }
        DynamicIslandStyle.CLASSIC ->
            IslandColors(
                background = SolidColor(Color.Black),
                content = Color.White,
                rim = SolidColor(Color.White.copy(alpha = 0.09f)),
                placeholder = Color(0xFF2A2A2E),
                sheen = Color.White.copy(alpha = 0.09f),
                glass = false,
                light = false,
            )
        DynamicIslandStyle.GLASS ->
            IslandColors(
                background = Brush.verticalGradient(listOf(Color(0xE6383A43), Color(0xD91B1C22), Color(0xE30F1014))),
                content = Color.White,
                rim =
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.38f), Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.16f)),
                    ),
                placeholder = Color.White.copy(alpha = 0.12f),
                sheen = Color.White.copy(alpha = 0.16f),
                glass = true,
                light = false,
            )
        DynamicIslandStyle.COVER -> {
            val darkText = lerp(coverTop, coverBottom, 0.5f).luminance() > 0.5f
            val content = if (darkText) Color(0xFF111114) else Color.White
            IslandColors(
                background = Brush.verticalGradient(listOf(coverTop, coverBottom)),
                content = content,
                rim = SolidColor(Color.White.copy(alpha = 0.14f)),
                placeholder = content.copy(alpha = 0.14f),
                sheen = Color.White.copy(alpha = 0.14f),
                glass = false,
                light = darkText,
            )
        }
        DynamicIslandStyle.LIGHT ->
            IslandColors(
                background = Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF0F1F4))),
                content = Color(0xFF121216),
                rim = SolidColor(Color.Black.copy(alpha = 0.08f)),
                placeholder = Color(0xFFE2E3E8),
                sheen = Color.Transparent,
                glass = false,
                light = true,
            )
    }

/** Accent that stays readable on a light background. */
private fun readableAccent(
    accent: Color,
    light: Boolean,
): Color = if (light && accent.luminance() > 0.4f) lerp(accent, Color.Black, 0.45f) else accent

/**
 * Rounded rectangle whose corner radius follows its height: [c0] at height [h0] (the compact pill)
 * and [c1] at height [h1] (the expanded card). All values in dp. Stable, so the clip never has to be
 * re-created during the morph.
 */
private class IslandShape(
    private val h0: Float,
    private val h1: Float,
    private val c0: Float,
    private val c1: Float,
    /** Flared top corners, dp at the compact and at the open size (0 = a plain rounded rectangle). Only the "Gabung" look uses them. */
    private val flare0: Float = 0f,
    private val flare1: Float = 0f,
) : Shape {
    private fun fraction(
        heightPx: Float,
        density: Density,
    ): Float = if (h1 > h0) ((heightPx / density.density - h0) / (h1 - h0)).coerceIn(0f, 1f) else 1f

    fun cornerPx(
        heightPx: Float,
        density: Density,
    ): Float = (mix(c0, c1, fraction(heightPx, density)) * density.density).coerceIn(0f, heightPx / 2f)

    fun flarePx(
        heightPx: Float,
        density: Density,
    ): Float = (mix(flare0, flare1, fraction(heightPx, density)) * density.density).coerceIn(0f, heightPx / 2f)

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = Outline.Rounded(RoundRect(0f, 0f, size.width, size.height, CornerRadius(cornerPx(size.height, density))))
}

/** The notch path starts this far above the screen edge, so the top row is covered and no seam shows. */
private const val NOTCH_BLEED_PX = 6f

/**
 * The silhouette of the island glued to the top edge, in the node's own pixels (0..[w], 0..[h]): the top corners are
 * concave, the shape flares outward by [flare] and meets the screen edge tangentially; the bottom corners are round
 * ([radius]). The same shape as the notch of the Windows island.
 */
private fun buildNotchPath(
    path: Path,
    w: Float,
    h: Float,
    flare: Float,
    radius: Float,
) {
    path.rewind()
    val f = minOf(flare, h / 2f).coerceAtLeast(0f)
    val r = minOf(radius, minOf(h - f, w / 2f)).coerceAtLeast(0f)
    if (f >= 0.5f) {
        path.moveTo(-f, -NOTCH_BLEED_PX)
        path.lineTo(-f, 0f)
        path.arcTo(Rect(-2f * f, 0f, 0f, 2f * f), -90f, 90f, false)
    } else {
        path.moveTo(0f, -NOTCH_BLEED_PX)
        path.lineTo(0f, 0f)
    }
    path.lineTo(0f, h - r)
    if (r >= 0.5f) path.arcTo(Rect(0f, h - 2f * r, 2f * r, h), 180f, -90f, false)
    path.lineTo(w - r, h)
    if (r >= 0.5f) path.arcTo(Rect(w - 2f * r, h - 2f * r, w, h), 90f, -90f, false)
    path.lineTo(w, maxOf(f, 0f))
    if (f >= 0.5f) {
        path.arcTo(Rect(w, 0f, w + 2f * f, 2f * f), 180f, 90f, false)
        path.lineTo(w + f, -NOTCH_BLEED_PX)
    } else {
        path.lineTo(w, -NOTCH_BLEED_PX)
    }
    path.close()
}

/** Sizes the node from the morph fraction in the layout phase (no recomposition per frame). */
private fun Modifier.morphSize(
    t: State<Float>,
    w0: Float,
    h0: Float,
    w1: Float,
    h1: Float,
): Modifier =
    layout { measurable, _ ->
        val k = t.value.coerceIn(-0.08f, 1.08f)
        val w = mix(w0, w1, k).dp.roundToPx().coerceAtLeast(0)
        val h = mix(h0, h1, k).dp.roundToPx().coerceAtLeast(0)
        val placeable = measurable.measure(Constraints.fixed(w, h))
        layout(w, h) { placeable.place(0, 0) }
    }

/** Moves and sizes a shared element between its compact and expanded frame (dp, layout phase only). */
private fun Modifier.morphBox(
    t: State<Float>,
    x0: Float,
    y0: Float,
    w0: Float,
    h0: Float,
    x1: Float,
    y1: Float,
    w1: Float,
    h1: Float,
): Modifier =
    layout { measurable, constraints ->
        val k = t.value.coerceIn(-0.08f, 1.08f)
        val w = mix(w0, w1, k).dp.roundToPx().coerceAtLeast(0)
        val h = mix(h0, h1, k).dp.roundToPx().coerceAtLeast(0)
        val placeable = measurable.measure(Constraints.fixed(w, h))
        layout(w.coerceAtMost(constraints.maxWidth), h.coerceAtMost(constraints.maxHeight)) {
            placeable.place(mix(x0, x1, k).dp.roundToPx(), mix(y0, y1, k).dp.roundToPx())
        }
    }

/** Lays the child out [padDp] larger than the parent on every side (for the glow halo). */
private fun Modifier.outset(padDp: Float): Modifier =
    layout { measurable, constraints ->
        val pad = padDp.dp.roundToPx()
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val placeable = measurable.measure(Constraints.fixed(w + pad * 2, h + pad * 2))
        layout(w, h) { placeable.place(-pad, -pad) }
    }

/** Root content of the overlay window: places the island at the controller-computed origin. */
@Composable
fun DynamicIslandOverlayContent(
    state: DynamicIslandUiState,
    actions: DynamicIslandActions,
) {
    CompositionLocalProvider(LocalIconFamily provides state.iconFamily) {
        Box(
            Modifier
                .fillMaxSize()
                .layout { measurable, constraints ->
                    // the window's real size in this pass decides which window's offsets apply (see IslandGeometry)
                    val g = state.geometry
                    val window = g?.windowFor(constraints.maxWidth, constraints.maxHeight, state.expandedWindowWanted)
                    if (g != null && window != null) {
                        state.lastOriginX = g.originX(window)
                        state.lastOriginY = g.originY(window)
                    }
                    val ox = state.lastOriginX
                    val oy = state.lastOriginY
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) { placeable.place(ox, oy) }
                },
        ) {
            DynamicIslandPill(state = state, actions = actions)
        }
    }
}

/**
 * The island. [modifier] positions the top-left corner of the *expanded* card; while compact the
 * pill sits at that point plus [DynamicIslandUiState.compactShiftX]/[DynamicIslandUiState.compactShiftY].
 *
 * Everything that follows the morph reads the animated fraction in the layout/draw phase only, so a
 * settled island never recomposes; while collapsed only the equaliser/marquee (when playing) and an
 * opted-in spinning cover animate.
 */
@Composable
fun DynamicIslandPill(
    state: DynamicIslandUiState,
    actions: DynamicIslandActions,
    modifier: Modifier = Modifier,
    animateAppearance: Boolean = true,
) {
    val c = state.config
    val s = state.scale
    val rowLayout = c.layout == DynamicIslandLayout.COMPACT_ROW
    val compactW = DynamicIslandDimens.compactWidth(c) * s
    val compactH = c.pillHeight * s
    val expandedW = if (state.expandedWidth > 0f) state.expandedWidth else c.expandedWidth * s
    val expandedH = DynamicIslandDimens.expandedHeight(c) * s
    val anim = c.animation

    val transition = updateTransition(targetState = state.expanded, label = "dynamicIsland")
    val tState =
        transition.animateFloat(
            transitionSpec = { spring(dampingRatio = anim.morphDamping, stiffness = anim.stiffness) },
            label = "morph",
        ) { expanded -> if (expanded) 1f else 0f }

    // the equaliser leaves the title's way early: it is at its card position by ~65% of the morph and rests exactly there
    // (clamped at 1.08 it sat 8% of its travel past its place, ~13 dp too far right, in every open card); only the
    // spring's own overshoot of the morph goes past it
    val eqT = remember(tState) { derivedStateOf { val k = tState.value; if (k >= 1f) k else minOf(k * 1.5f, 1f) } }
    val settledCollapsed = !transition.currentState && !transition.targetState
    val settledExpanded = transition.currentState && transition.targetState
    LaunchedEffect(settledCollapsed) {
        if (settledCollapsed) actions.onCollapseSettled()
    }

    val appear = remember { Animatable(if (animateAppearance) 0f else 1f) }
    LaunchedEffect(state.visible) {
        if (state.visible) {
            appear.animateTo(1f, spring(dampingRatio = anim.appearDamping, stiffness = anim.stiffness))
        } else {
            // it shrinks into its own centre (the camera, when it sits over it); the way back is the spring above
            appear.animateTo(0f, tween(durationMillis = DynamicIslandDimens.HIDE_MS, easing = FastOutLinearInEasing))
        }
    }

    // Progress ticker: runs only while the card is expanded and shows the progress bar.
    val needsProgress = c.showProgress && !rowLayout
    LaunchedEffect(state.expanded, needsProgress) {
        if (!state.expanded || !needsProgress) return@LaunchedEffect
        while (isActive) {
            actions.readProgress()?.let {
                state.position = it[0]
                state.duration = it[1]
            }
            delay(500)
        }
    }

    // Lyrics: while a line is shown (the open card, or the pill that shows it) the player's position steers the line and
    // its sung part on every frame, through the same clock as the lyrics of the player screen
    val lyricSource = remember(actions) { actions.playhead() }
    val lyricsShown =
        c.showLyrics && state.lyrics.status == IslandLyricsStatus.SYNCED &&
            (
                state.expanded || c.compactContent == DynamicIslandCompactContent.COVER_LYRICS ||
                    c.compactContent == DynamicIslandCompactContent.LYRICS_ONLY
            )
    LaunchedEffect(lyricsShown, state.lyrics, lyricSource, state.visible, state.lite) {
        val source = lyricSource
        if (!lyricsShown || source == null || !state.visible) return@LaunchedEffect
        val lines = state.lyrics.lines
        val follow = { positionMs: Long ->
            val at = positionMs + state.lyricOffsetMs
            val index = IslandLyricsLogic.indexAt(lines, at)
            if (index != state.lyricIndex) state.lyricIndex = index
            val sweep = IslandLyricsLogic.sweepAt(lines, index, at)
            if (sweep != state.lyricSweep && (abs(sweep - state.lyricSweep) > 0.002f || sweep == 0f || sweep == 1f)) state.lyricSweep = sweep
        }
        if (state.lite) {
            // no frame clock: a plain timer wakes the main thread about 30 times a second while the song plays, and not at all
            // while it is paused (then the player's own events say when to look again)
            while (isActive) {
                follow(source.positionMs())
                if (source.isPlaying()) {
                    delay(LITE_LYRIC_MS)
                } else {
                    val seen = source.changeToken()
                    snapshotFlow { source.changeToken() != seen }.first { it }
                }
            }
        } else {
            runLyricsPlayhead(source, LyricsClock(), { null }) { positionMs, _ -> follow(positionMs) }
        }
    }

    // A soft highlight sweeps across the card once each time it opens.
    val sweep = remember { Animatable(1f) }
    LaunchedEffect(state.expanded) {
        if (state.expanded) {
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(durationMillis = 1100, delayMillis = 140, easing = FastOutSlowInEasing))
        } else {
            sweep.snapTo(1f)
        }
    }

    val coverStyle = c.style == DynamicIslandStyle.COVER || c.style == DynamicIslandStyle.LIQUID
    val coverTop by animateColorAsState(
        targetValue = if (coverStyle) state.coverTop else DynamicIslandDefaultCoverTop,
        animationSpec = tween(450),
        label = "coverTop",
    )
    val coverBottom by animateColorAsState(
        targetValue = if (coverStyle) state.coverBottom else DynamicIslandDefaultCoverBottom,
        animationSpec = tween(450),
        label = "coverBottom",
    )
    val compactCorner = DynamicIslandDimens.compactCorner(c) * s
    val expandedCorner = DynamicIslandDimens.expandedCorner(c) * s
    // "Gabung": glued to the top edge with flared top corners (the shape is a path, not a rounded rectangle)
    val attached = c.attached
    // only the small island flares; the open card is a plain shape (square at the top edge, round at the bottom)
    val compactFlare = DynamicIslandDimens.compactFlare(c) * s
    val notchPath = remember { Path() }

    // Liquid style: the glass is baked once per cover and size on a worker thread (see IslandGlass); the ink follows it
    val liquidStyle = c.style == DynamicIslandStyle.LIQUID
    val dark = state.dark ?: isSystemInDarkTheme()
    val latestSkin =
        rememberIslandGlassSkin(
            enabled = liquidStyle,
            artwork = state.artwork,
            artworkKey = state.artworkKey,
            compactWDp = compactW,
            compactHDp = compactH,
            compactCornerDp = compactCorner,
            expandedWDp = expandedW,
            expandedHDp = expandedH,
            expandedCornerDp = expandedCorner,
            shadowPadDp = DynamicIslandDimens.GLOW_PAD * s,
            dark = dark,
            blur = c.blur,
        ).value
    // the skin that is drawn, the one it replaces while it fades in (a new track), and that fade
    val shownSkin = remember { mutableStateOf<IslandGlassSkin?>(null) }
    val fadingSkin = remember { mutableStateOf<IslandGlassSkin?>(null) }
    val skinFade = remember { Animatable(1f) }
    LaunchedEffect(latestSkin) {
        val old = shownSkin.value
        if (latestSkin == null || latestSkin === old) {
            if (latestSkin == null) {
                shownSkin.value = null
                fadingSkin.value = null
            }
            return@LaunchedEffect
        }
        fadingSkin.value = old
        shownSkin.value = latestSkin
        skinFade.snapTo(0f)
        skinFade.animateTo(1f, tween(if (old == null) 240 else 380))
        fadingSkin.value = null
    }
    val darkInk = latestSkin?.darkInk ?: !dark
    val ink by animateColorAsState(
        targetValue = if (darkInk) IslandInkDark else Color.White,
        animationSpec = tween(300),
        label = "liquidInk",
    )
    val colors =
        remember(c, coverTop, coverBottom, dark, ink) {
            islandColors(c, coverTop, coverBottom, LiquidLook(dark, ink))
        }

    val accent = state.accent
    val accentOverride = colors.accent
    val eqTint =
        accentOverride
            ?: when {
                // no cover colour on the glass: the equaliser takes the ink
                colors.liquid -> colors.content
                c.eqColor == DynamicIslandEqColor.COVER -> readableAccent(accent, colors.light)
                else -> colors.content
            }
    val glowTint =
        accentOverride
            ?: when (c.glowColor) {
                DynamicIslandGlowColor.COVER -> if (colors.liquid || accent == DynamicIslandDefaultAccent) GlowFallback else accent
                DynamicIslandGlowColor.ACCENT -> state.appAccent
                DynamicIslandGlowColor.WHITE -> Color.White
            }

    val shape =
        remember(compactH, expandedH, compactCorner, expandedCorner, compactFlare) {
            IslandShape(compactH, expandedH, compactCorner, expandedCorner, compactFlare, 0f)
        }
    val density = LocalDensity.current
    // border: the style's hairline unless the user picked a width; 0 draws none
    val rimStroke =
        remember(density, colors.glass, colors.liquid, c.borderWidth) {
            // the Liquid rim is as thin as the nav pill's highlight (0.9 dp)
            val dp = if (c.borderWidth < 0) (if (colors.liquid) 0.9f else if (colors.glass) 0.8f else 0.6f) else c.borderWidth.toFloat()
            Stroke(width = with(density) { dp.dp.toPx() })
        }
    val shineK = c.shine / 100f
    val sweepBandPx = with(density) { (90f * s).dp.toPx() }
    val sweepBrush =
        remember(colors.sheen, sweepBandPx) {
            Brush.horizontalGradient(
                listOf(Color.Transparent, colors.sheen, Color.Transparent),
                startX = 0f,
                endX = sweepBandPx,
            )
        }
    val glassSheen =
        remember { Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.15f), Color.White.copy(alpha = 0.03f), Color.Transparent)) }
    // glassSheen() of the app's glass: white at the top fading out at half the height
    val liquidSheen =
        remember(dark, c.shine) {
            Brush.verticalGradient(0f to Color.White.copy(alpha = IslandGlass.sheen(dark, c.shine)), 0.5f to Color.Transparent)
        }
    val opacity = c.opacity / 100f

    Box(
        modifier =
            modifier
                .offset {
                    val k = 1f - tState.value
                    IntOffset((state.compactShiftX * k).roundToInt(), (state.compactShiftY * k).roundToInt())
                }.graphicsLayer {
                    val a = appear.value
                    // 0 = a point at the centre of the island, 1 = full size. The compact window is the pill plus 2 dp: an
                    // overshooting spring would be cut flat at its edge, so the scale stops at 1.
                    val sc = a.coerceIn(0f, 1f)
                    scaleX = sc
                    scaleY = sc
                    // it stays solid until it is nearly a point, then fades the last bit
                    alpha = (a / 0.3f).coerceIn(0f, 1f) * opacity
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }.morphSize(tState, compactW, compactH, expandedW, expandedH),
    ) {
        if (colors.liquid && !attached) {
            // shadow under the glass: a tight contact shadow while compact (it fits the 2 dp frame of the compact window), the
            // baked soft shadow as the card opens (the open window keeps GLOW_PAD free around the card)
            IslandShadow(
                skin = shownSkin,
                morph = tState,
                shape = shape,
                dark = dark,
                scale = s,
                modifier = Modifier.outset(DynamicIslandDimens.GLOW_PAD * s),
            )
        }
        if (!settledCollapsed && c.glow && c.glowIntensity > 0 && !attached && !state.lite) {
            IslandGlow(
                color = glowTint,
                shape = shape,
                intensity = c.glowIntensity / 100f,
                scale = s,
                t = tState,
                modifier = Modifier.outset(DynamicIslandDimens.GLOW_PAD * s),
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(if (attached) Modifier else Modifier.clip(shape))
                    .drawBehind {
                        val paintGlass: DrawScope.() -> Unit = {
                            if (colors.liquid) {
                                // the baked glass: the old one first (a new track fades the new one in over it), then the sheen
                                val k = tState.value
                                val reach = if (attached) compactCorner * this.density else 0f
                                val reachOpen = if (attached) expandedCorner * this.density else 0f
                                fadingSkin.value?.let { drawIslandSkin(it, k, 1f, reach, reachOpen) }
                                shownSkin.value?.let { drawIslandSkin(it, k, skinFade.value, reach, reachOpen) }
                                drawRect(liquidSheen)
                            } else if (colors.glass) {
                                drawRect(glassSheen, size = Size(size.width, size.height * 0.55f))
                            }
                        }
                        if (attached) {
                            buildNotchPath(notchPath, size.width, size.height, shape.flarePx(size.height, this), shape.cornerPx(size.height, this))
                            // Everything of the background is cut to the notch: the glass is drawn larger than the island
                            // (its corners are transparent and must fall outside), and without this cut it showed as a
                            // dark rectangle around the pill and the card.
                            clipPath(notchPath) {
                                drawPath(notchPath, colors.background)
                                paintGlass()
                            }
                        } else {
                            drawRect(colors.background)
                            paintGlass()
                        }
                    }.then(
                        if (attached) {
                            // everything inside is cut to the notch (the flares lie outside the node, so a rounded clip would not do)
                            Modifier.drawWithContent { clipPath(notchPath) { this@drawWithContent.drawContent() } }
                        } else {
                            Modifier
                        },
                    ).drawWithContent {
                        drawContent()
                        val v = sweep.value
                        if (v > 0f && v < 1f && colors.sheen.alpha > 0f) {
                            val band = sweepBandPx
                            val x = -band * 2f + (size.width + band * 3f) * v
                            translate(left = x) {
                                rotate(degrees = 18f, pivot = Offset(band / 2f, size.height / 2f)) {
                                    drawRect(sweepBrush, topLeft = Offset(0f, -size.height), size = Size(band, size.height * 3f))
                                }
                            }
                        }
                        if (colors.glass && !colors.liquid && shineK > 0f && !attached) {
                            // glass thickness: a lit bevel just inside the edge (bright where the light enters at the
                            // top-left, dark where it leaves) and a thin highlight along the top edge
                            val corner = shape.cornerPx(size.height, this)
                            val bevel = 2.6.dp.toPx()
                            drawRoundRect(
                                brush =
                                    Brush.linearGradient(
                                        colorStops =
                                            arrayOf(
                                                0f to Color.White.copy(alpha = 0.34f * shineK),
                                                0.4f to Color.Transparent,
                                                0.62f to Color.Transparent,
                                                1f to Color.Black.copy(alpha = 0.28f * shineK),
                                            ),
                                        start = Offset.Zero,
                                        end = Offset(size.width, size.height),
                                    ),
                                topLeft = Offset(bevel / 2f, bevel / 2f),
                                size = Size(size.width - bevel, size.height - bevel),
                                cornerRadius = CornerRadius((corner - bevel / 2f).coerceAtLeast(0f)),
                                style = Stroke(width = bevel),
                            )
                            val lead = corner * 0.7f
                            if (size.width > lead * 2.4f) {
                                val y = 1.6.dp.toPx()
                                drawLine(
                                    brush =
                                        Brush.horizontalGradient(
                                            listOf(Color.Transparent, Color.White.copy(alpha = 0.6f * shineK), Color.Transparent),
                                            startX = lead,
                                            endX = size.width - lead,
                                        ),
                                    start = Offset(lead, y),
                                    end = Offset(size.width - lead, y),
                                    strokeWidth = 1.1.dp.toPx(),
                                    cap = StrokeCap.Round,
                                )
                            }
                        }
                        if (rimStroke.width > 0f && attached) {
                            // half of the stroke falls outside the notch and is cut away: double width keeps the line as set
                            drawPath(notchPath, brush = colors.rim, style = Stroke(width = rimStroke.width * 2f))
                        } else if (rimStroke.width > 0f) {
                            val inset = rimStroke.width / 2f
                            drawRoundRect(
                                brush = colors.rim,
                                topLeft = Offset(inset, inset),
                                size = Size(size.width - rimStroke.width, size.height - rimStroke.width),
                                cornerRadius = CornerRadius((shape.cornerPx(size.height, this) - inset).coerceAtLeast(0f)),
                                style = rimStroke,
                            )
                        }
                    }.pointerInput(actions) {
                        detectTapGestures(
                            onTap = { actions.onTap() },
                            onLongPress = { actions.onLongPress() },
                        )
                    },
        ) {
            if (!settledCollapsed) {
                // Expanded content cross-fades in and grows from the pill's centre.
                val contentModifier =
                    Modifier
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        .graphicsLayer {
                            val k = tState.value
                            // starts almost with the size change so the card is never an empty black box
                            // opening: fades in right with the size; closing: is gone before the card is small, so the
                            // text never gets squeezed into a half-shrunk capsule
                            alpha = if (state.expanded) ((k - 0.42f) / 0.38f).coerceIn(0f, 1f) else ((k - 0.38f) / 0.40f).coerceIn(0f, 1f)
                            val sc = mix(0.9f, 1f, k.coerceIn(0f, 1f))
                            scaleX = sc
                            scaleY = sc
                            val w = expandedW.dp.toPx()
                            val h = expandedH.dp.toPx()
                            val cx = state.compactShiftX + compactW.dp.toPx() / 2f
                            val cy = state.compactShiftY + compactH.dp.toPx() / 2f
                            transformOrigin =
                                TransformOrigin(
                                    if (w > 0f) (cx / w).coerceIn(0f, 1f) else 0.5f,
                                    if (h > 0f) (cy / h).coerceIn(0f, 1f) else 0f,
                                )
                        }
                if (rowLayout) {
                    ExpandedRow(state, actions, c, colors, expandedW, expandedH, contentModifier)
                } else {
                    ExpandedCard(state, actions, c, colors, expandedW, expandedH, contentModifier)
                }
            }

            val lyricOnly = c.compactContent == DynamicIslandCompactContent.LYRICS_ONLY
            val titleMode =
                c.compactContent == DynamicIslandCompactContent.COVER_TITLE ||
                    c.compactContent == DynamicIslandCompactContent.COVER_LYRICS || lyricOnly
            if (titleMode && !settledExpanded) {
                val start =
                    if (lyricOnly) {
                        14f * s
                    } else {
                        (DynamicIslandDimens.pillInsetX(c.pillHeight) + DynamicIslandDimens.pillCover(c.pillHeight) + 10f) * s
                    }
                val textModifier =
                    Modifier
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        .offset(x = start.dp)
                        .requiredSize((compactW - start - 14f * s).coerceAtLeast(0f).dp, compactH.dp)
                        .graphicsLayer { alpha = (1f - tState.value * 3f).coerceIn(0f, 1f) }
                if ((c.compactContent == DynamicIslandCompactContent.COVER_LYRICS || lyricOnly) && c.showLyrics) {
                    // where the camera is in the line: the text is split around it, so the middle is not hidden behind the hole
                    val gap: IslandLyricGap? =
                        if (c.lyricCameraGap) {
                            val d = density.density
                            val pillX = state.geometry?.pill?.x?.toFloat()
                            val textLeft = (pillX ?: 0f) + start * d
                            // the middle of the camera: from the phone, or the middle of the pill where it is not known (the preview)
                            val centre: Float? =
                                if (state.cameraKnown && pillX != null) {
                                    state.cameraCenterPx.takeIf { it >= 0 }?.toFloat()
                                } else {
                                    (pillX ?: 0f) + compactW * d / 2f
                                }
                            if (centre != null) {
                                // the gap is as wide as the user set it (a physical size: it does not follow the island's scale)
                                val half = c.cameraGapDp / 2f * d + 4f * d
                                IslandLyricGap(startDp = (centre - half - textLeft) / d, endDp = (centre + half - textLeft) / d)
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                    CompactLyric(
                        state = state,
                        color = colors.content,
                        marquee = state.isPlaying && settledCollapsed,
                        scale = s,
                        center = lyricOnly,
                        gap = gap,
                        modifier = textModifier,
                    )
                } else {
                    CompactTitle(
                        text = state.title,
                        color = colors.content,
                        marquee = state.isPlaying && settledCollapsed,
                        scale = s,
                        modifier = textModifier,
                    )
                }
            }

            // Shared elements: the cover and the equaliser glide between both layouts.
            val pillCover = DynamicIslandDimens.pillCover(c.pillHeight) * s
            val pillInset = DynamicIslandDimens.pillInsetX(c.pillHeight) * s
            val pillCoverY = (compactH - pillCover) / 2f
            val cover1 = (if (rowLayout) DynamicIslandDimens.rowCover(c) else DynamicIslandDimens.cardCover(c)) * s
            val cover1X = (if (rowLayout) DynamicIslandDimens.ROW_PAD else DynamicIslandDimens.CARD_PAD_H) * s
            val cover1Y =
                if (rowLayout) {
                    (DynamicIslandDimens.topPad(c) + (DynamicIslandDimens.rowHeight(c) - DynamicIslandDimens.rowCover(c)) / 2f) * s
                } else {
                    DynamicIslandDimens.cardTopPad(c) * s
                }
            val coverAlpha0 =
                if (c.compactContent == DynamicIslandCompactContent.EQUALIZER || c.compactContent == DynamicIslandCompactContent.LYRICS_ONLY) 0f else 1f
            if (coverAlpha0 > 0f || !settledCollapsed) {
                val circle = c.coverShape == DynamicIslandCoverShape.CIRCLE
                IslandCover(
                    artwork = state.artwork,
                    circle = circle,
                    spinning = circle && c.spinCover && state.isPlaying,
                    placeholder = colors.placeholder,
                    iconTint = colors.secondary,
                    modifier =
                        Modifier
                            .morphBox(tState, pillInset, pillCoverY, pillCover, pillCover, cover1X, cover1Y, cover1, cover1)
                            .graphicsLayer { alpha = mix(coverAlpha0, 1f, tState.value.coerceIn(0f, 1f)) },
                )
            }

            val eqOnly = c.compactContent == DynamicIslandCompactContent.EQUALIZER
            val eqAlpha0 = if (eqOnly || c.compactContent == DynamicIslandCompactContent.COVER_EQUALIZER) 1f else 0f
            val eqAlpha1 = if (rowLayout && c.showButtons) 0f else 1f
            if (eqAlpha0 > 0f || (!settledCollapsed && eqAlpha1 > 0f)) {
                val eqW0 = DynamicIslandDimens.PILL_EQ_WIDTH * s
                val eqH0 = DynamicIslandDimens.PILL_EQ_HEIGHT * s
                val eqW1 = DynamicIslandDimens.EQ_WIDTH * s
                val eqH1 = DynamicIslandDimens.EQ_HEIGHT * s
                val eq0X = if (eqOnly) (compactW - eqW0) / 2f else compactW - DynamicIslandDimens.PILL_EQ_END * s - eqW0
                val eq0Y = (compactH - eqH0) / 2f
                val eq1X = expandedW - (if (rowLayout) DynamicIslandDimens.ROW_PAD else DynamicIslandDimens.CARD_PAD_H) * s - eqW1
                val eq1Y =
                    if (rowLayout) {
                        ((DynamicIslandDimens.topPad(c) + DynamicIslandDimens.rowHeight(c) / 2f) * s) - eqH1 / 2f
                    } else {
                        (DynamicIslandDimens.cardTopPad(c) + (DynamicIslandDimens.cardCover(c) - DynamicIslandDimens.EQ_HEIGHT) / 2f) * s
                    }
                IslandEqualizer(
                    playing = state.isPlaying,
                    style = c.eqStyle,
                    color = eqTint,
                    lite = state.lite,
                    modifier =
                        Modifier
                            .morphBox(eqT, eq0X, eq0Y, eqW0, eqH0, eq1X, eq1Y, eqW1, eqH1)
                            .graphicsLayer { alpha = mix(eqAlpha0, eqAlpha1, tState.value.coerceIn(0f, 1f)) },
                )
            }
        }
    }
}

@Composable
private fun ExpandedCard(
    state: DynamicIslandUiState,
    actions: DynamicIslandActions,
    config: DynamicIslandConfig,
    colors: IslandColors,
    widthDp: Float,
    heightDp: Float,
    modifier: Modifier = Modifier,
) {
    val s = state.scale
    val density = LocalDensity.current
    val ts = config.textScale / 100f
    val titleSize = with(density) { (16f * s * ts).dp.toSp() }
    val artistSize = with(density) { (13f * s * ts).dp.toSp() }
    val timeSize = with(density) { (11f * s * ts).dp.toSp() }

    Column(
        modifier =
            modifier
                .requiredSize(widthDp.dp, heightDp.dp)
                .padding(
                    start = (DynamicIslandDimens.CARD_PAD_H * s).dp,
                    end = (DynamicIslandDimens.CARD_PAD_H * s).dp,
                    top = (DynamicIslandDimens.cardTopPad(config) * s).dp,
                ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height((DynamicIslandDimens.cardCover(config) * s).dp),
        ) {
            IslandHeaderText(
                state = state,
                showArtist = config.showArtist,
                colors = colors,
                titleSize = titleSize,
                artistSize = artistSize,
                coverSpace = ((DynamicIslandDimens.cardCover(config) + 12f) * s).dp,
                onOpen = actions::onOpenApp,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                lyricDp = if (config.showLyrics && IslandLyricsLogic.miniLineFits(config)) 12f * s * ts else 0f,
            )
            Spacer(Modifier.width(((DynamicIslandDimens.EQ_WIDTH + 10f) * s).dp))
        }

        val pageAvailable = IslandLyricsLogic.pageAvailable(config)
        Crossfade(
            targetState = state.lyricPage && pageAvailable,
            animationSpec = tween(180),
            label = "lyricPage",
        ) { page ->
            if (page) {
                IslandLyricPage(
                    state = state,
                    actions = actions,
                    config = config,
                    ink = colors.content,
                    secondary = colors.secondary,
                )
            } else {
                Column {
                    if (config.showProgress) {
                        Spacer(Modifier.height((DynamicIslandDimens.CARD_PROGRESS_GAP * s).dp))
                        IslandProgressRow(
                            state = state,
                            actions = actions,
                            colors = colors,
                            timeSize = timeSize,
                            modifier = Modifier.fillMaxWidth().height((DynamicIslandDimens.CARD_PROGRESS * s).dp),
                        )
                    }

                    if (config.showButtons) {
                        val gap = if (config.showProgress) DynamicIslandDimens.CARD_BUTTONS_GAP else DynamicIslandDimens.CARD_PROGRESS_GAP
                        Spacer(Modifier.height((gap * s).dp))
                        Box(Modifier.fillMaxWidth().height((DynamicIslandDimens.CARD_BUTTONS * s).dp)) {
                            IslandTransportButtons(
                                state = state,
                                actions = actions,
                                outline = config.buttonStyle == DynamicIslandButtonStyle.OUTLINE,
                                tint = colors.content,
                                buttonSize = (DynamicIslandDimens.CARD_BUTTONS * s).dp,
                                iconSize = (30f * s).dp,
                                playIconSize = (34f * s).dp,
                                horizontalArrangement = Arrangement.spacedBy((30f * s).dp, Alignment.CenterHorizontally),
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (pageAvailable) {
                                Box(Modifier.align(Alignment.CenterEnd)) {
                                    IslandGlyphButton(
                                        icon = R.drawable.lyrics,
                                        label = stringResource(R.string.lyrics),
                                        enabled = true,
                                        tint = colors.content.copy(alpha = colors.content.alpha * 0.62f),
                                        size = (DynamicIslandDimens.CARD_BUTTONS * s).dp,
                                        iconSize = (24f * s).dp,
                                        onClick = actions::onToggleLyrics,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The lyric page of the open card: the lines as a list that slides to the line being sung, in the place of the progress
 * bar and the buttons. A tap anywhere on it, or on the lyric button, goes back.
 */
@Composable
private fun IslandLyricPage(
    state: DynamicIslandUiState,
    actions: DynamicIslandActions,
    config: DynamicIslandConfig,
    ink: Color,
    secondary: Color,
) {
    val s = state.scale
    val ts = config.textScale / 100f
    val density = LocalDensity.current
    val region = IslandLyricsLogic.pageRegionHeight(config)
    val button = (DynamicIslandDimens.CARD_BUTTONS * s).dp
    val label = stringResource(R.string.lyrics)
    Box(
        Modifier
            .fillMaxWidth()
            .height((region * s).dp)
            .padding(top = (DynamicIslandDimens.CARD_PROGRESS_GAP * s).dp)
            .clickable(interactionSource = null, indication = null, onClickLabel = label, onClick = actions::onToggleLyrics),
    ) {
        val room = Modifier.fillMaxSize().padding(end = button)
        val lines = state.lyrics.lines
        if (state.lyrics.status == IslandLyricsStatus.SYNCED && lines.isNotEmpty()) {
            IslandLyricRows(
                lines = lines,
                index = state.lyricIndex,
                sweep = { state.lyricSweep },
                soft = !state.lite,
                ink = ink,
                fontSize = with(density) { (15f * s * ts).dp.toSp() },
                spacing = (22f * s * ts).dp,
                modifier = room,
            )
        } else {
            Box(room, contentAlignment = Alignment.CenterStart) {
                Text(
                    text =
                        stringResource(
                            when (state.lyrics.status) {
                                IslandLyricsStatus.PLAIN -> R.string.dynamic_island_lyrics_plain
                                IslandLyricsStatus.NONE -> R.string.dynamic_island_lyrics_none
                                else -> R.string.dynamic_island_lyrics_loading
                            },
                        ),
                    color = secondary,
                    fontSize = with(density) { (13f * s * ts).dp.toSp() },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.align(Alignment.CenterEnd)) {
            IslandGlyphButton(
                icon = R.drawable.lyrics,
                label = label,
                enabled = true,
                tint = ink,
                size = button,
                iconSize = (24f * s).dp,
                onClick = actions::onToggleLyrics,
            )
        }
    }
}

@Composable
private fun ExpandedRow(
    state: DynamicIslandUiState,
    actions: DynamicIslandActions,
    config: DynamicIslandConfig,
    colors: IslandColors,
    widthDp: Float,
    heightDp: Float,
    modifier: Modifier = Modifier,
) {
    val s = state.scale
    val density = LocalDensity.current
    val ts = config.textScale / 100f
    val titleSize = with(density) { (15f * s * ts).dp.toSp() }
    val artistSize = with(density) { (12.5f * s * ts).dp.toSp() }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .requiredSize(widthDp.dp, heightDp.dp)
                .padding(start = (DynamicIslandDimens.ROW_PAD * s).dp, end = (DynamicIslandDimens.ROW_PAD * s).dp, top = (DynamicIslandDimens.topPad(config) * s).dp),
    ) {
        IslandHeaderText(
            state = state,
            showArtist = config.showArtist,
            colors = colors,
            titleSize = titleSize,
            artistSize = artistSize,
            coverSpace = ((DynamicIslandDimens.rowCover(config) + 12f) * s).dp,
            onOpen = actions::onOpenApp,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            lyricDp = if (config.showLyrics && IslandLyricsLogic.miniLineFits(config)) 12f * s * ts else 0f,
        )
        if (config.showButtons) {
            Spacer(Modifier.width((8f * s).dp))
            IslandTransportButtons(
                state = state,
                actions = actions,
                outline = config.buttonStyle == DynamicIslandButtonStyle.OUTLINE,
                tint = colors.content,
                buttonSize = (DynamicIslandDimens.ROW_BUTTON * s).dp,
                iconSize = (24f * s).dp,
                playIconSize = (28f * s).dp,
                horizontalArrangement = Arrangement.spacedBy((2f * s).dp),
                modifier = Modifier.fillMaxHeight(),
            )
        } else {
            Spacer(Modifier.width(((DynamicIslandDimens.EQ_WIDTH + 10f) * s).dp))
        }
    }
}

/** Title + artist; the area (including the space under the shared cover) opens the app. */
@Composable
private fun IslandHeaderText(
    state: DynamicIslandUiState,
    showArtist: Boolean,
    colors: IslandColors,
    titleSize: TextUnit,
    artistSize: TextUnit,
    coverSpace: Dp,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /** Text size in dp (scale applied) of the current lyric line under the artist; 0 = no line. */
    lyricDp: Float = 0f,
) {
    val openLabel = stringResource(R.string.dynamic_island_open_app)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier.clickable(
                interactionSource = null,
                indication = null,
                onClickLabel = openLabel,
                onClick = onOpen,
            ),
    ) {
        Spacer(Modifier.width(coverSpace))
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = state.title,
                color = colors.content,
                fontSize = titleSize,
                lineHeight = titleSize * 1.2f,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showArtist && state.artist.isNotEmpty()) {
                Text(
                    text = state.artist,
                    color = colors.secondary,
                    fontSize = artistSize,
                    lineHeight = artistSize * 1.25f,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (lyricDp > 0f && state.lyrics.status == IslandLyricsStatus.SYNCED && state.lyrics.lines.isNotEmpty()) {
                val lyricSize = with(LocalDensity.current) { lyricDp.dp.toSp() }
                Box(Modifier.fillMaxWidth().height((lyricDp * 1.25f).dp)) {
                    Crossfade(
                        targetState = state.lyricIndex,
                        animationSpec = tween(140),
                        label = "headerLyric",
                    ) { k ->
                        val line = state.lyrics.lines.getOrNull(k)
                        if (line != null) {
                            IslandSweepText(
                                text = line.text,
                                rtl = line.rtl,
                                ink = colors.content,
                                fontSize = lyricSize,
                                fontWeight = FontWeight.Medium,
                                sweep = { if (k == state.lyricIndex) state.lyricSweep else 1f },
                                soft = !state.lite,
                                follow = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** elapsed | bar | -remaining */
@Composable
private fun IslandProgressRow(
    state: DynamicIslandUiState,
    actions: DynamicIslandActions,
    colors: IslandColors,
    timeSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val s = state.scale
    val seekLabel = stringResource(R.string.dynamic_island_seek)
    var seekDrag by remember { mutableFloatStateOf(-1f) }
    val duration = state.duration
    val fraction = if (duration > 0L) (state.position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
    val shownFraction = if (seekDrag >= 0f) seekDrag else fraction
    val shownPosition =
        if (seekDrag >= 0f && duration > 0L) (seekDrag * duration).toLong() else state.position.coerceAtLeast(0L)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Text(
            text = makeTimeString(shownPosition),
            color = colors.secondary,
            fontSize = timeSize,
            style = TabularDigits,
            maxLines = 1,
            textAlign = TextAlign.Start,
            modifier = Modifier.width((40f * s).dp),
        )
        IslandBarSlider(
            value = shownFraction,
            enabled = duration > 0L,
            onValueChange = { seekDrag = it },
            onValueChangeFinished = { f ->
                val d = state.duration
                seekDrag = -1f
                if (d > 0L) {
                    val target = (f * d).toLong()
                    state.position = target
                    actions.onSeek(target)
                }
            },
            onActiveChange = { actions.onSliderDrag(it) },
            description = seekLabel,
            trackColor = colors.track,
            fillColor = colors.fill,
            thumbColor = colors.content,
            scale = s,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        Text(
            text = "-" + makeTimeString((duration - shownPosition).coerceAtLeast(0L)),
            color = colors.secondary,
            fontSize = timeSize,
            style = TabularDigits,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width((44f * s).dp),
        )
    }
}

@Composable
private fun IslandTransportButtons(
    state: DynamicIslandUiState,
    actions: DynamicIslandActions,
    outline: Boolean,
    tint: Color,
    buttonSize: Dp,
    iconSize: Dp,
    playIconSize: Dp,
    horizontalArrangement: Arrangement.Horizontal,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = horizontalArrangement,
        modifier = modifier,
    ) {
        IslandGlyphButton(
            icon = if (outline) R.drawable.ic_island_previous_outline else R.drawable.ic_island_previous,
            label = stringResource(R.string.previous),
            enabled = state.canSkipPrevious,
            tint = tint,
            size = buttonSize,
            iconSize = iconSize,
            onClick = actions::onPrevious,
        )
        val playIcon =
            when {
                state.isPlaying && outline -> R.drawable.ic_island_pause_outline
                state.isPlaying -> R.drawable.ic_island_pause
                outline -> R.drawable.ic_island_play_outline
                else -> R.drawable.ic_island_play
            }
        IslandGlyphButton(
            icon = playIcon,
            label = stringResource(if (state.isPlaying) R.string.pause else R.string.play),
            enabled = true,
            tint = tint,
            size = buttonSize,
            iconSize = playIconSize,
            onClick = actions::onTogglePlay,
        )
        IslandGlyphButton(
            icon = if (outline) R.drawable.ic_island_next_outline else R.drawable.ic_island_next,
            label = stringResource(R.string.next),
            enabled = state.canSkipNext,
            tint = tint,
            size = buttonSize,
            iconSize = iconSize,
            onClick = actions::onNext,
        )
    }
}

/**
 * The pill that shows the lyric line: the line being sung, with its sung part laid over it, cross-fading to the next line.
 * Before the first line, between songs and for a song without synced lyrics it shows the title like [CompactTitle].
 */
@Composable
private fun CompactLyric(
    state: DynamicIslandUiState,
    color: Color,
    marquee: Boolean,
    scale: Float,
    modifier: Modifier = Modifier,
    /** The lyric-only pill: a line shorter than the pill rests in its middle. */
    center: Boolean = false,
    /** The camera hole in the line, or null: the text is split around it. */
    gap: IslandLyricGap? = null,
) {
    val synced = state.lyrics.status == IslandLyricsStatus.SYNCED
    val index = state.lyricIndex
    val key =
        when {
            state.titleFlash -> TITLE_KEY
            synced && index in state.lyrics.lines.indices -> index
            else -> -1
        }
    val fontSize = with(LocalDensity.current) { (13f * scale).dp.toSp() }
    Crossfade(targetState = key, animationSpec = tween(150), modifier = modifier, label = "compactLyric") { k ->
        val line = state.lyrics.lines.getOrNull(k)
        if (k == TITLE_KEY) {
            val text = if (state.artist.isBlank()) state.title else "${state.title} - ${state.artist}"
            if (gap != null) {
                IslandGapLine(
                    text = text,
                    rtl = false,
                    ink = color,
                    fontSize = fontSize,
                    fontWeight = FontWeight.SemiBold,
                    sweep = { 1f },
                    soft = !state.lite,
                    gapStart = gap.startDp.dp,
                    gapEnd = gap.endDp.dp,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                CompactTitle(text = text, color = color, marquee = true, scale = scale, modifier = Modifier.fillMaxSize())
            }
        } else if (line != null && gap != null) {
            IslandGapLine(
                text = line.text,
                rtl = line.rtl,
                ink = color,
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                sweep = { if (k == state.lyricIndex) state.lyricSweep else 1f },
                soft = !state.lite,
                gapStart = gap.startDp.dp,
                gapEnd = gap.endDp.dp,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (line != null) {
            Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.fillMaxSize()) {
                IslandSweepText(
                    text = line.text,
                    rtl = line.rtl,
                    ink = color,
                    fontSize = fontSize,
                    fontWeight = FontWeight.SemiBold,
                    sweep = { if (k == state.lyricIndex) state.lyricSweep else 1f },
                    soft = !state.lite,
                    follow = true,
                    center = center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            CompactTitle(text = state.title, color = color, marquee = marquee, scale = scale, modifier = Modifier.fillMaxSize())
        }
    }
}

/** [CompactLyric] key of the title shown for a moment after a song change. */
private const val TITLE_KEY = -2

/** Song title in the compact pill; scrolls (marquee) only while playing and collapsed. */
@Composable
private fun CompactTitle(
    text: String,
    color: Color,
    marquee: Boolean,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    val fontSize = with(LocalDensity.current) { (13f * scale).dp.toSp() }
    Box(contentAlignment = Alignment.CenterStart, modifier = modifier) {
        Text(
            text = text,
            color = color,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier =
                if (marquee) {
                    Modifier.basicMarquee(
                        iterations = Int.MAX_VALUE,
                        initialDelayMillis = 1200,
                        velocity = 26.dp,
                    )
                } else {
                    Modifier
                },
        )
    }
}

/**
 * Thin rounded bar slider drawn by hand so it works inside the overlay window. Tap to jump, drag
 * horizontally to scrub; the bar thickens and the thumb appears only while it is being touched.
 */
@Composable
private fun IslandBarSlider(
    value: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (Float) -> Unit,
    onActiveChange: (Boolean) -> Unit,
    description: String,
    trackColor: Color,
    fillColor: Color,
    thumbColor: Color,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    val currentValue = rememberUpdatedState(value)
    val isEnabled by rememberUpdatedState(enabled)
    val onChange by rememberUpdatedState(onValueChange)
    val onFinished by rememberUpdatedState(onValueChangeFinished)
    val onActive by rememberUpdatedState(onActiveChange)
    var active by remember { mutableStateOf(false) }
    val activeFraction =
        animateFloatAsState(
            targetValue = if (active) 1f else 0f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "sliderActive",
        )
    val density = LocalDensity.current
    val edgePx = with(density) { (7f * scale).dp.toPx() }
    val trackPx = with(density) { (4f * scale).dp.toPx() }
    val activeTrackPx = with(density) { (7f * scale).dp.toPx() }
    val thumbPx = with(density) { (6.5f * scale).dp.toPx() }
    val alphaMul = if (enabled) 1f else 0.4f

    Box(
        modifier =
            modifier
                .semantics {
                    contentDescription = description
                    progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(0f, 1f), 0f..1f)
                    if (enabled) {
                        setProgress { v ->
                            val f = v.coerceIn(0f, 1f)
                            onChange(f)
                            onFinished(f)
                            true
                        }
                    }
                }
                // Tap detector is the outer modifier so the drag detector (inner) sees and consumes
                // moves first; a consumed move cancels the tap.
                .pointerInput(edgePx) {
                    detectTapGestures(
                        onTap = { o ->
                            if (isEnabled) {
                                val usable = size.width - edgePx * 2f
                                val f = if (usable <= 0f) 0f else ((o.x - edgePx) / usable).coerceIn(0f, 1f)
                                onChange(f)
                                onFinished(f)
                            }
                        },
                    )
                }.pointerInput(edgePx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!isEnabled) return@awaitEachGesture
                        val start =
                            awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                                ?: return@awaitEachGesture
                        val usable = size.width - edgePx * 2f
                        var f = if (usable <= 0f) 0f else ((start.position.x - edgePx) / usable).coerceIn(0f, 1f)
                        active = true
                        onActive(true)
                        onChange(f)
                        try {
                            horizontalDrag(start.id) { change ->
                                f = if (usable <= 0f) 0f else ((change.position.x - edgePx) / usable).coerceIn(0f, 1f)
                                onChange(f)
                                change.consume()
                            }
                        } catch (e: CancellationException) {
                            active = false
                            onActive(false)
                            throw e
                        }
                        active = false
                        onActive(false)
                        onFinished(f)
                    }
                }.drawBehind {
                    val a = activeFraction.value
                    val trackH = trackPx + (activeTrackPx - trackPx) * a
                    val left = edgePx
                    val usable = (size.width - edgePx * 2f).coerceAtLeast(0f)
                    val top = (size.height - trackH) / 2f
                    val radius = CornerRadius(trackH / 2f)
                    drawRoundRect(
                        color = trackColor,
                        topLeft = Offset(left, top),
                        size = Size(usable, trackH),
                        cornerRadius = radius,
                        alpha = alphaMul,
                    )
                    val fillW = usable * currentValue.value.coerceIn(0f, 1f)
                    if (fillW > 0.5f) {
                        drawRoundRect(
                            color = fillColor,
                            topLeft = Offset(left, top),
                            size = Size(fillW.coerceAtLeast(trackH.coerceAtMost(usable)), trackH),
                            cornerRadius = radius,
                            alpha = alphaMul,
                        )
                    }
                    if (a > 0.01f) {
                        drawCircle(
                            color = thumbColor,
                            radius = thumbPx * a,
                            center = Offset(left + fillW, size.height / 2f),
                        )
                    }
                },
    )
}

/**
 * Draws a baked Liquid glass over the whole node. The compact skin is always drawn opaque and the expanded one fades in
 * on top as the island opens ([IslandGlass.expandedWeight]), so the pane is never see-through half way. Both are stretched
 * to the live size, which is invisible on frosted glass; the sharp parts (rim, sheen) are drawn live. [alpha] is the
 * fade-in of a new skin. Draw phase only; nothing is baked here.
 */
private fun DrawScope.drawIslandSkin(
    skin: IslandGlassSkin,
    morph: Float,
    alpha: Float,
    compactReach: Float = 0f,
    expandedReach: Float = 0f,
) {
    val w = size.width.roundToInt().coerceAtLeast(1)
    val h = size.height.roundToInt().coerceAtLeast(1)
    val open = IslandGlass.expandedWeight(morph)
    // A reach grows the skin by that much at the top and at both sides (attached to the top edge: the rounded corners of the
    // bitmap are transparent and must fall outside the notch that cuts it); 0 = the skin fills the node exactly.
    if (open < 0.999f) {
        val r = compactReach.roundToInt()
        drawImage(skin.compact, dstOffset = IntOffset(-r, -r), dstSize = IntSize(w + 2 * r, h + r), alpha = alpha, filterQuality = FilterQuality.Low)
    }
    if (open > 0.001f) {
        val r = expandedReach.roundToInt()
        drawImage(skin.expanded, dstOffset = IntOffset(-r, -r), dstSize = IntSize(w + 2 * r, h + r), alpha = open * alpha, filterQuality = FilterQuality.Low)
    }
}

/**
 * Shadow of the Liquid glass. Open: the soft shadow baked with the glass (a blurred outline in a bitmap), so showing it is
 * a plain image draw. Compact: three thin rings within [DynamicIslandDimens.compactShadowReachDp] of the pill, because the
 * compact window is only the pill plus 2 dp (finding 29 of the 2026-11-12 review). No blur runs while the island is on
 * screen, even with the equaliser redrawing every frame. The canvas is the island plus GLOW_PAD on every side; the two
 * cross-fade as the card opens.
 */
@Composable
private fun IslandShadow(
    skin: State<IslandGlassSkin?>,
    morph: State<Float>,
    shape: IslandShape,
    dark: Boolean,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val padPx = with(density) { (DynamicIslandDimens.GLOW_PAD * scale).dp.toPx() }
    val unit = with(density) { scale.dp.toPx() }
    val ring = remember(unit) { Stroke(width = DynamicIslandDimens.COMPACT_SHADOW_STROKE * unit) }
    val strength = if (dark) IslandGlass.SHADOW_DARK else IslandGlass.SHADOW_LIGHT
    Canvas(modifier) {
        val shown = skin.value ?: return@Canvas
        val open = IslandGlass.expandedWeight(morph.value)
        if (open < 0.999f) {
            val w = size.width - padPx * 2f
            val h = size.height - padPx * 2f
            if (w > 0f && h > 0f) {
                val corner = shape.cornerPx(h, this)
                val drop = DynamicIslandDimens.COMPACT_SHADOW_DROP * unit
                for (i in DynamicIslandDimens.COMPACT_SHADOW_SPREADS.indices) {
                    val spread = DynamicIslandDimens.COMPACT_SHADOW_SPREADS[i] * unit
                    drawRoundRect(
                        color = Color.Black,
                        topLeft = Offset(padPx - spread, padPx - spread + drop),
                        size = Size(w + spread * 2f, h + spread * 2f),
                        cornerRadius = CornerRadius(corner + spread),
                        style = ring,
                        alpha = (strength * DynamicIslandDimens.COMPACT_SHADOW_ALPHAS[i] * (1f - open)).coerceIn(0f, 1f),
                    )
                }
            }
        }
        if (open > 0.001f) {
            val dst = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1))
            drawImage(shown.shadowExpanded, dstSize = dst, alpha = open, filterQuality = FilterQuality.Low)
        }
    }
}

/** Soft halo around the expanded card. Draws once per size change; the fade is a layer property. */
@Composable
private fun IslandGlow(
    color: Color,
    shape: IslandShape,
    intensity: Float,
    scale: Float,
    t: State<Float>,
    modifier: Modifier = Modifier,
) {
    val blur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val density = LocalDensity.current
    val padPx = with(density) { (DynamicIslandDimens.GLOW_PAD * scale).dp.toPx() }
    val strokePx = with(density) { (mix(1.6f, 4.5f, intensity) * scale).dp.toPx() }
    val thinPx = with(density) { (mix(0.9f, 1.8f, intensity) * scale).dp.toPx() }
    val blurPx = with(density) { (mix(5f, 9f, intensity) * scale).dp.toPx() }
    val wideStroke = remember(strokePx) { Stroke(width = strokePx) }
    val thinStroke = remember(thinPx) { Stroke(width = thinPx) }
    val effect = remember(blurPx, blur) { if (blur) BlurEffect(blurPx, blurPx, TileMode.Decal) else null }
    val strength = mix(0.35f, 1f, intensity)

    Canvas(
        modifier.graphicsLayer {
            alpha = t.value.coerceIn(0f, 1f) * strength
            renderEffect = effect
        },
    ) {
        val cardW = size.width - padPx * 2f
        val cardH = size.height - padPx * 2f
        val cornerPx = shape.cornerPx(cardH, this)
        if (blur) {
            val spread = strokePx / 2f
            drawRoundRect(
                color = color,
                topLeft = Offset(padPx - spread, padPx - spread),
                size = Size(cardW + spread * 2f, cardH + spread * 2f),
                cornerRadius = CornerRadius(cornerPx + spread),
                style = wideStroke,
            )
        } else {
            for (i in GlowAlphas.indices) {
                val spread = thinPx * (0.5f + i * 1.2f)
                drawRoundRect(
                    color = color,
                    topLeft = Offset(padPx - spread, padPx - spread),
                    size = Size(cardW + spread * 2f, cardH + spread * 2f),
                    cornerRadius = CornerRadius(cornerPx + spread),
                    style = thinStroke,
                    alpha = GlowAlphas[i],
                )
            }
        }
    }
}

@Composable
private fun IslandGlyphButton(
    icon: Int,
    label: String,
    enabled: Boolean,
    tint: Color,
    size: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale =
        animateFloatAsState(
            targetValue = if (pressed) 0.82f else 1f,
            animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
            label = "press",
        )
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = pressScale.value
                    scaleY = pressScale.value
                }.clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClickLabel = label,
                    role = Role.Button,
                    onClick = onClick,
                ),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = tint.copy(alpha = if (enabled) tint.alpha else tint.alpha * 0.35f),
            modifier = Modifier.size(iconSize),
        )
    }
}

/** Artwork (or a placeholder). Spins slowly, like a record, when [spinning]; keeps its angle when paused. */
@Composable
private fun IslandCover(
    artwork: ImageBitmap?,
    circle: Boolean,
    spinning: Boolean,
    placeholder: Color,
    iconTint: Color,
    modifier: Modifier = Modifier,
) {
    val angle = remember { Animatable(0f) }
    LaunchedEffect(spinning, circle) {
        if (!circle) {
            angle.snapTo(0f)
            return@LaunchedEffect
        }
        while (spinning && isActive) {
            angle.animateTo(angle.value + 360f, tween(COVER_SPIN_MS, easing = LinearEasing))
            angle.snapTo(angle.value % 360f)
        }
    }
    val shape = if (circle) CircleShape else RoundedCover
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .graphicsLayer { rotationZ = angle.value }
                .clip(shape)
                .then(if (artwork == null) Modifier.background(placeholder) else Modifier),
    ) {
        if (artwork != null) {
            Image(
                bitmap = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.music_note),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.fillMaxSize(0.55f),
            )
        }
    }
}

/**
 * Equaliser glyph. While playing it animates with draw-only invalidation; on play/pause it eases
 * between the moving and the resting shape instead of jumping. Nothing runs once it has settled paused.
 */
@Composable
private fun IslandEqualizer(
    playing: Boolean,
    style: DynamicIslandEqStyle,
    color: Color,
    modifier: Modifier = Modifier,
    lite: Boolean = false,
) {
    val energy =
        animateFloatAsState(
            targetValue = if (playing) 1f else 0f,
            animationSpec = tween(EQ_EASE_MS, easing = FastOutSlowInEasing),
            label = "eqEnergy",
        )
    val playingState = rememberUpdatedState(playing)
    val running by remember { derivedStateOf { playingState.value || energy.value > 0.001f } }
    if (running && lite) {
        // a timer instead of a transition: the transition redraws the overlay on every screen refresh (90 or 120 a second)
        val phase = remember { mutableFloatStateOf(0f) }
        LaunchedEffect(Unit) {
            val t0 = SystemClock.uptimeMillis()
            while (isActive) {
                phase.floatValue = ((SystemClock.uptimeMillis() - t0) % EQ_PERIOD_MS) / EQ_PERIOD_MS.toFloat()
                delay(LITE_EQ_MS)
            }
        }
        Canvas(modifier) { drawEqualizer(style, phase.floatValue, energy.value, color) }
    } else if (running) {
        val phase =
            rememberInfiniteTransition(label = "eq").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(EQ_PERIOD_MS, easing = LinearEasing), RepeatMode.Restart),
                label = "phase",
            )
        Canvas(modifier) { drawEqualizer(style, phase.value, energy.value, color) }
    } else {
        Canvas(modifier) { drawEqualizer(style, 0f, 0f, color) }
    }
}

/** Smooth 0.22..1 level of bar [i] (cosine, so it never snaps at the bottom). */
private fun eqLevel(
    i: Int,
    phase: Float,
): Float = 0.22f + 0.78f * (0.5f - 0.5f * cos(TWO_PI * (phase * EqFrequencies[i] + EqOffsets[i])))

private fun DrawScope.drawEqualizer(
    style: DynamicIslandEqStyle,
    phase: Float,
    energy: Float,
    color: Color,
) {
    val tint = color.copy(alpha = color.alpha * mix(0.7f, 1f, energy))
    val w = size.width
    val h = size.height
    when (style) {
        DynamicIslandEqStyle.BARS -> {
            val n = EqStill.size
            val barW = w / (n * 2f - 1f)
            for (i in 0 until n) {
                val f = mix(EqStill[i], eqLevel(i, phase), energy)
                val bh = (h * f).coerceAtLeast(barW)
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(i * barW * 2f, (h - bh) / 2f),
                    size = Size(barW, bh),
                    cornerRadius = CornerRadius(barW / 2f),
                )
            }
        }
        DynamicIslandEqStyle.DOTS -> {
            val n = EqStill.size
            val step = w / n
            val r = (step * 0.34f).coerceAtMost(h * 0.22f)
            for (i in 0 until n) {
                val f = mix(EqStill[i], eqLevel(i, phase), energy)
                val cy = h / 2f + (0.5f - f) * (h - r * 2f)
                drawCircle(color = tint, radius = r * mix(0.8f, 1.1f, f), center = Offset(step * (i + 0.5f), cy))
            }
        }
        DynamicIslandEqStyle.WAVE -> {
            val stroke = (h * 0.15f).coerceAtLeast(1f)
            val mid = h / 2f
            val amp = (mid - stroke) * mix(0.22f, 1f, energy) * (0.8f + 0.2f * sin(TWO_PI * phase))
            val usable = w - stroke
            var px = stroke / 2f
            var py = mid
            for (j in 1..WAVE_SEGMENTS) {
                val u = j / WAVE_SEGMENTS.toFloat()
                val x = stroke / 2f + usable * u
                val y = mid + amp * sin(PI_F * u) * sin(TWO_PI * (u * 1.5f + phase * 2f))
                drawLine(color = tint, start = Offset(px, py), end = Offset(x, y), strokeWidth = stroke, cap = StrokeCap.Round)
                px = x
                py = y
            }
        }
    }
}
