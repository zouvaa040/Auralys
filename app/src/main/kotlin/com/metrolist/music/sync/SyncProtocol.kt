/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Sync wire format: JSON messages, gzip, AES-256-GCM frames with a message counter, 4-byte length prefix.
 * Pure JVM (javax.crypto), no Android classes, so it is unit tested without a device.
 */

package com.metrolist.music.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What went wrong, in terms the screen can explain to the user in plain Indonesian. */
enum class SyncErrorKind {
    /** The scanned/pasted text is not an Auralys sync code. */
    BAD_CODE,

    /** No host from the QR answered within the timeout. */
    CONNECT_FAILED,

    /** The PC closed the connection without an answer (wrong or expired code, session closed, 5 bad tries). */
    CLOSED_BY_PC,

    /** No message arrived in time. */
    TIMEOUT,

    /** The PC speaks another protocol version. */
    VERSION,

    /** The PC sent an `error` message. */
    PC_ERROR,

    /** Garbage on the wire, unknown message type, frame too large. */
    PROTOCOL,

    /** Reading or writing the local database failed. */
    LOCAL,

    /** Any other socket failure. */
    NETWORK,
}

class SyncException(
    val kind: SyncErrorKind,
    message: String,
    cause: Throwable? = null,
    /** The `code` field of a PC `error` message. */
    val pcCode: String? = null,
) : Exception(message, cause)

/** A frame that did not decrypt: wrong key, replay, reordering or tampering. */
class SyncAuthException(message: String, cause: Throwable? = null) : GeneralSecurityException(message, cause)

object SyncProtocol {
    const val MAX_FRAME_BYTES = 64 * 1024 * 1024
    const val MAX_PLAIN_BYTES = 256 * 1024 * 1024
    const val DIRECTION_PHONE_TO_PC: Byte = 0x01
    const val DIRECTION_PC_TO_PHONE: Byte = 0x02
    val AAD_PREFIX: ByteArray = "auralys-sync-v1".toByteArray(Charsets.US_ASCII)
    const val KEY_BYTES = 32
    const val IV_BYTES = 12
    const val TAG_BITS = 128
}

// -------------------------------------------------------------------------------------------------------------------
// Messages
// -------------------------------------------------------------------------------------------------------------------

sealed interface SyncMessage {
    data class Hello(val v: Int, val device: String, val app: String, val platform: String) : SyncMessage

    data class HelloOk(val v: Int, val device: String, val app: String, val platform: String) : SyncMessage

    data class SnapshotMsg(val data: Snapshot) : SyncMessage

    data object Declined : SyncMessage

    data class Done(val summary: JsonObject) : SyncMessage

    data class Error(val code: String, val message: String) : SyncMessage
}

object SyncMessages {
    fun encode(message: SyncMessage): ByteArray =
        when (message) {
            is SyncMessage.Hello -> helloJson("hello", message.v, message.device, message.app, message.platform)
            is SyncMessage.HelloOk -> helloJson("hello_ok", message.v, message.device, message.app, message.platform)
            is SyncMessage.SnapshotMsg -> """{"t":"snapshot","data":${message.data.toJson()}}"""
            is SyncMessage.Declined -> """{"t":"declined"}"""
            is SyncMessage.Done -> """{"t":"done","summary":${message.summary}}"""
            is SyncMessage.Error ->
                buildJsonObject {
                    put("t", "error")
                    put("code", message.code)
                    put("message", message.message)
                }.toString()
        }.toByteArray(Charsets.UTF_8)

    private fun helloJson(type: String, v: Int, device: String, app: String, platform: String): String =
        buildJsonObject {
            put("t", type)
            put("v", v)
            put("device", device)
            put("app", app)
            put("platform", platform)
        }.toString()

