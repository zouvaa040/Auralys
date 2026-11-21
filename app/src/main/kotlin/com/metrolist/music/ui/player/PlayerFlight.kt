/**
 * Auralys - the flying cover of the player sheet
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.metrolist.music.ui.component.BottomSheetState
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The player has one sheet, so its flight state is one process-wide holder (the mini player, the big cover and the sheet
 * all reach it here instead of passing it through six composables).
 */
object PlayerFlights {
    val main = PlayerFlight()
}

/**
 * What the flying cover needs: where the cover is in the mini player (collapsed) and where it is in the open player
 * (window coordinates, read when the sheet is at rest, never while it moves), the picture, and the angle of the spinning
 * disc to hand over. Nothing flies until both rests have been seen (so the first opening of a run is the plain fade) and
 * only while the big cover is on screen (not with the lyrics, in landscape or with the thumbnail hidden).
 */
@Stable
class PlayerFlight {
    var sheet: BottomSheetState? = null
    var mini by mutableStateOf<Rect?>(null)
    var big by mutableStateOf<Rect?>(null)
    var bigRadiusDp by mutableFloatStateOf(0f)
    var bigMasked by mutableStateOf(false)
    var url by mutableStateOf<String?>(null)
    var coverShown by mutableStateOf(false)
    var angle: Animatable<Float, AnimationVector1D>? = null

    private var miniCoords: LayoutCoordinates? = null
    private var bigCoords: LayoutCoordinates? = null

    val enabled: Boolean get() = mini != null && big != null && url != null && coverShown

    fun noteMini(coords: LayoutCoordinates) {
        miniCoords = coords
        if (sheet?.let { it.isCollapsed && !it.adjusting } == true) captureMini()
    }

    fun noteBig(
        coords: LayoutCoordinates,
        radiusDp: Float,
        masked: Boolean,
    ) {
        bigCoords = coords
        bigRadiusDp = radiusDp
        bigMasked = masked
        if (sheet?.isExpanded == true) captureBig()
    }

    fun captureMini() {
        miniCoords?.takeIf { it.isAttached }?.let { mini = it.boundsInWindow() }
    }

    fun captureBig() {
        bigCoords?.takeIf { it.isAttached }?.let { big = it.boundsInWindow() }
    }

    /** 1 while the open player's own cover shows; 0 while the flying one stands in for it (it takes over in the last 12 percent). */
    fun bigCoverAlpha(): Float {
        if (!enabled) return 1f
        val p = sheet?.progress ?: return 1f
        return smooth(((p - 0.88f) / 0.12f).coerceIn(0f, 1f))
    }

    /** The mini disc is hidden from the first movement of the sheet: the flying one is drawn on top of it, the same picture at the same place. */
    fun miniCoverAlpha(): Float = if (enabled && (sheet?.progress ?: 0f) > FLIGHT_START) 0f else 1f
}

/** The cover of the mini player: hidden while the flying one stands in, and its place is reported. */
internal fun Modifier.flightMini(): Modifier {
    val f = PlayerFlights.main
    return this
        .graphicsLayer { alpha = f.miniCoverAlpha() }
        .onGloballyPositioned { f.noteMini(it) }
}

/** The cover of the open player: hidden until the flying one lands, and its place is reported. */
internal fun Modifier.flightBig(
    radiusDp: Float,
    masked: Boolean,
): Modifier {
    val f = PlayerFlights.main
    return this
        .graphicsLayer { alpha = f.bigCoverAlpha() }
        .onGloballyPositioned { f.noteBig(it, radiusDp, masked) }
}

/** Only the hiding of the open player's cover (the card layouts report their place from an inner box). */
internal fun Modifier.flightBigAlpha(): Modifier {
    val f = PlayerFlights.main
    return this.graphicsLayer { alpha = f.bigCoverAlpha() }
}

/** Only the place report of the open player's cover. */
internal fun Modifier.flightBigBounds(
    radiusDp: Float,
    masked: Boolean,
): Modifier {
    val f = PlayerFlights.main
    return this.onGloballyPositioned { f.noteBig(it, radiusDp, masked) }
}

private const val FLIGHT_START = 0.002f

private fun smooth(t: Float): Float = t * t * (3f - 2f * t)

private fun mix(
    a: Float,
    b: Float,
    t: Float,
): Float = a + (b - a) * t

/**
 * Where the cover is at sheet progress [u] (0 = in the mini player, 1 = in the open player): the size grows early, the
 * centre moves sideways first and rises later, so the cover swings out of the corner in a curve instead of a diagonal.
 */
private fun flightRect(
    mini: Rect,
    big: Rect,
    u: Float,
): Rect {
    val sizeT = 1f - (1f - u) * (1f - u)
    val xT = 1f - (1f - u).pow(2.2f)
    val yT = u.pow(1.5f)
    val w = mix(mini.width, big.width, sizeT)
    val h = mix(mini.height, big.height, sizeT)
    val cx = mix(mini.center.x, big.center.x, xT)
    val cy = mix(mini.center.y, big.center.y, yT)
    return Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
}

