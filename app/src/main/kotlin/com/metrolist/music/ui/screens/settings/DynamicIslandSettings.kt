/**
 * Auralys - Dynamic Island settings
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import android.content.Intent
import android.widget.Toast
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import com.metrolist.music.ui.utils.horizontalBarInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.DynamicIslandAutoBorder
import com.metrolist.music.constants.DynamicIslandButtonStyle
import com.metrolist.music.constants.DynamicIslandFollowRoundness
import com.metrolist.music.constants.DynamicIslandMaxBorderWidth
import com.metrolist.music.constants.DynamicIslandMaxCameraGap
import com.metrolist.music.constants.DynamicIslandMaxExpandedWidth
import com.metrolist.music.constants.DynamicIslandMinCameraGap
import com.metrolist.music.constants.DynamicIslandMaxScalePercent
import com.metrolist.music.constants.DynamicIslandMinExpandedWidth
import com.metrolist.music.constants.DynamicIslandMinScalePercent
import com.metrolist.music.constants.DynamicIslandCompactContent
import com.metrolist.music.constants.DynamicIslandCoverShape
import com.metrolist.music.constants.DynamicIslandDefaultY
import com.metrolist.music.constants.DynamicIslandEnabledKey
import com.metrolist.music.constants.DynamicIslandEqColor
import com.metrolist.music.constants.DynamicIslandEqStyle
import com.metrolist.music.constants.DynamicIslandGlowColor
import com.metrolist.music.constants.DynamicIslandLayout
import com.metrolist.music.constants.DynamicIslandLongPressAction
import com.metrolist.music.constants.DynamicIslandPlacement
import com.metrolist.music.constants.DynamicIslandMaxAutoCollapse
import com.metrolist.music.constants.DynamicIslandMaxPillHeight
import com.metrolist.music.constants.DynamicIslandMaxPillWidth
import com.metrolist.music.constants.DynamicIslandMaxY
import com.metrolist.music.constants.DynamicIslandMinAutoCollapse
import com.metrolist.music.constants.DynamicIslandMinOpacity
import com.metrolist.music.constants.DynamicIslandMinPillHeight
import com.metrolist.music.constants.DynamicIslandMinPillWidth
import com.metrolist.music.constants.DynamicIslandMinY
import com.metrolist.music.constants.DynamicIslandSize
import com.metrolist.music.constants.DynamicIslandStyle
import com.metrolist.music.constants.DynamicIslandTapAction
import com.metrolist.music.constants.DynamicIslandXKey
import com.metrolist.music.constants.DynamicIslandXLandscapeKey
import com.metrolist.music.constants.DynamicIslandXOffsetKey
import com.metrolist.music.constants.DynamicIslandXOffsetLimit
import com.metrolist.music.constants.DynamicIslandYKey
import com.metrolist.music.constants.DynamicIslandYLandscapeKey
import com.metrolist.music.ui.component.ChipsRow
import com.metrolist.music.ui.component.DynamicIslandActions
import com.metrolist.music.ui.component.DynamicIslandConfig
import com.metrolist.music.ui.component.DynamicIslandDimens
import com.metrolist.music.ui.component.IslandLyricLine
import com.metrolist.music.ui.component.IslandLyrics
import com.metrolist.music.ui.component.IslandLyricsStatus
import com.metrolist.music.ui.component.DynamicIslandPill
import com.metrolist.music.ui.component.DynamicIslandPreset
import com.metrolist.music.ui.component.DynamicIslandUiState
import com.metrolist.music.ui.component.EnumDialog
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.NoOpDynamicIslandActions
import com.metrolist.music.ui.component.Slider
import com.metrolist.music.ui.component.Switch
import com.metrolist.music.ui.component.TopAppBar
import com.metrolist.music.ui.component.painterResource
import com.metrolist.music.ui.component.putDynamicIslandConfig
import com.metrolist.music.ui.component.toDynamicIslandConfig
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Options picked from a list dialog. */
private enum class IslandChoice {
    STYLE,
    GLOW_COLOR,
    COVER_SHAPE,
    COMPACT_CONTENT,
    EQ_STYLE,
    EQ_COLOR,
    LAYOUT,
    BUTTON_STYLE,
    TAP_ACTION,
    LONG_PRESS,
    POSITION,
}

/** The colours the user can pick (0 means "automatic" for the ones that allow it). */
private enum class IslandColorTarget {
    CUSTOM_TOP,
    CUSTOM_BOTTOM,
    TEXT,
    ACCENT,
    BORDER,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DynamicIslandSettings(
    navController: NavController,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val (enabled, onEnabledChange) = rememberPreference(DynamicIslandEnabledKey, defaultValue = false)
    val (xPref, onXPrefChange) = rememberPreference(DynamicIslandXKey, defaultValue = 0f)
    val (yPref, onYPrefChange) = rememberPreference(DynamicIslandYKey, defaultValue = DynamicIslandDefaultY)
    val (xOffsetPref, onXOffsetPrefChange) = rememberPreference(DynamicIslandXOffsetKey, defaultValue = 0)

    // All look/behaviour options, read through the same mapper the overlay uses.
    val stored by remember {
        context.dataStore.data
            .map { it.toDynamicIslandConfig() }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = DynamicIslandConfig())
    // Unsaved value (slider being dragged, or a write in flight) so the preview follows instantly.
    var draft by remember { mutableStateOf<DynamicIslandConfig?>(null) }
    LaunchedEffect(stored) { draft = null }
    val config = draft ?: stored
    val commit: (DynamicIslandConfig) -> Unit = { value ->
        draft = value
        scope.launch { context.safeDataStoreEdit { it.putDynamicIslandConfig(value) } }
    }
    val commitDraft: () -> Unit = { draft?.let(commit) }

    // Local slider values: DataStore is written only when the user releases the slider.
    var xLocal by remember(xPref) { mutableFloatStateOf(xPref.coerceIn(-1f, 1f)) }
    var yLocal by remember(yPref) { mutableFloatStateOf(yPref.coerceIn(DynamicIslandMinY, DynamicIslandMaxY).toFloat()) }
    var xOffsetLocal by remember(xOffsetPref) {
        mutableFloatStateOf(xOffsetPref.coerceIn(-DynamicIslandXOffsetLimit, DynamicIslandXOffsetLimit).toFloat())
    }
    val view = LocalView.current
    val sizeValues = DynamicIslandSize.entries

    // The island lives in the accessibility service of Auralys (the only window that can be in front of the status bar):
    // its state is read from the service itself, so the row follows it as soon as the user comes back from the settings.
    val frontService by com.metrolist.music.playback.IslandAccessibilityHost.service.collectAsState()
    val openAccessibilitySettings: () -> Unit = {
        try {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ preview state
    val sampleTitle = stringResource(R.string.dynamic_island_sample_title)
    val sampleArtist = stringResource(R.string.dynamic_island_sample_artist)
    val sampleLyric = stringResource(R.string.dynamic_island_sample_lyric)
    val previewState = remember(sampleTitle, sampleArtist, sampleLyric) { samplePreviewState(sampleTitle, sampleArtist, expanded = false, lyric = sampleLyric) }
    val cardState = remember(sampleTitle, sampleArtist, sampleLyric) { samplePreviewState(sampleTitle, sampleArtist, expanded = true, lyric = sampleLyric) }
    val appAccent = MaterialTheme.colorScheme.primary
    // the previews follow the app's own light/dark choice, like the overlay does
    val appDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    SideEffect {
        previewState.config = config
        cardState.config = config
        previewState.appAccent = appAccent
        cardState.appAccent = appAccent
        previewState.dark = appDark
        cardState.dark = appDark
    }
    // Bumped on every preview interaction so the auto-collapse timer restarts (like the overlay).
    var previewInteraction by remember { mutableIntStateOf(0) }
    LaunchedEffect(previewState.expanded, previewInteraction, config.autoCollapseSec) {
        if (previewState.expanded) {
            delay(config.autoCollapseSec * 1000L + 2_000L)
            previewState.expanded = false
        }
    }

    var choice by remember { mutableStateOf<IslandChoice?>(null) }
    var colorTarget by remember { mutableStateOf<IslandColorTarget?>(null) }
    var showPlace by remember { mutableStateOf(false) }
    val statusBarDpForPlace = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp().value }
    val activePreset = DynamicIslandPreset.entries.firstOrNull { it.matches(config) }
    val presetChips: List<Pair<DynamicIslandPreset?, String>> = DynamicIslandPreset.entries.map { it to presetLabel(it) }

    Column(
        modifier =
            Modifier
                .horizontalBarInsets()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
    ) {
        com.metrolist.music.ui.utils.BarsTopSpacer()
        Material3SettingsGroup(
            title = stringResource(R.string.dynamic_island),
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.ic_dynamic_island),
                        title = { Text(stringResource(R.string.dynamic_island_enable)) },
                        description = { Text(stringResource(R.string.dynamic_island_enable_desc)) },
                        trailingContent = {
                            IslandSwitch(
                                checked = enabled,
                                onCheckedChange = { value ->
                                    onEnabledChange(value)
                                    if (value && frontService == null) openAccessibilitySettings()
                                },
                            )
                        },
                        onClick = {
                            val value = !enabled
                            onEnabledChange(value)
                            if (value && frontService == null) openAccessibilitySettings()
                        },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(if (frontService != null) R.drawable.check else R.drawable.close),
                        title = { Text(stringResource(R.string.dynamic_island_service)) },
                        description = {
                            Text(
                                stringResource(
                                    if (frontService != null) R.string.dynamic_island_service_on else R.string.dynamic_island_service_off,
                                ),
                            )
                        },
                        isHighlighted = enabled && frontService == null,
                        onClick = openAccessibilitySettings,
                    ),
                ),
        )

