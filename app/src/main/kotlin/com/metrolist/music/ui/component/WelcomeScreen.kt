/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * First-launch page: sign in to YouTube Music, or carry on without an account.
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.metrolist.music.R

@Composable
fun WelcomeScreen(
    onLogin: () -> Unit,
    onSkip: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val rise = remember { Animatable(0.35f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, tween(1500, easing = FastOutSlowInEasing)) }
    val transition = rememberInfiniteTransition(label = "welcomeWave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart),
        label = "wave",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(scheme.background)
            // swallow touches so nothing underneath (the app) reacts while this is up
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Spacer(Modifier.height(40.dp))

            AuralysMark(
                modifier = Modifier.size(132.dp),
                rise = rise.value,
                phase = phase,
            )

            Spacer(Modifier.height(20.dp))
            Text(
                "Auralys",
                style = MaterialTheme.typography.headlineSmall,
                color = scheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.welcome_title),
                style = MaterialTheme.typography.displaySmall,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.welcome_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(32.dp))
            // three plain rows: a bare icon, the name and what it does
            Material3SettingsGroup(
                items =
                    listOf(
                        WelcomeFeature(R.drawable.sync, R.string.welcome_f1_title, R.string.welcome_f1_desc),
                        WelcomeFeature(R.drawable.group, R.string.welcome_f2_title, R.string.welcome_f2_desc),
                        WelcomeFeature(R.drawable.palette, R.string.welcome_f3_title, R.string.welcome_f3_desc),
                    ),
            )

            Spacer(Modifier.height(16.dp))
          }

            val pill = RoundedCornerShape(50)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .glassTinted(pill, scheme.primary)
                    .pressScale()
                    .clickable(onClick = onLogin),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(painterResource(R.drawable.login), null, tint = scheme.onPrimary, modifier = Modifier.size(22.dp))
                    Text(
                        stringResource(R.string.welcome_login),
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(pill)
                    .background(scheme.surfaceContainerHigh)
                    .pressScale()
                    .clickable(onClick = onSkip),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.welcome_skip),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.welcome_note),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun WelcomeFeature(
    icon: Int,
    title: Int,
    desc: Int,
): Material3SettingsItem =
    Material3SettingsItem(
        icon = painterResource(icon),
        title = { Text(stringResource(title)) },
        description = { Text(stringResource(desc)) },
    )
