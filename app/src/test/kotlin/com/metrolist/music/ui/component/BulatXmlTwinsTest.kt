/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The `bu_*.xml` drawables (Bulat icons for notification actions, the tile, Android Auto, the cast button and the
 * widget) must stay equal to the vectors of [BulatIconTable]. The first test writes them to build/bulat-xml (copy them
 * into res/drawable after changing a vector); the second fails when a checked-in file is stale or missing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BulatXmlTwinsTest {
    private fun twinName(twin: Int): String = IconSources.drawableNames.getValue(twin)

    private fun exported(): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for ((id, twin) in BulatXmlTwins.entries) {
            val vector = BulatIconTable.vectorFor(id)
            assertNotNull("${IconSources.drawableNames[id]} has no Bulat vector", vector)
            val xml = BulatXmlExport.toXml(vector!!)
            val name = twinName(twin)
            val earlier = out[name]
            assertTrue("$name is exported from two different vectors", earlier == null || earlier == xml)
            out[name] = xml
        }
        return out
    }

    @Test
    fun `writes the twins to build bulat-xml`() {
        val dir = File("build/bulat-xml").apply { mkdirs() }
        for ((name, xml) in exported()) File(dir, "$name.xml").writeText(xml)
    }

    @Test
    fun `the checked in twins equal the vectors`() {
        for ((name, xml) in exported()) {
            val file = File(IconSources.resDir, "drawable/$name.xml")
            assertTrue("${file.path} is missing: run the writer test and copy build/bulat-xml", file.isFile)
            assertEquals("$name.xml is stale", xml, file.readText().replace("\r\n", "\n"))
        }
    }

    @Test
    fun `every twin is used by the table and every file is a twin`() {
        val used = BulatXmlTwins.entries.values.map { twinName(it) }.toSet()
        val files =
            File(IconSources.resDir, "drawable").listFiles().orEmpty()
                .map { it.name.removeSuffix(".xml") }.filter { it.startsWith("bu_") }.toSet()
        assertEquals("bu_ files that no entry uses (or entries without a file)", used, files)
    }
}
