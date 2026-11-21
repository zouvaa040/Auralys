package com.metrolist.music.lyrics

import java.io.File

/**
 * The recorded answers of the live probe ([LyricsLiveProbeTest]) as test fixtures. Only the shape of the lyrics is kept
 * (sync kind and the start time of every line) and the metadata of search hits, never the words, so no lyric text ends
 * up in the repository. One tab separated text file per song under `src/test/resources/lyrics-probe/`.
 */
internal object LyricsProbeFixtures {
    data class Song(val id: String, val title: String, val artist: String, val album: String?, val duration: Int)

    /** [kind] is word, line or plain; [times] the start of each line in ms (empty for plain); [count] the number of lines. */
    data class Skeleton(val kind: String, val times: List<Long>, val count: Int)

    data class Hit(val title: String, val artist: String, val duration: Double, val synced: Skeleton?, val plain: Boolean)

    /** An answer of the new lookup: the shape of the lyrics and what the source said it matched. */
    data class Answer(val skeleton: Skeleton, val title: String?, val artist: String?, val seconds: Double?, val keyedOnVideo: Boolean) {
        fun hit(): LyricsHit = LyricsHit(text(skeleton), title, artist, seconds, keyedOnVideo)
    }

    data class Fixture(val song: Song, val providers: Map<String, Skeleton?>, val answers: Map<String, Answer?>, val lrclib: List<Hit>) {
        val request: LyricsRequest
            get() = LyricsRequest.of(song.id, song.title, song.artist.split(", "), song.duration, song.album)
    }

    fun skeleton(text: String): Skeleton {
        val lines = runCatching { LyricsUtils.parseLyrics(text) }.getOrDefault(emptyList()).filter { it.text.isNotBlank() }
        if (lines.isEmpty() || !LyricsSelection.isSynced(text)) {
            return Skeleton("plain", emptyList(), text.lines().count { it.isNotBlank() })
        }
        val word = lines.any { !it.words.isNullOrEmpty() }
        return Skeleton(if (word) "word" else "line", lines.map { it.time }, lines.size)
    }

    fun firstWords(text: String): String {
        val lines = runCatching { LyricsUtils.parseLyrics(text) }.getOrDefault(emptyList())
        val first = lines.firstOrNull { it.text.isNotBlank() }?.text ?: text.lines().firstOrNull { it.isNotBlank() }.orEmpty()
        return first.take(40)
    }

    fun slug(query: String): String = query.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** Rebuilds line-stamped text from a skeleton, for code that wants lyrics text: every line is "x". */
    fun text(skeleton: Skeleton): String =
        if (skeleton.kind == "plain") {
            List(skeleton.count.coerceAtLeast(1)) { "x" }.joinToString("\n")
        } else {
            skeleton.times.joinToString("\n") { ms ->
                val line = "[%02d:%02d.%02d]x".format(ms / 60000, ms / 1000 % 60, ms % 1000 / 10)
                if (skeleton.kind == "word") line + "\n<x:%.3f:%.3f>".format(java.util.Locale.ROOT, ms / 1000.0, ms / 1000.0 + 0.4) else line
            }
        }

    private fun Skeleton?.encode(): String = if (this == null) "none" else "$kind\t$count\t${times.joinToString(",")}"

    private fun decodeSkeleton(parts: List<String>): Skeleton? =
        if (parts[0] == "none") {
            null
        } else {
            Skeleton(parts[0], parts.getOrNull(2)?.split(',')?.filter { it.isNotBlank() }?.map { it.toLong() }.orEmpty(), parts[1].toInt())
        }

    fun write(file: File, song: Song, providers: Map<String, Skeleton?>, answers: Map<String, Answer?>, hits: List<Hit>) {
        val clean = { s: String? -> s.orEmpty().replace('\t', ' ').replace('\n', ' ') }
        val lines = ArrayList<String>()
        lines += "song\t${clean(song.id)}\t${clean(song.title)}\t${clean(song.artist)}\t${clean(song.album)}\t${song.duration}"
        providers.forEach { (name, sk) -> lines += "prov\t$name\t${sk.encode()}" }
        answers.forEach { (name, a) ->
            lines +=
                if (a == null) {
                    "ans\t$name\tmiss"
                } else {
                    "ans\t$name\tfound\t${clean(a.title)}\t${clean(a.artist)}\t${a.seconds ?: ""}\t${a.keyedOnVideo}\t${a.skeleton.encode()}"
                }
        }
        hits.forEach { h -> lines += "hit\t${clean(h.title)}\t${clean(h.artist)}\t${h.duration}\t${h.plain}\t${h.synced.encode()}" }
        file.writeText(lines.joinToString("\n") + "\n")
    }

    fun read(text: String): Fixture {
        var song: Song? = null
        val providers = LinkedHashMap<String, Skeleton?>()
        val hits = ArrayList<Hit>()
        val answers = LinkedHashMap<String, Answer?>()
        text.lines().filter { it.isNotBlank() }.forEach { line ->
            val p = line.split('\t')
            when (p[0]) {
                "song" -> song = Song(p[1], p[2], p[3], p[4].ifBlank { null }, p[5].toInt())
                "prov" -> providers[p[1]] = decodeSkeleton(p.drop(2))
                "ans" ->
                    answers[p[1]] =
                        if (p[2] == "miss") {
                            null
                        } else {
                            Answer(decodeSkeleton(p.drop(7))!!, p[3].ifBlank { null }, p[4].ifBlank { null }, p[5].toDoubleOrNull(), p[6].toBoolean())
                        }
                "hit" -> hits += Hit(p[1], p[2], p[3].toDouble(), decodeSkeleton(p.drop(5)), p[4].toBoolean())
            }
        }
        return Fixture(song!!, providers, answers, hits)
    }

    fun load(name: String): Fixture {
        val stream = LyricsProbeFixtures::class.java.classLoader!!.getResourceAsStream("lyrics-probe/$name.txt")
            ?: error("missing fixture $name")
        return read(stream.bufferedReader().readText())
    }
}
