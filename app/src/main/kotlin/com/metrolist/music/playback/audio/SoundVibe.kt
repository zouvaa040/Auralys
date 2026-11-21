package com.metrolist.music.playback.audio

/**
 * One-tap sound styles: speed and pitch move together (like a record played faster or slower), and some add a colour on
 * top: a room reverb ([reverbAmount], 1 = the "Slowed + reverb" amount), a warm low-pass ([lofi]) or a pan that circles
 * the head ([spatial]). The same styles exist in the Windows app.
 */
enum class SoundVibe(
    val speed: Float,
    val pitch: Float,
    val reverbAmount: Float = 0f,
    val lofi: Boolean = false,
    val spatial: Boolean = false,
) {
    NORMAL(1f, 1f),
    SPED_UP(1.25f, 1.25f),
    SLOWED_REVERB(0.85f, 0.85f, reverbAmount = 1f),
    NIGHTCORE(1.4f, 1.4f),
    LOFI(0.94f, 0.94f, reverbAmount = 0.45f, lofi = true),
    SPATIAL_8D(1f, 1f, reverbAmount = 0.35f, spatial = true),
    CONCERT(1f, 1f, reverbAmount = 0.75f),
    ;

    val reverb: Boolean get() = reverbAmount > 0f

    /** True when the audio itself is changed, which rules out offload (it hands the compressed audio straight to the DSP). */
    val processesAudio: Boolean get() = reverb || lofi || spatial
}

/**
 * A sound style other than normal changes the speed inside the app (Sonic) instead of through the system's AudioTrack
 * playback rate. The system then only ever receives plain 1x audio, which screen recorders and other apps that capture
 * the playback take in without trouble.
 */
fun soundVibeNeedsSoftwareSpeed(storedName: String?): Boolean {
    val vibe = SoundVibe.entries.firstOrNull { it.name == storedName } ?: SoundVibe.NORMAL
    return vibe != SoundVibe.NORMAL
}
