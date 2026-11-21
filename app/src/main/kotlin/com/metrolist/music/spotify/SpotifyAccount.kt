/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The one place that owns the connected Spotify account: it wires the cookie store, the token minter and the library client
 * together, installs the token provider into [SpotifySession] at app start, and keeps [SpotifySession.connected] true
 * exactly while a sign-in cookie is stored (set at start from storage, on sign-in, on disconnect).
 *
 * Signing out is the account's own work, not a screen's: [SpotifyAccount.disconnect] runs in a scope the account owns and
 * cannot be cancelled by leaving the page that asked for it.
 */

package com.metrolist.music.spotify

import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The library of the connected account as the last screen read it, so reopening a screen is instant. */
object SpotifyLibraryCache {
    @Volatile private var playlistsRead: List<SpotifyPlaylist>? = null

    @Volatile private var readAtMs = 0L

    /** The playlists last read, or null when none were read or they are older than [maxAgeMs]. */
    fun playlists(maxAgeMs: Long = DEFAULT_MAX_AGE_MS, now: Long = System.currentTimeMillis()): List<SpotifyPlaylist>? =
        playlistsRead?.takeIf { now - readAtMs < maxAgeMs }

    /** Any playlist read so far, however old: a playlist page only needs its name and cover from it. */
    fun find(id: String): SpotifyPlaylist? = playlistsRead?.firstOrNull { it.id == id }

    fun put(playlists: List<SpotifyPlaylist>, now: Long = System.currentTimeMillis()) {
        playlistsRead = playlists
        readAtMs = now
    }

    fun clear() {
        playlistsRead = null
        readAtMs = 0L
    }

    private const val DEFAULT_MAX_AGE_MS = 5L * 60 * 1000
}

/** The steps of a sign-out, in the order they run. Each one is allowed to fail without stopping the ones after it. */
internal class SpotifyDisconnectSteps(
    /** 1. Forgets the encrypted cookie (and the copy in memory). Runs on the IO dispatcher. */
    val clearStore: () -> Unit,
    /** 2. `connected` becomes false. */
    val markDisconnected: () -> Unit,
    /** 3. Tokens (and a mint in progress), the hashes read from the web player, the profile, the library and match caches. */
    val dropMemory: () -> Unit,
    /** 4. Spotify's cookies and web storage in the web view. Runs on the main dispatcher. */
    val clearWebSession: () -> Unit,
    /** 5. The Keystore key. Runs on the IO dispatcher. */
    val deleteKey: () -> Unit,
)

/**
 * Runs a sign-out completely, in [scope] (which belongs to the account, not to a screen) and shielded from cancellation: the
 * person tapping "Putuskan" and leaving the page at once must not leave the cookie on disk or in the web view.
 */
internal class SpotifyDisconnector(
    private val scope: CoroutineScope,
    private val steps: SpotifyDisconnectSteps,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val main: CoroutineDispatcher = Dispatchers.Main,
) {
    /** The run in progress, so a second tap joins it instead of starting another. */
    @Volatile private var running: Job? = null

    @Synchronized
    fun start(): Job {
        running?.takeIf { it.isActive }?.let { return it }
        return scope.launch { withContext(NonCancellable) { runAll() } }.also { running = it }
    }

    private suspend fun runAll() {
        step(io) { steps.clearStore() }
        step(null) { steps.markDisconnected() }
        step(null) { steps.dropMemory() }
        step(main) { steps.clearWebSession() }
        step(io) { steps.deleteKey() }
    }

    private suspend fun step(on: CoroutineDispatcher?, body: () -> Unit) {
        try {
            if (on == null) body() else withContext(on) { body() }
        } catch (e: Exception) {
            // a step that fails (no web view on this phone, a Keystore error) must not keep the others from running
        }
    }
}

object SpotifyAccount {
    /** The account's own scope: app-lifetime work (mints, sign-out) that no screen may cancel. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var store: SpotifyAuthStore? = null

    @Volatile private var minter: SpotifyTokenMinter? = null

    @Volatile private var hashes: SpotifyHashes? = null

    @Volatile private var apiClient: SpotifyApi? = null

    @Volatile private var disconnector: SpotifyDisconnector? = null

    /** The cookie in memory, so a token mint does not open the Keystore every time. Read from the store on first use, see [currentCookie]. */
    @Volatile private var cookie: String? = null

    @Volatile private var cookieLoaded = false

