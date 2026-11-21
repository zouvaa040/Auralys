/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Recap ("Replay"): a periodic listening recap built from the local listening history.
 * Follows both UI styles: glass surfaces in Liquid Glass, flat paper in Editorial.
 */

package com.metrolist.music.ui.screens

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.component.EmptyPlaceholder
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.TopAppBar
import com.metrolist.music.ui.component.glass
import com.metrolist.music.ui.component.glassCard
import com.metrolist.music.ui.component.glassShape
import com.metrolist.music.ui.component.glassTinted
import com.metrolist.music.ui.component.painterResource
import com.metrolist.music.ui.component.pressScale
import com.metrolist.music.ui.theme.HorizonRadius
import com.metrolist.music.ui.theme.isGlassStyle
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.ui.utils.resize
import com.metrolist.music.utils.RecapPosterRenderer
import com.metrolist.music.viewmodels.RecapArtist
import com.metrolist.music.viewmodels.RecapPeriod
import com.metrolist.music.viewmodels.RecapPeriodKind
import com.metrolist.music.viewmodels.RecapSong
import com.metrolist.music.viewmodels.RecapAlbum
import com.metrolist.music.viewmodels.RecapViewModel
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate

private const val SONGS_COLLAPSED = 10

/** Header word for a period: the year, or "Last 30 days" / "All time". */
@Composable
internal fun recapPeriodLabel(period: RecapPeriod): String =
    when (period.kind) {
        RecapPeriodKind.THIS_YEAR -> LocalDate.now().year.toString()
        RecapPeriodKind.YEAR -> period.year.toString()
        RecapPeriodKind.LAST_30_DAYS -> stringResource(R.string.auralys_recap_last_30_days)
        RecapPeriodKind.ALL_TIME -> stringResource(R.string.auralys_recap_all_time)
    }

