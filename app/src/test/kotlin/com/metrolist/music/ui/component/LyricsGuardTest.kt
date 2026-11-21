/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The lyrics view must stay cheap on weak phones, and plain: the playback position is read only where it is drawn
 * (never while the list composes), the per-line effects live in the layer phase, blur has one gate, and there is no
 * accent colour or capital letters. It reads the Kotlin sources (the module folder is the working directory of unit tests).
 */
class LyricsGuardTest {
    private val root: File =
        listOf("src/main/kotlin", "app/src/main/kotlin")
            .map { File(it, "com/metrolist/music") }
            .firstOrNull { it.isDirectory }
            ?: error("sources not found from ${File(".").absolutePath}")

    private fun src(path: String): String = File(root, path).readText()

    private fun res(path: String): String = File(root.parentFile.parentFile.parentFile.parentFile, "res/$path").readText()

    private val experimental get() = src("ui/component/ExperimentalLyrics.kt").lines()
    private val line get() = src("ui/component/LyricsLine.kt").lines()
    private val parts get() = src("ui/component/LyricsParts.kt").lines()

    /** Lines (with the [back] before them) that mention [token]. */
    private fun mentions(lines: List<String>, token: String, back: Int = 12): List<Pair<Int, List<String>>> =
        lines.withIndex().filter { it.value.contains(token) && !it.value.trim().startsWith("//") && !it.value.trim().startsWith("*") }
            .map { (i, _) -> i to lines.subList(maxOf(0, i - back), i + 1) }

    @Test
    fun `the position is not read while the list composes`() {
        // every read of the position in ExperimentalLyrics is a lambda, a derived state, an event handler, a read without
        // observation or the frame loop that writes it
        val reads = mentions(experimental, "position.longValue")
        assertTrue("the position is read in ExperimentalLyrics", reads.isNotEmpty())
        for ((i, context) in reads) {
            val text = context.last()
            val ok = text.contains("{ position.longValue") ||
                text.contains("withoutReadObservation") ||
                text.trim().startsWith("position.longValue =") ||
                context.any { it.contains("derivedStateOf") } ||
                context.any { it.contains("rememberUpdatedState") } ||
                context.any { it.contains("= remember {") }
            assertTrue("ExperimentalLyrics.kt:${i + 1} reads the position while composing: ${text.trim()}", ok)
        }
        for ((i, context) in mentions(experimental, "positionProvider()")) {
            val text = context.last()
            val ok = text.contains("{ positionProvider()") || context.any { it.contains("derivedStateOf") }
            assertTrue("ExperimentalLyrics.kt:${i + 1} reads the position while composing: ${text.trim()}", ok)
        }
        // the lines get a provider, never a value
        val call = experimental.joinToString("\n").let { it.substring(it.indexOf("LyricsLine(")) }
        assertTrue("LyricsLine gets the position as a provider", call.contains("currentPositionProvider = positionProvider"))
    }

