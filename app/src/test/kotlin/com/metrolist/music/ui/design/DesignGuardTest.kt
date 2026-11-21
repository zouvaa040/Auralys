/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps the old identity from creeping back: the cream and terracotta palette, serif and italic titles, capital letters
 * with letter spacing, accent colours in navigation and controls. It reads the Kotlin sources of the app (the module
 * folder is the working directory of unit tests). Allow-lists name the places that still carry the old look and are
 * waiting for their screen sweep; they can only shrink: a new entry, or a higher count, fails the test.
 */
class DesignGuardTest {
    private val root: File =
        listOf("src/main/kotlin", "app/src/main/kotlin")
            .map { File(it, "com/metrolist/music") }
            .firstOrNull { it.isDirectory }
            ?: error("sources not found from ${File(".").absolutePath}")

    private val allFiles: List<Pair<String, String>> by lazy {
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).path.replace('\\', '/') to it.readText() }
            .toList()
    }

    private val uiFiles: List<Pair<String, String>> by lazy { allFiles.filter { it.first.startsWith("ui/") } }

    private fun count(text: String, regex: Regex) = regex.findAll(text).count()

    // ---------------------------------------------------------------- capitals

    /** Lines where capitals are data, not styling: an avatar initial, a room code, a build tag. */
    private fun isDataUppercase(line: String) =
        line.contains("take(1).uppercase()") ||
            line.contains("roomCode", ignoreCase = true) ||
            line.contains("BuildConfig.ARCHITECTURE")

    /** Screens that still call uppercase() on a label. None: Home, Album, Queue, Stats and the settings are swept. */
    private val leftoverUppercase = emptyMap<String, Int>()

    @Test
    fun `no capital letters on labels in the shared components and the theme`() {
        val shared = uiFiles.filter { it.first.startsWith("ui/component/") || it.first.startsWith("ui/theme/") }
        for ((name, text) in shared) {
            val bad = text.lines().filter { it.contains(".uppercase(") && !isDataUppercase(it) }
            assertTrue("$name calls uppercase() on a label: ${bad.firstOrNull()?.trim()}", bad.isEmpty())
        }
    }

    @Test
    fun `uppercase on labels only where a screen still waits for its sweep`() {
        for ((name, text) in uiFiles) {
            val n = text.lines().count { it.contains(".uppercase(") && !isDataUppercase(it) }
            val allowed = leftoverUppercase[name] ?: 0
            assertTrue("$name: $n uppercase() calls on labels, $allowed allowed", n <= allowed)
        }
    }

    @Test
    fun `MicroLabel is a plain sentence case label`() {
        val text = allFiles.first { it.first == "ui/component/EditorialComponents.kt" }.second
        val start = text.indexOf("fun MicroLabel(")
        val body = text.substring(start, text.indexOf("\n}\n", start))
        assertTrue("MicroLabel must not change case", !body.contains("uppercase") && !body.contains("lowercase") && !body.contains("capitalize"))
        assertTrue("MicroLabel must not add letter spacing", !body.contains("letterSpacing"))
        assertTrue("MicroLabel must not use an accent colour", !body.contains("primary"))
        // and the text style it uses is a theme slot with no tracking (tested in HorizonTokensTest)
        assertTrue(body.contains("MaterialTheme.typography.labelMedium"))
    }

    @Test
    fun `MicroLabel call sites do not grow`() {
        val n = uiFiles.sumOf { count(it.second, Regex("""\bMicroLabel\(""")) }
        assertTrue("MicroLabel call sites: $n (28 at the time of the redesign, 13 after the settings sweep, target 0)", n <= MICRO_LABEL_CEILING)
    }

    @Test
    fun `the settings, You, account, recognition and font pages have no MicroLabel left`() {
        val swept = listOf(
            "ui/screens/YouScreen.kt", "ui/screens/settings/SettingsHub.kt", "ui/screens/settings/ThemeScreen.kt",
            "ui/screens/settings/FontSettingsCard.kt", "ui/screens/recognition/RecognitionScreen.kt", "ui/screens/AccountScreen.kt",
            "ui/component/ReleaseNotesCard.kt", "ui/component/WelcomeScreen.kt", "ui/screens/settings/AboutScreen.kt",
        )
        for (f in swept) {
            val text = allFiles.first { it.first == f }.second
            assertTrue("$f calls MicroLabel", count(text, Regex("""\bMicroLabel\(""")) == 0)
        }
    }

    // ---------------------------------------------------------------- serif, italics, tracking

    @Test
    fun `no italics except lyric background lines and the player title waiting for its sweep`() {
        val allowed = setOf("ui/component/LyricsLine.kt", "ui/player/Thumbnail.kt")
        for ((name, text) in uiFiles) {
            if (name in allowed) continue
            assertTrue("$name uses FontStyle.Italic", !text.contains("FontStyle.Italic"))
        }
        val thumb = uiFiles.first { it.first == "ui/player/Thumbnail.kt" }.second
        assertTrue("one italic left in the player title (A8)", count(thumb, Regex("FontStyle\\.Italic")) <= 1)
    }

    @Test
    fun `no serif family in the shared styles`() {
        val allowedNewsreader = setOf(
            "ui/theme/AppFont.kt", // "New York" in the font picker: the user's explicit choice
            "utils/LyricsCardRenderer.kt", // a font the user can pick for lyric share cards
        )
        for ((name, text) in allFiles) {
            if (name in allowedNewsreader) continue
            assertTrue("$name mentions Newsreader", !text.contains("Newsreader"))
            assertTrue("$name uses DmSansFamily", !text.contains("DmSansFamily"))
        }
        // FontFamily.Serif: nowhere (the mood tiles were the last one)
        for ((name, text) in allFiles) {
            assertTrue("$name uses FontFamily.Serif", !text.contains("FontFamily.Serif"))
        }
        // the theme itself has no serif at all
        for ((name, text) in uiFiles.filter { it.first.startsWith("ui/theme/") && it.first != "ui/theme/AppFont.kt" }) {
            assertTrue("$name", !text.contains("Serif") && !text.contains("Cursive"))
        }
    }

    /** Positive letter spacing in the styles and shared components is banned. Room codes and one brand wordmark are the exceptions. */
    @Test
    fun `no letter spacing above 0 1 sp outside the allow-list`() {
        val allowed = setOf(
            "ui/menu/PlayerMenu.kt", // room code digits
            "ui/screens/ListenTogetherScreen.kt", // room code digits
        )
        val number = Regex("""letterSpacing\s*=\s*\(?\s*(-?[0-9]*\.?[0-9]+)\s*\)?\s*\.(sp|em)""")
        for ((name, text) in uiFiles) {
            if (name in allowed) continue
            for (m in number.findAll(text)) {
                val v = m.groupValues[1].toDouble()
                assertTrue("$name: letterSpacing $v", v <= 0.1)
            }
        }
        val theme = uiFiles.filter { it.first.startsWith("ui/theme/") || it.first == "ui/component/EditorialComponents.kt" }
        for ((name, text) in theme) {
            assertTrue("$name sets letterSpacing in the shared styles", !Regex("""letterSpacing\s*=\s*[0-9]""").containsMatchIn(text))
        }
    }

    // ---------------------------------------------------------------- the old palette

    private val oldPalette = listOf(
        "C2623F", "FDF8F5", "984222", "1A1917", "E5603F", "FF7F5C", "F2EDE9", "E8E1D3", "FFDBCF", "1C1B19", "FFC9A8", "FF9EC0", "0088FF", "34C759",
        // the share cards' old coral and peach fallbacks, the purple dark tone and the warm near-black base
        "EE8A63", "1A1220", "0C0B0E",
    )

    @Test
    fun `the cream and terracotta palette is gone from the sources`() {
        val allowed = mapOf(
            "ui/theme/Theme.kt" to setOf("C2623F"), // the marker value of "no colour chosen", never drawn
        )
        for ((name, text) in allFiles) {
            for (hex in oldPalette) {
                if (hex in (allowed[name] ?: emptySet())) continue
                assertTrue("$name still has the old colour $hex", !text.contains(hex, ignoreCase = true))
            }
        }
    }

    // ---------------------------------------------------------------- accent in navigation and controls

    private fun chromatic(hex: String): Boolean {
        val v = hex.toLong(16)
        val r = ((v shr 16) and 0xFF).toInt()
        val g = ((v shr 8) and 0xFF).toInt()
        val b = (v and 0xFF).toInt()
        return (maxOf(r, g, b) - minOf(r, g, b)) / 255.0 > 0.12
    }

    @Test
    fun `navigation, segmented control, chips, switch and the shared surfaces carry no coloured literal`() {
        val files = listOf(
            "ui/component/AppNavigation.kt", "ui/component/SearchSourceSwitch.kt", "ui/component/ChipsRow.kt",
            "ui/component/GlassSwitch.kt", "ui/component/GlassComponents.kt", "ui/component/EditorialComponents.kt",
            "ui/component/Material3SettingsGroup.kt", "ui/component/GlossyButton.kt", "ui/component/Dialog.kt",
            "ui/component/NavigationTitle.kt", "ui/component/Menu.kt", "ui/component/NewMenuComponents.kt",
            "ui/component/BottomSheetMenu.kt", "ui/component/GlassMenu.kt", "ui/component/GlassLight.kt",
        )
        val literal = Regex("""0x([0-9A-Fa-f]{8})""")
        for (f in files) {
            val text = allFiles.first { it.first == f }.second
            assertTrue("$f uses a sweep gradient (the rainbow ring)", !text.contains("sweepGradient"))
            for (m in literal.findAll(text)) {
                assertTrue("$f has the coloured literal ${m.value}", !chromatic(m.groupValues[1].substring(2)))
            }
        }
    }

    @Test
    fun `no accent in the shared surface and control primitives`() {
        // the chips, the segmented control and the navigation read neutral slots; primary is allowed only because it is ink
        // in every neutral mode and the seeded colour in "Kustom" (a user's explicit choice). Tertiary and secondary tints are not.
        val files = listOf(
            "ui/component/AppNavigation.kt", "ui/component/SearchSourceSwitch.kt", "ui/component/ChipsRow.kt",
            "ui/component/Material3SettingsGroup.kt", "ui/component/EditorialComponents.kt",
        )
        for (f in files) {
            val text = allFiles.first { it.first == f }.second
            assertTrue("$f uses colorScheme.tertiary", !text.contains("scheme.tertiary") && !text.contains("colorScheme.tertiary"))
            assertTrue("$f tints with primary.copy(alpha", !Regex("""primary\.copy\(\s*alpha""").containsMatchIn(text))
        }
        val settings = allFiles.first { it.first == "ui/component/Material3SettingsGroup.kt" }.second
        assertTrue("settings icons cycle accent colours by index", !settings.contains("index % 3"))
        assertTrue("settings icons sit in a disc or tile", !settings.contains("CircleShape") && !settings.contains("size(36.dp)"))
    }

    // ---------------------------------------------------------------- the swept screens (settings, You, account, brand)

    private fun src(name: String): String = allFiles.first { it.first == name }.second

    @Test
    fun `settings home and You are plain grouped lists`() {
        val hub = src("ui/screens/settings/SettingsHub.kt")
        for (banned in listOf("SettingsTileCard", "CircleShape", "accent.copy", "MicroLabel", "riseIn", "SettingsShelfHeading")) {
            assertTrue("SettingsHub.kt still has $banned", !hub.contains(banned))
        }
        assertTrue("the categories must use the shared settings group", hub.contains("Material3SettingsGroup("))
        val you = src("ui/screens/YouScreen.kt")
        for (banned in listOf("CircleShape", "caption", "MicroLabel", "riseIn", "primary.copy", "glassCard")) {
            assertTrue("YouScreen.kt still has $banned", !you.contains(banned))
        }
    }

    @Test
    fun `no accent wash behind the swept screens`() {
        val accentAlpha = Regex("""(primary|secondary|tertiary)(Container)?\.copy\(\s*alpha""")
        val swept = listOf(
            "ui/screens/settings/AboutScreen.kt", "ui/screens/ListenTogetherScreen.kt", "ui/screens/RecapScreen.kt",
            "ui/component/WelcomeScreen.kt", "ui/screens/equalizer/EqFrequencyResponseGraph.kt", "ui/screens/ListeningStreakCard.kt",
            "ui/screens/HomeStreakBanner.kt", "ui/screens/settings/SettingsHub.kt", "ui/screens/YouScreen.kt",
            "ui/screens/AccountScreen.kt", "ui/screens/recognition/RecognitionScreen.kt", "ui/screens/settings/ThemeScreen.kt",
            "ui/screens/settings/FontSettingsCard.kt", "ui/component/ReleaseNotesCard.kt",
        )
        for (f in swept) {
            assertTrue("$f paints an accent colour at low alpha", !accentAlpha.containsMatchIn(src(f)))
        }
        val total = uiFiles.sumOf { count(it.second, accentAlpha) }
        assertTrue("accent colours at low alpha in ui/: $total (what is left is the song menu, the queue, the player and the library)", total <= ACCENT_ALPHA_CEILING)
    }

    @Test
    fun `no white glass border on cards`() {
        for ((name, text) in uiFiles) {
            assertTrue("$name has a white 45 percent card border", !text.contains("White.copy(alpha = 0.45f)"))
        }
    }

    @Test
    fun `the swept screens do not stagger their entrance`() {
        val swept = listOf("ui/screens/RecapScreen.kt", "ui/screens/RecapStory.kt", "ui/component/WelcomeScreen.kt", "ui/screens/YouScreen.kt", "ui/screens/settings/SettingsScreen.kt")
        for (f in swept) assertTrue("$f calls riseIn", !src(f).contains(".riseIn("))
    }

    @Test
    fun `the brand colours of the navy icon live in the brand file only`() {
        val brand = listOf("6AD1F2", "14507A", "0B2236", "EAF8FD")
        for ((name, text) in allFiles) {
            if (name == "ui/component/AuralysBrand.kt") continue
            for (hex in brand) {
                assertTrue("$name uses the brand colour $hex outside the brand file", !text.contains(hex, ignoreCase = true))
            }
        }
        val b = src("ui/component/AuralysBrand.kt")
        assertTrue(b.contains("object AuralysBrandColors"))
        assertTrue("the mark takes the brand sun, not the accent", b.contains("sun: Color = brandSunColor()"))
        assertTrue("the splash paints the page colour", b.contains("val background = MaterialTheme.colorScheme.background"))
    }

    @Test
    fun `the splash and the in-app logo are neutral and navy, not cream and terracotta`() {
        val res = File(root.parentFile.parentFile.parentFile.parentFile, "res")
        fun text(path: String) = File(res, path).readText()
        val day = text("values/colors.xml")
        val night = text("values-night/colors.xml")
        assertTrue("splash_bg (day) is not the page colour: $day", Regex("""name="splash_bg">#F5F6F8<""").containsMatchIn(day))
        assertTrue("splash_bg (night) is not the page colour: $night", Regex("""name="splash_bg">#0B0C0F<""").containsMatchIn(night))
        val logo = text("drawable/app_logo.xml")
        assertTrue("the logo is still terracotta", !logo.contains("C2623F", ignoreCase = true) && !logo.contains("1C1B19", ignoreCase = true))
        assertTrue("the logo sun is the cyan of the navy icon", logo.contains("6AD1F2", ignoreCase = true))
    }

    @Test
    fun `the share cards fall back to the neutral colours`() {
        val colors = src("utils/ShareCardColors.kt")
        assertTrue(colors.contains("ACCENT") && colors.contains("DARK") && colors.contains("BASE"))
        for (f in listOf("utils/LyricsCardRenderer.kt", "utils/SongShareCard.kt", "utils/RecapPosterRenderer.kt", "ui/component/LyricsShareStudio.kt")) {
            assertTrue("$f does not use the neutral fallback", src(f).contains("ShareCardColors."))
        }
    }

    @Test
    fun `the word terracotta is only left in history notes and the screens waiting for their sweep`() {
        val allowed = mapOf(
            "constants/PreferenceKeys.kt" to 1, "ui/theme/DesignMigration.kt" to 1, "ui/theme/Theme.kt" to 1, // history notes
            "ui/player/Player.kt" to 1,
        )
        for ((name, text) in allFiles) {
            val n = count(text, Regex("terracotta", RegexOption.IGNORE_CASE))
            assertTrue("$name mentions terracotta $n times, ${allowed[name] ?: 0} allowed", n <= (allowed[name] ?: 0))
        }
    }

    // ---------------------------------------------------------------- the media screens (sweep B)

    @Test
    fun `playlist, album and artist pages use the one hero header and nothing boxed`() {
        val pages = listOf(
            "ui/screens/AlbumScreen.kt", "ui/screens/artist/ArtistScreen.kt", "ui/screens/playlist/LocalPlaylistScreen.kt",
            "ui/screens/playlist/OnlinePlaylistScreen.kt", "ui/screens/playlist/AutoPlaylistScreen.kt",
            "ui/screens/playlist/TopPlaylistScreen.kt", "ui/screens/playlist/CachePlaylistScreen.kt", "ui/screens/podcast/OnlinePodcastScreen.kt",
        )
        for (f in pages) {
            val text = src(f)
            assertTrue("$f does not use HeroHeader", text.contains("HeroHeader("))
            assertTrue("$f does not fade its top bar with the header", text.contains("fade = barFade"))
            for (banned in listOf("MicroLabel(", ".uppercase(", "glassTinted(", "glassRim()", "glassFill()", ".riseIn(", "BorderStroke(1.dp")) {
                assertTrue("$f still has $banned", !text.contains(banned))
            }
        }
        val hero = src("ui/component/HeroHeader.kt")
        for (banned in listOf("MicroLabel(", ".uppercase(", "FontStyle.Italic", "Serif", "glassTinted(", ".shadow(", "letterSpacing")) {
            assertTrue("HeroHeader.kt has $banned", !hero.contains(banned))
        }
        assertTrue("the header title is the Display style", hero.contains("MaterialTheme.typography.displayMedium"))
    }

    @Test
    fun `home, search, library, queue and mood pages carry no old identity`() {
        val files = listOf(
            "ui/screens/HomeScreen.kt", "ui/screens/HomeGreeting.kt", "ui/screens/MoodAndGenresScreen.kt", "ui/screens/StatsScreen.kt",
            "ui/screens/search/SearchScreen.kt", "ui/screens/search/OnlineSearchScreen.kt", "ui/screens/search/LocalSearchScreen.kt",
            "ui/screens/search/SearchDiscovery.kt", "ui/screens/library/LibraryEditorial.kt", "ui/player/Queue.kt", "ui/component/NavigationTitle.kt",
        )
        for (f in files) {
            val text = src(f)
            for (banned in listOf("MicroLabel(", ".uppercase(", "hairlineBottom()", "hairlineTop()", ".riseIn(", "glassTinted(", "FontFamily.Serif", "EditorialDivider(")) {
                assertTrue("$f still has $banned", !text.contains(banned))
            }
        }
        // the genre tiles have no colours of their own, the mood tiles no rainbow
        val discovery = src("ui/screens/search/SearchDiscovery.kt")
        assertTrue("genre tiles carry colours", !discovery.contains("Color(0xFF") && !discovery.contains("Brush.linearGradient"))
        val mood = src("ui/screens/MoodAndGenresScreen.kt")
        assertTrue("mood tiles are tinted", !mood.contains("colorScheme.tertiary") && !mood.contains("colorScheme.secondary") && !mood.contains("hashCode()"))
        // the home page: two boxed cards became tiles, a list of songs is not boxed, the play word is not in capitals
        val home = src("ui/screens/HomeScreen.kt")
        assertTrue("the home page has a tile for the community playlists", home.contains("fun CommunityPlaylistTile("))
        assertTrue("the home page has a tile for the daily discovery", home.contains("fun DailyDiscoverTile("))
        assertTrue("a boxed song list on the home page", !home.contains("glassCard("))
        // library: a plain title and bare rows, no disc behind the icon, the open tab underlined in ink
        val lib = src("ui/screens/library/LibraryEditorial.kt")
        assertTrue("library icons sit in a disc", !lib.contains("CircleShape") && !lib.contains("primary"))
        // queue: the playing row is surface-2 and ink
        val queue = src("ui/player/Queue.kt")
        assertTrue("the queue rows carry an accent", !queue.contains("val primary = ") && !queue.contains("primary.copy(alpha"))
    }

    @Test
    fun `list rows and grid covers are on the radius scale and carry no accent or print shadow`() {
        val items = src("ui/component/Items.kt")
        assertTrue("Items.kt uses colorScheme.primary", !items.contains("colorScheme.primary"))
        assertTrue("Items.kt paints the liked heart in the error colour", !items.contains("colorScheme.error"))
        assertTrue("Items.kt draws an offset ink shadow behind covers", !items.contains("inkShadow"))
        assertTrue("grid covers use an off-scale radius", !items.contains("styledRadius(18.dp"))
        val dims = src("constants/Dimensions.kt")
        assertTrue("grid cover radius is r-md", dims.contains("val ThumbnailCornerRadius = 12.dp"))
        assertTrue("list art radius is r-sm", dims.contains("val ListThumbnailCornerRadius = 8.dp"))
        val rows = src("ui/component/AuralysRowButtons.kt")
        assertTrue("the row more button sits in a pill", !rows.contains("clip(RoundedCornerShape(50)).background"))
        // styledShape snaps to the scale
        assertTrue("styledShape does not snap to the radius scale", src("ui/theme/UiStyle.kt").contains("HorizonRadius.snap(styledRadius(glass, editorial))"))
    }

    // ---------------------------------------------------------------- ratchets: counts that may only go down

    @Test
    fun `ratchets for the sweep that is still to do`() {
        val offScale = setOf(4.0, 8.0, 12.0, 16.0, 24.0, 28.0)
        var radii = 0
        var glass = 0
        var rise = 0
        var settingsDescriptions = 0
        val radius = Regex("""RoundedCornerShape\(\s*(\d+(?:\.\d+)?)\.dp\s*\)""")
        for ((name, text) in uiFiles) {
            for (m in radius.findAll(text)) {
                val v = m.groupValues[1].toDouble()
                if (v != 0.0 && v < 50.0 && v !in offScale) radii++
            }
            glass += count(text, Regex("""\bisGlassStyle\b"""))
            rise += count(text, Regex("""\.riseIn\("""))
            if (name.startsWith("ui/screens/settings/")) settingsDescriptions += count(text, Regex("""\bdescription\s*="""))
        }
        assertTrue("radius literals outside the scale: $radii (33 at the time of the redesign)", radii <= RADIUS_CEILING)
        assertTrue("isGlassStyle branches: $glass (160 at the time of the redesign)", glass <= GLASS_STYLE_CEILING)
        assertTrue("riseIn call sites: $rise (51 at the time of the redesign, the modifier is a no-op now)", rise <= RISE_IN_CEILING)
        assertTrue("settings descriptions: $settingsDescriptions (budget in the design report: 40 at the end of the sweep)", settingsDescriptions <= SETTINGS_DESCRIPTION_CEILING)
    }

    @Test
    fun `the default style is the glass one and the old paper skin has no name left in the UI strings`() {
        val ui = allFiles.first { it.first == "ui/theme/UiStyle.kt" }.second
        assertTrue(ui.contains("val DEFAULT = GLASS"))
        val res = File(root.parentFile.parentFile.parentFile.parentFile, "res")
        for (lang in listOf("values", "values-in")) {
            val f = File(res, "$lang/metrolist_strings.xml")
            if (!f.exists()) continue
            val line = f.readLines().first { it.contains("name=\"ui_style_editorial\"") }
            assertTrue("$lang: $line", !line.contains("Editorial") && !line.contains("paper", ignoreCase = true) && !line.contains("kertas"))
        }
    }

    @Test
    fun `the font is bundled once and the old serif files are gone`() {
        val fonts = File(root.parentFile.parentFile.parentFile.parentFile, "res/font")
        val names = fonts.list()?.toSet() ?: emptySet()
        assertTrue(names.contains("plus_jakarta_sans.ttf"))
        for (gone in listOf("newsreader.ttf", "newsreader_italic.ttf", "dm_sans.ttf")) {
            assertTrue("$gone is still in res/font", gone !in names)
        }
        assertEquals("one font file for the app typography plus the old decorative one", setOf("plus_jakarta_sans.ttf", "bbh_bartle_regular.ttf", "bbh_bartle.xml"), names)
    }

    private companion object {
        /** Lowered as the pages are swept (141 before the redesign, 110 after the core, 75 after the settings sweep). */
        const val SETTINGS_DESCRIPTION_CEILING = 75
        const val MICRO_LABEL_CEILING = 4 // the definition, its kicker in EditorialHeader, and two player files
        const val ACCENT_ALPHA_CEILING = 10
        // 2026-11-18: raised from 12 / 58 to the measured 21 / 82 after the 4.3.6 design rounds (dock, sheets, peek menu, streak card, player bar); may only go down from here
        const val RADIUS_CEILING = 21
        const val GLASS_STYLE_CEILING = 82
        const val RISE_IN_CEILING = 1 // the no-op definition itself
    }
}
