/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.poster

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.poster.sticker.StickerEditScreen
import com.metrolist.music.ui.component.painterResource
import com.metrolist.music.ui.theme.LocalHorizon
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

// ====================================================================================================================
// The poster screen: first the song picker (up to five, "3 dari 5"), then the editor: the picture on top (tap = select, drag = move, two fingers = size and
// turn; on the photo: drag = move it, pinch = zoom), the sections below (Foto, Kartu, Gaya kartu, Efek, Stiker, Lagu) like the Windows app, Urungkan,
// Bagikan and Simpan (Pictures/Auralys) in the top bar.

private enum class PosterTab(val label: Int) {
    FOTO(R.string.poster_tab_photo),
    KARTU(R.string.poster_tab_cards),
    GAYA(R.string.poster_tab_card_style),
    LATAR(R.string.poster_tab_background),
    EFEK(R.string.poster_tab_effects),
    TEKS(R.string.poster_tab_text),
    STIKER(R.string.poster_tab_stickers),
    LAGU(R.string.poster_tab_songs),
    EKSPOR(R.string.poster_tab_export),
}

@Composable
fun PosterScreen(navController: NavController) {
    val vm: PosterViewModel = viewModel()
    val context = LocalContext.current
    // taken during the first composition, so a normal open shows the picker in its first frame (no flash of "no songs"); a page brought
    // back after the process died has its saved state already and ignores this (see PosterViewModel / PosterSaved.kt)
    remember(vm) {
        vm.start(PosterLaunch.take())
        true
    }
    LaunchedEffect(vm) {
        vm.eventFlow.collect { e ->
            when (e) {
                is PosterEvent.Toast -> Toast.makeText(context, if (e.arg != null) context.getString(e.text, e.arg) else context.getString(e.text), Toast.LENGTH_SHORT).show()
                is PosterEvent.Share -> runCatching { context.startActivity(e.intent) }
            }
        }
    }
    val hz = LocalHorizon.current
    Box(Modifier.fillMaxSize().background(hz.bg)) {
        when (vm.stage) {
            PosterViewModel.Stage.LOADING -> LoadingPoster { navController.popBackStack() }
            PosterViewModel.Stage.EMPTY -> EmptyPoster { navController.popBackStack() }
            PosterViewModel.Stage.PICK -> PosterPicker(vm) { navController.popBackStack() }
            PosterViewModel.Stage.EDIT -> PosterEditor(vm) { navController.popBackStack() }
        }
    }
}

@Composable
internal fun TopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    val hz = LocalHorizon.current
    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(56.dp).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(painterResource(R.drawable.arrow_back), stringResource(R.string.poster_back), tint = hz.ink) }
        Text(title, style = MaterialTheme.typography.titleMedium, color = hz.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp))
        actions()
    }
}

@Composable
private fun EmptyPoster(onBack: () -> Unit) {
    val hz = LocalHorizon.current
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.poster_title), onBack)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.poster_no_songs), style = MaterialTheme.typography.bodyLarge, color = hz.text2, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
        }
    }
}

@Composable
private fun LoadingPoster(onBack: () -> Unit) {
    val hz = LocalHorizon.current
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.poster_title), onBack)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), color = hz.ink, strokeWidth = 2.5.dp)
        }
    }
}

// ---------------------------------------------------------------- the picker

@Composable
private fun PosterPicker(vm: PosterViewModel, onBack: () -> Unit) {
    val hz = LocalHorizon.current
    BackHandler { onBack() }
    val bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding().coerceAtLeast(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.poster_pick_title), onBack)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.poster_count, vm.picked.size, PosterPick.MAX), style = MaterialTheme.typography.titleSmall, color = hz.ink, modifier = Modifier.weight(1f))
            Pill(stringResource(R.string.poster_pick_first, PosterPick.MAX), onClick = { vm.pickFirst() })
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(vm.source, key = { it.id }) { song ->
                val on = song.id in vm.picked
                val disabled = PosterPick.isDisabled(vm.picked, song.id)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .alpha(if (disabled) 0.45f else 1f)
                        .clickable(enabled = !disabled) { vm.togglePick(song.id) }
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(model = song.coverUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(hz.surface2))
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(song.title, style = MaterialTheme.typography.bodyLarge, color = hz.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.artist, style = MaterialTheme.typography.bodySmall, color = hz.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).background(if (on) hz.ink else Color.Transparent).border(1.5.dp, if (on) hz.ink else hz.text3, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (on) Icon(painterResource(R.drawable.check), null, tint = hz.bg, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = bottom + 12.dp)) {
            Pill(stringResource(R.string.poster_next), onClick = { vm.confirmPick() }, filled = true, enabled = PosterPick.canContinue(vm.picked), modifier = Modifier.fillMaxWidth())
        }
    }
}

