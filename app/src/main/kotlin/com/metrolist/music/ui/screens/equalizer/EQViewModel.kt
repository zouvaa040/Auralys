package com.metrolist.music.ui.screens.equalizer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.eq.EqualizerService
import com.metrolist.music.eq.data.EQProfileRepository
import com.metrolist.music.eq.data.GraphicEq
import com.metrolist.music.eq.data.ParametricEQParser
import com.metrolist.music.eq.data.SavedEQProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import javax.inject.Inject

/**
 * ViewModel for EQ Screen
 * Manages EQ profiles and applies them to the EqualizerService
 */
@HiltViewModel
class EQViewModel @Inject constructor(
    private val eqProfileRepository: EQProfileRepository,
    private val equalizerService: EqualizerService
) : ViewModel() {

    private val _state = MutableStateFlow(EQState())
    val state: StateFlow<EQState> = _state.asStateFlow()

    /** The slider that is being dragged saves a moment after the last move, not at every pixel. */
    private var saveJob: Job? = null

    init {
        loadProfiles()
    }

    /**
     * Load all saved EQ profiles (sorted: AutoEQ first, then custom)
     */
    private fun loadProfiles() {
        // Observe profiles changes
        viewModelScope.launch {
            eqProfileRepository.profiles.collect { all ->
                val sortedProfiles = eqProfileRepository.getSortedProfiles()
                val saved = all.firstOrNull { it.id == GraphicEq.ID }?.let(GraphicEq::gainsOf)
                val mine = all.filter { it.source == GraphicEq.SOURCE_PRESET }.sortedByDescending { it.addedTimestamp }
                _state.update {
                    it.copy(
                        profiles = sortedProfiles,
                        myPresets = mine,
                        // while a slider is being dragged the screen is ahead of what is saved: keep what the finger set
                        graphicGains = if (saveJob?.isActive == true) it.graphicGains else (saved ?: it.graphicGains),
                    )
                }
            }
        }

        // Observe active profile changes separately
        viewModelScope.launch {
            eqProfileRepository.activeProfile.collect { activeProfile ->
                _state.update {
                    it.copy(activeProfileId = activeProfile?.id)
                }
            }
        }
    }

    /**
     * Select and apply an EQ profile
     * Pass null to disable EQ
     */
    fun selectProfile(profileId: String?) {
        // a slider that was moved a moment ago must not switch the sliders back on after this
        saveJob?.cancel()
        viewModelScope.launch {
            if (profileId == null) {
                // Disable EQ
                equalizerService.disable()
                eqProfileRepository.setActiveProfile(null)
            } else {
                // Apply the selected profile
                val profile = _state.value.profiles.find { it.id == profileId }
                if (profile != null) {
                    val result = equalizerService.applyProfile(profile)
                    result.onSuccess {
                        eqProfileRepository.setActiveProfile(profileId)
                    }.onFailure { e ->
                        _state.update { it.copy(error = e.message ?: "Unknown error") }
                    }
                }
            }
        }
    }

    /**
     * Sets the ten sliders: the sound changes at once (the audio processor swaps the numbers without a click), the screen
     * follows, and the choice is saved and made the active equaliser. A slider that is being dragged saves a moment after
     * the last move; a tap on a preset saves at once.
     */
    fun setGains(gains: List<Double>, immediate: Boolean = false) {
        val snapped = gains.map(GraphicEq::snap)
        val profile = GraphicEq.profile(snapped)
        _state.update { it.copy(graphicGains = snapped) }
        equalizerService.applyProfile(profile)
        saveJob?.cancel()
        saveJob =
            viewModelScope.launch {
                if (!immediate) delay(SAVE_DELAY_MS)
                eqProfileRepository.saveProfile(profile)
                eqProfileRepository.setActiveProfile(profile.id)
            }
    }

    fun setBand(index: Int, db: Double) = setGains(GraphicEq.withBand(_state.value.graphicGains, index, db))

    fun setGroup(group: Int, level: Double) = setGains(GraphicEq.withGroupLevel(_state.value.graphicGains, group, level))

    fun applyPreset(preset: GraphicEq.Preset) = setGains(preset.gains, immediate = true)

    fun applyMyPreset(profile: SavedEQProfile) {
        val gains = GraphicEq.gainsOf(profile) ?: return
        setGains(gains, immediate = true)
    }

    fun resetGains() = setGains(GraphicEq.FLAT, immediate = true)

    /** The switch at the top: off = no equalizer at all, on = the sliders as they were. */
    fun setEnabled(on: Boolean) {
        if (on) setGains(_state.value.graphicGains, immediate = true) else selectProfile(null)
    }

    /** Keeps the sliders as a preset under [name]. */
    fun saveMyPreset(name: String) {
        val gains = _state.value.graphicGains
        val clean = name.trim().ifEmpty { return }
        viewModelScope.launch {
            eqProfileRepository.saveProfile(
                GraphicEq.profile(gains, id = "mypreset_${System.currentTimeMillis()}", name = clean, source = GraphicEq.SOURCE_PRESET),
            )
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * Delete an EQ profile
     */
    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            eqProfileRepository.deleteProfile(profileId)
        }
    }

    private companion object {
        const val SAVE_DELAY_MS = 250L
    }

    /**
     * Import a custom EQ profile from a file
     */
    fun importCustomProfile(
        fileName: String,
        inputStream: InputStream,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        viewModelScope.launch {
            try {
                // Read the file content
                val content = inputStream.bufferedReader().use { it.readText() }
                inputStream.close()

                // Parse the ParametricEQ format
                val parametricEQ = ParametricEQParser.parseText(content)

                // Validate the parsed EQ
                val validationErrors = ParametricEQParser.validate(parametricEQ)
                if (validationErrors.isNotEmpty()) {
                    onError(Exception("Invalid EQ file: ${validationErrors.first()}"))
                    return@launch
                }

                // Extract profile name from file name (remove .txt extension)
                val profileName = fileName.removeSuffix(".txt")

                // Import the profile
                eqProfileRepository.importCustomProfile(profileName, parametricEQ)

                _state.update { it.copy(importStatus = "Successfully imported $profileName") }
                onSuccess()
            } catch (e: Exception) {
                onError(Exception("Failed to import EQ profile: ${e.message}"))
            }
        }
    }
}