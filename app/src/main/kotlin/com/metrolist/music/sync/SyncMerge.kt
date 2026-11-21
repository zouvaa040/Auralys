/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The merge rules of SYNC-PROTOCOL.md as a pure function: (what this phone has) + (a foreign snapshot) -> a plan.
 * Additive only, idempotent, nothing is ever deleted. Applying the plan is the job of a [SyncStore] (Room on the phone,
 * a fake in the tests), so the rules can be tested without a database.
 */

package com.metrolist.music.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class SavedKey(val kind: String, val id: String)

data class EventKey(val songId: String, val at: Long)

/** A playlist of the library as the merge sees it. [songIds] are in playlist order, duplicates included. */
data class LocalPlaylist(
    val localId: String,
    val name: String,
    val browseId: String?,
    val songIds: List<String>,
)

/** An album/artist/playlist row that already exists; [bookmarked] tells whether it is in the library. */
data class LocalSavedRow(val bookmarked: Boolean, val localId: String? = null)

/** Everything the merge needs to know about this phone. [liked] maps a song id to its liked time (epoch ms) or null. */
class LocalState(
    val songIds: Set<String>,
    val liked: Map<String, Long?>,
    val playlists: List<LocalPlaylist>,
    val saved: Map<SavedKey, LocalSavedRow>,
    val events: Set<EventKey>,
    val searches: Set<String>,
)

data class LikeUpdate(val songId: String, val at: Long?, val newlyLiked: Boolean)

data class NewPlaylist(
    val localId: String,
    val sid: String,
    val name: String,
    val createdAt: Long,
    val songIds: List<String>,
)

data class PlaylistAppend(val localId: String, val songIds: List<String>)

data class SavedOp(val saved: SyncSaved, val existingLocalId: String?)

/** What a merge changed (or, for a preview, would change). The names are the keys of the `done` summary. */
data class MergeSummary(
    val songsAdded: Int = 0,
    val likedAdded: Int = 0,
    val playlistsCreated: Int = 0,
    val playlistsExtended: Int = 0,
    val playlistSongsAdded: Int = 0,
    /** Of [playlistSongsAdded], the songs that went into playlists which already existed here. */
    val playlistSongsAppended: Int = 0,
    val savedAdded: Int = 0,
    val savedSkipped: Int = 0,
    val eventsAdded: Int = 0,
    val playMsAdded: Long = 0,
    val searchesAdded: Int = 0,
    /** References (playlist entries, events, likes) left out because the snapshot had no metadata for the song. */
    val unresolved: Int = 0,
) {
    /** True when the merge changes nothing the user could see ("nothing new"). */
    val isEmpty: Boolean
        get() =
            songsAdded == 0 && likedAdded == 0 && playlistsCreated == 0 && playlistSongsAdded == 0 &&
                savedAdded == 0 && eventsAdded == 0 && searchesAdded == 0

    fun toJson(): JsonObject =
        buildJsonObject {
            put("songsAdded", songsAdded)
            put("likedAdded", likedAdded)
            put("playlistsCreated", playlistsCreated)
            put("playlistsExtended", playlistsExtended)
            put("playlistSongsAdded", playlistSongsAdded)
            put("savedAdded", savedAdded)
            put("savedSkipped", savedSkipped)
            put("eventsAdded", eventsAdded)
            put("searchesAdded", searchesAdded)
        }
}

class MergePlan(
    val newSongs: List<SyncSong>,
    val likeUpdates: List<LikeUpdate>,
    val newPlaylists: List<NewPlaylist>,
    val appends: List<PlaylistAppend>,
    /** The complete localPlaylistId -> sid map after this merge. */
    val sidMap: Map<String, String>,
    val savedOps: List<SavedOp>,
    val events: List<SyncEvent>,
    val searches: List<String>,
    val summary: MergeSummary,
)

object SyncMerger {
    fun normalizedName(name: String) = name.trim().lowercase()

    /** YouTube playlist ids sometimes carry a "VL" browse prefix (the golden file does it); compare without it. */
    fun normBrowseId(id: String) = id.removePrefix("VL")

    fun savedKey(kind: String, id: String) = SavedKey(kind, if (kind == SyncSaved.PLAYLIST) normBrowseId(id) else id)

