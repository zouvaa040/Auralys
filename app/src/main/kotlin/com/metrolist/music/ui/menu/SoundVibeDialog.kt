package com.metrolist.music.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.playback.audio.SoundVibe
import com.metrolist.music.ui.component.DefaultDialog

fun SoundVibe.titleRes(): Int =
    when (this) {
        SoundVibe.NORMAL -> R.string.auralys_vibe_normal
        SoundVibe.SPED_UP -> R.string.auralys_vibe_sped_up
        SoundVibe.SLOWED_REVERB -> R.string.auralys_vibe_slowed
        SoundVibe.NIGHTCORE -> R.string.auralys_vibe_nightcore
        SoundVibe.LOFI -> R.string.auralys_vibe_lofi
        SoundVibe.SPATIAL_8D -> R.string.auralys_vibe_8d
        SoundVibe.CONCERT -> R.string.auralys_vibe_concert
    }

private fun SoundVibe.descriptionRes(): Int =
    when (this) {
        SoundVibe.NORMAL -> R.string.auralys_vibe_normal_desc
        SoundVibe.SPED_UP -> R.string.auralys_vibe_sped_up_desc
        SoundVibe.SLOWED_REVERB -> R.string.auralys_vibe_slowed_desc
        SoundVibe.NIGHTCORE -> R.string.auralys_vibe_nightcore_desc
        SoundVibe.LOFI -> R.string.auralys_vibe_lofi_desc
        SoundVibe.SPATIAL_8D -> R.string.auralys_vibe_8d_desc
        SoundVibe.CONCERT -> R.string.auralys_vibe_concert_desc
    }

/** Pick one of the one-tap sound styles; it applies immediately. */
@Composable
fun SoundVibeDialog(
    selected: SoundVibe,
    onSelect: (SoundVibe) -> Unit,
    onDismiss: () -> Unit,
) {
    DefaultDialog(
        onDismiss = onDismiss,
        title = { Text(stringResource(R.string.auralys_vibe_title)) },
        buttons = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        },
    ) {
        // seven styles do not fit a short screen (or a landscape one), so the list scrolls
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            SoundVibe.entries.forEach { vibe ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onSelect(vibe) }
                        .padding(vertical = 6.dp),
                ) {
                    RadioButton(selected = vibe == selected, onClick = { onSelect(vibe) })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = stringResource(vibe.titleRes()),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(vibe.descriptionRes()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
