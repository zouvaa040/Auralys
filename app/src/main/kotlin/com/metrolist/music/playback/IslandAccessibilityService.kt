/**
 * Auralys - the optional window host that puts the Dynamic Island in front of the status bar
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Android draws an app overlay (TYPE_APPLICATION_OVERLAY) below the status bar, so the island cannot be touched where it
 * overlaps it. The only window type a normal app can place above the status bar is an accessibility overlay, which needs
 * an accessibility service. This service is only that: a place to put the window. It asks for no events and no window
 * content (see res/xml/island_accessibility_service.xml), reads nothing from the screen and does nothing to other apps.
 * It is used only when the user turns on "In front of the status bar" in the island settings and enables the service.
 */
class IslandAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        IslandAccessibilityHost.service.value = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (IslandAccessibilityHost.service.value === this) IslandAccessibilityHost.service.value = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (IslandAccessibilityHost.service.value === this) IslandAccessibilityHost.service.value = null
        super.onDestroy()
    }
}

/** The connected service, or null while the user has not enabled it. Read by the island controller of the music service. */
object IslandAccessibilityHost {
    val service = MutableStateFlow<AccessibilityService?>(null)
}
