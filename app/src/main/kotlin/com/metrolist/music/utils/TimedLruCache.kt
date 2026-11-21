/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0
 */

package com.metrolist.music.utils

import java.util.Collections

/** Small in-memory cache: at most [maxEntries] values (least recently used leave first), each valid for [ttlMs]. */
class TimedLruCache<K : Any, V : Any>(
    private val maxEntries: Int,
    private val ttlMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Stamped<V>(
        val value: V,
        val at: Long,
    )

    private val map: MutableMap<K, Stamped<V>> =
        Collections.synchronizedMap(
            object : LinkedHashMap<K, Stamped<V>>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Stamped<V>>?) = size > maxEntries
            },
        )

    operator fun get(key: K): V? {
        val entry = map[key] ?: return null
        if (now() - entry.at > ttlMs) {
            map.remove(key)
            return null
        }
        return entry.value
    }

    operator fun set(
        key: K,
        value: V,
    ) {
        map[key] = Stamped(value, now())
    }

    fun clear() = map.clear()

    val size: Int get() = map.size
}

/**
 * Stores [value] only when it is [complete]. An answer that is partial or failed (a metadata call that did not finish)
 * must not be served as the real thing for the whole lifetime of the cache. Returns whether it was stored.
 */
fun <K : Any, V : Any> TimedLruCache<K, V>.putIfComplete(
    key: K,
    value: V,
    complete: Boolean,
): Boolean {
    if (!complete) return false
    this[key] = value
    return true
}
