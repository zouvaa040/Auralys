package com.metrolist.kugou.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchSongResponse(
    val status: Int,
    val errcode: Int,
    val error: String,
    val data: Data,
) {
    @Serializable
    data class Data(
        val info: List<Info>,
    ) {
        @Serializable
        data class Info(
            val duration: Int,
            val hash: String,
            // what KuGou calls the song and its singer, so a hit with only a similar length can be told from the real one
            @SerialName("songname")
            val songName: String = "",
            @SerialName("singername")
            val singerName: String = "",
        )
    }
}
