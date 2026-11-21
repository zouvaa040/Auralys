/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.metrolist.music.R

val bbhBartle = FontFamily(
    Font(R.font.bbh_bartle_regular, FontWeight.Normal)
)

/**
 * Plus Jakarta Sans (Tokotype, SIL Open Font License 1.1), the one typeface of the Horizon design. A variable font
 * with a weight axis of 200 to 800, so one file serves every weight in [horizonTypography].
 * Licence text: app/src/main/assets/licenses/PlusJakartaSans-OFL.txt, credit in NOTICE and in the About screen.
 */
val HorizonFontFamily = FontFamily(
    (200..800 step 100).map { w ->
        Font(
            R.font.plus_jakarta_sans,
            FontWeight(w),
            FontStyle.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(w)),
        )
    },
)
