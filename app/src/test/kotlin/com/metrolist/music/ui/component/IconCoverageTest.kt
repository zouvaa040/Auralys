/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import com.metrolist.music.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The icon style setting (Garis / iOS / Bulat) must reach EVERY icon of the app. This ratchet reads the Kotlin sources
 * and the resources and fails when somebody adds an icon without twins, or draws one through a path that skips the
 * family (androidx painterResource, material-icons vectors, Coil, a system API that takes the raw id).
 *
 * Allow-lists have a reason on every entry and can only shrink. `ic_widget_mic` and the brand marks are the only
 * drawables the family cannot change, see [IconSources.exempt].
 */
class IconCoverageTest {
    private val exempt: Map<String, String> = IconSources.exempt

    private fun isIcon(name: String) = IconSources.isFamilyIcon(name) && name !in exempt

    private fun usedIcons(): Map<String, List<String>> = IconSources.drawableUses.filterKeys { isIcon(it) }

    private fun idOf(name: String): Int = IconSources.drawableIds[name] ?: error("R.drawable.$name does not exist")

    // ------------------------------------------------------------------ 1. every icon has both twins

    @Test
    fun `every icon the code draws has an iOS twin and a Bulat entry`() {
        val missingIos = mutableListOf<String>()
        val missingRound = mutableListOf<String>()
        for ((name, uses) in usedIcons()) {
            val id = idOf(name)
            if (IosIconTable.twinFor(id) == null) missingIos += "$name (${uses.first()})"
            if (BulatIconTable.vectorFor(id) == null) missingRound += "$name (${uses.first()})"
        }
        assertTrue("no iOS twin: $missingIos. Add them to Tools/build_ios_twins.py and run it.", missingIos.isEmpty())
        assertTrue("no Bulat entry: $missingRound. Add them to BulatIconTable.", missingRound.isEmpty())
    }

    @Test
    fun `the two tables name the same icons and no dead entries`() {
        val ios = IosIconTable.entries.map { IconSources.drawableNames.getValue(it.first) }
        val round = BulatIconTable.entries.map { IconSources.drawableNames.getValue(it.resId) }
        assertEquals("a drawable appears twice in the iOS table", ios.size, ios.toSet().size)
        assertEquals("the iOS and the Bulat table list different icons", ios.toSet(), round.toSet())
        val used = usedIcons().keys
        assertEquals("table entries that no code draws (they would keep their resources in the APK)", emptySet<String>(), ios.toSet() - used)
        assertEquals("icons drawn by code that the tables do not list", emptySet<String>(), used - ios.toSet())
    }

    @Test
    fun `exemptions are real drawables with a reason`() {
        for ((name, reason) in exempt) {
            assertTrue("$name is exempt but is not a drawable", IconSources.drawableIds.containsKey(name))
            assertTrue("$name has no reason", reason.length > 15)
            assertTrue("$name is exempt and is also in a table", IosIconTable.twinFor(idOf(name)) == null && BulatIconTable.vectorFor(idOf(name)) == null)
        }
    }

    // ------------------------------------------------------------------ 2. nothing draws around the wrapper

    private fun code(): List<IconSources.Source> = IconSources.files.filter { it.name !in IconSources.tableFiles }

    private fun offenders(
        files: List<IconSources.Source>,
        regex: Regex,
        skip: (IconSources.Source) -> Boolean = { false },
    ): List<String> =
        files.filter { !skip(it) }.flatMap { f ->
            f.lines.mapIndexedNotNull { i, line ->
                if (!IconSources.isComment(line) && regex.containsMatchIn(line)) "${f.path}:${i + 1}" else null
            }
        }

    @Test
    fun `no file draws icons through the androidx painterResource`() {
        val bad = offenders(code(), Regex("""androidx\.compose\.ui\.res\.(painterResource|\*)"""))
        assertTrue("these skip the family: $bad (use com.metrolist.music.ui.component.painterResource)", bad.isEmpty())
    }

    @Test
    fun `every painterResource call reaches the wrapper`() {
        val bad = mutableListOf<String>()
        for (f in code()) {
            if (!f.text.contains("painterResource(")) continue
            val imported = f.text.contains("import com.metrolist.music.ui.component.painterResource")
            val samePackage = f.text.contains("package com.metrolist.music.ui.component\n") || f.text.contains("package com.metrolist.music.ui.component\r")
            if (!imported && !samePackage) bad += f.path
        }
        assertTrue("these call a painterResource that is not the wrapper: $bad", bad.isEmpty())
    }

