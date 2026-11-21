/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Settings home: a search field over the categories as flat grouped lists. Searching looks through every setting on
 * every settings page (see SettingsSearchIndex) and opens the page it lives on.
 */

package com.metrolist.music.ui.screens.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.ui.theme.HorizonSpace
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.painterResource
import java.text.Normalizer

/** Opens the system page where the "open supported links" switches live, instead of one of our own pages. */
internal const val ROUTE_DEFAULT_LINKS = "action:default_links"

/** One category on the settings shelf. [captionArg], when given, replaces the caption with "New version <arg>". */
internal data class SettingsTile(
    @StringRes val title: Int,
    @StringRes val caption: Int,
    @DrawableRes val icon: Int,
    val route: String,
    val captionArg: String? = null,
)

/** A searchable row with its texts already resolved in the language the app is shown in. */
internal data class SearchableSetting(
    val title: String,
    val description: String?,
    val section: String?,
    val category: String,
    val route: String,
)

private val combiningMarks = Regex("\\p{Mn}+")
private val notLettersOrDigits = Regex("[^\\p{L}\\p{N}]+")

/** Lower case, without accents and punctuation, so "Pemutar & Audio" is found by "pemutar audio" and "cafe" by "café". */
internal fun normalizeForSettingsSearch(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(combiningMarks, "")
        .lowercase()
        .replace(notLettersOrDigits, " ")
        .trim()

/**
 * Settings that match every word of [query], best first: a title that starts with the word beats one that merely
 * contains it, which beats a match in the group name, the category or the description. Ties keep the page order.
 */
internal fun rankSettings(
    query: String,
    entries: List<SearchableSetting>,
    limit: Int = 40,
): List<SearchableSetting> {
    val tokens = normalizeForSettingsSearch(query).split(' ').filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return emptyList()
    val whole = tokens.joinToString(" ")

    data class Hit(val score: Int, val index: Int, val entry: SearchableSetting)

    val hits = ArrayList<Hit>()
    entries.forEachIndexed { index, entry ->
        val title = normalizeForSettingsSearch(entry.title)
        val section = entry.section?.let(::normalizeForSettingsSearch).orEmpty()
        val category = normalizeForSettingsSearch(entry.category)
        val description = entry.description?.let(::normalizeForSettingsSearch).orEmpty()
        val titleWords = title.split(' ')
        var total = 0
        for (token in tokens) {
            val score =
                when {
                    title.startsWith(token) -> 120
                    titleWords.any { it.startsWith(token) } -> 100
                    title.contains(token) -> 80
                    section.contains(token) -> 40
                    category.contains(token) -> 35
                    description.contains(token) -> 30
                    else -> 0
                }
            if (score == 0) {
                total = -1
                break
            }
            total += score
        }
        if (total >= 0) {
            if (title == whole) total += 50
            hits += Hit(total, index, entry)
        }
    }
    return hits
        .sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.index })
        .take(limit)
        .map { it.entry }
}

/** The shelf, in the order people reach for things. */
internal fun settingsTiles(
    hasAndroidAuto: Boolean,
    hasDefaultLinks: Boolean,
    updaterAvailable: Boolean,
    newVersionName: String?,
): List<SettingsTile> =
    buildList {
        add(SettingsTile(R.string.hub_cat_player, R.string.hub_cap_player, R.drawable.play, "settings/player"))
        add(SettingsTile(R.string.hub_cat_appearance, R.string.hub_cap_appearance, R.drawable.palette, "settings/appearance"))
        add(SettingsTile(R.string.hub_cat_content, R.string.hub_cap_content, R.drawable.language, "settings/content"))
        add(SettingsTile(R.string.hub_cat_ai, R.string.hub_cap_ai, R.drawable.translate, "settings/ai"))
        add(SettingsTile(R.string.hub_cat_privacy, R.string.hub_cap_privacy, R.drawable.security, "settings/privacy"))
        add(SettingsTile(R.string.hub_cat_storage, R.string.hub_cap_storage, R.drawable.storage, "settings/storage"))
        add(SettingsTile(R.string.hub_cat_backup, R.string.hub_cap_backup, R.drawable.restore, "settings/backup_restore"))
        if (hasAndroidAuto) {
            add(SettingsTile(R.string.hub_cat_auto, R.string.hub_cap_auto, R.drawable.ic_android_auto, "settings/android_auto"))
        }
        if (hasDefaultLinks) {
            add(SettingsTile(R.string.hub_cat_links, R.string.hub_cap_links, R.drawable.link, ROUTE_DEFAULT_LINKS))
        }
        if (updaterAvailable) {
            add(SettingsTile(R.string.hub_cat_update, R.string.hub_cap_update, R.drawable.update, "settings/updater", captionArg = newVersionName))
        }
        add(SettingsTile(R.string.hub_cat_about, R.string.hub_cap_about, R.drawable.info, "settings/about"))
    }

