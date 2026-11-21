/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUpdateTest {
    // the real markup of the download page: a label and a link per platform
    private val page =
        """<a class="btn ghost" href="https://www.mediafire.com/file/x/Auralys-Setup-1.5.5.exe/file"><span>Windows</span><span>v1.5.5 · .exe</span></a>
           <a class="btn ghost" href="https://www.mediafire.com/file/y/Auralys-4.3.2.apk/file"><span>Android</span><span>v4.3.2 · .apk</span></a>"""

    @Test
    fun readsTheAndroidVersionNotTheWindowsOne() {
        assertEquals("4.3.2", WebUpdate.parseAndroid(page))
    }

    @Test
    fun usesTheHigherOfLabelAndLinkName() {
        assertEquals("4.4.0", WebUpdate.parseAndroid(page.replace("Auralys-4.3.2.apk", "Auralys-4.4.0.apk")))
        assertEquals("4.5.1", WebUpdate.parseAndroid(page.replace("v4.3.2", "v4.5.1")))
    }

    @Test
    fun namesNoVersionWhenThePageHasNone() {
        assertNull(WebUpdate.parseAndroid("<html>nothing here</html>"))
    }

    @Test
    fun comparesPartByPart() {
        assertTrue(WebUpdate.compare("4.3.10", "4.3.9") > 0)
        assertEquals(0, WebUpdate.compare("4.3.2", "4.3.2"))
        assertTrue(WebUpdate.compare("4.3.2", "4.3.3") < 0)
        assertTrue(WebUpdate.compare("5.0", "4.9.9") > 0)
    }
}
