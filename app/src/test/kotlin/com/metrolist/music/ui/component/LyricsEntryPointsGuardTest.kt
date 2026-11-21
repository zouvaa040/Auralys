/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Review 2026-11-12, findings 1, 2, 4: every way to the source picker is reachable for a song without lyrics, the
 * not-found retry and the 4.3.4 re-check run in the one host of both lyrics views, and no screen stores a lookup's
 * answer by itself (only LyricsHelper / LyricsStore write lyrics found automatically). Reads the Kotlin sources.
 */
class LyricsEntryPointsGuardTest {
    private val root: File =
        listOf("src/main/kotlin", "app/src/main/kotlin")
            .map { File(it, "com/metrolist/music") }
            .firstOrNull { it.isDirectory }
            ?: error("sources not found from ${File(".").absolutePath}")

    private fun src(path: String): String = File(root, path).readText()

    private val inline: String
        get() {
            val player = src("ui/player/Player.kt")
            return player.substring(player.indexOf("fun InlineLyricsView("), player.indexOf("private fun PlayerMoreMenuButton("))
        }

    @Test
    fun `the inline lyrics view hands loading and not found to the lyrics views`() {
        val view = inline
        // before: its own spinner for null and its own text for LYRICS_NOT_FOUND, so the views' empty state never showed
        assertFalse("own not-found text", Regex("""lyrics\s*==\s*LyricsEntity\.LYRICS_NOT_FOUND\s*->\s*\{""").containsMatchIn(view))
        assertFalse("own spinner", view.contains("ContainedLoadingIndicator()"))
        assertTrue(view.contains("Lyrics(\n"))
        // the only state it draws itself: no row and no connection
        assertTrue(view.contains("row == null && noConnection"))
        // the retry and the re-check run here, for both views
        assertTrue(view.contains("lyricsHelper.retryNotFound(meta)"))
        assertTrue(view.contains("lyricsHelper.recheckIfListed(meta, current)"))
        assertTrue(view.contains("LyricsStore.Mode.FILL"))
    }

    @Test
    fun `both lyrics views show the empty state with Choose source and Try again`() {
        for (f in listOf("ui/component/ExperimentalLyrics.kt", "ui/component/OriginalLyrics.kt")) {
            val text = src(f)
            assertTrue(f, text.contains("LyricsNotFoundState("))
            assertTrue(f, text.contains("onChooseSource = "))
            assertTrue(f, text.contains("sourceViewModel.retryNow(meta)"))
            assertTrue("$f listens for the menu's request", text.contains("LyricsUiEvents.Event.OPEN_SOURCES"))
            assertTrue("$f passes the shown text to the picker", text.contains("shownLyrics = lyricsEntity?.lyrics"))
        }
    }

    @Test
    fun `the menu row opens the picker itself when no lyrics view takes the request`() {
        val menu = src("ui/menu/LyricsMenu.kt")
        assertTrue(menu.contains("if (LyricsUiEvents.send(LyricsUiEvents.Event.OPEN_SOURCES)) onDismiss() else showSourcesHere = true"))
        assertTrue(menu.contains("if (showSourcesHere) {"))
        assertTrue(menu.contains("LyricsSourceSheet("))
        // timing for lyrics without timestamps says so
        assertTrue(menu.contains("R.string.auralys_lyrics_timing_unsynced"))
    }

    @Test
    fun `lookups are stored only through LyricsHelper, never deleted first`() {
        val menuVm = src("viewmodels/LyricsMenuViewModel.kt")
        assertFalse("Refetch deleted the row first", menuVm.contains("lyricsEntity?.let(::delete)"))
        assertTrue(menuVm.contains("lyricsHelper.refetch(mediaMetadata)"))
        val sourceVm = src("viewmodels/LyricsSourceViewModel.kt")
        assertFalse("the picker wrote rows itself", sourceVm.contains("upsert("))
        assertFalse(inline.contains("upsert("))
        // no bulk delete of lyrics anywhere
        assertFalse(src("db/DatabaseDao.kt").contains("DELETE FROM lyrics WHERE LOWER(provider)"))
        for (f in listOf("lyrics/LyricsHelper.kt", "lyrics/LyricsRecheck434.kt", "lyrics/LyricsStore.kt")) {
            assertFalse("$f deletes lyrics", Regex("""\.delete\(\s*LyricsEntity|deleteAutoFetched""").containsMatchIn(src(f)))
        }
    }

    @Test
    fun `the picker offers previous lyrics and use anyway, in both languages`() {
        val sheet = src("ui/component/LyricsSourceSheet.kt")
        assertTrue(sheet.contains("R.string.auralys_lyrics_source_previous"))
        assertTrue(sheet.contains("R.string.auralys_lyrics_source_use_anyway"))
        assertTrue(sheet.contains("useAnyway = true"))
        val res = File(root.parentFile.parentFile.parentFile.parentFile, "res")
        val id = File(res, "values-in/auralys_lyrics_434.xml").readText()
        assertTrue(id.contains(">Lirik sebelumnya<"))
        assertTrue(id.contains(">Pakai tetap<"))
        val en = File(res, "values/auralys_lyrics_434.xml").readText()
        for (name in listOf("auralys_lyrics_source_previous", "auralys_lyrics_source_use_anyway", "auralys_lyrics_auto_nothing", "auralys_lyrics_refetch_nothing_new", "auralys_lyrics_timing_unsynced")) {
            assertTrue(name, en.contains("name=\"$name\"") && id.contains("name=\"$name\""))
        }
        for (banned in listOf("nggak", "udah", "gak ", "kalo", "bikin")) assertFalse(banned, id.contains(banned))
    }
}