    /** @throws SyncException (PROTOCOL) for text that is not a known message. */
    fun decode(bytes: ByteArray): SyncMessage {
        val root: JsonObject =
            try {
                SyncJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            } catch (e: Exception) {
                throw SyncException(SyncErrorKind.PROTOCOL, "Message is not JSON", e)
            }
        val type = (root["t"] as? JsonPrimitive)?.contentOrNull
        return try {
            when (type) {
                "hello" -> SyncMessage.Hello(root.intField("v"), root.str("device"), root.str("app"), root.str("platform"))
                "hello_ok" -> SyncMessage.HelloOk(root.intField("v"), root.str("device"), root.str("app"), root.str("platform"))
                "snapshot" ->
                    SyncMessage.SnapshotMsg(
                        SyncJson.decodeFromJsonElement(Snapshot.serializer(), root["data"] ?: error("no data")),
                    )
                "declined" -> SyncMessage.Declined
                "done" -> SyncMessage.Done((root["summary"] as? JsonObject) ?: JsonObject(emptyMap()))
                "error" -> SyncMessage.Error(root.str("code"), root.str("message"))
                else -> throw SyncException(SyncErrorKind.PROTOCOL, "Unknown message type: $type")
            }
        } catch (e: SyncException) {
            throw e
        } catch (e: Exception) {
            throw SyncException(SyncErrorKind.PROTOCOL, "Bad $type message", e)
        }
    }

    private fun JsonObject.str(name: String): String = (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.intField(name: String): Int = (this[name] as? JsonPrimitive)?.intOrNull ?: 0
}

// -------------------------------------------------------------------------------------------------------------------
// Gzip
// -------------------------------------------------------------------------------------------------------------------

object SyncGzip {
    fun compress(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 4 + 64)
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    /** @throws SyncException (PROTOCOL) when the result would be larger than [limit] (a zip bomb) or not gzip at all. */
    fun decompress(bytes: ByteArray, limit: Int = SyncProtocol.MAX_PLAIN_BYTES): ByteArray {
        try {
            GZIPInputStream(bytes.inputStream()).use { input ->
                val out = ByteArrayOutputStream(bytes.size * 4)
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > limit) throw SyncException(SyncErrorKind.PROTOCOL, "Message too large after unzip")
                    out.write(buffer, 0, n)
                }
                return out.toByteArray()
            }
        } catch (e: SyncException) {
            throw e
        } catch (e: IOException) {
            throw SyncException(SyncErrorKind.PROTOCOL, "Message is not gzip", e)
        }
    }
}

// -------------------------------------------------------------------------------------------------------------------
// Frame cipher
// -------------------------------------------------------------------------------------------------------------------

/**
 * One side of a session. [sendDirection] is the direction byte of the frames this side writes; the frames it reads carry
 * the other one. Both counters start at 0 and only move on success, so a replayed or reordered frame fails to decrypt.
 */
class SyncCipher(
    key: ByteArray,
    private val sendDirection: Byte,
    private val receiveDirection: Byte,
    private val random: SecureRandom = SecureRandom(),
) {
    private val keySpec: SecretKeySpec

    init {
        require(key.size == SyncProtocol.KEY_BYTES) { "key must be 32 bytes" }
        keySpec = SecretKeySpec(key, "AES")
    }

    private var sendCounter = 0L
    private var receiveCounter = 0L

    /** Frame = 12-byte IV || ciphertext || 16-byte tag. [fixedIv] is for the known-answer test only. */
    fun seal(plain: ByteArray, fixedIv: ByteArray? = null): ByteArray {
        val iv = fixedIv ?: ByteArray(SyncProtocol.IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(SyncProtocol.TAG_BITS, iv))
        cipher.updateAAD(aad(sendDirection, sendCounter))
        val sealed = cipher.doFinal(plain)
        sendCounter++
        return iv + sealed
    }

    /** @throws SyncAuthException when the frame does not authenticate for the expected direction and counter. */
    fun open(frame: ByteArray): ByteArray {
        if (frame.size < SyncProtocol.IV_BYTES + SyncProtocol.TAG_BITS / 8) throw SyncAuthException("Frame too short")
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                keySpec,
                GCMParameterSpec(SyncProtocol.TAG_BITS, frame, 0, SyncProtocol.IV_BYTES),
            )
            cipher.updateAAD(aad(receiveDirection, receiveCounter))
            val plain = cipher.doFinal(frame, SyncProtocol.IV_BYTES, frame.size - SyncProtocol.IV_BYTES)
            receiveCounter++
            return plain
        } catch (e: GeneralSecurityException) {
            throw SyncAuthException("Frame did not authenticate", e)
        }
    }

    private fun aad(direction: Byte, counter: Long): ByteArray {
        val prefix = SyncProtocol.AAD_PREFIX
        val out = ByteArray(prefix.size + 1 + 8)
        prefix.copyInto(out)
        out[prefix.size] = direction
        for (i in 0 until 8) out[prefix.size + 1 + i] = (counter ushr (56 - 8 * i)).toByte()
        return out
    }
}

