/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The Horizon type scale (DESIGN-V2.md 2.4): one family, eleven roles, hierarchy by size and weight only. No serif, no
 * italics, no capitals, no tracking except a slight negative on the two large titles.
 */

package com.metrolist.music.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** One role of the scale, kept as numbers so the scale can be tested without fonts. */
class HorizonRole(
    val name: String,
    val slots: List<String>,
    val sizeSp: Float,
    val lineSp: Float,
    val weight: FontWeight,
    val trackingSp: Float = 0f,
)

object HorizonScale {
    val Display = HorizonRole("Display", listOf("displayLarge", "displayMedium"), 34f, 38f, FontWeight.ExtraBold, -0.3f)
    val PageTitle = HorizonRole("Page title", listOf("headlineLarge", "displaySmall"), 28f, 34f, FontWeight.Bold, -0.2f)
    val Section = HorizonRole("Section", listOf("headlineSmall", "titleLarge"), 20f, 26f, FontWeight.Bold)
    val RowTitle = HorizonRole("Row title", listOf("titleMedium"), 16f, 22f, FontWeight.SemiBold)
    val RowTitleSmall = HorizonRole("Row title small", listOf("titleSmall"), 14f, 20f, FontWeight.SemiBold)
    val Body = HorizonRole("Body", listOf("bodyLarge"), 16f, 24f, FontWeight.Normal)
    val Secondary = HorizonRole("Secondary", listOf("bodyMedium"), 14f, 20f, FontWeight.Medium)
    val Caption = HorizonRole("Caption", listOf("bodySmall"), 12f, 16f, FontWeight.Medium)
    val Button = HorizonRole("Button", listOf("labelLarge"), 15f, 20f, FontWeight.SemiBold)
    val Chip = HorizonRole("Chip and tab", listOf("labelMedium"), 13f, 16f, FontWeight.SemiBold)
    val NavLabel = HorizonRole("Nav label", listOf("labelSmall"), 11f, 14f, FontWeight.SemiBold)

    /** Slot headlineMedium has no role of its own: the player title size. */
    val Headline = HorizonRole("Headline", listOf("headlineMedium"), 24f, 30f, FontWeight.Bold)

    val roles = listOf(Display, PageTitle, Section, RowTitle, RowTitleSmall, Body, Secondary, Caption, Button, Chip, NavLabel)
}

/** The scale in Material slots. Pass another [family] to keep the same sizes in a font the user picked. */
fun horizonTypography(family: FontFamily = HorizonFontFamily): Typography {
    fun style(role: HorizonRole, tabular: Boolean = false) =
        TextStyle(
            fontFamily = family,
            fontWeight = role.weight,
            fontSize = role.sizeSp.sp,
            lineHeight = role.lineSp.sp,
            letterSpacing = role.trackingSp.sp,
            fontFeatureSettings = if (tabular) "tnum" else null,
        )
    return Typography(
        displayLarge = style(HorizonScale.Display),
        displayMedium = style(HorizonScale.Display),
        displaySmall = style(HorizonScale.PageTitle),
        headlineLarge = style(HorizonScale.PageTitle),
        headlineMedium = style(HorizonScale.Headline),
        headlineSmall = style(HorizonScale.Section),
        titleLarge = style(HorizonScale.Section),
        titleMedium = style(HorizonScale.RowTitle),
        titleSmall = style(HorizonScale.RowTitleSmall),
        bodyLarge = style(HorizonScale.Body),
        bodyMedium = style(HorizonScale.Secondary),
        // counts and timestamps: digits of equal width (the font has the tnum feature)
        bodySmall = style(HorizonScale.Caption, tabular = true),
        labelLarge = style(HorizonScale.Button),
        labelMedium = style(HorizonScale.Chip),
        labelSmall = style(HorizonScale.NavLabel),
    )
}
