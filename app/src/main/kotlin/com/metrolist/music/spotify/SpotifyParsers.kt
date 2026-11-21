/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Pure readers for the Pathfinder answers (no network, no Android): the library's playlists, a playlist's tracks, Liked
 * Songs and the account's profile. The shapes follow BitChord's SpotifyLibrary parsers
 * (https://github.com/kushagrasinghx/BitChord, GPL-3.0); see NOTICE. Tested against a real captured playlist answer.
 */

package com.metrolist.music.spotify

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

private fun JsonElement?.asObj(): JsonObject? = this as? JsonObject

private fun JsonObject.obj(key: String): JsonObject? = this[key].asObj()

private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull

private fun JsonElement?.path(vararg keys: String): JsonElement? =
    keys.fold(this) { at, key -> (at as? JsonObject)?.get(key) }

/** A GraphQL answer that carries `errors`. [stale] is true when the persisted query's hash is unknown to Spotify. */
internal class GraphQlProblem(val message: String, val stale: Boolean, val hasData: Boolean)

/** The first GraphQL error of [root], or null when there is none. */
internal fun graphQlProblem(root: JsonObject): GraphQlProblem? {
    val errors =
        root.arr("errors").orEmpty().mapNotNull { (it as? JsonObject)?.str("message") }
    if (errors.isEmpty()) return null
    val data = root["data"]
    return GraphQlProblem(
        message = errors.joinToString("; ").take(200),
        stale = errors.any { it.contains("PersistedQueryNotFound", ignoreCase = true) },
        hasData = data != null && data !is JsonNull,
    )
}

/**
 * The source to show at about [minWidth] px: the smallest one at least that wide, else the widest. Spotify lists the sizes
 * in no fixed order and some covers give no width at all (then the first url is used).
 */
internal fun pickSource(sources: JsonArray?, minWidth: Int): String? {
    val list =
        sources.orEmpty().mapNotNull { element ->
            val source = element as? JsonObject ?: return@mapNotNull null
            val url = source.str("url")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            url to (source.int("width") ?: 0)
        }
    if (list.isEmpty()) return null
    val sorted = list.sortedBy { it.second }
    return (sorted.firstOrNull { it.second >= minWidth } ?: sorted.last()).first
}

private fun imageOf(images: JsonObject?, minWidth: Int): String? =
    pickSource(images?.arr("items")?.firstOrNull().asObj()?.arr("sources"), minWidth)

private val NOT_A_SONG = setOf("Episode", "NotFound", "LocalTrack", "Audiobook", "Chapter", "RestrictedContent")

private fun trackOf(data: JsonObject?, uri: String?): SpotifyTrack? {
    data ?: return null
    val title = data.str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val id = uri?.takeIf { it.isNotBlank() } ?: return null
    // podcast episodes and files on the owner's own device have no YouTube Music counterpart
    if (id.startsWith("spotify:local:") || id.startsWith("spotify:episode:")) return null
    if (data.str("__typename") in NOT_A_SONG) return null
    val album = data.obj("albumOfTrack")
    return SpotifyTrack(
        id = id.substringAfterLast(':'),
        title = title,
        artists =
            data.obj("artists")?.arr("items").orEmpty().mapNotNull { artist ->
                (artist as? JsonObject)?.obj("profile")?.str("name")?.trim()?.takeIf { it.isNotEmpty() }
            },
        album = album?.str("name")?.takeIf { it.isNotBlank() },
        // a playlist's answer calls it trackDuration, older shapes call it duration
        durationMs =
            (data.obj("trackDuration") ?: data.obj("duration"))?.long("totalMilliseconds")?.coerceAtLeast(0L) ?: 0L,
        imageUrl = pickSource(album?.obj("coverArt")?.arr("sources"), 300),
    )
}

/** `data.me.libraryV3`: the library's playlists (Liked Songs is not among them, the caller adds it). */
internal fun parsePlaylistPage(root: JsonObject): SpotifyPage<SpotifyPlaylist> {
    val library =
        root.obj("data")?.obj("me")?.obj("libraryV3")
            ?: throw SpotifyException(SpotifyException.Kind.API_CHANGED, "Spotify library answer was empty")
    val rawItems = library.arr("items").orEmpty()
    val items =
        rawItems.mapNotNull { element ->
            val wrapper = (element as? JsonObject)?.obj("item") ?: return@mapNotNull null
            if (wrapper.str("__typename")?.contains("Playlist", ignoreCase = true) != true) return@mapNotNull null
            val data = wrapper.obj("data") ?: return@mapNotNull null
            if (data.str("__typename") != "Playlist") return@mapNotNull null
            val uri = wrapper.str("_uri") ?: return@mapNotNull null
            val name = data.str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            SpotifyPlaylist(
                id = uri.substringAfterLast(':'),
                name = name,
                owner = data.obj("ownerV2")?.obj("data")?.str("name")?.takeIf { it.isNotBlank() },
                imageUrl = imageOf(data.obj("images"), 300),
            )
        }
    return SpotifyPage(items, library.int("totalCount") ?: rawItems.size, rawItems.size)
}

/** `data.playlistV2`: a playlist's name, owner, cover and one page of its tracks. */
internal fun parseTrackPage(root: JsonObject): Pair<SpotifyPlaylistHeader, SpotifyPage<SpotifyTrack>> {
    val playlist = root.obj("data")?.obj("playlistV2")
    val content = playlist?.obj("content")
    if (playlist == null || content == null) {
        val kind = playlist?.str("__typename")
        throw SpotifyException(
            if (kind == "NotFound" || kind == "GenericError") SpotifyException.Kind.OTHER else SpotifyException.Kind.API_CHANGED,
            "Spotify playlist answer was empty",
        )
    }
    val rawItems = content.arr("items").orEmpty()
    val items =
        rawItems.mapNotNull { element ->
            val data = (element as? JsonObject)?.obj("itemV2")?.obj("data") ?: return@mapNotNull null
            trackOf(data, data.str("uri") ?: data.str("_uri"))
        }
    val total = content.int("totalCount") ?: rawItems.size
    val header =
        SpotifyPlaylistHeader(
            name = playlist.str("name")?.trim()?.takeIf { it.isNotEmpty() },
            owner = playlist.obj("ownerV2")?.obj("data")?.str("name")?.takeIf { it.isNotBlank() },
            // the header cover is shown big: ask for the widest one
            imageUrl = imageOf(playlist.obj("images"), Int.MAX_VALUE),
            total = total,
        )
    return header to SpotifyPage(items, total, rawItems.size)
}

/** `data.me.library.tracks`: one page of Liked Songs, each entry wrapping the track under `track`. */
internal fun parseLikedPage(root: JsonObject): SpotifyPage<SpotifyTrack> {
    val tracks =
        root.obj("data")?.obj("me")?.obj("library")?.obj("tracks")
            ?: throw SpotifyException(SpotifyException.Kind.API_CHANGED, "Spotify liked songs answer was empty")
    val rawItems = tracks.arr("items").orEmpty()
    val items =
        rawItems.mapNotNull { element ->
            val wrapper = (element as? JsonObject)?.obj("track") ?: return@mapNotNull null
            val data = wrapper.obj("data")
            trackOf(data, wrapper.str("_uri") ?: data?.str("uri"))
        }
    return SpotifyPage(items, tracks.int("totalCount") ?: rawItems.size, rawItems.size)
}

/** `data.me.profile`: the account's display name and avatar, or null when the answer has no name. */
internal fun parseProfile(root: JsonObject): SpotifyProfile? {
    val profile = root.path("data", "me", "profile").asObj() ?: return null
    val name = (profile.str("name") ?: profile.str("username"))?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return SpotifyProfile(name, pickSource(profile.obj("avatar")?.arr("sources"), 64))
}