    @Test
    fun `the lyrics follow the player every frame, the slider only while it is held`() {
        // 4.3.4 handed the lyrics the player screen's progress value (refreshed every 100 ms) as if the slider were held:
        // the sweep moved in 100 ms steps and the view thought the user was always seeking
        val player = src("ui/player/Player.kt").replace("\r\n", "\n")
        assertTrue("the inline lyrics get the progress value again", !player.contains("positionProvider = { effectivePosition },\n                                )"))
        assertTrue("both inline lyrics calls hand the slider", player.split("sliderPositionProvider = sliderPositionProvider,\n                                    playhead = if (isCasting) castPlayhead else null,").size == 3)
        val e = src("ui/component/ExperimentalLyrics.kt")
        assertTrue("the view runs the lyric clock", e.contains("runLyricsPlayhead(playhead, clock, sliderPositionProvider)"))
        val o = src("ui/component/OriginalLyrics.kt")
        assertTrue("the classic view runs the lyric clock", o.contains("runLyricsPlayhead(playheadSource, lyricClock, sliderPositionProvider)"))
        assertTrue("the classic view polls again", !o.contains("delay(8)"))
        val clock = src("ui/component/LyricsPlayhead.kt")
        assertTrue("the clock never runs backwards on its own", clock.contains("coerceIn(-MAX_SLOW * step, MAX_FAST * step)"))
        // a held slider value must always end (review 2026-11-13, W1): released, cancelled, disabled, or another song
        assertTrue("the slider reset is gated again", Regex("""if \(enabled\) sliderPositionState\.value\?\.let \{ onSeek\(it\) \}\s+sliderPositionState\.value = null""").containsMatchIn(player))
        assertTrue("a held value outlives its song", player.contains("LaunchedEffect(mediaMetadata?.id) { sliderPosition = null }"))
        for (f in listOf("ui/component/WavySlider.kt", "ui/component/SquigglySlider.kt")) {
            val slider = src(f)
            assertTrue("$f: a cancelled drag does not end", Regex("""onDragCancel = \{\s*isDragging = false\s*latestFinished\?\.invoke\(\)""").containsMatchIn(slider))
            assertTrue("$f: a drag cut by its gesture block does not end", slider.contains("} finally {"))
        }
        assertTrue("cast buffering counts as playing", player.contains("playing = { h.castIsPlaying.value && !h.castIsBuffering.value }"))
        // the old lyrics stay still while the new song's lyrics are on their way (W5), and a fling stops for new lyrics (W6)
        assertTrue(e.contains("lyricsEntity.id != mediaMetadata?.id"))
        assertTrue(Regex("""LaunchedEffect\(lineTimes\) \{\s+// a fling still running[^\n]*\s+flingJob\?\.cancel\(\)""").containsMatchIn(e))
    }

    @Test
    fun `one glide moves the rows, in the placement, from measured heights`() {
        val e = src("ui/component/ExperimentalLyrics.kt")
        val scroller = src("ui/component/LyricsScroller.kt")
        // no animation per row any more, and no row placed from a guessed height
        assertTrue("a row animates its own offset again", !e.contains("animateFloatAsState"))
        assertTrue("rows are hidden while they wait for a height again", !e.contains("isExact("))
        assertTrue(scroller.contains("rowMeasurables[i].measure(rowConstraints)"))
        assertTrue(scroller.contains("if (prev >= 0 && glide()) follow.shift(top[a] - top[prev], a) else follow.stop()"))
        // the distance a row is given stops where its look stops changing, so far rows never recompose
        assertTrue(e.contains("min(abs(listIndex - anchor.value), LYRICS_DISTANCE_CAP)"))
    }

    @Test
    fun `the lines read the position only in the draw phase`() {
        for (token in listOf("positionProvider()", "currentPositionProvider()")) {
            for ((i, context) in mentions(line, token, back = 60)) {
                val text = context.last()
                val ok = text.contains("{ currentPositionProvider()") || // the lambda that is handed down
                    context.any { it.contains("Canvas(") || it.contains("drawWithContent") }
                assertTrue("LyricsLine.kt:${i + 1} reads the position outside a draw block: ${text.trim()}", ok)
            }
        }
        for (token in listOf("positionProvider()", "lineAlpha()", "progress()")) {
            for ((i, context) in mentions(parts, token)) {
                val text = context.last()
                val ok = context.any { it.contains("drawWithContent") || it.contains("Canvas(") } || text.contains("fun DrawScope") || text.contains("lineAlpha: Float")
                assertTrue("LyricsParts.kt:${i + 1} reads a provider outside a draw block: ${text.trim()}", ok)
            }
        }
    }