// ---------------------------------------------------------------- the editor

@Composable
private fun PosterEditor(vm: PosterViewModel, onBack: () -> Unit) {
    val hz = LocalHorizon.current
    val context = LocalContext.current
    val state = vm.state
    var tab by rememberSaveable { mutableStateOf(PosterTab.FOTO) }
    var askLeave by remember { mutableStateOf(false) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.choosePhoto(uri) }
    val stickerPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris -> vm.importStickers(uris) }
    val storage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.save() else Toast.makeText(context, R.string.poster_storage_denied, Toast.LENGTH_SHORT).show()
    }
    val pickPhoto = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val leave = { if (vm.needsLeaveConfirm()) askLeave = true else onBack() }
    BackHandler { leave() }
    // a tap on a card, a text or a sticker opens its section
    LaunchedEffect(state.selected, state.selectedSticker, state.selectedText) {
        if (state.selected >= 0 && tab != PosterTab.KARTU && tab != PosterTab.GAYA) tab = PosterTab.KARTU
        if (state.selectedSticker >= 0) tab = PosterTab.STIKER
        if (state.selectedText >= 0) tab = PosterTab.TEKS
    }
    val save = {
        if (PosterSave.needsLegacyPermission() && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            storage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            vm.save()
        }
    }
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val panel: Dp = (screenH * 0.34f).coerceIn(200.dp, 330.dp)
    val bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding().coerceAtLeast(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
    Column(Modifier.fillMaxSize().padding(bottom = bottom)) {
        TopBar(stringResource(R.string.poster_title), leave) {
            IconButton(onClick = { vm.undo() }, enabled = vm.canUndo) { Icon(painterResource(R.drawable.replay), stringResource(R.string.poster_undo), tint = if (vm.canUndo) hz.ink else hz.text3) }
            IconButton(onClick = { vm.share() }, enabled = !vm.saving) { Icon(painterResource(R.drawable.share), stringResource(R.string.poster_share), tint = hz.ink) }
            Pill(
                if (vm.saving) stringResource(R.string.poster_saving) else stringResource(R.string.poster_save),
                onClick = { save() },
                filled = true,
                enabled = !vm.saving,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            // a phone that needs over 250 ms for a picture three times running gets the lighter preview (no depth of field, smaller blurs); the PNG is always full quality
            var level by remember { mutableStateOf(previewLevel(context)) }
            var slow by remember { mutableStateOf(0) }
            PosterPreview(state, vm.assets, Modifier.fillMaxSize(), touch = vm, level = level, onRendered = { ms ->
                if (level == 0) {
                    slow = if (ms > 250) slow + 1 else 0
                    if (slow >= 3) level = 1
                }
            })
            if (vm.loadingPhoto || state.cutout == CutoutPhase.RUNNING) {
                Row(
                    Modifier.align(Alignment.TopCenter).padding(top = 10.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.62f)).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.cutout == CutoutPhase.RUNNING && state.cutoutProgress > 0f) {
                        CircularProgressIndicator(progress = { state.cutoutProgress }, modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp, trackColor = Color.White.copy(alpha = 0.25f))
                    } else {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    }
                    Spacer(Modifier.width(8.dp))
                    val pct = if (state.cutout == CutoutPhase.RUNNING && state.cutoutProgress > 0f) " ${(state.cutoutProgress * 100).roundToInt()}%" else ""
                    Text(stringResource(R.string.poster_cutting) + pct, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
                    if (state.cutout == CutoutPhase.RUNNING) {
                        TextButton(onClick = { vm.cancelCutout() }) { Text(stringResource(R.string.poster_cancel), color = Color.White, style = MaterialTheme.typography.labelLarge) }
                    } else {
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }
        }
        // the shape of the poster, always in reach: a small outline of each shape and its name; "Acak desain" stays at the right end
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PosterRatio.entries.forEach { r -> RatioChip(r, state.ratio == r) { vm.setRatio(r) } }
            }
            Spacer(Modifier.width(8.dp))
            Pill(stringResource(R.string.poster_shuffle), onClick = { vm.shuffleDesign() }, icon = { Icon(painterResource(R.drawable.shuffle), null, Modifier.size(18.dp)) })
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PosterTab.entries.forEach { t -> Chip(stringResource(t.label), tab == t) { tab = t } }
        }
        Box(Modifier.fillMaxWidth().height(panel).background(hz.surface1)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                when (tab) {
                    PosterTab.FOTO -> PhotoSection(vm, pickPhoto)
                    PosterTab.KARTU -> CardSection(vm)
                    PosterTab.GAYA -> CardStyleSection(vm)
                    PosterTab.LATAR -> BackgroundSection(vm)
                    PosterTab.EFEK -> EffectsSection(vm)
                    PosterTab.TEKS -> TextSection(vm)
                    PosterTab.STIKER -> StickerSection(vm) { stickerPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    PosterTab.LAGU -> SongsSection(vm)
                    PosterTab.EKSPOR -> ExportSection(vm, save)
                }
            }
        }
    }
    // "Ubah stiker" over the editor (its own back handling: registered later, it goes first)
    if (vm.stickerFlow.session != null || vm.stickerFlow.opening) StickerEditScreen(vm.stickerFlow)
    if (askLeave) {
        AlertDialog(
            onDismissRequest = { askLeave = false },
            title = { Text(stringResource(R.string.poster_leave_title)) },
            text = { Text(stringResource(R.string.poster_leave_text)) },
            confirmButton = {
                TextButton(onClick = {
                    askLeave = false
                    onBack()
                }) { Text(stringResource(R.string.poster_leave)) }
            },
            dismissButton = { TextButton(onClick = { askLeave = false }) { Text(stringResource(R.string.poster_stay)) } },
        )
    }
}

