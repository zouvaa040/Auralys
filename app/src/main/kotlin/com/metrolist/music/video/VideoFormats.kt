/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * What the video mode needs to know about the video format the library picked, and how sharp it may be.
 * Pure Kotlin on purpose (no Android types) so it is unit tested on the JVM.
 *
 * The library chooses the video format itself (tallest height up to the cap, then the lowest bitrate) and gives no codec
 * hint: VP9 in WebM where YouTube has VP9, H.264 in MP4 otherwise. So the codec cannot be asked for; it can only be
 * checked against the decoders of the phone, and a format the phone cannot decode means "no video for this song".
 */

package com.metrolist.music.video

enum class VideoContainer { WEBM, MP4 }

/** [decoderMime] is the MIME type MediaCodec knows the decoder under. */
enum class VideoCodec(
    val decoderMime: String,
) {
    VP9("video/x-vnd.on2.vp9"),
    AVC("video/avc"),
    AV1("video/av01"),
    HEVC("video/hevc"),
}

data class VideoFormatInfo(
    val container: VideoContainer,
    val codec: VideoCodec,
    val width: Int,
    val height: Int,
    val bitrate: Int?,
)

/** Why a song gets no video. Only for logs and tests; the user sees no difference. */
enum class NoVideoReason {
    /** The library gave no SABR video for the song. */
    NO_STREAM,

    /** A format came back that the app cannot read (unknown container or codec). */
    UNKNOWN_FORMAT,

    /** The phone has no decoder for the codec (or not for this size). */
    UNSUPPORTED_CODEC,

    /** The song cannot be played as video (age limit, not available). */
    UNAVAILABLE,

    /** The song is not a music video and has no matching one. */
    NO_COUNTERPART,
}

object VideoFormats {
    /**
     * Reads the format of a library result. [mimeType] is the container ("video/webm"), possibly with a codecs
     * parameter; [codecs] is the codec string ("vp9", "avc1.4D401E"). Null when the container or the codec is not one
     * the app can read.
     */
    fun parse(
        mimeType: String?,
        codecs: String?,
        width: Int?,
        height: Int?,
        bitrate: Int?,
    ): VideoFormatInfo? {
        val mime = mimeType?.trim()?.lowercase().orEmpty()
        val container =
            when (mime.substringBefore(';').trim()) {
                "video/webm" -> VideoContainer.WEBM
                "video/mp4" -> VideoContainer.MP4
                else -> return null
            }
        val codecString = codecs?.takeIf { it.isNotBlank() } ?: codecsParameter(mime)
        val codec = codecOf(codecString) ?: return null
        // A codec in the wrong container is a format nobody has seen: say "unknown" instead of guessing an extractor.
        val sensible =
            when (container) {
                VideoContainer.WEBM -> codec == VideoCodec.VP9 || codec == VideoCodec.AV1
                VideoContainer.MP4 -> codec == VideoCodec.AVC || codec == VideoCodec.HEVC || codec == VideoCodec.AV1
            }
        if (!sensible) return null
        return VideoFormatInfo(container, codec, (width ?: 0).coerceAtLeast(0), (height ?: 0).coerceAtLeast(0), bitrate)
    }

    /** The video codec named in a codecs string such as `avc1.4D401E`, `vp09.00.40.08` or `vp9, opus`. */
    fun codecOf(codecs: String?): VideoCodec? {
        if (codecs.isNullOrBlank()) return null
        for (part in codecs.lowercase().split(',')) {
            val c = part.trim().trim('"')
            when {
                c.startsWith("avc1") || c.startsWith("avc3") -> return VideoCodec.AVC
                c.startsWith("vp9") || c.startsWith("vp09") -> return VideoCodec.VP9
                c.startsWith("av01") || c == "av1" -> return VideoCodec.AV1
                c.startsWith("hev1") || c.startsWith("hvc1") -> return VideoCodec.HEVC
            }
        }
        return null
    }

