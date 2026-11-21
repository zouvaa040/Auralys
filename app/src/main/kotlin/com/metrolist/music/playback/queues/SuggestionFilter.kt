/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import androidx.media3.common.MediaItem
import com.metrolist.music.extensions.metadata
import com.metrolist.music.models.MediaMetadata
import java.util.Locale

/**
 * What "Putar otomatis" / radio pages may add to the queue. YouTube radio pages often bring back songs that are already
 * in the queue or were heard minutes ago, and the same recording comes twice (the catalogue audio and its music video
 * have different ids). Idea from BitChord's QueueBuilder ("dontRepeatSuggestions"), written again for MediaItems.
 *
 * Review 2026-11-12, finding 26: with a 5 000-song queue every autoplay batch normalised every queued title (lowercase,
 * three regex passes, a split) on the main thread. The keys are now cached per title + artist (a song's key is computed
 * once per session), [RecentPlays] answers by key in O(1), and the service runs [filter] off the main thread.
 * Thread-safe.
 */
object SuggestionFilter {
    /**
     * [candidates] without repeats: never a song twice in one batch, never one that is still [queued]; with
     * [noRepeat] also never one heard in this session ([recent]) and never the other cut (audio / video) of one of
     * them. Order kept.
     */
    fun filter(
        candidates: List<MediaItem>,
        queued: List<MediaItem>,
        recent: RecentPlays?,
        noRepeat: Boolean,
    ): List<MediaItem> {
        if (candidates.isEmpty()) return candidates
        val ids = HashSet<String>(queued.size + candidates.size)
        val keys = HashSet<String>()
        for (item in queued) {
            ids += item.mediaId
            if (noRepeat) recordingKey(item.metadata)?.let { keys += it }
        }
        val out = ArrayList<MediaItem>(candidates.size)
        for (item in candidates) {
            if (item.mediaId in ids) continue
            val key = if (noRepeat) recordingKey(item.metadata) else null
            if (noRepeat) {
                if (recent?.contains(item.mediaId, key) == true) continue
                if (key != null && key in keys) continue
            }
            ids += item.mediaId
            if (key != null) keys += key
            out += item
        }
        return out
    }

    /**
     * Radio playlists (endless, made by YouTube from a song, artist or playlist) start with "RD"; "RDCLAK" ids are
     * curated playlists with a fixed track list, so they are not filtered against what was heard before.
     */
    fun isRadioPlaylistId(playlistId: String?): Boolean =
        playlistId == null || (playlistId.startsWith("RD") && !playlistId.startsWith("RDCLAK"))

    /** Normalised "title|artist" of a song, null when there is no title. Cached by the raw title and first artist. */
    fun recordingKey(metadata: MediaMetadata?): String? {
        metadata ?: return null
        val rawArtist = metadata.artists.firstOrNull()?.name
        val cacheKey = metadata.title + '\u0000' + rawArtist.orEmpty()
        keyCache.get(cacheKey)?.let { return it.takeIf { key -> key != NO_KEY } }
        val key = computeKey(metadata.title, rawArtist)
        computed.incrementAndGet()
        keyCache.put(cacheKey, key ?: NO_KEY)
        return key
    }

    /** How many keys were really computed (not taken from the cache) since the start, for tests. */
    internal fun computedKeys(): Long = computed.get()

    private val computed = java.util.concurrent.atomic.AtomicLong()

    private fun computeKey(
        rawTitle: String,
        rawArtist: String?,
    ): String? {
        val title = normalisedTitle(rawTitle)
        if (title.isEmpty()) return null
        val artist = rawArtist?.let(::normalisedArtist).orEmpty()
        return "$title|$artist"
    }

    /** How many keys are cached (for tests). */
    internal fun cachedKeys(): Int = keyCache.size()

    private const val NO_KEY = "\u0000"

    /** Raw "title\u0000artist" -> key; bounded so a long session cannot grow it without limit. */
    private val keyCache = BoundedCache(KEY_CACHE_SIZE)

