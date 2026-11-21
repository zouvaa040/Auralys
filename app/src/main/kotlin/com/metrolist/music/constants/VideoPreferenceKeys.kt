/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Preference keys of the video mode (video/ package). Kept apart from PreferenceKeys.kt so the file stays untouched.
 */

package com.metrolist.music.constants

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/** The mode the user chose with the pill: "AUDIO" or "VIDEO". Written only when the user taps the pill. Default AUDIO. */
val VideoModeKey = stringPreferencesKey("videoMode3")

/** "Putar video otomatis dari hasil Video": a tap on a result of the search Video chip opens the player as video. Default on. */
val VideoAutoFromSearchKey = booleanPreferencesKey("videoAutoFromSearch3")

/** The experiment switch "Mode video". Off = the pill never shows. Default on. */
val VideoModeEnabledKey = booleanPreferencesKey("videoModeEnabled3")
