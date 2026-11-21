package com.metrolist.music.ui.component

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.emptyPreferences
import com.metrolist.music.constants.DynamicIslandAutoBorder
import com.metrolist.music.constants.DynamicIslandFollowRoundness
import com.metrolist.music.constants.DynamicIslandLayout
import com.metrolist.music.constants.DynamicIslandStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicIslandConfigTest {
    @Test
    fun `every option survives a round trip through preferences`() {
        val custom =
            DynamicIslandConfig(
                style = DynamicIslandStyle.CUSTOM,
                roundness = 40,
                expandedWidth = 300,
                expandedRoundness = 20,
                coverScale = 120,
                textScale = 90,
                customTop = 0xFF112233.toInt(),
                customBottom = 0xFF445566.toInt(),
                textColor = 0xFFEEEEEE.toInt(),
                accentColor = 0xFF22E6FF.toInt(),
                borderWidth = 2,
                borderColor = 0xFFFF0000.toInt(),
                blur = 25,
                shine = 10,
            )
        val prefs = mutablePreferencesOf().apply { putDynamicIslandConfig(custom) }
        assertEquals(custom, prefs.toDynamicIslandConfig())
    }

    @Test
    fun `nothing stored gives the defaults and an old install keeps its look`() {
        val config = emptyPreferences().toDynamicIslandConfig()
        assertEquals(DynamicIslandConfig(), config)
        assertEquals(DynamicIslandStyle.CLASSIC, config.style)
        assertEquals(DynamicIslandAutoBorder, config.borderWidth)
        assertEquals(0, config.textColor)
        assertEquals(0, config.accentColor)
    }

    @Test
    fun `out of range values are pulled back`() {
        val prefs = mutablePreferencesOf().apply {
            putDynamicIslandConfig(
                DynamicIslandConfig(expandedWidth = 9999, coverScale = 1, textScale = 900, borderWidth = 77, blur = 500, shine = -5),
            )
        }
        val config = prefs.toDynamicIslandConfig()
        assertEquals(420, config.expandedWidth)
        assertEquals(70, config.coverScale)
        assertEquals(140, config.textScale)
        assertEquals(4, config.borderWidth)
        assertEquals(100, config.blur)
        assertEquals(0, config.shine)
    }

    @Test
    fun `the card follows the pill roundness until it gets its own`() {
        val follow = DynamicIslandConfig(roundness = 30)
        assertEquals(DynamicIslandFollowRoundness, follow.expandedRoundness)
        assertEquals(30, follow.cardRoundness)
        assertEquals(80, follow.copy(expandedRoundness = 80).cardRoundness)
        val rounded = DynamicIslandDimens.expandedCorner(follow.copy(roundness = 100))
        val square = DynamicIslandDimens.expandedCorner(follow.copy(roundness = 100, expandedRoundness = 0))
        assertTrue("a card set to 0 is squarer than one set to 100 ($square vs $rounded)", square < rounded)
    }

    @Test
    fun `a bigger cover makes the card and the row taller`() {
        val normal = DynamicIslandConfig()
        val big = DynamicIslandConfig(coverScale = 140)
        assertTrue(DynamicIslandDimens.expandedHeight(big) > DynamicIslandDimens.expandedHeight(normal))
        val rowNormal = normal.copy(layout = DynamicIslandLayout.COMPACT_ROW)
        val rowBig = big.copy(layout = DynamicIslandLayout.COMPACT_ROW)
        assertEquals(72f, DynamicIslandDimens.expandedHeight(rowNormal), 0.01f)
        assertTrue(DynamicIslandDimens.expandedHeight(rowBig) > 72f)
    }

    @Test
    fun `a look never resets what the user set up`() {
        val mine =
            DynamicIslandConfig(
                compactContent = com.metrolist.music.constants.DynamicIslandCompactContent.LYRICS_ONLY,
                coverShape = com.metrolist.music.constants.DynamicIslandCoverShape.CIRCLE,
                spinCover = true,
                layout = DynamicIslandLayout.COMPACT_ROW,
                attached = true,
                pillWidth = 260,
                pillHeight = 44,
                expandedWidth = 300,
                coverScale = 120,
                textScale = 90,
                showArtist = false,
                showProgress = false,
                showButtons = false,
                showLyrics = false,
                roundness = 33,
                expandedRoundness = 55,
                eqStyle = com.metrolist.music.constants.DynamicIslandEqStyle.DOTS,
            )
        DynamicIslandPreset.entries.forEach { preset ->
            val applied = preset.applyTo(mine)
            assertEquals(mine.compactContent, applied.compactContent)
            assertEquals(mine.coverShape, applied.coverShape)
            assertEquals(mine.spinCover, applied.spinCover)
            assertEquals(mine.layout, applied.layout)
            assertEquals(mine.attached, applied.attached)
            assertEquals(mine.pillWidth, applied.pillWidth)
            assertEquals(mine.pillHeight, applied.pillHeight)
            assertEquals(mine.expandedWidth, applied.expandedWidth)
            assertEquals(mine.coverScale, applied.coverScale)
            assertEquals(mine.textScale, applied.textScale)
            assertEquals(mine.showArtist, applied.showArtist)
            assertEquals(mine.showProgress, applied.showProgress)
            assertEquals(mine.showButtons, applied.showButtons)
            assertEquals(mine.showLyrics, applied.showLyrics)
            assertEquals(mine.roundness, applied.roundness)
            assertEquals(mine.expandedRoundness, applied.expandedRoundness)
            assertTrue(preset.matches(applied))
        }
        // and going from one look to another and back keeps the choices
        val liquid = DynamicIslandPreset.LIQUID.applyTo(mine)
        val back = DynamicIslandPreset.LIQUID.applyTo(DynamicIslandPreset.GLASS.applyTo(liquid))
        assertEquals(liquid, back)
    }

    @Test
    fun `presets replace the look but keep size and behaviour`() {
        val current = DynamicIslandConfig(autoCollapseSec = 9, haptics = false, textColor = 0xFF123456.toInt())
        DynamicIslandPreset.entries.forEach { preset ->
            val applied = preset.applyTo(current)
            assertEquals(9, applied.autoCollapseSec)
            assertFalse(applied.haptics)
            assertEquals("a preset clears the custom text colour", 0, applied.textColor)
            assertTrue(preset.matches(applied))
        }
        assertEquals(DynamicIslandStyle.LIQUID, DynamicIslandPreset.LIQUID.applyTo(current).style)
        assertEquals(DynamicIslandStyle.CUSTOM, DynamicIslandPreset.NEON.applyTo(current).style)
    }
}
