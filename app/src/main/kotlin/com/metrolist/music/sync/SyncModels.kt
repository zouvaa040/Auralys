/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Auralys phone <-> PC sync, protocol v1 (see Metrolist-work/SYNC-PROTOCOL.md). The snapshot model, one class per JSON
 * object of the spec. Unknown fields are ignored, lists may be missing, numbers may arrive as 5 or 5.0.
 */

package com.metrolist.music.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

const val SYNC_PROTOCOL_VERSION = 1

/** A whole number that is also accepted when the other side wrote it as 5.0. Always written as a plain integer. */
internal object FlexLongSerializer : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor("FlexLong", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)

    override fun deserialize(decoder: Decoder): Long {
        val input = decoder as? JsonDecoder ?: return decoder.decodeLong()
        val element = input.decodeJsonElement() as? JsonPrimitive ?: error("number expected")
        return element.longOrNull ?: element.doubleOrNull?.toLong() ?: error("number expected: ${element.content}")
    }
}

@Serializable
data class SyncSong(
    val id: String,
    val title: String = "",
    val artist: String = "",
    val artistId: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    /** Seconds. */
    val duration: Int? = null,
    val thumb: String? = null,
)

@Serializable
data class SyncLiked(
    val id: String,
    @Serializable(with = FlexLongSerializer::class) val at: Long? = null,
)

@Serializable
data class SyncPlaylist(
    val sid: String,
    val name: String = "",
    @Serializable(with = FlexLongSerializer::class) val createdAt: Long = 0L,
    val songs: List<String> = emptyList(),
    val browseId: String? = null,
)

@Serializable
data class SyncSaved(
    val kind: String,
    val id: String,
    val title: String = "",
    val subtitle: String? = null,
    val thumb: String? = null,
) {
    companion object {
        const val ALBUM = "album"
        const val ARTIST = "artist"
        const val PLAYLIST = "playlist"
    }
}

@Serializable
data class SyncEvent(
    val id: String,
    @Serializable(with = FlexLongSerializer::class) val at: Long,
    @Serializable(with = FlexLongSerializer::class) val ms: Long = 0L,
)

@Serializable
data class Snapshot(
    val v: Int = SYNC_PROTOCOL_VERSION,
    val platform: String = "",
    @Serializable(with = FlexLongSerializer::class) val createdAt: Long = 0L,
    val songs: List<SyncSong> = emptyList(),
    val liked: List<SyncLiked> = emptyList(),
    val playlists: List<SyncPlaylist> = emptyList(),
    val saved: List<SyncSaved> = emptyList(),
    val events: List<SyncEvent> = emptyList(),
    val searches: List<String> = emptyList(),
    val days: List<@Serializable(with = FlexLongSerializer::class) Long> = emptyList(),
)

/** The one JSON configuration both directions use. Nulls are left out, which is how the spec writes optional fields. */
val SyncJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

fun Snapshot.toJson(): String = SyncJson.encodeToString(Snapshot.serializer(), this)

fun snapshotFromJson(text: String): Snapshot = SyncJson.decodeFromString(Snapshot.serializer(), text)