        SectionSpacer()

        // ------------------------------------------------------------ presets
        Material3SettingsGroup(
            title = stringResource(R.string.dynamic_island_presets),
            items =
                listOf(
                    Material3SettingsItem(
                        title = {
                            Text(activePreset?.let { presetLabel(it) } ?: stringResource(R.string.dynamic_island_preset_custom))
                        },
                        description = {
                            Column {
                                Text(stringResource(R.string.dynamic_island_presets_hint))
                                Spacer(Modifier.height(10.dp))
                                ChipsRow<DynamicIslandPreset?>(
                                    chips = presetChips,
                                    currentValue = activePreset,
                                    onValueUpdate = { preset -> preset?.let { commit(it.applyTo(config)) } },
                                )
                            }
                        },
                        onClick = null,
                    ),
                ),
        )

        SectionSpacer()

        // ------------------------------------------------------------ preview
        Material3SettingsGroup(
            title = stringResource(R.string.dynamic_island_preview),
            items =
                listOf(
                    Material3SettingsItem(
                        title = {},
                        description = { Text(stringResource(R.string.dynamic_island_preview_hint)) },
                        onClick = null,
                    ),
                ),
        )
        Spacer(Modifier.height(8.dp))
        DynamicIslandPreview(
            state = previewState,
            cardState = cardState,
            config = config,
            xFraction = xLocal,
            xOffsetDp = xOffsetLocal,
            yDp = yLocal,
            onInteraction = { previewInteraction++ },
        )

        SectionSpacer()

        // ------------------------------------------------------------ appearance
        Material3SettingsGroup(
            title = stringResource(R.string.dynamic_island_appearance),
            items =
                listOfNotNull(
                    choiceItem(
                        icon = R.drawable.palette,
                        title = stringResource(R.string.dynamic_island_style),
                        value = styleLabel(config.style),
                        onClick = { choice = IslandChoice.STYLE },
                    ),
                    sliderItem(
                        icon = R.drawable.crop,
                        title = stringResource(R.string.dynamic_island_roundness),
                        valueText = stringResource(R.string.dynamic_island_percent, config.roundness),
                        value = config.roundness.toFloat(),
                        range = 0f..100f,
                        onChange = { draft = config.copy(roundness = it.roundToInt()) },
                        onFinished = commitDraft,
                    ),
                    sliderItem(
                        icon = R.drawable.contrast,
                        title = stringResource(R.string.dynamic_island_opacity),
                        valueText = stringResource(R.string.dynamic_island_percent, config.opacity),
                        value = config.opacity.toFloat(),
                        range = DynamicIslandMinOpacity.toFloat()..100f,
                        onChange = { draft = config.copy(opacity = it.roundToInt()) },
                        onFinished = commitDraft,
                    ),
                    if (config.style == DynamicIslandStyle.LIQUID) {
                        sliderItem(
                            icon = R.drawable.contrast,
                            title = stringResource(R.string.dynamic_island_blur),
                            valueText = stringResource(R.string.dynamic_island_percent, config.blur),
                            value = config.blur.toFloat(),
                            range = 0f..100f,
                            onChange = { draft = config.copy(blur = it.roundToInt()) },
                            onFinished = commitDraft,
                        )
                    } else {
                        null
                    },
                    if (config.style == DynamicIslandStyle.LIQUID || config.style == DynamicIslandStyle.GLASS) {
                        sliderItem(
                            icon = R.drawable.gradient,
                            title = stringResource(R.string.dynamic_island_shine),
                            valueText = stringResource(R.string.dynamic_island_percent, config.shine),
                            value = config.shine.toFloat(),
                            range = 0f..100f,
                            onChange = { draft = config.copy(shine = it.roundToInt()) },
                            onFinished = commitDraft,
                        )
                    } else {
                        null
                    },
                    if (config.style == DynamicIslandStyle.CUSTOM) {
                        colorItem(
                            title = stringResource(R.string.dynamic_island_custom_top),
                            color = config.customTop,
                            autoLabel = null,
                            onClick = { colorTarget = IslandColorTarget.CUSTOM_TOP },
                        )
                    } else {
                        null
                    },
                    if (config.style == DynamicIslandStyle.CUSTOM) {
                        colorItem(
                            title = stringResource(R.string.dynamic_island_custom_bottom),
                            color = config.customBottom,
                            autoLabel = null,
                            onClick = { colorTarget = IslandColorTarget.CUSTOM_BOTTOM },
                        )
                    } else {
                        null
                    },
                    colorItem(
                        title = stringResource(R.string.dynamic_island_text_color),
                        color = config.textColor,
                        autoLabel = stringResource(R.string.dynamic_island_color_auto),
                        onClick = { colorTarget = IslandColorTarget.TEXT },
                    ),
                    colorItem(
                        title = stringResource(R.string.dynamic_island_accent_color),
                        color = config.accentColor,
                        autoLabel = stringResource(R.string.dynamic_island_color_auto),
                        extra = stringResource(R.string.dynamic_island_accent_color_desc),
                        onClick = { colorTarget = IslandColorTarget.ACCENT },
                    ),
                    sliderItem(
                        icon = R.drawable.crop,
                        title = stringResource(R.string.dynamic_island_border_width),
                        valueText =
                            when (config.borderWidth) {
                                DynamicIslandAutoBorder -> stringResource(R.string.dynamic_island_color_auto)
                                0 -> stringResource(R.string.dynamic_island_border_none)
                                else -> stringResource(R.string.dynamic_island_border_value, config.borderWidth)
                            },
                        value = config.borderWidth.toFloat(),
                        range = DynamicIslandAutoBorder.toFloat()..DynamicIslandMaxBorderWidth.toFloat(),
                        steps = DynamicIslandMaxBorderWidth - DynamicIslandAutoBorder - 1,
                        onChange = { draft = config.copy(borderWidth = it.roundToInt()) },
                        onFinished = commitDraft,
                    ),
                    if (config.borderWidth != 0) {
                        colorItem(
                            title = stringResource(R.string.dynamic_island_border_color),
                            color = config.borderColor,
                            autoLabel = stringResource(R.string.dynamic_island_color_auto),
                            onClick = { colorTarget = IslandColorTarget.BORDER },
                        )
                    } else {
                        null
                    },
                    switchItem(
                        icon = R.drawable.gradient,
                        title = stringResource(R.string.dynamic_island_glow),
                        description = stringResource(R.string.dynamic_island_glow_desc),
                        checked = config.glow,
                        onChange = { commit(config.copy(glow = it)) },
                    ),
                    if (config.glow) {
                        sliderItem(
                            icon = R.drawable.tune,
                            title = stringResource(R.string.dynamic_island_glow_intensity),
                            valueText = stringResource(R.string.dynamic_island_percent, config.glowIntensity),
                            value = config.glowIntensity.toFloat(),
                            range = 0f..100f,
                            onChange = { draft = config.copy(glowIntensity = it.roundToInt()) },
                            onFinished = commitDraft,
                        )
                    } else {
                        null
                    },
                    if (config.glow) {
                        choiceItem(
                            icon = R.drawable.palette,
                            title = stringResource(R.string.dynamic_island_glow_color),
                            value = glowColorLabel(config.glowColor),
                            onClick = { choice = IslandChoice.GLOW_COLOR },
                        )
                    } else {
                        null
                    },
                    choiceItem(
                        icon = R.drawable.album,
                        title = stringResource(R.string.dynamic_island_cover_shape),
                        value = coverShapeLabel(config.coverShape),
                        onClick = { choice = IslandChoice.COVER_SHAPE },
                    ),
                    if (config.coverShape == DynamicIslandCoverShape.CIRCLE) {
                        switchItem(
                            icon = R.drawable.sync,
                            title = stringResource(R.string.dynamic_island_spin_cover),
                            description = stringResource(R.string.dynamic_island_spin_cover_desc),
                            checked = config.spinCover,
                            onChange = { commit(config.copy(spinCover = it)) },
                        )
                    } else {
                        null
                    },
                ),
        )

