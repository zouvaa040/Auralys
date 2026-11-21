/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import androidx.lifecycle.ViewModel
import com.metrolist.music.utils.ListeningStreakManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Gives screens other than Statistics (the Home banner) the same streak. */
@HiltViewModel
class StreakViewModel
@Inject
constructor(
    manager: ListeningStreakManager,
) : ViewModel() {
    val streak = manager.streak
}
