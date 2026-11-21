/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.metrolist.music.playback.MusicService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
open class AuralysWidgetReceiver : AppWidgetProvider() {
    @Inject
    lateinit var manager: AuralysWidgetManager

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        renderInBackground(context, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        renderInBackground(context, intArrayOf(appWidgetId))
    }

    /**
     * The glass background is drawn on the CPU, which can take a second on a slow phone. Doing that on the main thread of a
     * broadcast could be cut short by the system and leave the widget on the launcher's grey loading tile, so the drawing
     * runs on its own thread while the broadcast is kept alive.
     */
    private fun renderInBackground(context: Context, ids: IntArray?) {
        val pending = goAsync()
        // one redraw at a time; events that arrive during a redraw are merged into the next one
        RENDER_QUEUE.request(
            ids = ids,
            render = { toDraw ->
                manager.renderAll(toDraw)
                requestRefreshFromService(context)
            },
            done = { pending.finish() },
        )
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { AuralysWidgetConfigStore.delete(context, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            // after a restart or an app update the launcher may hold a drawing it can no longer show: redraw everything
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> renderInBackground(context, null)

            ACTION_PLAY_PAUSE, ACTION_LIKE, ACTION_NEXT, ACTION_PREVIOUS -> {
                // A tap on a widget button is allowed to start the service from the background.
                val serviceIntent = Intent(context, MusicService::class.java).apply {
                    action = intent.action
                    putExtras(intent)
                }
                try {
                    context.startService(serviceIntent)
                } catch (e: Exception) {
                    // the system may still refuse; the button then does nothing
                }
            }
        }
    }

    /** Only when the service already runs: starting it from the background throws on Android 12+. */
    private fun requestRefreshFromService(context: Context) {
        if (!MusicService.isRunning) return
        try {
            context.startService(Intent(context, MusicService::class.java).setAction(ACTION_UPDATE_WIDGET))
        } catch (e: Exception) {
            // service restricted in the background: the widget keeps the last drawing
        }
    }

    companion object {
        private val RENDER_QUEUE =
            WidgetRenderQueue(java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "auralys-widget-render").apply { isDaemon = true } })

        /** Every receiver in the widget list, one per size; a widget belongs to whichever one it was placed from. */
        val PROVIDERS: List<Class<out AuralysWidgetReceiver>> = listOf(
            AuralysWidgetReceiver::class.java,
            AuralysWidgetSquareReceiver::class.java,
            AuralysWidgetStripReceiver::class.java,
            AuralysWidgetTallReceiver::class.java,
            AuralysWidgetTinyReceiver::class.java,
        )

        const val ACTION_PLAY_PAUSE = "com.metrolist.music.widget.PLAY_PAUSE"
        const val ACTION_LIKE = "com.metrolist.music.widget.LIKE"
        const val ACTION_NEXT = "com.metrolist.music.widget.NEXT"
        const val ACTION_PREVIOUS = "com.metrolist.music.widget.PREVIOUS"
        const val ACTION_UPDATE_WIDGET = "com.metrolist.music.widget.UPDATE_WIDGET"
    }
}
