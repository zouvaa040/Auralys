package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CsvImportSupportTest {
    @Test
    fun videoIdFromBareIdAndLinks() {
        assertEquals("0CNPR2qNzxk", CsvImportSupport.videoIdOf("0CNPR2qNzxk"))
        assertEquals("-SaUrDhp034", CsvImportSupport.videoIdOf("-SaUrDhp034"))
        assertEquals("_BhhYcSwHQ4", CsvImportSupport.videoIdOf(" _BhhYcSwHQ4 "))
        assertEquals("dQw4w9WgXcQ", CsvImportSupport.videoIdOf("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDAMVM"))
        assertEquals("dQw4w9WgXcQ", CsvImportSupport.videoIdOf("https://youtu.be/dQw4w9WgXcQ?si=abc"))
        assertNull(CsvImportSupport.videoIdOf("Guns N' Roses"))
        assertNull(CsvImportSupport.videoIdOf(""))
        assertNull(CsvImportSupport.videoIdOf("too short"))
    }

    @Test
    fun artistsDropPlayCounts() {
        assertEquals(listOf("Prince", "The Revolution"), CsvImportSupport.artistsOf("462 jt pemutaran; Prince; The Revolution"))
        assertEquals(listOf("Natalie Holt"), CsvImportSupport.artistsOf("6,7 jt pemutaran; Natalie Holt"))
        assertEquals(listOf("Wiz Khalifa"), CsvImportSupport.artistsOf("8,3 M pemutaran; Wiz Khalifa"))
        assertEquals(listOf("Roy Ellis / Mr. Symarip"), CsvImportSupport.artistsOf("761 rb pemutaran; Roy Ellis / Mr. Symarip"))
        assertEquals(listOf("Three Days Grace"), CsvImportSupport.artistsOf("945 jt pemutaran; Three Days Grace"))
        assertEquals(listOf("Swae Lee", "Post Malone"), CsvImportSupport.artistsOf("Swae Lee; Post Malone"))
        assertEquals(listOf("A", "B"), CsvImportSupport.artistsOf("A, B"))
        assertEquals(listOf("Tyler, The Creator"), CsvImportSupport.artistsOf("Tyler, The Creator;"))
        assertEquals(listOf("Adele"), CsvImportSupport.artistsOf("1.2M views; Adele"))
        assertEquals(emptyList<String>(), CsvImportSupport.artistsOf(""))
    }

    @Test
    fun detectsTheMetrolistExportHeader() {
        val rows =
            listOf(
                listOf("Title", "Artist", "Album", "YouTube Video ID"),
                listOf("Welcome To The Jungle", "Guns N' Roses", "", "0CNPR2qNzxk"),
                listOf("It's So Easy", "Guns N' Roses", "", "hykdH43PpX8"),
            )
        val c = CsvImportSupport.detectColumns(rows)
        assertNotNull(c)
        assertEquals(0, c!!.title)
        assertEquals(1, c.artist)
        assertEquals(3, c.video)
        assertEquals(true, c.hasHeader)
    }

    @Test
    fun detectsTheVideoColumnWithoutAHeader() {
        val rows =
            listOf(
                listOf("Welcome To The Jungle", "Guns N' Roses", "0CNPR2qNzxk"),
                listOf("It's So Easy", "Guns N' Roses", "hykdH43PpX8"),
                listOf("Disenchanted", "My Chemical Romance", "95hfNjZictY"),
            )
        val c = CsvImportSupport.detectColumns(rows)
        assertNotNull(c)
        assertEquals(2, c!!.video)
        assertEquals(false, c.hasHeader)
    }

    @Test
    fun noGuessWhenNothingMatches() {
        assertNull(CsvImportSupport.detectColumns(listOf(listOf("a", "b"), listOf("c", "d"))))
        assertNull(CsvImportSupport.detectColumns(emptyList()))
    }
}
