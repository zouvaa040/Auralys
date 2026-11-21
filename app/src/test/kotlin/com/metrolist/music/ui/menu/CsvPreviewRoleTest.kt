/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preview of the CSV column mapper tells the header row and the three columns apart without any hue (REVIEW-2026-11-07
 * F4): the Material container slots it used before are all one grey in the Horizon scheme.
 */
class CsvPreviewRoleTest {
    @Test
    fun `each column gets the role the user picked, other columns none`() {
        // title 1, artist 0, url 3
        assertEquals(CsvPreviewRole.ARTIST, csvPreviewRole(0, titleColumnIndex = 1, artistColumnIndex = 0, urlColumnIndex = 3))
        assertEquals(CsvPreviewRole.TITLE, csvPreviewRole(1, 1, 0, 3))
        assertNull(csvPreviewRole(2, 1, 0, 3))
        assertEquals(CsvPreviewRole.URL, csvPreviewRole(3, 1, 0, 3))
    }

    @Test
    fun `no url column means no url role, also for the column at index minus one`() {
        assertNull(csvPreviewRole(-1, 1, 0, -1))
        assertNull(csvPreviewRole(2, 1, 0, -1))
    }

    @Test
    fun `the title wins when one column is picked twice`() {
        assertEquals(CsvPreviewRole.TITLE, csvPreviewRole(1, 1, 1, -1))
    }

    @Test
    fun `the header row, the title column and the artist column have three different strengths of ink`() {
        val header = csvPreviewFillAlpha(isHeader = true, role = null)
        val title = csvPreviewFillAlpha(isHeader = false, role = CsvPreviewRole.TITLE)
        val artist = csvPreviewFillAlpha(isHeader = false, role = CsvPreviewRole.ARTIST)
        val other = csvPreviewFillAlpha(isHeader = false, role = null)
        assertEquals(4, setOf(header, title, artist, other).size)
        assertTrue("header > title > artist > nothing", header > title && title > artist && artist > other)
        // a header cell keeps the header look whatever its column is for
        assertEquals(header, csvPreviewFillAlpha(isHeader = true, role = CsvPreviewRole.TITLE))
        // the url column has no fill: the outline and the label tell it
        assertEquals(other, csvPreviewFillAlpha(isHeader = false, role = CsvPreviewRole.URL), 0f)
        assertNotEquals(title, artist)
    }

    @Test
    fun `the strongest fill stays light enough for the ink text on it`() {
        // ink at 22 percent over the page: the text (ink) keeps far more than 4.5 to 1
        assertTrue(csvPreviewFillAlpha(isHeader = true, role = null) <= 0.3f)
    }
}
