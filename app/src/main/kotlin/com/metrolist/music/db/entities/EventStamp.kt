/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db.entities

import java.time.LocalDateTime

/** The song and the moment of one play event, nothing else: what the merge of the account history needs to compare. */
data class EventStamp(
    val songId: String,
    val timestamp: LocalDateTime,
)