        SectionSpacer()

        // ------------------------------------------------------------ compact pill
        Material3SettingsGroup(
            title = stringResource(R.string.dynamic_island_compact),
            items =
                listOf(
                    sliderItem(
                        icon = R.drawable.fullscreen,
                        title = stringResource(R.string.dynamic_island_pill_width),
                        valueText = stringResource(R.string.dynamic_island_vertical_value, config.pillWidth),
                        value = config.pillWidth.toFloat(),
                        range = DynamicIslandMinPillWidth.toFloat()..DynamicIslandMaxPillWidth.toFloat(),
                        onChange = { draft = config.copy(pillWidth = it.roundToInt()) },
                        onFinished = commitDraft,
                    ),
                    sliderItem(
                        icon = R.drawable.tune,
                        title = stringResource(R.string.dynamic_island_pill_height),
                        valueText = stringResource(R.string.dynamic_island_vertical_value, config.pillHeight),
                        value = config.pillHeight.toFloat(),
                        range = DynamicIslandMinPillHeight.toFloat()..DynamicIslandMaxPillHeight.toFloat(),
                        onChange = { draft = config.copy(pillHeight = it.roundToInt()) },
                        onFinished = commitDraft,
                    ),
                    choiceItem(
                        icon = R.drawable.linear_scale,
                        title = stringResource(R.string.dynamic_island_placement),
                        value = placementLabel(config.attached),
                        onClick = { choice = IslandChoice.POSITION },
                    ),
                    choiceItem(
                        icon = R.drawable.lyrics,
                        title = stringResource(R.string.dynamic_island_compact_content),
                        value = compactContentLabel(config.compactContent),
                        onClick = { choice = IslandChoice.COMPACT_CONTENT },
                    ),
                    switchItem(
                        icon = R.drawable.lyrics,
                        title = stringResource(R.string.dynamic_island_lyric_camera),
                        checked = config.lyricCameraGap,
                        onChange = { commit(config.copy(lyricCameraGap = it)) },
                    ),
                    sliderItem(
                        icon = R.drawable.tune,
                        title = stringResource(R.string.dynamic_island_camera_gap),
                        valueText = stringResource(R.string.dynamic_island_vertical_value, config.cameraGapDp),
                        value = config.cameraGapDp.toFloat(),
                        range = DynamicIslandMinCameraGap.toFloat()..DynamicIslandMaxCameraGap.toFloat(),
                        onChange = { draft = config.copy(cameraGapDp = it.roundToInt()) },
                        onFinished = commitDraft,
                    ),
                    choiceItem(
                        icon = R.drawable.graphic_eq,
                        title = stringResource(R.string.dynamic_island_eq_style),
                        value = eqStyleLabel(config.eqStyle),
                        onClick = { choice = IslandChoice.EQ_STYLE },
                    ),
                    choiceItem(
                        icon = R.drawable.equalizer,
                        title = stringResource(R.string.dynamic_island_eq_color),
                        value = eqColorLabel(config.eqColor),
                        onClick = { choice = IslandChoice.EQ_COLOR },
                    ),
                ),
        )

        SectionSpacer()

