/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The pieces of the account experience, one family for YouTube Music and Spotify: the brand marks, the two sign-in tiles of
 * the You tab and Settings, the wide account card of the Spotify page, the plain buttons, and the frame of the two sign-in
 * screens (see LoginFrame.kt). Flat rounded rectangles from the Horizon radius scale, a brand colour only on the mark and as a
 * very soft wash at the top of a tile; no blur, no shadow, no endless animation. The tiles enter with iosAppear, which does
 * nothing while the iOS animations are off.
 */

package com.metrolist.music.ui.component

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.music.R
import com.metrolist.music.ui.theme.HorizonRadius
import com.metrolist.music.ui.theme.HorizonSpace
import com.metrolist.music.ui.theme.LocalHorizon

/** The accounts a person can connect: the mark, the brand colour and the name. */
enum class AccountProvider(
    @StringRes val nameRes: Int,
    @DrawableRes val mark: Int,
    val brand: Color,
) {
    YOUTUBE_MUSIC(R.string.ytm_section, R.drawable.youtube_music, Color(0xFFFF0000)),
    SPOTIFY(R.string.spotify_title, R.drawable.spotify, Color(0xFF1DB954)),
}

/** The brand mark in its own colour: a disc with the details cut out, so the surface shows through them. */
@Composable
fun BrandMark(
    provider: AccountProvider,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Icon(
        painter = painterResource(provider.mark),
        contentDescription = null,
        tint = provider.brand,
        modifier = modifier.size(size),
    )
}

/** What a tile shows. [accountName] and [avatarUrl] are only set while connected, and only when they are known. */
@Immutable
data class AccountTileState(
    val provider: AccountProvider,
    val connected: Boolean,
    val accountName: String? = null,
    val avatarUrl: String? = null,
    val onClick: () -> Unit = {},
)

/** The brand colour at the top of a tile, fading out: soft enough to read as a tint of the surface, not as a colour block. */
@Composable
private fun brandWash(provider: AccountProvider, vertical: Boolean = true): Brush {
    val alpha = if (LocalHorizon.current.dark) 0.10f else 0.07f
    val colors = listOf(provider.brand.copy(alpha = alpha), Color.Transparent)
    return if (vertical) Brush.verticalGradient(colors) else Brush.horizontalGradient(colors)
}

/** The small round tick that says "connected": the brand colour behind a white check. */
@Composable
fun ConnectedBadge(
    provider: AccountProvider,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    Box(modifier.size(size).clip(CircleShape).background(provider.brand), contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(R.drawable.check),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.7f),
        )
    }
}

/**
 * The person behind a connected account: the picture when there is one, else the first letter, with the brand mark in a
 * small ring at the corner so it is clear which account it is.
 */
