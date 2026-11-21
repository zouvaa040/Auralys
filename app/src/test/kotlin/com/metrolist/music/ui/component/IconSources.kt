/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import com.metrolist.music.R
import java.io.File

/**
 * Source scan shared by [IconCoverageTest] and [IconContactSheetTest]: which drawables the Kotlin sources draw, which
 * files are allowed to hold the family tables. JVM only, reads the module sources (the module folder is the working
 * directory of unit tests).
 */
object IconSources {
    private val srcDir: File =
        listOf("src", "app/src").map { File(it) }.firstOrNull { File(it, "main/kotlin").isDirectory }
            ?: error("sources not found from ${File(".").absolutePath}")

    val mainKotlin: File = File(srcDir, "main/kotlin/com/metrolist/music")
    val resDir: File = File(srcDir, "main/res")

    /** `foss`, `gms` and `izzy` flavours hold their own copy of the cast button. */
    private val kotlinRoots: List<File> =
        listOf("main", "foss", "gms", "izzy").map { File(srcDir, "$it/kotlin") }.filter { it.isDirectory }

    /** The tables, the wrapper and the system-surface twins: they name drawables on purpose and are not call sites. */
    val tableFiles: Set<String> = setOf("IosIcons.kt", "IosIconTable.kt", "IosLegacyTwins.kt", "BulatIconTable.kt", "BulatIcons.kt", "SystemIcons.kt")

    class Source(val path: String, val name: String, val text: String) {
        val lines: List<String> by lazy { text.lines() }
    }

    val files: List<Source> by lazy {
        kotlinRoots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.map {
                Source(it.relativeTo(srcDir).path.replace('\\', '/'), it.name, it.readText())
            }.toList()
        }
    }

    /** A comment line: not code. */
    fun isComment(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }

    /** Drawable name -> id, from the generated R class. */
    val drawableIds: Map<String, Int> by lazy {
        R.drawable::class.java.fields
            .filter { it.type == Int::class.javaPrimitiveType }
            .associate { it.name to it.getInt(null) }
    }

    val drawableNames: Map<Int, String> by lazy { drawableIds.entries.associate { it.value to it.key } }

    private val drawableRef = Regex("""\bR\.drawable\.(\w+)""")

    /** Every `R.drawable.X` in code outside the table files: name -> list of "path:line". */
    val drawableUses: Map<String, List<String>> by lazy {
        val out = linkedMapOf<String, MutableList<String>>()
        for (f in files) {
            if (f.name in tableFiles) continue
            f.lines.forEachIndexed { i, line ->
                if (isComment(line)) return@forEachIndexed
                for (m in drawableRef.findAll(line)) out.getOrPut(m.groupValues[1]) { mutableListOf() }.add("${f.path}:${i + 1}")
            }
        }
        out
    }

    /**
     * Drawables that are pictures, not pictograms of the family, each with its reason. A new entry needs a real reason:
     * "I have no twin yet" is not one (the ratchet in [IconCoverageTest] exists to fail on exactly that).
     */
    val exempt: Map<String, String> =
        linkedMapOf(
            "app_logo" to "the app's own logo (brand art, not a pictogram)",
            "small_icon" to "the app glyph: notification small icon and logo, drawn white by the system",
            "qris" to "payment QR code, a picture that must stay scannable",
            "dapz_avatar" to "the author's avatar photo",
            "telegram" to "third-party brand mark; its shape is the brand's",
            "instagram" to "third-party brand mark; its shape is the brand's",
            "tiktok" to "third-party brand mark; its shape is the brand's",
            "github" to "third-party brand mark; its shape is the brand's",
            "discord" to "third-party brand mark; its shape is the brand's",
            "spotify" to "third-party brand mark; its shape is the brand's",
            "youtube_music" to "third-party brand mark; its shape is the brand's",
            "buymeacoffee" to "third-party brand mark; its shape is the brand's",
            "ic_widget_mic" to "status-bar small icon of the recognition notification: it has to stay a solid silhouette",
            // the player's bottom bar (lyrics, queue, share, sleep) is drawn in one fixed iOS pen by design (Queue.kt), whatever the family is
            "ios_bar_lyrics" to "player bar glyph, a fixed iOS pen on purpose (see Queue.kt), not a family icon",
            "ios_bar_lyrics_fill" to "player bar glyph, a fixed iOS pen on purpose (see Queue.kt), not a family icon",
            "ios_bar_queue" to "player bar glyph, a fixed iOS pen on purpose (see Queue.kt), not a family icon",
            "ios_bar_share" to "player bar glyph, a fixed iOS pen on purpose (see Queue.kt), not a family icon",
            "ios_bar_sleep" to "player bar glyph, a fixed iOS pen on purpose (see Queue.kt), not a family icon",
            "ios_bar_sleep_fill" to "player bar glyph, a fixed iOS pen on purpose (see Queue.kt), not a family icon",
        )

    /** The widget layouts keep `aw_*` as the first frame; the renderer swaps in the family's glyph (SystemIcons.resolve). */
    fun isWidgetGlyph(name: String): Boolean = name.startsWith("aw_")

    fun isFamilyIcon(name: String): Boolean = name !in exempt && !isWidgetGlyph(name)
}
