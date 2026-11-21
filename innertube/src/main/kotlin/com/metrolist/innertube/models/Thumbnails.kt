package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class Thumbnails(
    val thumbnails: List<Thumbnail>,
)

@Serializable
data class Thumbnail(
    val url: String,
    val width: Int?,
    val height: Int?,
)

/**
 * Width/height ratio outside 0.85..1.15 means "not a square cover". Unknown sizes count as square so a
 * missing size never hides a real song. Idea from BitChord's search parser (GPLv3), own code.
 */
fun Thumbnail?.isNotSquare(): Boolean {
    val w = this?.width ?: return false
    val h = this.height ?: return false
    if (w <= 0 || h <= 0) return false
    val ratio = w.toFloat() / h.toFloat()
    return ratio < 0.85f || ratio > 1.15f
}
