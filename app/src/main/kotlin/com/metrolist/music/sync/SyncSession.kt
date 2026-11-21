/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The phone's side of one sync session: connect (every host in parallel), hello, snapshot out, wait for the PC's preview
 * answer, safety backup, merge, done. Pure JVM (java.net sockets), so the tests run it against a fake PC on loopback.
 */

package com.metrolist.music.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** Where the session is, for the progress text. */
enum class SyncStep { CONNECTING, HANDSHAKE, READING, SENDING, WAITING_PC, BACKUP, APPLYING, FINISHING }

/** How one attempt to open a socket to the PC is made (the Android one can bind to the Wi-Fi network). */
fun interface SyncConnector {
    @Throws(IOException::class)
    fun connect(host: String, port: Int, timeoutMs: Int): Socket
}

object SyncConnect {
    const val CONNECT_TIMEOUT_MS = 3_000
    const val READ_TIMEOUT_MS = 60_000
    const val PREVIEW_TIMEOUT_MS = 5 * 60_000

    val plain = SyncConnector { host, port, timeoutMs ->
        Socket().also { socket ->
            try {
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            } catch (e: IOException) {
                socket.close()
                throw e
            }
        }
    }

    /**
     * Tries every attempt at once and returns the first socket that connects; sockets that connect later are closed.
     * Fails with CONNECT_FAILED when all of them fail.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun connectFirst(attempts: List<() -> Socket>): Socket {
        if (attempts.isEmpty()) throw SyncException(SyncErrorKind.CONNECT_FAILED, "No address to try")
        val winner = CompletableDeferred<Socket>()
        val failures = AtomicInteger(0)
        val lastError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var succeeded = false
        try {
            for (attempt in attempts) {
                scope.launch {
                    try {
                        val socket = attempt()
                        if (!winner.complete(socket)) socket.close()
                    } catch (e: Throwable) {
                        lastError.set(e)
                        if (failures.incrementAndGet() == attempts.size) {
                            winner.completeExceptionally(
                                SyncException(SyncErrorKind.CONNECT_FAILED, "Nothing answered", lastError.get()),
                            )
                        }
                    }
                }
            }
            val socket = winner.await()
            succeeded = true
            return socket
        } finally {
            scope.cancel()
            if (!succeeded) {
                // The caller gave up: a socket that connects from now on must not leak.
                winner.cancel()
                if (winner.isCompleted && !winner.isCancelled) runCatching { winner.getCompleted().close() }
            }
        }
    }
}

class SyncOutcome(
    val pcName: String,
    /** Null when the PC declined. */
    val summary: MergeSummary?,
    val declined: Boolean,
    val backupFile: File?,
)

