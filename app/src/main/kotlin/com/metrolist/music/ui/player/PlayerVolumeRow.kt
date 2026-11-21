/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * System volume control for the full player: speaker icon, slider, loud speaker icon, like the one in Apple Music.
 * It drives the music stream (what the hardware volume keys change) and follows those keys while the player is open.
 * The bar itself is the shared [VolumeControl] (the same one the player menu uses); this file only wires it to the
 * Android music stream.
 */

package com.metrolist.music.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.ui.component.painterResource
import com.metrolist.music.ui.component.SystemVolumeMapping
import com.metrolist.music.ui.component.SystemVolumeMuteMemory
import com.metrolist.music.ui.component.VolumeControl
import com.metrolist.music.ui.component.VolumeControlDefaults

/**
 * @param horizontalPadding where the first icon (the glyph, not its 48 dp touch area) sits from the screen edge.
 */
@Composable
fun PlayerVolumeRow(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 28.dp,
) {
    val context = LocalContext.current
    val service = LocalPlayerConnection.current?.service
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    val minVolume =
        remember {
            if (Build.VERSION.SDK_INT >= 28) runCatching { audio.getStreamMinVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0) else 0
        }.coerceIn(0, maxVolume - 1)
    // the loudness of every step on this phone (Android 9+ tells it), so the trim between two steps follows the phone's curve
    val stepDb =
        remember(maxVolume) {
            SystemVolumeMapping.stepDbTable(
                maxVolume,
                if (Build.VERSION.SDK_INT >= 28) {
                    { index -> audio.getStreamVolumeDb(AudioManager.STREAM_MUSIC, index, currentOutputDeviceType(audio)) }
                } else {
                    null
                },
            )
        }
    // the bar shows the level the listener chose (a step plus the trim); after the volume keys it shows that step exactly
    val level =
        remember {
            val step = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            mutableFloatStateOf(service?.systemVolumeBarFraction(step) ?: SystemVolumeMapping.fractionOf(step, minVolume, maxVolume))
        }
    val lastStep = remember { intArrayOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }

    // follow the hardware volume keys while the player is on screen (the bar ignores it while a finger is on it)
    DisposableEffect(audio) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    val step = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (step == lastStep[0]) return // our own change (or another stream): the bar already shows it
                    lastStep[0] = step
                    // a step set from outside has no trim (the service drops it too, see MusicService.onDeviceVolumeChanged)
                    level.floatValue = SystemVolumeMapping.fractionOf(step, minVolume, maxVolume)
                }
            }
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
    VolumeControl(
        value = { level.floatValue },
        onValueChange = { v ->
            level.floatValue = v
            val target = SystemVolumeMapping.target(v, minVolume, maxVolume, stepDb)
            if (target.index != lastStep[0]) {
                lastStep[0] = target.index
                runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, target.index, 0) }
            }
            // the system may hold another step than asked (a headphone safety limit): trim against the step it really holds
            val held = runCatching { audio.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(target.index)
            val trim =
                when {
                    v <= 0f -> 0f // silent even where the phone's lowest step is not
                    held == target.index -> target.trim
                    else -> 1f
                }
            lastStep[0] = held
            service?.setSystemVolumeTrim(trim, held, v)
        },
        // the system refused a higher step (safety limit): show where it really is
        onValueChangeFinished = {
            val held = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val asked = SystemVolumeMapping.target(level.floatValue, minVolume, maxVolume, stepDb).index
            if (held < asked) level.floatValue = SystemVolumeMapping.fractionOf(held, minVolume, maxVolume)
        },
        sizes = VolumeControlDefaults.Player,
        memory = SystemVolumeMuteMemory,
        modifier =
            Modifier
                .weight(1f)
                .padding(start = (horizontalPadding - VolumeControlDefaults.GlyphInset).coerceAtLeast(0.dp)),
    )
    // choose the speaker, earbuds or Bluetooth device the music plays on (the system's own picker)
    IconButton(
        onClick = { openOutputPicker(context) },
        modifier = Modifier.padding(end = (horizontalPadding - 12.dp).coerceAtLeast(0.dp)).size(40.dp),
    ) {
        Icon(painter = painterResource(R.drawable.bluetooth), contentDescription = stringResource(R.string.player_output_device))
    }
    }
}

/**
 * Opens the system's list of outputs (Android 14+: the media output switcher), else the Bluetooth settings, so a speaker,
 * earbuds or any Bluetooth device can be connected without leaving the music.
 */
private fun openOutputPicker(context: Context) {
    if (Build.VERSION.SDK_INT >= 34) {
        val shown = runCatching { android.media.MediaRouter2.getInstance(context).showSystemOutputSwitcher() }.getOrDefault(false)
        if (shown) return
    }
    runCatching {
        context.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** The output the music plays on now (speaker, headphones, Bluetooth), whose loudness table the phone reports. */
private fun currentOutputDeviceType(audio: AudioManager): Int {
    if (Build.VERSION.SDK_INT >= 33) {
        runCatching {
            val media = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build()
            audio.getAudioDevicesForAttributes(media).firstOrNull()?.type
        }.getOrNull()?.let { return it }
    }
    val outputs = runCatching { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }.getOrDefault(emptyList())
    val preferred =
        listOfNotNull(
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            android.media.AudioDeviceInfo.TYPE_BLE_HEADSET.takeIf { Build.VERSION.SDK_INT >= 31 },
            android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER.takeIf { Build.VERSION.SDK_INT >= 31 },
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    return preferred.firstOrNull { type -> outputs.any { it.type == type } } ?: android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
}
