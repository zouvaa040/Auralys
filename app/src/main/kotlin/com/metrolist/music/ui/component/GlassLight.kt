/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Light that reacts to how the phone is held: the specular rim of every glass surface slides toward the
 * "light" as you tilt the device, like the edge highlights on iOS 26 glass. Only the rim is redrawn
 * (never recomposed), and the sensor is off whenever the app is in the background.
 */

package com.metrolist.music.ui.component

import android.app.ActivityManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Outline
import androidx.compose.runtime.remember
import kotlin.math.abs
import kotlin.math.max

object GlassLight {
    /** -1..1 on each axis (0 = held as usual). Read from draw scopes only. */
    var tiltX by mutableStateOf(0f)
        private set
    var tiltY by mutableStateOf(0f)
        private set

    private var listener: SensorEventListener? = null
    private var baseX = 0f
    private var baseY = 0f
    private var primed = false

    /** Whether it is worth running at all (skipped on low-RAM devices and when animations are turned off). */
    fun supported(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am?.isLowRamDevice == true) return false
        val scale = try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        } catch (_: Throwable) {
            1f
        }
        return scale > 0f
    }

    fun start(context: Context) {
        if (listener != null || !supported(context)) return
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val l = object : SensorEventListener {
            private var sx = 0f
            private var sy = 0f

            override fun onSensorChanged(e: SensorEvent) {
                val ax = e.values[0]
                val ay = e.values[1]
                if (!primed) {
                    baseX = ax; baseY = ay; sx = 0f; sy = 0f; primed = true
                }
                // the baseline follows slowly, so the highlight rests in the middle for the way you normally hold it
                baseX += (ax - baseX) * 0.02f
                baseY += (ay - baseY) * 0.02f
                val tx = ((ax - baseX) / 3.2f).coerceIn(-1f, 1f)
                val ty = ((ay - baseY) / 3.2f).coerceIn(-1f, 1f)
                sx += (tx - sx) * 0.18f
                sy += (ty - sy) * 0.18f
                if (abs(sx - tiltX) > 0.02f) tiltX = sx
                if (abs(sy - tiltY) > 0.02f) tiltY = sy
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        listener = l
        primed = false
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop(context: Context) {
        val l = listener ?: return
        (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.unregisterListener(l)
        listener = null
        tiltX = 0f
        tiltY = 0f
    }
}

/**
 * Glass rim: a faint base ring, a bright highlight that follows the light, and a warm glint on the opposite edge.
 */
@Composable
fun Modifier.glassRimBorder(
    shape: Shape,
    width: Dp = 1.dp,
): Modifier {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return this.drawWithContent {
        drawContent()
        val tx = GlassLight.tiltX
        val ty = GlassLight.tiltY
        val w = size.width
        val h = size.height
        val reach = max(w, h)
        // the light sits top-left at rest; tilting slides it
        val lx = (0.12f - 0.55f * tx) * w
        val ly = (0.05f - 0.55f * ty) * h
        val hi = if (dark) 0.34f else 0.98f
        val lo = if (dark) 0.06f else 0.28f
        val stroke = Stroke(width = width.toPx() * 2f)
        val outline = shape.createOutline(size, layoutDirection, this)
        fun ring(brush: Brush) {
            when (outline) {
                is Outline.Rounded -> drawPath(androidx.compose.ui.graphics.Path().apply { addRoundRect(outline.roundRect) }, brush, style = stroke)
                is Outline.Rectangle -> drawRect(brush, style = stroke)
                is Outline.Generic -> drawPath(outline.path, brush, style = stroke)
            }
        }
        ring(Brush.linearGradient(listOf(Color.White.copy(alpha = lo), Color.White.copy(alpha = lo * 0.6f)), Offset.Zero, Offset(w, h)))
        ring(Brush.radialGradient(listOf(Color.White.copy(alpha = hi), Color.White.copy(alpha = hi * 0.35f), Color.Transparent), Offset(lx, ly), reach * 0.75f))
        ring(Brush.radialGradient(listOf(Color.White.copy(alpha = if (dark) 0.40f else 0.65f), Color.White.copy(alpha = 0.12f), Color.Transparent), Offset(w - lx, h - ly), reach * 0.5f))
    }
}