// ---------------------------------------------------------------- sections

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhotoSection(vm: PosterViewModel, pickPhoto: () -> Unit) {
    val state = vm.state
    val ph = state.photo
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ph == null) {
            Pill(stringResource(R.string.poster_photo_add), onClick = pickPhoto, filled = true, icon = { Icon(painterResource(R.drawable.insert_photo), null, Modifier.size(18.dp)) })
        } else {
            Pill(stringResource(R.string.poster_photo_change), onClick = pickPhoto)
            Pill(stringResource(R.string.poster_photo_remove), onClick = { vm.removePhoto() })
        }
    }
    if (ph != null) {
        SliderRow(stringResource(R.string.poster_size), state.photoZoom, PosterEdit.MIN_PHOTO_ZOOM..PosterEdit.MAX_PHOTO_ZOOM, "${(state.photoZoom * 100).roundToInt()}%", vm::mark) { vm.setZoom(it) }
        val (limX, limY) = PosterEdit.panLimit(ph.w, ph.h, state.photoZoom, state.ratio)
        SliderRow(stringResource(R.string.poster_move_x), state.photoPanX, -maxOf(limX, 1f)..maxOf(limX, 1f), "${(state.photoPanX / state.ratio.w * 100).roundToInt()}%", vm::mark, enabled = limX >= 1f) { vm.setPan(it, state.photoPanY) }
        SliderRow(stringResource(R.string.poster_move_y), state.photoPanY, -maxOf(limY, 1f)..maxOf(limY, 1f), "${(state.photoPanY / state.ratio.h * 100).roundToInt()}%", vm::mark, enabled = limY >= 1f) { vm.setPan(state.photoPanX, it) }
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(stringResource(R.string.poster_fill), onClick = { vm.fillScreen() })
            Pill(stringResource(R.string.poster_fit), onClick = { vm.fitWhole() })
            Pill(stringResource(R.string.poster_rotate), onClick = { vm.rotatePhoto() }, icon = { Icon(painterResource(R.drawable.refresh), null, Modifier.size(18.dp)) })
            Pill(stringResource(R.string.poster_mirror), onClick = { vm.mirrorPhoto() })
        }
    }
    GroupTitle(stringResource(R.string.poster_background))
    SliderRow(stringResource(R.string.poster_dark), state.dim, 0f..0.7f, "${(state.dim / 0.7f * 100).roundToInt()}%", vm::mark) { state.dim = it }
    SliderRow(stringResource(R.string.poster_blur), state.blur, 0f..1f, "${(state.blur * 100).roundToInt()}%", vm::mark) { state.blur = it }
    SliderRow(stringResource(R.string.poster_card_light), state.glow, 0f..1f, "${(state.glow * 100).roundToInt()}%", vm::mark) { state.glow = it }
    GroupTitle(stringResource(R.string.poster_light_color))
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Chip(stringResource(R.string.poster_auto), state.glowTint == null) {
            vm.mark()
            state.glowTint = null
        }
        GlowPresets.forEach { (name, color) ->
            Swatch(stringResource(name), color, state.glowTint == color) {
                vm.mark()
                state.glowTint = color
            }
        }
    }
}

