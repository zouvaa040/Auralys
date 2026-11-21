/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.metrolist.music.R
import com.metrolist.music.ui.component.SystemIcons
import com.metrolist.music.ui.theme.MetrolistTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.roundToInt

/** Accent colours offered besides "from the cover". */
private val TINT_CHOICES = listOf(
    0xFF7C4DFF, 0xFF2979FF, 0xFF00BFA5, 0xFF2ECC71,
    0xFFFFB300, 0xFFFF6E40, 0xFFFF4081, 0xFFE53935,
).map { it.toInt() }

/** Settings of one widget: opened when it is added and again from the widget's reconfigure option. */
@AndroidEntryPoint
class AuralysWidgetConfigActivity : ComponentActivity() {
    @Inject
    lateinit var manager: AuralysWidgetManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId =
            intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // cancelled unless the user presses Done, so a backed-out widget is not added
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        enableEdgeToEdge()
        val initial = AuralysWidgetConfigStore.load(this, widgetId)
        setContent {
            val family = remember { SystemIcons.family(this@AuralysWidgetConfigActivity) }
            MetrolistTheme(iconFamily = family) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ConfigScreen(
                        initial = initial,
                        actualSize = manager.currentSize(widgetId),
                        preview = { config, widthDp, heightDp -> manager.buildPreview(config, widthDp, heightDp) },
                        onDone = { config ->
                            AuralysWidgetConfigStore.save(this, widgetId, config)
                            manager.renderAll(intArrayOf(widgetId))
                            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                            finish()
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfigScreen(
    initial: AuralysWidgetConfig,
    actualSize: Pair<Int, Int>,
    preview: (AuralysWidgetConfig, Int, Int) -> android.widget.RemoteViews,
    onDone: (AuralysWidgetConfig) -> Unit,
) {
    var config by remember { mutableStateOf(initial) }
    // the preview shows the size this widget really has on the home screen
    val shownWidth = actualSize.first
    val shownHeight = actualSize.second
    // the preview never grows wider than the screen allows; a narrower screen scales the whole shape down
    val room = (LocalConfiguration.current.screenWidthDp - 72).coerceAtLeast(120)
    val fit = (room.toFloat() / shownWidth).coerceAtMost(1f)
    val previewWidthDp = (shownWidth * fit).roundToInt()
    val previewHeightDp = (shownHeight * fit).roundToInt()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.aw_config_title), style = MaterialTheme.typography.headlineSmall)

        // a colourful backdrop so a see-through widget looks like it does on a wallpaper
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(Color(0xFFB8913F), Color(0xFF6B5A33), Color(0xFFD9C28A))))
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier
                    .width(previewWidthDp.dp)
                    .height(previewHeightDp.dp),
                factory = { FrameLayout(it) },
                update = { frame ->
                    frame.removeAllViews()
                    try {
                        frame.addView(preview(config, previewWidthDp, previewHeightDp).apply(frame.context, frame))
                    } catch (e: Exception) {
                        // an unreadable preview must not close the settings
                    }
                },
            )
        }

        Text(
            stringResource(R.string.aw_config_size_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Section(R.string.aw_config_style) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(config.style == WidgetStyle.GLASS, R.string.aw_style_glass) { config = config.copy(style = WidgetStyle.GLASS) }
                Choice(config.style == WidgetStyle.CLEAR, R.string.aw_style_clear) { config = config.copy(style = WidgetStyle.CLEAR) }
                Choice(config.style == WidgetStyle.DARK, R.string.aw_style_dark) { config = config.copy(style = WidgetStyle.DARK) }
                Choice(config.style == WidgetStyle.LIGHT, R.string.aw_style_light) { config = config.copy(style = WidgetStyle.LIGHT) }
                Choice(config.style == WidgetStyle.COVER, R.string.aw_style_cover) { config = config.copy(style = WidgetStyle.COVER) }
            }
        }

        // only the glass and cover-colour styles take an accent colour
        if (config.style == WidgetStyle.GLASS || config.style == WidgetStyle.COVER) {
            Section(R.string.aw_config_tint) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Choice(config.tint == 0, R.string.aw_tint_auto) { config = config.copy(tint = 0) }
                    TINT_CHOICES.forEach { color ->
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(color))
                                .border(
                                    border = if (config.tint == color) {
                                        BorderStroke(3.dp, MaterialTheme.colorScheme.onBackground)
                                    } else {
                                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                    },
                                    shape = CircleShape,
                                )
                                .clickable { config = config.copy(tint = color) },
                        )
                    }
                }
            }
        }

        // the glass style is always solid (see LiquidGlass); for the others opacity is how see-through / frosted it is
        if (config.style != WidgetStyle.GLASS) {
            Text(stringResource(R.string.aw_config_opacity, config.opacity), style = MaterialTheme.typography.titleSmall)
            Slider(
                value = config.opacity.toFloat(),
                onValueChange = { config = config.copy(opacity = it.roundToInt()) },
                valueRange = 30f..100f,
            )
        }

        Text(stringResource(R.string.aw_config_shine, config.shine), style = MaterialTheme.typography.titleSmall)
        Slider(
            value = config.shine.toFloat(),
            onValueChange = { config = config.copy(shine = it.roundToInt()) },
            valueRange = 0f..100f,
        )

        Text(stringResource(R.string.aw_config_edge, config.edge), style = MaterialTheme.typography.titleSmall)
        Slider(
            value = config.edge.toFloat(),
            onValueChange = { config = config.copy(edge = it.roundToInt()) },
            valueRange = 0f..100f,
        )

        Text(stringResource(R.string.aw_config_corner, config.corner), style = MaterialTheme.typography.titleSmall)
        Slider(
            value = config.corner.toFloat(),
            onValueChange = { config = config.copy(corner = it.roundToInt()) },
            valueRange = 0f..100f,
        )

        // the frosted cover only exists in the plain glass style
        if (config.style == WidgetStyle.GLASS && config.tint == 0) {
            Text(stringResource(R.string.aw_config_frost, config.frost), style = MaterialTheme.typography.titleSmall)
            Slider(
                value = config.frost.toFloat(),
                onValueChange = { config = config.copy(frost = it.roundToInt()) },
                valueRange = 0f..100f,
            )
        }

        Section(R.string.aw_config_cover_shape) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(config.coverShape == WidgetCoverShape.ROUNDED, R.string.aw_shape_rounded) { config = config.copy(coverShape = WidgetCoverShape.ROUNDED) }
                Choice(config.coverShape == WidgetCoverShape.CIRCLE, R.string.aw_shape_circle) { config = config.copy(coverShape = WidgetCoverShape.CIRCLE) }
                Choice(config.coverShape == WidgetCoverShape.SQUARE, R.string.aw_shape_square) { config = config.copy(coverShape = WidgetCoverShape.SQUARE) }
            }
        }

        Section(R.string.aw_config_text_size) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(config.textSize == WidgetTextSize.SMALL, R.string.aw_size_small) { config = config.copy(textSize = WidgetTextSize.SMALL) }
                Choice(config.textSize == WidgetTextSize.MEDIUM, R.string.aw_size_medium) { config = config.copy(textSize = WidgetTextSize.MEDIUM) }
                Choice(config.textSize == WidgetTextSize.LARGE, R.string.aw_size_large) { config = config.copy(textSize = WidgetTextSize.LARGE) }
            }
        }

        Section(R.string.aw_config_play_button) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(config.playButton == WidgetPlayButton.FILLED, R.string.aw_play_filled) { config = config.copy(playButton = WidgetPlayButton.FILLED) }
                Choice(config.playButton == WidgetPlayButton.PLAIN, R.string.aw_play_plain) { config = config.copy(playButton = WidgetPlayButton.PLAIN) }
            }
        }

        SwitchRow(stringResource(R.string.aw_config_show_cover), config.showCover) { config = config.copy(showCover = it) }
        SwitchRow(stringResource(R.string.aw_config_show_progress), config.showProgress) { config = config.copy(showProgress = it) }
        if (config.showProgress) {
            SwitchRow(stringResource(R.string.aw_config_show_time), config.showTime) { config = config.copy(showTime = it) }
        }
        SwitchRow(stringResource(R.string.aw_config_show_like), config.showLike) { config = config.copy(showLike = it) }
        SwitchRow(stringResource(R.string.aw_config_show_skip), config.showSkip) { config = config.copy(showSkip = it) }
        SwitchRow(stringResource(R.string.auralys434_widget_show_streak), config.showStreak) { config = config.copy(showStreak = it) }

        Button(onClick = { onDone(config) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.aw_config_done))
        }
    }
}

@Composable
private fun Section(title: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun Choice(selected: Boolean, label: Int, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(stringResource(label)) })
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
