/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

/**
 * The song lyrics are wanted for, in every form a provider may need.
 *
 * @param rawTitle the title as YouTube Music has it ("Dracula (feat. JENNIE)"), kept for the featured artist check
 * @param title credits and packaging taken off, version words kept ("La La Lost You (Acoustic Version)"), see [LyricsQuery]
 * @param searchTitle every bracket taken off ("La La Lost You"): only for servers that need a loose query; a hit found
 *   with it is still checked against [title]
 * @param artist the artists joined with ", "
 * @param duration length in seconds; zero or less when unknown
 * @param isVideo a music video or a user upload (not the catalogue audio): its length often differs from the audio's
 *   by an intro, a skit or an outro, so a lyrics source timed on the audio is further off in length (see
 *   [LyricsSelection.rate])
 */
data class LyricsRequest(
    val id: String,
    val rawTitle: String,
    val title: String,
    val searchTitle: String,
    val artist: String,
    val duration: Int,
    val album: String?,
    val isVideo: Boolean = false,
) {
    companion object {
        fun of(
            id: String,
            rawTitle: String,
            artists: List<String>,
            duration: Int,
            album: String?,
            isVideo: Boolean = false,
        ): LyricsRequest =
            LyricsRequest(
                id = id,
                rawTitle = rawTitle,
                title = LyricsQuery.title(rawTitle),
                searchTitle = LyricsUtils.cleanTitleForSearch(rawTitle).ifBlank { rawTitle.trim() },
                artist = artists.joinToString { LyricsQuery.artist(it) },
                duration = duration,
                album = album,
                isVideo = isVideo,
            )
    }
}

/**
 * What a provider found: the lyrics text and, when the provider knows it, what the source calls the recording. The
 * metadata is checked against the request in [LyricsSelection.rate]; a provider that cannot tell leaves it null.
 *
 * @param durationSeconds the length of the recording the lyrics were made for (a search hit's length, or the `dur` of
 *   an Apple style TTML document)
 * @param keyedOnVideo true when the source looked the song up by its YouTube video id (it cannot hand back another
 *   song, only another upload of it)
 */
data class LyricsHit(
    val text: String,
    val title: String? = null,
    val artist: String? = null,
    val durationSeconds: Double? = null,
    val keyedOnVideo: Boolean = false,
)
