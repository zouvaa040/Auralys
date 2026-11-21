/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

/**
 * Helpers for the playlist CSV import. A CSV exported from Metrolist has the columns
 * `Title,Artist,Album,YouTube Video ID`: the video id says exactly which song a row is, so the import
 * uses it instead of searching again by name (a search can land on another version of the song).
 * The artist cell of such a file may also carry play counts ("462 jt pemutaran; Prince"): those are dropped.
 */
object CsvImportSupport {
    private val videoIdPattern = Regex("^[A-Za-z0-9_-]{11}$")
    private val videoIdInLink = Regex("""(?:[?&]v=|youtu\.be/|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})""")
    private val playCountPattern =
        Regex(
            """^[\d.,\s]+\s*(rb|ribu|jt|juta|m|k|b|t|miliar|mio|mln)?\s*(x\s*)?(pemutaran|ditonton|penayangan|views?|plays?|streams?|kali\s+diputar)$""",
            RegexOption.IGNORE_CASE,
        )

    /** The 11-character video id in [raw] (a bare id or any YouTube link), or null. */
    fun videoIdOf(raw: String): String? {
        val text = raw.trim()
        if (videoIdPattern.matches(text)) return text
        return videoIdInLink.find(text)?.groupValues?.get(1)
    }

    /** Artist names of one cell: split on ";" (or on "," when there is no ";"), play counts and empty parts dropped. */
    fun artistsOf(raw: String): List<String> {
        val parts = if (raw.contains(';')) raw.split(';') else raw.split(',')
        return parts
            .map { it.trim() }
            .filter { it.isNotEmpty() && !playCountPattern.matches(it) }
            .distinct()
    }

    data class Columns(
        val title: Int,
        val artist: Int,
        val video: Int,
        val hasHeader: Boolean,
    )

    private val titleNames = listOf("title", "judul", "track", "song", "lagu")
    private val artistNames = listOf("artist", "artis", "penyanyi", "performer")
    private val videoNames = listOf("video id", "videoid", "youtube id", "youtube video id", "youtube url", "url", "link", "tautan")

    /**
     * Guesses which column is which from the first row (when it is a header) and from the cells (a column whose cells are
     * almost all video ids or YouTube links is the video column). Returns null when nothing could be told.
     */
    fun detectColumns(rows: List<List<String>>): Columns? {
        val first = rows.firstOrNull() ?: return null
        fun indexOfName(names: List<String>): Int =
            first.indexOfFirst { cell -> names.any { cell.trim().equals(it, ignoreCase = true) } }
                .takeIf { it >= 0 }
                ?: first.indexOfFirst { cell -> names.any { cell.trim().contains(it, ignoreCase = true) } }

        var title = indexOfName(titleNames)
        var artist = indexOfName(artistNames)
        var video = indexOfName(videoNames)
        val headerFound = title >= 0 && artist >= 0

        val body = if (headerFound) rows.drop(1) else rows
        if (video < 0 && body.isNotEmpty()) {
            val width = rows.maxOf { it.size }
            video = (0 until width).firstOrNull { col ->
                val cells = body.mapNotNull { it.getOrNull(col) }.filter { it.isNotBlank() }
                cells.isNotEmpty() && cells.count { videoIdOf(it) != null } * 10 >= cells.size * 8
            } ?: -1
        }
        if (title < 0 && artist < 0 && video < 0) return null
        if (title < 0) title = -1
        if (artist < 0) artist = -1
        return Columns(title, artist, video, hasHeader = headerFound)
    }
}
