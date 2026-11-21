/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import com.metrolist.music.BuildConfig

/**
 * How Auralys introduces itself to the services it calls (Last.fm, OpenRouter). Both of them credit traffic to the app
 * that sends this, so it has to be Auralys and not the project it was built on.
 */
object AuralysIdentity {
    const val NAME = "Auralys"

    /** A page where those services can find out who is behind the app. */
    const val URL = "https://t.me/ryuzinetshh"

    val userAgent: String
        get() = "$NAME/${BuildConfig.BASE_VERSION_NAME} ($URL)"
}
