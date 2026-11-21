/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * A dialog that picks one value of a list. Kaca: the iOS look (17 sp rows divided by hairlines, a checkmark on the chosen one);
 * Datar: a tidy list with a radio button. It shows its [title] now (the argument always existed but was never drawn).
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.R
import com.metrolist.music.ui.theme.isGlassStyle

@Composable
fun <T> EnumDialog(
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
    title: String,
    current: T,
    values: List<T>,
    valueText: @Composable (T) -> String,
    valueDescription: (@Composable (T) -> String)? = null,
) {
    val glass = isGlassStyle
    ListDialog(
        onDismiss = onDismiss,
        title = title,
    ) {
        itemsIndexed(values) { index, value ->
            val selected = value == current
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(value)
                            }.padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    if (!glass) {
                        RadioButton(
                            selected = selected,
                            onClick = null,
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f).padding(start = if (glass) 0.dp else 16.dp),
                    ) {
                        Text(
                            text = valueText(value),
                            fontSize = if (glass) 17.sp else 16.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = TextAlign.Start,
                        )
                        if (valueDescription != null) {
                            Text(
                                text = valueDescription(value),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Start,
                            )
                        }
                    }

                    if (glass && selected) {
                        Icon(
                            painter = painterResource(R.drawable.check),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 12.dp).size(22.dp),
                        )
                    }
                }
                if (glass && index < values.lastIndex) {
                    DialogHairline(inset = 20.dp)
                }
            }
        }
    }
}
