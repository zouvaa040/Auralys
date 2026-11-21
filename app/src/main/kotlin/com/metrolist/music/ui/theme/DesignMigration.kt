/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * One-time move of stored appearance choices to the Horizon design (DESIGN-V2.md section 6). Rules:
 *  - never reuse an old key with a new meaning: "Warna" is a NEW key ([ColorModeKey]) and the old dynamicTheme and
 *    selectedThemeColor values are only read, never deleted (a downgrade or a fix release can still use them);
 *  - the decision is a pure function ([migrate]) so every stored combination can be unit tested;
 *  - the result is written as explicit values, so a later change of a default never moves a user again;
 *  - dark mode, pure black, icon family, app font, icon style and the rest are not touched.
 */

package com.metrolist.music.ui.theme

import androidx.compose.ui.graphics.toArgb
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.ColorModeKey
import com.metrolist.music.constants.DesignVersionKey
import com.metrolist.music.constants.DynamicThemeKey
import com.metrolist.music.constants.SelectedThemeColorKey
import com.metrolist.music.constants.UiStyleKey
import kotlinx.coroutines.flow.first

/** The design generation this build writes. Absent in the store = 1 (paper, terracotta, serif). */
const val HorizonDesignVersion = 2

/** What the settings store holds today. null means the key is absent. */
data class StoredDesign(
    val uiStyle: String?,
    val selectedThemeColor: Int?,
    val dynamicTheme: Boolean?,
    val designVersion: Int?,
) {
    companion object {
        fun from(preferences: Preferences) = StoredDesign(
            uiStyle = preferences[UiStyleKey],
            selectedThemeColor = preferences[SelectedThemeColorKey],
            dynamicTheme = preferences[DynamicThemeKey],
            designVersion = preferences[DesignVersionKey],
        )
    }
}

/** The explicit values the migration writes. */
data class DesignChoice(
    val uiStyle: UiStyle,
    val colorMode: ColorMode,
)

object DesignMigration {
    /** True until a build with the Horizon design has run once. */
    fun needsMigration(stored: StoredDesign): Boolean = (stored.designVersion ?: 1) < HorizonDesignVersion

    /**
     * The mapping table:
     *
     *  uiStyle absent or unreadable      -> GLASS ("Kaca", the new default for people who never chose)
     *  uiStyle EDITORIAL                 -> EDITORIAL kept (shown as "Datar": same tokens, no blur, no veils)
     *  uiStyle GLASS                     -> GLASS kept
     *  seed = built-in and dynamic false -> NETRAL
     *  seed = built-in and dynamic true  -> SAMPUL (what the glass style used to switch on by itself)
     *  seed is any other colour          -> KUSTOM with the same seed (an explicit choice keeps recolouring controls)
     */
    fun migrate(stored: StoredDesign): DesignChoice {
        val style = when (stored.uiStyle) {
            UiStyle.EDITORIAL.name -> UiStyle.EDITORIAL
            UiStyle.GLASS.name -> UiStyle.GLASS
            else -> UiStyle.DEFAULT
        }
        val builtIn = BuiltInThemeColor.toArgb()
        val seed = stored.selectedThemeColor ?: builtIn
        val mode = when {
            seed != builtIn -> ColorMode.KUSTOM
            stored.dynamicTheme == true -> ColorMode.SAMPUL
            else -> ColorMode.NETRAL
        }
        return DesignChoice(style, mode)
    }

    /**
     * Runs the migration if this store has not seen the Horizon design yet. Returns true when it wrote something.
     * Never throws (except cancellation): if the write fails the app simply starts with the defaults and tries again
     * on the next launch.
     */
    suspend fun run(dataStore: DataStore<Preferences>): Boolean =
        try {
            runOrThrow(dataStore)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            timber.log.Timber.w(e, "design migration failed, will retry on the next launch")
            false
        }

    internal suspend fun runOrThrow(dataStore: DataStore<Preferences>): Boolean {
        val stored = StoredDesign.from(dataStore.data.first())
        if (!needsMigration(stored)) return false
        val choice = migrate(stored)
        dataStore.edit { settings ->
            settings[UiStyleKey] = choice.uiStyle.name
            settings[ColorModeKey] = choice.colorMode.name
            settings[DesignVersionKey] = HorizonDesignVersion
        }
        return true
    }
}
