/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Spotify's web-player GraphQL ("Pathfinder"): the account's playlists, a playlist's tracks, Liked Songs, the profile.
 * Read-only. Adapted from BitChord's SpotifyLibrary (https://github.com/kushagrasinghx/BitChord, GPL-3.0); see NOTICE.
 *
 * Paging is bounded ([MAX_LIBRARY_PAGES], [MAX_TRACK_PAGES]) and every call is cancellable (the screen that asked can
 * leave at any time). A 401 forgets the tokens and tries once more; "PersistedQueryNotFound", or an answer that no
 * longer has the expected shape, reads the web player's current hashes once ([SpotifyHashes]) and then tries the older
 * known hashes. Tokens never appear in an exception.
 */

package com.metrolist.music.spotify

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException

class SpotifyApi(
    private val transport: SpotifyTransport,
    private val hashes: SpotifyHashes,
    /** The bearer (and client token when there is one), or null when signed out or the mint failed. */
    private val tokens: suspend () -> SpotifyTokens?,
    /** Called on a 401: forget the cached tokens so the next [tokens] mints a new one. */
    private val invalidateTokens: () -> Unit,
    /** Why the last mint gave nothing; turns a null from [tokens] into the right [SpotifyException.Kind]. */
    private val mintFailure: () -> SpotifyMintFailure? = { null },
) {
    /** The library's playlists, Liked Songs first (its [SpotifyPlaylist.name] is empty: the screen names it). */
    suspend fun playlists(): List<SpotifyPlaylist> {
        val collected = ArrayList<SpotifyPlaylist>()
        var offset = 0
        for (page in 0 until MAX_LIBRARY_PAGES) {
            val answer = query(SpotifyOperation.LIBRARY, libraryVariables(offset), parse = ::parsePlaylistPage)
            collected += answer.items
            offset += answer.raw
            if (answer.raw == 0 || offset >= answer.total) break
        }
        // Liked Songs is not a playlist to Spotify's library query, so it is always offered first
        return listOf(SpotifyPlaylist(SPOTIFY_LIKED_ID, name = "", owner = null, imageUrl = SPOTIFY_LIKED_COVER)) +
            collected.distinctBy { it.id }
    }

    /**
     * Every track of [playlistId] (or of Liked Songs for [SPOTIFY_LIKED_ID]), up to the page limit. [onPage] gets the
     * tracks read so far after every page, so a long list fills in while it loads.
     */
    suspend fun tracks(
        playlistId: String,
        onPage: (SpotifyTracks) -> Unit = {},
    ): SpotifyTracks {
        val liked = playlistId == SPOTIFY_LIKED_ID
        val collected = ArrayList<SpotifyTrack>()
        var header = SpotifyPlaylistHeader(null, null, if (liked) SPOTIFY_LIKED_COVER else null, 0)
        var total = 0
        var offset = 0
        for (page in 0 until MAX_TRACK_PAGES) {
            val items: SpotifyPage<SpotifyTrack>
            if (liked) {
                items = query(SpotifyOperation.LIKED, likedVariables(offset), parse = ::parseLikedPage)
            } else {
                val parsed = query(SpotifyOperation.PLAYLIST, playlistVariables(playlistId, offset), parse = ::parseTrackPage)
                if (page == 0) header = parsed.first
                items = parsed.second
            }
            collected += items.items
            offset += items.raw
            total = items.total
            onPage(SpotifyTracks(header.copy(total = total), collected.toList(), total))
            if (items.raw == 0 || offset >= items.total) break
        }
        return SpotifyTracks(header.copy(total = total), collected, total)
    }

    /**
     * The account's display name and avatar, or null when Spotify does not give them. The profile is a nicety: a query that
     * fails, or whose answer is not the shape guessed here (no real capture of it exists), is NOT taken for a stale hash and
     * never makes the web player's scripts be read again; it just gives null.
     */
    suspend fun profile(): SpotifyProfile? =
        try {
            query(SpotifyOperation.PROFILE, buildJsonObject { }, recoverHashes = false) { root ->
                parseProfile(root) ?: throw SpotifyException(SpotifyException.Kind.OTHER, "Spotify profile answer was empty")
            }
        } catch (e: SpotifyException) {
            if (e.kind == SpotifyException.Kind.NOT_CONNECTED || e.kind == SpotifyException.Kind.SESSION_EXPIRED) throw e
            null
        }

    // ------------------------------------------------------------------ the call

    /**
     * Sends [operation] and reads the answer with [parse]. [parse] throws a [SpotifyException] of kind API_CHANGED when
     * the answer has not the shape it expects; that, like "PersistedQueryNotFound", means the hash may be stale. With
     * [recoverHashes] false (optional queries) it is just an error: no re-read of the web player, no walk through old hashes.
     */
    private suspend fun <T> query(
        operation: SpotifyOperation,
        variables: JsonObject,
        recoverHashes: Boolean = true,
        parse: (JsonObject) -> T,
    ): T {
        val tried = HashSet<String>()
        var hash = hashes.current(operation)
        var refreshedHashes = false
        var retriedAuth = false
        while (true) {
            currentCoroutineContext().ensureActive()
            tried += hash
            val token = tokens() ?: throw noTokens()
            val answer =
                try {
                    transport.post(
                        PATHFINDER_URL,
                        headersFor(token),
                        requestBody(operation, hash, variables).toByteArray(Charsets.UTF_8),
                        "application/json;charset=UTF-8",
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    throw SpotifyException(SpotifyException.Kind.NETWORK, "Spotify ${operation.operationName} could not be reached")
                }
            if (answer.code == 401) {
                if (retriedAuth) {
                    // A bearer that was minted fine but is refused while the client token is missing is Spotify asking for
                    // something new, not a session that ended: "connect again" would not help.
                    throw if (token.clientToken == null) {
                        SpotifyException(SpotifyException.Kind.API_CHANGED, "Spotify refused the token and wants something this version does not send")
                    } else {
                        SpotifyException(SpotifyException.Kind.SESSION_EXPIRED, "Spotify refused the sign-in")
                    }
                }
                retriedAuth = true
                invalidateTokens()
                continue
            }
            if (answer.code == 429) throw SpotifyException(SpotifyException.Kind.RATE_LIMITED, "Spotify is limiting requests")
            val root = runCatching { json.parseToJsonElement(answer.body) as? JsonObject }.getOrNull()
            val problem = root?.let(::graphQlProblem)

            if (!recoverHashes && (problem?.stale == true)) {
                throw SpotifyException(SpotifyException.Kind.OTHER, "Spotify ${operation.operationName} is not known to Spotify")
            }
            if (problem?.stale != true) {
                if (!answer.ok) {
                    throw SpotifyException(
                        if (answer.code in 500..599) SpotifyException.Kind.NETWORK else SpotifyException.Kind.OTHER,
                        "Spotify ${operation.operationName} failed (HTTP ${answer.code})",
                    )
                }
                if (root == null) {
                    throw SpotifyException(SpotifyException.Kind.API_CHANGED, "Spotify ${operation.operationName} answered with something other than JSON")
                }
                if (problem != null && !problem.hasData) {
                    // the server's own wording is not repeated: it is for no one to read, and nothing of it should reach a log
                    throw SpotifyException(SpotifyException.Kind.OTHER, "Spotify ${operation.operationName} answered with an error")
                }
                try {
                    return parse(root)
                } catch (e: SpotifyException) {
                    // any other kind is a real answer; this one means the shape is not the one the hash used to give
                    if (e.kind != SpotifyException.Kind.API_CHANGED || !recoverHashes) throw e
                }
            }

            // Spotify rebuilt its web player: read its current hashes once, then fall back to the older known ones
            if (!refreshedHashes) {
                refreshedHashes = true
                if (hashes.refresh()) {
                    val fresh = hashes.current(operation)
                    if (fresh !in tried) {
                        hash = fresh
                        continue
                    }
                }
            }
            hash = hashes.fallback(operation, tried)
                ?: throw SpotifyException(SpotifyException.Kind.API_CHANGED, "Spotify no longer answers the ${operation.operationName} query as expected")
        }
    }

    private fun noTokens(): SpotifyException =
        when (mintFailure()) {
            SpotifyMintFailure.EXPIRED -> SpotifyException(SpotifyException.Kind.SESSION_EXPIRED, "The Spotify sign-in is no longer accepted")
            SpotifyMintFailure.OFFLINE -> SpotifyException(SpotifyException.Kind.NETWORK, "Spotify could not be reached")
            SpotifyMintFailure.FAILED -> SpotifyException(SpotifyException.Kind.TOKEN_FAILED, "No Spotify token could be read")
            null -> SpotifyException(SpotifyException.Kind.NOT_CONNECTED, "No Spotify account is connected")
        }

    companion object {
        const val PATHFINDER_URL = "https://api-partner.spotify.com/pathfinder/v2/query"
        const val MAX_LIBRARY_PAGES = 20
        const val MAX_TRACK_PAGES = 50
        const val LIBRARY_PAGE_SIZE = 50
        const val TRACK_PAGE_SIZE = 100

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        internal fun headersFor(tokens: SpotifyTokens): Map<String, String> =
            buildMap {
                put("Authorization", "Bearer ${tokens.bearer}")
                put("Accept", "application/json")
                put("app-platform", "WebPlayer")
                put("Origin", "https://open.spotify.com")
                put("Referer", "https://open.spotify.com/")
                put("User-Agent", SPOTIFY_WEB_USER_AGENT)
                tokens.clientToken?.let { put("Client-Token", it) }
            }

        /** The JSON body of a persisted-query request. */
        internal fun requestBody(operation: SpotifyOperation, hash: String, variables: JsonObject): String =
            buildJsonObject {
                put("variables", variables)
                put("operationName", operation.operationName)
                putJsonObject("extensions") {
                    putJsonObject("persistedQuery") {
                        put("version", 1)
                        put("sha256Hash", hash)
                    }
                }
            }.toString()

        internal fun libraryVariables(offset: Int): JsonObject =
            buildJsonObject {
                putJsonArray("filters") { add(JsonPrimitive("Playlists")) }
                put("order", null as String?)
                put("textFilter", "")
                putJsonArray("features") {
                    for (feature in listOf("LIKED_SONGS", "YOUR_EPISODES_V2", "PRERELEASES", "EVENTS")) add(JsonPrimitive(feature))
                }
                put("limit", LIBRARY_PAGE_SIZE)
                put("offset", offset)
                put("flatten", true)
                put("expandedFolders", JsonArray(emptyList()))
                put("folderUri", null as String?)
                put("includeFoldersWhenFlattening", false)
            }

        internal fun playlistVariables(playlistId: String, offset: Int): JsonObject =
            buildJsonObject {
                put("uri", "spotify:playlist:$playlistId")
                put("offset", offset)
                put("limit", TRACK_PAGE_SIZE)
                put("enableWatchFeedEntrypoint", false)
            }

        internal fun likedVariables(offset: Int): JsonObject =
            buildJsonObject {
                put("offset", offset)
                put("limit", TRACK_PAGE_SIZE)
            }
    }
}
