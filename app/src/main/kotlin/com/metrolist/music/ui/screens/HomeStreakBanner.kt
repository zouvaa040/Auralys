/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * A compact streak card for the top of Home and You: the animated flame, the days in a row and what to do next, on a flat
 * surface (the flame carries the tier colour, nothing else does). A tap opens the Statistics screen where the full streak lives.
 */

package com.metrolist.music.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.R
import com.metrolist.music.constants.ShowStreakOnHomeKey
import com.metrolist.music.ui.component.glassCard
import com.metrolist.music.ui.component.glassShape
import com.metrolist.music.ui.component.pressScale
import com.metrolist.music.ui.theme.HorizonRadius
import com.metrolist.music.ui.theme.HorizonSpace
import com.metrolist.music.utils.ListeningStreak
import com.metrolist.music.utils.StreakState
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.StreakViewModel

@Composable
fun HomeStreakBanner(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StreakViewModel = hiltViewModel(),
) {
    val (show, _) = rememberPreference(ShowStreakOnHomeKey, true)
    val streak by viewModel.streak.collectAsStateWithLifecycle()
    if (!show || streak.totalDays == 0) return
    StreakBannerContent(streak = streak, onClick = onClick, modifier = modifier)
}

/** The card itself, for a streak that is already known. */
@Composable
internal fun StreakBannerContent(
    streak: ListeningStreak,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = glassShape(HorizonRadius.Lg, HorizonRadius.Lg)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = HorizonSpace.Gutter, vertical = 4.dp)
                .glassCard(shape)
                .pressScale(0.98f)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StreakFlame(tier = streak.tier, lit = streak.lit, modifier = Modifier.size(width = 52.dp, height = 60.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = streak.current.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (streak.lit) scheme.onSurface else scheme.onSurfaceVariant,
                )
                Text(
                    text = pluralStringResource(R.plurals.auralys_streak_poster_days, streak.current),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            Text(
                text = bannerLine(streak),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (streak.state == StreakState.RECOVERABLE) {
                StreakProgressBar(
                    progress = streak.recoveryProgress.coerceAtLeast(0.03f),
                    tier = streak.tier,
                    lit = true,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun bannerLine(streak: ListeningStreak): String =
    when {
        streak.state == StreakState.RECOVERABLE ->
            stringResource(R.string.auralys_streak_status_recoverable, ListeningStreak.RECOVERY_MINUTES) +
                " (" + stringResource(R.string.auralys_streak_recovery_progress, minOf(streak.todayMinutes, ListeningStreak.RECOVERY_MINUTES), ListeningStreak.RECOVERY_MINUTES) + ")"
        streak.state == StreakState.NONE && streak.recoveryBlocked -> stringResource(R.string.auralys_streak_recovery_blocked)
        streak.state == StreakState.NONE -> stringResource(R.string.auralys_streak_status_none)
        streak.state == StreakState.WAITING -> stringResource(R.string.auralys_streak_status_waiting)
        streak.recoveredToday -> stringResource(R.string.auralys_streak_recovered_today)
        else -> stringResource(streak.tier.nameRes)
    }
