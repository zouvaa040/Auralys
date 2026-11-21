/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

/**
 * On a phone java.util.regex is ICU, not the JDK engine these unit tests run on, and ICU refuses patterns the JDK takes.
 * 4.3.4 crashed at the first lyrics fetch on real phones: `\{[^}]*}` in LyricsSelection (a literal `}` without a
 * backslash) is fine on the JDK and a PatternSyntaxException in the class initialiser on Android, and no JVM test saw it.
 *
 * This test reads every regex the main sources build (`Regex("...")`, `"...".toRegex()`, `Pattern.compile("...")`, in
 * the app and the innertube module) and fails on the constructs a real Android runtime rejects. The rules come from
 * compiling probes and every pattern of the app with app_process on Android emulators (round 4.3.5):
 * - a `}` outside a character class that does not close a repetition `{n}`, `{n,}`, `{n,m}` (needs `\}`): refused;
 * - a `{` that does not open such a repetition (needs `\{`; the JDK refuses these too);
 * - `\pL` / `\PL` without braces and the inline flag `(?U)`: refused;
 * - a `]` outside a character class, or as the first member of a class: accepted by the ICU versions we tried, but a
 *   backslash costs nothing and protects against a stricter ICU, so the sources keep `\]`;
 * - `\p{...}` with a name outside the set verified on Android: general categories and `\p{script=...}`. The JDK
 *   spellings `\p{IsHan}`, `\p{IsLatin}`, `\p{IsL}`, `\p{IsAlphabetic}` work on Android 12 but throw
 *   U_ILLEGAL_ARGUMENT_ERROR on Android 8: `\p{IsHan}` in LyricsHitMatch broke every synced-lyrics provider there
 *   (4.3.4); `\p{script=Han}` means the same on both engines and every Android version tried.
 * Behaviour that differs without an error, and is fine for this app: on Android `\w`, `\d`, `\s` and `\b` are Unicode
 * (NBSP and ideographic space are `\s`, Arabic-Indic and full-width digits are `\d`); on the JDK they are ASCII.
 */
class AndroidRegexGuardTest {
    private val projectRoot: File =
        listOf(File("."), File(".."))
            .firstOrNull { File(it, "app/src/main/kotlin").isDirectory }
            ?: error("project not found from ${File(".").absolutePath}")

    private val sources: List<File> by lazy {
        listOf("app/src/main/kotlin", "innertube/src/main/kotlin")
            .map { File(projectRoot, it) }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
    }

    private val sites: List<RegexSites.Site> by lazy {
        val files = sources.associate { it.relativeTo(projectRoot).path.replace('\\', '/') to it.readText() }
        RegexSites.scan(files)
    }

    @Test
    fun `the scanner finds the regexes of the main sources`() {
        // 232 sites at the time of 4.3.5; a much lower number means the scanner broke, not that the regexes went away
        assertTrue("only ${sites.size} regex sites found", sites.size >= 150)
        assertTrue(
            "the lyrics tag pattern that crashed 4.3.4 must be among the sites",
            sites.any { it.file.endsWith("lyrics/LyricsSelection.kt") && it.pattern.startsWith("\\{[^}]*") },
        )
    }