/** Rounded field with a magnifier, a hint and a clear button. */
@Composable
internal fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
        cursorBrush = SolidColor(scheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(HorizonSpace.Target)
                        // a flat surface-2 field: the page behind it is plain, so there is nothing for glass to show
                        .clip(RoundedCornerShape(50))
                        .background(scheme.surfaceContainerHigh)
                        .padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.search),
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.hub_search_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    inner()
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.close),
                            contentDescription = stringResource(R.string.hub_search_clear),
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        },
    )
}

/**
 * Everything under the top bar of the settings home: the search field, then either the account block with the
 * categories or the search results. [account] is passed in because it needs a view model.
 */
@Composable
internal fun SettingsHubBody(
    query: String,
    onQueryChange: (String) -> Unit,
    tiles: List<SettingsTile>,
    onOpen: (String) -> Unit,
    account: @Composable () -> Unit,
) {
    SettingsSearchField(query = query, onQueryChange = onQueryChange)

    Spacer(modifier = Modifier.height(HorizonSpace.S16))

    if (query.isBlank()) {
        account()

        Spacer(modifier = Modifier.height(HorizonSpace.S24))

        SettingsShelf(tiles = tiles, onOpen = onOpen)
    } else {
        SettingsSearchResults(query = query, tiles = tiles, onOpen = onOpen)
    }
}

/**
 * The categories as flat grouped lists (a bare ink icon, the name, a chevron): the player and look first, then the data
 * pages, then the rest. A new version shows as a short value and a dot on the icon, not as a coloured caption.
 */
@Composable
internal fun SettingsShelf(
    tiles: List<SettingsTile>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val usage = setOf("settings/player", "settings/appearance", "settings/content", "settings/ai")
    val data = setOf("settings/privacy", "settings/storage", "settings/backup_restore")
    val groups = listOf(tiles.filter { it.route in usage }, tiles.filter { it.route in data }, tiles.filter { it.route !in usage && it.route !in data })
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HorizonSpace.S24)) {
        groups.filter { it.isNotEmpty() }.forEach { group ->
            Material3SettingsGroup(
                items =
                    group.map { tile ->
                        Material3SettingsItem(
                            icon = painterResource(tile.icon),
                            title = { Text(stringResource(tile.title)) },
                            trailingContent =
                                if (tile.captionArg != null) {
                                    { Text(stringResource(R.string.hub_new_version, tile.captionArg)) }
                                } else {
                                    null
                                },
                            showBadge = tile.captionArg != null,
                            onClick = { onOpen(tile.route) },
                        )
                    },
            )
        }
    }
}

/** Every setting that matches, as rows that open the page the setting is on. */
@Composable
internal fun SettingsSearchResults(
    query: String,
    tiles: List<SettingsTile>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val searchable =
        remember(configuration, tiles) {
            val categoryPages =
                tiles.map {
                    val title = context.getString(it.title)
                    SearchableSetting(title, context.getString(it.caption), null, title, it.route)
                }
            val settings =
                SettingsSearchIndex.map {
                    SearchableSetting(
                        title = context.getString(it.title),
                        description = it.description?.let { id -> context.getString(id) },
                        section = it.section?.let { id -> context.getString(id) },
                        category = context.getString(it.category),
                        route = it.route,
                    )
                }
            categoryPages + settings
        }
    val results = remember(query, searchable) { rankSettings(query, searchable) }
    val scheme = MaterialTheme.colorScheme

    if (results.isEmpty()) {
        Column(
            modifier = modifier.fillMaxWidth().padding(top = 48.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.hub_search_empty),
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.hub_search_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    Material3SettingsGroup(
        modifier = modifier,
        items =
            results.map { result ->
                Material3SettingsItem(
                    title = {
                        Text(
                            text = highlighted(result.title, query),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    description = {
                        Text(
                            // a category page says what is on it; a single setting says where it lives
                            text =
                                if (result.title == result.category) {
                                    result.description.orEmpty()
                                } else {
                                    listOfNotNull(result.category, result.section).distinct().joinToString("  ›  ")
                                },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = { onOpen(result.route) },
                )
            },
    )
}

/** [text] with the words of [query] in bold. */
private fun highlighted(
    text: String,
    query: String,
): AnnotatedString {
    val lower = text.lowercase()
    // lower-casing can change the length of a few letters; then the offsets would not line up, so no highlight
    if (lower.length != text.length) return AnnotatedString(text)
    val tokens = query.lowercase().split(' ', '-', '_').filter { it.isNotBlank() }
    return buildAnnotatedString {
        append(text)
        for (token in tokens) {
            var from = 0
            while (true) {
                val at = lower.indexOf(token, from)
                if (at < 0) break
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), at, at + token.length)
                from = at + token.length
            }
        }
    }
}
