/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

object LyricsProviderRegistry {
    private val providerMap = mapOf(
        "BetterLyrics" to BetterLyricsProvider,
        "Paxsenix" to PaxsenixLyricsProvider,
        "LrcLib" to LrcLibLyricsProvider,
        "KuGou" to KuGouLyricsProvider,
        "SimpMusic" to SimpMusicLyricsProvider,
        "BiniLyrics" to BiniLyricsProvider,
        "LrcRed" to LrcRedLyricsProvider,
        "Unison" to UnisonLyricsProvider,
        "LyricsPlus" to LyricsPlusProvider,
        "Zemer" to ZemerLyricsProvider,
        "YouTubeSubtitle" to YouTubeSubtitleLyricsProvider,
        "YouTube" to YouTubeLyricsProvider,
    )

    val providerNames = providerMap.keys.toList()

    /** The providers of the versions before SimpMusic, BiniLyrics, LrcRed and Unison were added. */
    private val EXISTING_BEFORE_UPDATE =
        setOf("BetterLyrics", "Paxsenix", "LrcLib", "KuGou", "LyricsPlus", "Zemer", "YouTubeSubtitle", "YouTube")

    fun getProviderByName(name: String): LyricsProvider? = providerMap[name]

    fun getProviderName(provider: LyricsProvider): String? =
        providerMap.entries.find { it.value == provider }?.key

    fun deserializeProviderOrder(orderString: String): List<String> {
        if (orderString.isBlank()) {
            return getDefaultProviderOrder()
        }
        val saved = orderString.split(",").map { it.trim() }.filter { it in providerNames }.distinct()
        return mergeNewProviders(saved, getDefaultProviderOrder())
    }

    /**
     * A provider that is not in the saved order (added by an update) goes in front of the first saved
     * provider that follows it in the default order, so a new source does not end up below the YouTube
     * fallbacks for people who already had a saved order.
     */
    internal fun mergeNewProviders(
        saved: List<String>,
        defaultOrder: List<String>,
    ): List<String> {
        // Providers the app already had keep the old rule (missing ones go to the end); only the ones
        // added later are slotted in by their default position.
        val result = (saved + defaultOrder.filter { it in EXISTING_BEFORE_UPDATE && it !in saved }).toMutableList()
        defaultOrder.forEachIndexed { index, name ->
            if (name in result) return@forEachIndexed
            val before = defaultOrder.drop(index + 1).firstNotNullOfOrNull { later -> result.indexOf(later).takeIf { it >= 0 } }
            // never above the source the user put first
            if (before != null) result.add(if (saved.isNotEmpty()) maxOf(before, 1) else before, name) else result.add(name)
        }
        return result
    }

    fun serializeProviderOrder(providers: List<String>): String {
        return providers.filter { it in providerNames }.joinToString(",")
    }

    // Apple style TTML sources that check title, version and length come first; KuGou (title lines, looser search)
    // and Paxsenix (slow, often times out) after the community databases. Order only decides between answers of the
    // same rank (see LyricsSelection): a word-timed, checked answer wins wherever it is in the list.
    fun getDefaultProviderOrder(): List<String> = listOf(
        "BetterLyrics",
        "BiniLyrics",
        "LrcRed",
        "LrcLib",
        "SimpMusic",
        "Unison",
        "KuGou",
        "Paxsenix",
        "LyricsPlus",
        "Zemer",
        "YouTubeSubtitle",
        "YouTube",
    )

    fun getOrderedProviders(orderString: String): List<LyricsProvider> {
        val order = deserializeProviderOrder(orderString)
        return order.mapNotNull { getProviderByName(it) }
    }
}