    @Test
    fun `no material-icons vector and no raw vector painter outside the tables`() {
        val bad =
            offenders(
                code(),
                Regex("""\bIcons\.(Rounded|Filled|Outlined|Default|Sharp|TwoTone|AutoMirrored)\b|androidx\.compose\.material\.icons|imageVector\s*=|vectorResource\(|rememberVectorPainter"""),
                skip = { it.name == "IosIcons.kt" },
            )
        assertTrue("these draw a vector that ignores the family: $bad", bad.isEmpty())
    }

    @Test
    fun `no image loader is given a raw drawable id`() {
        val bad = mutableListOf<String>()
        for (f in code()) {
            f.lines.forEachIndexed { i, line ->
                if (IconSources.isComment(line) || !line.contains("R.drawable.")) return@forEachIndexed
                val window = f.lines.subList(maxOf(0, i - 2), i + 1).joinToString(" ")
                if (Regex("""AsyncImage|SubcomposeAsyncImage|ImageRequest|rememberAsyncImagePainter|\.data\(""").containsMatchIn(window)) {
                    bad += "${f.path}:${i + 1}"
                }
            }
        }
        assertTrue("these hand a drawable id to Coil: $bad", bad.isEmpty())
    }

    /**
     * Files that still hand a raw id to a system API; shrink only. Empty since 2026-11-07: the media notification buttons
     * (MusicService.updateNotification) and the update notification (MainActivity) go through SystemIcons.resolve too.
     */
    private val pendingSystemCalls: Map<String, Int> = emptyMap()

    @Test
    fun `system apis get the id of the stored family`() {
        val api = Regex("""setSmallIcon\(|Icon\.createWithResource\(|setIconResId\(|setImageViewResource\(|ContextCompat\.getDrawable\(|decodeResource\(""")
        val perFile = linkedMapOf<String, MutableList<String>>()
        for (f in code()) {
            f.lines.forEachIndexed { i, line ->
                if (IconSources.isComment(line)) return@forEachIndexed
                val names = Regex("""R\.drawable\.(\w+)""").findAll(line).map { it.groupValues[1] }.toList()
                if (names.isEmpty()) return@forEachIndexed
                val window = f.lines.subList(maxOf(0, i - 1), i + 1).joinToString(" ")
                if (!api.containsMatchIn(window)) return@forEachIndexed
                if (names.all { !isIcon(it) }) return@forEachIndexed
                if (window.contains("SystemIcons.resolve(")) return@forEachIndexed
                perFile.getOrPut(f.path) { mutableListOf() }.add("${i + 1}")
            }
        }
        for ((path, lines) in perFile) {
            val allowed = pendingSystemCalls[path] ?: 0
            assertTrue("$path passes a raw drawable id to a system API on lines $lines (wrap it in SystemIcons.resolve)", lines.size <= allowed)
        }
        for ((path, allowed) in pendingSystemCalls) {
            val now = perFile[path]?.size ?: 0
            assertTrue("$path has $now raw system-API ids, the pending list says $allowed: lower the number", now <= allowed)
        }
    }

    @Test
    fun `drawableUri resolves through the family`() {
        val f = IconSources.files.first { it.name == "MediaLibrarySessionCallback.kt" }
        val start = f.text.indexOf("fun drawableUri(")
        assertTrue(start > 0)
        assertTrue(f.text.substring(start, start + 400).contains("SystemIcons.resolve("))
    }

    // ------------------------------------------------------------------ 3. the twin files

    private fun drawableFiles(prefix: String): List<File> =
        File(IconSources.resDir, "drawable").listFiles().orEmpty().filter { it.name.startsWith(prefix) && it.extension == "xml" }

    @Test
    fun `every cx twin file is in the iOS table and every table twin has a file`() {
        val files = drawableFiles("cx_").map { it.nameWithoutExtension }.filterNot { it.startsWith("cx_legacy_") }.toSet()
        val tabled = IosIconTable.twins.values.map { IconSources.drawableNames.getValue(it) }.toSet()
        assertEquals("cx_ files that no table entry uses (delete them) or entries without a file", tabled, files)
        // ios_bar_* are the player bar's own fixed glyphs (see IconSources.exempt), not the old family twins
        val oldTwins = drawableFiles("ios_").filterNot { it.name.startsWith("ios_bar_") }
        assertTrue("old ios_ twins are back: ${oldTwins.map { it.name }}", oldTwins.isEmpty())
    }

