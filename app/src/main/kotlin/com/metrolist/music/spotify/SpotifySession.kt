/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The one narrow door between the Spotify account code (login, token minting, library) and its users
 * (library screens, Spotify Canvas). Users call [tokens] and [connected]; the account code installs a
 * [SpotifyTokenProvider] at app start and calls [setConnected] when the user signs in or out.
 * Nothing here touches the network or stores anything.
 */

package com.metrolist.music.spotify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The web player's bearer token and, when it could be minted, its client token. */
class SpotifyTokens(val bearer: String, val clientToken: String?)

fun interface SpotifyTokenProvider {
    /** Tokens for the signed-in account, or null when signed out, expired or the mint failed. Never throws except for cancellation. */
    suspend fun tokens(): SpotifyTokens?
}

object SpotifySession {
    @Volatile
    var provider: SpotifyTokenProvider? = null

    private val mutableConnected = MutableStateFlow(false)

    /** True while a Spotify account is connected (a login cookie is stored). */
    val connected: StateFlow<Boolean> = mutableConnected.asStateFlow()

    fun setConnected(value: Boolean) {
        mutableConnected.value = value
    }

    suspend fun tokens(): SpotifyTokens? = provider?.tokens()
}