private val GlowPresets =
    listOf(
        R.string.poster_glow_warm_white to Color(0xFFFFE9C9),
        R.string.poster_glow_gold to Color(0xFFFFC857),
        R.string.poster_glow_orange to Color(0xFFFF9248),
        R.string.poster_glow_pink to Color(0xFFFF7BA3),
        R.string.poster_glow_cool_white to Color(0xFFDDE3F0),
    )

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardSection(vm: PosterViewModel) {
    val state = vm.state
    val hz = LocalHorizon.current
    GroupTitle(stringResource(R.string.poster_layout))
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PosterLayout.entries.forEach { l -> Chip(stringResource(layoutLabel(l)), state.layout == l) { vm.applyLayout(l) } }
    }
    Box(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) { Pill(stringResource(R.string.poster_rearrange), onClick = { vm.rearrange() }, icon = { Icon(painterResource(R.drawable.refresh), null, Modifier.size(18.dp)) }) }
    val sel = state.selected
    if (sel in state.poses.indices) {
        val pose = state.poses[sel]
        Text(state.songs.getOrNull(sel)?.title.orEmpty(), style = MaterialTheme.typography.titleSmall, color = hz.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp))
        SliderRow(stringResource(R.string.poster_size), pose.scale, PosterEdit.MIN_CARD_SCALE..PosterEdit.MAX_CARD_SCALE, "${(pose.scale * 100).roundToInt()}%", vm::mark) { v -> vm.updateCard { it.copy(scale = v) } }
        SliderRow(stringResource(R.string.poster_rotate), pose.rotation, -45f..45f, "${pose.rotation.roundToInt()}°", vm::mark) { v -> vm.updateCard { it.copy(rotation = v) } }
        SliderRow(stringResource(R.string.poster_tilt_flat), pose.tiltY, -60f..60f, "${pose.tiltY.roundToInt()}°", vm::mark) { v -> vm.updateCard { it.copy(tiltY = v) } }
        SliderRow(stringResource(R.string.poster_tilt_up), pose.tiltX, -45f..45f, "${pose.tiltX.roundToInt()}°", vm::mark) { v -> vm.updateCard { it.copy(tiltX = v) } }
        SliderRow(stringResource(R.string.poster_lift), pose.lift, 0f..1f, "${(pose.lift * 100).roundToInt()}%", vm::mark) { v -> vm.updateCard { it.copy(lift = v) } }
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { Pill(stringResource(R.string.poster_card_reset), onClick = { vm.resetCard(sel) }) }
    } else {
        Text(stringResource(R.string.poster_card_hint), style = MaterialTheme.typography.bodyMedium, color = hz.text2, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
    }
}

private fun layoutLabel(l: PosterLayout): Int =
    when (l) {
        PosterLayout.ORBIT -> R.string.poster_layout_orbit
        PosterLayout.TUMPUK -> R.string.poster_layout_stack
        PosterLayout.DERET -> R.string.poster_layout_row
        PosterLayout.KOLOM -> R.string.poster_layout_column
        PosterLayout.KOLASE -> R.string.poster_layout_grid
        PosterLayout.BESAR -> R.string.poster_layout_big
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardStyleSection(vm: PosterViewModel) {
    val state = vm.state
    GroupTitle(stringResource(R.string.poster_glass))
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardMaterial.entries.forEach { m ->
            Chip(stringResource(materialLabel(m)), PosterStyleEdit.isLit(state, m)) {
                vm.mark()
                PosterStyleEdit.setMaterial(state, m)
            }
        }
    }
    CardSlider.entries.forEach { s ->
        val v = PosterStyleEdit.value(state, s)
        SliderRow(stringResource(cardSliderLabel(s)), v, 0f..1f, "${(v * 100).roundToInt()}%", vm::mark) { PosterStyleEdit.setSlider(state, s, it) }
    }
    GroupTitle(stringResource(R.string.poster_shadow))
    ShadowSlider.entries.forEach { s ->
        val v = PosterStyleEdit.value(state, s)
        SliderRow(stringResource(shadowSliderLabel(s)), v, 0f..1f, "${(v * 100).roundToInt()}%", vm::mark) { PosterStyleEdit.setSlider(state, s, it) }
    }
    SliderRow(stringResource(R.string.poster_direction), state.shadowAngle, 0f..360f, "${state.shadowAngle.roundToInt()}°", vm::mark) { PosterStyleEdit.setShadowAngle(state, it) }
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ShadowTone.entries.forEach { t ->
            Chip(stringResource(toneLabel(t)), state.shadowTone == t) {
                vm.mark()
                state.shadowTone = t
            }
        }
    }
    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Pill(stringResource(R.string.poster_style_reset), onClick = {
            vm.mark()
            PosterStyleEdit.reset(state)
        })
    }
}

private fun materialLabel(m: CardMaterial): Int =
    when (m) {
        CardMaterial.ASAP -> R.string.poster_glass_smoke
        CardMaterial.BENING -> R.string.poster_glass_clear
        CardMaterial.SUSU -> R.string.poster_glass_milk
        CardMaterial.GELAP -> R.string.poster_glass_dark
    }

private fun cardSliderLabel(s: CardSlider): Int =
    when (s) {
        CardSlider.PERSPEKTIF -> R.string.poster_perspective
        CardSlider.KETEBALAN -> R.string.poster_thickness
        CardSlider.KACA_BURAM -> R.string.poster_frost
        CardSlider.KEKERUHAN -> R.string.poster_opacity
        CardSlider.SUDUT -> R.string.poster_corner
        CardSlider.PANTULAN -> R.string.poster_reflection
        CardSlider.KEDALAMAN -> R.string.poster_dof
    }

