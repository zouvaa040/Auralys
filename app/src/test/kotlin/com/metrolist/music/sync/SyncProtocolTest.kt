package com.metrolist.music.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException

class SyncProtocolTest {
    // ---------------------------------------------------------------- golden snapshot

    /** Drops nulls and the field the golden file adds to prove unknown fields are ignored. */
    private fun normalize(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject ->
                JsonObject(
                    element.filterValues { it !is JsonNull }.filterKeys { it != "futureField" }
                        .mapValues { normalize(it.value) },
                )
            is JsonArray -> JsonArray(element.map(::normalize))
            else -> element
        }

    @Test
    fun `golden snapshot parses and re-serialises without losing anything`() {
        val text = golden()
        val snapshot = snapshotFromJson(text)
        assertEquals(1, snapshot.v)
        assertEquals("android", snapshot.platform)
        assertEquals(1793880000000L, snapshot.createdAt)
        assertEquals(3, snapshot.songs.size)
        assertEquals("Lagu \"Tes\" — dengan karakter 日本語 & emoji 🎵", snapshot.songs[2].title)
        assertEquals(213, snapshot.songs[0].duration)
        assertEquals(null, snapshot.songs[2].thumb)
        assertEquals(listOf(SyncLiked("dQw4w9WgXcQ", 1793800000000L), SyncLiked("kJQP7kiw5Fk", null)), snapshot.liked)
        assertEquals("VLPLabcdefghijklmnop", snapshot.playlists[2].browseId)
        assertEquals(emptyList<String>(), snapshot.playlists[1].songs)
        assertEquals(listOf("album", "artist", "playlist"), snapshot.saved.map { it.kind })
        assertEquals(1793873000123L, snapshot.events[1].at)
        assertEquals(listOf("rick astley", "lagu santai", "Dewa 19"), snapshot.searches)
        assertEquals(listOf(20400L, 20401L, 20402L), snapshot.days)

        val again = SyncJson.parseToJsonElement(snapshot.toJson())
        val original = SyncJson.parseToJsonElement(text)
        assertEquals(normalize(original), normalize(again))
        // and a second round trip is stable
        assertEquals(snapshot, snapshotFromJson(snapshot.toJson()))
    }

    @Test
    fun `optional fields are left out and unknown or missing fields do not matter`() {
        val json = """{"v":1,"platform":"windows","extra":[1,2],"songs":[{"id":"a","title":"T","artist":"X","weird":1}],"events":[{"id":"a","at":5.0,"ms":7.0}]}"""
        val snapshot = snapshotFromJson(json)
        assertEquals(5L, snapshot.events[0].at)
        assertEquals(emptyList<SyncLiked>(), snapshot.liked)
        val out = snapshot.toJson()
        assertFalse(out.contains("artistId"))
        assertFalse(out.contains("null"))
        assertTrue(out.contains("\"at\":5,"))
    }

    // ---------------------------------------------------------------- messages

    @Test
    fun `messages round trip`() {
        val all =
            listOf(
                SyncMessage.Hello(1, "Pixel 8", "4.3.3", "android"),
                SyncMessage.HelloOk(1, "PC-DAVA", "1.6.0", "windows"),
                SyncMessage.SnapshotMsg(snapshotFromJson(golden())),
                SyncMessage.Declined,
                SyncMessage.Done(MergeSummary(songsAdded = 2, eventsAdded = 9).toJson()),
                SyncMessage.Error("version", "bad version"),
            )
        for (message in all) {
            val bytes = SyncMessages.encode(message)
            val decoded = SyncMessages.decode(bytes)
            if (message is SyncMessage.SnapshotMsg) {
                assertEquals(message.data, (decoded as SyncMessage.SnapshotMsg).data)
            } else {
                assertEquals(message, decoded)
            }
        }
        val text = SyncMessages.encode(SyncMessage.Hello(1, "Pixel", "4.3.3", "android")).toString(Charsets.UTF_8)
        assertEquals("""{"t":"hello","v":1,"device":"Pixel","app":"4.3.3","platform":"android"}""", text)
    }

    @Test
    fun `an unknown or broken message is a protocol error`() {
        for (bad in listOf("""{"t":"surprise"}""", "not json", """{"x":1}""", """{"t":"snapshot"}""")) {
            try {
                SyncMessages.decode(bad.toByteArray())
                fail("accepted: $bad")
            } catch (e: SyncException) {
                assertEquals(SyncErrorKind.PROTOCOL, e.kind)
            }
        }
    }

