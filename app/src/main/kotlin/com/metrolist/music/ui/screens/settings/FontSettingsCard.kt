/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Settings > Appearance > Theme: pick the app typeface and shift its weight (thin ... black).
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import com.metrolist.music.ui.component.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.constants.AppFontKey
import com.metrolist.music.constants.AppFontWeightKey
import com.metrolist.music.ui.component.glassCard
import com.metrolist.music.ui.component.glassShape
import com.metrolist.music.ui.theme.AppFont
import com.metrolist.music.ui.theme.HorizonFontFamily
import com.metrolist.music.ui.theme.HorizonRadius
import com.metrolist.music.ui.theme.family
import com.metrolist.music.ui.theme.weightShiftLabel
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference

@Composable
fun FontSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val (font, setFont) = rememberEnumPreference(AppFontKey, AppFont.STYLE)
    val (shift, setShift) = rememberPreference(AppFontWeightKey, 0)
    val shape = glassShape(HorizonRadius.Lg, HorizonRadius.Lg)
    val base = (shift * 100 + 400).coerceIn(100, 900)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassCard(shape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.font_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)

        // live preview in the chosen font and weight
        val family = remember(font) { font.family(context.assets) }
        Column(Modifier.fillMaxWidth()) {
            val sample = TextStyle(fontFamily = family ?: HorizonFontFamily, fontWeight = FontWeight(base))
            Text("Auralys", style = MaterialTheme.typography.displaySmall.merge(sample))
            Text(stringResource(R.string.font_sample), style = MaterialTheme.typography.bodyLarge.merge(sample))
            Text("Aa Bb Cc 0123456789", style = MaterialTheme.typography.titleMedium.merge(sample))
        }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppFont.entries.forEach { f ->
                val fam = remember(f) { f.family(context.assets) }
                val selected = f == font
                Text(
                    text = f.label,
                    style = MaterialTheme.typography.titleSmall.merge(TextStyle(fontFamily = fam ?: if (f == AppFont.STYLE) HorizonFontFamily else FontFamily.Default)),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHigh)
                        .then(if (selected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(50)) else Modifier)
                        .clickable { setFont(f) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
        Text(font.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.font_weight), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(weightShiftLabel(shift), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = shift.toFloat(),
            onValueChange = { setShift(it.toInt().coerceIn(-3, 3)) },
            valueRange = -3f..3f,
            steps = 5,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.font_thin), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.font_heavy), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(2.dp))
        OutlinedButton(
            onClick = {
                setFont(AppFont.STYLE)
                setShift(0)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.font_reset)) }
    }
}
