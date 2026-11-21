/**
 * Auralys - the look of every pop-up dialog (4.3.7)
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Two looks, one component (DefaultDialog / ListDialog in Dialog.kt build on this file, so every screen gets them):
 *  - Kaca (Liquid Glass): an iOS alert. A 340 dp (at most) pane with 30 dp corners, a centred 17 sp semi-bold title, a centred
 *    13 sp message, a grey rounded text field (10 dp, no outline), and the actions as full-width cells divided by hairlines:
 *    two side by side, three or more stacked, the main one bold. It appears the way an iOS alert does: it settles from 1.12 to
 *    1.0 while it fades in, with a soft spring.
 *  - Datar (Editorial): a flat modern dialog. 28 dp corners, a left-aligned bold title, a FILLED text field (surface-2, 12 dp,
 *    the hint always visible, a 1.5 dp ink rim while it has the focus), the actions right-aligned as plain text buttons under one
 *    hairline (no pill, no capsule). It appears from 0.94 with a fade.
 *
 * A dialog lives in its own window and cannot sample the page behind it: no backdrop blur here (see glassOverlay), only an
 * opaque pane. Everything animated runs in graphicsLayer / transition specs: nothing recomposes per frame.
 * The idea of the iOS alert (scale-in from a larger size, hairline-divided cells) is common to every iOS-style UI kit; the
 * numbers and the code are our own.
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.ui.theme.isGlassStyle
import kotlinx.coroutines.launch

/**
 * One action of a dialog. [primary] is the main one (bold); [destructive] draws it in the error colour; [leading] puts it at the
 * far left in Datar (a "reset" next to the cancel and confirm pair). Kaca ignores [leading] and orders three or more actions
 * with the main one on top.
 */
data class DialogAction(
    val label: String,
    val onClick: () -> Unit,
    val primary: Boolean = false,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val leading: Boolean = false,
)

/** The pop-in of a dialog: [modifier] goes on the pane, [requestDismiss] plays the short exit and then calls onDismiss. */
class DialogPop(
    val modifier: Modifier,
    val requestDismiss: () -> Unit,
)

@Composable
internal fun rememberDialogPop(onDismiss: () -> Unit): DialogPop {
    val glass = isGlassStyle
    val ios = LocalIosMotion.current
    val enter = remember { Animatable(0f) }
    val leave = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val latestDismiss by rememberUpdatedState(onDismiss)
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // iOS animations on: a looser spring, so the settle is seen
        val damping = if (glass) (if (ios) 0.68f else 0.8f) else (if (ios) 0.75f else 0.85f)
        enter.animateTo(1f, spring(dampingRatio = damping, stiffness = 520f))
    }
    val from = if (glass) 1.12f else 0.94f
    val requestDismiss: () -> Unit =
        remember {
            {
                if (!leaving) {
                    leaving = true
                    scope.launch {
                        leave.animateTo(1f, tween(110))
                        latestDismiss()
                    }
                }
            }
        }
    val modifier =
        Modifier.graphicsLayer {
            val e = enter.value
            val l = leave.value
            val s = (from + (1f - from) * e) * (1f - 0.04f * l)
            scaleX = s
            scaleY = s
            alpha = (e * 1.8f).coerceIn(0f, 1f) * (1f - l)
        }
    return DialogPop(modifier, requestDismiss)
}

/** The pane of a dialog (shape, width, material): Kaca 30 dp / 340 dp at most, Datar 28 dp / 400 dp at most. */
@Composable
internal fun DialogPane(
    pop: DialogPop,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = isGlassStyle
    val shape = RoundedCornerShape(if (glass) 30.dp else 28.dp)
    Surface(
        modifier =
            Modifier
                .padding(28.dp)
                .then(pop.modifier)
                .widthIn(max = if (glass) 340.dp else 400.dp)
                .fillMaxWidth()
                .glassOverlay(shape, 32.dp),
        shape = shape,
        color = Color.Transparent,
        tonalElevation = 0.dp,
    ) {
        Column(content = content)
    }
}

@Composable
internal fun dialogTitleStyle(glass: Boolean) =
    if (glass) {
        MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    } else {
        MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Start)
    }

@Composable
internal fun dialogBodyStyle(glass: Boolean) =
    if (glass) {
        MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
    } else {
        MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Start)
    }

/** A title for the dialogs that only have a list (ListDialog / EnumDialog). */
@Composable
internal fun DialogHeaderTitle(text: String) {
    val glass = isGlassStyle
    Text(
        text = text,
        style = dialogTitleStyle(glass),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 10.dp),
    )
}

/** A 0.5 dp rule, horizontal (full width, [inset] from both sides) or vertical (full height). */
@Composable
internal fun DialogHairline(
    vertical: Boolean = false,
    inset: Dp = 0.dp,
) {
    val colour = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    if (vertical) {
        Box(Modifier.width(0.5.dp).fillMaxHeight().background(colour))
    } else {
        Box(Modifier.fillMaxWidth().padding(horizontal = inset).height(0.5.dp).background(colour))
    }
}

