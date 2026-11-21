/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.Collator
import java.util.Locale
import javax.inject.Inject

/** How the "Di perangkat" list is ordered. */
enum class LocalSongSort { NAME, ARTIST, DATE_ADDED }

val LocalSongSortKey = stringPreferencesKey("local_song_sort")
val LocalSongSortDescendingKey = booleanPreferencesKey("local_song_sort_descending")

/** Orders [songs] by [sort]; ties and equal titles keep a stable, readable order. */
fun sortLocalSongs(
    songs: List<Song>,
    sort: LocalSongSort,
    descending: Boolean,
    locale: Locale = Locale.getDefault(),
): List<Song> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
    val byTitle = compareBy<Song, String>(collator) { it.song.title }
    val sorted =
        when (sort) {
            LocalSongSort.NAME -> songs.sortedWith(byTitle)
            LocalSongSort.ARTIST ->
                songs.sortedWith(
                    compareBy<Song, String>(collator) { s -> s.orderedArtists.joinToString(", ") { it.name } }.then(byTitle),
                )
            LocalSongSort.DATE_ADDED ->
                songs.sortedWith(compareBy<Song> { it.song.inLibrary?.toEpochSecond(java.time.ZoneOffset.UTC) ?: 0L }.then(byTitle))
        }
    return if (descending) sorted.asReversed() else sorted
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class LocalSongsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        database: MusicDatabase,
        private val scanner: LocalMusicScanner,
    ) : ViewModel() {
        val scanState = scanner.state

        val count: StateFlow<Int> =
            database.localSongCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

        val songs: StateFlow<List<Song>> =
            combine(
                // a first scan commits every 200 files; the list is rebuilt once things settle, not 25 times
                database.localSongs().debounce(300),
                context.dataStore.data.map { it[LocalSongSortKey].toEnum(LocalSongSort.NAME) }.distinctUntilChanged(),
                context.dataStore.data.map { it[LocalSongSortDescendingKey] ?: false }.distinctUntilChanged(),
            ) { list, sort, descending -> sortLocalSongs(list, sort, descending) }
                .flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        fun hasPermission(): Boolean = scanner.hasPermission()

        fun scan() = scanner.startScan()
    }