        // ------------------------------------------------------------ expanded card
        val cardLayout = config.layout == DynamicIslandLayout.CARD
        Material3SettingsGroup(
            title = stringResource(R.string.dynamic_island_expanded),
            items =
                listOfNotNull(
                    choiceItem(
                        icon = R.drawable.grid_view,
                        title = stringResource(R.string.dynamic_island_layout),
                        value = layoutLabel(config.layout),
                        onClick = { choice = IslandChoice.LAYOUT },
                    ),
                    sliderItem(
                        icon = R.drawable.fullscreen,
                        title = stringResource(R.string.dynamic_island_expanded_width),
                        valueText = stringResource(R.string.dynamic_island_vertical_value, config.expandedWidth),
                        value = config.expandedWidth.toFloat(),
                        range = DynamicIslandMinExpandedWidth.toFloat()..DynamicIslandMaxExpandedWidth.toFloat(),
                        onChange = { draft = config.copy(expandedWidth = it.roundToInt()) },
                        onFinished = commitDraft,
                    ),
                    switchItem(
                        icon = R.drawable.crop,
                        title = stringResource(R.string.dynamic_island_follow_roundness),
                        checked = config.expandedRoundness == DynamicIslandFollowRoundness,
                        onChange = {
                            commit(
                                config.copy(expandedRoundness = if (it) DynamicIslandFollowRoundness else config.roundness),
                            )
                        },
                    ),
                    if (config.expandedRoundness != DynamicIslandFollowRoundness) {
                        sliderItem(
                            icon = R.drawable.crop,
                            title = stringResource(R.string.dynamic_island_expanded_roundness),
                            valueText = stringResource(R.string.dynamic_island_percent, config.expandedRoundness),
                            value = config.expandedRoundness.toFloat(),
                            range = 0f..100f,
                            onChange = { draft = config.copy(expandedRoundness = it.roundToInt()) },
                            onFinished = commitDraft,
                        )
                    } else {
                        null
                    },
                    sliderItem(
                        icon = R.drawable.album,
                        title = stringResource(R.string.dynamic_island_cover_size),
                        valueText = stringResource(R.string.dynamic_island_percent, config.coverScale),
                        value = config.coverScale.toFloat(),
                        range = DynamicIslandMinScalePercent.toFloat()..DynamicIslandMaxScalePercent.toFloat(),
                        onChange = { draft = config.copy(coverScale = it.roundToInt()) },
                        onFinished = commitDraft,
                    ),
                    sliderItem(
                        icon = R.drawable.lyrics,
                        title = stringResource(R.string.dynamic_island_text_size),
                        valueText = stringResource(R.string.dynamic_island_percent, config.textScale),
                        value = config.textScale.toFloat(),
                        range = DynamicIslandMinScalePercent.toFloat()..DynamicIslandMaxScalePercent.toFloat(),
                        onChange = { draft = config.copy(textScale = it.roundToInt()) },
                        onFinished = commitDraft,
                    ),
                    switchItem(
                        icon = R.drawable.music_note,
                        title = stringResource(R.string.dynamic_island_show_artist),
                        checked = config.showArtist,
                        onChange = { commit(config.copy(showArtist = it)) },
                    ),
                    switchItem(
                        icon = R.drawable.linear_scale,
                        title = stringResource(R.string.dynamic_island_show_progress),
                        description = stringResource(R.string.dynamic_island_show_progress_desc),
                        checked = config.showProgress && cardLayout,
                        enabled = cardLayout,
                        onChange = { commit(config.copy(showProgress = it)) },
                    ),
                    switchItem(
                        icon = R.drawable.lyrics,
                        title = stringResource(R.string.dynamic_island_show_lyrics),
                        checked = config.showLyrics,
                        onChange = { commit(config.copy(showLyrics = it)) },
                    ),
                    switchItem(
                        icon = R.drawable.play,
                        title = stringResource(R.string.dynamic_island_show_buttons),
                        checked = config.showButtons,
                        onChange = { commit(config.copy(showButtons = it)) },
                    ),
                    choiceItem(
                        icon = R.drawable.skip_next,
                        title = stringResource(R.string.dynamic_island_button_style),
                        value = buttonStyleLabel(config.buttonStyle),
                        enabled = config.showButtons,
                        onClick = { choice = IslandChoice.BUTTON_STYLE },
                    ),
                ),
        )

        SectionSpacer()

        // ------------------------------------------------------------ behaviour
        Material3SettingsGroup(
            title = stringResource(R.string.dynamic_island_behaviour),
            items =
                listOf(
                    sliderItem(
                        icon = R.drawable.timer,
                        title = stringResource(R.string.dynamic_island_auto_collapse),
                        valueText = stringResource(R.string.dynamic_island_seconds, config.autoCollapseSec),
                        value = config.autoCollapseSec.toFloat(),
                        range = DynamicIslandMinAutoCollapse.toFloat()..DynamicIslandMaxAutoCollapse.toFloat(),
                        steps = DynamicIslandMaxAutoCollapse - DynamicIslandMinAutoCollapse - 1,
                        onChange = { draft = config.copy(autoCollapseSec = it.roundToInt()) },
                        onFinished = commitDraft,
                    ),
                    choiceItem(
                        icon = R.drawable.ic_dynamic_island,
                        title = stringResource(R.string.dynamic_island_tap_action),
                        value = tapActionLabel(config.tapAction),
                        onClick = { choice = IslandChoice.TAP_ACTION },
                    ),
                    choiceItem(
                        icon = R.drawable.drag_handle,
                        title = stringResource(R.string.dynamic_island_long_press),
                        value = longPressLabel(config.longPressAction),
                        onClick = { choice = IslandChoice.LONG_PRESS },
                    ),
                    switchItem(
                        icon = R.drawable.tune,
                        title = stringResource(R.string.dynamic_island_haptics),
                        checked = config.haptics,
                        onChange = { commit(config.copy(haptics = it)) },
                    ),
                ),
        )

        SectionSpacer()