@Composable
fun AccountAvatar(
    provider: AccountProvider,
    name: String?,
    avatarUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val badge = size * 0.46f
    Box(modifier.size(size)) {
        Box(
            Modifier.size(size).clip(CircleShape).background(scheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarUrl != null) {
                AsyncImage(model = avatarUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
            } else {
                val initial = name.orEmpty().trim().take(1).uppercase()
                if (initial.isNotEmpty()) {
                    Text(initial, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                } else {
                    Icon(painter = painterResource(R.drawable.person), contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(size * 0.5f))
                }
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp).size(badge + 4.dp).clip(CircleShape).background(scheme.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            BrandMark(provider, badge)
        }
    }
}

/**
 * A flat rounded-rectangle button. The main one is filled like the app's other buttons, the quiet one is a light tone of the
 * text colour. [onClick] null draws it as a label only (inside a tile, where the whole tile is the target).
 */
@Composable
fun AccountButton(
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    quiet: Boolean = false,
    danger: Boolean = false,
    @DrawableRes trailingIcon: Int? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val fill = if (quiet) scheme.onSurface.copy(alpha = 0.08f) else scheme.primary
    val ink =
        when {
            danger -> scheme.error
            quiet -> scheme.onSurface
            else -> scheme.onPrimary
        }
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(HorizonRadius.Md))
            .background(fill)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = HorizonSpace.S16),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (trailingIcon != null) {
                Spacer(Modifier.width(HorizonSpace.S4))
                Icon(painter = painterResource(trailingIcon), contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** One sign-in: the mark (or the avatar once connected), the name, one line, and the action. The whole tile is the target. */
@Composable
fun AccountTile(
    state: AccountTileState,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val provider = state.provider
    val shape = glassShape(HorizonRadius.Lg, HorizonRadius.Lg)
    Column(
        modifier
            .clip(shape)
            .background(scheme.surfaceContainer)
            .background(brandWash(provider))
            .clickable(role = Role.Button, onClick = state.onClick)
            .padding(HorizonSpace.S16),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            if (state.connected) {
                AccountAvatar(provider, state.accountName, state.avatarUrl)
            } else {
                BrandMark(provider, 44.dp)
            }
            Spacer(Modifier.weight(1f))
            if (state.connected) ConnectedBadge(provider)
        }
        Spacer(Modifier.height(HorizonSpace.S16))
        Text(
            text = stringResource(provider.nameRes),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(HorizonSpace.S4))
        Text(
            text =
                when {
                    !state.connected && provider == AccountProvider.YOUTUBE_MUSIC -> stringResource(R.string.acct_pitch_ytm)
                    !state.connected -> stringResource(R.string.acct_pitch_spotify)
                    state.accountName.isNullOrBlank() -> stringResource(R.string.spotify_connected)
                    else -> stringResource(R.string.acct_connected_as, state.accountName)
                },
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(HorizonSpace.S12))
        if (state.connected) {
            AccountButton(
                label = stringResource(R.string.acct_action_manage),
                onClick = null,
                quiet = true,
                trailingIcon = R.drawable.navigate_next,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            AccountButton(
                label = stringResource(if (provider == AccountProvider.YOUTUBE_MUSIC) R.string.acct_action_login else R.string.acct_action_connect),
                onClick = null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The "Account" block: a plain group title, then one tile per sign-in side by side, equally wide and equally tall. */
@Composable
fun AccountTilesSection(
    tiles: List<AccountTileState>,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.account),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = HorizonSpace.S16, end = HorizonSpace.S16, top = HorizonSpace.S8, bottom = HorizonSpace.S8),
        )
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(HorizonSpace.S12),
        ) {
            tiles.forEachIndexed { index, tile ->
                AccountTile(tile, Modifier.weight(1f).fillMaxHeight().iosAppear(index))
            }
        }
    }
}

/**
 * The whole account block of the You tab and Settings: the tiles, and under them, once YouTube Music is signed in, what
 * belongs to that sign-in only ([youtubeRows]: the sync switch, signing out) in a plain group with its own title.
 */
@Composable
fun AccountBlock(
    tiles: List<AccountTileState>,
    youtubeRows: List<Material3SettingsItem>,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        AccountTilesSection(tiles)
        if (youtubeRows.isNotEmpty()) {
            Spacer(Modifier.height(HorizonSpace.S16))
            Material3SettingsGroup(title = stringResource(R.string.ytm_section), items = youtubeRows)
        }
    }
}

/** The wide card of a connected account (the Spotify page): picture, the name, "Connected", the tick. Not a button. */
@Composable
fun AccountCard(
    provider: AccountProvider,
    name: String?,
    avatarUrl: String?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .clip(glassShape(HorizonRadius.Lg, HorizonRadius.Lg))
            .background(scheme.surfaceContainer)
            .background(brandWash(provider, vertical = false))
            .padding(HorizonSpace.S16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountAvatar(provider, name, avatarUrl, size = 52.dp)
        Spacer(Modifier.width(HorizonSpace.S16))
        Column(Modifier.weight(1f)) {
            Text(
                text = name?.takeIf { it.isNotBlank() } ?: stringResource(provider.nameRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.spotify_connected),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        ConnectedBadge(provider, size = 24.dp)
    }
}

/** A short benefit: a small ink icon and one plain line. */
@Composable
fun BenefitRow(
    @DrawableRes icon: Int,
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painter = painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(HorizonSpace.S16))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Start)
    }
}
