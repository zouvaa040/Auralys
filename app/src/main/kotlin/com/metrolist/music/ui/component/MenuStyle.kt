/**
 * Auralys - the look of every three-dot menu (the bottom sheet, its groups, tiles and header).
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Two looks, picked by the UI style:
 *  - Kaca (UiStyle.GLASS): an iOS sheet. A 36 x 5 dp grabber, 40 dp top corners, "inset grouped" lists (rounded groups on a
 *    translucent fill, hairlines inset from the text, rows 52 dp high with the title on the left and the icon on the RIGHT,
 *    as in an iOS context menu), tiles of 80 dp. The group radius is concentric with the sheet corner (40 dp - 16 dp inset
 *    = 24 dp): the same idea as iOS and as Convx's sheet radius, own numbers.
 *  - Datar (UiStyle.EDITORIAL): the editorial look, cooler: rows 56 dp with the icon on the left, groups parted by an inset
 *    hairline, tiles with a hairline outline instead of a heavy fill. No capsules.
 * Both: a pressed row gets a soft ink fill (no ripple), drawn behind the content.
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.metrolist.music.ui.theme.isGlassStyle
import com.metrolist.music.ui.utils.resize

/** The numbers of the menu look. dp unless said. */
internal object MenuLook {
    /** Top corner of the sheet in Kaca (iOS 26 sheets are about 38 to 44). Datar keeps the 28 dp sheet radius. */
    val SheetCornerGlass = 40.dp

    /** The content inset of the sheet's groups; the group radius is the sheet corner minus this (concentric). */
    val GroupInset = 16.dp
    val GroupRadiusGlass = SheetCornerGlass - GroupInset

    /** Space above a group: the groups of one menu do not touch. */
    val GroupGap = 16.dp

    val RowMinGlass = 52.dp
    val RowMinEditorial = 56.dp
    val TileMinGlass = 80.dp
    val TileMinEditorial = 84.dp
    val TileRadiusGlass = 20.dp
    val TileRadiusEditorial = 16.dp

    /** The sheet curve of iOS (a fast start that settles softly): used when iOS animations are off. */
    val SheetEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
}

/** The top corners of the menu sheet: 40 dp in Kaca (iOS), 28 dp in Datar. */
@Composable
internal fun menuSheetShape(): Shape =
    if (isGlassStyle) {
        RoundedCornerShape(topStart = MenuLook.SheetCornerGlass, topEnd = MenuLook.SheetCornerGlass)
    } else {
        RoundedCornerShape(topStart = HorizonRadiusSheet, topEnd = HorizonRadiusSheet)
    }

private val HorizonRadiusSheet = 28.dp

/** The handle on top of the sheet: the iOS grabber (36 x 5) in Kaca, the thin editorial one (36 x 4) in Datar. */
@Composable
internal fun MenuGrabber() {
    if (isGlassStyle) {
        Box(
            modifier =
                Modifier
                    .padding(top = 8.dp, bottom = 10.dp)
                    .size(width = 36.dp, height = 5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)),
        )
    } else {
        Box(
            modifier =
                Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        )
    }
}

/**
 * Click with the press feedback of the menus: a soft ink fill behind the content while the finger is down (it fades back in
 * 200 ms), no ripple. The fill is drawn in the draw phase, so a press recomposes nothing but the pressed flag.
 */
@Composable
internal fun Modifier.menuPressable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val ink = MaterialTheme.colorScheme.onSurface
    val shade =
        animateColorAsState(
            targetValue = ink.copy(alpha = if (pressed && enabled) 0.10f else 0f),
            animationSpec = tween(if (pressed) 40 else 200),
            label = "menuPressShade",
        )
    return this
        .drawBehind { drawRect(shade.value) }
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}

/** A 0.5 dp line between two rows of a group, inset from the start (it begins where the text begins, as on iOS). */
@Composable
internal fun MenuHairline(inset: Dp) {
    Box(
        Modifier
            .padding(start = inset)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
    )
}

/**
 * The song the menu is about, at the top of the sheet (Apple Music's song sheet starts the same way): the cover (48 dp, 8 dp
 * corners), the title and the artist. Only what the caller knows: nothing is looked up here.
 */
@Composable
fun MenuSongHeader(
    title: String,
    subtitle: String,
    coverUrl: String?,
    modifier: Modifier = Modifier,
) {
    val glass = isGlassStyle
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .padding(top = if (glass) 2.dp else 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = RoundedCornerShape(8.dp)
        Box(
            Modifier
                .size(48.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            if (coverUrl != null) {
                val model = remember(coverUrl) { coverUrl.resize(144, 144) }
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The volume row of the player's menu. In Kaca it sits in its own rounded group (an iOS slider row: the speakers at the ends,
 * the bar between); in Datar it is the bare control, as before.
 */
@Composable
fun MenuVolumeCard(content: @Composable () -> Unit) {
    if (isGlassStyle) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MenuLook.GroupRadiusGlass))
                .background(glassFill())
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            content()
        }
    } else {
        content()
    }
}
