/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.metrolist.music.db.DatabaseDao
import com.metrolist.music.db.entities.LyricsEntity
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The lyrics 4.3.3 and before stored are checked once more with the matching of 4.3.4, lazily: no lyrics are deleted.
 *
 * Lyrics are stored per song and never looked up again, so a song that got the wrong lyrics (another cut, another
 * version, another song) from the old matching would keep them. 4.3.4 first removed those rows in one go, which lost
 * hand edits (an edit kept the source's name) and, for an offline listener, every lyric (review 2026-11-12, finding 1).
 * Now, once after the update, the ids of the rows the automatic lookup wrote are listed in a small file
 * ([LyricsRecheckList]); when such a song's lyrics are shown, the new lookup runs, and the row is replaced only when it
 * found usable lyrics (the old text is kept in [LyricsBackup] and offered as "Previous lyrics" in the picker). Offline,
 * with every source failing or with nothing found, the old row stays. Songs the user picked a source for or made the
 * lyrics of by hand are never listed.
 */
object LyricsRecheck434 {
    /** Set once the list is written. */
    val ListedKey = booleanPreferencesKey("lyricsRecheck434Listed")

    /** One song id per line, in the app's files. */
    const val FILE = "lyrics_recheck_434.txt"

    /** The names LyricsHelper stored (provider.name), lower case, plus the registry spellings. */
    val AUTO_PROVIDERS =
        listOf(
            "betterlyrics", "binilyrics", "lrc.red", "lrcred", "lrclib", "simpmusic", "unison", "kugou",
            "paxsenix", "lyricsplus", "zemer", "youtube subtitle", "youtubesubtitle", "youtube music", "youtube",
        )

    /**
     * Lists the rows to check again unless that was done before; returns how many, or null when it had run. The flag
     * is set only after the list is on disk, so a crash in between lists them again next time (harmless).
     */
    suspend fun listOnce(
        dataStore: DataStore<Preferences>,
        dao: DatabaseDao,
        list: LyricsRecheckList,
        keep: Set<String>,
    ): Int? {
        if (dataStore.data.first()[ListedKey] == true) return null
        val ids = withContext(Dispatchers.IO) { dao.autoFetchedLyricsIds(AUTO_PROVIDERS, LyricsEntity.LYRICS_NOT_FOUND) }.filter { it !in keep }
        list.replaceAll(ids)
        dataStore.edit { it[ListedKey] = true }
        return ids.size
    }

    /**
     * The check of one listed song whose lyrics are being shown ([row]): [lookup] runs and the row is replaced only by
     * the usable lyrics it found (the old text is backed up by [store]). Offline, with every source failing, or with
     * nothing found, the row stays. The song leaves the list once a lookup really answered, or when the user made
     * or picked its lyrics ([keptByUser], or a "Manual" row): those are never replaced. Null when nothing ran.
     */
    suspend fun recheck(
        row: LyricsEntity,
        list: LyricsRecheckList,
        store: LyricsStore,
        keptByUser: suspend () -> Boolean,
        online: () -> Boolean,
        lookup: suspend () -> LyricsLookup,
    ): LyricsStore.Result? {
        if (!LyricsStore.hasText(row) || !list.contains(row.id)) return null
        if (keptByUser() || row.provider.equals("Manual", ignoreCase = true)) {
            list.remove(row.id)
            return null
        }
        if (!online()) return null
        val outcome = lookup()
        val result = store.apply(row.id, outcome, LyricsStore.Mode.RECHECK)
        if (outcome !is LyricsLookup.NoConnection) list.remove(row.id)
        return result
    }
}

/**
 * The songs still to be checked again ([LyricsRecheck434]), read from [file] on first use and written back (whole,
 * through a temporary file) when one is taken off.
 */
class LyricsRecheckList(
    private val file: File,
) {
    private val mutex = Mutex()
    private var ids: MutableSet<String>? = null

    private fun load(): MutableSet<String> =
        ids ?: runCatching { file.readLines().filter { it.isNotBlank() }.toMutableSet() }
            .getOrDefault(HashSet())
            .also { ids = it }

    suspend fun contains(id: String): Boolean = mutex.withLock { withContext(Dispatchers.IO) { id in load() } }

    suspend fun size(): Int = mutex.withLock { withContext(Dispatchers.IO) { load().size } }

    suspend fun remove(id: String) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val set = load()
                if (set.remove(id)) write(set)
            }
        }
    }

    suspend fun replaceAll(newIds: Collection<String>) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val set = newIds.filter { it.isNotBlank() && '\n' !in it }.toMutableSet()
                ids = set
                write(set)
            }
        }
    }

    private fun write(set: Set<String>) {
        runCatching {
            if (set.isEmpty()) {
                file.delete()
                return
            }
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(set.joinToString("\n"))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }
}

/**
 * Songs whose current lyrics the user made or chose by hand outside the source picker: an edit in the lyrics menu, a
 * result taken from the lyrics search list, "Previous lyrics" taken back in the picker. Kept in the preferences (no
 * database change), newest last, at most [MAX]. A song leaves the list when its lyrics are replaced by found ones.
 */
object LyricsHandMade {
    const val MAX = 450
    private val KEY = stringPreferencesKey("auralys_lyrics_hand_made")

    suspend fun ids(context: Context): Set<String> =
        context.dataStore.data.first()[KEY].orEmpty().lineSequence().filter { it.isNotBlank() }.toSet()

    suspend fun contains(
        context: Context,
        mediaId: String,
    ): Boolean = mediaId.replace('\n', ' ') in ids(context)

    fun update(
        current: String,
        mediaId: String,
    ): String {
        val ids = LinkedHashSet(current.lineSequence().filter { it.isNotBlank() }.toList())
        val clean = mediaId.replace('\n', ' ')
        ids.remove(clean)
        ids.add(clean)
        while (ids.size > MAX) ids.remove(ids.first())
        return ids.joinToString("\n")
    }

    fun without(
        current: String,
        mediaId: String,
    ): String {
        val clean = mediaId.replace('\n', ' ')
        return current.lineSequence().filter { it.isNotBlank() && it != clean }.joinToString("\n")
    }

    suspend fun markNow(
        context: Context,
        mediaId: String,
    ) {
        runCatching { context.dataStore.edit { it[KEY] = update(it[KEY].orEmpty(), mediaId) } }
    }

    suspend fun unmark(
        context: Context,
        mediaId: String,
    ) {
        if (!contains(context, mediaId)) return
        runCatching { context.dataStore.edit { it[KEY] = without(it[KEY].orEmpty(), mediaId) } }
    }

    /** Records the song in the background (the menus that call it have no coroutine of their own at that point). */
    fun mark(
        context: Context,
        mediaId: String,
    ) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch { markNow(app, mediaId) }
    }
}
