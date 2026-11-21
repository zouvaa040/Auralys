/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Settings > Appearance > Theme and colors: "Efek berbayang". The three levels (Mati, Lembut, Kuat) ARE the live preview:
 * each choice is a small page (a title and three rows) washed with the colour of the cover that plays, or with a fixed
 * sample colour while nothing plays, drawn by the same code as the app background. Tap one to choose it.
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.constants.CoverShadeKey
import com.metrolist.music.ui.component.glassCard
import com.metrolist.music.ui.component.glassShape
import com.metrolist.music.ui.theme.CoverShadeLevel
import com.metrolist.music.ui.theme.HorizonRadius
import com.metrolist.music.ui.theme.LocalCoverShade
import com.metrolist.music.ui.theme.LocalHorizon
import com.metrolist.music.ui.theme.LocalUiStyle
import com.metrolist.music.ui.theme.UiStyle
import com.metrolist.music.ui.theme.coverShade
import com.metrolist.music.ui.theme.rememberCoverShade
import com.metrolist.music.utils.rememberEnumPreference

/** The cover colour of the previews while no song plays: a calm blue, so the effect is visible to someone who has not played anything yet. */
private val PreviewSampleCover = Color(0xFF2F63D9)

@Composable
fun CoverShadeCard(modifier: Modifier = Modifier) {
    val (level, setLevel) = rememberEnumPreference(CoverShadeKey, CoverShadeLevel.DEFAULT)
    val cover = LocalCoverShade.current.sourceCover ?: PreviewSampleCover
    val shape = glassShape(HorizonRadius.Lg, HorizonRadius.Lg)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassCard(shape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // No explanation line under the title: the three live previews show the effect.
        Text(
            stringResource(R.string.auralys_cover_shade_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CoverShadeLevel.entries.forEach { option ->
                CoverShadeChoice(
                    option = option,
                    cover = cover,
                    selected = option == level,
                    onClick = { setLevel(option) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun CoverShadeLevel.labelRes(): Int =
    when (this) {
        CoverShadeLevel.OFF -> R.string.auralys_cover_shade_off
        CoverShadeLevel.SOFT -> R.string.auralys_cover_shade_soft
        CoverShadeLevel.STRONG -> R.string.auralys_cover_shade_strong
    }

/** One level: a mini page with its wash, and the name under it. The chosen one has the ink outline the font chips have. */
@Composable
private fun CoverShadeChoice(
    option: CoverShadeLevel,
    cover: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val page = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    val rowFill = MaterialTheme.colorScheme.surfaceContainerHigh
    val preview =
        rememberCoverShade(
            level = option,
            cover = cover,
            dark = LocalHorizon.current.dark,
            pureBlack = page == Color.Black,
            flat = LocalUiStyle.current == UiStyle.EDITORIAL,
        )
    val frame = RoundedCornerShape(HorizonRadius.Md)

    Column(
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // decorative: the page is the preview, the name below is what a screen reader gets
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.62f)
                .clip(frame)
                .background(page)
                .coverShade(preview)
                .then(
                    if (selected) Modifier.border(1.5.dp, ink, frame) else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, frame),
                )
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(Modifier.width(38.dp).height(7.dp).clip(RoundedCornerShape(HorizonRadius.Xs)).background(ink.copy(alpha = 0.85f)))
            repeat(4) { i ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(20.dp).clip(RoundedCornerShape(HorizonRadius.Xs)).background(rowFill))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.width((40 - i * 4).dp).height(5.dp).clip(RoundedCornerShape(HorizonRadius.Xs)).background(ink.copy(alpha = 0.7f)))
                        Box(Modifier.width((26 - i * 2).dp).height(4.dp).clip(RoundedCornerShape(HorizonRadius.Xs)).background(ink.copy(alpha = 0.35f)))
                    }
                }
            }
        }
        Text(
            text = stringResource(option.labelRes()),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 2.dp),
        )
    }
}
