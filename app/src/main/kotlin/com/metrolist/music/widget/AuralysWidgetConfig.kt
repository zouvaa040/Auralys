/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.widget

import android.content.Context

/** CLEAR is see-through glass: almost no body colour, so the wallpaper shows and only the edges and gloss are drawn. */
enum class WidgetStyle { GLASS, DARK, LIGHT, COVER, CLEAR }

enum class WidgetCoverShape { ROUNDED, CIRCLE, SQUARE }

enum class WidgetTextSize { SMALL, MEDIUM, LARGE }

enum class WidgetPlayButton { FILLED, PLAIN }

/** Look of one Auralys Player widget; every widget on the home screen keeps its own copy. */
data class AuralysWidgetConfig(
    val style: WidgetStyle = WidgetStyle.GLASS,
    /** Accent colour (ARGB) for the glass and cover-colour styles; 0 takes the colours from the cover. */
    val tint: Int = 0,
    /** 30..100, how solid the background is. */
    val opacity: Int = 100,
    /** 0..100, strength of the glossy highlight and the bright edge. */
    val shine: Int = 70,
    /** 0..100, corner radius from nearly square to a full capsule (the default is the classic 28dp). */
    val corner: Int = DEFAULT_CORNER,
    /** 0..100, how soft the blurred cover behind the glass is. */
    val frost: Int = DEFAULT_FROST,
    /** 0..100, how thick the glass edge looks (rim light and inner depth). */
    val edge: Int = DEFAULT_EDGE,
    val coverShape: WidgetCoverShape = WidgetCoverShape.ROUNDED,
    val textSize: WidgetTextSize = WidgetTextSize.MEDIUM,
    val playButton: WidgetPlayButton = WidgetPlayButton.FILLED,
    val showCover: Boolean = true,
    val showProgress: Boolean = true,
    val showTime: Boolean = true,
    val showLike: Boolean = true,
    val showSkip: Boolean = true,
    /** The listening streak badge (flame + days) when a streak is running. */
    val showStreak: Boolean = true,
) {
    companion object {
        const val DEFAULT_CORNER = 66
        const val DEFAULT_FROST = 40
        const val DEFAULT_EDGE = 60
    }
}

object AuralysWidgetConfigStore {
    private const val PREFS = "auralys_widget_config"
    private val KEYS = listOf(
        "style", "tint", "opacity", "shine", "shape", "text", "play",
        "cover", "progress", "time", "like", "skip", "corner", "frost", "edge", "streak",
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context, widgetId: Int): AuralysWidgetConfig {
        val p = prefs(context)
        val d = AuralysWidgetConfig()
        return AuralysWidgetConfig(
            style = WidgetStyle.entries.getOrElse(p.getInt("$widgetId.style", d.style.ordinal)) { d.style },
            tint = p.getInt("$widgetId.tint", d.tint),
            opacity = p.getInt("$widgetId.opacity", d.opacity).coerceIn(30, 100),
            shine = p.getInt("$widgetId.shine", d.shine).coerceIn(0, 100),
            corner = p.getInt("$widgetId.corner", d.corner).coerceIn(0, 100),
            frost = p.getInt("$widgetId.frost", d.frost).coerceIn(0, 100),
            edge = p.getInt("$widgetId.edge", d.edge).coerceIn(0, 100),
            coverShape = WidgetCoverShape.entries.getOrElse(p.getInt("$widgetId.shape", d.coverShape.ordinal)) { d.coverShape },
            textSize = WidgetTextSize.entries.getOrElse(p.getInt("$widgetId.text", d.textSize.ordinal)) { d.textSize },
            playButton = WidgetPlayButton.entries.getOrElse(p.getInt("$widgetId.play", d.playButton.ordinal)) { d.playButton },
            showCover = p.getBoolean("$widgetId.cover", d.showCover),
            showProgress = p.getBoolean("$widgetId.progress", d.showProgress),
            showTime = p.getBoolean("$widgetId.time", d.showTime),
            showLike = p.getBoolean("$widgetId.like", d.showLike),
            showSkip = p.getBoolean("$widgetId.skip", d.showSkip),
            showStreak = p.getBoolean("$widgetId.streak", d.showStreak),
        )
    }

    fun save(context: Context, widgetId: Int, config: AuralysWidgetConfig) {
        prefs(context).edit()
            .putInt("$widgetId.style", config.style.ordinal)
            .putInt("$widgetId.tint", config.tint)
            .putInt("$widgetId.opacity", config.opacity)
            .putInt("$widgetId.shine", config.shine)
            .putInt("$widgetId.corner", config.corner)
            .putInt("$widgetId.frost", config.frost)
            .putInt("$widgetId.edge", config.edge)
            .putInt("$widgetId.shape", config.coverShape.ordinal)
            .putInt("$widgetId.text", config.textSize.ordinal)
            .putInt("$widgetId.play", config.playButton.ordinal)
            .putBoolean("$widgetId.cover", config.showCover)
            .putBoolean("$widgetId.progress", config.showProgress)
            .putBoolean("$widgetId.time", config.showTime)
            .putBoolean("$widgetId.like", config.showLike)
            .putBoolean("$widgetId.skip", config.showSkip)
            .putBoolean("$widgetId.streak", config.showStreak)
            .apply()
    }

    fun delete(context: Context, widgetId: Int) {
        prefs(context).edit().apply { KEYS.forEach { remove("$widgetId.$it") } }.apply()
    }
}
