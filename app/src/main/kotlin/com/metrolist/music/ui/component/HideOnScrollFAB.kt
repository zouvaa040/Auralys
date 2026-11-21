/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.MiniPlayerHeight
import com.metrolist.music.ui.component.motion.LocalNavFold
import com.metrolist.music.ui.theme.isGlassStyle
import com.metrolist.music.ui.utils.isScrollingUp

@Composable
fun BoxScope.HideOnScrollFAB(
    visible: Boolean = true,
    lazyListState: LazyListState,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    onRecognitionClick: (() -> Unit)? = null,
    onRandomClick: (() -> Unit)? = null,
    isRandomizing: Boolean = false,
) {
    // the floating shuffle button was removed; pages that also carry the recognition shortcut keep that one
    val isShuffle = icon == R.drawable.shuffle
    // the mic on the search pages is the same recognition shortcut as on Home: the small neutral button, never a main one
    val isMic = icon == R.drawable.mic
    val recognitionClick = onRecognitionClick ?: if (isMic) onClick else null
    if (isShuffle && recognitionClick == null) return
    val showMain = !isShuffle && !isMic
    AnimatedVisibility(
        visible = visible && lazyListState.isScrollingUp(),
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier =
        Modifier
            .align(Alignment.BottomEnd)
            // Liquid Glass fold: drops with the mini player when the bar folds into its pill
            .followNavFold()
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current
                    .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp)
        ) {
            if (recognitionClick != null) {
                SmallGlassFab(
                    onClick = recognitionClick,
                    icon = R.drawable.mic,
                    contentDescription = stringResource(R.string.recognize_music),
                )
                if (onRandomClick != null || showMain) Spacer(modifier = Modifier.height(12.dp))
            }
            if (onRandomClick != null) {
                // the old dice tile of the speed dial: same action, a small glass button like the recognition one
                SmallGlassFab(
                    onClick = onRandomClick,
                    icon = R.drawable.shuffle,
                    contentDescription = stringResource(R.string.home_random),
                    loading = isRandomizing,
                )
                if (showMain) Spacer(modifier = Modifier.height(12.dp))
            }
            if (showMain) {
                NeutralFab(
                    onClick = onClick,
                    icon = icon,
                    contentDescription = null,
                )
            }
        }
    }
}

/**
 * Makes a floating button follow the Liquid Glass nav bar fold: when the bar shrinks to its pill the mini player drops next to it,
 * and a button that floats above the mini player has to drop by the same amount (same formula as the collapsed player sheet in
 * Player.kt) or it hangs in the middle of the page. Read in the graphics layer, so the animation never recomposes anything.
 * No-op when there is no folding bar (other styles, older Android, pages without the bar).
 */
@Composable
fun Modifier.followNavFold(): Modifier {
    val fold = LocalNavFold.current ?: return this
    return this.graphicsLayer {
        val p = fold.followProgress
        if (p != 0f) {
            translationY = navFoldDropDp(fold.miniShiftDp, fold.pillSizeDp, MiniPlayerHeight.value).dp.toPx() * p
        }
    }
}

/**
 * How far (dp) the collapsed mini player sheet drops at full fold. The folded capsule is top-aligned in the mini player box and grows
 * downwards, so its centre is (pill - box) / 2 lower than when it was centred: the sheet drops that much less than [miniShiftDp].
 * Same formula as `collapsedShiftPx` in Player.kt; the floating buttons use it so they keep their gap above the capsule.
 */
internal fun navFoldDropDp(miniShiftDp: Float, pillSizeDp: Float, miniPlayerHeightDp: Float): Float =
    miniShiftDp - (pillSizeDp - miniPlayerHeightDp) / 2f

/** The small round button of the stack (recognition, random): a neutral glass disc in Liquid Glass, a flat surface square otherwise. */
@Composable
private fun SmallGlassFab(
    onClick: () -> Unit,
    @DrawableRes icon: Int,
    contentDescription: String,
    loading: Boolean = false,
) {
    NeutralFab(
        onClick = onClick,
        icon = icon,
        contentDescription = contentDescription,
        small = true,
        loading = loading,
    )
}

/**
 * The floating button of the app, with no accent colour anywhere. Liquid Glass: a translucent glass disc (rim, sheen) with the icon in
 * onSurface. It never samples the app backdrop, because it lives inside the page that backdrop records. Editorial: a neutral
 * surface square with a hairline. [small] is the 48 dp (glass) / 40 dp (editorial) variant, otherwise 56 dp.
 */
@Composable
fun NeutralFab(
    onClick: () -> Unit,
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    loading: Boolean = false,
) {
    val glass = isGlassStyle
    val scheme = MaterialTheme.colorScheme
    val shape = if (glass) CircleShape else RoundedCornerShape(if (small) 8.dp else 14.dp)
    val side = if (small) (if (glass) 48.dp else 40.dp) else 56.dp
    val look =
        if (glass) {
            Modifier.size(side).glass(shape, strong = true)
        } else {
            Modifier.size(side).border(1.dp, scheme.outlineVariant, shape)
        }
    val container = if (glass) Color.Transparent else scheme.surfaceContainerHigh
    val content: @Composable () -> Unit = {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                painter = painterResource(icon),
                contentDescription = contentDescription,
                modifier = Modifier.size(if (small) 20.dp else 24.dp),
            )
        }
    }
    val none = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)
    if (small) {
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = container,
            contentColor = scheme.onSurface,
            shape = shape,
            elevation = none,
            modifier = modifier.then(look),
            content = content,
        )
    } else {
        FloatingActionButton(
            onClick = onClick,
            containerColor = container,
            contentColor = scheme.onSurface,
            shape = shape,
            elevation = none,
            modifier = modifier.then(look),
            content = content,
        )
    }
}