private fun shadowSliderLabel(s: ShadowSlider): Int =
    when (s) {
        ShadowSlider.KEKUATAN -> R.string.poster_strength
        ShadowSlider.KABUR -> R.string.poster_blur
        ShadowSlider.JARAK -> R.string.poster_distance
    }

private fun toneLabel(t: ShadowTone): Int =
    when (t) {
        ShadowTone.NETRAL -> R.string.poster_tone_neutral
        ShadowTone.HANGAT -> R.string.poster_tone_warm
        ShadowTone.SAMPUL -> R.string.poster_tone_cover
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EffectsSection(vm: PosterViewModel) {
    val state = vm.state
    GroupTitle(stringResource(R.string.poster_look))
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PosterFxPreset.entries.forEach { p ->
            Chip(stringResource(presetLabel(p)), state.preset == p) {
                vm.mark()
                PosterFxEdit.setPreset(state, p)
            }
        }
    }
    GroupTitle(stringResource(R.string.poster_colour))
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PosterGrade.entries.forEach { g ->
            Chip(stringResource(gradeLabel(g)), state.grade == g) {
                vm.mark()
                PosterFxEdit.setGrade(state, g)
            }
        }
    }
    FxSlider.entries.forEach { s ->
        val v = PosterFxEdit.value(state, s)
        SliderRow(stringResource(fxLabel(s)), v, 0f..1f, "${(v * 100).roundToInt()}%", vm::mark) { PosterFxEdit.setSlider(state, s, it) }
    }
}

private fun presetLabel(p: PosterFxPreset): Int =
    when (p) {
        PosterFxPreset.SINEMA -> R.string.poster_preset_cinema
        PosterFxPreset.FILM -> R.string.poster_preset_film
        PosterFxPreset.BERSIH -> R.string.poster_preset_clean
    }

private fun gradeLabel(g: PosterGrade): Int =
    when (g) {
        PosterGrade.ASLI -> R.string.poster_grade_original
        PosterGrade.HANGAT -> R.string.poster_grade_warm
        PosterGrade.DINGIN -> R.string.poster_grade_cool
        PosterGrade.NEON -> R.string.poster_grade_neon
        PosterGrade.FILM -> R.string.poster_grade_film
    }

