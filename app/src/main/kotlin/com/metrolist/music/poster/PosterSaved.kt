/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.poster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.metrolist.music.db.MusicDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

// ====================================================================================================================
// The poster after the process died (review 2026-11-12, finding 14). On a 2-3 GB phone Android may kill the app while the Photo Picker or
// the storage question of Android 8-9 is in front. Android then brings the "poster" page back in a NEW process, where the songs handed
// over in memory ([PosterLaunch]) are gone. So the editor keeps a small saved state (the SavedStateHandle of its page, a few KB): the
// stage, the title, the source song ids (reloaded from the database), the picked songs and the songs on the poster in full (at most 5
// each), the whole document (cards, look, stickers); and the photo goes into the app's cache right after it is picked (the picker's
// read grant ends with the process), the cut-out mask next to it, so neither the photo nor the seconds of the cut-out are lost.

/** Reads songs back by id (the database in the app; a stand-in in the tests). Returns the songs found, in any order. */
fun interface PosterSongLookup {
    suspend fun find(context: Context, ids: List<String>): List<PosterSong>

    companion object {
        /** The app's database, reached through Hilt (the poster's view model is not a Hilt view model). */
        val Database = PosterSongLookup { context, ids ->
            val db = EntryPointAccessors.fromApplication(context.applicationContext, PosterDatabaseEntryPoint::class.java).database()
            // SQLite takes at most 999 values in one IN (...)
            ids.distinct().chunked(500).flatMap { db.getSongsByIds(it) }.map { it.toPosterSong() }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PosterDatabaseEntryPoint {
    fun database(): MusicDatabase
}

/** The saved state of one poster editor, as plain values (built and read on the main thread). */
class PosterSnapshot(
    val session: String,
    val stage: String,
    val title: String,
    val sourceIds: List<String>,
    val picked: List<String>,
    /** The picked songs in full (the source may not be in the database: an online playlist). */
    val pickedSongs: List<PosterSong>,
    /** The songs on the poster (EDIT) in full. */
    val songs: List<PosterSong>,
    val startSongs: List<PosterSong>,
    val doc: PosterDoc?,
    val cutout: CutoutPhase,
    val changedSinceSave: Boolean,
    /** How the poster is saved (round 4.3.6); an older saved state has neither and gets PNG. */
    val exportFormat: PosterFormat = PosterFormat.PNG,
    val exportQuality: Float = 0.92f,
) {
    fun toBundle(): Bundle =
        Bundle().apply {
            putInt("v", VERSION)
            putString("session", session)
            putString("stage", stage)
            putString("title", title)
            putStringArray("src", sourceIds.take(MAX_SOURCE_IDS).toTypedArray())
            putStringArray("picked", picked.toTypedArray())
            putBundle("pickedSongs", songsBundle(pickedSongs))
            putBundle("songs", songsBundle(songs))
            putBundle("start", songsBundle(startSongs))
            doc?.let { putBundle("doc", docBundle(it)) }
            putString("cutout", cutout.name)
            putBoolean("changed", changedSinceSave)
            putString("exportFormat", exportFormat.name)
            putFloat("exportQuality", exportQuality)
        }

    companion object {
        const val VERSION = 1

        /** Enough for the picker of a big playlist; ids are short (11 characters for YouTube), so this stays under about 25 KB. */
        const val MAX_SOURCE_IDS = 600

        fun from(b: Bundle?): PosterSnapshot? {
            if (b == null || b.getInt("v") != VERSION) return null
            return runCatching {
                PosterSnapshot(
                    session = b.getString("session") ?: return null,
                    stage = b.getString("stage") ?: return null,
                    title = b.getString("title").orEmpty(),
                    sourceIds = b.getStringArray("src")?.toList().orEmpty(),
                    picked = b.getStringArray("picked")?.toList().orEmpty(),
                    pickedSongs = songsFrom(b.getBundle("pickedSongs")),
                    songs = songsFrom(b.getBundle("songs")),
                    startSongs = songsFrom(b.getBundle("start")),
                    doc = b.getBundle("doc")?.let { docFrom(it) },
                    cutout = runCatching { CutoutPhase.valueOf(b.getString("cutout").orEmpty()) }.getOrDefault(CutoutPhase.NONE),
                    changedSinceSave = b.getBoolean("changed"),
                    exportFormat = runCatching { PosterFormat.valueOf(b.getString("exportFormat").orEmpty()) }.getOrDefault(PosterFormat.PNG),
                    exportQuality = if (b.containsKey("exportQuality")) b.getFloat("exportQuality").coerceIn(0.5f, 1f) else 0.92f,
                )
            }.getOrNull()
        }

        private fun songsBundle(songs: List<PosterSong>): Bundle =
            Bundle().apply {
                putStringArray("id", songs.map { it.id }.toTypedArray())
                putStringArray("title", songs.map { it.title }.toTypedArray())
                putStringArray("artist", songs.map { it.artist }.toTypedArray())
                putStringArray("cover", songs.map { it.coverUrl.orEmpty() }.toTypedArray())
                putIntArray("sec", songs.map { it.durationSec }.toIntArray())
            }

        private fun songsFrom(b: Bundle?): List<PosterSong> {
            if (b == null) return emptyList()
            val id = b.getStringArray("id") ?: return emptyList()
            val title = b.getStringArray("title") ?: return emptyList()
            val artist = b.getStringArray("artist") ?: return emptyList()
            val cover = b.getStringArray("cover") ?: return emptyList()
            val sec = b.getIntArray("sec") ?: return emptyList()
            val n = minOf(id.size, title.size, artist.size, cover.size, sec.size)
            return List(n) { PosterSong(id[it], title[it], artist[it], cover[it].ifEmpty { null }, sec[it]) }
        }

        private const val POSE = 7
        private const val STICKER = 4
        private const val TEXT = 4

        private fun docBundle(d: PosterDoc): Bundle =
            Bundle().apply {
                putStringArray("songs", d.songs.map { it.id }.toTypedArray())
                putFloatArray("poses", FloatArray(d.poses.size * POSE).also { a ->
                    d.poses.forEachIndexed { i, p ->
                        a[i * POSE] = p.x; a[i * POSE + 1] = p.y; a[i * POSE + 2] = p.scale; a[i * POSE + 3] = p.rotation
                        a[i * POSE + 4] = p.tiltX; a[i * POSE + 5] = p.tiltY; a[i * POSE + 6] = p.lift
                    }
                })
                putString("layout", d.layout.name)
                putIntArray("stickerIds", d.stickers.map { it.id }.toIntArray())
                putStringArray("stickerKinds", d.stickers.map { it.kind.name }.toTypedArray())
                putStringArray("stickerKeys", d.stickers.map { it.imageKey.orEmpty() }.toTypedArray())
                putBooleanArray("stickerFlips", d.stickers.map { it.flip }.toBooleanArray())
                putFloatArray("stickerValues", FloatArray(d.stickers.size * STICKER).also { a ->
                    d.stickers.forEachIndexed { i, s ->
                        a[i * STICKER] = s.x; a[i * STICKER + 1] = s.y; a[i * STICKER + 2] = s.scale; a[i * STICKER + 3] = s.rotation
                    }
                })
                putFloatArray("look", floatArrayOf(d.glow, d.dim, d.blur, d.photoZoom, d.photoPanX, d.photoPanY))
                d.glowTint?.let { putInt("glowTint", it.toArgb()) }
                putString("material", d.material.name)
                putBoolean("materialCustom", d.materialCustom)
                putFloatArray("card", d.card)
                putString("shadowTone", d.shadowTone.name)
                putString("grade", d.grade.name)
                putFloatArray("fx", d.fx)
                d.preset?.let { putString("preset", it.name) }
                // round 4.3.6: the shape, the background, the vignette and the texts (an older saved state has none of them: 9:16, no texts)
                putString("ratio", d.ratio.name)
                putString("background", d.background.name)
                putFloat("vignette", d.vignette)
                putIntArray("textIds", d.texts.map { it.id }.toIntArray())
                putStringArray("textWords", d.texts.map { it.text }.toTypedArray())
                putStringArray("textStyles", d.texts.map { it.style.name }.toTypedArray())
                putIntArray("textColors", d.texts.map { it.color }.toIntArray())
                putFloatArray("textValues", FloatArray(d.texts.size * TEXT).also { a ->
                    d.texts.forEachIndexed { i, t ->
                        a[i * TEXT] = t.x; a[i * TEXT + 1] = t.y; a[i * TEXT + 2] = t.scale; a[i * TEXT + 3] = t.rotation
                    }
                })
            }

        private fun docFrom(b: Bundle): PosterDoc? {
            val songIds = b.getStringArray("songs") ?: return null
            val pa = b.getFloatArray("poses") ?: return null
            val poses = List(pa.size / POSE) { i ->
                CardPose(pa[i * POSE], pa[i * POSE + 1], pa[i * POSE + 2], pa[i * POSE + 3], pa[i * POSE + 4], pa[i * POSE + 5], pa[i * POSE + 6])
            }
            val ids = b.getIntArray("stickerIds") ?: IntArray(0)
            val kinds = b.getStringArray("stickerKinds") ?: emptyArray()
            val keys = b.getStringArray("stickerKeys") ?: emptyArray()
            val flips = b.getBooleanArray("stickerFlips") ?: BooleanArray(0)
            val sv = b.getFloatArray("stickerValues") ?: FloatArray(0)
            val n = minOf(ids.size, kinds.size, keys.size, flips.size, sv.size / STICKER)
            val stickers = (0 until n).mapNotNull { i ->
                val kind = runCatching { StickerKind.valueOf(kinds[i]) }.getOrNull() ?: return@mapNotNull null
                PosterSticker(ids[i], kind, sv[i * STICKER], sv[i * STICKER + 1], sv[i * STICKER + 2], sv[i * STICKER + 3], flips[i], keys[i].ifEmpty { null })
            }
            val look = b.getFloatArray("look") ?: return null
            val card = b.getFloatArray("card") ?: return null
            val fx = b.getFloatArray("fx") ?: return null
            if (look.size < 6 || card.size < 11 || fx.size < 6) return null
            val tIds = b.getIntArray("textIds") ?: IntArray(0)
            val tWords = b.getStringArray("textWords") ?: emptyArray()
            val tStyles = b.getStringArray("textStyles") ?: emptyArray()
            val tColors = b.getIntArray("textColors") ?: IntArray(0)
            val tv = b.getFloatArray("textValues") ?: FloatArray(0)
            val tn = minOf(tIds.size, tWords.size, tStyles.size, tColors.size, tv.size / TEXT)
            val texts = (0 until tn).mapNotNull { i ->
                val style = runCatching { PosterTextStyle.valueOf(tStyles[i]) }.getOrNull() ?: return@mapNotNull null
                val words = PosterTexts.clean(tWords[i]) ?: return@mapNotNull null
                PosterText(tIds[i], words, style, tv[i * TEXT], tv[i * TEXT + 1], tv[i * TEXT + 2], tv[i * TEXT + 3], tColors[i])
            }
            return PosterDoc(
                songs = songIds.map { PosterSong(it, "", "", null, 0) },
                poses = poses,
                layout = runCatching { PosterLayout.valueOf(b.getString("layout").orEmpty()) }.getOrDefault(PosterLayout.ORBIT),
                stickers = stickers,
                glow = look[0],
                glowTint = if (b.containsKey("glowTint")) Color(b.getInt("glowTint")) else null,
                dim = look[1],
                blur = look[2],
                photoZoom = look[3],
                photoPanX = look[4],
                photoPanY = look[5],
                material = runCatching { CardMaterial.valueOf(b.getString("material").orEmpty()) }.getOrDefault(CardMaterial.ASAP),
                materialCustom = b.getBoolean("materialCustom"),
                card = card,
                shadowTone = runCatching { ShadowTone.valueOf(b.getString("shadowTone").orEmpty()) }.getOrDefault(ShadowTone.NETRAL),
                grade = runCatching { PosterGrade.valueOf(b.getString("grade").orEmpty()) }.getOrDefault(PosterGrade.ASLI),
                fx = fx,
                preset = b.getString("preset")?.let { runCatching { PosterFxPreset.valueOf(it) }.getOrNull() },
                ratio = PosterRatio.parse(b.getString("ratio")),
                texts = texts,
                background = runCatching { PosterBackground.valueOf(b.getString("background").orEmpty()) }.getOrDefault(PosterBackground.ASLI),
                vignette = if (b.containsKey("vignette")) b.getFloat("vignette").coerceIn(0f, 1f) else PosterFxDefaults.VIGNETTE,
            )
        }
    }
}

/**
 * The photo of the poster being edited, kept in the app's cache (never backed up) so it survives the process: the decoded picture as a
 * JPEG and the cut-out mask (deflated bytes). One folder per editor session; a new poster clears the folder; leaving the poster deletes it.
 */
class PosterSessionFiles(context: Context, val session: String) {
    private val dir = File(context.applicationContext.cacheDir, DIR)
    private val photo = File(dir, "$session-photo.jpg")
    private val mask = File(dir, "$session-mask.bin")

    // the sticker being edited (round 4.3.5): its picture as it came and the mask so far, so the editor survives the process too
    private val stickerPicture = File(dir, "$session-sticker.png")
    private val stickerMask = File(dir, "$session-sticker-mask.bin")

    /** Writes the picture of the sticker being edited (unpremultiplied ARGB ints) as a PNG (alpha kept). Off the main thread. */
    fun writeStickerPicture(argb: IntArray, w: Int, h: Int) {
        dir.mkdirs()
        stickerMask.delete()
        val b = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        try {
            atomically(stickerPicture) { out -> if (!b.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IllegalStateException("PNG") }
        } finally {
            b.recycle()
        }
    }

    fun writeStickerMask(w: Int, h: Int, alpha: ByteArray) = writeMaskFile(stickerMask, w, h, alpha)

    fun readStickerPicture(): Bitmap? {
        if (!stickerPicture.isFile) return null
        return runCatching { BitmapFactory.decodeFile(stickerPicture.absolutePath, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) }.getOrNull()
    }

    fun readStickerMask(w: Int, h: Int): ByteArray? = readMaskFile(stickerMask, w, h)

    fun deleteSticker() {
        stickerPicture.delete()
        stickerMask.delete()
    }

    fun hasPhoto(): Boolean = photo.isFile && photo.length() > 0

    /** Writes [bitmap] (the decoded, upright photo) as a high-quality JPEG. Off the main thread. */
    fun writePhoto(bitmap: Bitmap) {
        dir.mkdirs()
        mask.delete()
        atomically(photo) { out -> if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) throw IllegalStateException("JPEG") }
    }

    /** Writes a photo given as opaque ARGB ints (after a turn or a mirror). */
    fun writePhoto(image: PosterImage) {
        val b = Bitmap.createBitmap(image.argb, image.w, image.h, Bitmap.Config.ARGB_8888)
        try {
            writePhoto(b)
        } finally {
            b.recycle()
        }
    }

    fun writeMask(w: Int, h: Int, alpha: ByteArray) {
        dir.mkdirs()
        writeMaskFile(mask, w, h, alpha)
    }

    /** The cached photo, or null. */
    fun readPhoto(): Bitmap? {
        if (!hasPhoto()) return null
        return runCatching { BitmapFactory.decodeFile(photo.absolutePath, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) }.getOrNull()
    }

    /** The cached mask for a photo of [w] x [h], or null (none, or for another size). */
    fun readMask(w: Int, h: Int): ByteArray? = readMaskFile(mask, w, h)

    fun deletePhoto() {
        photo.delete()
        mask.delete()
    }

    fun deleteMask() {
        mask.delete()
    }

    /** Removes this session's files. */
    fun delete() {
        deletePhoto()
        deleteSticker()
    }

    /** Removes every other session's files (a new poster starts: an old session of a process that never came back is garbage). */
    fun clearOthers() {
        dir.listFiles()?.forEach { if (!it.name.startsWith("$session-")) it.delete() }
    }

    companion object {
        const val DIR = "poster_session"
        private const val MASK_MAGIC = 0x504D4B31 // "PMK1"

        /** Writes [target] through a temporary file and a rename: a crash half way never leaves half a file. */
        internal inline fun atomically(target: File, write: (FileOutputStream) -> Unit) {
            val tmp = File(target.parentFile, target.name + ".tmp")
            try {
                FileOutputStream(tmp).use { out ->
                    write(out)
                    out.flush()
                    runCatching { out.fd.sync() }
                }
                if (!tmp.renameTo(target)) {
                    target.delete()
                    if (!tmp.renameTo(target)) throw IllegalStateException("rename")
                }
            } finally {
                tmp.delete()
            }
        }

        /** A mask file: a magic number, the size, the bytes deflated. Atomic. */
        internal fun writeMaskFile(file: File, w: Int, h: Int, alpha: ByteArray) {
            file.parentFile?.mkdirs()
            atomically(file) { out ->
                DataOutputStream(out).let { d ->
                    d.writeInt(MASK_MAGIC)
                    d.writeInt(w)
                    d.writeInt(h)
                    d.flush()
                }
                val def = Deflater(Deflater.BEST_SPEED)
                try {
                    val z = DeflaterOutputStream(out, def)
                    z.write(alpha, 0, w * h)
                    z.finish()
                } finally {
                    def.end()
                }
            }
        }

        /** The mask in [file] for a picture of [w] x [h], or null (none, damaged, or for another size). */
        internal fun readMaskFile(file: File, w: Int, h: Int): ByteArray? {
            if (!file.isFile) return null
            return runCatching {
                file.inputStream().buffered().use { input ->
                    val d = DataInputStream(input)
                    if (d.readInt() != MASK_MAGIC || d.readInt() != w || d.readInt() != h) return null
                    val out = ByteArray(w * h)
                    val inf = Inflater()
                    try {
                        val z = InflaterInputStream(input, inf)
                        var off = 0
                        while (off < out.size) {
                            val n = z.read(out, off, out.size - off)
                            if (n < 0) return null
                            off += n
                        }
                    } finally {
                        inf.end()
                    }
                    out
                }
            }.getOrNull()
        }
    }
}