    /**
     * @param sidMap localPlaylistId -> sid for playlists this phone copied from the other side earlier.
     * @param newPlaylistId makes the local id of a playlist that has to be created.
     * @param now epoch ms, used when the other side did not say when a playlist was created.
     */
    fun plan(
        local: LocalState,
        foreign: Snapshot,
        sidMap: Map<String, String>,
        newPlaylistId: () -> String,
        now: Long,
    ): MergePlan {
        // ---- songs ----
        val foreignSongs = LinkedHashMap<String, SyncSong>()
        for (song in foreign.songs) if (song.id.isNotBlank()) foreignSongs.putIfAbsent(song.id, song)
        val newSongs = foreignSongs.values.filter { it.id !in local.songIds }
        fun resolvable(id: String) = id in local.songIds || id in foreignSongs
        var unresolved = 0

        // ---- liked ----
        val likeUpdates = ArrayList<LikeUpdate>()
        val seenLikes = HashSet<String>()
        for (like in foreign.liked) {
            if (like.id.isBlank() || !seenLikes.add(like.id)) continue
            if (!resolvable(like.id)) {
                unresolved++
                continue
            }
            if (like.id in local.liked) {
                val localAt = local.liked[like.id]
                // Union keeps the earlier time.
                if (like.at != null && (localAt == null || like.at < localAt)) {
                    likeUpdates += LikeUpdate(like.id, like.at, newlyLiked = false)
                }
            } else {
                likeUpdates += LikeUpdate(like.id, like.at, newlyLiked = true)
            }
        }

        // ---- playlists ----
        val localIds = local.playlists.mapTo(HashSet()) { it.localId }
        // Entries of playlists the user deleted are dropped here, so such a playlist is treated as new if it comes back.
        val sidByLocal = LinkedHashMap(sidMap.filterKeys { it in localIds })
        fun sidOf(playlist: LocalPlaylist) = sidByLocal[playlist.localId] ?: playlist.localId

        val bySid = HashMap<String, LocalPlaylist>()
        val byBrowseId = HashMap<String, LocalPlaylist>()
        for (playlist in local.playlists) {
            bySid.putIfAbsent(sidOf(playlist), playlist)
            playlist.browseId?.let { byBrowseId.putIfAbsent(normBrowseId(it), playlist) }
        }
        val foreignSids = foreign.playlists.map { it.sid }.toSet()
        val matchedLocal = HashSet<String>()
        val seenSids = HashSet<String>()
        val newPlaylists = ArrayList<NewPlaylist>()
        val appends = ArrayList<PlaylistAppend>()
        var playlistSongsAdded = 0
        val claimedIds = HashSet(localIds)

        for (theirs in foreign.playlists) {
            if (theirs.sid.isBlank() || !seenSids.add(theirs.sid)) continue
            var target: LocalPlaylist? = theirs.browseId?.let { byBrowseId[normBrowseId(it)] }?.takeIf { it.localId !in matchedLocal }
            if (target == null) target = bySid[theirs.sid]?.takeIf { it.localId !in matchedLocal }
            if (target == null) {
                // First contact: the same name, a local playlist nobody has mapped yet.
                val wanted = normalizedName(theirs.name)
                target =
                    local.playlists.firstOrNull { candidate ->
                        candidate.localId !in matchedLocal &&
                            candidate.localId !in sidByLocal &&
                            candidate.localId !in foreignSids &&
                            normalizedName(candidate.name) == wanted
                    }
                if (target != null) sidByLocal[target.localId] = theirs.sid
            }

            val usable = theirs.songs.filter { it.isNotBlank() && resolvable(it) }
            unresolved += theirs.songs.count { it.isNotBlank() && !resolvable(it) }

            if (target != null) {
                matchedLocal += target.localId
                val have = target.songIds.toHashSet()
                val missing = ArrayList<String>()
                for (id in usable) if (have.add(id)) missing += id
                if (missing.isNotEmpty()) {
                    appends += PlaylistAppend(target.localId, missing)
                    playlistSongsAdded += missing.size
                }
            } else {
                var localId = newPlaylistId()
                while (!claimedIds.add(localId)) localId = newPlaylistId()
                sidByLocal[localId] = theirs.sid
                newPlaylists +=
                    NewPlaylist(
                        localId = localId,
                        sid = theirs.sid,
                        name = theirs.name.ifBlank { "Playlist" },
                        createdAt = if (theirs.createdAt > 0) theirs.createdAt else now,
                        songIds = usable,
                    )
                playlistSongsAdded += usable.size
            }
        }

        // ---- saved albums / artists / online playlists ----
        val savedOps = ArrayList<SavedOp>()
        val localSaved = local.saved.mapKeys { savedKey(it.key.kind, it.key.id) }
        var savedSkipped = 0
        val seenSaved = HashSet<SavedKey>()
        for (item in foreign.saved) {
            val kind = item.kind
            if (kind != SyncSaved.ALBUM && kind != SyncSaved.ARTIST && kind != SyncSaved.PLAYLIST || item.id.isBlank()) {
                savedSkipped++
                continue
            }
            val key = savedKey(kind, item.id)
            if (!seenSaved.add(key)) continue
            val row = localSaved[key]
            if (row?.bookmarked == true) continue
            savedOps += SavedOp(item, row?.localId)
        }

        // ---- events ----
        val seenEvents = HashSet<EventKey>()
        val events = ArrayList<SyncEvent>()
        var playMs = 0L
        for (event in foreign.events) {
            if (event.id.isBlank()) continue
            if (!resolvable(event.id)) {
                unresolved++
                continue
            }
            val key = EventKey(event.id, event.at)
            if (key in local.events || !seenEvents.add(key)) continue
            val ms = event.ms.coerceAtLeast(0L)
            events += event.copy(ms = ms)
            playMs += ms
        }

        // ---- searches ----
        val searches = ArrayList<String>()
        val seenSearches = HashSet<String>()
        for (raw in foreign.searches) {
            val query = raw.trim()
            if (query.isEmpty() || query in local.searches || !seenSearches.add(query)) continue
            searches += query
        }

        val summary =
            MergeSummary(
                songsAdded = newSongs.size,
                likedAdded = likeUpdates.count { it.newlyLiked },
                playlistsCreated = newPlaylists.size,
                playlistsExtended = appends.size,
                playlistSongsAdded = playlistSongsAdded,
                playlistSongsAppended = appends.sumOf { it.songIds.size },
                savedAdded = savedOps.size,
                savedSkipped = savedSkipped,
                eventsAdded = events.size,
                playMsAdded = playMs,
                searchesAdded = searches.size,
                unresolved = unresolved,
            )
        return MergePlan(
            newSongs = newSongs,
            likeUpdates = likeUpdates,
            newPlaylists = newPlaylists,
            appends = appends,
            sidMap = sidByLocal,
            savedOps = savedOps,
            events = events,
            searches = searches,
            summary = summary,
        )
    }
}

