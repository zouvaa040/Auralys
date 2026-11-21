/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.local

import java.util.Locale

/** One audio row read from MediaStore, with the raw tag values (nothing cleaned yet). */
data class LocalTrack(
    val mediaStoreId: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumId: Long?,
    val durationMs: Long,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
    val year: Int?,
    val displayName: String?,
    /** Folder of the file: `RELATIVE_PATH` (Android 10+) or the full path before that. May be null. */
    val folder: String?,
)

/** The texts the scanner needs from the app language (kept out of the pure rules so they can be tested). */
data class LocalLabels(
    val unknownArtist: String,
    val untitled: String,
)

/** Which files count as music, and how raw tags become what the library shows. */
object LocalTrackRules {
    const val MIN_DURATION_MS = 30_000L

    /** MediaStore writes this for a missing tag. */
    private const val MEDIASTORE_UNKNOWN = "<unknown>"

    // Folders that hold voice notes, recordings, ringtones and similar. Lower case, with a slash on both sides.
    private val blockedFolders =
        listOf(
            "/alarms/",
            "/notifications/",
            "/ringtones/",
            "/podcasts/",
            "/audiobooks/",
            "/recordings/",
            "/call recordings/",
            "/voice recorder/",
            "/voicerecorder/",
            "/sound_recorder/",
            "/soundrecorder/",
            "/call_rec/",
            "/callrecorder/",
            "/whatsapp voice notes/",
        )

    // Formats that are never songs (voice notes, ringtone formats). The rest is left to the player.
    private val blockedExtensions = setOf("amr", "awb", "3gp", "3ga", "mid", "midi", "xmf", "mxmf", "imy", "ota", "rtttl", "rtx")

    /** True when [folder] is one of the recording/ringtone folders. Works for `Recordings/Voice/` and `/storage/emulated/0/Alarms/x.mp3`. */
    fun isBlockedFolder(folder: String?): Boolean {
        if (folder.isNullOrBlank()) return false
        var path = folder.replace('\\', '/').lowercase(Locale.ROOT)
        if (!path.startsWith("/")) path = "/$path"
        if (!path.endsWith("/")) path = "$path/"
        return blockedFolders.any { path.contains(it) }
    }

    fun isBlockedExtension(displayName: String?): Boolean {
        val name = displayName ?: return false
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return false
        return name.substring(dot + 1).lowercase(Locale.ROOT) in blockedExtensions
    }

    fun isEligible(track: LocalTrack): Boolean =
        track.durationMs >= MIN_DURATION_MS &&
            !isBlockedFolder(track.folder) &&
            !isBlockedExtension(track.displayName)

    /** A tag with surrounding spaces and MediaStore's "<unknown>" placeholder removed; null when nothing is left. */
    fun cleanTag(value: String?): String? =
        value
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals(MEDIASTORE_UNKNOWN, ignoreCase = true) }

    /** The title tag, else the file name without its extension, else [LocalLabels.untitled]. */
    fun titleOf(track: LocalTrack, labels: LocalLabels): String =
        cleanTag(track.title)
            ?: cleanTag(track.displayName?.let { name -> name.substringBeforeLast('.', name) })
            ?: labels.untitled

    /** The artist tag, or null when the file has none (the scanner then uses the single unknown-artist row). */
    fun artistOf(track: LocalTrack): String? = cleanTag(track.artist)

    /** The album tag, or null. A file without an album tag has no album row. */
    fun albumOf(track: LocalTrack): String? = cleanTag(track.album)

    fun yearOf(track: LocalTrack): Int? = track.year?.takeIf { it in 1000..9999 }

    /** Length in whole seconds, as the song table stores it. */
    fun durationSeconds(track: LocalTrack): Int = (track.durationMs / 1000L).toInt().coerceAtLeast(1)

    /**
     * The row of every MediaStore id that is in the database but was not seen in this scan.
     *
     * An empty scan with a non-empty library is treated as "storage not readable" (permission taken away,
     * memory card out, MediaStore busy) and removes nothing, so playlists are not emptied by a hiccup.
     */
    fun idsToRemove(existingIds: Collection<String>, seenIds: Set<String>): List<String> {
        if (existingIds.isEmpty() || seenIds.isEmpty()) return emptyList()
        return existingIds.filter { it !in seenIds }
    }
}
