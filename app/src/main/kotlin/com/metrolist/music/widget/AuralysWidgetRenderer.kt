/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import com.metrolist.music.ui.component.SystemIcons
import kotlin.math.max
import kotlin.math.min

/** What the widget shows right now. */
data class AuralysWidgetState(
    val title: String,
    val artist: String,
    val cover: Bitmap?,
    /** A dark colour taken from the cover, used by the "cover colour" style. */
    val accent: Int,
    val isPlaying: Boolean,
    val isLiked: Boolean,
    val duration: Long,
    val position: Long,
    /** Listening streak in days (0 = none) and whether today already counts (the flame is lit). */
    val streakDays: Int = 0,
    val streakLit: Boolean = false,
)

/** Draws the one Auralys widget: picks the layout for the size, paints the glass background and wires the buttons. */
object AuralysWidgetRenderer {
    private const val MAX_BACKGROUND_PX = 900
    private const val COVER_PX = 256

    const val DEFAULT_ACCENT = 0xFF2A2F3A.toInt()
    private val INK = 0xFF1B1B1F.toInt()
    private val LIKED = 0xFFFF5C7A.toInt()

    enum class WidgetLayout(val res: Int) {
        WIDE(R.layout.aw_wide),
        STRIP(R.layout.aw_strip),
        SQUARE(R.layout.aw_square),
    }

    fun layoutFor(widthDp: Int, heightDp: Int): WidgetLayout = when {
        widthDp in 1..179 -> WidgetLayout.SQUARE
        heightDp in 1..99 -> WidgetLayout.STRIP
        else -> WidgetLayout.WIDE
    }

    fun progressLevel(duration: Long, position: Long): Int =
        if (duration > 0) ((position.toDouble() / duration) * 10_000).toInt().coerceIn(0, 10_000) else 0

    fun formatTime(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    /**
     * The layout resource a widget of this size uses; partial updates (progress ticks) must target the same one.
     * Android 12+ can size and place every view from code, so one layout ([R.layout.aw_flex]) fits any widget shape.
     */
    fun layoutRes(widthDp: Int, heightDp: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) R.layout.aw_flex else layoutFor(widthDp, heightDp).res

    fun build(
        context: Context,
        config: AuralysWidgetConfig,
        state: AuralysWidgetState,
        widthDp: Int,
        heightDp: Int,
        interactive: Boolean = true,
    ): RemoteViews =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            buildFlexible(context, config, state, widthDp, heightDp, interactive)
        } else {
            buildClassic(context, config, state, widthDp, heightDp, interactive)
        }

