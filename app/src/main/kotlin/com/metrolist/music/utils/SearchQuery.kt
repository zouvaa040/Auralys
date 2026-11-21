/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

/**
 * Turns what the user typed into a SQL LIKE pattern that matches it literally anywhere:
 * `%`, `_` and `\` in the text are escaped (use together with `ESCAPE '\'`), blanks around it are trimmed.
 * Returns null for a blank query so callers can skip the database.
 */
fun likePatternOrNull(query: String): String? {
    val text = query.trim()
    if (text.isEmpty()) return null
    val escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    return "%$escaped%"
}