        // ------------------------------------------------------------ position and size
        val horizontalPercent = (xLocal * 100f).roundToInt()
        Material3SettingsGroup(
            title = stringResource(R.string.dynamic_island_position),
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.ic_dynamic_island),
                        title = { Text(stringResource(R.string.dynamic_island_place)) },
                        description = { Text(stringResource(R.string.dynamic_island_place_desc)) },
                        isHighlighted = true,
                        onClick = { showPlace = true },
                    ),
                    sliderItem(
                        icon = R.drawable.drag_handle,
                        title = stringResource(R.string.dynamic_island_horizontal),
                        valueText =
                            when {
                                abs(horizontalPercent) < 1 -> stringResource(R.string.dynamic_island_center)
                                horizontalPercent < 0 -> stringResource(R.string.dynamic_island_left, abs(horizontalPercent))
                                else -> stringResource(R.string.dynamic_island_right, horizontalPercent)
                            },
                        value = xLocal,
                        range = -1f..1f,
                        // Snap to the centre when close to it.
                        onChange = { xLocal = if (abs(it) < 0.04f) 0f else it },
                        onFinished = { onXPrefChange(xLocal) },
                    ),
                    sliderItem(
                        icon = R.drawable.drag_handle,
                        title = stringResource(R.string.dynamic_island_fine_x),
                        valueText = stringResource(R.string.dynamic_island_vertical_value, xOffsetLocal.roundToInt()),
                        value = xOffsetLocal,
                        range = -DynamicIslandXOffsetLimit.toFloat()..DynamicIslandXOffsetLimit.toFloat(),
                        onChange = { xOffsetLocal = it.roundToInt().toFloat() },
                        onFinished = { onXOffsetPrefChange(xOffsetLocal.roundToInt()) },
                    ),
                    sliderItem(
                        icon = R.drawable.tune,
                        title = stringResource(R.string.dynamic_island_vertical),
                        valueText = stringResource(R.string.dynamic_island_vertical_value, yLocal.roundToInt()),
                        // 0 sits in the middle: the left half goes up into the status bar, the right half down the screen
                        value = IslandHeightSlider.toSlider(yLocal),
                        range = -1f..1f,
                        onChange = { yLocal = IslandHeightSlider.fromSlider(it).toFloat() },
                        onFinished = { onYPrefChange(yLocal.roundToInt()) },
                    ),
                    sliderItem(
                        icon = R.drawable.ic_dynamic_island,
                        title = stringResource(R.string.dynamic_island_size),
                        valueText = sizeLabel(config.size),
                        value = config.size.ordinal.toFloat(),
                        range = 0f..sizeValues.lastIndex.toFloat(),
                        steps = (sizeValues.size - 2).coerceAtLeast(0),
                        onChange = { draft = config.copy(size = sizeValues[it.roundToInt().coerceIn(0, sizeValues.lastIndex)]) },
                        onFinished = commitDraft,
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.ic_dynamic_island),
                        title = { Text(stringResource(R.string.dynamic_island_fit_camera)) },
                        description = { Text(stringResource(R.string.dynamic_island_fit_camera_desc)) },
                        onClick = {
                            val fit = fitToCamera(view, config)
                            if (fit == null) {
                                Toast.makeText(context, R.string.dynamic_island_fit_camera_none, Toast.LENGTH_LONG).show()
                            } else {
                                xLocal = fit.x
                                yLocal = fit.y.toFloat()
                                xOffsetLocal = 0f
                                commit(config.copy(pillWidth = fit.pillWidth, pillHeight = fit.pillHeight))
                                scope.launch {
                                    context.safeDataStoreEdit {
                                        it[DynamicIslandXKey] = fit.x
                                        it[DynamicIslandYKey] = fit.y
                                        it[DynamicIslandXOffsetKey] = 0
                                    }
                                }
                            }
                        },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.restore),
                        title = { Text(stringResource(R.string.dynamic_island_reset)) },
                        description = { Text(stringResource(R.string.dynamic_island_reset_desc)) },
                        onClick = {
                            xLocal = 0f
                            yLocal = DynamicIslandDefaultY.toFloat()
                            xOffsetLocal = 0f
                            onXPrefChange(0f)
                            onYPrefChange(DynamicIslandDefaultY)
                            onXOffsetPrefChange(0)
                        },
                    ),
                ),
        )

        SectionSpacer()

        Material3SettingsGroup(
            title = stringResource(R.string.dynamic_island_gestures),
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.swipe),
                        title = { Text(stringResource(R.string.dynamic_island_gestures_pill)) },
                        onClick = null,
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.ic_dynamic_island),
                        title = { Text(stringResource(R.string.dynamic_island_gestures_expanded)) },
                        onClick = null,
                    ),
                ),
        )

        SectionSpacer()

        Material3SettingsGroup(
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.restore),
                        title = { Text(stringResource(R.string.dynamic_island_reset_all)) },
                        description = { Text(stringResource(R.string.dynamic_island_reset_all_desc)) },
                        isHighlighted = true,
                        onClick = {
                            val defaults = DynamicIslandConfig()
                            draft = defaults
                            xLocal = 0f
                            yLocal = DynamicIslandDefaultY.toFloat()
                            xOffsetLocal = 0f
                            scope.launch {
                                context.safeDataStoreEdit {
                                    it.putDynamicIslandConfig(defaults)
                                    it[DynamicIslandXKey] = 0f
                                    it[DynamicIslandYKey] = DynamicIslandDefaultY
                                    it.remove(DynamicIslandXLandscapeKey)
                                    it.remove(DynamicIslandYLandscapeKey)
                                    it[DynamicIslandXOffsetKey] = 0
                                }
                            }
                        },
                    ),
                ),
        )

        SectionSpacer()
        com.metrolist.music.ui.utils.BarsBottomSpacer()
    }

    when (choice) {
        IslandChoice.STYLE ->
            IslandEnumDialog(
                title = stringResource(R.string.dynamic_island_style),
                current = config.style,
                values = DynamicIslandStyle.entries,
                label = { styleLabel(it) },
                description = { styleDescription(it) },
                onSelect = { commit(config.copy(style = it)) },
                onDismiss = { choice = null },
            )
        IslandChoice.GLOW_COLOR ->
            IslandEnumDialog(
                title = stringResource(R.string.dynamic_island_glow_color),
                current = config.glowColor,
                values = DynamicIslandGlowColor.entries,
                label = { glowColorLabel(it) },
                onSelect = { commit(config.copy(glowColor = it)) },
                onDismiss = { choice = null },
            )
        IslandChoice.COVER_SHAPE ->
            IslandEnumDialog(
                title = stringResource(R.string.dynamic_island_cover_shape),
                current = config.coverShape,
                values = DynamicIslandCoverShape.entries,
                label = { coverShapeLabel(it) },
                onSelect = { commit(config.copy(coverShape = it)) },
                onDismiss = { choice = null },
            )
        IslandChoice.COMPACT_CONTENT ->
            IslandEnumDialog(
                title = stringResource(R.string.dynamic_island_compact_content),
                current = config.compactContent,
                values = DynamicIslandCompactContent.entries,
                label = { compactContentLabel(it) },
                onSelect = { commit(config.copy(compactContent = it)) },
                onDismiss = { choice = null },
            )
        IslandChoice.EQ_STYLE ->
            IslandEnumDialog(
                title = stringResource(R.string.dynamic_island_eq_style),
                current = config.eqStyle,
                values = DynamicIslandEqStyle.entries,
                label = { eqStyleLabel(it) },
                onSelect = { commit(config.copy(eqStyle = it)) },
                onDismiss = { choice = null },
            )
        IslandChoice.EQ_COLOR ->
            IslandEnumDialog(
                title = stringResource(R.string.dynamic_island_eq_color),
                current = config.eqColor,
                values = DynamicIslandEqColor.entries,
                label = { eqColorLabel(it) },
                onSelect = { commit(config.copy(eqColor = it)) },
                onDismiss = { choice = null },
            )
        IslandChoice.LAYOUT ->
            IslandEnumDialog(
                title = stringResource(R.string.dynamic_island_layout),
                current = config.layout,
                values = DynamicIslandLayout.entries,
                label = { layoutLabel(it) },
                description = { layoutDescription(it) },
                onSelect = { commit(config.copy(layout = it)) },
                onDismiss = { choice = null },
            )
        IslandChoice.BUTTON_STYLE ->
            IslandEnumDialog(
                title = stringResource(R.string.dynamic_island_button_style),
                current = config.buttonStyle,
                values = DynamicIslandButtonStyle.entries,
                label = { buttonStyleLabel(it) },
                onSelect = { commit(config.copy(buttonStyle = it)) },
                onDismiss = { choice = null },
            )
        IslandChoice.TAP_ACTION ->
            IslandEnumDialog(
                title = stringResource(R.string.dynamic_island_tap_action),
                current = config.tapAction,
                values = DynamicIslandTapAction.entries,
                label = { tapActionLabel(it) },
                onSelect = { commit(config.copy(tapAction = it)) },
                onDismiss = { choice = null },
            )
        IslandChoice.LONG_PRESS ->
            IslandEnumDialog(
                title = stringResource(R.string.dynamic_island_long_press),
                current = config.longPressAction,
                values = DynamicIslandLongPressAction.entries,
                label = { longPressLabel(it) },
                onSelect = { commit(config.copy(longPressAction = it)) },
                onDismiss = { choice = null },
            )
        IslandChoice.POSITION ->
            IslandEnumDialog(
                title = stringResource(R.string.dynamic_island_placement),
                current = if (config.attached) DynamicIslandPlacement.ATTACHED else DynamicIslandPlacement.FLOATING,
                values = DynamicIslandPlacement.entries,
                label = { placementLabel(it == DynamicIslandPlacement.ATTACHED) },
                onSelect = { commit(config.copy(attached = it == DynamicIslandPlacement.ATTACHED)) },
                onDismiss = { choice = null },
            )
        null -> Unit
    }

    colorTarget?.let { target ->
        val title =
            stringResource(
                when (target) {
                    IslandColorTarget.CUSTOM_TOP -> R.string.dynamic_island_custom_top
                    IslandColorTarget.CUSTOM_BOTTOM -> R.string.dynamic_island_custom_bottom
                    IslandColorTarget.TEXT -> R.string.dynamic_island_text_color
                    IslandColorTarget.ACCENT -> R.string.dynamic_island_accent_color
                    IslandColorTarget.BORDER -> R.string.dynamic_island_border_color
                },
            )
        val current =
            when (target) {
                IslandColorTarget.CUSTOM_TOP -> config.customTop
                IslandColorTarget.CUSTOM_BOTTOM -> config.customBottom
                IslandColorTarget.TEXT -> config.textColor
                IslandColorTarget.ACCENT -> config.accentColor
                IslandColorTarget.BORDER -> config.borderColor
            }
        IslandColorDialog(
            title = title,
            initial = current,
            allowAuto = target != IslandColorTarget.CUSTOM_TOP && target != IslandColorTarget.CUSTOM_BOTTOM,
            onSelect = { value ->
                commit(
                    when (target) {
                        IslandColorTarget.CUSTOM_TOP -> config.copy(customTop = value)
                        IslandColorTarget.CUSTOM_BOTTOM -> config.copy(customBottom = value)
                        IslandColorTarget.TEXT -> config.copy(textColor = value)
                        IslandColorTarget.ACCENT -> config.copy(accentColor = value)
                        IslandColorTarget.BORDER -> config.copy(borderColor = value)
                    },
                )
                colorTarget = null
            },
            onDismiss = { colorTarget = null },
        )
    }

    if (showPlace) {
        IslandPlaceDialog(
            config = config,
            sampleTitle = sampleTitle,
            sampleArtist = sampleArtist,
            statusBarDp = statusBarDpForPlace,
            initialX = xLocal,
            initialY = yLocal.roundToInt(),
            onSave = { x, y ->
                xLocal = x
                yLocal = y.toFloat()
                xOffsetLocal = 0f
                scope.launch {
                    context.safeDataStoreEdit {
                        it[DynamicIslandXKey] = x
                        it[DynamicIslandYKey] = y
                        it[DynamicIslandXOffsetKey] = 0
                    }
                }
                showPlace = false
            },
            onDismiss = { showPlace = false },
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.dynamic_island)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}