// -------------------------------------------------------------------------------------------------------------------
// Framing
// -------------------------------------------------------------------------------------------------------------------

object SyncFraming {
    fun writeFrame(out: OutputStream, frame: ByteArray) {
        require(frame.size <= SyncProtocol.MAX_FRAME_BYTES) { "frame too large" }
        val data = DataOutputStream(out)
        data.writeInt(frame.size)
        data.write(frame)
        data.flush()
    }

    /** @throws java.io.EOFException when the peer closed; SyncException(PROTOCOL) for a length over the limit. */
    fun readFrame(input: InputStream): ByteArray {
        val data = DataInputStream(input)
        val length = data.readInt()
        if (length < 0 || length > SyncProtocol.MAX_FRAME_BYTES) {
            throw SyncException(SyncErrorKind.PROTOCOL, "Frame length $length is not allowed")
        }
        val frame = ByteArray(length)
        data.readFully(frame)
        return frame
    }
}

/**
 * A message pipe over a connected stream pair: send = JSON -> gzip -> seal -> frame; receive = frame -> open -> gunzip ->
 * JSON. Any failure to authenticate becomes [SyncAuthException]; the caller closes the socket without saying anything.
 */
class SyncChannel(
    private val input: InputStream,
    private val output: OutputStream,
    private val cipher: SyncCipher,
) {
    fun send(message: SyncMessage) {
        val frame = cipher.seal(SyncGzip.compress(SyncMessages.encode(message)))
        SyncFraming.writeFrame(output, frame)
    }

    fun receive(): SyncMessage {
        val frame = SyncFraming.readFrame(input)
        return SyncMessages.decode(SyncGzip.decompress(cipher.open(frame)))
    }
}

// -------------------------------------------------------------------------------------------------------------------
// The QR / pasted code
// -------------------------------------------------------------------------------------------------------------------

/** What the PC put in the QR: `auralys://sync?v=1&h=<ip1>,<ip2>&p=<port>&k=<base64url key>&n=<name>`. */
class SyncInvite(
    val version: Int,
    val hosts: List<String>,
    val port: Int,
    private val key: ByteArray,
    val pcName: String,
) {
    /** A copy, so nobody can change the key and the invite can be forgotten after the session. */
    fun keyBytes(): ByteArray = key.copyOf()

    /** Never print the key. */
    override fun toString(): String = "SyncInvite(v=$version, hosts=$hosts, port=$port, pc=$pcName)"

    fun wipe() = key.fill(0)

    companion object {
        private const val PREFIX = "auralys://sync"

        /** @throws SyncException (BAD_CODE) with a short reason. */
        fun parse(rawText: String): SyncInvite {
            val text = rawText.trim()
            if (!text.startsWith(PREFIX, ignoreCase = true)) bad("not an auralys://sync code")
            val queryStart = text.indexOf('?')
            if (queryStart < 0) bad("no parameters")
            val params = HashMap<String, String>()
            for (part in text.substring(queryStart + 1).split('&')) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                val name = if (eq < 0) part else part.substring(0, eq)
                val value = if (eq < 0) "" else part.substring(eq + 1)
                params[name] = decode(value)
            }
            val version = params["v"]?.toIntOrNull() ?: bad("no version")
            if (version != SYNC_PROTOCOL_VERSION) {
                throw SyncException(SyncErrorKind.VERSION, "Code is for protocol v$version")
            }
            val hosts =
                (params["h"] ?: bad("no host"))
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
            if (hosts.isEmpty() || hosts.size > 16 || hosts.any { host -> host.any { it.isWhitespace() || it == '/' } }) {
                bad("bad host list")
            }
            val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: bad("bad port")
            val key =
                try {
                    Base64.getUrlDecoder().decode((params["k"] ?: bad("no key")).trimEnd('='))
                } catch (e: IllegalArgumentException) {
                    bad("bad key")
                }
            if (key.size != SyncProtocol.KEY_BYTES) bad("key has the wrong length")
            return SyncInvite(version, hosts, port, key, params["n"]?.takeIf { it.isNotBlank() } ?: hosts.first())
        }

        private fun decode(value: String): String =
            try {
                URLDecoder.decode(value, "UTF-8")
            } catch (e: IllegalArgumentException) {
                value
            }

        private fun bad(reason: String): Nothing = throw SyncException(SyncErrorKind.BAD_CODE, reason)
    }
}
