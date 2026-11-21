/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Library building blocks: text tabs with an ink underline instead of filter chips, and the "Collections" block, an
 * index of the automatic playlists (liked, downloaded, cached, most played, uploaded): a plain title, then bare rows.
 */

package com.metrolist.music.ui.screens.library

import androidx.annotation.StringRes
import com.metrolist.music.ui.component.NeutralFab
import com.metrolist.music.ui.component.followNavFold
import com.metrolist.music.ui.component.glass
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.constants.LibraryFilter
import com.metrolist.music.ui.component.EditorialDivider
import com.metrolist.music.ui.component.LargeTitleHeader
import com.metrolist.music.ui.component.MicroLabel
import com.metrolist.music.ui.component.glassCard
import com.metrolist.music.ui.component.glassShape
import com.metrolist.music.ui.component.hairlineBottom
import com.metrolist.music.ui.component.painterResource
import com.metrolist.music.ui.component.pressScale

/** Which tab is open and how to open another one; provided by [LibraryScreen] so every library page draws the same tabs. */
@Immutable
internal class LibraryTabState(
    val current: LibraryFilter,
    val onSelect: (LibraryFilter) -> Unit,
)

internal val LocalLibraryTabs = staticCompositionLocalOf<LibraryTabState?> { null }

private val libraryTabs =
    listOf(
        LibraryFilter.LIBRARY to R.string.lib_tab_all,
        LibraryFilter.PLAYLISTS to R.string.filter_playlists,
        LibraryFilter.SONGS to R.string.filter_songs,
        LibraryFilter.ALBUMS to R.string.filter_albums,
        LibraryFilter.ARTISTS to R.string.filter_artists,
        LibraryFilter.PODCASTS to R.string.filter_podcasts,
    )

/**
 * The tabs of the library page that is showing, or nothing when no [LibraryScreen] is around. Every library page puts this
 * first in its list, so the large "Pustaka" title is drawn here, above the tabs (it scrolls away with the list; it shows only
 * where the screen provides a LargeTitleState, see LargeTitle.kt).
 */
@Composable
internal fun LibraryTabsFromScope(modifier: Modifier = Modifier) {
    val state = LocalLibraryTabs.current ?: return
    Column(modifier = modifier) {
        LargeTitleHeader(title = stringResource(R.string.filter_library))
        LibraryTabs(current = state.current, onSelect = state.onSelect)
    }
}

/** Text tabs; the open one is bold with a 2 dp ink underline. No rule under the row. */
@Composable
internal fun LibraryTabs(
    current: LibraryFilter,
    onSelect: (LibraryFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        items(libraryTabs, key = { it.first.name }) { (filter, label) ->
            val selected = filter == current
            Column(
                modifier =
                    Modifier
                        .width(IntrinsicSize.Max)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onSelect(filter) }
                        .padding(top = 10.dp),
            ) {
                Text(
                    text = stringResource(label),
                    style =
                        MaterialTheme.typography.titleMedium.copy(
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                    color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(if (selected) scheme.onSurface else Color.Transparent),
                )
            }
        }
    }
}

/** The "new playlist" button floats over the lists; this much empty space at the end of a list keeps its last row clear of it. */
internal val NewPlaylistFabClearance = 88.dp

/**
 * Icon-only "new playlist" button. The shared neutral floating button (no accent colour): a glass disc in Liquid Glass (it cannot
 * sample the page behind it because it sits inside that page), a neutral surface square with a hairline in editorial. It drops with
 * the mini player when the Liquid Glass bar folds into its pill.
 */
@Composable
internal fun NewPlaylistFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NeutralFab(
        onClick = onClick,
        icon = R.drawable.add,
        contentDescription = stringResource(R.string.lib_new_playlist),
        modifier = modifier.followNavFold(),
    )
}

/** One automatic playlist in the Collections block. [titleSuffix] is appended to the title ("Top" + " 50"). */
internal data class CollectionEntry(
    @StringRes val title: Int,
    @DrawableRes val icon: Int,
    val titleSuffix: String = "",
    /** A brand mark drawn in its own colour (and a little larger) instead of ink; only the Spotify entry uses it. */
    val brandTint: Color? = null,
    val onClick: () -> Unit,
)

/** The automatic playlists as an index: a plain section title, then one bare row each (ink icon, title, chevron). */
@Composable
internal fun CollectionIndex(
    entries: List<CollectionEntry>,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Text(
            text = stringResource(R.string.lib_collections),
            style = MaterialTheme.typography.headlineSmall,
            color = scheme.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        entries.forEach { entry ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .pressScale(0.99f)
                        .clickable(onClick = entry.onClick)
                        .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(entry.icon),
                    contentDescription = null,
                    tint = entry.brandTint ?: scheme.onSurface,
                    modifier = Modifier.size(if (entry.brandTint != null) 26.dp else 22.dp),
                )
                Spacer(Modifier.width(if (entry.brandTint != null) 12.dp else 16.dp))
                Text(
                    text = stringResource(entry.title) + entry.titleSuffix,
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painter = painterResource(R.drawable.navigate_next),
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
