/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Typeface choice for the whole app. Apple's own faces (SF Pro, Helvetica Neue, New York, ...) are proprietary and
 * cannot be shipped in an app, so each "iOS" entry is the closest open-licence (SIL OFL / Apache) lookalike.
 * All of them are variable fonts, so any weight from Thin to Black is available.
 */

package com.metrolist.music.ui.theme

import android.content.res.AssetManager
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

enum class AppFont(
    val label: String,
    val note: String,
    val asset: String? = null,
) {
    STYLE("Default", "Plus Jakarta Sans, the font of Auralys"),
    IOS("iOS (SF Pro)", "Inter, the closest open font to San Francisco", "fonts/Inter.ttf"),
    IOS_ROUNDED("iOS Rounded", "Nunito, like SF Rounded", "fonts/Nunito.ttf"),
    HELVETICA("Helvetica", "Arimo, a Helvetica-style grotesque", "fonts/Arimo.ttf"),
    HELVETICA_NEUE("Helvetica Neue", "Albert Sans, clean and neutral", "fonts/AlbertSans.ttf"),
    AVENIR("Avenir Next", "Nunito Sans, soft and friendly", "fonts/NunitoSans.ttf"),
    FUTURA("Futura", "Jost, geometric", "fonts/Jost.ttf"),
    SF_COMPACT("SF Compact", "Figtree, compact and modern", "fonts/Figtree.ttf"),
    NEW_YORK("New York", "Newsreader, serif", "fonts/Newsreader.ttf"),
    GEORGIA("Georgia", "Gelasio, a Georgia-style serif", "fonts/Gelasio.ttf"),
    PLAYFAIR("Playfair", "High-contrast serif", "fonts/PlayfairDisplay.ttf"),
    GROTESK("Space Grotesk", "Techy grotesque", "fonts/SpaceGrotesk.ttf"),
    RUBIK("Rubik", "Rounded geometric, great in bold", "fonts/Rubik.ttf"),
    DM_SANS("DM Sans", "Geometric sans", "fonts/DMSans.ttf"),
    MENLO("Menlo (SF Mono)", "JetBrains Mono, monospace", "fonts/JetBrainsMono.ttf"),
    MARKER("Noteworthy", "Caveat, handwriting", "fonts/Caveat.ttf"),
    SYSTEM("System", "Your phone's own font"),
}

private val weightSteps = listOf(100, 200, 300, 400, 500, 600, 700, 800, 900)
private val familyCache = HashMap<AppFont, FontFamily>()

/** Family for [font], one entry per weight so Compose picks the exact weight from the variable file. */
fun AppFont.family(assets: AssetManager): FontFamily? {
    if (this == AppFont.STYLE) return null
    if (this == AppFont.SYSTEM) return FontFamily.Default
    familyCache[this]?.let { return it }
    val path = asset ?: return null
    val built =
        try {
            FontFamily(
                weightSteps.map { w ->
                    Font(path, assets, FontWeight(w), FontStyle.Normal, FontVariation.Settings(FontVariation.weight(w)))
                },
            )
        } catch (_: Throwable) {
            return null // a broken asset must never take the app down: fall back to the style's own font
        }
    familyCache[this] = built
    return built
}

/** Label for the weight shift slider (-3 .. +3 steps of 100). */
fun weightShiftLabel(shift: Int): String =
    when {
        shift <= -3 -> "Thin"
        shift == -2 -> "Light"
        shift == -1 -> "Slightly light"
        shift == 0 -> "Default"
        shift == 1 -> "Medium"
        shift == 2 -> "Bold"
        else -> "Black"
    }

private fun TextStyle.restyled(
    family: FontFamily?,
    shift: Int,
): TextStyle {
    val base = (fontWeight ?: FontWeight.Normal).weight
    val w = (((base + shift * 100).coerceIn(100, 900) + 50) / 100) * 100
    return copy(fontFamily = family ?: fontFamily, fontWeight = FontWeight(w.coerceIn(100, 900)))
}

/** Applies the chosen typeface and weight shift to every Material text style. */
fun Typography.withAppFont(
    font: AppFont,
    shift: Int,
    assets: AssetManager,
): Typography {
    val family = font.family(assets)
    if (family == null && shift == 0) return this
    return copy(
        displayLarge = displayLarge.restyled(family, shift),
        displayMedium = displayMedium.restyled(family, shift),
        displaySmall = displaySmall.restyled(family, shift),
        headlineLarge = headlineLarge.restyled(family, shift),
        headlineMedium = headlineMedium.restyled(family, shift),
        headlineSmall = headlineSmall.restyled(family, shift),
        titleLarge = titleLarge.restyled(family, shift),
        titleMedium = titleMedium.restyled(family, shift),
        titleSmall = titleSmall.restyled(family, shift),
        bodyLarge = bodyLarge.restyled(family, shift),
        bodyMedium = bodyMedium.restyled(family, shift),
        bodySmall = bodySmall.restyled(family, shift),
        labelLarge = labelLarge.restyled(family, shift),
        labelMedium = labelMedium.restyled(family, shift),
        labelSmall = labelSmall.restyled(family, shift),
    )
}
