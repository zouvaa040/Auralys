package com.metrolist.music.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.constants.ShowStreakOnHomeKey
import com.metrolist.music.ui.component.glassCard
import com.metrolist.music.ui.component.glassShape
import com.metrolist.music.ui.component.painterResource
import com.metrolist.music.ui.theme.HorizonRadius
import com.metrolist.music.utils.FlameArt
import com.metrolist.music.utils.ListeningStreak
import com.metrolist.music.utils.StreakPosterRenderer
import com.metrolist.music.utils.StreakState
import com.metrolist.music.utils.StreakTier
import com.metrolist.music.utils.StreakTiers
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.launch

/** The position in the flame's animation loop (0..1), one shared clock for however many flames are on screen. */
@Composable
fun rememberFlamePhase(): State<Float> =
    rememberInfiniteTransition(label = "flame").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "flame phase",
    )

/** The animated flame of [tier]; grey and almost still while it is not [lit]. */
@Composable
fun StreakFlame(
    tier: StreakTier,
    lit: Boolean,
    modifier: Modifier = Modifier,
    phase: State<Float> = rememberFlamePhase(),
) {
    Canvas(modifier) {
        drawIntoCanvas { FlameArt.draw(it.nativeCanvas, size.width, size.height, tier, lit, phase.value) }
    }
}

/**
 * The listening streak: the flame (grey until something was played today, hotter the longer the streak), how it stands,
 * the way to the next flame, the recovery of a missed day, and buttons to share the streak as a picture and to see
 * every flame there is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListeningStreakCard(
    streak: ListeningStreak,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = glassShape(HorizonRadius.Lg, HorizonRadius.Lg)
    val tier = streak.tier
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shareFailed = stringResource(R.string.auralys_streak_share_failed)
    var showGallery by remember { mutableStateOf(false) }

    // the number counts up when the card appears
    var target by remember { mutableIntStateOf(0) }
    LaunchedEffect(streak.current) { target = streak.current }
    val shown by animateIntAsState(target, tween(900), label = "streak number")

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .glassCard(shape)
                .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StreakFlame(tier = tier, lit = streak.lit, modifier = Modifier.size(width = 112.dp, height = 128.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(tier.nameRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    text = shown.toString(),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (streak.lit) scheme.onSurface else scheme.onSurfaceVariant,
                )
                Text(
                    text = pluralStringResource(R.plurals.auralys_streak_poster_days, streak.current),
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Column {
                IconButton(
                    enabled = streak.current > 0,
                    onClick = {
                        scope.launch {
                            try {
                                StreakPosterRenderer.share(context, streak)
                            } catch (_: Exception) {
                                Toast.makeText(context, shareFailed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.share),
                        contentDescription = stringResource(R.string.auralys_streak_share),
                        tint = scheme.onSurface,
                    )
                }
                IconButton(onClick = { showGallery = true }) {
                    Icon(
                        painter = painterResource(R.drawable.info),
                        contentDescription = stringResource(R.string.auralys_streak_gallery_open),
                        tint = scheme.onSurface,
                    )
                }
            }
        }

        StreakStatus(streak = streak)

        val next = streak.nextMilestone
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text =
                    if (next != null) {
                        pluralStringResource(
                            R.plurals.auralys_streak_goal_short,
                            (next - streak.current).coerceAtLeast(1),
                            stringResource(StreakTiers.forDays(next).nameRes),
                            (next - streak.current).coerceAtLeast(1),
                        )
                    } else {
                        stringResource(R.string.auralys_streak_all_done)
                    },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            StreakProgressBar(progress = streak.milestoneProgress.coerceIn(0.03f, 1f), tier = tier, lit = streak.lit)
        }

        // three numbers with one word each (it was two lines of grey sentences: the owner found the card too wordy)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.onSurface.copy(alpha = 0.05f))
                    .padding(vertical = 10.dp),
        ) {
            StreakStat(value = streak.longest.toString(), label = stringResource(R.string.auralys_streak_stat_longest), modifier = Modifier.weight(1f))
            StreakStat(value = streak.totalDays.toString(), label = stringResource(R.string.auralys_streak_stat_total), modifier = Modifier.weight(1f))
            StreakStat(
                value = "${streak.recoveriesLeft}/${ListeningStreak.MAX_RECOVERIES}",
                label = stringResource(R.string.auralys_streak_stat_recoveries),
                modifier = Modifier.weight(1f),
            )
        }

        val (showOnHome, onShowOnHomeChange) = rememberPreference(ShowStreakOnHomeKey, true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.auralys_streak_home_setting),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = showOnHome, onCheckedChange = onShowOnHomeChange)
        }
    }

    if (showGallery) {
        FlameGallerySheet(currentDays = streak.current, onDismiss = { showGallery = false })
    }
}

/** The line under the flame that says where the streak stands, or the recovery box when yesterday was missed. */
@Composable
private fun StreakStatus(streak: ListeningStreak) {
    val scheme = MaterialTheme.colorScheme
    if (streak.state == StreakState.RECOVERABLE) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(HorizonRadius.Lg))
                    .background(scheme.surfaceContainerHigh)
                    .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.auralys_streak_status_recoverable, ListeningStreak.RECOVERY_MINUTES),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            val barTier = StreakTiers.forDays(maxOf(streak.current, 1))
            StreakProgressBar(progress = streak.recoveryProgress.coerceAtLeast(0.03f), tier = barTier, lit = true)
            Text(
                text = stringResource(R.string.auralys_streak_recovery_progress, minOf(streak.todayMinutes, ListeningStreak.RECOVERY_MINUTES), ListeningStreak.RECOVERY_MINUTES),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        return
    }
    // burning and nothing to do: the flame and its name above already say it
    if (streak.state == StreakState.ACTIVE && !streak.recoveredToday) return
    Text(
        text =
            stringResource(
                when {
                    streak.state == StreakState.NONE && streak.recoveryBlocked -> R.string.auralys_streak_recovery_blocked
                    streak.state == StreakState.NONE -> R.string.auralys_streak_status_none
                    streak.state == StreakState.WAITING -> R.string.auralys_streak_status_waiting
                    streak.recoveredToday -> R.string.auralys_streak_recovered_today
                    else -> R.string.auralys_streak_status_active
                },
            ),
        style = MaterialTheme.typography.bodyMedium,
        color = if (streak.lit) scheme.onSurface else scheme.onSurfaceVariant,
        fontWeight = if (streak.lit) FontWeight.SemiBold else FontWeight.Normal,
    )
}