    /** True while the Keystore failed to open a stored cookie for a reason that may pass: still signed in, try again later. */
    @Volatile private var keystoreHiccup = false

    private val mutableProfile = MutableStateFlow<SpotifyProfile?>(null)

    /** The account's name and avatar once [loadProfile] has read them. */
    val profile: StateFlow<SpotifyProfile?> = mutableProfile.asStateFlow()

    private val mutableEpoch = MutableStateFlow(0)

    /** Goes up every time the account changes (connected, or disconnected): a screen that shows its data starts over. */
    val epoch: StateFlow<Int> = mutableEpoch.asStateFlow()

    /** When the profile was last asked for in vain, and for which account: it is not asked again for a while. */
    @Volatile private var profileFailedAtMs = 0L

    @Volatile private var profileFailedEpoch = -1

    /** The library client. Only valid after [init]. */
    val api: SpotifyApi
        get() = apiClient ?: throw SpotifyException(SpotifyException.Kind.NOT_CONNECTED, "Spotify is not set up")

    /** True while a token is being minted in the background (an earlier caller ran out of time waiting for it). */
    fun isMintRunning(): Boolean = minter?.isMinting == true

    /** A person asked to try again: a failed mint is no longer remembered. */
    fun forgetMintFailure() {
        minter?.forgetFailure()
    }

    /**
     * Sets everything up and installs the token provider. Call once at app start in the main process. Cheap: nothing is
     * read from the network, and the web view is only touched when a token is first needed.
     */
    @Synchronized
    fun init(context: Context) {
        if (store != null) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(SpotifyAuthStore.PREFS_FILE, Context.MODE_PRIVATE)
        val authStore =
            SpotifyAuthStore(
                prefs,
                AesGcmCookieCipher(key = { AesGcmCookieCipher.keystoreKey() }, deleteKey = { AesGcmCookieCipher.deleteKeystoreKey() }),
            )
        val transport = OkHttpSpotifyTransport()
        val webPlayer = SpotifyWebPlayer(transport)
        val tokenMinter =
            SpotifyTokenMinter(
                cookie = ::currentCookie,
                harvester = WebViewSpotifyTokenHarvester(app),
                webPlayer = webPlayer,
                transport = transport,
                osVersion = { Build.VERSION.RELEASE.orEmpty().ifBlank { "unknown" } },
                scope = scope,
            )
        val hashReader = SpotifyHashes(webPlayer)
        apiClient =
            SpotifyApi(
                transport = transport,
                hashes = hashReader,
                tokens = { SpotifySession.tokens() },
                invalidateTokens = tokenMinter::invalidate,
                // a Keystore that is only busy is "try again", not "connect again"
                mintFailure = { if (keystoreHiccup) SpotifyMintFailure.FAILED else tokenMinter.lastFailure },
            )
        minter = tokenMinter
        hashes = hashReader
        store = authStore
        disconnector =
            SpotifyDisconnector(
                scope = scope,
                steps =
                    SpotifyDisconnectSteps(
                        clearStore = {
                            synchronized(this) {
                                cookie = null
                                cookieLoaded = true
                                keystoreHiccup = false
                            }
                            authStore.clear()
                        },
                        markDisconnected = { SpotifySession.setConnected(false) },
                        dropMemory = {
                            tokenMinter.reset()
                            hashReader.reset()
                            forgetAccountData()
                        },
                        clearWebSession = ::clearSpotifyWebSession,
                        deleteKey = authStore::wipe,
                    ),
            )
        SpotifySession.provider = tokenMinter
        // only "is something stored": the Keystore is opened later, off the main thread, when the cookie is first needed
        SpotifySession.setConnected(authStore.has())
    }

    /**
     * The cookie for the token minter. The first call reads it from the store (a Keystore decryption, so not at app start).
     * A stored value that can never be read again is dropped and the account reads as signed out; one that the Keystore only
     * failed to open this time stays stored, the account stays signed in, and the next call tries again.
     */
    private fun currentCookie(): String? {
        if (cookieLoaded) return cookie
        synchronized(this) {
            if (!cookieLoaded) {
                when (val read = store?.read()) {
                    is StoredCookie.Present -> {
                        cookie = read.value
                        cookieLoaded = true
                        keystoreHiccup = false
                    }
                    StoredCookie.Unavailable -> keystoreHiccup = true
                    StoredCookie.Absent, null -> {
                        cookie = null
                        cookieLoaded = true
                        keystoreHiccup = false
                        if (SpotifySession.connected.value) SpotifySession.setConnected(false)
                    }
                }
            }
        }
        return cookie
    }

