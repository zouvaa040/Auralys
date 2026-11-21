/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The two questions Spotify's web player asks to show a Canvas, with no network of their own: its track
 * search (`searchTracks`) and the track's video (`canvas`), both persisted GraphQL operations on Pathfinder v2,
 * and the reading of their query hashes out of the web player's own scripts.
 *
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord, GPL-3.0): SpotifyCanvasQuery.kt
 * (request body, answer, hash declaration) and web/extension/lib/spotify.ts (the `searchTracks` hash lives in the
 * search route's chunk, found through the bundle's chunk table; `api.spotify.com/v1/search` answers the web
 * player's token with 429, so it is not used). Rewritten for Auralys on kotlinx.serialization.json.
 */

package com.metrolist.music.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal object SpotifyCanvasQuery {
    const val ENDPOINT = "https://api-partner.spotify.com/pathfinder/v2/query"
    const val OP_SEARCH = "searchTracks"
    const val OP_CANVAS = "canvas"

    /** The last hashes known to work (BitChord, read from the web player bundle on 2026-10-16). */
    const val KNOWN_SEARCH_HASH = "b02683192a98dde7966b5e6655a79eeb62713eab703eda9902c932818dd52751"
    const val KNOWN_CANVAS_HASH = "575138ab27cd5c1b3e54da54d0a7cc8d85485402de26340c2145f0f6bb5e7a9f"

    val KNOWN_HASHES: Map<String, String> = mapOf(OP_SEARCH to KNOWN_SEARCH_HASH, OP_CANVAS to KNOWN_CANVAS_HASH)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Requests

    fun searchBody(term: String, hash: String): String = buildJsonObject {
        put("operationName", OP_SEARCH)
        putJsonObject("variables") {
            put("searchTerm", term)
            put("offset", 0)
            put("limit", 10)
            put("numberOfTopResults", 5)
            put("includeAudiobooks", false)
            put("includePreReleases", false)
            put("includeAuthors", false)
        }
        putJsonObject("extensions") {
            putJsonObject("persistedQuery") {
                put("version", 1)
                put("sha256Hash", hash)
            }
        }
    }.toString()

    fun canvasBody(trackUri: String, hash: String): String = buildJsonObject {
        put("operationName", OP_CANVAS)
        putJsonObject("variables") { put("trackUri", trackUri) }
        putJsonObject("extensions") {
            putJsonObject("persistedQuery") {
                put("version", 1)
                put("sha256Hash", hash)
            }
        }
    }.toString()

    // Answers

    sealed interface SearchAnswer {
        data class Tracks(val hits: List<SpotifyTrackHit>) : SearchAnswer

        /** The query did not work: unreadable, GraphQL errors with no data, or a shape that is not the known one. */
        data class Failed(val reason: String, val staleHash: Boolean = false) : SearchAnswer
    }

    sealed interface CanvasAnswer {
        data class Found(val url: String, val type: String?) : CanvasAnswer

        /** Spotify answered, and the track has no video this app can play (none at all, an image, or a shape that is not a plain mp4). */
        data class NoCanvas(val detail: String) : CanvasAnswer

        data class Failed(val reason: String, val staleHash: Boolean = false) : CanvasAnswer
    }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement?.num(): Long? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private fun JsonObject.at(vararg keys: String): JsonElement? {
        var at: JsonElement? = this
        for (key in keys) at = at.obj()?.get(key)
        return at
    }

    /** The answer's `errors[].message` texts (what a retired hash says) and its `data`. */
    private class Envelope(val data: JsonObject?, val errors: List<String>) {
        val stale: Boolean get() = errors.any { it.contains("PersistedQueryNotFound", ignoreCase = true) }
        val reason: String get() = errors.joinToString("; ").take(120).ifBlank { "no data" }
    }

    private fun envelope(body: String): Envelope? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val errors = root["errors"].arr()?.mapNotNull { it.obj()?.get("message").str() }.orEmpty()
        return Envelope(root["data"].obj(), errors)
    }

    fun parseSearch(body: String): SearchAnswer {
        val env = envelope(body) ?: return SearchAnswer.Failed("not JSON")
        val data = env.data ?: return SearchAnswer.Failed(env.reason, env.stale)
        val items = data.at("searchV2", "tracksV2", "items").arr()
            ?: return SearchAnswer.Failed("unexpected shape", env.stale)
        val hits = items.mapNotNull { entry ->
            val track = entry.obj()?.at("item", "data").obj() ?: return@mapNotNull null
            val type = track["__typename"].str()
            if (type != null && type != "Track") return@mapNotNull null
            val uri = track["uri"].str() ?: track["id"].str()?.let { "spotify:track:$it" } ?: return@mapNotNull null
            val title = track["name"].str()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val artists = track.at("artists", "items").arr()
                ?.mapNotNull { it.obj()?.at("profile", "name").str()?.trim()?.takeIf { name -> name.isNotEmpty() } }
                .orEmpty()
            val ms = track.at("duration", "totalMilliseconds").num() ?: track.at("trackDuration", "totalMilliseconds").num()
            SpotifyTrackHit(
                uri = uri,
                title = title,
                artists = artists,
                album = track.at("albumOfTrack", "name").str(),
                durationSec = ms?.let { (it / 1000).toInt() },
            )
        }
        return SearchAnswer.Tracks(hits)
    }

    private val mp4 = Regex("""\.mp4(?:[?#]|$)""", RegexOption.IGNORE_CASE)

    fun parseCanvas(body: String): CanvasAnswer {
        val env = envelope(body) ?: return CanvasAnswer.Failed("not JSON")
        val data = env.data ?: return CanvasAnswer.Failed(env.reason, env.stale)
        val track = data["trackUnion"].obj() ?: return CanvasAnswer.Failed("unexpected shape", env.stale)
        val canvas = track["canvas"].obj() ?: return CanvasAnswer.NoCanvas("canvas null")
        val type = canvas["type"].str()
        // The web player plays video canvases from `fileId` through its own player and only reads `url`
        // for images and GIFs; a plain https .mp4 is the one shape this app can play, anything else is skipped.
        val url = canvas["url"].str()?.takeIf { it.startsWith("https://") && mp4.containsMatchIn(it) }
            ?: return CanvasAnswer.NoCanvas("type=$type, no plain mp4 url")
        return CanvasAnswer.Found(url, type)
    }

    // Hashes in the web player's scripts

    /** `"canvas","query","<64 hex>"`, the way the bundle declares its operations. */
    private fun declaration(operation: String) =
        Regex("""["']${Regex.escape(operation)}["']\s*,\s*["']query["']\s*,\s*["']([0-9a-f]{64})["']""")

    fun findQueryHash(script: String, operation: String): String? =
        declaration(operation).find(script)?.groupValues?.get(1)

    private val webPlayerBundle = Regex("""https://open\.spotifycdn\.com/cdn/build/web-player/web-player\.[0-9a-f]+\.js""")

    fun bundleUrl(html: String): String? = webPlayerBundle.find(html)?.value

    const val CHUNK_BASE = "https://open.spotifycdn.com/cdn/build/web-player/"

    private val searchChunkId = Regex("""(\d+):"xpui-routes-search"""")

    /** The search route's chunk files named by the bundle's chunk table (usually one). */
    fun searchChunkUrls(bundle: String): List<String> {
        val id = searchChunkId.find(bundle)?.groupValues?.get(1) ?: return emptyList()
        return Regex("""\b$id:"([0-9a-f]{8})"""").findAll(bundle)
            .map { "${CHUNK_BASE}xpui-routes-search.${it.groupValues[1]}.js" }
            .distinct()
            .toList()
    }

    private val hashShape = Regex("""[0-9a-f]{64}""")

    fun isHash(value: String): Boolean = hashShape.matches(value)
}
