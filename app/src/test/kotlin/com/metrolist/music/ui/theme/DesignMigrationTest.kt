/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme

import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.metrolist.music.constants.ColorModeKey
import com.metrolist.music.constants.CoverShadeKey
import com.metrolist.music.constants.DarkModeKey
import com.metrolist.music.constants.DesignVersionKey
import com.metrolist.music.constants.DynamicThemeKey
import com.metrolist.music.constants.IconFamilyKey
import com.metrolist.music.constants.PureBlackKey
import com.metrolist.music.constants.SelectedThemeColorKey
import com.metrolist.music.constants.UiStyleKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Every stored combination of the old appearance choices and what the Horizon migration makes of it. */
class DesignMigrationTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val builtIn = BuiltInThemeColor.toArgb()
    private val custom = 0xFF3A7BB0.toInt()

    private fun stored(style: String?, seed: Int?, dynamic: Boolean?, version: Int? = null) =
        StoredDesign(uiStyle = style, selectedThemeColor = seed, dynamicTheme = dynamic, designVersion = version)

    @Test
    fun `style table`() {
        // never chose (absent) or unreadable -> GLASS ("Kaca")
        assertEquals(UiStyle.GLASS, DesignMigration.migrate(stored(null, null, null)).uiStyle)
        assertEquals(UiStyle.GLASS, DesignMigration.migrate(stored("", null, null)).uiStyle)
        assertEquals(UiStyle.GLASS, DesignMigration.migrate(stored("PAPER", null, null)).uiStyle)
        assertEquals(UiStyle.GLASS, DesignMigration.migrate(stored("glass", null, null)).uiStyle)
        // explicit choices are kept
        assertEquals(UiStyle.EDITORIAL, DesignMigration.migrate(stored("EDITORIAL", null, null)).uiStyle)
        assertEquals(UiStyle.GLASS, DesignMigration.migrate(stored("GLASS", null, null)).uiStyle)
    }

    @Test
    fun `colour table`() {
        // built-in seed (or none) and dynamic off or absent -> NETRAL
        assertEquals(ColorMode.NETRAL, DesignMigration.migrate(stored(null, null, null)).colorMode)
        assertEquals(ColorMode.NETRAL, DesignMigration.migrate(stored(null, null, false)).colorMode)
        assertEquals(ColorMode.NETRAL, DesignMigration.migrate(stored(null, builtIn, null)).colorMode)
        assertEquals(ColorMode.NETRAL, DesignMigration.migrate(stored(null, builtIn, false)).colorMode)
        // built-in seed and dynamic on (what the glass style switched on by itself) -> SAMPUL
        assertEquals(ColorMode.SAMPUL, DesignMigration.migrate(stored(null, builtIn, true)).colorMode)
        assertEquals(ColorMode.SAMPUL, DesignMigration.migrate(stored(null, null, true)).colorMode)
        // any other colour is the user's own choice -> KUSTOM, whatever dynamic says
        assertEquals(ColorMode.KUSTOM, DesignMigration.migrate(stored(null, custom, null)).colorMode)
        assertEquals(ColorMode.KUSTOM, DesignMigration.migrate(stored(null, custom, false)).colorMode)
        assertEquals(ColorMode.KUSTOM, DesignMigration.migrate(stored(null, custom, true)).colorMode)
    }

    @Test
    fun `full matrix, every combination of style, seed, dynamic and version`() {
        val styles = listOf<String?>(null, "", "EDITORIAL", "GLASS", "garbage")
        val seeds = listOf<Int?>(null, builtIn, custom, 0xFFEC5464.toInt(), 0)
        val dynamics = listOf<Boolean?>(null, false, true)
        val versions = listOf<Int?>(null, 1, 2, 3)
        var count = 0
        for (st in styles) for (sd in seeds) for (dy in dynamics) for (v in versions) {
            val s = stored(st, sd, dy, v)
            val migrate = DesignMigration.needsMigration(s)
            assertEquals("version $v", (v ?: 1) < HorizonDesignVersion, migrate)
            val c = DesignMigration.migrate(s)
            val expectedStyle = if (st == "EDITORIAL") UiStyle.EDITORIAL else UiStyle.GLASS
            val seedIsBuiltIn = (sd ?: builtIn) == builtIn
            val expectedMode = when {
                !seedIsBuiltIn -> ColorMode.KUSTOM
                dy == true -> ColorMode.SAMPUL
                else -> ColorMode.NETRAL
            }
            assertEquals("style for $s", expectedStyle, c.uiStyle)
            assertEquals("mode for $s", expectedMode, c.colorMode)
            count++
        }
        assertEquals(5 * 5 * 3 * 4, count)
    }

    @Test
    fun `an explicit Editorial choice stays flat and keeps its custom colour`() {
        val c = DesignMigration.migrate(stored("EDITORIAL", custom, false))
        assertEquals(UiStyle.EDITORIAL, c.uiStyle)
        assertEquals(ColorMode.KUSTOM, c.colorMode)
    }

    @Test
    fun `reading the stored values from preferences`() {
        assertEquals(stored(null, null, null), StoredDesign.from(emptyPreferences()))
        val p = mutablePreferencesOf(
            UiStyleKey to "EDITORIAL",
            SelectedThemeColorKey to custom,
            DynamicThemeKey to true,
            DesignVersionKey to 2,
        )
        assertEquals(stored("EDITORIAL", custom, true, 2), StoredDesign.from(p))
    }

    /**
     * In-memory store: the file based one renames a temp file over the old one, which fails on a Windows host (the
     * production code runs on Android, where it does not).
     */
    private class MemoryStore : androidx.datastore.core.DataStore<Preferences> {
        private val state = kotlinx.coroutines.flow.MutableStateFlow<Preferences>(emptyPreferences())
        override val data: kotlinx.coroutines.flow.Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun newStore(name: String): androidx.datastore.core.DataStore<Preferences> = MemoryStore()

    @Test
    fun `run writes explicit values once and leaves the old ones and other settings alone`() = runBlocking {
        val store = newStore("a")
        store.edit {
            it[SelectedThemeColorKey] = custom
            it[DynamicThemeKey] = true
            it[PureBlackKey] = true
            it[DarkModeKey] = "ON"
            it[IconFamilyKey] = "LINE"
        }
        assertTrue(DesignMigration.runOrThrow(store))
        val after = store.data.first()
        assertEquals("GLASS", after[UiStyleKey])
        assertEquals("KUSTOM", after[ColorModeKey])
        assertEquals(HorizonDesignVersion, after[DesignVersionKey])
        // old values are never deleted or rewritten
        assertEquals(custom, after[SelectedThemeColorKey])
        assertEquals(true, after[DynamicThemeKey])
        // dark mode, pure black and icon family are untouched
        assertEquals(true, after[PureBlackKey])
        assertEquals("ON", after[DarkModeKey])
        assertEquals("LINE", after[IconFamilyKey])
        // the second run does nothing, even if the user changed things in between
        store.edit { it[ColorModeKey] = "NETRAL"; it[UiStyleKey] = "EDITORIAL" }
        assertFalse(DesignMigration.runOrThrow(store))
        val again = store.data.first()
        assertEquals("NETRAL", again[ColorModeKey])
        assertEquals("EDITORIAL", again[UiStyleKey])
    }

    @Test
    fun `run on a fresh install gives Kaca and Netral`() = runBlocking {
        val store = newStore("fresh")
        assertTrue(DesignMigration.runOrThrow(store))
        val after = store.data.first()
        assertEquals("GLASS", after[UiStyleKey])
        assertEquals("NETRAL", after[ColorModeKey])
        assertNull("the old seed key is not invented", after[SelectedThemeColorKey])
        assertNull("the old dynamic key is not invented", after[DynamicThemeKey])
    }

    @Test
    fun `run does not write or reset the cover shade, absent means Lembut`() = runBlocking {
        // a fresh install and an update both have no value: the setting reads its default (Lembut)
        val fresh = newStore("shade-fresh")
        assertTrue(DesignMigration.runOrThrow(fresh))
        assertNull("the migration invents no shade value", fresh.data.first()[CoverShadeKey])
        assertEquals(CoverShadeLevel.SOFT, fresh.data.first()[CoverShadeKey].let { name -> CoverShadeLevel.entries.firstOrNull { it.name == name } ?: CoverShadeLevel.DEFAULT })
        // a user who already chose keeps the choice, whatever the migration decides about the rest
        val chose = newStore("shade-chose")
        chose.edit { it[CoverShadeKey] = "OFF"; it[SelectedThemeColorKey] = custom }
        assertTrue(DesignMigration.runOrThrow(chose))
        assertEquals("OFF", chose.data.first()[CoverShadeKey])
        assertEquals("KUSTOM", chose.data.first()[ColorModeKey])
    }

    @Test
    fun `run keeps an explicit Editorial choice`() = runBlocking {
        val store = newStore("editorial")
        store.edit { it[UiStyleKey] = "EDITORIAL" }
        assertTrue(DesignMigration.runOrThrow(store))
        val after = store.data.first()
        assertEquals("EDITORIAL", after[UiStyleKey])
        assertEquals("NETRAL", after[ColorModeKey])
    }

    @Test
    fun `run on a build that already ran does not touch a newer version`() = runBlocking {
        val store = newStore("newer")
        store.edit { it[DesignVersionKey] = HorizonDesignVersion + 1; it[ColorModeKey] = "SAMPUL" }
        assertFalse(DesignMigration.runOrThrow(store))
        val after = store.data.first()
        assertEquals(HorizonDesignVersion + 1, after[DesignVersionKey])
        assertEquals("SAMPUL", after[ColorModeKey])
        assertNull(after[UiStyleKey])
    }
}