/** The actions of a dialog under a hairline: iOS cells in Kaca, right-aligned plain text buttons in Datar. */
@Composable
internal fun DialogActionBar(actions: List<DialogAction>) {
    if (actions.isEmpty()) return
    val glass = isGlassStyle
    DialogHairline()
    if (glass) {
        if (actions.size >= 3) {
            // stacked, the main action on top (the sort is stable: the others keep their order)
            val ordered = actions.sortedByDescending { it.primary }
            Column(Modifier.fillMaxWidth()) {
                ordered.forEachIndexed { index, action ->
                    if (index > 0) DialogHairline()
                    DialogCell(action, Modifier.fillMaxWidth())
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().height(50.dp)) {
                actions.forEachIndexed { index, action ->
                    if (index > 0) DialogHairline(vertical = true)
                    DialogCell(action, Modifier.weight(1f))
                }
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.filter { it.leading }.forEach { DialogTextAction(it) }
            Spacer(Modifier.weight(1f))
            actions.filter { !it.leading }.forEach { DialogTextAction(it) }
        }
    }
}

@Composable
private fun actionColour(action: DialogAction): Color {
    val scheme = MaterialTheme.colorScheme
    val base = if (action.destructive) scheme.error else scheme.primary
    return if (action.enabled) base else base.copy(alpha = 0.38f)
}

/** One iOS alert cell: 17 sp, the main one bold. */
@Composable
private fun DialogCell(
    action: DialogAction,
    modifier: Modifier,
) {
    Box(
        modifier =
            modifier
                .heightIn(min = 50.dp)
                .clickable(enabled = action.enabled, onClick = action.onClick)
                .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = action.label,
            fontSize = 17.sp,
            fontWeight = if (action.primary) FontWeight.SemiBold else FontWeight.Normal,
            color = actionColour(action),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** One Datar action: plain text, the main one bold in the ink colour, a soft rounded press area (never a capsule). */
@Composable
private fun DialogTextAction(action: DialogAction) {
    val scheme = MaterialTheme.colorScheme
    val colour =
        when {
            action.destructive -> if (action.enabled) scheme.error else scheme.error.copy(alpha = 0.38f)
            action.primary -> if (action.enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.38f)
            else -> if (action.enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.38f)
        }
    Box(
        modifier =
            Modifier
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = action.enabled, onClick = action.onClick)
                .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = action.label,
            fontSize = 15.sp,
            fontWeight = if (action.primary) FontWeight.Bold else FontWeight.Medium,
            color = colour,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The buttons a call site brings itself (DefaultDialog's `buttons` lambda): under the hairline, centred in Kaca, at the end in Datar. */
@Composable
internal fun DialogButtonsRow(buttons: @Composable RowScope.() -> Unit) {
    val glass = isGlassStyle
    val scheme = MaterialTheme.colorScheme
    DialogHairline()
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = if (glass) Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally) else Arrangement.End,
    ) {
        CompositionLocalProvider(LocalContentColor provides scheme.primary) {
            ProvideTextStyle(
                value =
                    MaterialTheme.typography.labelLarge.copy(
                        fontSize = if (glass) 17.sp else 15.sp,
                        fontWeight = if (glass) FontWeight.SemiBold else FontWeight.Bold,
                    ),
            ) {
                buttons()
            }
        }
    }
}

/**
 * The text field of the dialogs. Kaca: a grey rounded field (10 dp, 8 percent ink, no outline, 44 dp); Datar: a filled surface-2
 * field (12 dp, 52 dp) with a 1.5 dp ink rim while it has the focus. The hint ([placeholder]) is always shown while the field is
 * empty (the old underline field showed nothing).
 */
@Composable
internal fun DialogTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    maxLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val glass = isGlassStyle
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(if (glass) 10.dp else 12.dp)
    val fill = if (glass) scheme.onSurface.copy(alpha = 0.08f) else scheme.surfaceContainerHigh
    val rim by animateColorAsState(
        targetValue = if (!glass && focused) scheme.onSurface else Color.Transparent,
        animationSpec = tween(140),
        label = "dialogFieldRim",
    )
    val textStyle =
        MaterialTheme.typography.bodyLarge.copy(
            color = scheme.onSurface,
            fontSize = if (glass) 17.sp else 16.sp,
            textAlign = TextAlign.Start,
        )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier
                .clip(shape)
                .background(fill)
                .then(if (!glass) Modifier.border(1.5.dp, rim, shape) else Modifier),
        textStyle = textStyle,
        cursorBrush = SolidColor(scheme.onSurface),
        singleLine = singleLine,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        interactionSource = interaction,
        decorationBox = { inner ->
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (glass) 44.dp else 52.dp)
                        .padding(horizontal = 14.dp, vertical = if (singleLine) 0.dp else 12.dp),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (value.text.isEmpty() && placeholder != null) {
                    CompositionLocalProvider(LocalContentColor provides scheme.onSurfaceVariant) {
                        ProvideTextStyle(textStyle.copy(color = scheme.onSurfaceVariant)) {
                            placeholder()
                        }
                    }
                }
                inner()
            }
        },
    )
}