/** Prominent entry shown at the top of the Stats screen. */
@Composable
fun RecapEntryCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = glassShape(HorizonRadius.Lg, HorizonRadius.Lg)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .pressScale(0.97f)
                .glassCard(shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.trending_up),
                contentDescription = null,
                tint = scheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.auralys_recap_your_recap, LocalDate.now().year.toString()),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.auralys_recap_entry_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            painter = painterResource(R.drawable.arrow_forward),
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecapScreen(
    navController: NavController,
    viewModel: RecapViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    val glass = isGlassStyle
    val scheme = MaterialTheme.colorScheme

    val data by viewModel.data.collectAsStateWithLifecycle()
    val period by viewModel.period.collectAsStateWithLifecycle()
    val pastYears by viewModel.pastYears.collectAsStateWithLifecycle()

    var showAllSongs by rememberSaveable { mutableStateOf(false) }
    var showStory by rememberSaveable { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }

    val periodLabel = recapPeriodLabel(period)
    val recapTitle = stringResource(R.string.auralys_recap_your_recap, periodLabel)
    val shareFailed = stringResource(R.string.auralys_recap_share_failed)

    val periods =
        remember(pastYears) {
            listOf(
                RecapPeriod(RecapPeriodKind.THIS_YEAR),
                RecapPeriod(RecapPeriodKind.LAST_30_DAYS),
                RecapPeriod(RecapPeriodKind.ALL_TIME),
            ) + pastYears.map { RecapPeriod(RecapPeriodKind.YEAR, it) }
        }

    fun playSongs(songs: List<RecapSong>, start: RecapSong?) {
        val playable = songs.filter { it.song != null }
        if (playable.isEmpty()) return
        val startIndex = start?.let { s -> playable.indexOfFirst { it.id == s.id } }?.coerceAtLeast(0) ?: 0
        playerConnection?.playQueue(
            ListQueue(
                title = recapTitle,
                items = playable.map { it.song!!.toMediaMetadata().toMediaItem() },
                startIndex = startIndex,
            ),
        )
    }

    fun share() {
        val d = data ?: return
        if (sharing || d.isEmpty) return
        sharing = true
        scope.launch {
            try {
                RecapPosterRenderer.share(context, d, recapTitle)
            } catch (e: Throwable) {
                Toast.makeText(context, shareFailed, Toast.LENGTH_SHORT).show()
            } finally {
                sharing = false
            }
        }
    }

    val nf = remember { NumberFormat.getIntegerInstance() }
    val sectionShape = glassShape(26.dp, 0.dp)

    LazyColumn(
        contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // ---- hero
        item(key = "hero") {
            val d = data
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .then(if (glass) Modifier.glassCard(glassShape(30.dp, 16.dp)) else Modifier)
                        .padding(horizontal = if (glass) 20.dp else 4.dp, vertical = 20.dp),
            ) {
                Text(
                    text = recapTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = nf.format(d?.totalMinutes ?: 0L),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = scheme.primary,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(R.string.auralys_recap_minutes_listened),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecapStatPill(stringResource(R.string.auralys_recap_songs_count, nf.format(d?.songCount ?: 0)))
                    RecapStatPill(stringResource(R.string.auralys_recap_artists_count, nf.format(d?.artistCount ?: 0)))
                }
            }
            if (!glass) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = scheme.outlineVariant)
        }

        // ---- period chips
        item(key = "periods") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(periods, key = { "${it.kind}_${it.year}" }) { p ->
                    RecapChip(
                        text =
                            when (p.kind) {
                                RecapPeriodKind.THIS_YEAR -> stringResource(R.string.auralys_recap_this_year)
                                RecapPeriodKind.LAST_30_DAYS -> stringResource(R.string.auralys_recap_last_30_days)
                                RecapPeriodKind.ALL_TIME -> stringResource(R.string.auralys_recap_all_time)
                                RecapPeriodKind.YEAR -> p.year.toString()
                            },
                        selected = p == period,
                        onClick = {
                            showAllSongs = false
                            viewModel.select(p)
                        },
                    )
                }
            }
        }

        val d = data
        when {
            d == null -> {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            d.isEmpty -> {
                item(key = "empty") {
                    EmptyPlaceholder(
                        icon = R.drawable.history,
                        text = stringResource(R.string.auralys_recap_empty),
                    )
                }
            }

            else -> {
                // ---- actions
                item(key = "actions") {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        // one button for the whole flow: Play -> (playing) Pause -> (paused) Resume, as long as the song on
                        // the player is one of the recap's songs
                        val playingNow by (playerConnection?.isPlaying?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) })
                        val nowId = playerConnection?.mediaMetadata?.collectAsStateWithLifecycle()?.value?.id
                        val recapActive = nowId != null && d.songs.any { it.id == nowId }
                        RecapPillButton(
                            text =
                                stringResource(
                                    when {
                                        recapActive && playingNow -> R.string.auralys_recap_pause
                                        recapActive -> R.string.auralys_recap_resume
                                        else -> R.string.auralys_recap_play
                                    },
                                ),
                            icon = if (recapActive && playingNow) R.drawable.pause else R.drawable.play,
                            primary = true,
                            onClick = {
                                val player = playerConnection?.player
                                if (recapActive && player != null) {
                                    if (player.isPlaying) player.pause() else player.play()
                                } else {
                                    playSongs(d.songs, null)
                                }
                            },
                        )
                        RecapPillButton(
                            text = stringResource(R.string.auralys_recap_watch),
                            icon = R.drawable.graphic_eq,
                            primary = false,
                            onClick = { showStory = true },
                        )
                    }
                }

                // ---- top songs
                if (d.songs.isNotEmpty()) {
                    item(key = "songs") {
                        val visible = if (showAllSongs) d.songs else d.songs.take(SONGS_COLLAPSED)
                        RecapSection(title = stringResource(R.string.auralys_recap_top_songs), shape = sectionShape) {
                            visible.forEachIndexed { index, song ->
                                RecapSongRow(
                                    rank = index + 1,
                                    song = song,
                                    onClick = { playSongs(d.songs, song) },
                                )
                            }
                            if (d.songs.size > SONGS_COLLAPSED) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { showAllSongs = !showAllSongs }
                                            .padding(vertical = 12.dp),
                                ) {
                                    Text(
                                        text = stringResource(if (showAllSongs) R.string.auralys_recap_show_less else R.string.auralys_recap_show_more),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = scheme.primary,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        painter = painterResource(if (showAllSongs) R.drawable.expand_less else R.drawable.expand_more),
                                        contentDescription = null,
                                        tint = scheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // ---- top artists
                if (d.artists.isNotEmpty()) {
                    item(key = "artists") {
                        RecapSection(title = stringResource(R.string.auralys_recap_top_artists), shape = sectionShape) {
                            d.artists.forEachIndexed { index, artist ->
                                RecapArtistRow(
                                    rank = index + 1,
                                    artist = artist,
                                    onClick = { navController.navigate("artist/${artist.id}") },
                                )
                            }
                        }
                    }
                }

                // ---- top albums
                if (d.albums.isNotEmpty()) {
                    item(key = "albums") {
                        RecapSection(title = stringResource(R.string.auralys_recap_top_albums), shape = sectionShape) {
                            d.albums.forEach { album ->
                                RecapAlbumRow(
                                    album = album,
                                    onClick = { navController.navigate("album/${album.id}") },
                                )
                            }
                        }
                    }
                }

                item(key = "bottom_space") { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.auralys_recap_title)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
        actions = {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .padding(end = 8.dp)
                        .size(40.dp)
                        .pressScale(0.9f)
                        .then(if (glass) Modifier.glass(CircleShape) else Modifier.clip(CircleShape).border(1.dp, scheme.outlineVariant, CircleShape))
                        .clickable(enabled = data?.isEmpty == false && !sharing) { share() },
            ) {
                if (sharing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        painter = painterResource(R.drawable.share),
                        contentDescription = stringResource(R.string.auralys_recap_share),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
    )

    val storyData = data
    if (showStory && storyData != null && !storyData.isEmpty) {
        RecapStoryDialog(
            data = storyData,
            title = recapTitle,
            onDismiss = { showStory = false },
            onShare = { share() },
        )
    }
}

@Composable
private fun RecapStatPill(text: String) {
    val glass = isGlassStyle
    val scheme = MaterialTheme.colorScheme
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = scheme.onSurface,
        modifier =
            Modifier
                .then(if (glass) Modifier.glass(CircleShape) else Modifier.clip(CircleShape).background(scheme.surfaceContainerHigh))
                .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun RecapChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val glass = isGlassStyle
    val scheme = MaterialTheme.colorScheme
    val base =
        when {
            glass && selected -> Modifier.glassTinted(CircleShape, scheme.primary)
            glass -> Modifier.glass(CircleShape)
            selected -> Modifier.clip(CircleShape).background(scheme.onSurface)
            else -> Modifier.clip(CircleShape).border(1.dp, scheme.outlineVariant, CircleShape)
        }
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        color =
            when {
                selected && glass -> scheme.onPrimary
                selected -> scheme.surface
                else -> scheme.onSurface
            },
        modifier =
            Modifier
                .pressScale(0.95f)
                .then(base)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

@Composable
private fun RecapPillButton(
    text: String,
    icon: Int,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val glass = isGlassStyle
    val scheme = MaterialTheme.colorScheme
    val base =
        when {
            primary && glass -> Modifier.glassTinted(CircleShape, scheme.primary)
            primary -> Modifier.clip(CircleShape).background(scheme.primary)
            glass -> Modifier.glass(CircleShape)
            else -> Modifier.clip(CircleShape).border(1.dp, scheme.outline, CircleShape)
        }
    val content = if (primary) scheme.onPrimary else scheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(if (primary) 56.dp else 48.dp)
                .pressScale(0.97f)
                .then(base)
                .clickable(onClick = onClick),
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = if (primary) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = content,
        )
    }
}

@Composable
private fun RecapSection(
    title: String,
    shape: RoundedCornerShape,
    content: @Composable () -> Unit,
) {
    val glass = isGlassStyle
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (glass) 12.dp else 0.dp)
                    .then(if (glass) Modifier.glassCard(shape) else Modifier)
                    .padding(vertical = if (glass) 6.dp else 0.dp)
                    .animateContentSize(),
        ) {
            content()
        }
    }
}