class SyncSession(
    private val engine: SyncEngine,
    private val makeBackup: suspend () -> File,
    private val attemptsFor: (SyncInvite) -> List<() -> Socket>,
    private val deviceName: String,
    private val appVersion: String,
    private val onStep: (SyncStep) -> Unit = {},
    private val readTimeoutMs: Int = SyncConnect.READ_TIMEOUT_MS,
    private val previewTimeoutMs: Int = SyncConnect.PREVIEW_TIMEOUT_MS,
) {
    @Volatile
    private var socket: Socket? = null

    /** Closes the connection so a blocked read ends at once (the Cancel button). */
    fun abort() {
        runCatching { socket?.close() }
    }

    suspend fun run(invite: SyncInvite): SyncOutcome =
        try {
            runInternal(invite)
        } finally {
            invite.wipe()
            abort()
        }

    private suspend fun runInternal(invite: SyncInvite): SyncOutcome {
        onStep(SyncStep.CONNECTING)
        val connected = SyncConnect.connectFirst(attemptsFor(invite))
        socket = connected
        connected.tcpNoDelay = true
        connected.soTimeout = readTimeoutMs

        val channel =
            SyncChannel(
                connected.getInputStream().buffered(),
                connected.getOutputStream().buffered(),
                SyncCipher(invite.keyBytes(), SyncProtocol.DIRECTION_PHONE_TO_PC, SyncProtocol.DIRECTION_PC_TO_PHONE),
            )

        onStep(SyncStep.HANDSHAKE)
        io { channel.send(SyncMessage.Hello(SYNC_PROTOCOL_VERSION, deviceName, appVersion, "android")) }
        val hello = io { channel.receive() }
        val pcName =
            when (hello) {
                is SyncMessage.HelloOk -> {
                    if (hello.v != SYNC_PROTOCOL_VERSION) throw SyncException(SyncErrorKind.VERSION, "PC speaks v${hello.v}")
                    hello.device.ifBlank { invite.pcName }
                }
                is SyncMessage.Error -> throw pcError(hello)
                else -> throw SyncException(SyncErrorKind.PROTOCOL, "Expected hello_ok")
            }

        onStep(SyncStep.READING)
        val snapshot =
            try {
                engine.buildSnapshot()
            } catch (e: Exception) {
                throw SyncException(SyncErrorKind.LOCAL, "Could not read the phone data", e)
            }

        onStep(SyncStep.SENDING)
        io { channel.send(SyncMessage.SnapshotMsg(snapshot)) }

        onStep(SyncStep.WAITING_PC)
        connected.soTimeout = previewTimeoutMs
        val answer = io { channel.receive() }
        connected.soTimeout = readTimeoutMs
        val theirs =
            when (answer) {
                is SyncMessage.SnapshotMsg -> answer.data
                is SyncMessage.Declined -> return SyncOutcome(pcName, null, declined = true, backupFile = null)
                is SyncMessage.Error -> throw pcError(answer)
                else -> throw SyncException(SyncErrorKind.PROTOCOL, "Expected snapshot or declined")
            }
        if (theirs.v != SYNC_PROTOCOL_VERSION) throw SyncException(SyncErrorKind.VERSION, "Snapshot is v${theirs.v}")

        onStep(SyncStep.BACKUP)
        val backup =
            try {
                makeBackup()
            } catch (e: Exception) {
                // No backup, no merge.
                throw SyncException(SyncErrorKind.LOCAL, "Could not make the safety backup", e)
            }

        onStep(SyncStep.APPLYING)
        val summary =
            try {
                engine.merge(theirs)
            } catch (e: Exception) {
                throw SyncException(SyncErrorKind.LOCAL, "Could not apply the merge", e)
            }

        onStep(SyncStep.FINISHING)
        // The merge is done and saved; if the PC already went away there is nothing to fix.
        try {
            io { channel.send(SyncMessage.Done(summary.toJson())) }
        } catch (_: SyncException) {
        }
        return SyncOutcome(pcName, summary, declined = false, backupFile = backup)
    }

    private fun pcError(error: SyncMessage.Error): SyncException =
        SyncException(
            if (error.code == "version") SyncErrorKind.VERSION else SyncErrorKind.PC_ERROR,
            error.message,
            pcCode = error.code,
        )

    /** Socket work on the IO dispatcher with the failures turned into [SyncException]. */
    private suspend fun <T> io(block: () -> T): T =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: SyncException) {
                throw e
            } catch (e: SocketTimeoutException) {
                throw SyncException(SyncErrorKind.TIMEOUT, "No answer in time", e)
            } catch (e: SyncAuthException) {
                throw SyncException(SyncErrorKind.CLOSED_BY_PC, "Code did not match", e)
            } catch (e: EOFException) {
                throw SyncException(SyncErrorKind.CLOSED_BY_PC, "The PC closed the connection", e)
            } catch (e: SocketException) {
                throw SyncException(SyncErrorKind.CLOSED_BY_PC, "The connection was closed", e)
            } catch (e: IOException) {
                throw SyncException(SyncErrorKind.NETWORK, "Network error", e)
            }
        }
}
