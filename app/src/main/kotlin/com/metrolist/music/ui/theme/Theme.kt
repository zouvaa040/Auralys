/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Theme entry point of the "Horizon" design (DESIGN-V2.md): ink on neutral surfaces, one typeface, colour only from
 * the cover. The Material colour slots are mapped to neutral tokens ([Tokens.kt]), so every default Button, Switch,
 * Slider, progress bar, chip and every `colorScheme.primary` in the app turns ink in one place. A user who picked a
 * colour of their own ("Kustom") still gets a seeded scheme, as before.
 */

package com.metrolist.music.ui.theme

import android.graphics.Bitmap
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme
import com.materialkolor.score.Score

/**
 * The seed colour that means "no colour chosen" in the stored settings. It is only a marker so old data stays valid;
 * it is never drawn (it used to be the terracotta brand colour of the paper look).
 */
val BuiltInThemeColor = Color(0xFFC2623F)

/** Old name of [BuiltInThemeColor], kept so files owned by other work streams keep compiling. */
val DefaultThemeColor: Color get() = BuiltInThemeColor

/** The one radius scale as Material shapes. */
val HorizonShapes = Shapes(
    extraSmall = RoundedCornerShape(HorizonRadius.Xs),
    small = RoundedCornerShape(HorizonRadius.Sm),
    medium = RoundedCornerShape(HorizonRadius.Md),
    large = RoundedCornerShape(HorizonRadius.Lg),
    extraLarge = RoundedCornerShape(HorizonRadius.Xl),
)

/** Where colour comes from. NETRAL: nowhere. SAMPUL: the cover that plays (never a control). KUSTOM: the user's own seed colour. */
enum class ColorMode {
    NETRAL,
    SAMPUL,
    KUSTOM,
}

/** Maps the neutral tokens onto the Material slots (DESIGN-V2.md 2.3, "Slot mapping"). */
fun horizonColorScheme(c: HorizonColors): ColorScheme {
    val errorContainer = c.danger.copy(alpha = 0.12f).compositeOver(c.bg)
    val surfaceDim = if (c.dark) c.bg else c.surface2
    return if (c.dark) {
        darkColorScheme(
            primary = c.ink,
            onPrimary = c.onInk,
            primaryContainer = c.surface3,
            onPrimaryContainer = c.ink,
            inversePrimary = c.bg,
            secondary = c.text2,
            onSecondary = c.onInk,
            secondaryContainer = c.surface3,
            onSecondaryContainer = c.ink,
            tertiary = c.text2,
            onTertiary = c.onInk,
            tertiaryContainer = c.surface3,
            onTertiaryContainer = c.ink,
            background = c.bg,
            onBackground = c.ink,
            surface = c.bg,
            onSurface = c.ink,
            surfaceVariant = c.surface2,
            onSurfaceVariant = c.text2,
            surfaceTint = Color.Transparent,
            inverseSurface = c.ink,
            inverseOnSurface = c.bg,
            error = c.danger,
            onError = c.onDanger,
            errorContainer = errorContainer,
            onErrorContainer = c.ink,
            outline = c.text3,
            outlineVariant = c.divider,
            surfaceDim = surfaceDim,
            surfaceBright = c.surface1,
            surfaceContainerLowest = c.bg,
            surfaceContainerLow = c.surface1,
            surfaceContainer = c.surface1,
            surfaceContainerHigh = c.surface2,
            surfaceContainerHighest = c.surface3,
        )
    } else {
        lightColorScheme(
            primary = c.ink,
            onPrimary = c.onInk,
            primaryContainer = c.surface3,
            onPrimaryContainer = c.ink,
            inversePrimary = c.bg,
            secondary = c.text2,
            onSecondary = c.onInk,
            secondaryContainer = c.surface3,
            onSecondaryContainer = c.ink,
            tertiary = c.text2,
            onTertiary = c.onInk,
            tertiaryContainer = c.surface3,
            onTertiaryContainer = c.ink,
            background = c.bg,
            onBackground = c.ink,
            surface = c.bg,
            onSurface = c.ink,
            surfaceVariant = c.surface2,
            onSurfaceVariant = c.text2,
            surfaceTint = Color.Transparent,
            inverseSurface = c.ink,
            inverseOnSurface = c.bg,
            error = c.danger,
            onError = c.onDanger,
            errorContainer = errorContainer,
            onErrorContainer = c.ink,
            outline = c.text3,
            outlineVariant = c.divider,
            surfaceDim = surfaceDim,
            surfaceBright = c.surface1,
            surfaceContainerLowest = c.bg,
            surfaceContainerLow = c.surface1,
            surfaceContainer = c.surface1,
            surfaceContainerHigh = c.surface2,
            surfaceContainerHighest = c.surface3,
        )
    }
}

private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    return Color(
        red = red * a + background.red * (1 - a),
        green = green * a + background.green * (1 - a),
        blue = blue * a + background.blue * (1 - a),
        alpha = 1f,
    )
}

