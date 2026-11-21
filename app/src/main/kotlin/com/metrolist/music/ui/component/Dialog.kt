/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Pop-up dialogs. The look (iOS alert in Kaca, flat modern in Datar) lives in DialogStyle.kt; this file keeps the behaviour and
 * the public signatures every screen calls.
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.metrolist.music.LocalNavController
import com.metrolist.music.R
import com.metrolist.music.ui.screens.settings.AccountSettings
import com.metrolist.music.ui.theme.isGlassStyle
import kotlinx.coroutines.delay

/**
 * The base of the pop-ups. [buttons] is the call site's own row of buttons; [actions] (new) is the list a dialog hands over to get
 * the full iOS cells (Kaca) or the plain text actions (Datar): when both are given, [actions] wins.
 */
@Composable
fun DefaultDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    buttons: (@Composable RowScope.() -> Unit)? = null,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    actions: List<DialogAction>? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = isGlassStyle
    val scheme = MaterialTheme.colorScheme
    val pop = rememberDialogPop(onDismiss)
    val hasActions = (actions != null && actions.isNotEmpty()) || buttons != null

    Dialog(
        onDismissRequest = pop.requestDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DialogPane(pop) {
            Column(
                horizontalAlignment = horizontalAlignment,
                modifier =
                    modifier
                        .fillMaxWidth()
                        .padding(
                            start = if (glass) 20.dp else 24.dp,
                            end = if (glass) 20.dp else 24.dp,
                            top = 24.dp,
                            bottom = if (hasActions) 20.dp else 24.dp,
                        ),
            ) {
                if (icon != null) {
                    if (glass) {
                        // iOS has no icon in an alert; a small quiet one above the title keeps the call sites' icons
                        CompositionLocalProvider(LocalContentColor provides scheme.onSurfaceVariant) {
                            Box(Modifier.align(Alignment.CenterHorizontally)) {
                                icon()
                            }
                        }
                    } else {
                        // Datar: the icon in a soft rounded square, at the start
                        CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier =
                                    Modifier
                                        .align(Alignment.Start)
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(scheme.surfaceContainerHigh),
                            ) {
                                icon()
                            }
                        }
                    }

                    Spacer(Modifier.height(if (glass) 12.dp else 16.dp))
                }
                if (title != null) {
                    CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
                        ProvideTextStyle(dialogTitleStyle(glass)) {
                            Box(
                                // Kaca: the title is centred; Datar: it starts at the left edge
                                Modifier.align(if (glass) Alignment.CenterHorizontally else Alignment.Start),
                            ) {
                                title()
                            }
                        }
                    }

                    Spacer(Modifier.height(if (glass) 8.dp else 12.dp))
                }

                ProvideTextStyle(dialogBodyStyle(glass)) {
                    content()
                }
            }

            if (actions != null && actions.isNotEmpty()) {
                DialogActionBar(actions)
            } else if (buttons != null) {
                DialogButtonsRow(buttons)
            }
        }
    }
}

