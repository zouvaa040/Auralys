/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import com.metrolist.music.ui.component.backdrop.catalog.components.LiquidBottomTab
import com.metrolist.music.ui.component.backdrop.drawBackdrop
import com.metrolist.music.ui.component.backdrop.effects.blur
import com.metrolist.music.ui.component.backdrop.effects.lens
import com.metrolist.music.ui.component.backdrop.effects.vibrancy
import com.metrolist.music.ui.component.motion.NavBarFoldState
import com.metrolist.music.ui.component.motion.gooey
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import kotlinx.coroutines.CoroutineScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.metrolist.music.ui.theme.LocalUiStyle
import com.metrolist.music.ui.theme.UiStyle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Alignment
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.style.TextOverflow
import com.metrolist.music.ui.screens.Screens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** The navigation bars carry no accent colour: the selected tab is onSurface, the others the same colour at this strength. */
private const val NavMutedAlpha = 0.62f

@Stable
private fun isRouteSelected(currentRoute: String?, screenRoute: String, navigationItems: List<Screens>): Boolean {
    if (currentRoute == null) return false
    if (currentRoute == screenRoute) return true
    if (navigationItems.any { it.route == screenRoute } &&
        currentRoute.startsWith("$screenRoute/")) return true

    // Fix: match the route template, not the resolved route
    if (screenRoute == "search_input" &&
        (currentRoute.startsWith("search/") || currentRoute == "search/{query}")) return true

    return false
}