/** A made-up cover for the previews, so the blurred "Liquid" background and the cover slot have something to show. */
private val sampleArtwork: ImageBitmap by lazy {
    val side = 96
    val bitmap = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.shader =
        android.graphics.LinearGradient(
            0f, 0f, side.toFloat(), side.toFloat(),
            intArrayOf(0xFFFF6FD8.toInt(), 0xFF7C4DFF.toInt(), 0xFF22E6FF.toInt()),
            null,
            android.graphics.Shader.TileMode.CLAMP,
        )
    canvas.drawRect(0f, 0f, side.toFloat(), side.toFloat(), paint)
    paint.shader = null
    paint.color = 0x66FFFFFF
    canvas.drawCircle(side * 0.32f, side * 0.36f, side * 0.22f, paint)
    paint.color = 0x33000000
    canvas.drawCircle(side * 0.7f, side * 0.72f, side * 0.3f, paint)
    bitmap.asImageBitmap()
}

internal fun samplePreviewState(
    title: String,
    artist: String,
    expanded: Boolean,
    lyric: String = "",
): DynamicIslandUiState =
    DynamicIslandUiState().apply {
        if (lyric.isNotBlank()) {
            // one sung line, half way: the lyric pill and the open card show it like the real island does
            lyrics = IslandLyrics(IslandLyricsStatus.SYNCED, listOf(IslandLyricLine(0L, 4_000L, lyric, false)))
            lyricIndex = 0
            lyricSweep = 0.45f
        }
        visible = true
        this.expanded = expanded
        this.title = title
        this.artist = artist
        isPlaying = true
        position = 42_000L
        duration = 200_000L
        artwork = sampleArtwork
        accent = Color(0xFFC58BFF)
        coverTop = Color(0xFF4A2D6B)
        coverBottom = Color(0xFF170E28)
    }

@Composable
private fun SectionSpacer() {
    Spacer(Modifier.height(27.dp))
}

@Composable
private fun IslandSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        thumbContent = {
            Icon(
                painter = painterResource(if (checked) R.drawable.check else R.drawable.close),
                contentDescription = null,
                modifier = Modifier.size(SwitchDefaults.IconSize),
            )
        },
    )
}

@Composable
private fun switchItem(
    @DrawableRes icon: Int,
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    description: String? = null,
    enabled: Boolean = true,
): Material3SettingsItem {
    val descriptionContent: (@Composable () -> Unit)? = if (description != null) {
        { Text(description) }
    } else {
        null
    }
    return Material3SettingsItem(
        icon = painterResource(icon),
        title = { Text(title) },
        description = descriptionContent,
        trailingContent = { IslandSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
        enabled = enabled,
        onClick = { onChange(!checked) },
    )
}

@Composable
private fun choiceItem(
    @DrawableRes icon: Int,
    title: String,
    value: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
): Material3SettingsItem =
    Material3SettingsItem(
        icon = painterResource(icon),
        title = { Text(title) },
        description = { Text(value) },
        enabled = enabled,
        onClick = onClick,
    )

/** A colour option: shows the chosen colour as a swatch, or [autoLabel] while it is 0 (automatic). */
@Composable
private fun colorItem(
    title: String,
    color: Int,
    autoLabel: String?,
    onClick: () -> Unit,
    extra: String? = null,
): Material3SettingsItem {
    val label =
        if (color == 0) {
            autoLabel.orEmpty()
        } else {
            "#%06X".format(color and 0xFFFFFF)
        }
    return Material3SettingsItem(
        icon = painterResource(R.drawable.palette),
        title = { Text(title) },
        description = { Text(if (extra != null) "$label · $extra" else label) },
        trailingContent = {
            if (color != 0) {
                Spacer(
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(color))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50)),
                )
            }
        },
        onClick = onClick,
    )
}

/** Pill geometry that sits exactly over the front camera cut-out. */
private class CameraFit(
    val x: Float,
    val y: Int,
    val pillWidth: Int,
    val pillHeight: Int,
)

/**
 * Reads the top display cut-out (camera hole / notch) from the window insets and works out the pill position, width
 * and height that wrap it, so the island hugs the camera on any phone. Null when the phone has no top cut-out.
 */
private fun fitToCamera(
    view: android.view.View,
    config: DynamicIslandConfig,
): CameraFit? {
    val insets = ViewCompat.getRootWindowInsets(view) ?: return null
    val metrics = view.resources.displayMetrics
    val rect =
        insets.displayCutout
            ?.boundingRects
            ?.filter { it.width() > 0 && it.height() > 0 && it.top < metrics.heightPixels / 4 }
            ?.minByOrNull { it.top }
            ?: return null
    val d = metrics.density
    val sw = metrics.widthPixels
    val scale = config.size.scale
    val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top

    val holeW = rect.width() / d
    val holeH = rect.height() / d
    val width = (maxOf(config.pillWidth * scale, holeW + 70f) / scale).roundToInt().coerceIn(DynamicIslandMinPillWidth, DynamicIslandMaxPillWidth)
    val height = ((holeH + 8f) / scale).roundToInt().coerceIn(DynamicIslandMinPillHeight, DynamicIslandMaxPillHeight)
    val pillW = width * scale * d
    val free = sw - pillW
    val left = rect.exactCenterX() - pillW / 2f
    val x = if (free > 0f) (left / (free / 2f) - 1f).coerceIn(-1f, 1f) else 0f
    val pillTop = (rect.top - 4f * d).coerceAtLeast(0f)
    val y = ((pillTop - statusTop) / d).roundToInt().coerceIn(DynamicIslandMinY, DynamicIslandMaxY)
    return CameraFit(x = x, y = y, pillWidth = width, pillHeight = height)
}

