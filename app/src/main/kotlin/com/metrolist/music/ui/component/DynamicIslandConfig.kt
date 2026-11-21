/**
 * Auralys - Dynamic Island options
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.metrolist.music.constants.DynamicIslandAnimation
import com.metrolist.music.constants.DynamicIslandAutoCollapseKey
import com.metrolist.music.constants.DynamicIslandCameraGapKey
import com.metrolist.music.constants.DynamicIslandDefaultCameraGap
import com.metrolist.music.constants.DynamicIslandLyricCameraGapKey
import com.metrolist.music.constants.DynamicIslandMaxCameraGap
import com.metrolist.music.constants.DynamicIslandMinCameraGap
import com.metrolist.music.constants.DynamicIslandButtonStyle
import com.metrolist.music.constants.DynamicIslandButtonStyleKey
import com.metrolist.music.constants.DynamicIslandCompactContent
import com.metrolist.music.constants.DynamicIslandCompactContentKey
import com.metrolist.music.constants.DynamicIslandCoverShape
import com.metrolist.music.constants.DynamicIslandCoverShapeKey
import com.metrolist.music.constants.DynamicIslandAccentColorKey
import com.metrolist.music.constants.DynamicIslandAttachedKey
import com.metrolist.music.constants.DynamicIslandAutoBorder
import com.metrolist.music.constants.DynamicIslandBlurKey
import com.metrolist.music.constants.DynamicIslandBorderColorKey
import com.metrolist.music.constants.DynamicIslandBorderWidthKey
import com.metrolist.music.constants.DynamicIslandCoverScaleKey
import com.metrolist.music.constants.DynamicIslandCustomBottomKey
import com.metrolist.music.constants.DynamicIslandCustomTopKey
import com.metrolist.music.constants.DynamicIslandDefaultAutoCollapse
import com.metrolist.music.constants.DynamicIslandDefaultBlur
import com.metrolist.music.constants.DynamicIslandDefaultCustomBottom
import com.metrolist.music.constants.DynamicIslandDefaultCustomTop
import com.metrolist.music.constants.DynamicIslandDefaultExpandedWidth
import com.metrolist.music.constants.DynamicIslandDefaultShine
import com.metrolist.music.constants.DynamicIslandExpandedRoundnessKey
import com.metrolist.music.constants.DynamicIslandExpandedWidthKey
import com.metrolist.music.constants.DynamicIslandFollowRoundness
import com.metrolist.music.constants.DynamicIslandMaxBorderWidth
import com.metrolist.music.constants.DynamicIslandMaxExpandedWidth
import com.metrolist.music.constants.DynamicIslandMaxScalePercent
import com.metrolist.music.constants.DynamicIslandMinExpandedWidth
import com.metrolist.music.constants.DynamicIslandMinScalePercent
import com.metrolist.music.constants.DynamicIslandShineKey
import com.metrolist.music.constants.DynamicIslandTextColorKey
import com.metrolist.music.constants.DynamicIslandTextScaleKey
import com.metrolist.music.constants.DynamicIslandDefaultGlowIntensity
import com.metrolist.music.constants.DynamicIslandDefaultOpacity
import com.metrolist.music.constants.DynamicIslandDefaultPillHeight
import com.metrolist.music.constants.DynamicIslandDefaultPillWidth
import com.metrolist.music.constants.DynamicIslandDefaultRoundness
import com.metrolist.music.constants.DynamicIslandEqColor
import com.metrolist.music.constants.DynamicIslandEqColorKey
import com.metrolist.music.constants.DynamicIslandEqStyle
import com.metrolist.music.constants.DynamicIslandEqStyleKey
import com.metrolist.music.constants.DynamicIslandGlowColor
import com.metrolist.music.constants.DynamicIslandGlowColorKey
import com.metrolist.music.constants.DynamicIslandGlowIntensityKey
import com.metrolist.music.constants.DynamicIslandGlowKey
import com.metrolist.music.constants.DynamicIslandHapticsKey
import com.metrolist.music.constants.DynamicIslandLayout
import com.metrolist.music.constants.DynamicIslandLayoutKey
import com.metrolist.music.constants.DynamicIslandLongPressAction
import com.metrolist.music.constants.DynamicIslandLongPressActionKey
import com.metrolist.music.constants.DynamicIslandMaxAutoCollapse
import com.metrolist.music.constants.DynamicIslandMaxPillHeight
import com.metrolist.music.constants.DynamicIslandMaxPillWidth
import com.metrolist.music.constants.DynamicIslandMinAutoCollapse
import com.metrolist.music.constants.DynamicIslandMinOpacity
import com.metrolist.music.constants.DynamicIslandMinPillHeight
import com.metrolist.music.constants.DynamicIslandMinPillWidth
import com.metrolist.music.constants.DynamicIslandOpacityKey
import com.metrolist.music.constants.DynamicIslandPillHeightKey
import com.metrolist.music.constants.DynamicIslandPillWidthKey
import com.metrolist.music.constants.DynamicIslandRoundnessKey
import com.metrolist.music.constants.DynamicIslandShowArtistKey
import com.metrolist.music.constants.DynamicIslandShowButtonsKey
import com.metrolist.music.constants.DynamicIslandShowLyricsKey
import com.metrolist.music.constants.DynamicIslandShowProgressKey
import com.metrolist.music.constants.DynamicIslandSize
import com.metrolist.music.constants.DynamicIslandSizeKey
import com.metrolist.music.constants.DynamicIslandSpinCoverKey
import com.metrolist.music.constants.DynamicIslandStyle
import com.metrolist.music.constants.DynamicIslandStyleKey
import com.metrolist.music.constants.DynamicIslandTapAction
import com.metrolist.music.constants.DynamicIslandTapActionKey
import com.metrolist.music.extensions.toEnum

/** Every look/behaviour option of the Dynamic Island (position and the master switch excluded). */
@Immutable
data class DynamicIslandConfig(
    val style: DynamicIslandStyle = DynamicIslandStyle.CLASSIC,
    /** 0..100 */
    val roundness: Int = DynamicIslandDefaultRoundness,
    /** Compact pill width in dp (before the size multiplier). */
    val pillWidth: Int = DynamicIslandDefaultPillWidth,
    /** Compact pill height in dp (before the size multiplier). */
    val pillHeight: Int = DynamicIslandDefaultPillHeight,
    /** 60..100 */
    val opacity: Int = DynamicIslandDefaultOpacity,
    val size: DynamicIslandSize = DynamicIslandSize.NORMAL,
    val glow: Boolean = true,
    /** 0..100 */
    val glowIntensity: Int = DynamicIslandDefaultGlowIntensity,
    val glowColor: DynamicIslandGlowColor = DynamicIslandGlowColor.COVER,
    val coverShape: DynamicIslandCoverShape = DynamicIslandCoverShape.ROUNDED,
    val spinCover: Boolean = false,
    val compactContent: DynamicIslandCompactContent = DynamicIslandCompactContent.COVER_EQUALIZER,
    val eqStyle: DynamicIslandEqStyle = DynamicIslandEqStyle.BARS,
    val eqColor: DynamicIslandEqColor = DynamicIslandEqColor.COVER,
    val showArtist: Boolean = true,
    val showProgress: Boolean = true,
    val showButtons: Boolean = true,
    /** The synced lyrics: the current line under the artist, the lyric page of the open card, the "cover and lyric line" pill. */
    val showLyrics: Boolean = true,
    /** "Gabung": glued to the top edge of the screen with flared top corners (the notch of the Windows island); false = floating. */
    val attached: Boolean = false,
    /** The lyric line in the pill is split around the camera hole, so the middle of the line is not hidden behind it. */
    val lyricCameraGap: Boolean = false,
    /** The width of the gap left for the camera, in dp (the real size of a camera hole, not the island's scale). */
    val cameraGapDp: Int = DynamicIslandDefaultCameraGap,
    val buttonStyle: DynamicIslandButtonStyle = DynamicIslandButtonStyle.FILLED,
    val layout: DynamicIslandLayout = DynamicIslandLayout.CARD,
    /** Seconds, 2..15 */
    val autoCollapseSec: Int = DynamicIslandDefaultAutoCollapse,
    val tapAction: DynamicIslandTapAction = DynamicIslandTapAction.EXPAND,
    val longPressAction: DynamicIslandLongPressAction = DynamicIslandLongPressAction.DRAG,
    val haptics: Boolean = true,
    val animation: DynamicIslandAnimation = DynamicIslandAnimation.CALM,
    /** Width of the expanded card in dp (before the size multiplier). */
    val expandedWidth: Int = DynamicIslandDefaultExpandedWidth,
    /** 0..100, or [DynamicIslandFollowRoundness] to use the same roundness as the pill. */
    val expandedRoundness: Int = DynamicIslandFollowRoundness,
    /** Percent of the normal cover size in the expanded card. */
    val coverScale: Int = 100,
    /** Percent of the normal text size in the expanded card. */
    val textScale: Int = 100,
    /** The two colours (ARGB) of the "Custom" style, top to bottom. */
    val customTop: Int = DynamicIslandDefaultCustomTop,
    val customBottom: Int = DynamicIslandDefaultCustomBottom,
    /** ARGB, or 0 to let the style choose (white on dark, near black on light). */
    val textColor: Int = 0,
    /** ARGB used for the equaliser, the progress bar and the glow, or 0 to take the cover colour. */
    val accentColor: Int = 0,
    /** [DynamicIslandAutoBorder] = the style's own hairline, 0 = none, then whole dp. */
    val borderWidth: Int = DynamicIslandAutoBorder,
    /** ARGB, or 0 for the style's own edge colour. */
    val borderColor: Int = 0,
    /** 0..100, how soft the blurred cover is in the "Liquid" style. */
    val blur: Int = DynamicIslandDefaultBlur,
    /** 0..100, strength of the lit edge and the top highlight in the glass styles. */
    val shine: Int = DynamicIslandDefaultShine,
) {
    /** Roundness of the expanded card, 0..100. */
    val cardRoundness: Int get() = if (expandedRoundness == DynamicIslandFollowRoundness) roundness else expandedRoundness
}