@Composable
fun AppNavigationRail(
    navigationItems: List<Screens>,
    currentRoute: String?,
    onItemClick: (Screens, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    pureBlack: Boolean = false,
    onSearchLongClick: (() -> Unit)? = null,
    onHomeLongHold: (() -> Unit)? = null,
) {
    val containerColor = if (pureBlack) Color.Black else MaterialTheme.colorScheme.surfaceContainer
    val haptics = LocalHapticFeedback.current
    val viewConfiguration = LocalViewConfiguration.current

    NavigationRail(
        modifier = modifier,
        containerColor = containerColor
    ) {
        Spacer(modifier = Modifier.weight(1f))

        navigationItems.forEach { screen ->
            val isSelected = remember(currentRoute, screen.route) {
                isRouteSelected(currentRoute, screen.route, navigationItems)
            }
            val currentIsSelected by rememberUpdatedState(isSelected)
            val iconRes = remember(isSelected, screen) {
                if (isSelected) screen.iconIdActive else screen.iconIdInactive
            }

            val isSearchItem = screen == Screens.Search && onSearchLongClick != null
            val isHomeHoldItem = screen == Screens.Home && onHomeLongHold != null
            val interactionSource = remember { MutableInteractionSource() }

            // Long press detection using InteractionSource
            if (isSearchItem || isHomeHoldItem) {
                LaunchedEffect(interactionSource) {
                    var isLongClick = false
                    interactionSource.interactions.collectLatest { interaction ->
                        when (interaction) {
                            is PressInteraction.Press -> {
                                isLongClick = false
                                delay(if (isHomeHoldItem) 15_000L else viewConfiguration.longPressTimeoutMillis)
                                isLongClick = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (isHomeHoldItem) onHomeLongHold.invoke() else onSearchLongClick?.invoke()
                            }
                            is PressInteraction.Release -> {
                                if (!isLongClick) {
                                    onItemClick(screen, currentIsSelected)
                                }
                            }
                            is PressInteraction.Cancel -> {
                                isLongClick = false
                            }
                        }
                    }
                }
            }

            NavigationRailItem(
                selected = isSelected,
                onClick = {
                    if (!isSearchItem && !isHomeHoldItem) {
                        onItemClick(screen, currentIsSelected)
                    }
                    // Long presses are handled via InteractionSource
                },
                interactionSource = interactionSource,
                colors =
                    androidx.compose.material3.NavigationRailItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onSurface,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        indicatorColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                        unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = NavMutedAlpha),
                        unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = NavMutedAlpha),
                    ),
                icon = {
                    Icon(
                        painter = painterResource(id = iconRes),
                        contentDescription = stringResource(screen.titleId)
                    )
                }
            )
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
fun AppNavigationBar(
    navigationItems: List<Screens>,
    currentRoute: String?,
    onItemClick: (Screens, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    pureBlack: Boolean = false,
    slimNav: Boolean = false,
    onSearchLongClick: (() -> Unit)? = null,
    onHomeLongHold: (() -> Unit)? = null,
    foldState: NavBarFoldState? = null,
) {
    LensNavigationBar(
        navigationItems, currentRoute, onItemClick, modifier, pureBlack, slimNav, onSearchLongClick, onHomeLongHold, foldState,
    )
}

/** Long-press handling shared by both bars (Search long press, Home 15s hold). */
@Composable
private fun NavLongPress(
    screen: Screens,
    interactionSource: MutableInteractionSource,
    currentIsSelected: Boolean,
    onItemClick: (Screens, Boolean) -> Unit,
    onSearchLongClick: (() -> Unit)?,
    onHomeLongHold: (() -> Unit)?,
) {
    val haptics = LocalHapticFeedback.current
    val viewConfiguration = LocalViewConfiguration.current
    val isSearchItem = screen == Screens.Search && onSearchLongClick != null
    val isHomeHoldItem = screen == Screens.Home && onHomeLongHold != null
    val selectedNow by rememberUpdatedState(currentIsSelected)
    if (isSearchItem || isHomeHoldItem) {
        LaunchedEffect(interactionSource) {
            var isLongClick = false
            interactionSource.interactions.collectLatest { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        isLongClick = false
                        delay(if (isHomeHoldItem) 15_000L else viewConfiguration.longPressTimeoutMillis)
                        isLongClick = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (isHomeHoldItem) onHomeLongHold?.invoke() else onSearchLongClick?.invoke()
                    }
                    is PressInteraction.Release -> {
                        if (!isLongClick) onItemClick(screen, selectedNow)
                    }
                    is PressInteraction.Cancel -> isLongClick = false
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------------------------
// Navigation bar with a draggable "lens".
//
//  - Touching the bar lifts the lens; dragging moves it with the finger (it stretches with speed,
//    rubber-bands at the ends, ticks a haptic per tab) and releasing snaps it to a tab with momentum
//    and a little overshoot. A plain tap just slides the lens to the tapped tab.
//  - GLASS: the Liquid bar (LiquidNavBar below, vendored Kyant0 LiquidBottomTabs) on every Android version,
//    with its own puck physics and the fold on scroll. Its material is lens / blur / frost (GlassBarPolicy).
//    The older GlassLensBar (Android 8-12 fallback, no fold) was removed in 4.3.4.
//  - EDITORIAL: flat paper capsule, the selected tab is an ink-tint pill that widens to show its label;
//    while dragging the pill glides between tabs and the labels morph in and out (LensController below).
// ----------------------------------------------------------------------------------------------
@Stable
private class LensController(
    val n: Int,
    val scope: CoroutineScope,
    val haptics: HapticFeedback,
    initial: Int,
) {
    val pos = Animatable(initial.toFloat())
    val press = Animatable(0f)
    val stretch = Animatable(0f)
    var dragging by mutableStateOf(false)
    var selected by mutableIntStateOf(initial)
    var onSelect: (Int) -> Unit = {}
    val nearest by derivedStateOf { pos.value.roundToInt().coerceIn(0, n - 1) }

    fun slideTo(index: Int) {
        if (!dragging) scope.launch { pos.animateTo(index.toFloat(), spring(dampingRatio = 0.58f, stiffness = 380f)) }
    }
}

private suspend fun AwaitPointerEventScope.lensGesture(c: LensController, itemW: Float) {
    val down = awaitFirstDown(requireUnconsumed = false)
    c.scope.launch { c.press.animateTo(1f, spring(0.5f, 700f)) }
    val tracker = VelocityTracker()
    tracker.addPosition(down.uptimeMillis, down.position)
    var raw = c.pos.value
    val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
        change.consume()
        c.dragging = true
        c.scope.launch { c.pos.stop() }
        raw = (c.pos.value + over / itemW).coerceIn(-0.28f, c.n - 1 + 0.28f)
        val shown = rubber(raw, c.n)
        c.scope.launch { c.pos.snapTo(shown) }
    }
    if (slop != null) {
        horizontalDrag(slop.id) { change ->
            val dx = change.positionChange().x
            tracker.addPosition(change.uptimeMillis, change.position)
            change.consume()
            raw = (raw + dx / itemW).coerceIn(-0.28f, c.n - 1 + 0.28f)
            val shown = rubber(raw, c.n)
            val v = tracker.calculateVelocity().x
            c.scope.launch {
                c.pos.snapTo(shown)
                c.stretch.snapTo((abs(v) / (itemW * 9f)).coerceIn(0f, 1f))
            }
        }
    }
    val vx = if (slop != null) tracker.calculateVelocity().x else 0f
    val wasDragging = c.dragging
    c.dragging = false
    c.scope.launch { c.press.animateTo(0f, spring(0.55f, 520f)) }
    c.scope.launch { c.stretch.animateTo(0f, spring(0.5f, 500f)) }
    if (wasDragging) {
        val projected = c.pos.value + vx / itemW * 0.18f
        val target = projected.roundToInt().coerceIn(0, c.n - 1)
        c.scope.launch { c.pos.animateTo(target.toFloat(), spring(0.55f, 360f)) }
        if (target != c.selected) {
            c.haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            c.onSelect(target)
        }
    }
}

@Composable
private fun rememberLensController(n: Int, selectedIndex: Int, onSelect: (Int) -> Unit): LensController {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val controller = remember(n) { LensController(n, scope, haptics, selectedIndex) }
    controller.selected = selectedIndex
    controller.onSelect = onSelect
    LaunchedEffect(selectedIndex) { controller.slideTo(selectedIndex) }
    LaunchedEffect(controller.nearest, controller.dragging) {
        if (controller.dragging) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    return controller
}

private fun Modifier.lensGestures(c: LensController, itemW: Float): Modifier =
    pointerInput(c.n, itemW) { awaitEachGesture { lensGesture(c, itemW) } }

@Composable
private fun LensNavigationBar(
    navigationItems: List<Screens>,
    currentRoute: String?,
    onItemClick: (Screens, Boolean) -> Unit,
    modifier: Modifier,
    pureBlack: Boolean,
    slimNav: Boolean,
    onSearchLongClick: (() -> Unit)?,
    onHomeLongHold: (() -> Unit)?,
    foldState: NavBarFoldState?,
) {
    // a new page (or tab) always starts with the bar open
    LaunchedEffect(currentRoute) { foldState?.expand() }
    val n = navigationItems.size.coerceAtLeast(1)
    val selectedIndex = remember(currentRoute, navigationItems) {
        navigationItems.indexOfFirst { isRouteSelected(currentRoute, it.route, navigationItems) }.coerceAtLeast(0)
    }
    val itemsNow by rememberUpdatedState(navigationItems)
    val onItemClickNow by rememberUpdatedState(onItemClick)
    val controller = rememberLensController(n, selectedIndex) { target -> onItemClickNow(itemsNow[target], false) }

    Box(
        modifier = modifier.background(if (pureBlack && LocalUiStyle.current != UiStyle.GLASS) Color.Black else Color.Transparent),
        contentAlignment = Alignment.TopCenter,
    ) {
        // Kaca: the floating Liquid bar with its fold on EVERY Android version (GlassBarPolicy). The material follows the
        // phone: lens (Android 13+), blur (Android 12+, "Blur kaca" on) or frost (no backdrop: Android 8-11 or blur off).
        // Before 4.3.4 anything below Android 13 got the old GlassLensBar here, which could not fold.
        if (GlassBarPolicy.liquidBar(kaca = LocalUiStyle.current == UiStyle.GLASS)) {
            LiquidNavBar(navigationItems, selectedIndex, onItemClick, LocalAppBackdrop.current, slimNav, onSearchLongClick, onHomeLongHold, foldState)
        } else {
            PillNavBar(navigationItems, selectedIndex, controller, onItemClick, pureBlack, slimNav, onSearchLongClick, onHomeLongHold)
        }
    }
}

@Composable
private fun PillNavBar(
    navigationItems: List<Screens>,
    selectedIndex: Int,
    ctl: LensController,
    onItemClick: (Screens, Boolean) -> Unit,
    pureBlack: Boolean,
    slimNav: Boolean,
    onSearchLongClick: (() -> Unit)?,
    onHomeLongHold: (() -> Unit)?,
) {
    val n = ctl.n
    val scheme = MaterialTheme.colorScheme
    val capsuleShape = remember { RoundedCornerShape(22.dp) }
    val pillShape = remember { RoundedCornerShape(17.dp) }
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge
    val labels = navigationItems.map { stringResource(it.titleId) }
    val labelWidths = remember(labels, labelStyle) {
        labels.map { measurer.measure(AnnotatedString(it), labelStyle).size.width.toFloat() }
    }
    val gapPx = with(LocalDensity.current) { 8.dp.toPx() }
    val basePillPx = with(LocalDensity.current) { 46.dp.toPx() }
    val barHeight = if (slimNav) 52.dp else 62.dp

    Box(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .padding(top = 6.dp)
            .fillMaxWidth()
            .height(barHeight)
            .graphicsLayer {
                val s = 1f + 0.014f * ctl.press.value
                scaleX = s
                scaleY = s
            }
            .clip(capsuleShape)
            .background(scheme.surfaceContainer)
            .border(1.dp, scheme.outlineVariant, capsuleShape)
            .padding(5.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val widthPx = constraints.maxWidth.toFloat()
            val itemW = widthPx / n

            // the pill: centre follows the lens position, width = icon + the labels that are fading in
            Box(
                modifier = Modifier
                    .layout { measurable, c ->
                        val p = ctl.pos.value
                        var w = basePillPx
                        for (i in 0 until n) {
                            val closeness = (1f - abs(p - i)).coerceIn(0f, 1f)
                            w += closeness * (labelWidths[i] + gapPx)
                        }
                        val pw = w.roundToInt().coerceAtMost(c.maxWidth)
                        val placeable = measurable.measure(Constraints.fixed(pw, c.maxHeight))
                        layout(c.maxWidth, c.maxHeight) {
                            val cx = (p + 0.5f) * itemW
                            placeable.place((cx - pw / 2f).roundToInt(), 0)
                        }
                    }
                    .graphicsLayer {
                        val lift = ctl.press.value
                        scaleX = 1f + 0.07f * lift + 0.12f * ctl.stretch.value
                        scaleY = 1f + 0.05f * lift
                    }
                    .clip(pillShape)
                    .background(scheme.onSurface.copy(alpha = 0.07f))
                    .border(1.dp, scheme.onSurface.copy(alpha = 0.16f), pillShape),
            )

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .lensGestures(ctl, itemW),
            ) {
                navigationItems.forEachIndexed { index, screen ->
                    val isActive = if (ctl.dragging) ctl.nearest == index else selectedIndex == index
                    val iconRes = if (isActive) screen.iconIdActive else screen.iconIdInactive
                    val isSearchItem = screen == Screens.Search && onSearchLongClick != null
                    val isHomeHoldItem = screen == Screens.Home && onHomeLongHold != null
                    val interactionSource = remember { MutableInteractionSource() }
                    NavLongPress(screen, interactionSource, selectedIndex == index, onItemClick, onSearchLongClick, onHomeLongHold)

                    val contentColor by animateColorAsState(
                        targetValue = if (isActive) scheme.onSurface else scheme.onSurface.copy(alpha = NavMutedAlpha),
                        animationSpec = tween(180),
                        label = "pillNavContent",
                    )
                    val iconScale by animateFloatAsState(
                        targetValue = if (isActive) 1.12f else 1f,
                        animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
                        label = "pillNavIcon",
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                role = Role.Tab,
                                onClick = { if (!isSearchItem && !isHomeHoldItem) onItemClick(screen, selectedIndex == index) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(id = iconRes),
                                contentDescription = labels[index],
                                tint = contentColor,
                                modifier = Modifier
                                    .size(24.dp)
                                    .graphicsLayer { scaleX = iconScale; scaleY = iconScale },
                            )
                            if (!slimNav) {
                                Box(
                                    modifier = Modifier
                                        .layout { measurable, c ->
                                            val closeness = (1f - abs(ctl.pos.value - index)).coerceIn(0f, 1f)
                                            val pl = measurable.measure(Constraints())
                                            val w = ((pl.width + gapPx) * closeness).roundToInt()
                                            layout(w, pl.height) { pl.placeRelative((gapPx * closeness).roundToInt(), 0) }
                                        }
                                        .graphicsLayer { alpha = ((1f - abs(ctl.pos.value - index)) * 1.3f - 0.3f).coerceIn(0f, 1f) },
                                ) {
                                    Text(
                                        text = labels[index],
                                        style = labelStyle,
                                        color = contentColor,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Clip,
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

/** Rubber band: beyond the first/last tab the lens moves at a fraction of the finger. */
private fun rubber(raw: Float, n: Int): Float {
    val max = (n - 1).toFloat()
    return when {
        raw < 0f -> raw * 0.45f
        raw > max -> max + (raw - max) * 0.45f
        else -> raw
    }
}


/**
 * iOS 26 tab bar: a clear glass capsule that refracts what is behind it, with a glass "puck" under the selected tab.
 * Hold and drag the puck: it swells, lifts past the bar and bends the icons beneath it. (Engine: vendored Kyant0/backdrop.)
 * [backdrop] null (Android 8-11, or "Blur kaca" off): the same bar, puck and fold in the frost material (GlassFrost.kt).
 */
@Composable
private fun LiquidNavBar(
    navigationItems: List<Screens>,
    selectedIndex: Int,
    onItemClick: (Screens, Boolean) -> Unit,
    backdrop: com.metrolist.music.ui.component.backdrop.backdrops.LayerBackdrop?,
    slimNav: Boolean,
    onSearchLongClick: (() -> Unit)?,
    onHomeLongHold: (() -> Unit)?,
    foldState: NavBarFoldState? = null,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val containerColor = com.metrolist.music.ui.theme.HorizonGlass.G1.veil(dark)
    // the glass level of this phone (lens at rest / frost only), see GlassLensPolicy; FLAT whenever backdrop is null
    val lensMode = rememberGlassLensMode()
    // sampled only when GlassBarPolicy says LENS or BLUR; FROST (no backdrop, or FLAT) draws without it
    val glassBackdrop = backdrop?.takeIf { GlassBarPolicy.material(lensMode, backdropAvailable = true) != BarMaterial.FROST }
    val barHeight = com.metrolist.music.ui.component.motion.NavFoldGeometry.pillSizeDp(slimNav).dp

    // Fold on scroll: 0 = open bar, 1 = compact pill. Only `folded` is read in composition (it flips on a
    // threshold crossing); the animated value is read in draw/graphicsLayer, or through derived booleans.
    val foldTarget = foldState?.folded == true
    val fold = remember { Animatable(if (foldTarget) 1f else 0f) }
    LaunchedEffect(foldTarget) {
        fold.animateTo(
            if (foldTarget) 1f else 0f,
            if (foldTarget) spring<Float>(dampingRatio = 0.8f, stiffness = 360f) else spring<Float>(dampingRatio = 0.72f, stiffness = 420f),
        )
    }
    LaunchedEffect(foldState) {
        if (foldState != null) androidx.compose.runtime.snapshotFlow { fold.value }.collect { foldState.progress = it }
    }
    // generous thresholds (the bar is invisible above 0.75, the pill below 0.35) so the spring's
    // overshoot never flips them back and forth
    val barGone by remember { derivedStateOf { fold.value >= 0.9f } }
    val pillShown by remember { derivedStateOf { fold.value > 0.1f } }
    val showBar = !foldTarget || !barGone
    val showPill = foldTarget || pillShown
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val gooeyPx = with(LocalDensity.current) { 10.dp.toPx() }

    Box(
        Modifier
            .fillMaxWidth()
            .then(
                if (foldState != null) {
                    // merge/split of bar and pill; a no-op at rest and below Android 12
                    Modifier.gooey {
                        val p = fold.value.coerceIn(0f, 1f)
                        gooeyPx * 4f * p * (1f - p)
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        if (showBar) {
            Box(
                Modifier
                    .padding(horizontal = com.metrolist.music.ui.component.motion.NavFoldGeometry.BAR_SIDE_DP.dp)
                    .padding(top = com.metrolist.music.ui.component.motion.NavFoldGeometry.BAR_TOP_DP.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        val p = fold.value.coerceIn(0f, 1f)
                        if (p > 0f) {
                            // retract towards the pill at the start edge
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (rtl) 1f else 0f, 0.5f)
                            scaleX = androidx.compose.ui.util.lerp(1f, (size.height / size.width.coerceAtLeast(1f)).coerceAtMost(1f), p)
                            // stays solid until the pill has appeared (the gooey alpha threshold would turn
                            // two overlapping half-faded shapes into a blank frame)
                            alpha = ((0.75f - p) / 0.2f).coerceIn(0f, 1f)
                        } else {
                            scaleX = 1f
                            alpha = 1f
                        }
                    },
            ) {
                LiquidTabsBar(navigationItems, selectedIndex, onItemClick, glassBackdrop, slimNav, onSearchLongClick, onHomeLongHold, containerColor, barHeight, lensMode)
            }
        }
        if (showPill && foldState != null) {
            FoldedNavPill(
                screen = navigationItems.getOrNull(selectedIndex),
                backdrop = glassBackdrop,
                containerColor = containerColor,
                size = barHeight,
                rtl = rtl,
                fold = { fold.value },
                onExpand = { foldState.expand() },
                lensMode = lensMode,
            )
        }
    }
}

@Composable
private fun LiquidTabsBar(
    navigationItems: List<Screens>,
    selectedIndex: Int,
    onItemClick: (Screens, Boolean) -> Unit,
    backdrop: com.metrolist.music.ui.component.backdrop.backdrops.LayerBackdrop?,
    slimNav: Boolean,
    onSearchLongClick: (() -> Unit)?,
    onHomeLongHold: (() -> Unit)?,
    containerColor: Color,
    barHeight: androidx.compose.ui.unit.Dp,
    lensMode: LensMode,
) {
    val scheme = MaterialTheme.colorScheme
    val selState = rememberUpdatedState(selectedIndex)
    val itemsState = rememberUpdatedState(navigationItems)
    val clickState = rememberUpdatedState(onItemClick)
    // The tab that was tapped, before the route has changed: the puck answers the tap in the next frame instead of waiting for the
    // new page to be built (LiquidBottomTabs then navigates, two frames after the puck has left).
    var pending by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    LaunchedEffect(selectedIndex) { pending = -1 }
    LaunchedEffect(pending) {
        if (pending >= 0) {
            kotlinx.coroutines.delay(1500)
            pending = -1
        }
    }
    com.metrolist.music.ui.component.backdrop.catalog.components.LiquidBottomTabs(
        selectedTabIndex = { if (pending >= 0) pending else selState.value },
        onTabSelected = { i -> if (i != selState.value && i in itemsState.value.indices) clickState.value(itemsState.value[i], false) },
        backdrop = backdrop,
        tabsCount = navigationItems.size.coerceAtLeast(1),
        modifier = Modifier,
        // no accent: the copy of the tabs that the puck shows is tinted with onSurface, so the tab under it stays neutral
        accent = scheme.onSurface,
        containerColor = containerColor,
        height = barHeight,
        lensMode = lensMode,
        // the tab whose page is on screen: a tap on another tab swells the bubble, builds the page, then glides (see LiquidBottomTabs)
        routeIndex = { selState.value },
    ) {
        navigationItems.forEachIndexed { index, screen ->
            val isSelected = selectedIndex == index
            val iconRes = if (isSelected) screen.iconIdActive else screen.iconIdInactive
            val color = if (isSelected) scheme.onSurface else scheme.onSurface.copy(alpha = NavMutedAlpha)
            val longClick: (() -> Unit)? =
                when {
                    screen == Screens.Search && onSearchLongClick != null -> onSearchLongClick
                    screen == Screens.Home && onHomeLongHold != null -> onHomeLongHold
                    else -> null
                }
            LiquidBottomTab(
                onClick = {
                    // the current tab: scroll to top, at once; another tab: the puck leaves now and the page follows
                    if (isSelected) onItemClick(screen, true) else pending = index
                },
                onLongClick = longClick,
            ) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = stringResource(screen.titleId),
                    tint = color,
                    modifier = Modifier.size(if (slimNav) 24.dp else 26.dp),
                )
                if (!slimNav) {
                    Text(
                        text = stringResource(screen.titleId),
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val FoldedPillShape = RoundedCornerShape(percent = 50)

/**
 * The folded bar: one glass circle at the start edge showing the current tab's icon. Tapping it unfolds
 * the bar. Its scale/alpha follow [fold] in the graphics layer only, so the animation never recomposes.
 */
@Composable
private fun FoldedNavPill(
    screen: Screens?,
    backdrop: com.metrolist.music.ui.component.backdrop.backdrops.LayerBackdrop?,
    containerColor: Color,
    size: androidx.compose.ui.unit.Dp,
    rtl: Boolean,
    fold: () -> Float,
    onExpand: () -> Unit,
    lensMode: LensMode,
) {
    val iconColor = MaterialTheme.colorScheme.onSurface
    val pillRecipe = remember(lensMode) { GlassLensRecipes.navBar(lensMode) }
    val pillVeil = remember(containerColor, pillRecipe) { containerColor.thinned(pillRecipe.veilScale) }
    val expandLabel =stringResource(com.metrolist.music.R.string.nav_bar_expand)
    Box(
        modifier =
            Modifier
                .padding(horizontal = com.metrolist.music.ui.component.motion.NavFoldGeometry.BAR_SIDE_DP.dp)
                .padding(top = com.metrolist.music.ui.component.motion.NavFoldGeometry.BAR_TOP_DP.dp)
                .size(size)
                .graphicsLayer {
                    val p = fold()
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (rtl) 1f else 0f, 0.5f)
                    // grows out of the retracting bar; the spring's small overshoot gives it a soft landing
                    val s = androidx.compose.ui.util.lerp(0.72f, 1f, p.coerceIn(0f, 1.08f))
                    scaleX = s
                    scaleY = s
                    alpha = ((p - 0.35f) / 0.2f).coerceIn(0f, 1f)
                }
                .pressScale(0.94f)
                .then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { FoldedPillShape },
                            effects = {
                                // same material as the bar (see LiquidBottomTabs): lens at rest in LENS mode, heavy frost in BLUR mode
                                applyGlassRecipe(pillRecipe, com.metrolist.music.ui.component.backdrop.catalog.components.NavGlassScale)
                            },
                            onDrawSurface = { drawRect(pillVeil) },
                            backdropScale = com.metrolist.music.ui.component.backdrop.catalog.components.NavGlassScale,
                        )
                    } else {
                        // no backdrop: the bar's frost material (GlassFrost.kt), so pill, bar and mini player match
                        Modifier.glassFrost(FoldedPillShape)
                    },
                )
                .clickable(
                    interactionSource = null,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = expandLabel,
                    onClick = onExpand,
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (screen != null) {
            Icon(
                painter = painterResource(id = screen.iconIdActive),
                contentDescription = stringResource(screen.titleId),
                tint = iconColor,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}
