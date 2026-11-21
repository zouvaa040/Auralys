/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.local

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory

/** True for the addresses of files on the phone (`content://...`, `file://...`). Everything else is a YouTube id or stream. */
fun isDeviceScheme(scheme: String?): Boolean = scheme == "content" || scheme == "file"

/**
 * Sends files on the phone straight to [local] and everything else to [remote] (the YouTube resolver and its caches).
 *
 * A device file therefore never goes through the stream lookup, the player cache or the download cache: the cache layers
 * would copy the whole song into the app's storage for nothing.
 */
@UnstableApi
class DeviceAwareDataSourceFactory(
    private val remote: DataSource.Factory,
    private val local: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = DeviceAwareDataSource(remote, local)
}

@UnstableApi
private class DeviceAwareDataSource(
    private val remote: DataSource.Factory,
    private val local: DataSource.Factory,
) : DataSource {
    private val listeners = ArrayList<TransferListener>()
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        active?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = (if (isDeviceScheme(dataSpec.uri.scheme)) local else remote).createDataSource()
        listeners.forEach(source::addTransferListener)
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(active) { "read before open" }.read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        val source = active
        active = null
        source?.close()
    }
}

/**
 * The extractors for streams are the short list the app has always used (only the containers YouTube sends); files on the phone
 * can be mp3, flac, ogg, wav, aac and more, so they get the full list of the player.
 */
@UnstableApi
class DeviceAwareExtractorsFactory(
    private val streams: ExtractorsFactory,
    private val files: ExtractorsFactory = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true),
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = streams.createExtractors()

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> =
        if (isDeviceScheme(uri.scheme)) files.createExtractors(uri, responseHeaders) else streams.createExtractors(uri, responseHeaders)
}