/** The database side of a sync. Room on the phone; a plain in-memory fake in the tests. */
interface SyncStore {
    /** The phone's state as a snapshot; [sidMap] tells which sid a copied playlist carries. */
    suspend fun exportSnapshot(sidMap: Map<String, String>, createdAt: Long): Snapshot

    suspend fun readLocal(): LocalState

    /** Applies the whole plan in ONE transaction; either everything is written or nothing. */
    suspend fun apply(plan: MergePlan)
}

/** Where `localPlaylistId -> sid` lives (a small file, not the Room schema). */
interface SyncStateStore {
    fun loadSidMap(): Map<String, String>

    fun saveSidMap(map: Map<String, String>)
}

class SyncEngine(
    private val store: SyncStore,
    private val state: SyncStateStore,
    private val newPlaylistId: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun buildSnapshot(): Snapshot = store.exportSnapshot(state.loadSidMap(), now())

    /** What [merge] would do, without writing anything. */
    suspend fun preview(foreign: Snapshot): MergeSummary = planFor(foreign).summary

    suspend fun merge(foreign: Snapshot): MergeSummary {
        val plan = planFor(foreign)
        store.apply(plan)
        state.saveSidMap(plan.sidMap)
        return plan.summary
    }

    private suspend fun planFor(foreign: Snapshot): MergePlan =
        SyncMerger.plan(store.readLocal(), foreign, state.loadSidMap(), newPlaylistId, now())
}
