/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The Android glue of the sync: builds a [SyncSession] on the real Room database, the app files and the Wi-Fi network.
 */

package com.metrolist.music.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.metrolist.music.BuildConfig
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.utils.ArtistNameAliases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

object SyncAndroid {
    private const val BACKUP_DIR = "sync_backup"
    private const val STATE_FILE = "sync_state.json"
    private const val SETTINGS_FILE = "datastore/settings.preferences_pb"

    fun backupDir(context: Context) = File(context.filesDir, BACKUP_DIR)

    fun createSession(
        context: Context,
        database: MusicDatabase,
        onStep: (SyncStep) -> Unit,
    ): SyncSession {
        val appContext = context.applicationContext
        val engine =
            SyncEngine(
                store = RoomSyncStore(database),
                state = FileSyncStateStore(File(appContext.filesDir, STATE_FILE)),
                newPlaylistId = { PlaylistEntity.generatePlaylistId() },
            )
        val backup =
            SyncBackup(
                dir = backupDir(appContext),
                databaseFile = { database.openHelper.writableDatabase.path?.let(::File) },
                settingsFile = File(appContext.filesDir, SETTINGS_FILE),
                extraEntries = {
                    mapOf(ArtistNameAliases.BACKUP_FILENAME to ArtistNameAliases.serialize().encodeToByteArray())
                },
                checkpoint = { database.checkpoint() },
            )
        return SyncSession(
            engine = engine,
            makeBackup = { withContext(Dispatchers.IO) { backup.create() } },
            attemptsFor = { invite -> attempts(appContext, invite) },
            deviceName = deviceName(),
            appVersion = BuildConfig.BASE_VERSION_NAME,
            onStep = onStep,
        )
    }

    private fun deviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ").take(60)

    /**
     * One attempt per host, plus (when the phone is on Wi-Fi) one that is bound to the Wi-Fi network, because with a VPN
     * or a Wi-Fi without internet Android may otherwise send a LAN address out over mobile data.
     */
    @Suppress("DEPRECATION")
    private fun attempts(context: Context, invite: SyncInvite): List<() -> Socket> {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val lan: Network? =
            runCatching {
                manager?.allNetworks?.firstOrNull { network ->
                    manager.getNetworkCapabilities(network)?.let {
                        it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    } == true
                }
            }.getOrNull()
        return buildList {
            for (host in invite.hosts) {
                if (lan != null) {
                    add {
                        val socket = lan.socketFactory.createSocket()
                        try {
                            socket.connect(InetSocketAddress(host, invite.port), SyncConnect.CONNECT_TIMEOUT_MS)
                        } catch (e: Exception) {
                            socket.close()
                            throw e
                        }
                        socket
                    }
                }
                add { SyncConnect.plain.connect(host, invite.port, SyncConnect.CONNECT_TIMEOUT_MS) }
            }
        }
    }
}