/** A number with a one-word label under it (centred): longest streak, days listened, recoveries left. */
@Composable
private fun StreakStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = scheme.onSurface, maxLines = 1)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
internal fun StreakProgressBar(progress: Float, tier: StreakTier, lit: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val colors = if (lit) listOf(Color(tier.tip), Color(tier.accent)) else listOf(scheme.outline, scheme.outline)
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(scheme.onSurface.copy(alpha = 0.10f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Brush.horizontalGradient(colors)),
        )
    }
}

/** Every flame, so a listener can see what 100 or 1000 days will look like: the ones reached, and the ones still ahead. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlameGallerySheet(currentDays: Int, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val phase = rememberFlamePhase()
    val currentTier = StreakTiers.forDays(currentDays)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // glass themes make the surface colours translucent, which let the screen behind show through the sheet
        containerColor = scheme.surfaceContainerLow.copy(alpha = 1f),
    ) {
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.auralys_streak_gallery_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.auralys_streak_gallery_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            StreakTiers.all.filter { it.index > 0 }.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { tier ->
                        FlameTile(tier, currentDays, tier.index == currentTier.index, phase, Modifier.weight(1f))
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FlameTile(tier: StreakTier, currentDays: Int, isCurrent: Boolean, phase: State<Float>, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val reached = currentDays >= tier.minDays
    val shape = RoundedCornerShape(HorizonRadius.Xl)
    Column(
        modifier =
            modifier
                .clip(shape)
                .background(scheme.surfaceContainerHigh.copy(alpha = 1f))
                .then(if (isCurrent) Modifier.border(BorderStroke(2.dp, scheme.onSurface), shape) else Modifier)
                .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        StreakFlame(tier = tier, lit = true, modifier = Modifier.size(width = 104.dp, height = 118.dp), phase = phase)
        Text(stringResource(tier.nameRes), style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, textAlign = TextAlign.Center)
        Text(
            pluralStringResource(R.plurals.auralys_streak_gallery_days, tier.minDays, tier.minDays),
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(
                painter = painterResource(if (reached) R.drawable.check else R.drawable.lock),
                contentDescription = null,
                tint = if (reached) scheme.onSurface else scheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text =
                    when {
                        isCurrent -> stringResource(R.string.auralys_streak_gallery_here)
                        reached -> stringResource(R.string.auralys_streak_gallery_unlocked)
                        else -> stringResource(R.string.auralys_streak_gallery_togo, tier.minDays - currentDays)
                    },
                style = MaterialTheme.typography.labelSmall,
                color = if (reached) scheme.onSurface else scheme.onSurfaceVariant,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}
