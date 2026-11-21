package com.metrolist.music.ui.screens.equalizer

import com.metrolist.music.eq.data.GraphicEq
import com.metrolist.music.eq.data.SavedEQProfile

/**
 * UI State for EQ Screen
 */
data class EQState(
    /** The imported headphone / file profiles. */
    val profiles: List<SavedEQProfile> = emptyList(),
    val activeProfileId: String? = null,
    val importStatus: String? = null,
    val error: String? = null,
    /** The ten sliders of the easy equaliser, as they are now (also while it is not the active one). */
    val graphicGains: List<Double> = GraphicEq.FLAT,
    /** The presets the listener saved. */
    val myPresets: List<SavedEQProfile> = emptyList(),
) {
    val enabled: Boolean get() = activeProfileId != null
    val graphicActive: Boolean get() = activeProfileId == GraphicEq.ID
}