fun Preferences.toDynamicIslandConfig(): DynamicIslandConfig {
    val d = DynamicIslandConfig()
    return DynamicIslandConfig(
        style = this[DynamicIslandStyleKey].toEnum(d.style),
        roundness = (this[DynamicIslandRoundnessKey] ?: d.roundness).coerceIn(0, 100),
        pillWidth = (this[DynamicIslandPillWidthKey] ?: d.pillWidth).coerceIn(DynamicIslandMinPillWidth, DynamicIslandMaxPillWidth),
        pillHeight = (this[DynamicIslandPillHeightKey] ?: d.pillHeight).coerceIn(DynamicIslandMinPillHeight, DynamicIslandMaxPillHeight),
        opacity = (this[DynamicIslandOpacityKey] ?: d.opacity).coerceIn(DynamicIslandMinOpacity, 100),
        size = this[DynamicIslandSizeKey].toEnum(d.size),
        glow = this[DynamicIslandGlowKey] ?: d.glow,
        glowIntensity = (this[DynamicIslandGlowIntensityKey] ?: d.glowIntensity).coerceIn(0, 100),
        glowColor = this[DynamicIslandGlowColorKey].toEnum(d.glowColor),
        coverShape = this[DynamicIslandCoverShapeKey].toEnum(d.coverShape),
        spinCover = this[DynamicIslandSpinCoverKey] ?: d.spinCover,
        compactContent = this[DynamicIslandCompactContentKey].toEnum(d.compactContent),
        eqStyle = this[DynamicIslandEqStyleKey].toEnum(d.eqStyle),
        eqColor = this[DynamicIslandEqColorKey].toEnum(d.eqColor),
        showArtist = this[DynamicIslandShowArtistKey] ?: d.showArtist,
        showProgress = this[DynamicIslandShowProgressKey] ?: d.showProgress,
        showButtons = this[DynamicIslandShowButtonsKey] ?: d.showButtons,
        showLyrics = this[DynamicIslandShowLyricsKey] ?: d.showLyrics,
        attached = this[DynamicIslandAttachedKey] ?: d.attached,
        lyricCameraGap = this[DynamicIslandLyricCameraGapKey] ?: d.lyricCameraGap,
        cameraGapDp = (this[DynamicIslandCameraGapKey] ?: d.cameraGapDp).coerceIn(DynamicIslandMinCameraGap, DynamicIslandMaxCameraGap),
        buttonStyle = this[DynamicIslandButtonStyleKey].toEnum(d.buttonStyle),
        layout = this[DynamicIslandLayoutKey].toEnum(d.layout),
        autoCollapseSec =
            (this[DynamicIslandAutoCollapseKey] ?: d.autoCollapseSec)
                .coerceIn(DynamicIslandMinAutoCollapse, DynamicIslandMaxAutoCollapse),
        tapAction = this[DynamicIslandTapActionKey].toEnum(d.tapAction),
        longPressAction = this[DynamicIslandLongPressActionKey].toEnum(d.longPressAction),
        haptics = this[DynamicIslandHapticsKey] ?: d.haptics,
        animation = d.animation,
        expandedWidth =
            (this[DynamicIslandExpandedWidthKey] ?: d.expandedWidth)
                .coerceIn(DynamicIslandMinExpandedWidth, DynamicIslandMaxExpandedWidth),
        expandedRoundness =
            (this[DynamicIslandExpandedRoundnessKey] ?: d.expandedRoundness).let {
                if (it == DynamicIslandFollowRoundness) it else it.coerceIn(0, 100)
            },
        coverScale = (this[DynamicIslandCoverScaleKey] ?: d.coverScale).coerceIn(DynamicIslandMinScalePercent, DynamicIslandMaxScalePercent),
        textScale = (this[DynamicIslandTextScaleKey] ?: d.textScale).coerceIn(DynamicIslandMinScalePercent, DynamicIslandMaxScalePercent),
        customTop = this[DynamicIslandCustomTopKey] ?: d.customTop,
        customBottom = this[DynamicIslandCustomBottomKey] ?: d.customBottom,
        textColor = this[DynamicIslandTextColorKey] ?: d.textColor,
        accentColor = this[DynamicIslandAccentColorKey] ?: d.accentColor,
        borderWidth =
            (this[DynamicIslandBorderWidthKey] ?: d.borderWidth).coerceIn(DynamicIslandAutoBorder, DynamicIslandMaxBorderWidth),
        borderColor = this[DynamicIslandBorderColorKey] ?: d.borderColor,
        blur = (this[DynamicIslandBlurKey] ?: d.blur).coerceIn(0, 100),
        shine = (this[DynamicIslandShineKey] ?: d.shine).coerceIn(0, 100),
    )
}

