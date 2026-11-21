/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.poster

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.LocalNavController
import com.metrolist.music.R
import com.metrolist.music.db.entities.Song
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.ui.component.Material3MenuItemData
import com.metrolist.music.ui.component.painterResource

// ====================================================================================================================
// The ways into the poster: a "Buat poster" row in the menus of the playlist, album, liked and auto playlist pages. Each hook is two
// small additive lines in the menu file (a remembered opener + one menu row); the songs travel in memory ([PosterLaunch]), not in the
// route, because a playlist can hold hundreds of them. After the process died the page comes back from its saved state instead (song
// ids reloaded from the database, the picked songs and the poster's songs saved in full, the photo in the cache: PosterSaved.kt).

const val POSTER_ROUTE = "poster"

/** The songs a poster starts from (the picker shows them; at most 5 go on the poster). */
class PosterRequest(val title: String, val songs: List<PosterSong>)

/** Hands the songs from a page to the poster screen: the fast way in. After the process died the editor's saved state is used instead. */
object PosterLaunch {
    @Volatile var pending: PosterRequest? = null

    fun take(): PosterRequest? = pending.also { pending = null }
}

fun Song.toPosterSong(): PosterSong =
    PosterSong(song.id, song.title, artists.joinToString(", ") { it.name }, song.thumbnailUrl, song.duration.coerceAtLeast(0))

fun SongItem.toPosterSong(): PosterSong = PosterSong(id, title, artists.joinToString(", ") { it.name }, thumbnail, duration ?: 0)

fun MediaMetadata.toPosterSong(): PosterSong = PosterSong(id, title, artists.joinToString(", ") { it.name }, thumbnailUrl, duration.coerceAtLeast(0))

/** Opens the poster with [songs] (the picker comes first). */
fun NavController.openPoster(title: String, songs: List<PosterSong>) {
    PosterLaunch.pending = PosterRequest(title, songs.distinctBy { it.id })
    navigate(POSTER_ROUTE) { launchSingleTop = true }
}

/** For the menus: an opener bound to the app's NavController. Call it in the menu's composable body. */
@Composable
fun rememberPosterOpener(): (String, List<PosterSong>) -> Unit {
    val nav = LocalNavController.current
    return { title, songs -> nav.openPoster(title, songs) }
}

/** The "Buat poster" row of a menu. [onClick] opens the poster (and closes the menu). */
fun posterMenuItem(onClick: () -> Unit): Material3MenuItemData =
    Material3MenuItemData(
        title = { Text(stringResource(R.string.poster_make)) },
        description = { Text(stringResource(R.string.poster_make_desc)) },
        icon = { Icon(painter = painterResource(R.drawable.insert_photo), contentDescription = null) },
        onClick = onClick,
    )

/** The poster's route in the app's NavHost (one additive line in NavigationBuilder). */
fun NavGraphBuilder.posterRoute(navController: NavController) {
    composable(POSTER_ROUTE) { PosterScreen(navController) }
}