    @Test
    fun `the legacy iOS twins are exactly the ten the owner asked to keep, each used and each a white 24dp shape`() {
        // owner, 2026-11-07: the bottom bar tabs and the previous / next buttons keep their previous iOS look
        val expected =
            setOf(
                "home_outlined", "home_filled", "search", "group_outlined", "group_filled",
                "library_music_outlined", "library_music_filled", "person", "skip_next", "skip_previous",
            )
        val ids = IosLegacyTwins.twins.keys.map { IconSources.drawableNames.getValue(it) }.toSet()
        assertEquals("ids with a legacy twin", expected, ids)
        val files = drawableFiles("cx_legacy_").map { it.nameWithoutExtension }.toSet()
        val used = IosLegacyTwins.twins.values.map { IconSources.drawableNames.getValue(it) }.toSet()
        assertEquals("cx_legacy_ files that nothing uses, or twins without a file", used, files)
        for (f in drawableFiles("cx_legacy_")) {
            val t = f.readText()
            assertTrue("${f.name} is not 24dp", t.contains("android:width=\"24dp\"") && t.contains("android:height=\"24dp\""))
            val colours = Regex("""(fill|stroke)Color="(#[0-9A-Fa-f]{8})"""").findAll(t).map { it.groupValues[2].uppercase() }.toSet()
            assertTrue("${f.name} has colours other than white / clear: $colours", colours.all { it == "#FFFFFFFF" || it == "#00000000" })
        }
        // the override really wins over the generated table, and every other id still uses the generated one
        for (id in IosLegacyTwins.twins.keys) assertEquals(IosLegacyTwins.twins.getValue(id), iosTwinFor(id))
        for ((id, twin) in IosIconTable.entries) if (id !in IosLegacyTwins.twins) assertEquals(twin, iosTwinFor(id))
    }

    @Test
    fun `every table id exists as a drawable`() {
        for ((id, twin) in IosIconTable.entries) {
            assertNotNull(IconSources.drawableNames[id])
            assertNotNull(IconSources.drawableNames[twin])
        }
        for (e in BulatIconTable.entries) assertNotNull(IconSources.drawableNames[e.resId])
    }

    @Test
    fun `the iOS family is one set - 512 grid, white, round pen`() {
        // the cx_legacy_ twins (previous look of the bottom bar and the skip buttons) are checked in their own test
        for (f in drawableFiles("cx_").filterNot { it.name.startsWith("cx_legacy_") }) {
            val t = f.readText()
            assertTrue("${f.name} is not on the 512 grid", t.contains("android:viewportWidth=\"512\"") && t.contains("android:viewportHeight=\"512\""))
            assertTrue("${f.name} is not 24dp", t.contains("android:width=\"24dp\"") && t.contains("android:height=\"24dp\""))
            val colours = Regex("""(fill|stroke)Color="(#[0-9A-Fa-f]{8})"""").findAll(t).map { it.groupValues[2].uppercase() }.toSet()
            assertTrue("${f.name} has colours other than white / clear: $colours", colours.all { it == "#FFFFFFFF" || it == "#00000000" })
            val widths = Regex("""strokeWidth="([\d.]+)"""").findAll(t).map { it.groupValues[1].toFloat() }.toSet()
            assertTrue("${f.name} pen ${widths}: the family pen is 32, bold 44 to 48", widths.all { it in setOf(32f, 44f, 46f, 48f) })
            if (widths.isNotEmpty()) {
                assertTrue("${f.name} has a stroke without round caps", !t.contains("strokeLineCap=\"butt\"") && !t.contains("strokeLineCap=\"square\""))
                assertTrue("${f.name} has a stroke without round joins", !t.contains("strokeLineJoin=\"miter\"") && !t.contains("strokeLineJoin=\"bevel\""))
            }
        }
    }

    // ------------------------------------------------------------------ 4. glyphs are not reused for different jobs

    private fun content(name: String): String {
        val f = File(IconSources.resDir, "drawable/$name.xml")
        return if (f.isFile) f.readText().replace(Regex("""\s+"""), " ") else "no file: $name"
    }

    private fun duplicateGroups(ofName: (String) -> String): Set<Set<String>> =
        usedIcons().keys.groupBy { ofName(it) }.values.filter { it.size > 1 }.map { it.toSet() }.toSet()

    @Test
    fun `Garis - two icons share a base drawable only inside the agreed groups`() {
        val allowed =
            setOf(
                // the same picture on purpose: one idea, two names (a tick; the outline form of a pair that has a filled one)
                setOf("check", "done"),
                setOf("group", "group_outlined"),
                setOf("library_music", "library_music_outlined"),
                setOf("language_korean_latin", "translate"),
                setOf("favorite", "ic_heart"),
                setOf("favorite_border", "ic_heart_outline"),
            )
        val groups = duplicateGroups { content(it) }
        assertEquals("identical base drawables", allowed, groups)
    }

    @Test
    fun `iOS - two ids share a twin only inside the agreed groups`() {
        val allowed =
            setOf(
                setOf("check", "done"),
                setOf("group", "group_outlined"),
                setOf("library_music", "library_music_outlined"),
                setOf("notification", "subscribe"),
                setOf("translate", "language_korean_latin"),
                setOf("tune", "sliders"),
                setOf("favorite", "ic_heart"),
                setOf("favorite_border", "ic_heart_outline"),
                // the Dynamic Island draws the same transport glyphs as the player
                setOf("play", "ic_island_play"),
                setOf("pause", "ic_island_pause"),
                setOf("skip_next", "ic_island_next"),
                setOf("skip_previous", "ic_island_previous"),
            )
        val groups =
            IosIconTable.entries.groupBy({ it.second }, { IconSources.drawableNames.getValue(it.first) })
                .values.filter { it.size > 1 }.map { it.toSet() }.toSet()
        assertEquals(allowed, groups)
    }

    @Test
    fun `share and export are two different glyphs in every family`() {
        val share = idOf("share")
        val export = idOf("export_playlist")
        assertTrue(IosIconTable.twinFor(share) != IosIconTable.twinFor(export))
        assertTrue(BulatIconTable.vectorFor(share)!!.name != BulatIconTable.vectorFor(export)!!.name)
        assertTrue(content("share") != content("export_playlist"))
    }

    @Test
    fun `state pairs look different in every family`() {
        val pairs =
            listOf(
                "favorite_border" to "favorite", "home_outlined" to "home_filled",
                "library_music_outlined" to "library_music_filled", "group_outlined" to "group_filled",
                "shuffle" to "shuffle_on", "repeat" to "repeat_on", "repeat_one" to "repeat_one_on",
                "subscribe" to "subscribed", "radio_button_unchecked" to "radio_button_checked",
                "ic_heart_outline" to "ic_heart", "cast" to "cast_connected", "library_add" to "library_add_check",
                "play" to "pause", "volume_down" to "volume_up",
            )
        for ((a, b) in pairs) {
            val ia = idOf(a)
            val ib = idOf(b)
            assertTrue("iOS: $a and $b share a twin", iosTwinFor(ia) != iosTwinFor(ib))
            assertTrue("Bulat: $a and $b share a vector", BulatIconTable.vectorFor(ia)!!.name != BulatIconTable.vectorFor(ib)!!.name)
            assertTrue("Garis: $a and $b are the same drawable", content(a) != content(b))
        }
    }

    // ------------------------------------------------------------------ 5. Garis is one pen

    /** Filled transport glyphs of the Dynamic Island: shapes, not strokes, by design (the outline switch has the stroked twins). */
    private val garisFilledShapes =
        setOf("ic_island_previous", "ic_island_play", "ic_island_pause", "ic_island_next")

    @Test
    fun `Garis - every icon is a 1_75 round stroke or a listed filled shape`() {
        val bad = mutableListOf<String>()
        for (name in usedIcons().keys) {
            if (name in garisFilledShapes) continue
            val t = File(IconSources.resDir, "drawable/$name.xml").takeIf { it.isFile }?.readText() ?: continue
            val widths = Regex("""strokeWidth="([\d.]+)"""").findAll(t).map { it.groupValues[1] }.toSet()
            val caps = Regex("""strokeLineCap="(\w+)"""").findAll(t).map { it.groupValues[1] }.toSet()
            val ok = widths == setOf("1.75") && caps.all { it == "round" }
            if (!ok) bad += "$name (widths $widths, caps $caps)"
        }
        assertTrue("base drawables that are not the Garis pen (1.75, round): $bad", bad.isEmpty())
    }

    @Test
    fun `the new ids exist`() {
        assertNotNull(R.drawable.export_playlist)
    }
}