@Composable
private fun sliderItem(
    @DrawableRes icon: Int,
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
    steps: Int = 0,
): Material3SettingsItem =
    Material3SettingsItem(
        icon = painterResource(icon),
        title = { Text(title) },
        description = {
            Column {
                Text(valueText)
                Slider(
                    value = value.coerceIn(range.start, range.endInclusive),
                    onValueChange = onChange,
                    onValueChangeFinished = onFinished,
                    valueRange = range,
                    steps = steps.coerceAtLeast(0),
                )
            }
        },
    )

@Composable
private fun <T> IslandEnumDialog(
    title: String,
    current: T,
    values: List<T>,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    description: (@Composable (T) -> String)? = null,
) {
    EnumDialog(
        onDismiss = onDismiss,
        onSelect = {
            onSelect(it)
            onDismiss()
        },
        title = title,
        current = current,
        values = values,
        valueText = label,
        valueDescription = description,
    )
}

// ---------------------------------------------------------------- labels

@Composable
private fun presetLabel(value: DynamicIslandPreset): String =
    stringResource(
        when (value) {
            DynamicIslandPreset.IPHONE -> R.string.dynamic_island_preset_iphone
            DynamicIslandPreset.GLASS -> R.string.dynamic_island_preset_glass
            DynamicIslandPreset.COLOURFUL -> R.string.dynamic_island_preset_colourful
            DynamicIslandPreset.MINIMAL -> R.string.dynamic_island_preset_minimal
            DynamicIslandPreset.LIQUID -> R.string.dynamic_island_preset_liquid
            DynamicIslandPreset.NEON -> R.string.dynamic_island_preset_neon
        },
    )

@Composable
private fun styleLabel(value: DynamicIslandStyle): String =
    stringResource(
        when (value) {
            DynamicIslandStyle.CLASSIC -> R.string.dynamic_island_style_classic
            DynamicIslandStyle.GLASS -> R.string.dynamic_island_style_glass
            DynamicIslandStyle.COVER -> R.string.dynamic_island_style_cover
            DynamicIslandStyle.LIGHT -> R.string.dynamic_island_style_light
            DynamicIslandStyle.LIQUID -> R.string.dynamic_island_style_liquid
            DynamicIslandStyle.CUSTOM -> R.string.dynamic_island_style_custom
        },
    )

@Composable
private fun styleDescription(value: DynamicIslandStyle): String =
    stringResource(
        when (value) {
            DynamicIslandStyle.CLASSIC -> R.string.dynamic_island_style_classic_desc
            DynamicIslandStyle.GLASS -> R.string.dynamic_island_style_glass_desc
            DynamicIslandStyle.COVER -> R.string.dynamic_island_style_cover_desc
            DynamicIslandStyle.LIGHT -> R.string.dynamic_island_style_light_desc
            DynamicIslandStyle.LIQUID -> R.string.dynamic_island_style_liquid_desc
            DynamicIslandStyle.CUSTOM -> R.string.dynamic_island_style_custom_desc
        },
    )

@Composable
private fun glowColorLabel(value: DynamicIslandGlowColor): String =
    stringResource(
        when (value) {
            DynamicIslandGlowColor.COVER -> R.string.dynamic_island_glow_color_cover
            DynamicIslandGlowColor.ACCENT -> R.string.dynamic_island_glow_color_accent
            DynamicIslandGlowColor.WHITE -> R.string.dynamic_island_glow_color_white
        },
    )

@Composable
private fun placementLabel(attached: Boolean): String =
    stringResource(if (attached) R.string.dynamic_island_placement_attached else R.string.dynamic_island_placement_floating)

@Composable
private fun coverShapeLabel(value: DynamicIslandCoverShape): String =
    stringResource(
        when (value) {
            DynamicIslandCoverShape.ROUNDED -> R.string.dynamic_island_cover_rounded
            DynamicIslandCoverShape.CIRCLE -> R.string.dynamic_island_cover_circle
        },
    )

@Composable
private fun compactContentLabel(value: DynamicIslandCompactContent): String =
    stringResource(
        when (value) {
            DynamicIslandCompactContent.COVER_EQUALIZER -> R.string.dynamic_island_content_cover_eq
            DynamicIslandCompactContent.COVER -> R.string.dynamic_island_content_cover
            DynamicIslandCompactContent.EQUALIZER -> R.string.dynamic_island_content_eq
            DynamicIslandCompactContent.COVER_TITLE -> R.string.dynamic_island_content_cover_title
            DynamicIslandCompactContent.COVER_LYRICS -> R.string.dynamic_island_content_cover_lyric
            DynamicIslandCompactContent.LYRICS_ONLY -> R.string.dynamic_island_content_lyric_only
        },
    )

@Composable
private fun eqStyleLabel(value: DynamicIslandEqStyle): String =
    stringResource(
        when (value) {
            DynamicIslandEqStyle.BARS -> R.string.dynamic_island_eq_bars
            DynamicIslandEqStyle.WAVE -> R.string.dynamic_island_eq_wave
            DynamicIslandEqStyle.DOTS -> R.string.dynamic_island_eq_dots
        },
    )

@Composable
private fun eqColorLabel(value: DynamicIslandEqColor): String =
    stringResource(
        when (value) {
            DynamicIslandEqColor.COVER -> R.string.dynamic_island_eq_color_cover
            DynamicIslandEqColor.WHITE -> R.string.dynamic_island_eq_color_white
        },
    )

@Composable
private fun layoutLabel(value: DynamicIslandLayout): String =
    stringResource(
        when (value) {
            DynamicIslandLayout.CARD -> R.string.dynamic_island_layout_card
            DynamicIslandLayout.COMPACT_ROW -> R.string.dynamic_island_layout_row
        },
    )

@Composable
private fun layoutDescription(value: DynamicIslandLayout): String =
    stringResource(
        when (value) {
            DynamicIslandLayout.CARD -> R.string.dynamic_island_layout_card_desc
            DynamicIslandLayout.COMPACT_ROW -> R.string.dynamic_island_layout_row_desc
        },
    )

@Composable
private fun buttonStyleLabel(value: DynamicIslandButtonStyle): String =
    stringResource(
        when (value) {
            DynamicIslandButtonStyle.FILLED -> R.string.dynamic_island_buttons_filled
            DynamicIslandButtonStyle.OUTLINE -> R.string.dynamic_island_buttons_outline
        },
    )

@Composable
private fun tapActionLabel(value: DynamicIslandTapAction): String =
    stringResource(
        when (value) {
            DynamicIslandTapAction.EXPAND -> R.string.dynamic_island_tap_expand
            DynamicIslandTapAction.OPEN_APP -> R.string.dynamic_island_tap_open
            DynamicIslandTapAction.PLAY_PAUSE -> R.string.dynamic_island_tap_play_pause
        },
    )

