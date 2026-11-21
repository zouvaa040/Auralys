/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Top of the Home screen: a time-of-day greeting and a compact "Play again" grid of your latest songs, tap to play.
 * Built from the local listening history, so it shows instantly and needs no network.
 */

package com.metrolist.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.layout
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.component.glassCard
import com.metrolist.music.ui.component.glassShape
import com.metrolist.music.ui.component.pressScale
import com.metrolist.music.ui.component.riseIn
import com.metrolist.music.ui.screens.search.SearchDiscoveryViewModel
import java.util.Calendar

@Composable
fun HomeGreeting(
    modifier: Modifier = Modifier,
    viewModel: SearchDiscoveryViewModel = hiltViewModel(),
) {
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val playerConnection = LocalPlayerConnection.current
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting =
        stringResource(
            when (hour) {
                in 4..10 -> R.string.greeting_morning
                in 11..14 -> R.string.greeting_noon
                in 15..17 -> R.string.greeting_afternoon
                else -> R.string.greeting_evening
            },
        )

    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // the greeting is the large title of Beranda (it collapses into the "Beranda" of the top bar when the list is scrolled)
        com.metrolist.music.ui.component.LargeTitleHeader(
            title = greeting,
            // this column already keeps the 20 dp gutter that the row adds itself: let the row grow out of it
            modifier =
                Modifier.layout { measurable, constraints ->
                    val extra = 20.dp.roundToPx()
                    val placeable =
                        measurable.measure(
                            constraints.copy(minWidth = constraints.maxWidth + 2 * extra, maxWidth = constraints.maxWidth + 2 * extra),
                        )
                    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
                },
        )
        val tiles = recent.take(6)
        if (tiles.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                tiles.chunked(2).forEachIndexed { rowIndex, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEachIndexed { colIndex, song ->
                            val index = rowIndex * 2 + colIndex
                            Row(
                                Modifier
                                    .weight(1f)
                                    .height(56.dp)
                                    .glassCard(glassShape(16.dp, 10.dp))
                                    .pressScale(0.97f)
                                    .clickable {
                                        playerConnection?.playQueue(ListQueue(title = null, items = tiles.map { it.toMediaItem() }, startIndex = index))
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AsyncImage(
                                    model = song.thumbnailUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                )
                                Text(
                                    song.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 10.dp),
                                )
                            }
                        }
                        if (pair.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
