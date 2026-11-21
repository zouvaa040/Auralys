/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Every Pathfinder query is named by a persisted-query hash that changes whenever Spotify rebuilds its web player.
 * BitChord's Kotlin keeps them as constants with no refresh (fragile); only its browser extension re-reads them from the
 * web player's JavaScript. This does both: the constants are the start, and when Spotify answers "PersistedQueryNotFound"
 * the current hashes are read once from the player's own scripts (see web/docs/spotify-and-discord.md in BitChord,
 * https://github.com/kushagrasinghx/BitChord, GPL-3.0; see NOTICE). The scripts are about 4 MB, so they are only read
 * when a hash turns out to be stale, and at most once per [MIN_REFRESH_GAP_MS].
 */

package com.metrolist.music.spotify

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * A Pathfinder operation Auralys calls, with the hashes known to have worked, newest first. The first two of each list
 * come from the web player bundle web-player.c6932d85.js (read 2026-10-16, BitChord's fixture web-player.json); the older
 * ones are BitChord's Kotlin constants, which still answered then.
 */
enum class SpotifyOperation(val operationName: String, val known: List<String>) {
    LIBRARY(
        "libraryV3",
        listOf(
            "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455",
            "973e511ca44261fda7eebac8b653155e7caee3675abb4fb110cc1b8c78b091c3",
        ),
    ),
    PLAYLIST(
        "fetchPlaylist",
        listOf(
            "21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf",
            "346811f856fb0b7e4f6c59f8ebea78dd081c6e2fb01b77c954b26259d5fc6763",
        ),
    ),
    LIKED(
        "fetchLibraryTracks",
        listOf("087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240"),
    ),
    PROFILE(
        "profileAttributes",
        listOf("08ffb4730af3746e04a8301396f20875dbbce10c75243803091a9274eacc8ac0"),
    ),
}

/** The web player's front page and what its headers say, cached for a while. */
class SpotifyWebPlayerPage(val html: String, val deviceId: String?)

/** Reads open.spotify.com and its script bundles. Holds the page in memory; one fetch serves hashes and client version. */
class SpotifyWebPlayer(
    private val transport: SpotifyTransport,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var cached: SpotifyWebPlayerPage? = null

    @Volatile private var cachedAt = 0L
    private val mutex = Mutex()

    /** The front page, or null when it could not be read. Cached for [PAGE_TTL_MS] unless [fresh]. */
    suspend fun page(fresh: Boolean = false): SpotifyWebPlayerPage? =
        mutex.withLock {
            val current = cached
            if (!fresh && current != null && now() - cachedAt < PAGE_TTL_MS) return@withLock current
            val answer =
                try {
                    transport.get(WEB_PLAYER_URL, mapOf("User-Agent" to SPOTIFY_WEB_USER_AGENT, "Accept" to "text/html"))
                } catch (e: java.io.IOException) {
                    return@withLock current
                }
            if (!answer.ok || answer.body.isBlank()) return@withLock current
            val deviceId = answer.setCookies.firstNotNullOfOrNull { SP_T.find(it)?.groupValues?.get(1) }
            SpotifyWebPlayerPage(answer.body, deviceId).also {
                cached = it
                cachedAt = now()
            }
        }

    /** Forgets the cached front page. */
    fun clear() {
        cached = null
        cachedAt = 0L
    }

    suspend fun script(url: String): String? =
        try {
            transport.get(url, mapOf("User-Agent" to SPOTIFY_WEB_USER_AGENT)).takeIf { it.ok }?.body
        } catch (e: java.io.IOException) {
            null
        }

    companion object {
        const val WEB_PLAYER_URL = "https://open.spotify.com/"
        private const val PAGE_TTL_MS = 30L * 60 * 1000
        private val SP_T = Regex("""sp_t=([^;]+)""")

        private val CONFIG_BLOCK = Regex("""<script id="appServerConfig" type="text/plain">([^<]+)</script>""")
        private val CLIENT_VERSION = Regex(""""clientVersion"\s*:\s*"([^"]+)"""")

        /** The web player's client version, from the base64 config block embedded in its front page. */
        fun clientVersionIn(html: String): String? {
            val encoded = CONFIG_BLOCK.find(html)?.groupValues?.get(1)?.trim() ?: return null
            val decoded = runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) }.getOrNull() ?: return null
            return CLIENT_VERSION.find(decoded)?.groupValues?.get(1)
        }
    }
}