@Composable
private fun longPressLabel(value: DynamicIslandLongPressAction): String =
    stringResource(
        when (value) {
            DynamicIslandLongPressAction.DRAG -> R.string.dynamic_island_long_press_drag
            DynamicIslandLongPressAction.HIDE -> R.string.dynamic_island_long_press_hide
        },
    )

@Composable
private fun sizeLabel(value: DynamicIslandSize): String =
    stringResource(
        when (value) {
            DynamicIslandSize.SMALL -> R.string.dynamic_island_size_small
            DynamicIslandSize.NORMAL -> R.string.dynamic_island_size_normal
            DynamicIslandSize.LARGE -> R.string.dynamic_island_size_large
        },
    )

// ---------------------------------------------------------------- preview

private val PreviewShape = RoundedCornerShape(28.dp)
private val PreviewWallpaper = listOf(Color(0xFF3A4458), Color(0xFF1B1F29), Color(0xFF101217))
private val CardWallpaper = listOf(Color(0xFF2C3446), Color(0xFF151821))

/**
 * Top: a scaled-down "phone top" with the island where it would appear on screen (tap toggles the
 * expanded card, like the overlay). Below: the expanded card on its own. Both are the real
 * [DynamicIslandPill] reading the same options as the overlay.
 */
@Composable
private fun DynamicIslandPreview(
    state: DynamicIslandUiState,
    cardState: DynamicIslandUiState,
    config: DynamicIslandConfig,
    xFraction: Float,
    xOffsetDp: Float,
    yDp: Float,
    onInteraction: () -> Unit,
) {
    val density = LocalDensity.current
    val screenWidth = LocalWindowInfo.current.containerDpSize.width
    val statusBarDp = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val currentOnInteraction by rememberUpdatedState(onInteraction)
    val previewActions =
        remember(state, cardState) {
            object : DynamicIslandActions by NoOpDynamicIslandActions {
                override fun onTap() {
                    // Same as the overlay: a tap expands the pill; taps on the card do nothing.
                    if (!state.expanded) state.expanded = true
                    currentOnInteraction()
                }

                override fun onTogglePlay() {
                    val playing = !state.isPlaying
                    state.isPlaying = playing
                    cardState.isPlaying = playing
                    currentOnInteraction()
                }

                override fun onPrevious() = onSeek(0L)

                override fun onNext() = onSeek(0L)

                override fun onSeek(positionMs: Long) {
                    state.position = positionMs
                    cardState.position = positionMs
                    currentOnInteraction()
                }

                override fun onSliderDrag(active: Boolean) {
                    currentOnInteraction()
                }
            }
        }
    val cardActions =
        remember(previewActions) {
            object : DynamicIslandActions by previewActions {
                override fun onTap() = Unit
            }
        }
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.dynamic_island_preview_on_screen),
            style = MaterialTheme.typography.labelLarge,
            color = labelColor,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(PreviewShape)
                    .background(Brush.verticalGradient(PreviewWallpaper))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), PreviewShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { state.expanded = !state.expanded },
        ) {
            val ratio =
                if (screenWidth.value > 0f) (maxWidth / screenWidth).coerceIn(0.3f, 1f) else 0.85f
            val pillScale = config.size.scale * ratio

            // Same geometry as the overlay controller, in preview (scaled) units.
            val margin = DynamicIslandDimens.SCREEN_MARGIN.dp * ratio
            val compactWidth = DynamicIslandDimens.compactWidth(config).dp * pillScale
            val cardWidth =
                (config.expandedWidth.dp * pillScale)
                    .coerceAtMost((maxWidth - margin * 2).coerceAtLeast(compactWidth))
            val cardHeight = DynamicIslandDimens.expandedHeight(config).dp * pillScale
            val compactLeft =
                ((maxWidth - compactWidth).coerceAtLeast(0.dp) / 2 * (1f + xFraction) + xOffsetDp.dp * ratio)
                    .coerceIn(0.dp, (maxWidth - compactWidth).coerceAtLeast(0.dp))
            val cardLeft =
                (compactLeft + compactWidth / 2 - cardWidth / 2)
                    .coerceIn(margin, (maxWidth - margin - cardWidth).coerceAtLeast(margin))
            val top = ((statusBarDp + yDp.dp) * ratio).coerceAtLeast(0.dp)
            val previewHeight =
                (top + cardHeight + DynamicIslandDimens.GLOW_PAD.dp * pillScale + 8.dp).coerceAtLeast(120.dp)
            val shiftPx = with(density) { (compactLeft - cardLeft).toPx() }
            SideEffect {
                state.scale = pillScale
                state.expandedWidth = cardWidth.value
                state.compactShiftX = shiftPx
                state.compactShiftY = 0f
            }

            Spacer(Modifier.fillMaxWidth().height(previewHeight))
            // Status bar hint line
            Spacer(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = (statusBarDp * ratio).coerceAtLeast(4.dp))
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.08f)),
            )
            DynamicIslandPill(
                state = state,
                actions = previewActions,
                animateAppearance = false,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(x = cardLeft, y = top),
            )
        }

        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.dynamic_island_preview_expanded),
            style = MaterialTheme.typography.labelLarge,
            color = labelColor,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(PreviewShape)
                    .background(Brush.verticalGradient(CardWallpaper))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), PreviewShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { state.expanded = !state.expanded },
        ) {
            val ratio =
                if (screenWidth.value > 0f) (maxWidth / screenWidth).coerceIn(0.3f, 1f) else 0.85f
            val cardScale = config.size.scale * ratio
            val pad: Dp = maxOf(DynamicIslandDimens.GLOW_PAD, DynamicIslandDimens.compactFlare(config)).dp * cardScale + 4.dp
            val compactWidth = DynamicIslandDimens.compactWidth(config).dp * cardScale
            val cardWidth =
                (config.expandedWidth.dp * cardScale)
                    .coerceAtMost((maxWidth - pad * 2).coerceAtLeast(compactWidth))
            val cardHeight = DynamicIslandDimens.expandedHeight(config).dp * cardScale
            val shiftPx = with(density) { ((cardWidth - compactWidth) / 2).toPx() }
            SideEffect {
                cardState.scale = cardScale
                cardState.expandedWidth = cardWidth.value
                cardState.compactShiftX = shiftPx
                cardState.compactShiftY = 0f
            }

            Spacer(Modifier.fillMaxWidth().height(cardHeight + pad * 2))
            DynamicIslandPill(
                state = cardState,
                actions = cardActions,
                animateAppearance = false,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(x = (maxWidth - cardWidth) / 2, y = pad),
            )
        }
    }
}

/** The island's height slider: the distance from the status bar with 0 in the middle, minus to the left, plus to the right. */
internal object IslandHeightSlider {
    fun toSlider(y: Float): Float = if (y < 0f) y / -DynamicIslandMinY.toFloat() else y / DynamicIslandMaxY.toFloat()

    fun fromSlider(position: Float): Int {
        val p = position.coerceIn(-1f, 1f)
        // close to the middle it snaps to exactly 0
        if (kotlin.math.abs(p) < 0.015f) return 0
        val y = if (p < 0f) p * -DynamicIslandMinY.toFloat() else p * DynamicIslandMaxY.toFloat()
        return y.roundToInt().coerceIn(DynamicIslandMinY, DynamicIslandMaxY)
    }
}
