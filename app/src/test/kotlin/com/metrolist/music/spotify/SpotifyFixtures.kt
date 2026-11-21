/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.spotify

internal object SpotifyFixtures {
    fun text(name: String): String =
        checkNotNull(SpotifyFixtures::class.java.classLoader?.getResourceAsStream("spotify-canvas/$name")) { "fixture $name missing" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    /** The ten tracks Spotify's own search answered for "Let Down Radiohead". */
    val letDownSearch: List<SpotifyTrackHit> by lazy {
        (SpotifyCanvasQuery.parseSearch(text("search-tracks.json")) as SpotifyCanvasQuery.SearchAnswer.Tracks).hits
    }

    fun hit(title: String, vararg artists: String, album: String? = null, sec: Int? = null, id: String = "0AAAAAAAAAAAAAAAAAAAA1") =
        SpotifyTrackHit("spotify:track:$id", title, artists.toList(), album, sec)
}