private fun fxLabel(s: FxSlider): Int =
    when (s) {
        FxSlider.LEAK -> R.string.poster_fx_key_light
        FxSlider.STREAK -> R.string.poster_fx_lens_streak
        FxSlider.SPARKLE -> R.string.poster_fx_glints
        FxSlider.BLOOM -> R.string.poster_fx_bloom
        FxSlider.GRAIN -> R.string.poster_fx_grain
        FxSlider.RIM -> R.string.poster_fx_rim
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StickerSection(vm: PosterViewModel, pickStickers: () -> Unit) {
    val state = vm.state
    val hz = LocalHorizon.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill(stringResource(R.string.poster_sticker_add), onClick = pickStickers, filled = true, icon = { Icon(painterResource(R.drawable.add), null, Modifier.size(18.dp)) })
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.poster_count, state.stickers.size, PosterStickers.MAX_COUNT), style = MaterialTheme.typography.labelMedium, color = hz.text2)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StickerButton(StickerKind.BURUNG, stringResource(R.string.poster_sticker_bird)) { vm.addSticker(StickerKind.BURUNG) }
        StickerButton(StickerKind.KILAU, stringResource(R.string.poster_sticker_glint)) { vm.addSticker(StickerKind.KILAU) }
    }
    val i = state.selectedSticker
    if (i in state.stickers.indices) {
        val s = state.stickers[i]
        SliderRow(stringResource(R.string.poster_size), log2(s.scale), -2f..2f, "${(s.scale * 100).roundToInt()}%", vm::mark) { v -> vm.updateSticker { it.copy(scale = 2f.pow(v).coerceIn(PosterStickers.MIN_SCALE, PosterStickers.MAX_SCALE)) } }
        SliderRow(stringResource(R.string.poster_rotate), s.rotation, -180f..180f, "${s.rotation.roundToInt()}°", vm::mark) { v -> vm.updateSticker { it.copy(rotation = v) } }
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(stringResource(R.string.poster_mirror), onClick = {
                vm.mark()
                vm.updateSticker { it.copy(flip = !it.flip) }
            })
            Pill(stringResource(R.string.poster_duplicate), onClick = { vm.duplicateSticker() }, enabled = state.stickers.size < PosterStickers.MAX_COUNT, icon = { Icon(painterResource(R.drawable.content_copy), null, Modifier.size(18.dp)) })
            Pill(stringResource(R.string.poster_delete), onClick = { vm.deleteSticker() }, icon = { Icon(painterResource(R.drawable.delete), null, Modifier.size(18.dp)) })
            val key = s.imageKey
            if (s.kind == StickerKind.CUSTOM && key != null && vm.libraryItems.any { it.key == key }) {
                Pill(stringResource(R.string.poster_sticker_edit), onClick = { vm.editLibrarySticker(key) }, icon = { Icon(painterResource(R.drawable.edit), null, Modifier.size(18.dp)) })
            }
        }
    }
    GroupTitle(stringResource(R.string.poster_my_stickers))
    if (vm.libraryItems.isEmpty()) {
        Text(stringResource(R.string.poster_none_yet), style = MaterialTheme.typography.bodyMedium, color = hz.text2, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    } else {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.libraryItems.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { item -> LibraryTile(item, Modifier.weight(1f), onPlace = { vm.placeLibrarySticker(item.key) }, onEdit = { vm.editLibrarySticker(item.key) }, onRemove = { vm.removeFromLibrary(item.key) }) }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun SongsSection(vm: PosterViewModel) {
    val state = vm.state
    val hz = LocalHorizon.current
    state.songs.forEachIndexed { i, song ->
        Row(
            Modifier.fillMaxWidth().height(60.dp).background(if (state.selected == i) hz.surface2 else Color.Transparent).clickable { vm.selectCard(if (state.selected == i) -1 else i) }.padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(model = song.coverUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(hz.surface2))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(song.title, style = MaterialTheme.typography.bodyLarge, color = hz.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = MaterialTheme.typography.bodySmall, color = hz.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { vm.moveSong(i, -1) }, enabled = i > 0) { Icon(painterResource(R.drawable.expand_less), stringResource(R.string.poster_up), tint = if (i > 0) hz.text2 else hz.text3) }
            IconButton(onClick = { vm.moveSong(i, 1) }, enabled = i < state.songs.lastIndex) { Icon(painterResource(R.drawable.expand_more), stringResource(R.string.poster_down), tint = if (i < state.songs.lastIndex) hz.text2 else hz.text3) }
            IconButton(onClick = { vm.removeSong(i) }, enabled = state.songs.size > 1) { Icon(painterResource(R.drawable.close), stringResource(R.string.poster_remove_song), tint = if (state.songs.size > 1) hz.text2 else hz.text3) }
        }
    }
}

// ---------------------------------------------------------------- round 4.3.6: background style, texts, export

private fun backgroundLabel(b: PosterBackground): Int =
    when (b) {
        PosterBackground.ASLI -> R.string.poster_bg_original
        PosterBackground.KABUR -> R.string.poster_bg_blur
        PosterBackground.GRADIEN -> R.string.poster_bg_gradient
        PosterBackground.POLOS -> R.string.poster_bg_solid
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackgroundSection(vm: PosterViewModel) {
    val state = vm.state
    val hz = LocalHorizon.current
    GroupTitle(stringResource(R.string.poster_tab_background))
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PosterBackground.entries.forEach { b -> Chip(stringResource(backgroundLabel(b)), state.background == b) { vm.setBackground(b) } }
    }
    if (state.photo != null && state.background != PosterBackground.ASLI) {
        Text(stringResource(R.string.poster_bg_hint), style = MaterialTheme.typography.bodyMedium, color = hz.text2, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
    }
    SliderRow(stringResource(R.string.poster_vignette), state.fxVignette, 0f..1f, "${(state.fxVignette * 100).roundToInt()}%", vm::mark) { state.fxVignette = it }
    SliderRow(stringResource(R.string.poster_card_light), state.glow, 0f..1f, "${(state.glow * 100).roundToInt()}%", vm::mark) { state.glow = it }
}

private fun textStyleLabel(t: PosterTextStyle): Int =
    when (t) {
        PosterTextStyle.BERSIH -> R.string.poster_text_clean
        PosterTextStyle.TEBAL -> R.string.poster_text_bold
        PosterTextStyle.SERIF -> R.string.poster_text_serif
        PosterTextStyle.SOROT -> R.string.poster_text_marker
        PosterTextStyle.GARIS -> R.string.poster_text_outline
    }

/** The colours a text may take: white, ink, the covers' own colours and a gold. */
private fun textPalette(state: PosterState): List<Int> =
    (listOf(PosterTexts.WHITE, PosterTexts.INK) + state.songs.mapNotNull { state.tints[it.id]?.toArgb() } + 0xFFFFC857.toInt()).distinct()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextSection(vm: PosterViewModel) {
    val state = vm.state
    val hz = LocalHorizon.current
    var dialog by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    val i = state.selectedText
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill(
            stringResource(R.string.poster_text_add),
            onClick = {
                editing = false
                dialog = ""
            },
            filled = true,
            icon = { Icon(painterResource(R.drawable.add), null, Modifier.size(18.dp)) },
        )
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.poster_count, state.texts.size, PosterTexts.MAX_COUNT), style = MaterialTheme.typography.labelMedium, color = hz.text2)
    }
    if (i in state.texts.indices) {
        val t = state.texts[i]
        GroupTitle(stringResource(R.string.poster_text_style))
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PosterTextStyle.entries.forEach { st ->
                Chip(stringResource(textStyleLabel(st)), t.style == st) {
                    vm.mark()
                    vm.updateText { it.copy(style = st) }
                }
            }
        }
        GroupTitle(stringResource(R.string.poster_text_color))
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            textPalette(state).forEach { c ->
                Swatch(stringResource(R.string.poster_text_color), Color(c), t.color == c) {
                    vm.mark()
                    vm.updateText { it.copy(color = c) }
                }
            }
        }
        SliderRow(stringResource(R.string.poster_size), log2(t.scale), log2(PosterTexts.MIN_SCALE)..log2(PosterTexts.MAX_SCALE), "${(t.scale * 100).roundToInt()}%", vm::mark) { v ->
            vm.updateText { it.copy(scale = 2f.pow(v).coerceIn(PosterTexts.MIN_SCALE, PosterTexts.MAX_SCALE)) }
        }
        SliderRow(stringResource(R.string.poster_rotate), t.rotation, -180f..180f, "${t.rotation.roundToInt()}°", vm::mark) { v -> vm.updateText { it.copy(rotation = v) } }
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(
                stringResource(R.string.poster_text_edit),
                onClick = {
                    editing = true
                    dialog = t.text
                },
                icon = { Icon(painterResource(R.drawable.edit), null, Modifier.size(18.dp)) },
            )
            Pill(stringResource(R.string.poster_duplicate), onClick = { vm.duplicateText() }, enabled = state.texts.size < PosterTexts.MAX_COUNT, icon = { Icon(painterResource(R.drawable.content_copy), null, Modifier.size(18.dp)) })
            Pill(stringResource(R.string.poster_delete), onClick = { vm.deleteText() }, icon = { Icon(painterResource(R.drawable.delete), null, Modifier.size(18.dp)) })
        }
    } else if (state.texts.isNotEmpty()) {
        Text(stringResource(R.string.poster_text_hint), style = MaterialTheme.typography.bodyMedium, color = hz.text2, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
    }
    val words = dialog
    if (words != null) {
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(if (editing) R.string.poster_text_edit else R.string.poster_text_add)) },
            text = {
                OutlinedTextField(
                    value = words,
                    onValueChange = { dialog = it.take(PosterTexts.MAX_CHARS) },
                    placeholder = { Text(stringResource(R.string.poster_text_placeholder)) },
                    maxLines = PosterTexts.MAX_LINES,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = PosterTexts.clean(words) != null,
                    onClick = {
                        if (editing) vm.editSelectedText(words) else vm.addText(words, color = textPalette(state).firstOrNull() ?: PosterTexts.WHITE)
                        dialog = null
                    },
                ) { Text(stringResource(R.string.poster_text_done)) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.poster_cancel)) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExportSection(vm: PosterViewModel, save: () -> Unit) {
    val state = vm.state
    val hz = LocalHorizon.current
    GroupTitle(stringResource(R.string.poster_export_format))
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(stringResource(R.string.poster_export_png), state.exportFormat == PosterFormat.PNG) { vm.setExportFormat(PosterFormat.PNG) }
        Chip(stringResource(R.string.poster_export_jpeg), state.exportFormat == PosterFormat.JPEG) { vm.setExportFormat(PosterFormat.JPEG) }
    }
    val jpeg = state.exportFormat == PosterFormat.JPEG
    SliderRow(stringResource(R.string.poster_export_quality), state.exportQuality, 0.5f..1f, "${(state.exportQuality * 100).roundToInt()}%", {}, enabled = jpeg) { vm.setExportQuality(it) }
    Text(
        stringResource(R.string.poster_export_size, state.ratio.w, state.ratio.h) + "  " + state.ratio.label,
        style = MaterialTheme.typography.bodyMedium,
        color = hz.text2,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(if (vm.saving) stringResource(R.string.poster_saving) else stringResource(R.string.poster_save), onClick = save, filled = true, enabled = !vm.saving)
        Pill(stringResource(R.string.poster_share), onClick = { vm.share() }, enabled = !vm.saving, icon = { Icon(painterResource(R.drawable.share), null, Modifier.size(18.dp)) })
    }
}