class SpotifyHashes(
    private val webPlayer: SpotifyWebPlayer,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val live = ConcurrentHashMap<String, String>()
    private val refreshLock = Mutex()

    @Volatile private var refreshedAt = 0L

    /** The hash to try first for [operation]: the one read from the web player when there is one, else the newest known. */
    fun current(operation: SpotifyOperation): String = live[operation.operationName] ?: operation.known.first()

    /** The first known hash for [operation] that is not in [tried], or null when every one was tried. */
    fun fallback(operation: SpotifyOperation, tried: Set<String>): String? = operation.known.firstOrNull { it !in tried }

    /**
     * Reads the current hashes from the web player's scripts. Returns true when at least one hash was read. A second call
     * within [MIN_REFRESH_GAP_MS] returns false without any network, so a stale hash never causes repeated 4 MB downloads.
     */
    suspend fun refresh(): Boolean =
        refreshLock.withLock {
            if (refreshedAt != 0L && now() - refreshedAt < MIN_REFRESH_GAP_MS) return@withLock false
            // a front page that cannot be read (offline) does not start the pause: nothing was downloaded
            val page = webPlayer.page(fresh = true) ?: return@withLock false
            val wanted = SpotifyOperation.values().map { it.operationName }.toMutableSet()
            var found = false
            for (script in webPlayerScripts(page.html).take(MAX_SCRIPTS)) {
                // the profile's hash is a nicety: the read stops once the operations the library needs are found
                if (REQUIRED.none { it in wanted }) break
                val text = webPlayer.script(script) ?: continue
                for ((name, hash) in queryHashesIn(text)) {
                    if (name in wanted) {
                        live[name] = hash
                        wanted.remove(name)
                        found = true
                    }
                }
            }
            // scripts were downloaded: whatever came out of it, the next read waits (a cancelled read never gets here)
            refreshedAt = now()
            found
        }

    /** Forgets the hashes read from the web player and its cached front page (sign-out). The known constants stay. */
    fun reset() {
        live.clear()
        refreshedAt = 0L
        webPlayer.clear()
    }

    companion object {
        const val MIN_REFRESH_GAP_MS = 10L * 60 * 1000
        private const val MAX_SCRIPTS = 8

        /** The operations a read has to find; the profile's is optional. */
        private val REQUIRED =
            listOf(SpotifyOperation.LIBRARY, SpotifyOperation.PLAYLIST, SpotifyOperation.LIKED).map { it.operationName }

        /** `"libraryV3","query","<64 hex>"`, the way the web player's bundle declares its operations. */
        private val DECLARATION = Regex("""["'](\w+)["']\s*,\s*["']query["']\s*,\s*["']([0-9a-f]{64})["']""")

        private val SCRIPT_URL = Regex("""https://open\.spotifycdn\.com/cdn/build/web-player/[A-Za-z0-9._~-]+\.js""")

        /** Every operation declared in a piece of the web player's JavaScript, first declaration wins. */
        fun queryHashesIn(script: String): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            for (match in DECLARATION.findAll(script)) result.putIfAbsent(match.groupValues[1], match.groupValues[2])
            return result
        }

        /** The player's own script bundles as linked from its front page, the main `web-player.<hex>.js` first. */
        fun webPlayerScripts(html: String): List<String> =
            SCRIPT_URL.findAll(html).map { it.value }.distinct().toList()
                .sortedBy { if (it.substringAfterLast('/').startsWith("web-player.")) 0 else 1 }
    }
}