    @Test
    fun `a line knows nothing about the player and does not recompose for its animations`() {
        val text = line.joinToString("\n")
        for (banned in listOf("PlayerConnection", "playerConnection", "hiltViewModel", "LocalPlayerConnection")) {
            assertTrue("LyricsLine.kt mentions $banned", !text.contains(banned))
        }
        val all = text + "\n" + parts.joinToString("\n")
        // the animated look is held as States and read in graphicsLayer / draw, not as values in the composition
        assertTrue("an animated value is read in the composition", !Regex("""val\s+\w+\s+by\s+animate\w+AsState""").containsMatchIn(all))
        assertTrue("the depth is applied in the layer phase", parts.joinToString("\n").contains("depth.scale.value") && parts.joinToString("\n").contains("depth.alpha.value"))
    }

    @Test
    fun `blur has one gate and goes through the layer, not through a modifier on every line`() {
        val lyricFiles = listOf("ui/component/LyricsLine.kt", "ui/component/LyricsParts.kt", "ui/component/ExperimentalLyrics.kt", "ui/component/LyricsCommon.kt")
        for (f in lyricFiles) {
            assertTrue("$f uses Modifier.blur", !src(f).contains(".blur("))
        }
        val p = src("ui/component/LyricsParts.kt")
        assertTrue("the blur is a RenderEffect on the line's own layer", p.contains("renderEffect = BlurEffects.of("))
        assertTrue("the blur effects are made once per radius step", p.contains("cache.getOrPut(step) { BlurEffect("))
        assertTrue("nothing is clipped by the blur", p.contains("TileMode.Decal"))
        // the radius a line asks for is zero unless the one rule allows it
        val l = src("ui/component/LyricsLine.kt")
        assertTrue("the line asks LyricsMotion for its blur", l.contains("LyricsMotion.targetBlurDp(motion.blurAllowed"))
        assertTrue("the gate reads API level, low RAM, battery saver, animation scale and the user's switch",
            p.contains("LyricsMotion.blurAllowed(Build.VERSION.SDK_INT, lowRam, powerSave, animationScale, userBlur)"))
        assertTrue("low RAM is asked of the system", p.contains("isLowRamDevice"))
        assertTrue("battery saver is asked of the system", p.contains("isPowerSaveMode"))
        assertTrue("the animation scale is asked of the system", p.contains("ANIMATOR_DURATION_SCALE"))
    }

    @Test
    fun `motion stops when the animation scale is zero`() {
        val e = src("ui/component/ExperimentalLyrics.kt")
        assertTrue("the scroll of the rows does not follow the motion switch", e.contains("glide = { isAutoScrollEnabled && motion.animate }"))
        assertTrue("the glide does not stop when motion is off", e.contains("follow.runGlide { animateLatest }"))
        assertTrue("the glide does not jump when motion is off", src("ui/component/LyricsScroller.kt").contains("if (!animate()) {"))
        assertTrue("the lines get the environment", e.contains("motion = motionEnv"))
        val p = src("ui/component/LyricsParts.kt")
        assertTrue("the depth jumps when motion is off", p.contains("if (animate && anim.value != target) anim.animateTo(target, spec) else anim.snapTo(target)"))
        assertTrue("the dots do not breathe when motion is off", src("ui/component/LyricsCommon.kt").contains("breathing = visible && animate"))
    }

    @Test
    fun `the view is ink on the page, plain type, no accent`() {
        for (f in listOf("ui/component/LyricsLine.kt", "ui/component/LyricsParts.kt", "ui/component/LyricsCommon.kt", "ui/component/LyricsMotion.kt")) {
            val text = src(f)
            for (banned in listOf("colorScheme.primary", "colorScheme.tertiary", "colorScheme.secondary", ".uppercase(", "isGlassStyle", "Color(0x", "Serif")) {
                assertTrue("$f has $banned", !text.contains(banned))
            }
            for (m in Regex("""letterSpacing\s*=\s*\(?\s*(-?[0-9]*\.?[0-9]+)\s*\)?\s*\.(sp|em)""").findAll(text)) {
                assertTrue("$f: letterSpacing ${m.groupValues[1]}", m.groupValues[1].toDouble() <= 0.1)
            }
        }
        val e = src("ui/component/ExperimentalLyrics.kt")
        assertTrue("the gap dots take the ink", !e.contains("intervalAccent") && !e.contains("colorScheme.primary"))
    }