/** Writes every option of [config] (call inside a single DataStore edit). */
fun MutablePreferences.putDynamicIslandConfig(config: DynamicIslandConfig) {
    this[DynamicIslandStyleKey] = config.style.name
    this[DynamicIslandRoundnessKey] = config.roundness
    this[DynamicIslandPillWidthKey] = config.pillWidth
    this[DynamicIslandPillHeightKey] = config.pillHeight
    this[DynamicIslandOpacityKey] = config.opacity
    this[DynamicIslandSizeKey] = config.size.name
    this[DynamicIslandGlowKey] = config.glow
    this[DynamicIslandGlowIntensityKey] = config.glowIntensity
    this[DynamicIslandGlowColorKey] = config.glowColor.name
    this[DynamicIslandCoverShapeKey] = config.coverShape.name
    this[DynamicIslandSpinCoverKey] = config.spinCover
    this[DynamicIslandCompactContentKey] = config.compactContent.name
    this[DynamicIslandEqStyleKey] = config.eqStyle.name
    this[DynamicIslandEqColorKey] = config.eqColor.name
    this[DynamicIslandShowArtistKey] = config.showArtist
    this[DynamicIslandShowProgressKey] = config.showProgress
    this[DynamicIslandShowButtonsKey] = config.showButtons
    this[DynamicIslandShowLyricsKey] = config.showLyrics
    this[DynamicIslandAttachedKey] = config.attached
    this[DynamicIslandLyricCameraGapKey] = config.lyricCameraGap
    this[DynamicIslandCameraGapKey] = config.cameraGapDp
    this[DynamicIslandButtonStyleKey] = config.buttonStyle.name
    this[DynamicIslandLayoutKey] = config.layout.name
    this[DynamicIslandAutoCollapseKey] = config.autoCollapseSec
    this[DynamicIslandTapActionKey] = config.tapAction.name
    this[DynamicIslandLongPressActionKey] = config.longPressAction.name
    this[DynamicIslandHapticsKey] = config.haptics
    this[DynamicIslandExpandedWidthKey] = config.expandedWidth
    this[DynamicIslandExpandedRoundnessKey] = config.expandedRoundness
    this[DynamicIslandCoverScaleKey] = config.coverScale
    this[DynamicIslandTextScaleKey] = config.textScale
    this[DynamicIslandCustomTopKey] = config.customTop
    this[DynamicIslandCustomBottomKey] = config.customBottom
    this[DynamicIslandTextColorKey] = config.textColor
    this[DynamicIslandAccentColorKey] = config.accentColor
    this[DynamicIslandBorderWidthKey] = config.borderWidth
    this[DynamicIslandBorderColorKey] = config.borderColor
    this[DynamicIslandBlurKey] = config.blur
    this[DynamicIslandShineKey] = config.shine
}

