package com.metrolist.music.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuralysWidgetLayoutTest {
    private val widths = listOf(40, 57, 70, 90, 110, 150, 170, 250, 320, 400)
    private val heights = listOf(40, 57, 70, 90, 110, 150, 200, 270, 400)

    private val inputs = listOf(
        WidgetLayoutInput(),
        WidgetLayoutInput(showCover = false),
        WidgetLayoutInput(showProgress = false, showLike = false, showSkip = false),
        WidgetLayoutInput(showTime = false, textScale = 1.12f),
        WidgetLayoutInput(showCover = false, showProgress = false, showLike = false, showSkip = false, textScale = 0.88f),
        // 4.3.4: the listening streak badge (1 to 4 digits) at every size
        WidgetLayoutInput(streakDays = 3),
        WidgetLayoutInput(streakDays = 128, textScale = 1.12f),
        WidgetLayoutInput(streakDays = 1000, showCover = false, showLike = false),
        WidgetLayoutInput(streakDays = 45, showProgress = false, showSkip = false, textScale = 0.88f),
    )

    private fun everySize(check: (Int, Int, WidgetLayoutInput, WidgetLayoutSpec) -> Unit) {
        for (w in widths) for (h in heights) for (input in inputs) {
            check(w, h, input, AuralysWidgetLayout.compute(w.toFloat(), h.toFloat(), input))
        }
    }

    @Test
    fun `everything stays inside the widget at every size`() = everySize { w, h, _, spec ->
        spec.all.forEach { r ->
            assertTrue("${w}x$h ${spec.form}: $r leaves the widget", r.x >= -0.01f && r.y >= -0.01f && r.right <= w + 0.01f && r.bottom <= h + 0.01f)
        }
    }

    @Test
    fun `nothing overlaps except the play button on the cover of a tiny widget`() = everySize { w, h, _, spec ->
        val parts = listOfNotNull(
            spec.cover?.let { "cover" to it }, spec.title?.let { "title" to it }, spec.artist?.let { "artist" to it },
            spec.like?.let { "like" to it }, spec.prev?.let { "prev" to it }, spec.play?.let { "play" to it },
            spec.next?.let { "next" to it }, spec.progress?.let { "progress" to it },
            spec.timeNow?.let { "now" to it }, spec.timeTotal?.let { "total" to it },
            spec.streak?.let { "streak" to it },
        )
        for (i in parts.indices) for (j in i + 1 until parts.size) {
            val (a, ra) = parts[i]
            val (b, rb) = parts[j]
            if (spec.form == WidgetForm.MICRO && setOf(a, b) == setOf("cover", "play")) continue
            assertTrue("${w}x$h ${spec.form}: $a $ra overlaps $b $rb", !ra.overlaps(rb))
        }
    }

    @Test
    fun `the play button is always there and big enough to hit`() = everySize { w, h, _, spec ->
        val play = spec.play
        assertNotNull("${w}x$h has no play button", play)
        assertTrue("${w}x$h play ${play!!.w}x${play.h}", play.w >= 26f && play.h >= 26f)
    }

    @Test
    fun `the buttons that stay are wide enough`() = everySize { w, h, _, spec ->
        listOfNotNull(spec.like, spec.prev, spec.next).forEach {
            assertTrue("${w}x$h ${spec.form} button ${it.w}x${it.h}", it.w >= AuralysWidgetLayout.MIN_BUTTON_WIDTH - 0.01f && it.h >= 26f)
        }
    }

    @Test
    fun `the cover is always a square`() = everySize { w, h, _, spec ->
        spec.cover?.let { assertEquals("${w}x$h cover", it.w, it.h, 0.01f) }
    }

    @Test
    fun `text boxes are tall enough for their text`() = everySize { w, h, _, spec ->
        spec.title?.let { assertTrue("${w}x$h title ${it.h} for ${spec.titleSize}", it.h >= spec.titleSize * 1.2f && it.w >= 24f) }
        spec.artist?.let { assertTrue("${w}x$h artist ${it.h} for ${spec.artistSize}", it.h >= spec.artistSize * 1.2f && it.w >= 24f) }
    }

    @Test
    fun `a roomy widget shows the cover, both texts and the buttons`() {
        for ((w, h) in listOf(170 to 170, 250 to 110, 320 to 150, 150 to 270, 400 to 400, 210 to 120)) {
            val spec = AuralysWidgetLayout.compute(w.toFloat(), h.toFloat())
            assertNotNull("${w}x$h cover", spec.cover)
            assertNotNull("${w}x$h title", spec.title)
            assertNotNull("${w}x$h artist", spec.artist)
            assertNotNull("${w}x$h prev", spec.prev)
            assertNotNull("${w}x$h next", spec.next)
        }
    }

    @Test
    fun `a small square keeps a cover beside the texts`() {
        val spec = AuralysWidgetLayout.compute(170f, 170f)
        assertEquals(WidgetForm.STACK, spec.form)
        assertTrue("cover ${spec.cover}", spec.cover!!.w >= 40f)
        assertNotNull(spec.title)
    }

    @Test
    fun `a big square gets a big cover on top`() {
        val spec = AuralysWidgetLayout.compute(320f, 320f)
        assertEquals(WidgetForm.HERO, spec.form)
        assertTrue("cover ${spec.cover}", spec.cover!!.w >= 150f)
    }

    @Test
    fun `the standard 4x2 widget shows all four buttons`() {
        val spec = AuralysWidgetLayout.compute(250f, 110f)
        assertEquals(WidgetForm.BESIDE, spec.form)
        listOf(spec.like, spec.prev, spec.play, spec.next).forEach { assertNotNull(it) }
        assertNotNull(spec.title)
        assertNotNull(spec.artist)
        assertTrue("texts sit right of the cover", spec.title!!.x >= spec.cover!!.right)
    }

    @Test
    fun `time labels are left out when there is no room for them`() {
        // a 2x4 widget is only about 120 dp of content wide
        val narrow = AuralysWidgetLayout.compute(110f, 270f)
        assertNotNull(narrow.progress)
        assertNull(narrow.timeNow)
        assertNull(narrow.timeTotal)
        val wide = AuralysWidgetLayout.compute(320f, 150f)
        assertNotNull(wide.timeNow)
        assertNotNull(wide.timeTotal)
    }

    @Test
    fun `a flat strip lays everything out in one row`() {
        val spec = AuralysWidgetLayout.compute(320f, 72f)
        assertEquals(WidgetForm.ROW, spec.form)
        assertNull(spec.progress)
        val cover = spec.cover!!
        val title = spec.title!!
        assertTrue("texts sit right of the cover", title.x >= cover.right)
        assertTrue("buttons sit right of the texts", spec.like!!.x >= title.right)
    }

    @Test
    fun `a tall widget gives the cover the full width`() {
        val spec = AuralysWidgetLayout.compute(150f, 270f)
        assertEquals(WidgetForm.HERO, spec.form)
        val content = 150f - 2 * spec.cover!!.x
        assertEquals(content, spec.cover!!.w, 0.5f)
    }

    @Test
    fun `a tiny widget shows the cover with the play button on it`() {
        val spec = AuralysWidgetLayout.compute(70f, 70f)
        assertEquals(WidgetForm.MICRO, spec.form)
        assertNotNull(spec.cover)
        assertNull(spec.title)
        assertNull(spec.progress)
        val cover = spec.cover!!
        val play = spec.play!!
        assertTrue("play is centred on the cover", kotlin.math.abs((play.x + play.w / 2) - (cover.x + cover.w / 2)) < 0.5f)
    }

    @Test
    fun `without a cover a tiny widget is just a play button`() {
        val spec = AuralysWidgetLayout.compute(70f, 70f, WidgetLayoutInput(showCover = false))
        assertNull(spec.cover)
        assertNotNull(spec.play)
    }

    @Test
    fun `the progress bar appears only when there is height for it`() {
        assertNull(AuralysWidgetLayout.compute(320f, 72f).progress)
        assertNotNull(AuralysWidgetLayout.compute(320f, 150f).progress)
        assertNull(AuralysWidgetLayout.compute(320f, 150f, WidgetLayoutInput(showProgress = false)).progress)
    }

    @Test
    fun `hiding the times keeps the bar full width`() {
        val withTime = AuralysWidgetLayout.compute(320f, 150f).progress!!
        val without = AuralysWidgetLayout.compute(320f, 150f, WidgetLayoutInput(showTime = false))
        assertNull(without.timeNow)
        assertTrue(without.progress!!.w > withTime.w)
    }

    @Test
    fun `a narrow widget drops the like and skip buttons instead of squeezing them`() {
        val spec = AuralysWidgetLayout.compute(100f, 100f)
        assertNotNull(spec.play)
        listOfNotNull(spec.like, spec.prev, spec.next).forEach { assertTrue(it.w >= AuralysWidgetLayout.MIN_BUTTON_WIDTH - 0.01f) }
    }

    // ------------------------------------------------------------------------------------------- streak (4.3.4)

    @Test
    fun `no streak, no badge, and nothing else moves`() = everySize { w, h, input, spec ->
        if (input.streakDays > 0) return@everySize
        assertNull("${w}x$h", spec.streak)
    }

    @Test
    fun `the badge sits on the title or artist line, leaves the text room and is big enough to read`() = everySize { w, h, input, spec ->
        val badge = spec.streak ?: return@everySize
        assertTrue("${w}x$h ${spec.form} streak without days", input.streakDays > 0)
        assertTrue("${w}x$h badge ${badge.w}x${badge.h}", badge.h >= 11f && spec.streakIcon >= 11f && spec.streakTextSize >= 8f)
        // the digits fit: icon + gap + about 0.6 em per digit
        val digits = input.streakDays.toString().length
        assertTrue("${w}x$h badge too narrow for $digits digits", badge.w >= spec.streakIcon + 2f + digits * spec.streakTextSize * 0.6f)
        val line = listOfNotNull(spec.title, spec.artist).firstOrNull { badge.y >= it.y - 0.01f && badge.bottom <= it.bottom + 0.01f }
        val lastText = spec.artist ?: spec.title
        // either at the end of a text line, or (narrow text column) on a line of its own right under the texts
        val below = lastText != null && kotlin.math.abs(badge.x - spec.title!!.x) < 0.01f && badge.y >= lastText.bottom - 0.01f
        if (spec.form != WidgetForm.MICRO) {
            assertTrue("${w}x$h ${spec.form} badge $badge is neither on nor under the texts", line != null || below)
        }
        if (line != null) {
            assertTrue("${w}x$h text left ${line.w}", line.w >= AuralysWidgetLayout.MIN_TITLE_WITH_STREAK - 0.01f)
            assertTrue("${w}x$h badge after the text", badge.x >= line.right)
        }
    }

    @Test
    fun `the badge shows on the usual widget sizes`() {
        for ((w, h) in listOf(250 to 110, 320 to 72, 170 to 170, 150 to 270, 320 to 150, 400 to 400, 110 to 270, 210 to 120, 110 to 110)) {
            val spec = AuralysWidgetLayout.compute(w.toFloat(), h.toFloat(), WidgetLayoutInput(streakDays = 12))
            assertNotNull("${w}x$h ${spec.form} has no streak badge", spec.streak)
        }
    }

    @Test
    fun `with the badge the title is shorter by exactly the badge and a gap`() {
        val plain = AuralysWidgetLayout.compute(250f, 110f)
        val withBadge = AuralysWidgetLayout.compute(250f, 110f, WidgetLayoutInput(streakDays = 12))
        val badge = withBadge.streak!!
        assertEquals(plain.title!!.w - badge.w - 4f, withBadge.title!!.w, 0.01f)
        assertEquals(plain.title!!.right, badge.right, 0.01f)
        // everything else stays where it was
        assertEquals(plain.copy(title = null, streak = null, streakIcon = 0f, streakTextSize = 0f), withBadge.copy(title = null, streak = null, streakIcon = 0f, streakTextSize = 0f))
    }

    @Test
    fun `corner radius follows the setting and never passes half the short side`() {
        assertEquals(4f, AuralysWidgetRenderer.cornerRadiusDp(0, 300f, 300f), 0.01f)
        assertEquals(40f, AuralysWidgetRenderer.cornerRadiusDp(100, 300f, 300f), 0.01f)
        assertEquals(27.8f, AuralysWidgetRenderer.cornerRadiusDp(AuralysWidgetConfig.DEFAULT_CORNER, 300f, 300f), 0.2f)
        assertEquals(20f, AuralysWidgetRenderer.cornerRadiusDp(100, 300f, 40f), 0.01f)
    }
}