    // ---------------------------------------------------------------- crypto

    private fun pair(key: ByteArray = testKey()) =
        SyncCipher(key, SyncProtocol.DIRECTION_PHONE_TO_PC, SyncProtocol.DIRECTION_PC_TO_PHONE) to
            SyncCipher(key, SyncProtocol.DIRECTION_PC_TO_PHONE, SyncProtocol.DIRECTION_PHONE_TO_PC)

    @Test
    fun `frames round trip in both directions and each frame is different`() {
        val (phone, pc) = pair()
        val a = phone.seal("hello".toByteArray())
        val b = phone.seal("hello".toByteArray())
        assertNotEquals(a.toList(), b.toList()) // random IV
        assertEquals(12 + 5 + 16, a.size)
        assertEquals("hello", pc.open(a).decodeToString())
        assertEquals("hello", pc.open(b).decodeToString())
        val back = pc.seal("world".toByteArray())
        assertEquals("world", phone.open(back).decodeToString())
    }

    @Test
    fun `a wrong key is an authentication failure`() {
        val phone = SyncCipher(testKey(1), SyncProtocol.DIRECTION_PHONE_TO_PC, SyncProtocol.DIRECTION_PC_TO_PHONE)
        val pc = SyncCipher(testKey(2), SyncProtocol.DIRECTION_PC_TO_PHONE, SyncProtocol.DIRECTION_PHONE_TO_PC)
        try {
            pc.open(phone.seal("x".toByteArray()))
            fail("opened with the wrong key")
        } catch (_: SyncAuthException) {
        }
    }

    @Test
    fun `a replayed or reordered frame is rejected and a tampered one too`() {
        val (phone, pc) = pair()
        val first = phone.seal("one".toByteArray())
        val second = phone.seal("two".toByteArray())
        // reordering: the receiver expects counter 0
        try {
            pc.open(second)
            fail("accepted reordered frame")
        } catch (_: SyncAuthException) {
        }
        assertEquals("one", pc.open(first).decodeToString())
        // replay of the frame it already accepted
        try {
            pc.open(first)
            fail("accepted replay")
        } catch (_: SyncAuthException) {
        }
        assertEquals("two", pc.open(second).decodeToString())
        // a flipped bit
        val third = phone.seal("three".toByteArray())
        third[third.size - 1] = (third[third.size - 1].toInt() xor 1).toByte()
        try {
            pc.open(third)
            fail("accepted tampered frame")
        } catch (_: SyncAuthException) {
        }
    }

    @Test
    fun `a frame cannot be reflected back in the other direction`() {
        val (phone, _) = pair()
        val frame = phone.seal("mine".toByteArray())
        val otherPhone = SyncCipher(testKey(), SyncProtocol.DIRECTION_PHONE_TO_PC, SyncProtocol.DIRECTION_PC_TO_PHONE)
        try {
            otherPhone.open(frame) // a phone must not accept a frame stamped phone->PC
            fail("accepted a reflected frame")
        } catch (_: SyncAuthException) {
        }
    }

    @Test
    fun `known answer for the frame layout so the other side can check theirs`() {
        val key = ByteArray(32) { it.toByte() } // 00 01 02 ... 1f
        val iv = ByteArray(12) { (0xA0 + it).toByte() }
        val phone = SyncCipher(key, SyncProtocol.DIRECTION_PHONE_TO_PC, SyncProtocol.DIRECTION_PC_TO_PHONE)
        val frame = phone.seal("auralys".toByteArray(), fixedIv = iv)
        val hex = frame.joinToString("") { "%02x".format(it) }
        assertEquals(KNOWN_FRAME_HEX, hex)
        val pc = SyncCipher(key, SyncProtocol.DIRECTION_PC_TO_PHONE, SyncProtocol.DIRECTION_PHONE_TO_PC)
        assertEquals("auralys", pc.open(frame).decodeToString())
    }

    @Test
    fun `gzip round trip and the size limit`() {
        val data = ByteArray(100_000) { (it % 7).toByte() }
        assertArrayEquals(data, SyncGzip.decompress(SyncGzip.compress(data)))
        try {
            SyncGzip.decompress(SyncGzip.compress(data), limit = 1_000)
            fail("zip bomb accepted")
        } catch (e: SyncException) {
            assertEquals(SyncErrorKind.PROTOCOL, e.kind)
        }
        try {
            SyncGzip.decompress("plain".toByteArray())
            fail("non gzip accepted")
        } catch (e: SyncException) {
            assertEquals(SyncErrorKind.PROTOCOL, e.kind)
        }
    }