/**
 * One-tap looks. A preset replaces the appearance, compact-pill and expanded-card options and keeps
 * the size and the behaviour options (timers, gestures, haptics, animation) the user picked.
 */
enum class DynamicIslandPreset {
    IPHONE,
    GLASS,
    COLOURFUL,
    MINIMAL,
    LIQUID,
    NEON,

    ;

    /**
     * Applies this look to [current]. Only the colours and the glass are replaced (style, opacity, glow, equaliser colour,
     * the custom colours, the border, the blur and the shine); everything the user set up stays: what the pill shows, the
     * cover shape, the layout, the sizes, the placement, the lyrics, the buttons and the behaviour. A look used to replace
     * all of it, so every change of look made the user set the island up again.
     */
    fun applyTo(current: DynamicIslandConfig): DynamicIslandConfig {
        val d = DynamicIslandConfig()
        val base =
            current.copy(
                style = d.style,
                opacity = d.opacity,
                glow = d.glow,
                glowIntensity = d.glowIntensity,
                glowColor = d.glowColor,
                eqColor = d.eqColor,
                customTop = d.customTop,
                customBottom = d.customBottom,
                textColor = d.textColor,
                accentColor = d.accentColor,
                borderWidth = d.borderWidth,
                borderColor = d.borderColor,
                blur = d.blur,
                shine = d.shine,
            )
        return when (this) {
            IPHONE -> base
            GLASS ->
                base.copy(
                    style = DynamicIslandStyle.GLASS,
                    glowIntensity = 35,
                    glowColor = DynamicIslandGlowColor.WHITE,
                    eqColor = DynamicIslandEqColor.WHITE,
                    opacity = 96,
                )
            COLOURFUL ->
                base.copy(
                    style = DynamicIslandStyle.COVER,
                    glowIntensity = 100,
                    glowColor = DynamicIslandGlowColor.COVER,
                    eqColor = DynamicIslandEqColor.WHITE,
                    eqStyle = DynamicIslandEqStyle.WAVE,
                )
            MINIMAL -> base.copy(glow = false)
            LIQUID ->
                base.copy(
                    style = DynamicIslandStyle.LIQUID,
                    // the glass has its own rim and shadow; no coloured halo, no coloured accents
                    glow = false,
                    glowIntensity = 45,
                    glowColor = DynamicIslandGlowColor.COVER,
                    eqColor = DynamicIslandEqColor.WHITE,
                    blur = 70,
                    shine = 85,
                )
            NEON ->
                base.copy(
                    style = DynamicIslandStyle.CUSTOM,
                    customTop = 0xFF0A0F1C.toInt(),
                    customBottom = 0xFF05070D.toInt(),
                    accentColor = 0xFF22E6FF.toInt(),
                    borderWidth = 1,
                    borderColor = 0xFF22E6FF.toInt(),
                    glowIntensity = 80,
                    glowColor = DynamicIslandGlowColor.WHITE,
                    eqStyle = DynamicIslandEqStyle.WAVE,
                    shine = 40,
                )
        }
    }

    fun matches(current: DynamicIslandConfig): Boolean = applyTo(current) == current
}