    @Test
    fun `every regex of the main sources is safe for the ICU engine of Android`() {
        val bad =
            sites.flatMap { site ->
                IcuRegexRisk.problems(site.pattern).map { "${site.file}:${site.line}  ${site.pattern}  -> $it" }
            }
        assertTrue("regexes Android's ICU engine rejects:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `every regex of the main sources compiles`() {
        val bad =
            sites.filter { !it.hasUnknownParts }.mapNotNull { site ->
                runCatching { Pattern.compile(site.pattern) }.exceptionOrNull()?.let { "${site.file}:${site.line}  ${site.pattern}  -> ${it.message}" }
            }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `the checker flags what crashed on Android and accepts the fixed forms`() {
        // rejected by Android's ICU (seen on the emulators) or JDK-only
        for (p in listOf("\\{[^}]*}", "a}", "^}", "(a)}", "x{2}}", "\\p{IsHan}", "\\p{Han}", "a{,2}", "x{a", "[\\d]]", "^\\[\\d\\d](.*)$", "\\pL", "\\PL", "(?U)\\w", "(?iU)x")) {
            assertTrue("should flag $p", IcuRegexRisk.problems(p).isNotEmpty())
        }
        // fine on both engines
        for (p in listOf(
            "\\{[^}]*\\}", "\\{agent:([^}]+)\\}", "[)\\]}]", "[(\\[{]", "\\d{2,3}", "a{2,}", "x{2}", "\\p{L}", "\\P{L}",
            "\\p{Mn}+", "\\p{script=Han}", "(?i)abc", "(?i:ab)c", "(?<=x)y", "\\x{1F600}", "\\Q}]{\\E", "[^\\]]*", "\\]", "\\}", "[\\u0400-\\u04FF]",
            "(?<=[a-z0-9])(?=\\p{script=Han})", "^(?:\\[[^\\]]*\\]|\\{[^}]*\\})+",
        )) {
            assertEquals("should accept $p", emptyList<String>(), IcuRegexRisk.problems(p))
        }
    }

    @Test
    fun `the scanner reads Kotlin strings like the compiler`() {
        val src =
            """
            val A = "x"
            val r1 = Regex(""${'"'}\{[^}]*}""${'"'})
            val r2 = "\\[(\\d+)\\]".toRegex()
            val r3 = Regex("[${'$'}A]+", RegexOption.IGNORE_CASE)
            val c = '"' // a quote char must not open a string
            /* Regex("not code") */ val r4 = Pattern.compile("a" + "b")
            """.trimIndent()
        val found = RegexSites.scan(mapOf("T.kt" to src)).map { it.pattern }
        assertEquals(listOf("\\{[^}]*}", "\\[(\\d+)\\]", "[x]+", "ab"), found)
    }
}

/** Finds the regex patterns a set of Kotlin sources builds from string literals. */
internal object RegexSites {
    class Site(val file: String, val line: Int, val pattern: String, val hasUnknownParts: Boolean)

    private sealed interface Tok {
        val line: Int
    }

    private class Str(val parts: List<Any>, override val line: Int) : Tok // String = text, Expr = template

    private class Word(val text: String, override val line: Int) : Tok

    private class Mark(val ch: Char, override val line: Int) : Tok

    private class Expr(val code: String)

    fun scan(files: Map<String, String>): List<Site> {
        val lexed = files.mapValues { lex(it.value) }
        val constants = constants(lexed.values)
        val out = ArrayList<Site>()
        for ((name, toks) in lexed) {
            for (k in toks.indices) {
                val t = toks[k]
                if (t !is Word) continue
                val prevDot = (toks.getOrNull(k - 1) as? Mark)?.ch == '.'
                val parts: List<Any>? =
                    when {
                        t.text == "Regex" && !prevDot && (toks.getOrNull(k + 1) as? Mark)?.ch == '(' -> stringsFrom(toks, k + 2)
                        t.text == "compile" && prevDot && (toks.getOrNull(k - 2) as? Word)?.text == "Pattern" -> stringsFrom(toks, k + 2)
                        (t.text == "toRegex" || t.text == "toPattern") && prevDot -> stringsBefore(toks, k - 2)
                        else -> null
                    }
                if (parts == null) continue
                var unknown = false
                val pattern =
                    parts.joinToString("") { p ->
                        when (p) {
                            is String -> p
                            is Expr -> {
                                val code = p.code.trim()
                                when {
                                    code == "'$'" -> "$"
                                    else -> constants[code.substringAfterLast('.')] ?: run { unknown = true; "x" }
                                }
                            }
                            else -> ""
                        }
                    }
                out += Site(name, t.line, pattern, unknown)
            }
        }
        return out
    }

    private fun stringsFrom(toks: List<Tok>, start: Int): List<Any>? {
        val parts = ArrayList<Any>()
        var j = start
        while (j < toks.size) {
            val s = toks[j] as? Str ?: break
            parts.addAll(s.parts)
            if ((toks.getOrNull(j + 1) as? Mark)?.ch == '+' && toks.getOrNull(j + 2) is Str) j += 2 else break
        }
        return parts.takeIf { toks.getOrNull(start) is Str }
    }

    private fun stringsBefore(toks: List<Tok>, end: Int): List<Any>? {
        var b = end
        if (toks.getOrNull(b) !is Str) return null
        while (b >= 2 && (toks[b - 1] as? Mark)?.ch == '+' && toks[b - 2] is Str) b -= 2
        return (b..end step 2).flatMap { (toks[it] as Str).parts }
    }

    /** `val NAME = "..."` (plain strings, + concatenations) anywhere in the sources, by simple name; ambiguous names dropped. */
    private fun constants(all: Collection<List<Tok>>): Map<String, String> {
        val found = HashMap<String, MutableSet<String>>()
        for (toks in all) {
            for (k in 0 until toks.size - 3) {
                if ((toks[k] as? Word)?.text != "val") continue
                val name = (toks[k + 1] as? Word)?.text ?: continue
                if ((toks[k + 2] as? Mark)?.ch != '=') continue
                val sb = StringBuilder()
                var j = k + 3
                var ok = false
                while (j < toks.size) {
                    val s = toks[j] as? Str ?: break
                    if (s.parts.any { it !is String }) { ok = false; break }
                    s.parts.forEach { sb.append(it as String) }
                    ok = true
                    if ((toks.getOrNull(j + 1) as? Mark)?.ch == '+' && toks.getOrNull(j + 2) is Str) j += 2 else { j++; break }
                }
                val next = toks.getOrNull(j) as? Mark
                if (ok && next?.ch != '.' && next?.ch != '(') found.getOrPut(name) { HashSet() } += sb.toString()
            }
        }
        return found.filterValues { it.size == 1 }.mapValues { it.value.first() }
    }

    private fun lex(src: String): List<Tok> {
        val out = ArrayList<Tok>()
        val n = src.length
        var i = 0
        var line = 1

        fun templateAt(k: Int) = src[k] == '$' && k + 1 < n && (src[k + 1] == '{' || src[k + 1].isLetter() || src[k + 1] == '_')

        fun template(k: Int): Pair<Expr, Int> {
            if (src[k + 1] == '{') {
                var j = k + 2
                var depth = 1
                while (j < n && depth > 0) {
                    when (src[j]) {
                        '{' -> depth++
                        '}' -> depth--
                    }
                    j++
                }
                return Expr(src.substring(k + 2, j - 1)) to j
            }
            var j = k + 1
            while (j < n && (src[j].isLetterOrDigit() || src[j] == '_')) j++
            return Expr(src.substring(k + 1, j)) to j
        }

        while (i < n) {
            val c = src[i]
            when {
                c == '\n' -> { line++; i++ }
                c.isWhitespace() -> i++
                src.startsWith("//", i) -> i = src.indexOf('\n', i).let { if (it < 0) n else it }
                src.startsWith("/*", i) -> {
                    var depth = 1
                    i += 2
                    while (i < n && depth > 0) {
                        when {
                            src.startsWith("/*", i) -> { depth++; i += 2 }
                            src.startsWith("*/", i) -> { depth--; i += 2 }
                            else -> { if (src[i] == '\n') line++; i++ }
                        }
                    }
                }
                src.startsWith("\"\"\"", i) -> {
                    val start = line
                    val parts = ArrayList<Any>()
                    val buf = StringBuilder()
                    i += 3
                    while (i < n) {
                        if (src.startsWith("\"\"\"", i) && !src.startsWith("\"\"\"\"", i)) { i += 3; break }
                        if (templateAt(i)) {
                            if (buf.isNotEmpty()) { parts += buf.toString(); buf.clear() }
                            val (e, j) = template(i)
                            parts += e
                            i = j
                            continue
                        }
                        if (src[i] == '\n') line++
                        buf.append(src[i])
                        i++
                    }
                    if (buf.isNotEmpty()) parts += buf.toString()
                    out += Str(parts, start)
                }
                c == '"' -> {
                    val start = line
                    val parts = ArrayList<Any>()
                    val buf = StringBuilder()
                    i++
                    while (i < n && src[i] != '"') {
                        val ch = src[i]
                        if (ch == '\\') {
                            val e = src[i + 1]
                            if (e == 'u') {
                                buf.append(src.substring(i + 2, i + 6).toInt(16).toChar())
                                i += 6
                            } else {
                                buf.append(
                                    when (e) {
                                        't' -> '\t'
                                        'b' -> '\b'
                                        'n' -> '\n'
                                        'r' -> '\r'
                                        else -> e // \' \" \\ \$
                                    },
                                )
                                i += 2
                            }
                            continue
                        }
                        if (templateAt(i)) {
                            if (buf.isNotEmpty()) { parts += buf.toString(); buf.clear() }
                            val (e, j) = template(i)
                            parts += e
                            i = j
                            continue
                        }
                        buf.append(ch)
                        i++
                    }
                    i++
                    if (buf.isNotEmpty()) parts += buf.toString()
                    out += Str(parts, start)
                }
                c == '\'' -> {
                    // a char literal ('x', '\n' or a unicode escape); a quote inside it must not open a string
                    val end = if (src.getOrNull(i + 1) == '\\') (if (src.getOrNull(i + 2) == 'u') i + 7 else i + 3) else i + 2
                    if (src.getOrNull(end) == '\'') i = end + 1 else { out += Mark(c, line); i++ }
                }
                c.isLetter() || c == '_' -> {
                    var j = i
                    while (j < n && (src[j].isLetterOrDigit() || src[j] == '_')) j++
                    out += Word(src.substring(i, j), line)
                    i = j
                }
                c == '`' -> {
                    val j = src.indexOf('`', i + 1).let { if (it < 0) n else it + 1 }
                    out += Word(src.substring(i, j), line)
                    i = j
                }
                else -> { out += Mark(c, line); i++ }
            }
        }
        return out
    }
}

/** The constructs of a pattern that Android's ICU regex engine refuses (or that are JDK-only), with a reason each. */
internal object IcuRegexRisk {
    /**
     * `\p{...}` names compiled and matched the same on Android 8, 12 and 14 (app_process) and on the JDK. A name outside
     * this set is not wrong by itself: check it on a device first, then add it here.
     */
    private val generalCategories =
        setOf(
            "L", "Lu", "Ll", "Lt", "Lm", "Lo", "M", "Mn", "Mc", "Me", "N", "Nd", "Nl", "No", "P", "Pc", "Pd", "Ps", "Pe",
            "Pi", "Pf", "Po", "S", "Sm", "Sc", "Sk", "So", "Z", "Zs", "Zl", "Zp", "C", "Cc", "Cf", "Co", "Cn",
        )

    private fun propertyOk(name: String): Boolean {
        if (name in generalCategories) return true
        val script = name.substringAfter('=', "")
        val key = name.substringBefore('=', "")
        return (key.equals("script", ignoreCase = true) || key.equals("sc", ignoreCase = true)) && script.isNotEmpty() && script.all { it.isLetter() || it == '_' }
    }

    private val repetition = Regex("""\{\d+(,\d*)?\}""")

    fun problems(p: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        var depth = 0

        fun classStart(k: Int): Int {
            var j = k
            if (p.getOrNull(j) == '^') j++
            if (p.getOrNull(j) == ']') { out += "']' as the first member of a class needs a backslash"; j++ }
            return j
        }

        while (i < p.length) {
            val c = p[i]
            if (c == '\\') {
                val e = p.getOrNull(i + 1)
                when {
                    e == null -> i++
                    e == 'Q' -> i = p.indexOf("\\E", i + 2).let { if (it < 0) p.length else it + 2 }
                    (e == 'p' || e == 'P') && p.getOrNull(i + 2) == '{' -> {
                        val close = p.indexOf('}', i + 3)
                        if (close < 0) { out += "unclosed \\$e{"; i = p.length } else {
                            val name = p.substring(i + 3, close)
                            if (!propertyOk(name)) out += "\\$e{$name} is not a property name verified on Android (use \\p{script=...} or a general category)"
                            i = close + 1
                        }
                    }
                    e == 'p' || e == 'P' -> { out += "\\$e${p.getOrNull(i + 2) ?: ""} without braces is refused on Android (write \\$e{...})"; i += 3 }
                    (e == 'x' || e == 'N') && p.getOrNull(i + 2) == '{' -> i = p.indexOf('}', i + 3).let { if (it < 0) p.length else it + 1 }
                    else -> i += 2
                }
                continue
            }
            if (c == '(' && p.getOrNull(i + 1) == '?') {
                // inline flags such as (?i) or (?i:...): UNICODE_CHARACTER_CLASS (?U) is refused on Android
                val flags = p.substring(i + 2).takeWhile { it.isLetter() || it == '-' }
                if ('U' in flags) out += "the (?U) flag is refused on Android (its \\w \\d \\s \\b are Unicode already)"
            }
            if (depth > 0) {
                when (c) {
                    '[' -> { depth++; i = classStart(i + 1) }
                    ']' -> { depth--; i++ }
                    else -> i++
                }
                continue
            }
            when (c) {
                '[' -> { depth = 1; i = classStart(i + 1) }
                ']' -> { out += "']' outside a character class needs a backslash"; i++ }
                '}' -> { out += "'}' outside a character class needs a backslash (PatternSyntaxException on Android)"; i++ }
                '{' -> {
                    val m = repetition.find(p, i)?.takeIf { it.range.first == i }
                    if (m == null) { out += "'{' that does not start a repetition needs a backslash"; i++ } else i = m.range.last + 1
                }
                else -> i++
            }
        }
        return out
    }
}