    /**
     * Keeps the cookie `sp_dc` from a finished sign-in and marks the account connected. False when it could not be stored.
     * Writes to disk and may create the Keystore key: call it off the main thread.
     */
    fun connect(spDc: String): Boolean {
        val authStore = store ?: return false
        if (!authStore.set(spDc)) return false
        synchronized(this) {
            cookie = spDc.trim()
            cookieLoaded = true
            keystoreHiccup = false
        }
        minter?.reset()
        forgetAccountData()
        SpotifySession.setConnected(true)
        return true
    }

    /**
     * Signs out, completely, in the account's own scope: it keeps going when the page that asked is left. In this order:
     * the encrypted cookie is forgotten, `connected` turns false, the tokens/hashes/profile/caches in memory are dropped (and
     * a mint in progress is stopped), Spotify's cookies and web storage are removed from the web view (only Spotify's: the
     * same jar holds the YouTube Music sign-in), and the Keystore key is deleted. The returned job ends when all is done.
     */
    fun disconnect(): Job = disconnector?.start() ?: Job().also { it.complete() }

    /**
     * Reads the account's name and avatar (best effort: null when Spotify does not give them). Off the main thread (the first
     * read opens the Keystore), and the answer is kept only if the account is still the one that asked. A failure is
     * remembered for a while, so opening the page again does not ask again at once.
     */
    suspend fun loadProfile(): SpotifyProfile? {
        mutableProfile.value?.let { return it }
        val epochAtStart = mutableEpoch.value
        if (!SpotifySession.connected.value) return null
        val now = System.currentTimeMillis()
        if (profileFailedEpoch == epochAtStart && now - profileFailedAtMs < PROFILE_RETRY_MS) return null
        val read =
            withContext(Dispatchers.IO) {
                try {
                    api.profile()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        if (read == null) {
            profileFailedEpoch = epochAtStart
            profileFailedAtMs = System.currentTimeMillis()
        } else if (SpotifySession.connected.value && mutableEpoch.value == epochAtStart) {
            mutableProfile.value = read
        }
        return read
    }

    private fun forgetAccountData() {
        mutableProfile.value = null
        profileFailedEpoch = -1
        SpotifyLibraryCache.clear()
        SpotifyMatchCache.clear()
        mutableEpoch.update { it + 1 }
    }

    /** What the last failed mint says: used by the screens to offer "connect again" instead of "try again". */
    val lastMintFailure: SpotifyMintFailure? get() = minter?.lastFailure

    private const val PROFILE_RETRY_MS = 10L * 60 * 1000
}

/** The hosts whose cookies a sign-in leaves in the web view's jar. */
private val SPOTIFY_COOKIE_URLS =
    listOf("https://open.spotify.com", "https://accounts.spotify.com", "https://www.spotify.com", "https://spotify.com")

/** The origins whose web storage (local storage, IndexedDB: the web player keeps its last token there) belongs to Spotify. */
internal val SPOTIFY_WEB_ORIGINS = SPOTIFY_COOKIE_URLS

/**
 * Removes Spotify's cookies AND Spotify's web storage from the web view (main thread). A plain "forget the stored cookie"
 * would leave the web view's own session behind, which signs the next "Connect" straight back in, and the web player's last
 * token and account data would stay in its storage. Only Spotify's go: the same jar and storage hold the YouTube Music
 * sign-in (so never `removeAllCookies` or `deleteAllData` here). Each part is tried even if another fails.
 */
fun clearSpotifyWebSession() {
    try {
        val cookies = CookieManager.getInstance()
        for (url in SPOTIFY_COOKIE_URLS) {
            val names =
                cookies.getCookie(url)?.split(";")?.map { it.substringBefore("=").trim() }?.filter { it.isNotEmpty() }.orEmpty()
            for (name in names) {
                val expired = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"
                cookies.setCookie(url, expired)
                cookies.setCookie(url, "$expired; Domain=.spotify.com")
            }
        }
        cookies.flush()
    } catch (e: Exception) {
        // no web view on this phone: nothing was ever written there either
    }
    try {
        val storage = WebStorage.getInstance()
        SPOTIFY_WEB_ORIGINS.forEach { storage.deleteOrigin(it) }
    } catch (e: Exception) {
        // same
    }
}