    @Test
    fun `framing writes a big endian length and rejects an oversized one`() {
        val out = ByteArrayOutputStream()
        SyncFraming.writeFrame(out, byteArrayOf(9, 8, 7))
        assertEquals(listOf<Byte>(0, 0, 0, 3, 9, 8, 7), out.toByteArray().toList())
        assertArrayEquals(byteArrayOf(9, 8, 7), SyncFraming.readFrame(ByteArrayInputStream(out.toByteArray())))

        val huge = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(SyncProtocol.MAX_FRAME_BYTES + 1) }
        try {
            SyncFraming.readFrame(ByteArrayInputStream(huge.toByteArray()))
            fail("oversized frame accepted")
        } catch (e: SyncException) {
            assertEquals(SyncErrorKind.PROTOCOL, e.kind)
        }
        try {
            SyncFraming.readFrame(ByteArrayInputStream(byteArrayOf(0, 0)))
            fail("truncated frame accepted")
        } catch (_: EOFException) {
        }
    }

    @Test
    fun `a whole channel moves a snapshot`() {
        val (phone, pc) = pair()
        val wire = ByteArrayOutputStream()
        SyncChannel(ByteArrayInputStream(ByteArray(0)), wire, phone).send(SyncMessage.SnapshotMsg(snapshotFromJson(golden())))
        val received = SyncChannel(ByteArrayInputStream(wire.toByteArray()), ByteArrayOutputStream(), pc).receive()
        assertEquals(snapshotFromJson(golden()), (received as SyncMessage.SnapshotMsg).data)
    }

    // ---------------------------------------------------------------- the QR text

    @Test
    fun `a good code is parsed`() {
        val key = testKey()
        val invite =
            SyncInvite.parse("  auralys://sync?v=1&h=192.168.1.20,10.0.0.5&p=51234&k=${keyToken(key)}&n=PC%20Dava%20%F0%9F%8E%B5  ")
        assertEquals(listOf("192.168.1.20", "10.0.0.5"), invite.hosts)
        assertEquals(51234, invite.port)
        assertEquals("PC Dava 🎵", invite.pcName)
        assertArrayEquals(key, invite.keyBytes())
        assertFalse(invite.toString().contains(keyToken(key)))
        invite.wipe()
        assertTrue(invite.keyBytes().all { it == 0.toByte() })
    }

    @Test
    fun `padding in the key and an encoded comma are tolerated`() {
        val key = testKey(3)
        val padded = java.util.Base64.getUrlEncoder().encodeToString(key)
        val invite = SyncInvite.parse("auralys://sync?v=1&h=10.1.1.1%2C10.1.1.2&p=1&k=$padded")
        assertEquals(2, invite.hosts.size)
        assertEquals("10.1.1.1", invite.pcName)
        assertArrayEquals(key, invite.keyBytes())
    }

    @Test
    fun `bad codes are refused with the right kind`() {
        val k = keyToken(testKey())
        val cases =
            mapOf(
                "https://example.com/?v=1" to SyncErrorKind.BAD_CODE,
                "auralys://sync" to SyncErrorKind.BAD_CODE,
                "auralys://sync?v=1&h=1.2.3.4&p=99999&k=$k" to SyncErrorKind.BAD_CODE,
                "auralys://sync?v=1&h=&p=5&k=$k" to SyncErrorKind.BAD_CODE,
                "auralys://sync?v=1&h=1.2.3.4&p=5&k=short" to SyncErrorKind.BAD_CODE,
                "auralys://sync?v=1&h=1.2.3.4&p=5" to SyncErrorKind.BAD_CODE,
                "auralys://sync?h=1.2.3.4&p=5&k=$k" to SyncErrorKind.BAD_CODE,
                "auralys://sync?v=2&h=1.2.3.4&p=5&k=$k" to SyncErrorKind.VERSION,
            )
        for ((text, kind) in cases) {
            try {
                SyncInvite.parse(text)
                fail("accepted: $text")
            } catch (e: SyncException) {
                assertEquals(text, kind, e.kind)
            }
        }
    }

    private companion object {
        // Checked against Python `cryptography` AESGCM. AES-256-GCM, key 00..1f, IV a0..ab, AAD "auralys-sync-v1" 01 0000000000000000, plaintext "auralys"
        const val KNOWN_FRAME_HEX = "a0a1a2a3a4a5a6a7a8a9aaab876d0e4c29b2715f29680ea3adaab72a6bd71aea6098e6"
    }
}
