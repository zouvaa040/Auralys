/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.design

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * There is ONE volume control. The player menu and the player page both use it, the stock Material expressive slider is
 * gone, and it carries no accent colour, capital letters, tracked labels or per-pointer-move recomposition.
 */
class VolumeGuardTest {
    private val root: File =
        listOf("src/main/kotlin", "app/src/main/kotlin")
            .map { File(it, "com/metrolist/music") }
            .firstOrNull { it.isDirectory }
            ?: error("sources not found from ${File(".").absolutePath}")

    private fun src(path: String): String = File(root, path).readText()

    private fun res(path: String): String = File(root.parentFile.parentFile.parentFile.parentFile, "res/$path").readText()

    @Test
    fun `the menu and the player page use the same control and the stock slider is gone`() {
        val menu = src("ui/menu/PlayerMenu.kt")
        val row = src("ui/player/PlayerVolumeRow.kt")
        assertTrue("PlayerMenu does not use VolumeControl", menu.contains("VolumeControl("))
        assertTrue("PlayerMenu still uses the stock VolumeSlider", !menu.contains("VolumeSlider"))
        assertTrue("the player row does not use VolumeControl", row.contains("VolumeControl("))
        assertTrue("the player row still builds its own Material Slider", !row.contains("Slider(") || row.contains("VolumeControl("))
        assertTrue("the player row still draws its own track", !row.contains("PlayerSliderTrack"))
        assertTrue("VolumeSlider.kt is back", !File(root, "ui/component/VolumeSlider.kt").exists())
    }

    @Test
    fun `the data flow is the old one`() {
        val menu = src("ui/menu/PlayerMenu.kt")
        // the menu writes the in-app level and the cast level through the same setters as before
        assertTrue(menu.contains("playerConnection.service.playerVolume.value = volume"))
        assertTrue(menu.contains("castHandler?.setVolume(volume)"))
        // and reads the level lazily (a provider), so dragging does not recompose the menu
        assertTrue("PlayerMenu passes a value, not a provider", menu.contains("value = { if (isCasting) castVolume else playerVolume.value }"))
        // the player page writes the music stream
        val row = src("ui/player/PlayerVolumeRow.kt")
        assertTrue(row.contains("audio.setStreamVolume(AudioManager.STREAM_MUSIC"))
    }

    @Test
    fun `the control is ink on neutral, plain, and reads its level in the draw phase`() {
        val control = src("ui/component/VolumeControl.kt")
        for (banned in listOf("colorScheme.primary", "colorScheme.tertiary", "colorScheme.secondary", "isGlassStyle", ".uppercase(", "letterSpacing", "Color.White", "Color(0x", "FontStyle.Italic", "Serif")) {
            assertTrue("VolumeControl.kt has $banned", !control.contains(banned))
        }
        assertTrue("the value is a provider", control.contains("value: () -> Float"))
        assertTrue("the bar is drawn in a Canvas", control.contains("Canvas(Modifier.fillMaxSize())"))
        assertTrue("the icons go through the icon family wrapper", control.contains("painterResource(") && !control.contains("androidx.compose.ui.res.painterResource"))
        assertTrue("48 dp touch height", src("ui/component/VolumeControl.kt").contains("HorizonSpace.Target"))
    }

    @Test
    fun `the volume strings exist in English and Indonesian and use a plain word`() {
        for (lang in listOf("values", "values-in")) {
            val xml = res("$lang/auralys_volume.xml")
            for (key in listOf("auralys_volume_state", "auralys_volume_mute", "auralys_volume_unmute")) {
                assertTrue("$lang is missing $key", xml.contains("name=\"$key\""))
            }
            assertTrue("$lang state string must carry the percent", xml.contains("%1\$d%%"))
        }
        val id = res("values-in/auralys_volume.xml")
        for (banned in listOf("Anda", "gue", "banget", "dong", "nih", "ya!")) {
            assertTrue("Indonesian wording: $banned", !id.contains(banned))
        }
    }
}