    private fun codecsParameter(mime: String): String? {
        val start = mime.indexOf("codecs=")
        if (start < 0) return null
        return mime.substring(start + "codecs=".length).trim().trim('"').substringBefore('"')
    }

    /** True when the phone can decode [info]. [decoderSupports] answers for a MIME type and a picture size. */
    fun isDecodable(
        info: VideoFormatInfo,
        decoderSupports: (mime: String, width: Int, height: Int) -> Boolean,
    ): Boolean = decoderSupports(info.codec.decoderMime, info.width, info.height)
}

/** How well the phone decodes a format: by a hardware decoder, only by a software one (the CPU), or not at all. */
enum class DecoderKind { NONE, SOFTWARE, HARDWARE }

/** One decoder of the phone as the choice sees it. [hardwareAccelerated] is null below API 29: the name decides then. */
class DecoderCandidate(
    val name: String,
    val hardwareAccelerated: Boolean?,
)

/**
 * Hardware decoders are preferred; a phone that has only a software decoder for the picked format gets at most 360 p, so
 * the CPU cannot melt the battery or the interface (REVIEW-2026-11-07 F3). Pure Kotlin, unit tested.
 */
object DecoderChoice {
    /** The tallest picture a CPU decoder is given. */
    const val SOFTWARE_MAX_HEIGHT = 360

    /** The software decoders by name (the rules of media3's MediaCodecInfo for phones below API 29). */
    fun isSoftwareName(name: String): Boolean {
        val n = name.lowercase()
        if (n.startsWith("arc.")) return false // ChromeOS hardware decoders
        return n.startsWith("omx.google.") ||
            n.startsWith("omx.ffmpeg.") ||
            (n.startsWith("omx.sec.") && n.contains(".sw.")) ||
            n == "omx.qcom.video.decoder.hevcswvdec" ||
            n.startsWith("c2.android.") ||
            n.startsWith("c2.google.") ||
            (!n.startsWith("omx.") && !n.startsWith("c2."))
    }

    fun isHardware(candidate: DecoderCandidate): Boolean = candidate.hardwareAccelerated ?: !isSoftwareName(candidate.name)

    /** The kind of decoding the phone offers, from the decoders that can do the format: one hardware decoder is enough. */
    fun kindOf(capable: List<DecoderCandidate>): DecoderKind =
        when {
            capable.isEmpty() -> DecoderKind.NONE
            capable.any(::isHardware) -> DecoderKind.HARDWARE
            else -> DecoderKind.SOFTWARE
        }

    /**
     * Is a picture [heightPx] tall too sharp for a software decoder? An unknown height (0) counts as sharp when more than
     * [SOFTWARE_MAX_HEIGHT] was asked for.
     */
    fun tooSharpForSoftware(
        heightPx: Int,
        askedMaxHeight: Int,
    ): Boolean = if (heightPx > 0) heightPx > SOFTWARE_MAX_HEIGHT else askedMaxHeight > SOFTWARE_MAX_HEIGHT

    /** The decoders in the order the player tries them: hardware first, the rest after, the order inside each group kept. */
    fun <T> hardwareFirst(
        decoders: List<T>,
        isHardware: (T) -> Boolean,
    ): List<T> = decoders.sortedBy { if (isHardware(it)) 0 else 1 }
}

/** How sharp the picture may be. 360 p on a metered network, with the data saver and on a phone with little RAM. */
object VideoQuality {
    const val LOW = 360
    const val HIGH = 720

    /** The data saver as the player service reads it: switched on, and (when "only on mobile data") on a metered network. */
    fun dataSaverActive(
        dataSaverOn: Boolean,
        mobileOnly: Boolean,
        metered: Boolean,
    ): Boolean = dataSaverOn && (!mobileOnly || metered)

    fun maxHeight(
        metered: Boolean,
        dataSaverActive: Boolean,
        lowRam: Boolean,
    ): Int = if (metered || dataSaverActive || lowRam) LOW else HIGH
}