    internal const val KEY_CACHE_SIZE = 12_000

    /** "Kesariya (Official Video)", "Kesariya [4K]" and "Kesariya" are one song. Remix / live / cover stay apart. */
    fun normalisedTitle(raw: String): String =
        raw
            .lowercase(Locale.ROOT)
            .substringBefore(" | ")
            .replace(NOISE, " ")
            .replace(PUNCTUATION, " ")
            .replace(SPACES, " ")
            .trim()

    fun normalisedArtist(raw: String): String =
        raw
            .lowercase(Locale.ROOT)
            .replace(TOPIC, " ")
            .split(",", "&", " feat", " ft.", " x ")
            .first()
            .replace(PUNCTUATION, " ")
            .replace(SPACES, " ")
            .trim()

    private val NOISE =
        Regex(
            """\((?:official|lyric|lyrics|lyrical|audio|video|visuali[sz]er|music video|mv|hd|4k)[^)]*\)""" +
                """|\[(?:official|lyric|lyrics|audio|video|mv|hd|4k)[^\]]*\]""" +
                """|\b(?:official (?:music video|video|audio)|lyric video|lyrics video|music video)\b""",
            RegexOption.IGNORE_CASE,
        )
    private val TOPIC = Regex("""\s*-\s*topic\b""", RegexOption.IGNORE_CASE)
    private val PUNCTUATION = Regex("""[^\p{L}\p{N}]+""")
    private val SPACES = Regex("""\s+""")
}

/**
 * True when the next pages of this queue are suggestions (a radio, or the radio after an album), false when they are
 * more songs of a fixed list (a YouTube playlist): only suggestions are filtered against what was heard.
 */
val Queue.pagesAreSuggestions: Boolean
    get() =
        when (this) {
            is YouTubeQueue -> isRadio
            is LocalAlbumRadio, is YouTubeAlbumRadio -> true
            // "Utamakan versi audio" wraps only radio starts (search rows, home feeds)
            is com.metrolist.music.version.AudioFirstQueue -> true
            else -> false
        }

/**
 * The songs heard (and suggested) in this session, newest last, at most [capacity], by id and by recording key, so a
 * radio does not bring them back. In memory only.
 */
class RecentPlays(
    private val capacity: Int = 600,
) {
    private val ids = LinkedHashSet<String>()
    private val keys = LinkedHashMap<String, String>()

    /** key -> how many remembered ids have it: "is this recording heard?" without scanning [keys] (was O(n) per song). */
    private val keyCounts = HashMap<String, Int>()

    @Synchronized
    fun add(metadata: MediaMetadata?) {
        metadata ?: return
        ids.remove(metadata.id)
        ids += metadata.id
        dropKeyOf(metadata.id)
        SuggestionFilter.recordingKey(metadata)?.let {
            keys[metadata.id] = it
            keyCounts[it] = (keyCounts[it] ?: 0) + 1
        }
        while (ids.size > capacity) {
            val oldest = ids.first()
            ids.remove(oldest)
            dropKeyOf(oldest)
        }
    }

    private fun dropKeyOf(id: String) {
        val key = keys.remove(id) ?: return
        val n = (keyCounts[key] ?: 1) - 1
        if (n <= 0) keyCounts.remove(key) else keyCounts[key] = n
    }

    @Synchronized
    fun contains(
        id: String,
        key: String?,
    ): Boolean = id in ids || (key != null && key in keyCounts)

    @Synchronized
    fun size(): Int = ids.size

    @Synchronized
    fun clear() {
        ids.clear()
        keys.clear()
        keyCounts.clear()
    }
}

/** A small thread-safe LRU map of strings (access order). */
internal class BoundedCache(
    private val capacity: Int,
) {
    private val map =
        object : LinkedHashMap<String, String>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > capacity
        }

    @Synchronized
    fun get(key: String): String? = map[key]

    @Synchronized
    fun put(
        key: String,
        value: String,
    ) {
        map[key] = value
    }

    @Synchronized
    fun size(): Int = map.size
}
