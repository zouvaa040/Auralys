package com.metrolist.music.sync

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import kotlin.concurrent.thread

/** An in-memory phone/PC: the same merge rules, no database. Mirrors the Android semantics (library flag, play time). */
class MemStore(private val platform: String = "android") : SyncStore {
    class P(val id: String, var name: String, val browseId: String?, val createdAt: Long, val songs: MutableList<String>)

    val songs = LinkedHashMap<String, SyncSong>()
    val inLibrary = HashSet<String>()
    val liked = LinkedHashMap<String, Long?>()
    val playlists = ArrayList<P>()
    val saved = LinkedHashMap<SavedKey, SyncSaved>()
    val events = ArrayList<SyncEvent>()
    val totalPlay = HashMap<String, Long>()
    val searches = ArrayList<String>() // newest first
    var applyCalls = 0

    fun addSong(id: String, title: String = "T-$id", artist: String = "A", inLib: Boolean = false) {
        songs[id] = SyncSong(id = id, title = title, artist = artist)
        if (inLib) inLibrary += id
    }

    fun like(id: String, at: Long? = null) {
        liked[id] = at
        inLibrary += id
    }

    fun playlist(id: String, name: String, vararg songIds: String, browseId: String? = null, createdAt: Long = 1000L) =
        P(id, name, browseId, createdAt, songIds.toMutableList()).also { playlists += it }

    override suspend fun exportSnapshot(sidMap: Map<String, String>, createdAt: Long): Snapshot {
        val pls = playlists.map { SyncPlaylist(sidMap[it.id] ?: it.id, it.name, it.createdAt, it.songs.toList(), it.browseId) }
        val referenced = HashSet<String>()
        liked.keys.forEach { referenced += it }
        pls.forEach { referenced += it.songs }
        events.forEach { referenced += it.id }
        return Snapshot(
            platform = platform,
            createdAt = createdAt,
            songs = songs.values.filter { it.id in referenced },
            liked = liked.map { SyncLiked(it.key, it.value) },
            playlists = pls,
            saved = saved.values.toList(),
            events = events.toList(),
            searches = searches.take(100),
        )
    }

    override suspend fun readLocal(): LocalState =
        LocalState(
            songIds = songs.keys.toSet(),
            liked = liked.toMap(),
            playlists = playlists.map { LocalPlaylist(it.id, it.name, it.browseId, it.songs.toList()) },
            saved = saved.mapValues { LocalSavedRow(bookmarked = true) },
            events = events.mapTo(HashSet()) { EventKey(it.id, it.at) },
            searches = searches.toSet(),
        )

    override suspend fun apply(plan: MergePlan) {
        applyCalls++
        val likeById = plan.likeUpdates.associateBy { it.songId }
        for (song in plan.newSongs) {
            songs[song.id] = song
            // A song that only arrives for a playlist or the history is NOT in the library.
        }
        for (like in plan.likeUpdates) {
            if (like.newlyLiked) {
                liked[like.songId] = like.at
                inLibrary += like.songId
            } else {
                liked[like.songId] = like.at
            }
        }
        for (p in plan.newPlaylists) playlists += P(p.localId, p.name, null, p.createdAt, p.songIds.toMutableList())
        for (a in plan.appends) playlists.first { it.id == a.localId }.songs += a.songIds
        for (op in plan.savedOps) saved[SyncMerger.savedKey(op.saved.kind, op.saved.id)] = op.saved
        for (e in plan.events) {
            events += e
            totalPlay.merge(e.id, e.ms, Long::plus)
        }
        // newest first: local ones stay in front
        searches += plan.searches
        check(likeById.size == plan.likeUpdates.size)
    }
}

class MemStateStore(var map: Map<String, String> = emptyMap()) : SyncStateStore {
    override fun loadSidMap() = map

    override fun saveSidMap(map: Map<String, String>) {
        this.map = map.toMap()
    }
}

fun engineFor(store: SyncStore, state: SyncStateStore = MemStateStore(), idPrefix: String = "LPnew", now: Long = 5_000L): SyncEngine {
    var counter = 0
    return SyncEngine(store, state, newPlaylistId = { "$idPrefix${counter++}" }, now = { now })
}

fun testKey(seed: Int = 1): ByteArray = ByteArray(32) { (it * seed + 7).toByte() }

fun keyToken(key: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(key)

fun inviteFor(port: Int, key: ByteArray, hosts: String = "127.0.0.1", name: String = "PC-TES"): SyncInvite =
    SyncInvite.parse("auralys://sync?v=1&h=$hosts&p=$port&k=${keyToken(key)}&n=$name")

fun loopbackAttempts(invite: SyncInvite): List<() -> Socket> =
    invite.hosts.map { host -> { SyncConnect.plain.connect(host, invite.port, 1_000) } }

/** What the fake PC answers after it received the phone's snapshot. */
sealed interface PcReply {
    class Merge(val after: (Snapshot) -> Snapshot) : PcReply

    data object Decline : PcReply

    class Fail(val code: String, val message: String) : PcReply

    /** Does not answer at all. */
    data object Silence : PcReply
}

/** A fake Auralys PC on loopback speaking the protocol with the opposite directions. */
class FakePc(
    private val key: ByteArray,
    private val reply: PcReply,
    private val helloVersion: Int = SYNC_PROTOCOL_VERSION,
) : AutoCloseable {
    val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
    val port get() = server.localPort

    @Volatile var hello: SyncMessage.Hello? = null

    @Volatile var received: Snapshot? = null

    @Volatile var done: SyncMessage.Done? = null

    @Volatile var authFailed = false

    @Volatile var finished = false
    private val worker =
        thread(isDaemon = true) {
            try {
                server.accept().use { socket -> serve(socket) }
            } catch (_: Exception) {
            } finally {
                finished = true
            }
        }

    private fun serve(socket: Socket) {
        socket.soTimeout = 10_000
        val channel =
            SyncChannel(
                socket.getInputStream().buffered(),
                socket.getOutputStream().buffered(),
                SyncCipher(key, SyncProtocol.DIRECTION_PC_TO_PHONE, SyncProtocol.DIRECTION_PHONE_TO_PC),
            )
        try {
            hello = channel.receive() as SyncMessage.Hello
        } catch (e: SyncAuthException) {
            authFailed = true
            return // closes silently
        }
        channel.send(SyncMessage.HelloOk(helloVersion, "PC-TES", "1.6.0", "windows"))
        received = (channel.receive() as SyncMessage.SnapshotMsg).data
        when (val r = reply) {
            is PcReply.Merge -> {
                channel.send(SyncMessage.SnapshotMsg(r.after(received!!)))
                done = channel.receive() as? SyncMessage.Done
            }
            PcReply.Decline -> channel.send(SyncMessage.Declined)
            is PcReply.Fail -> channel.send(SyncMessage.Error(r.code, r.message))
            PcReply.Silence -> Thread.sleep(3_000)
        }
    }

    fun join(ms: Long = 3_000) = worker.join(ms)

    override fun close() {
        runCatching { server.close() }
        worker.join(2_000)
    }
}

/** The golden snapshot shared with the Windows tests (a byte-identical copy of Metrolist-work/sync-golden-snapshot.json). */
fun golden(): String =
    object {}.javaClass.getResource("/sync-golden-snapshot.json")!!.readText(Charsets.UTF_8)