private fun ratioLabel(r: PosterRatio): Int =
    when (r) {
        PosterRatio.R9_16 -> R.string.poster_ratio_9_16
        PosterRatio.R16_9 -> R.string.poster_ratio_16_9
        PosterRatio.R4_5 -> R.string.poster_ratio_4_5
        PosterRatio.R1_1 -> R.string.poster_ratio_1_1
        PosterRatio.R3_4 -> R.string.poster_ratio_3_4
        PosterRatio.R4_3 -> R.string.poster_ratio_4_3
        PosterRatio.R2_3 -> R.string.poster_ratio_2_3
        PosterRatio.R3_2 -> R.string.poster_ratio_3_2
    }

/** One shape of the poster: a small outline of it (to scale) and its name. */
@Composable
private fun RatioChip(ratio: PosterRatio, selected: Boolean, onClick: () -> Unit) {
    val hz = LocalHorizon.current
    val ink = if (selected) hz.bg else hz.ink
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) hz.ink else hz.surface2)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
            val k = size.minDimension / maxOf(ratio.w, ratio.h) * 0.92f
            val w = ratio.w * k
            val h = ratio.h * k
            drawRoundRect(ink, Offset((size.width - w) / 2f, (size.height - h) / 2f), androidx.compose.ui.geometry.Size(w, h), CornerRadius(2.5.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.8.dp.toPx()))
        }
        Spacer(Modifier.width(8.dp))
        Text(stringResource(ratioLabel(ratio)), style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1)
    }
}

