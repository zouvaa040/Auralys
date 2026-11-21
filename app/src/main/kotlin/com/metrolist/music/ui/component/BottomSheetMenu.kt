/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetDefaults
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState as rememberMaterialBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val LocalMenuState = compositionLocalOf { MenuState() }

@Stable
class MenuState(
    isVisible: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    var isVisible by mutableStateOf(isVisible)
    var content by mutableStateOf(content)

    fun show(content: @Composable ColumnScope.() -> Unit) {
        isVisible = true
        this.content = content
    }

    fun dismiss() {
        isVisible = false
    }
}

/**
 * Bottom sheet used by every menu. Built on a plain dialog window instead of Material's ModalBottomSheet: that one
 * stopped at a half-way detent in this Compose version and left tall menus half off-screen and impossible to drag up.
 * This sheet is as tall as its content (at most 90% of the screen), the content scrolls inside it, and it closes by
 * dragging the handle down, tapping the dim area, or the back button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimatedBottomSheet(
    isVisible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") sheetState: SheetState = rememberMaterialBottomSheetState(initialValue = SheetValue.Hidden),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    // Kaca: 40 dp iOS corners; Datar: 28 dp (MenuStyle.kt)
    shape: Shape = menuSheetShape(),
    @Suppress("UNUSED_PARAMETER") containerColor: Color = MaterialTheme.colorScheme.surface,
    @Suppress("UNUSED_PARAMETER") contentColor: Color = contentColorFor(containerColor),
    @Suppress("UNUSED_PARAMETER") tonalElevation: Dp = 0.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { MenuGrabber() },
    @Suppress("UNUSED_PARAMETER") contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.modalWindowInsets },
    @Suppress("UNUSED_PARAMETER") properties: ModalBottomSheetProperties = ModalBottomSheetDefaults.properties,
    content: @Composable ColumnScope.() -> Unit,
) {
    var lastContent by remember { mutableStateOf(content) }
    LaunchedEffect(content) {
        if (isVisible) lastContent = content
    }
    val visibleState = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    visibleState.targetState = isVisible
    // fully closed and finished animating out: nothing to compose
    if (!visibleState.currentState && !visibleState.targetState) return

    // Back closes the sheet. Registered only while it is on screen, so it sits above the player's own back handler.
    androidx.activity.compose.BackHandler(enabled = isVisible, onBack = onDismissRequest)

    // Drawn inside the app window as the last child of the root box (was a Dialog: a new window, a second composition
    // root and a first frame that had to wait for both, which is where the player menu hitched when opened).
    run {
        val maxHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.9f).dp
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val dragY = remember { androidx.compose.animation.core.Animatable(0f) }
        val noRipple = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val noRipple2 = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .androidxClickable(noRipple, onDismissRequest),
            contentAlignment = androidx.compose.ui.Alignment.BottomCenter,
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visibleState = visibleState,
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)),
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)),
            ) {
                Box(Modifier.fillMaxSize().background(scrimColor))
            }
            androidx.compose.animation.AnimatedVisibility(
                visibleState = visibleState,
                // iOS animations on: a spring that settles visibly but quickly. Off: the iOS sheet curve (a fast start, a soft landing).
                // Leaving is shorter than arriving and accelerates (ease-in), as on iOS.
                enter = if (LocalIosMotion.current) {
                    androidx.compose.animation.slideInVertically(androidx.compose.animation.core.spring(dampingRatio = 0.78f, stiffness = 420f)) { it }
                } else {
                    androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(340, easing = MenuLook.SheetEasing)) { it }
                },
                exit = androidx.compose.animation.slideOutVertically(
                    androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.FastOutLinearInEasing),
                ) { it },
            ) {
                Column(
                    modifier = modifier
                        .widthIn(max = sheetMaxWidth)
                        .fillMaxWidth()
                        // a layer property, not a layout offset: dragging and sliding move a cached layer
                        .graphicsLayer { translationY = dragY.value.coerceAtLeast(0f) }
                        .heightIn(max = maxHeight)
                        .glassOverlay(shape, 32.dp)
                        .androidxClickable(noRipple2) {}, // taps on the sheet must not reach the dim area behind it
                ) {
                    if (dragHandle != null) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .androidxDragToDismiss(
                                    onDrag = { dy -> scope.launch { dragY.snapTo((dragY.value + dy).coerceAtLeast(0f)) } },
                                    onEnd = {
                                        scope.launch {
                                            if (dragY.value > 160f) onDismissRequest() else dragY.animateTo(0f, androidx.compose.animation.core.spring(0.7f, 500f))
                                        }
                                    },
                                ),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) { dragHandle() }
                    }
                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            .androidx_navPadding(),
                    ) { lastContent() }
                }
            }
        }
    }
}

private fun Modifier.androidxClickable(
    source: androidx.compose.foundation.interaction.MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = this.then(Modifier.clickable(interactionSource = source, indication = null, onClick = onClick))

private fun Modifier.androidxDragToDismiss(
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit,
): Modifier =
    this.pointerInput(Unit) {
        detectVerticalDragGestures(
            onDragEnd = onEnd,
            onDragCancel = onEnd,
            onVerticalDrag = { _, dy -> onDrag(dy) },
        )
    }

private fun Modifier.androidx_navPadding(): Modifier = this.navigationBarsPadding().imePadding()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetMenu(
    modifier: Modifier = Modifier,
    state: MenuState,
    background: Color = MaterialTheme.colorScheme.surface,
) {
    val focusManager = LocalFocusManager.current
    val sheetState = rememberMaterialBottomSheetState(initialValue = SheetValue.Hidden)

    AnimatedBottomSheet(
        isVisible = state.isVisible,
        onDismissRequest = {
            focusManager.clearFocus()
            state.isVisible = false
        },
        sheetState = sheetState,
        containerColor = background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = { MenuGrabber() },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            state.content(this)
        }
    }
}
