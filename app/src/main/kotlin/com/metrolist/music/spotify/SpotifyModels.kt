/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * What Auralys reads from a connected Spotify account. The library flow (sp_dc sign-in, a web-player token, Pathfinder
 * GraphQL queries) is adapted from BitChord (https://github.com/kushagrasinghx/BitChord, GPL-3.0); see NOTICE.
 */

package com.metrolist.music.spotify

/** The pseudo playlist id for Liked Songs, which has no playlist uri on Spotify. */
const val SPOTIFY_LIKED_ID = "liked"

/** Spotify's own artwork for Liked Songs (the collection has no cover of its own). */
const val SPOTIFY_LIKED_COVER = "https://misc.scdn.co/liked-songs/liked-songs-640.png"

data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val owner: String?,
    val imageUrl: String?,
)

data class SpotifyTrack(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String?,
    val durationMs: Long,
    val imageUrl: String?,
)

/** A playlist's header as the first page of its tracks reports it. */
data class SpotifyPlaylistHeader(
    val name: String?,
    val owner: String?,
    val imageUrl: String?,
    val total: Int,
)

data class SpotifyProfile(
    val name: String,
    val avatarUrl: String?,
)

/** One page of an answer: the entries kept, how many the service says exist, and how many the page held before filtering. */
data class SpotifyPage<T>(
    val items: List<T>,
    val total: Int,
    val raw: Int,
)

/** Every track of a playlist that was read. [total] is what Spotify says exists; it is larger when the page limit stopped the read. */
data class SpotifyTracks(
    val header: SpotifyPlaylistHeader,
    val tracks: List<SpotifyTrack>,
    val total: Int,
) {
    val truncated: Boolean get() = total > tracks.size
}

/** Why a Spotify call failed, so a screen can say something useful in plain words. Never carries a token or cookie. */
class SpotifyException(
    val kind: Kind,
    message: String,
) : Exception(message) {
    enum class Kind {
        /** No Spotify account is connected. */
        NOT_CONNECTED,

        /** The stored sign-in no longer works: Spotify sent the web player to its login page, or answered 401. */
        SESSION_EXPIRED,

        /** The web player's token could not be read (timeout, page changed). */
        TOKEN_FAILED,

        /** Spotify answered 429. */
        RATE_LIMITED,

        /** The query or its answer no longer looks like what this code expects (Spotify changed its web player). */
        API_CHANGED,

        /** No connection or a broken one. */
        NETWORK,

        OTHER,
    }
}
