/**
 * Auralys - the Android side of the glass lens policy ([GlassLensPolicy]): reads the phone (RAM class, battery saver,
 * animation scale, app visibility) and the user's switch, and turns a [GlassLensRecipe] into the effect chain of the
 * vendored backdrop library.
 * Licensed under GPL-3.0 | See git history for contributors
 */
package com.metrolist.music.ui.component

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.metrolist.music.constants.GlassLensKey
import com.metrolist.music.ui.component.backdrop.BackdropEffectScope
import com.metrolist.music.ui.component.backdrop.effects.blur
import com.metrolist.music.ui.component.backdrop.effects.colorControls
import com.metrolist.music.ui.component.backdrop.effects.lens
import com.metrolist.music.ui.component.backdrop.effects.vibrancy
import com.metrolist.music.ui.theme.LocalUiStyle
import com.metrolist.music.ui.theme.UiStyle
import com.metrolist.music.utils.rememberPreference

/**
 * True while the full player is open or on its way (set by BottomSheet(premium)). The refracting lens is a shader that runs
 * on every frame for every glass bar (the mini player, the navigation bar), and those are exactly the bars that slide and
 * fade while the player opens: the Kaca theme dropped frames there while Datar (no glass) was smooth. For that time the bars
 * rest on plain frost (the BLUR level, same material, no refraction); the lens is back a moment after the player has closed.
 */
object GlassMotion {
    var sheetMoving by mutableStateOf(false)
}

/** Set once when a lens shader cannot be built: every surface stays on blur until the process restarts. */
object GlassLensGuard {
    var failed by mutableStateOf(false)
        private set

    fun fail() {
        failed = true
    }
}

/**
 * The two things about the phone that change while the app runs: the battery saver and the animation scale. One
 * receiver for the whole process; the lifecycle re-reads both when the app comes back to the front.
 */
internal object GlassLensSystem {
    var powerSave by mutableStateOf(false)
        private set
    var animScale by mutableFloatStateOf(1f)
        private set
    private var started = false

    fun start(context: Context) {
        val app = context.applicationContext
        refresh(app)
        if (started) return
        started = true
        runCatching {
            ContextCompat.registerReceiver(
                app,
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) = refresh(c)
                },
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    fun refresh(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val save = pm?.isPowerSaveMode == true
        if (save != powerSave) powerSave = save
        val scale = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        if (scale != animScale) animScale = scale
    }

    fun isLowRam(context: Context): Boolean =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
}

/**
 * The glass level for the surfaces drawn in this composition (see [GlassLensPolicy.decide]). Cheap: the inputs are
 * state, the answer is remembered.
 */
@Composable
fun rememberGlassLensMode(): LensMode {
    val context = LocalContext.current
    val kaca = LocalUiStyle.current == UiStyle.GLASS
    val backdropAvailable = LocalAppBackdrop.current != null
    val userSwitch by rememberPreference(GlassLensKey, true)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateFlow.collectAsState()
    val lowRam = remember(context) { GlassLensSystem.isLowRam(context) }
    remember(context) { GlassLensSystem.start(context) }
    LaunchedEffect(state) {
        if (state == Lifecycle.State.RESUMED) GlassLensSystem.refresh(context)
    }
    val powerSave = GlassLensSystem.powerSave
    val animScale = GlassLensSystem.animScale
    val failed = GlassLensGuard.failed
    val visible = state.isAtLeast(Lifecycle.State.STARTED)
    val moving = GlassMotion.sheetMoving
    val decided = remember(kaca, backdropAvailable, userSwitch, lowRam, powerSave, animScale, visible, failed) {
        GlassLensPolicy.decide(
            sdk = Build.VERSION.SDK_INT,
            kaca = kaca,
            backdropAvailable = backdropAvailable,
            userSwitch = userSwitch,
            lowRam = lowRam,
            powerSave = powerSave,
            animScale = animScale,
            appVisible = visible,
            shaderFailed = failed,
        )
    }
    return if (moving && decided == LensMode.LENS) LensMode.BLUR else decided
}

/**
 * Saturation, frost and (when the recipe has one) the lens, in the library's chain order. [scale] is the capture scale
 * of the surface: pixel sizes shrink with it, the picture they act on is that much smaller.
 */
fun BackdropEffectScope.applyGlassRecipe(recipe: GlassLensRecipe, scale: Float) {
    if (recipe.saturation == 1.5f) vibrancy() else colorControls(saturation = recipe.saturation)
    blur(recipe.blurDp.dp.toPx() * scale)
    if (recipe.hasLens) {
        lensGuarded(
            recipe.lensHeightDp.dp.toPx() * scale,
            recipe.lensAmountDp.dp.toPx() * scale,
            recipe.depth,
            recipe.dispersion,
        )
    }
}

/** [lens] that cannot take the app down: a shader that fails to compile leaves blur and tells the policy. */
fun BackdropEffectScope.lensGuarded(
    refractionHeight: Float,
    refractionAmount: Float,
    depthEffect: Boolean = false,
    dispersion: Float = 0f,
) {
    try {
        lens(
            refractionHeight = refractionHeight,
            refractionAmount = refractionAmount,
            depthEffect = depthEffect,
            chromaticAberration = dispersion > 0f,
            chromaticAberrationAmount = dispersion,
        )
    } catch (_: Throwable) {
        GlassLensGuard.fail()
    }
}

/** The veil of a tier, thinned for a surface that shows a lens (a thick veil would bury the refraction). */
fun androidx.compose.ui.graphics.Color.thinned(scale: Float): androidx.compose.ui.graphics.Color =
    if (scale >= 1f) this else copy(alpha = alpha * scale)