    private fun textScale(size: WidgetTextSize): Float = when (size) {
        WidgetTextSize.SMALL -> 0.88f
        WidgetTextSize.MEDIUM -> 1f
        WidgetTextSize.LARGE -> 1.12f
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun RemoteViews.place(id: Int, rect: WRect?) {
        if (rect == null) {
            setViewVisibility(id, View.GONE)
            return
        }
        setViewVisibility(id, View.VISIBLE)
        setViewLayoutWidth(id, rect.w, TypedValue.COMPLEX_UNIT_DIP)
        setViewLayoutHeight(id, rect.h, TypedValue.COMPLEX_UNIT_DIP)
        setViewLayoutMargin(id, RemoteViews.MARGIN_START, rect.x, TypedValue.COMPLEX_UNIT_DIP)
        setViewLayoutMargin(id, RemoteViews.MARGIN_TOP, rect.y, TypedValue.COMPLEX_UNIT_DIP)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun RemoteViews.square(id: Int, edge: Float) {
        setViewLayoutWidth(id, edge, TypedValue.COMPLEX_UNIT_DIP)
        setViewLayoutHeight(id, edge, TypedValue.COMPLEX_UNIT_DIP)
    }

    /** The widget for Android 12+: every element is placed by [AuralysWidgetLayout] for the exact size. */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun buildFlexible(
        context: Context,
        config: AuralysWidgetConfig,
        state: AuralysWidgetState,
        widthDp: Int,
        heightDp: Int,
        interactive: Boolean,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.aw_flex)
        val family = SystemIcons.family(context)
        val density = context.resources.displayMetrics.density
        val input = WidgetLayoutInput(
            showCover = config.showCover,
            showProgress = config.showProgress && state.duration > 0,
            showTime = config.showTime,
            showLike = config.showLike,
            showSkip = config.showSkip,
            textScale = textScale(config.textSize),
            streakDays = streakDaysShown(config, state),
        )
        val spec = AuralysWidgetLayout.compute(widthDp.toFloat(), heightDp.toFloat(), input)

        val light = config.style == WidgetStyle.LIGHT
        val primary = if (light) INK else Color.WHITE
        val secondary = ColorUtils.setAlphaComponent(primary, 0xB3)
        // over a cover (tiny widget) the round button must always show, whatever the setting says
        val filled = config.playButton == WidgetPlayButton.FILLED || (spec.form == WidgetForm.MICRO && spec.cover != null)
        val playBg = if (light) INK else 0xF2FFFFFF.toInt()
        val playIcon = when {
            !filled -> primary
            light -> Color.WHITE
            config.style == WidgetStyle.COVER -> coverColor(config, state)
            else -> INK
        }

        views.setImageViewBitmap(R.id.aw_bg, background(config, state, widthDp, heightDp, density))

        // cover
        views.place(R.id.aw_cover, spec.cover)
        if (spec.cover != null) {
            // the cover's corners follow the glass's: its radius is the glass radius minus the gap to the edge, so the two
            // curves stay parallel (a capsule strip gets a round cover)
            val cover = spec.cover
            val gap = min(min(cover.x, cover.y), min(widthDp - cover.right, heightDp - cover.bottom)).coerceAtLeast(0f)
            val inner = (cornerRadiusDp(config.corner, widthDp.toFloat(), heightDp.toFloat()) - gap).coerceAtLeast(4f)
            views.setImageViewBitmap(R.id.aw_cover, coverBitmap(context, state.cover, config.coverShape, primary, inner / cover.w))
        }

        // texts
        views.place(R.id.aw_title, spec.title)
        views.place(R.id.aw_artist, spec.artist)
        views.setTextViewText(R.id.aw_title, state.title)
        views.setTextViewText(R.id.aw_artist, state.artist)
        views.setTextColor(R.id.aw_title, primary)
        views.setTextColor(R.id.aw_artist, secondary)
        views.setTextViewTextSize(R.id.aw_title, TypedValue.COMPLEX_UNIT_DIP, spec.titleSize)
        views.setTextViewTextSize(R.id.aw_artist, TypedValue.COMPLEX_UNIT_DIP, spec.artistSize)

        // buttons
        views.place(R.id.aw_like_box, spec.like)
        views.place(R.id.aw_prev_box, spec.prev)
        views.place(R.id.aw_play_box, spec.play)
        views.place(R.id.aw_next_box, spec.next)
        views.square(R.id.aw_like, spec.likeIcon)
        views.square(R.id.aw_prev, spec.skipIcon)
        views.square(R.id.aw_next, spec.skipIcon)
        views.square(R.id.aw_play_bg, spec.playDisc)
        views.square(R.id.aw_play, spec.playIcon)
        views.setInt(R.id.aw_prev, "setColorFilter", primary)
        views.setInt(R.id.aw_next, "setColorFilter", primary)
        views.setViewVisibility(R.id.aw_play_bg, if (filled) View.VISIBLE else View.GONE)
        views.setInt(R.id.aw_play_bg, "setColorFilter", playBg)
        views.setInt(R.id.aw_play, "setColorFilter", playIcon)
        views.setImageViewResource(R.id.aw_play, SystemIcons.resolve(family, if (state.isPlaying) R.drawable.pause else R.drawable.play))
        views.setImageViewResource(R.id.aw_prev, SystemIcons.resolve(family, R.drawable.skip_previous))
        views.setImageViewResource(R.id.aw_next, SystemIcons.resolve(family, R.drawable.skip_next))
        views.setImageViewResource(R.id.aw_like, SystemIcons.resolve(family, if (state.isLiked) R.drawable.favorite else R.drawable.favorite_border))
        views.setInt(R.id.aw_like, "setColorFilter", if (state.isLiked) LIKED else primary)

        // progress
        views.place(R.id.aw_progress_box, spec.progress)
        views.place(R.id.aw_time_now, spec.timeNow)
        views.place(R.id.aw_time_total, spec.timeTotal)
        views.setInt(R.id.aw_progress_track, "setColorFilter", primary)
        views.setInt(R.id.aw_progress_track, "setImageAlpha", 70)
        views.setInt(R.id.aw_progress_fill, "setColorFilter", primary)
        views.setInt(R.id.aw_progress_fill, "setImageLevel", progressLevel(state.duration, state.position))
        views.setTextColor(R.id.aw_time_now, secondary)
        views.setTextColor(R.id.aw_time_total, secondary)
        views.setTextViewTextSize(R.id.aw_time_now, TypedValue.COMPLEX_UNIT_DIP, spec.timeSize)
        views.setTextViewTextSize(R.id.aw_time_total, TypedValue.COMPLEX_UNIT_DIP, spec.timeSize)
        views.setTextViewText(R.id.aw_time_now, formatTime(state.position))
        views.setTextViewText(R.id.aw_time_total, formatTime(state.duration))

        // streak badge: only where the layout found room for it
        views.place(R.id.aw_streak, spec.streak)
        if (spec.streak != null) {
            views.square(R.id.aw_streak_icon, spec.streakIcon)
            views.setTextViewTextSize(R.id.aw_streak_text, TypedValue.COMPLEX_UNIT_DIP, spec.streakTextSize)
            bindStreak(context, views, family, state, secondary, primary)
        }

        if (interactive) {
            val open = openAppIntent(context)
            views.setOnClickPendingIntent(R.id.aw_root, open)
            views.setOnClickPendingIntent(R.id.aw_cover, open)
            views.setOnClickPendingIntent(R.id.aw_title, open)
            views.setOnClickPendingIntent(R.id.aw_artist, open)
            views.setOnClickPendingIntent(R.id.aw_play_box, actionIntent(context, AuralysWidgetReceiver.ACTION_PLAY_PAUSE))
            views.setOnClickPendingIntent(R.id.aw_next_box, actionIntent(context, AuralysWidgetReceiver.ACTION_NEXT))
            views.setOnClickPendingIntent(R.id.aw_prev_box, actionIntent(context, AuralysWidgetReceiver.ACTION_PREVIOUS))
            views.setOnClickPendingIntent(R.id.aw_like_box, actionIntent(context, AuralysWidgetReceiver.ACTION_LIKE))
        }
        return views
    }

    /** Days the streak badge shows: 0 hides it (no streak, or switched off for this widget). */
    internal fun streakDaysShown(config: AuralysWidgetConfig, state: AuralysWidgetState): Int =
        if (config.showStreak) state.streakDays.coerceAtLeast(0) else 0

    /** Flame colour: the streak's own colour while today already counts, the quiet text colour while it waits. */
    internal fun streakFlameColor(state: AuralysWidgetState, quiet: Int): Int =
        if (state.streakLit) com.metrolist.music.utils.StreakTiers.forDays(state.streakDays).glow else quiet

    private fun bindStreak(
        context: Context,
        views: RemoteViews,
        family: com.metrolist.music.ui.component.IconFamily,
        state: AuralysWidgetState,
        quiet: Int,
        ink: Int,
    ) {
        views.setImageViewResource(R.id.aw_streak_icon, SystemIcons.resolve(family, R.drawable.ic_streak_flame))
        views.setInt(R.id.aw_streak_icon, "setColorFilter", streakFlameColor(state, quiet))
        views.setTextViewText(R.id.aw_streak_text, state.streakDays.toString())
        views.setTextColor(R.id.aw_streak_text, if (state.streakLit) ink else quiet)
        views.setContentDescription(
            R.id.aw_streak,
            context.resources.getQuantityString(R.plurals.auralys434_widget_streak_desc, state.streakDays, state.streakDays),
        )
    }

    /** Android 8-11: the three fixed layouts, picked by size. */
    private fun buildClassic(
        context: Context,
        config: AuralysWidgetConfig,
        state: AuralysWidgetState,
        widthDp: Int,
        heightDp: Int,
        interactive: Boolean,
    ): RemoteViews {
        val layout = layoutFor(widthDp, heightDp)
        val views = RemoteViews(context.packageName, layout.res)
        val family = SystemIcons.family(context)
        val density = context.resources.displayMetrics.density

        val light = config.style == WidgetStyle.LIGHT
        val primary = if (light) INK else Color.WHITE
        val secondary = ColorUtils.setAlphaComponent(primary, 0xB3)
        val filled = config.playButton == WidgetPlayButton.FILLED
        val playBg = if (light) INK else 0xF2FFFFFF.toInt()
        val playIcon = when {
            !filled -> primary
            light -> Color.WHITE
            config.style == WidgetStyle.COVER -> coverColor(config, state)
            else -> INK
        }

        // ------------------------------------------------------------ background and cover
        views.setImageViewBitmap(R.id.aw_bg, background(config, state, max(widthDp, 110), max(heightDp, 50), density))

        views.setViewVisibility(R.id.aw_cover, if (config.showCover) View.VISIBLE else View.GONE)
        if (config.showCover) {
            views.setImageViewBitmap(R.id.aw_cover, coverBitmap(context, state.cover, config.coverShape, primary))
            if (layout == WidgetLayout.WIDE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // a taller widget gets a bigger cover instead of empty space around a small one
                val size = min(96, min(heightDp - 24, (widthDp * 0.30f).toInt())).coerceAtLeast(56).toFloat()
                views.setViewLayoutWidth(R.id.aw_cover, size, TypedValue.COMPLEX_UNIT_DIP)
                views.setViewLayoutHeight(R.id.aw_cover, size, TypedValue.COMPLEX_UNIT_DIP)
            }
        }

        // ------------------------------------------------------------------------- texts
        val (titleSp, artistSp) = textSizes(layout, config.textSize)
        views.setTextViewText(R.id.aw_title, state.title)
        views.setTextViewText(R.id.aw_artist, state.artist)
        views.setTextColor(R.id.aw_title, primary)
        views.setTextColor(R.id.aw_artist, secondary)
        views.setTextViewTextSize(R.id.aw_title, TypedValue.COMPLEX_UNIT_SP, titleSp)
        views.setTextViewTextSize(R.id.aw_artist, TypedValue.COMPLEX_UNIT_SP, artistSp)

        // streak badge at the end of the title line (the title shortens itself for it)
        val streakDays = streakDaysShown(config, state)
        views.setViewVisibility(R.id.aw_streak, if (streakDays > 0) View.VISIBLE else View.GONE)
        if (streakDays > 0) bindStreak(context, views, family, state, secondary, primary)

        // --------------------------------------------------------------------- controls
        views.setInt(R.id.aw_prev, "setColorFilter", primary)
        views.setInt(R.id.aw_next, "setColorFilter", primary)
        val skipVisibility = if (config.showSkip) View.VISIBLE else View.GONE
        views.setViewVisibility(R.id.aw_prev_box, skipVisibility)
        views.setViewVisibility(R.id.aw_next_box, skipVisibility)

        views.setViewVisibility(R.id.aw_play_bg, if (filled) View.VISIBLE else View.GONE)
        views.setInt(R.id.aw_play_bg, "setColorFilter", playBg)
        views.setInt(R.id.aw_play, "setColorFilter", playIcon)
        views.setImageViewResource(R.id.aw_play, SystemIcons.resolve(family, if (state.isPlaying) R.drawable.pause else R.drawable.play))
        views.setImageViewResource(R.id.aw_prev, SystemIcons.resolve(family, R.drawable.skip_previous))
        views.setImageViewResource(R.id.aw_next, SystemIcons.resolve(family, R.drawable.skip_next))

        views.setImageViewResource(R.id.aw_like, SystemIcons.resolve(family, if (state.isLiked) R.drawable.favorite else R.drawable.favorite_border))
        views.setInt(R.id.aw_like, "setColorFilter", if (state.isLiked) LIKED else primary)
        views.setViewVisibility(R.id.aw_like_box, if (config.showLike) View.VISIBLE else View.GONE)

        // -------------------------------------------------------------------- progress
        val showProgress = config.showProgress && state.duration > 0
        val progressVisibility = if (showProgress) View.VISIBLE else View.GONE
        views.setViewVisibility(R.id.aw_progress_row, progressVisibility)
        views.setViewVisibility(R.id.aw_progress_box, progressVisibility)
        // the track is the same shape as the fill, only see-through: image alpha, not a translucent colour filter
        views.setInt(R.id.aw_progress_track, "setColorFilter", primary)
        views.setInt(R.id.aw_progress_track, "setImageAlpha", 70)
        views.setInt(R.id.aw_progress_fill, "setColorFilter", primary)
        views.setInt(R.id.aw_progress_fill, "setImageLevel", progressLevel(state.duration, state.position))
        val timeVisibility = if (showProgress && config.showTime) View.VISIBLE else View.GONE
        views.setViewVisibility(R.id.aw_time_now, timeVisibility)
        views.setViewVisibility(R.id.aw_time_total, timeVisibility)
        views.setTextColor(R.id.aw_time_now, secondary)
        views.setTextColor(R.id.aw_time_total, secondary)
        views.setTextViewText(R.id.aw_time_now, formatTime(state.position))
        views.setTextViewText(R.id.aw_time_total, formatTime(state.duration))

        if (interactive) {
            val open = openAppIntent(context)
            views.setOnClickPendingIntent(R.id.aw_root, open)
            views.setOnClickPendingIntent(R.id.aw_cover, open)
            views.setOnClickPendingIntent(R.id.aw_title, open)
            views.setOnClickPendingIntent(R.id.aw_artist, open)
            views.setOnClickPendingIntent(R.id.aw_play_box, actionIntent(context, AuralysWidgetReceiver.ACTION_PLAY_PAUSE))
            views.setOnClickPendingIntent(R.id.aw_next_box, actionIntent(context, AuralysWidgetReceiver.ACTION_NEXT))
            views.setOnClickPendingIntent(R.id.aw_prev_box, actionIntent(context, AuralysWidgetReceiver.ACTION_PREVIOUS))
            views.setOnClickPendingIntent(R.id.aw_like_box, actionIntent(context, AuralysWidgetReceiver.ACTION_LIKE))
        }
        return views
    }

    /** (title, artist) text sizes in sp for the layout and the chosen size. */
    private fun textSizes(layout: WidgetLayout, size: WidgetTextSize): Pair<Float, Float> {
        val step = when (size) {
            WidgetTextSize.SMALL -> -2f
            WidgetTextSize.MEDIUM -> 0f
            WidgetTextSize.LARGE -> 2f
        }
        val title = when (layout) {
            WidgetLayout.WIDE -> 15f
            WidgetLayout.STRIP -> 14f
            WidgetLayout.SQUARE -> 13f
        } + step
        return title to (title - 3f)
    }

    private fun actionIntent(context: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, AuralysWidgetReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // ------------------------------------------------------------------ cover

    internal fun coverBitmap(context: Context, cover: Bitmap?, shape: WidgetCoverShape, ink: Int, roundedFraction: Float = 0.2f): Bitmap {
        val size = COVER_PX
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        val radius = when (shape) {
            WidgetCoverShape.CIRCLE -> size / 2f
            WidgetCoverShape.ROUNDED -> size * roundedFraction.coerceIn(0.06f, 0.5f)
            WidgetCoverShape.SQUARE -> size * 0.04f
        }
        if (cover != null) {
            val side = min(cover.width, cover.height)
            val square = Bitmap.createBitmap(cover, (cover.width - side) / 2, (cover.height - side) / 2, side, side)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                shader = BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(Matrix().apply { setScale(size / side.toFloat(), size / side.toFloat()) })
                }
            }
            canvas.drawRoundRect(rect, radius, radius, paint)
        } else {
            canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(ink, 0x26) })
            ContextCompat.getDrawable(context, SystemIcons.resolve(context, R.drawable.music_note))?.mutate()?.let { note ->
                note.setTint(ColorUtils.setAlphaComponent(ink, 0xB3))
                val pad = (size * 0.28f).toInt()
                note.setBounds(pad, pad, size - pad, size - pad)
                note.draw(canvas)
            }
        }
        return out
    }

    // ------------------------------------------------------------- background

    /** The colour the "cover colour" style (and its play icon) is built on. */
    private fun coverColor(config: AuralysWidgetConfig, state: AuralysWidgetState): Int =
        if (config.tint != 0) darken(config.tint, 0.7f) else state.accent

    /** Corner radius in dp for the 0..100 corner setting: nearly square to a full capsule, never past half the short side. */
    internal fun cornerRadiusDp(corner: Int, widthDp: Float, heightDp: Float): Float =
        (4f + 36f * corner.coerceIn(0, 100) / 100f).coerceAtMost(min(widthDp, heightDp) / 2f)

    internal fun background(config: AuralysWidgetConfig, state: AuralysWidgetState, widthDp: Int, heightDp: Int, density: Float): Bitmap {
        var w = (max(widthDp, 24) * density).toInt()
        var h = (max(heightDp, 24) * density).toInt()
        val scale = min(1f, MAX_BACKGROUND_PX / max(w, h).toFloat())
        w = max(1, (w * scale).toInt())
        h = max(1, (h * scale).toInt())
        val px = density * scale
        val shine = config.shine / 100f

        if (config.style == WidgetStyle.GLASS || config.style == WidgetStyle.CLEAR) {
            return liquidGlass(config, state, w, h, px, cornerRadiusDp(config.corner, widthDp.toFloat(), heightDp.toFloat()))
        }

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val radius = min(cornerRadiusDp(config.corner, widthDp.toFloat(), heightDp.toFloat()) * px, min(w, h) / 2f)
        val shape = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        val dark = config.style != WidgetStyle.LIGHT
        val depth = config.edge / 100f

        canvas.save()
        canvas.clipPath(shape)

        // 1. the body of the glass; its opacity decides how much of the wallpaper shows through
        canvas.saveLayerAlpha(rect, (config.opacity / 100f * 255).toInt())
        when (config.style) {
            WidgetStyle.CLEAR, WidgetStyle.GLASS -> Unit // drawn by liquidGlass() above
            WidgetStyle.DARK -> canvas.drawRect(rect, Paint().apply { color = 0xFF14161A.toInt() })
            WidgetStyle.LIGHT -> canvas.drawRect(rect, Paint().apply { color = 0xFFF4F1F8.toInt() })
            WidgetStyle.COVER -> {
                val base = coverColor(config, state)
                canvas.drawRect(rect, gradient(rect, lighten(base, 1.1f), darken(base, 0.66f), vertical = false))
            }
        }
        canvas.restore()

        // 2. gloss: a soft sheen across the top, a glint near the top-left and a faint shade at the bottom
        val sheenTop = if (dark) 0.16f else 0.55f
        canvas.drawRect(
            RectF(0f, 0f, w.toFloat(), h * 0.5f),
            gradient(RectF(0f, 0f, w.toFloat(), h * 0.5f), Color.argb((sheenTop * shine * 255).toInt(), 255, 255, 255), 0x00FFFFFF, vertical = true),
        )
        val glintRadius = max(w, h) * 0.55f
        canvas.drawRect(
            rect,
            Paint().apply {
                shader = RadialGradient(
                    w * 0.16f, h * 0.04f, glintRadius,
                    intArrayOf(Color.argb(((if (dark) 0.22f else 0.5f) * shine * 255).toInt(), 255, 255, 255), 0x00FFFFFF),
                    null,
                    Shader.TileMode.CLAMP,
                )
            },
        )
        canvas.drawRect(
            RectF(0f, h * 0.62f, w.toFloat(), h.toFloat()),
            gradient(RectF(0f, h * 0.62f, w.toFloat(), h.toFloat()), 0x00000000, Color.argb(((if (dark) 0.16f else 0.06f) * 255).toInt(), 0, 0, 0), vertical = true),
        )
        canvas.restore()

        // 3. the edge: bright at the top-left, fading, small glint at the bottom-right, plus a faint inner line
        val stroke = (0.9f + 0.8f * depth) * px
        val edge = (0.25f + 0.75f * shine) * (0.6f + 0.4f * depth)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            shader = if (dark) {
                LinearGradient(
                    0f, 0f, w.toFloat(), h.toFloat(),
                    intArrayOf(
                        Color.argb((0.62f * edge * 255).toInt(), 255, 255, 255),
                        Color.argb((0.05f * edge * 255).toInt(), 255, 255, 255),
                        Color.argb((0.05f * edge * 255).toInt(), 255, 255, 255),
                        Color.argb((0.26f * edge * 255).toInt(), 255, 255, 255),
                    ),
                    floatArrayOf(0f, 0.4f, 0.7f, 1f),
                    Shader.TileMode.CLAMP,
                )
            } else {
                LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0x2E000000, 0x14000000, Shader.TileMode.CLAMP)
            }
        }
        val inset = stroke / 2f
        canvas.drawRoundRect(RectF(inset, inset, w - inset, h - inset), radius - inset, radius - inset, rim)
        val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.8f * px
            color = Color.argb(((if (dark) 0.10f else 0.35f) * shine * 255).toInt(), 255, 255, 255)
        }
        val gap = stroke + 0.8f * px
        canvas.drawRoundRect(RectF(gap, gap, w - gap, h - gap), max(0f, radius - gap), max(0f, radius - gap), inner)

        // 4. glass thickness: a soft band just inside the edge that is bright where light enters (top-left) and dark
        // where it leaves (bottom-right), like the bevel of a thick pane
        if (depth > 0f) {
            val bandWidth = (2.5f + 7f * depth) * px
            val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = bandWidth
                maskFilter = BlurMaskFilter(bandWidth * 0.8f, BlurMaskFilter.Blur.NORMAL)
                shader = LinearGradient(
                    0f, 0f, w.toFloat(), h.toFloat(),
                    intArrayOf(
                        Color.argb(((if (dark) 0.34f else 0.5f) * depth * (0.4f + 0.6f * shine) * 255).toInt(), 255, 255, 255),
                        0x00FFFFFF,
                        0x00000000,
                        Color.argb(((if (dark) 0.26f else 0.12f) * depth * 255).toInt(), 0, 0, 0),
                    ),
                    floatArrayOf(0f, 0.38f, 0.62f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.save()
            canvas.clipPath(shape)
            canvas.drawRoundRect(rect, radius, radius, bandPaint)
            canvas.restore()
        }

        // 5. a thin specular line along the top edge, fading out towards the corners
        if (dark && shine > 0f && w > radius * 2.6f) {
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = 1.1f * px
                strokeCap = Paint.Cap.ROUND
                shader = LinearGradient(
                    radius * 0.7f, 0f, w - radius * 0.7f, 0f,
                    intArrayOf(0x00FFFFFF, Color.argb((0.5f * shine * (0.5f + 0.5f * depth) * 255).toInt(), 255, 255, 255), 0x00FFFFFF),
                    floatArrayOf(0f, 0.35f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            val y = stroke + 0.7f * px
            canvas.drawLine(radius * 0.7f, y, w - radius * 0.7f, y, line)
        }
        return bitmap
    }

    private fun gradient(rect: RectF, from: Int, to: Int, vertical: Boolean) = Paint().apply {
        shader = if (vertical) {
            LinearGradient(0f, rect.top, 0f, rect.bottom, from, to, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(rect.left, 0f, rect.right, 0f, from, to, Shader.TileMode.CLAMP)
        }
    }

    /**
     * The liquid-glass styles (see [LiquidGlass]): the cover (or a colour wash) sits behind the pane, is blurred by the
     * frost setting, bent by the lens at the edge and finished with a rim light and a soft shadow. The see-through
     * style has no picture of its own, so only the pane, rim and shadow are drawn and the wallpaper shows.
     */
    private fun liquidGlass(config: AuralysWidgetConfig, state: AuralysWidgetState, w: Int, h: Int, px: Float, cornerDp: Float): Bitmap {
        val clear = config.style == WidgetStyle.CLEAR
        var sharp: IntArray? = null
        val scene = if (clear) {
            null
        } else {
            val raw = when {
                config.tint != 0 -> LiquidGlass.syntheticScene(w, h, config.tint)
                state.cover != null -> coverPixels(state.cover, w, h)
                else -> LiquidGlass.syntheticScene(w, h, DEFAULT_ACCENT)
            }
            sharp = LiquidGlass.blur(raw, w, h, 1.2f * px)
            LiquidGlass.blur(raw, w, h, (1.5f + 12.5f * config.frost / 100f) * px)
        }
        val params = LiquidGlass.paramsFor(
            w, h, px, cornerDp,
            edge = config.edge / 100f,
            shine = config.shine / 100f,
            opacity = config.opacity / 100f,
            luma = scene?.let(LiquidGlass::luma) ?: 0f,
            clear = clear,
        )
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(LiquidGlass.render(scene, params, sharp), 0, w, 0, 0, w, h)
        return bitmap
    }

    /** The cover cropped to the widget's shape and scaled to [w] x [h], as pixels. */
    private fun coverPixels(cover: Bitmap, w: Int, h: Int): IntArray {
        val aspect = w.toFloat() / h
        var cropW = cover.width.toFloat()
        var cropH = cropW / aspect
        if (cropH > cover.height) {
            cropH = cover.height.toFloat()
            cropW = cropH * aspect
        }
        val cropped = Bitmap.createBitmap(
            cover,
            ((cover.width - cropW) / 2).toInt().coerceAtLeast(0),
            ((cover.height - cropH) / 2).toInt().coerceAtLeast(0),
            cropW.toInt().coerceIn(1, cover.width),
            cropH.toInt().coerceIn(1, cover.height),
        )
        val scaled = Bitmap.createScaledBitmap(cropped, w, h, true)
        return IntArray(w * h).also { scaled.getPixels(it, 0, w, 0, 0, w, h) }
    }

    private fun darken(color: Int, factor: Float): Int =
        Color.rgb((Color.red(color) * factor).toInt(), (Color.green(color) * factor).toInt(), (Color.blue(color) * factor).toInt())

    private fun lighten(color: Int, factor: Float): Int =
        Color.rgb(
            min(255, (Color.red(color) * factor).toInt()),
            min(255, (Color.green(color) * factor).toInt()),
            min(255, (Color.blue(color) * factor).toInt()),
        )
}
