package com.metrolist.music.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the settings search ranks and filters: title beats group beats category beats description, every word must match. */
class SettingsSearchTest {
    private fun setting(
        title: String,
        description: String? = null,
        section: String? = null,
        category: String = "Suara & pemutaran",
        route: String = "settings/player",
    ) = SearchableSetting(title, description, section, category, route)

    private val entries =
        listOf(
            setting("Kualitas audio"),
            setting("Audio mono", description = "Gabungkan kanal kiri dan kanan"),
            setting("Bersihkan cache lagu", category = "Penyimpanan", route = "settings/storage"),
            setting("Ukuran maksimal", description = "Batas cache gambar", category = "Penyimpanan", route = "settings/storage"),
            setting("Crossfade", section = "Pemutar"),
            setting("Mode Café"),
        )

    @Test
    fun `a blank query finds nothing`() {
        assertTrue(rankSettings("", entries).isEmpty())
        assertTrue(rankSettings("   ", entries).isEmpty())
    }

    @Test
    fun `a title that starts with the word beats one that only has it later`() {
        val titles = rankSettings("audio", entries).map { it.title }
        assertEquals(listOf("Audio mono", "Kualitas audio"), titles)
    }

    @Test
    fun `every word has to match`() {
        val titles = rankSettings("audio mono", entries).map { it.title }
        assertEquals(listOf("Audio mono"), titles)
        assertTrue(rankSettings("audio storage", entries).isEmpty())
    }

    @Test
    fun `case and accents do not matter`() {
        assertEquals(listOf("Mode Café"), rankSettings("CAFE", entries).map { it.title })
        assertEquals(listOf("Mode Café"), rankSettings("café", entries).map { it.title })
    }

    @Test
    fun `a title match beats a match in the description`() {
        val titles = rankSettings("cache", entries).map { it.title }
        assertEquals(listOf("Bersihkan cache lagu", "Ukuran maksimal"), titles)
    }

    @Test
    fun `the group and the category are searched too`() {
        assertEquals(listOf("Crossfade"), rankSettings("pemutar", entries.filter { it.title == "Crossfade" }).map { it.title })
        val byCategory = rankSettings("penyimpanan", entries).map { it.title }
        assertEquals(listOf("Bersihkan cache lagu", "Ukuran maksimal"), byCategory)
    }

    @Test
    fun `punctuation in the query is ignored`() {
        assertEquals(listOf("Kualitas audio"), rankSettings("kualitas-audio!", entries).map { it.title })
    }

    @Test
    fun `equal scores keep the order of the pages`() {
        val same = listOf(setting("Tema satu"), setting("Tema dua"), setting("Tema tiga"))
        assertEquals(listOf("Tema satu", "Tema dua", "Tema tiga"), rankSettings("tema", same).map { it.title })
    }

    @Test
    fun `the list is capped`() {
        val many = (1..100).map { setting("Pilihan $it") }
        assertEquals(40, rankSettings("pilihan", many).size)
        assertEquals(5, rankSettings("pilihan", many, limit = 5).size)
    }
}