@Composable
fun AccountSettingsDialog(
    onDismiss: () -> Unit,
    latestVersionName: String,
) {
    val navController = LocalNavController.current
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = true,
            ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onDismiss() }
                        )
                    },
            contentAlignment = Alignment.TopCenter,
        ) {
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 72.dp, start = 16.dp, end = 16.dp)
                        .glassOverlay(glassShape(28.dp, 28.dp), 32.dp),
                shape = glassShape(28.dp, 28.dp),
                color = Color.Transparent,
                tonalElevation = 0.dp,
            ) {
                AccountSettings(
                    navController = navController,
                    onClose = onDismiss,
                    latestVersionName = latestVersionName,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionPromptDialog(
    title: String? = null,
    titleBar: @Composable (RowScope.() -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onReset: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val resetLabel = stringResource(R.string.reset)
    val cancelLabel = stringResource(android.R.string.cancel)
    val okLabel = stringResource(android.R.string.ok)
    // the same buttons as before, as actions: reset (at the far left in Datar), cancel, then the main one
    val actions =
        buildList<DialogAction> {
            if (onReset != null) add(DialogAction(label = resetLabel, onClick = { onReset() }, leading = true))
            if (onCancel != null) add(DialogAction(label = cancelLabel, onClick = { onCancel() }))
            add(DialogAction(label = okLabel, onClick = { onConfirm() }, primary = true))
        }
    DefaultDialog(
        onDismiss = onDismiss,
        title =
            if (titleBar != null) {
                { Row { titleBar() } }
            } else if (title != null) {
                {
                    Text(
                        text = title,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                }
            } else {
                null
            },
        actions = actions,
    ) {
        content()
    }
}

/**
 * A dialog that only holds a list. [title] (new, optional) shows a heading above it: EnumDialog always had a title argument that
 * was never drawn.
 */
@Composable
fun ListDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: LazyListScope.() -> Unit,
) {
    val pop = rememberDialogPop(onDismiss)
    Dialog(
        onDismissRequest = pop.requestDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DialogPane(pop) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    modifier
                        .padding(top = if (title != null) 0.dp else 8.dp, bottom = 8.dp)
                        .imePadding(),
            ) {
                if (title != null) {
                    DialogHeaderTitle(title)
                }
                LazyColumn(modifier = Modifier.weight(1f, fill = false), content = content)
            }
        }
    }
}

@Composable
fun InfoLabel(text: String) =
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 8.dp),
    ) {
        Icon(
            painter = painterResource(id = R.drawable.info),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(4.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }

@Composable
fun TextFieldDialog(
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    initialTextFieldValue: TextFieldValue = TextFieldValue(),
    placeholder: @Composable (() -> Unit)? = null,
    singleLine: Boolean = true,
    autoFocus: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 10,
    isInputValid: (String) -> Boolean = { it.isNotEmpty() },
    keyboardType: KeyboardType = KeyboardType.Text,
    onDone: (String) -> Unit = {},
    // new multi-field support
    textFields: List<Pair<String, TextFieldValue>>? = null,
    onTextFieldsChange: ((Int, TextFieldValue) -> Unit)? = null,
    onDoneMultiple: ((List<String>) -> Unit)? = null,
    onDismiss: () -> Unit,
    autoDismiss: Boolean = true,
    extraContent: (@Composable () -> Unit)? = null,
) {
    val legacyFieldState = remember { mutableStateOf(initialTextFieldValue) }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (autoFocus) {
            delay(300)
            focusRequester.requestFocus()
        }
    }

    val isValid =
        textFields?.all { isInputValid(it.second.text) }
            ?: isInputValid(legacyFieldState.value.text)
    val cancelLabel = stringResource(android.R.string.cancel)
    val okLabel = stringResource(android.R.string.ok)

    DefaultDialog(
        onDismiss = onDismiss,
        modifier = modifier,
        icon = icon,
        title = title,
        actions =
            listOf(
                DialogAction(label = cancelLabel, onClick = onDismiss),
                DialogAction(
                    label = okLabel,
                    primary = true,
                    enabled = isValid,
                    onClick = {
                        if (autoDismiss) onDismiss()
                        if (textFields != null && onDoneMultiple != null) {
                            onDoneMultiple(textFields.map { it.second.text })
                        } else {
                            onDone(legacyFieldState.value.text)
                        }
                    },
                ),
            ),
    ) {
        Column(
            modifier = Modifier.weight(weight = 1f, fill = false),
        ) {
            if (textFields != null) {
                textFields.forEachIndexed { index, (label, value) ->
                    DialogTextField(
                        value = value,
                        onValueChange = { onTextFieldsChange?.invoke(index, it) },
                        placeholder = { Text(label) },
                        singleLine = singleLine,
                        maxLines = maxLines,
                        keyboardOptions =
                            KeyboardOptions(
                                imeAction = if (singleLine) ImeAction.Done else ImeAction.None,
                                keyboardType = keyboardType,
                            ),
                        keyboardActions =
                            KeyboardActions(
                                onDone = {
                                    if (onDoneMultiple != null) {
                                        onDoneMultiple(textFields.map { it.second.text })
                                        if (autoDismiss) onDismiss()
                                    }
                                },
                            ),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = if (index < textFields.size - 1) 12.dp else 0.dp)
                                .then(if (index == 0) Modifier.focusRequester(focusRequester) else Modifier),
                    )
                }
            } else {
                // Wrap in Box with pointerInput to prevent double-click crashes in TextClassifier
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onDoubleTap = { /* Consume double-tap to prevent TextClassifier crash */ },
                                )
                            },
                ) {
                    DialogTextField(
                        value = legacyFieldState.value,
                        onValueChange = { legacyFieldState.value = it },
                        placeholder = placeholder,
                        singleLine = singleLine,
                        maxLines = maxLines,
                        keyboardOptions =
                            KeyboardOptions(
                                imeAction = if (singleLine) ImeAction.Done else ImeAction.None,
                                keyboardType = keyboardType,
                            ),
                        keyboardActions =
                            KeyboardActions(
                                onDone = {
                                    onDone(legacyFieldState.value.text)
                                    if (autoDismiss) onDismiss()
                                },
                            ),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                    )
                }
            }

            extraContent?.invoke()
        }
    }
}
