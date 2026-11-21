/**
 * Auralys - the app's messages in the iOS way
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * A message without an action ("Ditambahkan ke playlist") is a HUD: a small rounded panel with a check mark that draws itself
 * (a warning glyph for a failure) and the text under it, in the middle of the screen, for about 1.6 s. A message WITH an action
 * ("Urungkan") stays a banner at the bottom, restyled as a floating rounded card. One host replaces Material's SnackbarHost:
 * [AuralysSnackbarHost] takes the same SnackbarHostState, so nothing that posts a message changes.
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.metrolist.music.R
import com.metrolist.music.ui.theme.isGlassStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Drop-in for Material's SnackbarHost (same state, same name of the arguments). Messages without an action are HUDs, messages
 * with an action are floating banners. [modifier] places the banner as before; the HUD is placed by the window (a popup, so it is
 * never behind the player or the bars): centred, a little above the middle of the screen. The HUD window is only as big as the
 * panel, so everything outside it stays usable while it shows.
 */
@Composable
fun AuralysSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val current = hostState.currentSnackbarData
    // what is on screen (kept while it leaves) and whether it should be in or out
    var shown by remember { mutableStateOf<SnackbarData?>(null) }
    val visible = remember { MutableTransitionState(false) }

    LaunchedEffect(current) {
        if (current == null) {
            visible.targetState = false
            return@LaunchedEffect
        }
        shown = current
        visible.targetState = true
        val hold = holdMillis(current.visuals)
        if (hold != null) {
            delay(hold)
            current.dismiss()
        }
    }
    // once the leave animation is over, forget what was drawn
    LaunchedEffect(visible.currentState, visible.targetState) {
        if (!visible.targetState && !visible.currentState) shown = null
    }

    val data = shown
    val ios = LocalIosMotion.current
    Box(modifier) {
        if (data != null) {
            if (data.visuals.actionLabel == null) {
                Popup(
                    popupPositionProvider = HudPositionProvider,
                    properties =
                        PopupProperties(
                            focusable = false,
                            dismissOnBackPress = false,
                            dismissOnClickOutside = false,
                            clippingEnabled = false,
                        ),
                ) {
                    // a margin inside the window: the panel overshoots a little while it springs in and must not be cut by the window
                    Box(Modifier.padding(20.dp)) {
                        AnimatedVisibility(
                            visibleState = visible,
                            enter =
                                fadeIn(tween(140)) +
                                    scaleIn(spring<Float>(dampingRatio = if (ios) 0.6f else 0.78f, stiffness = 420f), initialScale = 0.85f),
                            exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.95f),
                        ) {
                            key(data) { IosHudPanel(data.visuals.message) }
                        }
                    }
                }
            } else {
                AnimatedVisibility(
                    visibleState = visible,
                    enter =
                        fadeIn(tween(160)) +
                            slideInVertically(
                                animationSpec = spring<IntOffset>(dampingRatio = if (ios) 0.68f else 0.85f, stiffness = 420f),
                                initialOffsetY = { it / 2 },
                            ),
                    exit =
                        fadeOut(tween(140)) +
                            slideOutVertically(animationSpec = tween<IntOffset>(160), targetOffsetY = { it / 2 }),
                ) {
                    key(data) { IosBanner(data) }
                }
            }
        }
    }
}

/** How long a message stays: a HUD about 1.6 s (a little longer for a long text, at most 3.2 s), a banner as Material says. */
private fun holdMillis(visuals: SnackbarVisuals): Long? {
    if (visuals.actionLabel == null) {
        val extra = (visuals.message.length - 28).coerceAtLeast(0) * 35L
        return (1600L + extra).coerceAtMost(3200L)
    }
    return when (visuals.duration) {
        SnackbarDuration.Indefinite -> null
        SnackbarDuration.Long -> 10_000L
        SnackbarDuration.Short -> 4_000L
    }
}

/**
 * A snackbar has no type, so the glyph comes from the words: a message that says it failed, could not, or has no connection gets
 * the warning glyph, everything else the check mark. (Indonesian and English; lower-cased contains-match.)
 */
internal fun looksLikeFailure(message: String): Boolean {
    val text = message.lowercase()
    return FailureWords.any { text.contains(it) }
}

private val FailureWords =
    listOf(
        "gagal", "galat", "kesalahan", "error", "failed", "failure", "unable", "cannot", "can't", "couldn't", "could not",
        "tidak bisa", "tidak dapat", "tidak ada koneksi", "tidak ada internet", "no connection", "no internet", "offline",
        "tidak ditemukan", "tidak tersedia", "not found", "unavailable",
    )

