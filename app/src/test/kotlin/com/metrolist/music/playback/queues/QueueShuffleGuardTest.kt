/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps the 4.3.4 shuffle fixes from being undone by a later edit: reads the sources (the module folder is the working
 * directory of unit tests).
 */
class QueueShuffleGuardTest {
    private val root: File =
        listOf("src/main/kotlin", "app/src/main/kotlin")
            .map { File(it, "com/metrolist/music") }
            .firstOrNull { it.isDirectory }
            ?: error("sources not found from ${File(".").absolutePath}")

    private val res: File =
        listOf("src/main/res", "app/src/main/res").map(::File).firstOrNull { it.isDirectory } ?: error("res not found")

    private fun src(path: String) = File(root, path).readText()

    @Test
    fun `no shuffle button pre-shuffles its list any more`() {
        // a pre-shuffled list plays with the player's shuffle OFF: repeat all replays the same order, a restart kept
        // only part of it. "Acak" is ListQueue.shuffled (player shuffle on, a random first song).
        val pattern = Regex("""items\s*=\s*[^\n]*\.shuffled\(\)""")
        val offenders =
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.path.replace('\\', '/').contains("/ui/") }
                .filter { pattern.containsMatchIn(it.readText()) }
                .map { it.name }
                .toList()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `the service never sets a DefaultShuffleOrder and reads the same defaults as the settings`() {
        val service = src("playback/MusicService.kt")
        assertFalse("DefaultShuffleOrder( in MusicService", service.contains("DefaultShuffleOrder("))
        assertTrue(service.contains("dataStore.get(PersistentShuffleAcrossQueuesKey, QueueDefaults.PERSISTENT_SHUFFLE)"))
        assertTrue(service.contains("PlayerShuffle.newPassIfLast(player)"))
        assertTrue("load-more counts the PLAY order", service.contains("ShuffleOrders.remainingAfter(player, limit = 5)"))
        assertFalse("the old window", service.contains("PersistQueueWindow.range("))
        val settings = src("ui/screens/settings/PlayerSettings.kt")
        assertTrue(settings.contains("defaultValue = QueueDefaults.PERSISTENT_SHUFFLE"))
        assertTrue(settings.contains("defaultValue = QueueDefaults.AUTOPLAY_NO_REPEAT"))
        assertTrue(QueueDefaults.PERSISTENT_SHUFFLE)
        assertTrue(QueueDefaults.AUTOPLAY_NO_REPEAT)
    }

    @Test
    fun `the queue is unlocked by default and played songs can be removed`() {
        val queue = src("ui/player/Queue.kt")
        assertTrue(queue.contains("rememberPreference(QueueEditLockKey, defaultValue = QueueDefaults.QUEUE_LOCKED)"))
        assertFalse(QueueDefaults.QUEUE_LOCKED)
        assertTrue(queue.contains("PlayerShuffle.removePlayed("))
        assertTrue(queue.contains("onRemoveFromQueue ="))
        assertFalse("DefaultShuffleOrder( in Queue.kt", queue.contains("DefaultShuffleOrder("))
        assertFalse(src("ui/player/QueueSwipeLogic.kt").contains("DefaultShuffleOrder("))
    }

    @Test
    fun `the new strings exist in English and Indonesian, plain words`() {
        val en = File(res, "values/auralys_queue_434.xml").readText()
        val id = File(res, "values-in/auralys_queue_434.xml").readText()
        for (name in listOf("auralys_autoplay_no_repeat", "auralys_queue_remove_played", "auralys_queue_remove_played_message", "auralys_queue_remove_played_confirm", "auralys_queue_removed_played")) {
            assertTrue("$name missing (en)", en.contains("name=\"$name\""))
            assertTrue("$name missing (in)", id.contains("name=\"$name\""))
        }
        // whole words: "sudah" is right, "udah" is slang
        for (slang in listOf("nggak", "udah", "gak", "kalo", "bikin")) {
            assertFalse(slang, Regex("""(?i)\b$slang\b""").containsMatchIn(id))
        }
        assertTrue(src("ui/screens/settings/SettingsSearchIndex.kt").contains("R.string.auralys_autoplay_no_repeat"))
    }
}
