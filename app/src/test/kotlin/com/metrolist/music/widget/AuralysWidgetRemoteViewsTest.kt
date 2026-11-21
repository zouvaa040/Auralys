package com.metrolist.music.widget

import android.content.Context
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Builds the real RemoteViews for several widget shapes, lays them out at exactly that size and checks that every view
 * ends up where [AuralysWidgetLayout] said it would, and that hidden things are really gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class AuralysWidgetRemoteViewsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val state =
        AuralysWidgetState(
            title = "A rather long song title that has to be cut",
            artist = "Some artist",
            cover = null,
            accent = AuralysWidgetRenderer.DEFAULT_ACCENT,
            isPlaying = true,
            isLiked = false,
            duration = 200_000,
            position = 70_000,
        )

    private val shapes = listOf(250 to 110, 170 to 170, 320 to 72, 150 to 270, 70 to 70, 320 to 150, 100 to 100, 400 to 400)

    private fun layoutOut(w: Int, h: Int, config: AuralysWidgetConfig): FrameLayout {
        val views = AuralysWidgetRenderer.build(context, config, state, w, h, interactive = false)
        val frame = FrameLayout(context)
        frame.addView(views.apply(context, frame))
        val d = context.resources.displayMetrics.density
        val pw = (w * d).toInt()
        val ph = (h * d).toInt()
        frame.measure(MeasureSpec.makeMeasureSpec(pw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ph, MeasureSpec.EXACTLY))
        frame.layout(0, 0, pw, ph)
        return frame
    }

    private fun check(frame: FrameLayout, name: String, id: Int, rect: WRect?, size: String) {
        val view = frame.findViewById<View>(id)
        val d = context.resources.displayMetrics.density
        if (rect == null) {
            assertEquals("$size $name should be gone", View.GONE, view.visibility)
            return
        }
        assertEquals("$size $name should show", View.VISIBLE, view.visibility)
        fun near(label: String, actual: Int, expectedDp: Float) =
            assertTrue("$size $name $label is $actual px, expected ${expectedDp * d}", abs(actual - expectedDp * d) <= 1.5f)
        near("left", view.left, rect.x)
        near("top", view.top, rect.y)
        near("width", view.width, rect.w)
        near("height", view.height, rect.h)
    }

    @Test
    fun `every view lands exactly where the layout engine put it`() {
        for ((w, h) in shapes) {
            val frame = layoutOut(w, h, AuralysWidgetConfig())
            val input = WidgetLayoutInput(showProgress = true)
            val spec = AuralysWidgetLayout.compute(w.toFloat(), h.toFloat(), input)
            val size = "${w}x$h ${spec.form}"
            check(frame, "cover", R.id.aw_cover, spec.cover, size)
            check(frame, "title", R.id.aw_title, spec.title, size)
            check(frame, "artist", R.id.aw_artist, spec.artist, size)
            check(frame, "like", R.id.aw_like_box, spec.like, size)
            check(frame, "prev", R.id.aw_prev_box, spec.prev, size)
            check(frame, "play", R.id.aw_play_box, spec.play, size)
            check(frame, "next", R.id.aw_next_box, spec.next, size)
            check(frame, "progress", R.id.aw_progress_box, spec.progress, size)
            check(frame, "time", R.id.aw_time_now, spec.timeNow, size)
        }
    }

    @Test
    fun `nothing a view shows leaves the widget`() {
        for ((w, h) in shapes) {
            val frame = layoutOut(w, h, AuralysWidgetConfig(showLike = true, showSkip = true))
            val pw = frame.width
            val ph = frame.height
            listOf(R.id.aw_cover, R.id.aw_title, R.id.aw_artist, R.id.aw_like_box, R.id.aw_prev_box, R.id.aw_play_box, R.id.aw_next_box, R.id.aw_progress_box)
                .map { frame.findViewById<View>(it) }
                .filter { it.visibility == View.VISIBLE }
                .forEach {
                    assertTrue("${w}x$h view ${it.id} leaves the widget", it.left >= -1 && it.top >= -1 && it.right <= pw + 1 && it.bottom <= ph + 1)
                }
        }
    }

    @Test
    fun `turning options off removes their views at any size`() {
        val config = AuralysWidgetConfig(showCover = false, showProgress = false, showLike = false, showSkip = false)
        for ((w, h) in shapes) {
            val frame = layoutOut(w, h, config)
            assertEquals("${w}x$h cover", View.GONE, frame.findViewById<View>(R.id.aw_cover).visibility)
            assertEquals("${w}x$h progress", View.GONE, frame.findViewById<View>(R.id.aw_progress_box).visibility)
            assertEquals("${w}x$h like", View.GONE, frame.findViewById<View>(R.id.aw_like_box).visibility)
            assertEquals("${w}x$h prev", View.GONE, frame.findViewById<View>(R.id.aw_prev_box).visibility)
            assertEquals("${w}x$h next", View.GONE, frame.findViewById<View>(R.id.aw_next_box).visibility)
            assertEquals("${w}x$h play", View.VISIBLE, frame.findViewById<View>(R.id.aw_play_box).visibility)
        }
    }

    @Test
    fun `every style draws a background of the right size at every shape`() {
        val density = context.resources.displayMetrics.density
        for (style in WidgetStyle.entries) for ((w, h) in shapes) {
            val bitmap = AuralysWidgetRenderer.background(AuralysWidgetConfig(style = style), state, w, h, density)
            assertTrue("$style ${w}x$h background ${bitmap.width}x${bitmap.height}", bitmap.width > 0 && bitmap.height > 0)
            val ratio = bitmap.width.toFloat() / bitmap.height
            assertEquals("$style ${w}x$h keeps its shape", w.toFloat() / h, ratio, 0.05f * w.toFloat() / h)
        }
    }
}
