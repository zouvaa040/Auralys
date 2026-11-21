package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class ThumbnailRenderer(
    val musicThumbnailRenderer: MusicThumbnailRenderer?,
    val musicAnimatedThumbnailRenderer: MusicAnimatedThumbnailRenderer?,
    val croppedSquareThumbnailRenderer: MusicThumbnailRenderer?,
) {
    @Serializable
    data class MusicThumbnailRenderer(
        val thumbnail: Thumbnails,
        val thumbnailCrop: String?,
        val thumbnailScale: String?,
    ) {
        fun getThumbnailUrl() = thumbnail.thumbnails.lastOrNull()?.url

        /** True when the largest thumbnail is clearly not square (video frames are 16:9). */
        fun isNotSquare() = thumbnail.thumbnails.lastOrNull().isNotSquare()
    }

    /**
     * Same renderer priority as [getThumbnailUrl]. Used to tell video uploads (wide frame) from
     * catalogue songs (square cover) in search results.
     */
    fun isNotSquare(): Boolean =
        (
            musicThumbnailRenderer
                ?: musicAnimatedThumbnailRenderer?.backupRenderer
                ?: croppedSquareThumbnailRenderer
        )?.isNotSquare() == true

    fun getThumbnailUrl(): String? =
        musicThumbnailRenderer?.getThumbnailUrl()
            ?: musicAnimatedThumbnailRenderer?.backupRenderer?.getThumbnailUrl()
            ?: croppedSquareThumbnailRenderer?.getThumbnailUrl()

    @Serializable
    data class MusicAnimatedThumbnailRenderer(
        val animatedThumbnail: Thumbnails,
        val backupRenderer: MusicThumbnailRenderer,
    )
}
