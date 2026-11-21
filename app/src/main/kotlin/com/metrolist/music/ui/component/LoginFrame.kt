/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The frame around the web view of both sign-in screens (YouTube Music and Spotify), so they feel like one family: a header
 * with the back button, the brand mark and the title; a thin progress line in the brand colour; the web view in a rounded
 * frame, covered by a branded "opening" state until the page has painted, or by an offline state with a retry; and one plain
 * line under it that says where the password is typed. It only wraps the web view: how a sign-in is read stays in each screen.
 * No blur, no endless animation: the progress line and the fade of the cover are the only motion.
 */

package com.metrolist.music.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.ui.theme.HorizonRadius
import com.metrolist.music.ui.theme.HorizonSpace

/** What the page behind the web view is doing: opening, painted, or not reachable. */
enum class LoginPhase { Loading, Ready, Offline }

/**
 * @param privacy the line under the frame; null leaves it out (the channel picker has no password)
 * @param progress 0 to 1, the web view's own page progress
 * @param loadingText what the opening state says (it changes to "Connecting" once a sign-in has been read)
 * @param web the web view; it fills the frame
 */
@Composable
fun LoginFrame(
    provider: AccountProvider,
    title: String,
    privacy: String?,
    phase: LoginPhase,
    progress: Float,
    loadingText: String,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onBackLongClick: () -> Unit = onBack,
    @DrawableRes backIcon: Int = R.drawable.arrow_back,
    web: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = HorizonSpace.S4, vertical = HorizonSpace.S4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, onLongClick = onBackLongClick) {
                Icon(painterResource(backIcon), contentDescription = null)
            }
            BrandMark(provider, 26.dp)
            Spacer(Modifier.width(HorizonSpace.S12))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        LoginProgressLine(provider, progress, active = phase != LoginPhase.Offline && (phase == LoginPhase.Loading || progress < 1f))

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = HorizonSpace.S16, vertical = HorizonSpace.S8)
                .clip(RoundedCornerShape(HorizonRadius.Xl))
                .background(scheme.surfaceContainer),
        ) {
            web()
            // shown at once while the page opens, fades out when it has painted
            val coverAlpha by animateFloatAsState(if (phase != LoginPhase.Ready) 1f else 0f, tween(160), label = "loginCover")
            if (coverAlpha > 0.001f) {
                LoginStateCover(provider, offline = phase == LoginPhase.Offline, loadingText = loadingText, onRetry = onRetry, modifier = Modifier.alpha(coverAlpha))
            }
        }

        if (privacy != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = HorizonSpace.S24, vertical = HorizonSpace.S12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.lock), contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(HorizonSpace.S8))
                Text(privacy, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

/** Two dp tall, the brand colour, as wide as the page is loaded; it fades out when the page is done. */
@Composable
private fun LoginProgressLine(
    provider: AccountProvider,
    progress: Float,
    active: Boolean,
) {
    val width by animateFloatAsState(progress.coerceIn(0.05f, 1f), tween(180), label = "loginProgress")
    val visible by animateFloatAsState(if (active) 1f else 0f, tween(220), label = "loginProgressVisible")
    Box(
        Modifier
            .fillMaxWidth()
            .height(2.dp)
            .alpha(visible)
            .drawBehind { drawRect(provider.brand, size = Size(size.width * width, size.height)) },
    )
}

/** What covers the web view until it has painted (the brand mark and one line), or when the page cannot be reached. */
@Composable
private fun LoginStateCover(
    provider: AccountProvider,
    offline: Boolean,
    loadingText: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxSize()
            .background(scheme.surfaceContainer)
            // the cover is above the web view: it must take the touches itself while it is shown
            .pointerInput(Unit) {}
            .padding(HorizonSpace.S32),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (offline) {
            Icon(painterResource(R.drawable.warning), contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(HorizonSpace.S16))
            Text(
                text = stringResource(R.string.login_offline_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(HorizonSpace.S8))
            Text(
                text = stringResource(R.string.login_offline_message),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(HorizonSpace.S24))
            AccountButton(label = stringResource(R.string.retry), onClick = onRetry, modifier = Modifier.width(180.dp))
        } else {
            BrandMark(provider, 56.dp)
            Spacer(Modifier.height(HorizonSpace.S16))
            Text(
                text = loadingText,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
