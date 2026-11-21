package com.metrolist.music.ui.screens.equalizer

import com.metrolist.music.ui.component.glassOverlay
import com.metrolist.music.ui.component.glassBlur
import android.annotation.SuppressLint
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextField
import androidx.compose.runtime.saveable.rememberSaveable
import com.metrolist.music.eq.data.GraphicEq
import com.metrolist.music.ui.component.ChipsRow
import com.metrolist.music.ui.component.Switch
import com.metrolist.music.ui.component.glass
import com.metrolist.music.ui.component.glassShape
import java.util.Locale
import com.metrolist.music.ui.component.Button
import com.metrolist.music.ui.component.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import com.metrolist.music.ui.component.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.metrolist.music.ui.component.TopAppBar
import com.metrolist.music.ui.theme.pageBackgroundColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.metrolist.music.ui.component.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalNavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.eq.data.SavedEQProfile
import timber.log.Timber

/**
 * EQ Screen - Manage and select EQ profiles
 */
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun EqScreen(
    viewModel: EQViewModel = hiltViewModel(),
) {
    val navController = LocalNavController.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showError by remember { mutableStateOf<String?>(null) }
    var showAddMenu by remember { mutableStateOf(false) }

    // File picker for custom EQ import
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val contentResolver = context.contentResolver

                // Extract file name from URI
                var fileName = "custom_eq.txt"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (displayNameIndex >= 0) {
                            val name = cursor.getString(displayNameIndex)
                            if (!name.isNullOrBlank()) {
                                fileName = name
                            }
                        }
                    }
                }

                val inputStream = contentResolver.openInputStream(uri)

                if (inputStream != null) {
                    viewModel.importCustomProfile(
                        fileName = fileName,
                        inputStream = inputStream,
                        onSuccess = {
                            Timber.d("Custom EQ profile imported successfully: $fileName")
                        },
                        onError = { error ->
                            Timber.d("Error: Unable to import Custom EQ profile: $fileName")
                            showError = context.getString(R.string.import_error_title) + ": " + error.message
                        })
                } else {
                    showError = context.getString(R.string.error_file_read)
                }
            } catch (e: Exception) {
                showError = context.getString(R.string.error_file_open, e.message)
            }
        }
    }

    val activeProfile = state.profiles.find { it.id == state.activeProfileId }

    EqScreenContent(
        state = state,
        activeProfile = activeProfile,
        actions =
            EqActions(
                onToggle = viewModel::setEnabled,
                onPreset = viewModel::applyPreset,
                onMyPreset = viewModel::applyMyPreset,
                onGroup = viewModel::setGroup,
                onBand = viewModel::setBand,
                onReset = viewModel::resetGains,
                onSavePreset = viewModel::saveMyPreset,
                onDeleteProfile = viewModel::deleteProfile,
                onProfileSelected = viewModel::selectProfile,
            ),
        onNavigateBack = { navController.navigateUp() },
        showAddMenu = showAddMenu,
        onAddClicked = { showAddMenu = true },
        onAddMenuDismissed = { showAddMenu = false },
        onWizardClicked = {
            showAddMenu = false
            navController.navigate("eq_wizard")
        },
        onImportClicked = {
            showAddMenu = false
            filePickerLauncher.launch("text/plain")
        },
    )

    // Error dialog
    if (showError != null) {
        AlertDialog(modifier = androidx.compose.ui.Modifier.glassOverlay(com.metrolist.music.ui.component.glassShape(32.dp, 16.dp), 32.dp), containerColor = androidx.compose.ui.graphics.Color.Transparent, shape = com.metrolist.music.ui.component.glassShape(32.dp, 16.dp),
tonalElevation = 0.dp,

            onDismissRequest = { showError = null },
            title = {
                Text(stringResource(R.string.import_error_title))
            },
            text = {
                Text(showError ?: "")
            },
            confirmButton = {
                TextButton(onClick = { showError = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }

    // Error dialog for apply failure
    if (state.error != null) {
        AlertDialog(modifier = androidx.compose.ui.Modifier.glassOverlay(com.metrolist.music.ui.component.glassShape(32.dp, 16.dp), 32.dp), containerColor = androidx.compose.ui.graphics.Color.Transparent, shape = com.metrolist.music.ui.component.glassShape(32.dp, 16.dp),
tonalElevation = 0.dp,

            onDismissRequest = { viewModel.clearError() },
            title = {
                Text(stringResource(R.string.error_title))
            },
            text = {
                Text(stringResource(R.string.error_eq_apply_failed, state.error ?: ""))
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}

/** What the screen can do; each one goes straight to the view model. */
private class EqActions(
    val onToggle: (Boolean) -> Unit,
    val onPreset: (GraphicEq.Preset) -> Unit,
    val onMyPreset: (SavedEQProfile) -> Unit,
    val onGroup: (Int, Double) -> Unit,
    val onBand: (Int, Double) -> Unit,
    val onReset: () -> Unit,
    val onSavePreset: (String) -> Unit,
    val onDeleteProfile: (String) -> Unit,
    val onProfileSelected: (String?) -> Unit,
)

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EqScreenContent(
    state: EQState,
    activeProfile: SavedEQProfile?,
    actions: EqActions,
    onNavigateBack: () -> Unit,
    showAddMenu: Boolean,
    onAddClicked: () -> Unit,
    onAddMenuDismissed: () -> Unit,
    onWizardClicked: () -> Unit,
    onImportClicked: () -> Unit,
) {
    val gains = state.graphicGains
    val graphicActive = state.graphicActive
    val presetMatch = if (graphicActive) GraphicEq.presetFor(gains) else null
    val myMatch =
        if (graphicActive && presetMatch == null) {
            state.myPresets.firstOrNull { p -> GraphicEq.gainsOf(p)?.let { GraphicEq.sameGains(it, gains) } == true }
        } else {
            null
        }
    val selectedKey = presetMatch?.let { "p:${it.id}" } ?: myMatch?.let { "m:${it.id}" } ?: ""
    val headroom = remember(gains) { GraphicEq.autoPreamp(gains) }

    var showBands by rememberSaveable { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var presetNameText by remember { mutableStateOf("") }
    var deletingPreset by remember { mutableStateOf<SavedEQProfile?>(null) }

    val status =
        when {
            !state.enabled -> stringResource(R.string.eq_status_off)
            graphicActive ->
                presetMatch?.let { stringResource(R.string.eq_status_preset, presetName(it.id)) }
                    ?: myMatch?.let { stringResource(R.string.eq_status_preset, it.name) }
                    ?: stringResource(R.string.eq_status_custom)
            else -> stringResource(R.string.eq_status_profile, activeProfile?.deviceModel ?: "")
        }
    val chips =
        GraphicEq.PRESETS.map { "p:${it.id}" to presetName(it.id) } + state.myPresets.map { "m:${it.id}" to it.name }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.equalizer_header)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = onAddClicked) {
                            Icon(
                                painter = painterResource(R.drawable.add),
                                contentDescription = stringResource(R.string.import_profile)
                            )
                        }
                        DropdownMenu(
                            expanded = showAddMenu,
                            onDismissRequest = onAddMenuDismissed
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.eq_wizard)) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.discover_tune),
                                        contentDescription = null
                                    )
                                },
                                onClick = onWizardClicked
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_from_file)) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.upload),
                                        contentDescription = null
                                    )
                                },
                                onClick = onImportClicked
                            )
                        }
                    }
                }
            )
        },
        containerColor = pageBackgroundColor(),
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = LocalPlayerAwareWindowInsets.current
                .asPaddingValues().calculateBottomPadding() + 16.dp)
        ) {
            // On / off, and what is playing now
            item(key = "switch") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .glass(glassShape(24.dp, 16.dp))
                        .clickable { actions.onToggle(!state.enabled) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.eq_use), style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.enabled, onCheckedChange = actions.onToggle)
                }
            }

            // The curve you hear, on a fixed scale so it does not jump while a slider moves
            item(key = "graph") {
                EqFrequencyResponseGraph(
                    bands = if (graphicActive) GraphicEq.bands(gains) else activeProfile?.bands ?: emptyList(),
                    preamp = if (graphicActive) headroom else activeProfile?.preamp ?: 0.0,
                    fixedHalfRangeDb = if (graphicActive || !state.enabled) 12.5 else null,
                )
            }

            item(key = "presets-title") { SectionTitle(stringResource(R.string.eq_section_presets)) }
            item(key = "presets") {
                Column {
                    ChipsRow(
                        chips = chips,
                        currentValue = selectedKey,
                        onValueUpdate = { key ->
                            if (key.startsWith("p:")) {
                                GraphicEq.PRESETS.firstOrNull { "p:${it.id}" == key }?.let(actions.onPreset)
                            } else {
                                state.myPresets.firstOrNull { "m:${it.id}" == key }?.let(actions.onMyPreset)
                            }
                        },
                    )
                    if (myMatch != null) {
                        TextButton(
                            onClick = { deletingPreset = myMatch },
                            modifier = Modifier.padding(start = 12.dp),
                        ) {
                            Text(stringResource(R.string.eq_delete_preset), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            item(key = "tone-title") { SectionTitle(stringResource(R.string.eq_section_tone)) }
            item(key = "tone") {
                Column {
                    EqToneSliders(gains = gains, onGroup = actions.onGroup)
                    if (graphicActive && headroom <= -0.5) {
                        Text(
                            text = stringResource(R.string.eq_headroom, String.format(Locale.US, "%.1f", -headroom)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            // The ten sliders behind a button: the easy view stays short
            item(key = "fine-title") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 12.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.eq_fine_tune),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showBands = !showBands }) {
                        Text(stringResource(if (showBands) R.string.eq_hide else R.string.eq_show))
                    }
                }
            }
            if (showBands) {
                item(key = "bands") { EqBandSliders(gains = gains, onBand = actions.onBand) }
            }

            item(key = "buttons") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(onClick = actions.onReset, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.eq_reset))
                    }
                    Button(
                        onClick = {
                            presetNameText = ""
                            showSave = true
                        },
                        modifier = Modifier.weight(2f),
                    ) {
                        Text(stringResource(R.string.eq_save_preset))
                    }
                }
            }

            // Profiles from a file or from AutoEQ (headphone measurements)
            item(key = "profiles-title") { SectionTitle(stringResource(R.string.eq_section_profiles)) }
            val customProfiles = state.profiles.filter { it.isCustom }
            if (customProfiles.isNotEmpty()) {
                items(customProfiles, key = { it.id }) { profile ->
                    EQProfileItem(
                        profile = profile,
                        isSelected = state.activeProfileId == profile.id,
                        onSelected = { actions.onProfileSelected(profile.id) },
                        onDelete = { actions.onDeleteProfile(profile.id) }
                    )
                }
            } else {
                item(key = "profiles-empty") {
                    Text(
                        text = stringResource(R.string.eq_profiles_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }

    if (showSave) {
        AlertDialog(modifier = androidx.compose.ui.Modifier.glassOverlay(glassShape(32.dp, 16.dp), 32.dp), containerColor = androidx.compose.ui.graphics.Color.Transparent, shape = glassShape(32.dp, 16.dp),
            tonalElevation = 0.dp,
            onDismissRequest = { showSave = false },
            title = { Text(stringResource(R.string.eq_save_preset)) },
            text = {
                TextField(
                    value = presetNameText,
                    onValueChange = { presetNameText = it.take(24) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.eq_preset_name)) },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = presetNameText.isNotBlank(),
                    onClick = {
                        actions.onSavePreset(presetNameText)
                        showSave = false
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSave = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    deletingPreset?.let { preset ->
        AlertDialog(modifier = androidx.compose.ui.Modifier.glassOverlay(glassShape(32.dp, 16.dp), 32.dp), containerColor = androidx.compose.ui.graphics.Color.Transparent, shape = glassShape(32.dp, 16.dp),
            tonalElevation = 0.dp,
            onDismissRequest = { deletingPreset = null },
            title = { Text(stringResource(R.string.eq_delete_preset)) },
            text = { Text(stringResource(R.string.eq_delete_preset_confirm, preset.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.onDeleteProfile(preset.id)
                        deletingPreset = null
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingPreset = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

// --- HELPER COMPOSABLES ---

@Composable
private fun EQProfileItem(
    profile: SavedEQProfile,
    isSelected: Boolean,
    onSelected: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    ListItem(
        colors = com.metrolist.music.ui.component.glassListItemColors(),
        content = {
            Text(
                text = profile.deviceModel,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
        },
        supportingContent = {
            Text(
                pluralStringResource(
                    id = R.plurals.band_count,
                    count = profile.bands.size,
                    profile.bands.size
                )
            )
        },
        leadingContent = {
            RadioButton(
                selected = isSelected,
                onClick = onSelected
            )
        },
        trailingContent = {
            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(
                    painter = painterResource(R.drawable.delete),
                    contentDescription = stringResource(R.string.delete_profile_desc),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        },
        modifier = Modifier
            .clickable(onClick = onSelected)
            .padding(horizontal = 8.dp)
    )

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(modifier = androidx.compose.ui.Modifier.glassOverlay(com.metrolist.music.ui.component.glassShape(32.dp, 16.dp), 32.dp), containerColor = androidx.compose.ui.graphics.Color.Transparent, shape = com.metrolist.music.ui.component.glassShape(32.dp, 16.dp),
tonalElevation = 0.dp,

            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_profile_desc)) },
            text = {
                Text(
                    stringResource(R.string.delete_profile_confirmation, profile.name)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}