// ---------------------------------------------------------------- small controls (Horizon: neutral ink, no accent colour)

@Composable
internal fun GroupTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = LocalHorizon.current.ink, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 2.dp))
}

@Composable
internal fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onStart: () -> Unit, enabled: Boolean = true, onEnd: () -> Unit = {}, onChange: (Float) -> Unit) {
    val hz = LocalHorizon.current
    var dragging by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = if (enabled) hz.ink else hz.text3, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = hz.text2)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = {
                if (!dragging) {
                    onStart()
                    dragging = true
                }
                onChange(it)
            },
            onValueChangeFinished = {
                dragging = false
                onEnd()
            },
            valueRange = range,
            enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = hz.ink, activeTrackColor = hz.ink, inactiveTrackColor = hz.divider, disabledThumbColor = hz.text3, disabledActiveTrackColor = hz.text3, disabledInactiveTrackColor = hz.divider),
            modifier = Modifier.fillMaxWidth().height(32.dp),
        )
    }
}

@Composable
internal fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val hz = LocalHorizon.current
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) hz.ink else hz.surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) hz.bg else hz.ink, maxLines = 1)
    }
}

@Composable
internal fun Pill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = false, enabled: Boolean = true, icon: (@Composable () -> Unit)? = null) {
    val hz = LocalHorizon.current
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(50))
            .background(if (filled) hz.ink else hz.surface2)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides if (filled) hz.bg else hz.ink) { icon() }
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (filled) hz.bg else hz.ink, maxLines = 1)
    }
}

@Composable
private fun Swatch(name: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    val hz = LocalHorizon.current
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = name, onClick = onClick)
            .border(2.dp, if (selected) hz.ink else Color.Transparent, CircleShape)
            .padding(4.dp)
            .clip(CircleShape)
            .background(color)
            .border(0.8.dp, hz.divider, CircleShape),
    )
}

@Composable
private fun StickerButton(kind: StickerKind, label: String, onClick: () -> Unit) {
    val hz = LocalHorizon.current
    Column(
        Modifier.width(84.dp).height(96.dp).clip(RoundedCornerShape(12.dp)).background(hz.surface2).clickable(onClick = onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StickerPreview(kind, Modifier.size(52.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = hz.ink, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun LibraryTile(item: StickerLibItem, modifier: Modifier, onPlace: () -> Unit, onEdit: () -> Unit, onRemove: () -> Unit) {
    val hz = LocalHorizon.current
    val img = remember(item) { item.thumb.asImageBitmap() }
    Box(modifier.height(76.dp).clip(RoundedCornerShape(12.dp)).background(hz.surface2).clickable(onClick = onPlace).padding(8.dp), contentAlignment = Alignment.Center) {
        Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Box(
            Modifier.align(Alignment.TopStart).offset(x = (-6).dp, y = (-6).dp).size(24.dp).clip(CircleShape).background(hz.surface3).clickable(onClickLabel = stringResource(R.string.poster_sticker_edit), onClick = onEdit),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.edit), stringResource(R.string.poster_sticker_edit), tint = hz.text2, modifier = Modifier.size(13.dp))
        }
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp).size(24.dp).clip(CircleShape).background(hz.surface3).clickable(onClickLabel = stringResource(R.string.poster_sticker_remove_lib), onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.close), stringResource(R.string.poster_sticker_remove_lib), tint = hz.text2, modifier = Modifier.size(14.dp))
        }
    }
}