/**
 * The record decoration of the mini player's cover (two faint grooves, a dark label ring and the spindle hole), scaled by
 * [alpha] so the flying cover can let it go while it grows. Drawn on the cover, so it turns with it.
 */
internal fun DrawScope.drawRecordGrooves(alpha: Float = 1f) {
    if (alpha <= 0f) return
    val r = size.minDimension / 2f
    val hair = 0.8.dp.toPx()
    drawCircle(Color.Black.copy(alpha = 0.20f * alpha), radius = r * 0.72f, style = Stroke(hair))
    drawCircle(Color.Black.copy(alpha = 0.14f * alpha), radius = r * 0.54f, style = Stroke(hair))
    drawCircle(Color.Black.copy(alpha = 0.55f * alpha), radius = r * 0.24f)
    drawCircle(Color.White.copy(alpha = 0.30f * alpha), radius = r * 0.24f, style = Stroke(hair))
    drawCircle(Color(0xFF111113).copy(alpha = alpha), radius = r * 0.09f)
}

/**
 * The cover that flies between the mini player and the open player (BottomSheet's overlay slot, above everything): one
 * picture whose rectangle, corner radius and turn are functions of the sheet's progress, so it follows the finger while
 * the sheet is dragged, and the same flight runs backwards when the player closes. At the start it is the spinning disc
 * of the mini player (same place, same angle, grooves), it flattens into the cover of the open player (the disc's turn
 * settles to zero by 62 percent, the circle opens into the cover's corners) and hands over to the real cover in the last
 * 12 percent. Everything runs in layout/graphicsLayer reads of the sheet's progress: nothing recomposes per frame.
 */
@Composable
internal fun PlayerFlightOverlay(
    flight: PlayerFlight,
    state: BottomSheetState,
) {
    LaunchedEffect(state, flight) {
        launch { snapshotFlow { state.isCollapsed && !state.adjusting }.filter { it }.collect { flight.captureMini() } }
        launch { snapshotFlow { state.isExpanded }.filter { it }.collect { flight.captureBig() } }
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        val mini = flight.mini
        val big = flight.big
        val url = flight.url
        if (flight.enabled && mini != null && big != null && url != null) {
            val request = remember(url) { ImageRequest.Builder(context).data(url).size(512).build() }
            val bigRadiusPx = with(density) { flight.bigRadiusDp.dp.toPx() }
            val masked = flight.bigMasked
            Box(
                modifier =
                    Modifier
                        .offset {
                            val r = flightRect(mini, big, state.progress.coerceIn(0f, 1f))
                            IntOffset((r.left - origin.x).roundToInt(), (r.top - origin.y).roundToInt())
                        }.layout { measurable, _ ->
                            val r = flightRect(mini, big, state.progress.coerceIn(0f, 1f))
                            val w = r.width.roundToInt().coerceAtLeast(1)
                            val h = r.height.roundToInt().coerceAtLeast(1)
                            val placeable = measurable.measure(Constraints.fixed(w, h))
                            layout(w, h) { placeable.place(0, 0) }
                        }.graphicsLayer {
                            val u = state.progress.coerceIn(0f, 1f)
                            alpha = if (u <= FLIGHT_START) 0f else 1f - smooth(((u - 0.88f) / 0.12f).coerceIn(0f, 1f))
                            rotationZ = (flight.angle?.value ?: 0f) * (1f - (u / 0.62f).coerceIn(0f, 1f))
                            val half = min(size.width, size.height) / 2f
                            val radius = mix(half, bigRadiusPx, 1f - (1f - u) * (1f - u)).coerceIn(0f, half)
                            shape = RoundedCornerShape(radius)
                            clip = true
                            // an offscreen copy of the picture is only needed to mask it (the full-bleed cover); a plain cover is drawn straight
                            if (masked) compositingStrategy = CompositingStrategy.Offscreen
                        }.drawWithContent {
                            drawContent()
                            val u = state.progress.coerceIn(0f, 1f)
                            drawRecordGrooves((1f - u / 0.3f).coerceIn(0f, 1f))
                            if (masked) {
                                // the open player's full-bleed cover melts into the page at its foot (PlayerHero): the flying one
                                // takes that mask on while it lands, so the hand-over shows no change
                                val m = smooth(((u - 0.45f) / 0.55f).coerceIn(0f, 1f))
                                if (m > 0f) {
                                    drawRect(
                                        brush =
                                            Brush.verticalGradient(
                                                colorStops =
                                                    heroMaskStops()
                                                        .map { (pos, a) -> pos to Color.Black.copy(alpha = 1f - m * (1f - a)) }
                                                        .toTypedArray(),
                                                startY = 0f,
                                                endY = size.height,
                                            ),
                                        blendMode = BlendMode.DstIn,
                                    )
                                }
                            }
                        },
            ) {
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
