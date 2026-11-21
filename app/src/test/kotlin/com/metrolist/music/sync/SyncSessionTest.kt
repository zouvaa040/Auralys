package com.metrolist.music.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class SyncSessionTest {
    private fun phoneWithData() =
        MemStore().apply {
            addSong("a"); addSong("b")
            like("a", 100)
            playlist("LPmine", "Santai", "a", "b")
            events += SyncEvent("a", 1000, 20_000)
            searches += "lagu hp"
        }

    private fun pcAfter(snapshot: Snapshot): Snapshot =
        // what a PC that merged the phone would send back: the phone's data plus one liked song, one playlist, one play
        snapshot.copy(
            platform = "windows",
            songs = snapshot.songs + SyncSong("pcsong", "Lagu PC", "Artis PC"),
            liked = snapshot.liked + SyncLiked("pcsong", 77),
            playlists = snapshot.playlists + SyncPlaylist("pl-9", "Dari PC", 5, listOf("pcsong", "a")),
            events = snapshot.events + SyncEvent("pcsong", 3000, 60_000),
            searches = snapshot.searches + "lagu pc",
        )

    private fun session(
        store: SyncStore,
        backup: suspend () -> File = { File("before-sync-test.zip") },
        steps: MutableList<SyncStep> = ArrayList(),
        readTimeout: Int = 5_000,
        previewTimeout: Int = 5_000,
        state: SyncStateStore = MemStateStore(),
    ) = SyncSession(
        engine = engineFor(store, state),
        makeBackup = backup,
        attemptsFor = ::loopbackAttempts,
        deviceName = "Test phone",
        appVersion = "4.3.3",
        onStep = { steps += it },
        readTimeoutMs = readTimeout,
        previewTimeoutMs = previewTimeout,
    )

    @Test
    fun `a whole sync talks to the PC, merges its answer and reports done`() {
        val key = testKey()
        val phone = phoneWithData()
        val steps = ArrayList<SyncStep>()
        var backups = 0
        FakePc(key, PcReply.Merge(::pcAfter)).use { pc ->
            val outcome =
                runBlocking {
                    session(phone, backup = { backups++; File("b.zip") }, steps = steps).run(inviteFor(pc.port, key))
                }
            assertFalse(outcome.declined)
            assertEquals("PC-TES", outcome.pcName)
            assertEquals(1, backups)
            assertEquals(
                listOf(
                    SyncStep.CONNECTING, SyncStep.HANDSHAKE, SyncStep.READING, SyncStep.SENDING, SyncStep.WAITING_PC,
                    SyncStep.BACKUP, SyncStep.APPLYING, SyncStep.FINISHING,
                ),
                steps,
            )
            // what the PC saw
            assertEquals("Test phone", pc.hello!!.device)
            assertEquals("android", pc.hello!!.platform)
            assertEquals(1, pc.hello!!.v)
            assertEquals(listOf("a", "b"), pc.received!!.playlists.single().songs)
            assertEquals(1, pc.received!!.events.size)
            // what the phone took over
            val summary = outcome.summary!!
            assertEquals(1, summary.songsAdded)
            assertEquals(1, summary.likedAdded)
            assertEquals(1, summary.playlistsCreated)
            assertEquals(1, summary.eventsAdded)
            assertEquals(1, summary.searchesAdded)
            assertEquals(setOf("a", "pcsong"), phone.liked.keys)
            assertEquals(60_000L, phone.totalPlay["pcsong"])
            assertEquals(2, phone.playlists.size)
            // the PC got the summary
            pc.join()
            assertEquals(1, pc.done!!.summary["eventsAdded"].toString().toInt())
        }
    }

    @Test
    fun `a decline leaves the phone untouched and makes no backup`() {
        val key = testKey()
        val phone = phoneWithData()
        var backups = 0
        FakePc(key, PcReply.Decline).use { pc ->
            val outcome = runBlocking { session(phone, backup = { backups++; File("b.zip") }).run(inviteFor(pc.port, key)) }
            assertTrue(outcome.declined)
            assertNull(outcome.summary)
            assertEquals(0, backups)
            assertEquals(0, phone.applyCalls)
        }
    }

    @Test
    fun `a failing backup stops the merge before anything is written`() {
        val key = testKey()
        val phone = phoneWithData()
        FakePc(key, PcReply.Merge(::pcAfter)).use { pc ->
            try {
                runBlocking { session(phone, backup = { throw java.io.IOException("disk full") }).run(inviteFor(pc.port, key)) }
                fail("merged without a backup")
            } catch (e: SyncException) {
                assertEquals(SyncErrorKind.LOCAL, e.kind)
            }
            assertEquals(0, phone.applyCalls)
            assertEquals(1, phone.playlists.size)
        }
    }

    @Test
    fun `an error message from the PC is reported with its kind`() {
        val key = testKey()
        FakePc(key, PcReply.Fail("busy", "Sinkron lain sedang jalan")).use { pc ->
            try {
                runBlocking { session(phoneWithData()).run(inviteFor(pc.port, key)) }
                fail("no error")
            } catch (e: SyncException) {
                assertEquals(SyncErrorKind.PC_ERROR, e.kind)
                assertEquals("busy", e.pcCode)
                assertEquals("Sinkron lain sedang jalan", e.message)
            }
        }
        FakePc(key, PcReply.Fail("version", "v2"), helloVersion = 2).use { pc ->
            try {
                runBlocking { session(phoneWithData()).run(inviteFor(pc.port, key)) }
                fail("no error")
            } catch (e: SyncException) {
                assertEquals(SyncErrorKind.VERSION, e.kind)
            }
        }
    }

    @Test
    fun `a PC with another key just closes and the phone says so`() {
        val phone = phoneWithData()
        FakePc(testKey(2), PcReply.Merge(::pcAfter)).use { pc ->
            try {
                runBlocking { session(phone).run(inviteFor(pc.port, testKey(1))) }
                fail("talked to a PC with another key")
            } catch (e: SyncException) {
                assertEquals(SyncErrorKind.CLOSED_BY_PC, e.kind)
            }
            pc.join()
            assertTrue(pc.authFailed)
            assertNull(pc.received)
        }
        assertEquals(0, phone.applyCalls)
    }

    @Test
    fun `a PC that never answers ends in a timeout`() {
        val key = testKey()
        FakePc(key, PcReply.Silence).use { pc ->
            try {
                runBlocking { session(phoneWithData(), previewTimeout = 400).run(inviteFor(pc.port, key)) }
                fail("no timeout")
            } catch (e: SyncException) {
                assertEquals(SyncErrorKind.TIMEOUT, e.kind)
            }
        }
    }

    @Test
    fun `the first host that answers wins and a dead one does not matter`() {
        val live = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val dead = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")).also { it.close() } // nothing listens there
        live.use {
            val socket =
                runBlocking {
                    SyncConnect.connectFirst(
                        listOf(
                            { SyncConnect.plain.connect("127.0.0.1", dead.localPort, 1_000) },
                            { SyncConnect.plain.connect("127.0.0.1", live.localPort, 1_000) },
                        ),
                    )
                }
            socket.use { assertTrue(it.isConnected) }
        }
    }

    @Test
    fun `when nothing answers the error says so`() {
        val dead = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")).also { it.close() }
        try {
            runBlocking {
                SyncConnect.connectFirst(List(3) { { SyncConnect.plain.connect("127.0.0.1", dead.localPort, 500) } })
            }
            fail("connected to nothing")
        } catch (e: SyncException) {
            assertEquals(SyncErrorKind.CONNECT_FAILED, e.kind)
        }
        try {
            runBlocking { SyncConnect.connectFirst(emptyList<() -> Socket>()) }
            fail("connected to nothing")
        } catch (e: SyncException) {
            assertEquals(SyncErrorKind.CONNECT_FAILED, e.kind)
        }
    }

    @Test
    fun `the key is wiped after the session`() {
        val key = testKey()
        val invite = inviteFor(1, key)
        FakePc(key, PcReply.Decline).use { pc ->
            val real = inviteFor(pc.port, key)
            runBlocking { session(phoneWithData()).run(real) }
            assertTrue(real.keyBytes().all { it == 0.toByte() })
        }
        assertNotNull(invite)
    }
}