/** Makes a seeded scheme ("Kustom" with the Kaca style) behave like glass: translucent containers. */
internal fun ColorScheme.asGlass(dark: Boolean): ColorScheme = if (dark) {
    copy(
        surfaceContainerLowest = Color(0x08FFFFFF),
        surfaceContainerLow = Color(0x0FFFFFFF),
        surfaceContainer = Color(0x17FFFFFF),
        surfaceContainerHigh = Color(0x1FFFFFFF),
        surfaceContainerHighest = Color(0x29FFFFFF),
        outlineVariant = Color(0x2EFFFFFF),
    )
} else {
    copy(
        surfaceContainerLowest = Color(0x66FFFFFF),
        surfaceContainerLow = Color(0x80FFFFFF),
        surfaceContainer = Color(0x99FFFFFF),
        surfaceContainerHigh = Color(0xB3FFFFFF),
        surfaceContainerHighest = Color(0xCCFFFFFF),
        outlineVariant = Color(0x331B1916),
    )
}

@Composable
fun MetrolistTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    pureBlack: Boolean = false,
    themeColor: Color = BuiltInThemeColor,
    style: UiStyle = UiStyle.DEFAULT,
    appFont: AppFont = AppFont.STYLE,
    fontWeightShift: Int = 0,
    iosMotion: Boolean = false,
    iconFamily: com.metrolist.music.ui.component.IconFamily = com.metrolist.music.ui.component.IconFamily.DEFAULT,
    colorMode: ColorMode = ColorMode.NETRAL,
    coverColor: Color? = null,
    /** "Efek berbayang": how much the cover colour washes into the page background. Independent of [colorMode]. */
    shadeLevel: CoverShadeLevel = CoverShadeLevel.OFF,
    content: @Composable () -> Unit,
) {
    val glass = style == UiStyle.GLASS
    val horizon = remember(darkTheme, pureBlack) { horizonColors(darkTheme, pureBlack) }
    val seeded = colorMode == ColorMode.KUSTOM && themeColor != BuiltInThemeColor

    val colorScheme =
        if (seeded) {
            val dyn = rememberDynamicColorScheme(
                seedColor = themeColor,
                isDark = darkTheme,
                specVersion = ColorSpec.SpecVersion.SPEC_2025,
                style = PaletteStyle.TonalSpot,
            )
            val styled = if (glass) dyn.asGlass(darkTheme) else dyn
            remember(styled, pureBlack, darkTheme) { if (darkTheme && pureBlack) styled.pureBlack(true) else styled }
        } else {
            remember(horizon) { horizonColorScheme(horizon) }
        }

    val coverTint = remember(colorMode, coverColor) {
        if (colorMode == ColorMode.SAMPUL && coverColor != null) clampCoverTint(coverColor) else null
    }

    // the cover shade needs the cover colour whatever the colour mode is (the mode only decides about hero washes)
    val coverShade = rememberCoverShade(shadeLevel, coverColor, darkTheme, pureBlack && darkTheme, style == UiStyle.EDITORIAL)

    val assets = androidx.compose.ui.platform.LocalContext.current.assets
    val typography = remember(appFont, fontWeightShift) {
        horizonTypography().withAppFont(appFont, fontWeightShift, assets)
    }
    CompositionLocalProvider(
        LocalUiStyle provides style,
        LocalHorizon provides horizon,
        LocalCoverTint provides coverTint,
        LocalCoverTintEnabled provides (colorMode == ColorMode.SAMPUL),
        LocalCoverShade provides coverShade,
        com.metrolist.music.ui.component.LocalIosMotion provides iosMotion,
        com.metrolist.music.ui.component.LocalIconFamily provides iconFamily,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = HorizonShapes,
            typography = typography,
            // iOS animations: the rubber-band overscroll for every list below (the platform stretch stays when the option is off)
            content = { com.metrolist.music.ui.component.IosOverscrollHost(enabled = iosMotion, content = content) },
        )
    }
}

/** Dominant colour of a cover for the old "dynamic" path; a cover with nothing usable gives a neutral grey, not a brand colour. */
fun Bitmap.extractThemeColor(): Color = Color(
    Palette.from(this)
        .maximumColorCount(8)
        .generate()
        .rankedColors(1, HorizonLight.text3.toArgb())
        .first()
)

private const val NoCoverColorSentinel = 0xFF010203.toInt()

/** The colour that [Modifier.coverWash] and the veils use, or null when the cover has no usable colour. */
fun Bitmap.extractCoverTint(): Color? {
    val picked = Palette.from(this)
        .maximumColorCount(8)
        .generate()
        .rankedColors(1, NoCoverColorSentinel)
        .first()
    return if (picked == NoCoverColorSentinel) null else Color(picked)
}

internal fun Palette.rankedColors(
    desiredColorCount: Int,
    fallbackColor: Int,
): List<Int> = Score.score(
    swatches.associate { it.rgb to it.population },
    desiredColorCount,
    fallbackColor,
    true,
)

fun ColorScheme.pureBlack(apply: Boolean) =
    if (apply) copy(
        surface = Color.Black,
        background = Color.Black
    ) else this

val ColorSaver = object : Saver<Color, Int> {
    override fun restore(value: Int): Color = Color(value)
    override fun SaverScope.save(value: Color): Int = value.toArgb()
}