/** The HUD sits centred, at 40 percent of the window height. */
private object HudPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset =
        IntOffset(
            x = (windowSize.width - popupContentSize.width) / 2,
            y = (windowSize.height * 0.40f - popupContentSize.height / 2f).toInt(),
        )
}

/** The panel: 22 dp corners (not a capsule), 140-240 dp wide, 20 dp inside. Glass: dark and nearly opaque; Datar: flat with a hairline. */
@Composable
private fun IosHudPanel(message: String) {
    val glass = isGlassStyle
    val scheme = MaterialTheme.colorScheme
    val ios = LocalIosMotion.current
    val shape = RoundedCornerShape(22.dp)
    val ink = if (glass) Color.White else scheme.onSurface
    val failure = remember(message) { looksLikeFailure(message) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .semantics { liveRegion = LiveRegionMode.Polite }
                .widthIn(min = 140.dp, max = 240.dp)
                .clip(shape)
                .background(if (glass) Color(0xFF1C1C1E).copy(alpha = 0.88f) else scheme.surfaceContainerHigh)
                .border(
                    width = if (glass) 0.5.dp else 1.dp,
                    color = if (glass) Color.White.copy(alpha = 0.14f) else scheme.onSurface.copy(alpha = 0.08f),
                    shape = shape,
                ).padding(20.dp),
    ) {
        HudGlyph(failure = failure, color = ink, bouncy = ios)
        Spacer(Modifier.height(10.dp))
        Text(
            text = message,
            color = ink,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The glyph pops in with its own bounce (looser with iOS animations on). The check mark is a path that draws itself from its
 * start to its end in about 360 ms; the warning glyph is the app's own icon. Progress and scale are read in graphics only.
 */
@Composable
private fun HudGlyph(
    failure: Boolean,
    color: Color,
    bouncy: Boolean,
) {
    val pop = remember { Animatable(0.6f) }
    val draw = remember { Animatable(if (failure) 1f else 0f) }
    LaunchedEffect(Unit) {
        launch { pop.animateTo(1f, spring(dampingRatio = if (bouncy) 0.32f else 0.45f, stiffness = 380f)) }
        if (!failure) draw.animateTo(1f, tween(durationMillis = 360, delayMillis = 60))
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(44.dp)
                .graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                },
    ) {
        if (failure) {
            Icon(
                painter = painterResource(R.drawable.warning),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(44.dp),
            )
        } else {
            Canvas(Modifier.size(44.dp)) {
                val w = size.width
                val h = size.height
                val check =
                    Path().apply {
                        moveTo(w * 0.16f, h * 0.54f)
                        lineTo(w * 0.40f, h * 0.76f)
                        lineTo(w * 0.84f, h * 0.26f)
                    }
                val measure = PathMeasure()
                measure.setPath(check, false)
                val part = Path()
                measure.getSegment(0f, measure.length * draw.value, part, true)
                drawPath(
                    path = part,
                    color = color,
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}

/**
 * A message with an action, as a floating banner: 20 dp corners, 12 dp from the sides, the action in bold on the right and a close
 * button when the message asks for one. Glass: dark and nearly opaque with a hairline; Datar: flat surface with a hairline.
 */
@Composable
private fun IosBanner(data: SnackbarData) {
    val glass = isGlassStyle
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val ink = if (glass) Color.White else scheme.onSurface
    val visuals = data.visuals
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .fillMaxWidth()
                .clip(shape)
                .background(if (glass) Color(0xFF1C1C1E).copy(alpha = 0.92f) else scheme.surfaceContainerHigh)
                .border(
                    width = if (glass) 0.5.dp else 1.dp,
                    color = if (glass) Color.White.copy(alpha = 0.14f) else scheme.onSurface.copy(alpha = 0.08f),
                    shape = shape,
                ).defaultMinSize(minHeight = 52.dp)
                .padding(start = 18.dp, end = 6.dp),
    ) {
        Text(
            text = visuals.message,
            color = ink,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 14.dp),
        )
        visuals.actionLabel?.let { label ->
            Text(
                text = label,
                color = ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { data.performAction() }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
        if (visuals.withDismissAction) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable { data.dismiss() },
            ) {
                Icon(
                    painter = painterResource(R.drawable.close),
                    contentDescription = null,
                    tint = ink,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
