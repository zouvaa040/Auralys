/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import com.metrolist.music.R
import com.metrolist.music.utils.ListeningStreakManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the Auralys Player widgets in step with playback. The service pushes the current song in
 * ([updateWidgets], [updateProgress]); the receiver and the settings screen redraw from the last state.
 */
@Singleton
class AuralysWidgetManager @Inject constructor(
    @ApplicationContext private val context: Context,
    /** Lazy: nothing about the streak is built until a widget actually draws. */
    private val streakManager: dagger.Lazy<ListeningStreakManager>,
) {
    private val imageLoader by lazy { ImageLoader.Builder(context).crossfade(false).build() }

    /** The streak the badge shows (days, lit); follows [ListeningStreakManager], redraws only when it changes. */
    @Volatile
    private var streak: Pair<Int, Boolean> = 0 to false
    private val streakScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var streakJob: Job? = null

    /**
     * Starts following the listening streak once there is a widget to show it on. No timer of its own: the streak
     * manager already re-reads the day once a minute while the app runs, and the widgets are redrawn only when the
     * day count or the lit state really changes (a few times a day at most).
     */
    @Synchronized
    private fun watchStreak() {
        if (streakJob != null) return
        streakJob =
            streakScope.launch {
                try {
                    streakManager.get().streak
                        .map { it.current.coerceAtLeast(0) to (it.current > 0 && it.lit) }
                        .distinctUntilChanged()
                        .collect { value ->
                            if (value == streak) return@collect
                            streak = value
                            renderAll()
                        }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    timber.log.Timber.tag("AuralysWidget").w(e, "Streak for the widget unavailable")
                }
            }
    }

    private fun withStreak(state: AuralysWidgetState): AuralysWidgetState =
        streak.let { (days, lit) -> if (days == state.streakDays && lit == state.streakLit) state else state.copy(streakDays = days, streakLit = lit) }

    @Volatile
    private var last: AuralysWidgetState? = null
    private var cachedArtworkUri: String? = null
    private var cachedCover: Bitmap? = null
    private var cachedAccent: Int = AuralysWidgetRenderer.DEFAULT_ACCENT

    suspend fun updateWidgets(
        title: String,
        artist: String,
        artworkUri: String?,
        isPlaying: Boolean,
        isLiked: Boolean,
        duration: Long = 0,
        currentPosition: Long = 0,
    ) {
        if (artworkUri == null || artworkUri != cachedArtworkUri || cachedCover == null) {
            val cover = artworkUri?.let { loadCover(it) }
            cachedArtworkUri = artworkUri
            cachedCover = cover
            cachedAccent = cover?.let(::accentOf) ?: AuralysWidgetRenderer.DEFAULT_ACCENT
        }
        last = AuralysWidgetState(
            title = title,
            artist = artist,
            cover = cachedCover,
            accent = cachedAccent,
            isPlaying = isPlaying,
            isLiked = isLiked,
            duration = duration,
            position = currentPosition,
        )
        withContext(Dispatchers.Default) { renderAll() }
    }

    fun updateProgress(duration: Long, currentPosition: Long) {
        last = last?.copy(duration = duration, position = currentPosition) ?: return
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val level = AuralysWidgetRenderer.progressLevel(duration, currentPosition)
        for (id in widgetIds(appWidgetManager)) {
            if (!AuralysWidgetConfigStore.load(context, id).showProgress) continue
            val (widthDp, heightDp) = sizeOf(appWidgetManager, id)
            val views = RemoteViews(context.packageName, AuralysWidgetRenderer.layoutRes(widthDp, heightDp))
            views.setInt(R.id.aw_progress_fill, "setImageLevel", level)
            views.setTextViewText(R.id.aw_time_now, AuralysWidgetRenderer.formatTime(currentPosition))
            appWidgetManager.partiallyUpdateAppWidget(id, views)
        }
    }

    /** Redraws [onlyIds] (default: every widget) from the last known state. */
    fun renderAll(onlyIds: IntArray? = null) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val ids = onlyIds ?: widgetIds(appWidgetManager)
        if (ids.isEmpty()) return
        watchStreak()
        val state = withStreak(last ?: idleState())
        for (id in ids) {
            val (widthDp, heightDp) = sizeOf(appWidgetManager, id)
            val config = AuralysWidgetConfigStore.load(context, id)
            try {
                appWidgetManager.updateAppWidget(id, AuralysWidgetRenderer.build(context, config, state, widthDp, heightDp))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A widget that fails to draw is left on the launcher's grey "loading" tile until something redraws it, which is
                // what users saw as a widget that "disappears". Out-of-memory is an Error, not an Exception, so it is caught here too.
                timber.log.Timber.tag("AuralysWidget").w(e, "Could not draw widget $id, drawing the plain version")
                drawPlain(appWidgetManager, id, config, state, widthDp, heightDp)
            }
        }
    }

    /** The lightest drawing there is: the dark style with no cover and no glass, so it works even when memory is short. */
    private fun drawPlain(
        appWidgetManager: AppWidgetManager,
        id: Int,
        config: AuralysWidgetConfig,
        state: AuralysWidgetState,
        widthDp: Int,
        heightDp: Int,
    ) {
        try {
            appWidgetManager.updateAppWidget(
                id,
                AuralysWidgetRenderer.build(context, config.copy(style = WidgetStyle.DARK), state.copy(cover = null), widthDp, heightDp),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            timber.log.Timber.tag("AuralysWidget").w(e, "Could not draw the plain widget $id either")
        }
    }

    /** What the settings screen shows: the current song if there is one, otherwise sample text. */
    fun buildPreview(config: AuralysWidgetConfig, widthDp: Int, heightDp: Int): RemoteViews {
        watchStreak()
        val state = withStreak(
            last ?: idleState().copy(
                title = context.getString(R.string.aw_sample_title),
                artist = context.getString(R.string.aw_sample_artist),
                duration = 200_000,
                position = 70_000,
            ),
        ).let { if (it.streakDays > 0) it else it.copy(streakDays = PREVIEW_STREAK_DAYS, streakLit = true) }
        return AuralysWidgetRenderer.build(context, config, state, widthDp, heightDp, interactive = false)
    }

    private companion object {
        /** The settings preview shows the badge even before the first streak day, so the option can be judged. */
        const val PREVIEW_STREAK_DAYS = 7
    }

    private fun idleState() = AuralysWidgetState(
        title = context.getString(R.string.no_song_playing),
        artist = context.getString(R.string.tap_to_open),
        cover = null,
        accent = AuralysWidgetRenderer.DEFAULT_ACCENT,
        isPlaying = false,
        isLiked = false,
        duration = 0,
        position = 0,
    )

    private fun widgetIds(appWidgetManager: AppWidgetManager): IntArray =
        AuralysWidgetReceiver.PROVIDERS.flatMap { appWidgetManager.getAppWidgetIds(ComponentName(context, it)).toList() }.toIntArray()

    /**
     * The size the widget really has on screen, in dp. The launcher reports a min and a max for each side: upright
     * the widget is min-width by max-height, sideways it is max-width by min-height. Taking the wrong pair draws the
     * background at another shape than the widget, which then stretches it (squashed corners).
     */
    /** The size widget [id] has right now, in dp (what the settings screen previews by default). */
    fun currentSize(id: Int): Pair<Int, Int> = sizeOf(AppWidgetManager.getInstance(context), id)

    private fun sizeOf(appWidgetManager: AppWidgetManager, id: Int): Pair<Int, Int> {
        val options = appWidgetManager.getAppWidgetOptions(id)
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val widthKey = if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH
        val heightKey = if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
        val width = options.getInt(widthKey).takeIf { it > 0 } ?: options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 250
        val height = options.getInt(heightKey).takeIf { it > 0 } ?: options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).takeIf { it > 0 } ?: 110
        return width to height
    }

    private suspend fun loadCover(uri: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            imageLoader.execute(
                ImageRequest.Builder(context).data(uri).size(300, 300).allowHardware(false).build(),
            ).image?.toBitmap()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** A colour from the cover, pulled down to something dark enough for white text on top. */
    private fun accentOf(cover: Bitmap): Int = try {
        val small = Bitmap.createScaledBitmap(cover, 48, 48, true)
        val palette = Palette.from(small).maximumColorCount(12).generate()
        val base = palette.getDarkMutedColor(palette.getDominantColor(AuralysWidgetRenderer.DEFAULT_ACCENT))
        if (ColorUtils.calculateLuminance(base) > 0.18) {
            android.graphics.Color.rgb(
                (android.graphics.Color.red(base) * 0.55f).toInt(),
                (android.graphics.Color.green(base) * 0.55f).toInt(),
                (android.graphics.Color.blue(base) * 0.55f).toInt(),
            )
        } else {
            base
        }
    } catch (e: Exception) {
        AuralysWidgetRenderer.DEFAULT_ACCENT
    }
}