    @Test
    fun `the top and the bottom fade is one offscreen layer with a DstIn gradient`() {
        val p = src("ui/component/LyricsParts.kt")
        assertTrue(p.contains("CompositingStrategy.Offscreen"))
        assertTrue(p.contains("BlendMode.DstIn"))
        assertTrue(src("ui/component/ExperimentalLyrics.kt").contains(".lyricsEdgeFade("))
        assertTrue("the old fixed dp fade is gone", !src("ui/component/ExperimentalLyrics.kt").contains("fadingEdge("))
    }

    @Test
    fun `the blur switch is a boolean on by default, in the settings and in the search`() {
        assertTrue(src("constants/PreferenceKeys.kt").contains("val LyricsBlurKey = booleanPreferencesKey("))
        val settings = src("ui/screens/settings/AppearanceSettings.kt")
        assertTrue(settings.contains("rememberPreference(LyricsBlurKey, defaultValue = true)"))
        assertTrue(settings.contains("R.string.auralys_lyrics_blur"))
        assertTrue(src("ui/screens/settings/SettingsSearchIndex.kt").contains("R.string.auralys_lyrics_blur"))
        assertTrue(src("ui/component/ExperimentalLyrics.kt").contains("rememberPreference(LyricsBlurKey, true)"))
        // the row has no subtitle
        val row = settings.substring(settings.indexOf("R.string.auralys_lyrics_blur"), settings.indexOf("onLyricsBlurChange(!lyricsBlur)"))
        assertTrue("the blur row has a description", !row.contains("description ="))
    }

    @Test
    fun `the position default is the one constant, the explicit choice is kept`() {
        assertTrue(src("ui/component/ExperimentalLyrics.kt").contains("rememberEnumPreference(LyricsTextPositionKey, DEFAULT_LYRICS_POSITION)"))
        assertTrue(src("ui/screens/settings/AppearanceSettings.kt").contains("defaultValue = DEFAULT_LYRICS_POSITION"))
        assertTrue(src("ui/component/LyricsMotion.kt").contains("internal val DEFAULT_LYRICS_POSITION = LyricsPosition.LEFT"))
    }

    @Test
    fun `the strings exist in English and Indonesian in their own files, in plain wording`() {
        for (lang in listOf("values", "values-in")) {
            val xml = res("$lang/auralys_lyrics.xml")
            assertTrue("$lang is missing the blur string", xml.contains("name=\"auralys_lyrics_blur\""))
        }
        val id = res("values-in/auralys_lyrics.xml")
        assertTrue(id.contains(">Buramkan baris lain<"))
        for (banned in listOf("Anda", "kalau", "gue", "banget", "dong", "nih")) {
            assertTrue("Indonesian wording: $banned", !id.contains(banned))
        }
        // no other file got the string
        val main = File(root.parentFile.parentFile.parentFile.parentFile, "res")
        for (dir in listOf("values", "values-in")) {
            File(main, dir).listFiles { f -> f.extension == "xml" && f.name != "auralys_lyrics.xml" }!!.forEach {
                assertTrue("${it.name} defines auralys_lyrics_blur", !it.readText().contains("name=\"auralys_lyrics_blur\""))
            }
        }
    }

    @Test
    fun `the classic lyrics view is left alone`() {
        val original = src("ui/component/OriginalLyrics.kt")
        for (token in listOf("LyricsMotion", "LyricsParts", "lyricsEdgeFade", "LyricDots", "LyricsBlurKey")) {
            assertTrue("OriginalLyrics.kt uses $token", !original.contains(token))
        }
    }
}