@Composable
private fun RecapRank(rank: Int) {
    Text(
        text = rank.toString(),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = if (rank == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.width(30.dp),
        maxLines = 1,
    )
}

@Composable
private fun RecapRow(
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 7.dp),
        content = content,
    )
}

@Composable
private fun RecapThumb(
    url: String?,
    size: Dp,
    circle: Boolean,
) {
    val shape = if (circle) CircleShape else RoundedCornerShape(if (isGlassStyle) 12.dp else 6.dp)
    val px = (size.value * 2.5f).toInt()
    AsyncImage(
        model = url?.resize(px, px),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier =
            Modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

@Composable
private fun RecapTexts(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RecapSongRow(
    rank: Int,
    song: RecapSong,
    onClick: () -> Unit,
) {
    val minutes = stringResource(R.string.auralys_recap_minutes_short, song.minutes.toString())
    RecapRow(onClick = onClick) {
        RecapRank(rank)
        RecapThumb(song.thumbnailUrl, 56.dp, circle = false)
        Spacer(Modifier.width(14.dp))
        RecapTexts(
            title = song.title,
            subtitle = if (song.artist.isBlank()) minutes else "${song.artist} · $minutes",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RecapArtistRow(
    rank: Int,
    artist: RecapArtist,
    onClick: () -> Unit,
) {
    RecapRow(onClick = onClick) {
        RecapRank(rank)
        RecapThumb(artist.thumbnailUrl, 56.dp, circle = true)
        Spacer(Modifier.width(14.dp))
        RecapTexts(
            title = artist.name,
            subtitle = stringResource(R.string.auralys_recap_minutes_short, artist.minutes.toString()),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RecapAlbumRow(
    album: RecapAlbum,
    onClick: () -> Unit,
) {
    RecapRow(onClick = onClick) {
        RecapThumb(album.thumbnailUrl, 56.dp, circle = false)
        Spacer(Modifier.width(14.dp))
        RecapTexts(
            title = album.title,
            subtitle = album.artist,
            modifier = Modifier.weight(1f),
        )
    }
}
