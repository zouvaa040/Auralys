package com.metrolist.music.widget

import android.content.Context
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.R
import com.metrolist.music.utils.StreakTiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/** 4.3.4: the listening streak (flame + days) on the home-screen widget, in the real RemoteViews. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class AuralysWidgetStreakTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun state(days: Int, lit: Boolean = true) =
        AuralysWidgetState(
            title = "A rather long song title that has to be cut",
            artist = "Some artist",
            cover = null,
            accent = AuralysWidgetRenderer.DEFAULT_ACCENT,
            isPlaying = true,
            isLiked = false,
            duration = 200_000,
            position = 70_000,
            streakDays = days,
            streakLit = lit,
        )

    private val shapes = listOf(250 to 110, 170 to 170, 320 to 72, 150 to 270, 70 to 70, 110 to 40, 320 to 150, 100 to 100, 400 to 400)

    private fun layoutOut(w: Int, h: Int, config: AuralysWidgetConfig, state: AuralysWidgetState): FrameLayout {
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

    @Test
    fun `the badge lands where the layout put it and shows the days`() {
        val d = context.resources.displayMetrics.density
        for ((w, h) in shapes) {
            val st = state(12)
            val frame = layoutOut(w, h, AuralysWidgetConfig(), st)
            val spec = AuralysWidgetLayout.compute(w.toFloat(), h.toFloat(), WidgetLayoutInput(streakDays = 12))
            val badge = frame.findViewById<View>(R.id.aw_streak)
            val rect = spec.streak
            if (rect == null) {
                assertEquals("${w}x$h badge should be gone", View.GONE, badge.visibility)
                continue
            }
            assertEquals("${w}x$h badge should show", View.VISIBLE, badge.visibility)
            fun near(label: String, actual: Int, expectedDp: Float) =
                assertTrue("${w}x$h badge $label $actual px vs ${expectedDp * d}", abs(actual - expectedDp * d) <= 1.5f)
            near("left", badge.left, rect.x)
            near("top", badge.top, rect.y)
            near("width", badge.width, rect.w)
            near("height", badge.height, rect.h)
            assertEquals("12", frame.findViewById<TextView>(R.id.aw_streak_text).text.toString())
            // the title view shrank to make room (still where the layout said)
            val title = frame.findViewById<View>(R.id.aw_title)
            spec.title?.let { near("title width", title.width, it.w) }
            // the text inside the badge is not cut: its measured width fits the room after the icon
            val text = frame.findViewById<TextView>(R.id.aw_streak_text)
            val need = text.paint.measureText("12")
            assertTrue("${w}x$h digits need $need px, badge text room ${text.width}", text.width + 1 >= need)
        }
    }

    @Test
    fun `no streak or the option off hides the badge at every size`() {
        for ((w, h) in shapes) {
            assertEquals("${w}x$h no streak", View.GONE, layoutOut(w, h, AuralysWidgetConfig(), state(0)).findViewById<View>(R.id.aw_streak).visibility)
            assertEquals(
                "${w}x$h switched off",
                View.GONE,
                layoutOut(w, h, AuralysWidgetConfig(showStreak = false), state(30)).findViewById<View>(R.id.aw_streak).visibility,
            )
        }
    }

    @Test
    fun `the badge shows on the common sizes`() {
        for ((w, h) in listOf(250 to 110, 170 to 170, 320 to 72, 150 to 270, 320 to 150)) {
            assertEquals("${w}x$h", View.VISIBLE, layoutOut(w, h, AuralysWidgetConfig(), state(7)).findViewById<View>(R.id.aw_streak).visibility)
        }
    }

    @Test
    fun `lit flame takes the streak colour, a waiting one the quiet text colour`() {
        val quiet = 0x80FFFFFF.toInt()
        assertEquals(StreakTiers.forDays(30).glow, AuralysWidgetRenderer.streakFlameColor(state(30, lit = true), quiet))
        assertEquals(quiet, AuralysWidgetRenderer.streakFlameColor(state(30, lit = false), quiet))
        assertEquals(0, AuralysWidgetRenderer.streakDaysShown(AuralysWidgetConfig(showStreak = false), state(30)))
        assertEquals(30, AuralysWidgetRenderer.streakDaysShown(AuralysWidgetConfig(), state(30)))
    }

    @Test
    fun `the option is saved per widget and defaults to on`() {
        assertTrue(AuralysWidgetConfig().showStreak)
        AuralysWidgetConfigStore.save(context, 77, AuralysWidgetConfig(showStreak = false))
        assertEquals(false, AuralysWidgetConfigStore.load(context, 77).showStreak)
        AuralysWidgetConfigStore.delete(context, 77)
        assertEquals(true, AuralysWidgetConfigStore.load(context, 77).showStreak)
    }
}

/** Android 8-11 use the three fixed layouts: the badge sits at the end of the title row. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class AuralysWidgetStreakClassicTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `classic layouts show the badge beside the title and hide it without a streak`() {
        for ((w, h) in listOf(250 to 110, 320 to 72, 150 to 150)) {
            val base =
                AuralysWidgetState(
                    title = "Song",
                    artist = "Artist",
                    cover = null,
                    accent = AuralysWidgetRenderer.DEFAULT_ACCENT,
                    isPlaying = false,
                    isLiked = false,
                    duration = 0,
                    position = 0,
                )
            val shown = AuralysWidgetRenderer.build(context, AuralysWidgetConfig(), base.copy(streakDays = 5, streakLit = true), w, h, interactive = false)
            val frame = FrameLayout(context)
            val view = shown.apply(context, frame)
            val badge = view.findViewById<View>(R.id.aw_streak)
            assertNotNull("${w}x$h", badge)
            assertEquals("${w}x$h", View.VISIBLE, badge.visibility)
            assertEquals("5", view.findViewById<TextView>(R.id.aw_streak_text).text.toString())
            val hidden = AuralysWidgetRenderer.build(context, AuralysWidgetConfig(), base, w, h, interactive = false).apply(context, FrameLayout(context))
            assertEquals("${w}x$h", View.GONE, hidden.findViewById<View>(R.id.aw_streak).visibility)
        }
    }
}
