/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Review 2026-11-12 (fix round P). MusicService cannot be created in a unit test, so this pins in its source the parts
 * of the fixes that live there; the rules themselves are tested in PlaybackRecoveryTest, LoadBudgetTest,
 * ExtractionGateTest, PersistFilesTest, PageFillTest and SuggestionFilterTest.
 */
class MusicServiceFixPGuardTest {
    private val source by lazy {
        listOf("src/main/kotlin", "app/src/main/kotlin")
            .map { File(it, "com/metrolist/music/playback/MusicService.kt") }
            .first { it.isFile }
            .readText()
            .replace("\r\n", "\n")
    }

    private fun between(
        start: String,
        end: String,
    ): String {
        val a = source.indexOf(start)
        assertTrue("'$start' not found", a >= 0)
        val b = source.indexOf(end, a + start.length)
        assertTrue("'$end' not found after '$start'", b > a)
        return source.substring(a, b)
    }

    @Test
    fun `finding 3 - every saved playback file is written atomically, none in place`() {
        val writers = between("private fun writeQueueSnapshot(", "override fun onDestroy()")
        assertFalse("no file is truncated and rewritten in place any more", writers.contains(".outputStream()"))
        assertTrue(writers.contains("PersistFiles.write(queueFile, queue, sync)"))
        assertTrue(writers.contains("PersistFiles.write(filesDir.resolve(PERSISTENT_AUTOMIX_FILE)"))
        assertTrue(writers.contains("PersistFiles.write(filesDir.resolve(PERSISTENT_PLAYER_STATE_FILE)"))
        assertTrue(writers.contains("cursorFile,"))
        assertFalse(source.contains("ObjectInputStream("))
    }

    @Test
    fun `finding 3 - a broken file drops only itself, not every saved file`() {
        val reader = between("private fun readPersistedPlayback(): RestoredPlayback {", "private fun applyRestoredPlayback(")
        assertFalse(reader.contains("clearPersistedQueueFiles()"))
        assertTrue(reader.contains("dropPersisted(PERSISTENT_QUEUE_FILE, PERSISTENT_QUEUE_CURSOR_FILE)"))
        assertTrue(reader.contains("dropPersisted(PERSISTENT_AUTOMIX_FILE)"))
        assertTrue(reader.contains("dropPersisted(PERSISTENT_PLAYER_STATE_FILE)"))
        assertTrue(reader.contains("PersistFiles.sweepTemps("))
    }

    @Test
    fun `finding 23 - onCreate reads the saved queue on IO and applies it on the main thread, a tap wins`() {
        val onCreate = between("override fun onCreate() {", "private fun createExoPlayer(")
        assertFalse("no queue file is read inside onCreate", onCreate.contains("PERSISTENT_QUEUE_FILE"))
        assertTrue(onCreate.contains("withContext(Dispatchers.IO) { readPersistedPlayback() }"))
        assertTrue(onCreate.contains("applyRestoredPlayback(restored)"))
        val apply = between("private fun applyRestoredPlayback(", "private fun dropPersisted(")
        assertTrue(apply.contains("queuesStartedSinceCreate > 0 || player.mediaItemCount > 0"))
        val play = between("    fun playQueue(", "private var shuffleFillJob")
        assertTrue(play.contains("if (!restoringQueue) queuesStartedSinceCreate++"))
        assertTrue("an older queue's load must not land over a newer one", play.contains("if (currentQueue !== queue) return@launch"))
    }

    @Test
    fun `finding 20 - a new queue cancels the old queue's page load, shuffle fill and radio append`() {
        val play = between("    fun playQueue(", "private var shuffleFillJob")
        for (job in listOf("shuffleFillJob", "loadMoreJob", "radioAppendJob")) {
            assertTrue("$job must be cancelled", play.contains("$job?.cancel()"))
            assertTrue("$job must be cleared", play.contains("$job = null"))
        }
    }

    @Test
    fun `finding 31 - the shuffle fill backs off and stops after failed pages`() {
        val fill = between("private fun fillShuffledPlaylist() {", "private fun loadNextPage(")
        assertTrue(fill.contains("PageFill.run("))
        assertTrue(fill.contains("QueueDefaults.SHUFFLE_FILL_MAX_FAILURES"))
        assertFalse("the old loop counted a failed page as loaded", fill.contains("pages++"))
    }

    @Test
    fun `finding 26 - the suggestion filter runs off the main thread`() {
        val suggest = between("private suspend fun suggestionsToAdd(", "private suspend fun radioSuggestions(")
        val hop = suggest.indexOf("withContext(Dispatchers.Default)")
        assertTrue(hop >= 0)
        assertTrue("the filter must be inside the hop", suggest.indexOf("SuggestionFilter.filter(") > hop)
        assertTrue("the player is read before the hop", suggest.indexOf("player.getMediaItemAt") in 0 until hop)
    }

    @Test
    fun `finding 25 - the stream probe shares the media client's connection pool`() {
        val probe = between("private val streamProbe by lazy {", "/** The default network for the player")
        assertTrue(probe.contains("mediaHttpClient\n                .newBuilder()"))
        assertFalse("no client of its own", probe.contains("OkHttpClient\n"))
        val media = between("private fun createCacheDataSource()", "private var isSilenceSkipping")
        assertTrue(media.contains("OkHttpDataSource.Factory(mediaHttpClient)"))
    }

    @Test
    fun `finding 7 - the player's load control knows the network budget`() {
        val player = between("private fun createExoPlayer(", "/** Asks for the audio focus")
        assertTrue(player.contains("NetworkAwareLoadControl("))
        assertTrue(player.contains("wholeSong = ::wholeSongBuffering"))
        val whole = between("private fun wholeSongBuffering(): Boolean {", "\n    }\n")
        assertTrue(whole.contains("networkWatcher?.unmetered"))
        assertTrue(whole.contains("!(dataSaverOn && !dataSaverMobileOnly)"))
        assertFalse("no binder call on the playback thread", whole.contains("isActiveNetworkMetered"))
        // the watcher exists before the player that asks it
        val onCreate = between("override fun onCreate() {", "private fun createExoPlayer(")
        assertTrue(onCreate.indexOf("networkWatcher =") in 0 until onCreate.indexOf("player = createExoPlayer("))
    }

    @Test
    fun `finding 6 - connection errors while online retry on a validated network and resume after the stop`() {
        val apply = between("private fun applyRecovery(", "/** Drops the bytes of [mediaId]")
        assertTrue(apply.contains("if (decision.onValidatedNetwork) retryOnValidatedNetwork = retry"))
        assertTrue(apply.contains("UnreachableResume(mediaId)"))
        assertTrue(apply.contains("R.string.auralys_playback_stopped_unreachable"))
        val back = between("private fun onValidatedNetworkAppeared() {", "/** Runs the stall watchdog")
        assertTrue("only the same song", back.contains("current != resume.mediaId"))
        assertTrue("only while nobody pressed play", back.contains("player.playWhenReady"))
        val pwr = between("override fun onPlayWhenReadyChanged(", "/** The current and the next song")
        assertTrue("any later play/pause of the user cancels the resume", pwr.contains("unreachableResume = null"))
        assertTrue(source.contains("networkWatcher?.stop()"))
    }

    @Test
    fun `finding 19 - user seeks forward count as heard, the silence skip does not`() {
        val disc = between("override fun onPositionDiscontinuity(", "/** Video A: songs that")
        assertTrue(disc.contains("!isSilenceSkipping"))
        assertTrue(disc.contains("currentItemUserSeekForwardMs +="))
        assertTrue(source.contains("recovery.onSongEnded(durationMs, currentItemPlayedWallMs, player.playbackParameters.speed, currentItemUserSeekForwardMs)"))
        val transition = between("override fun onMediaItemTransition(", "if (skippingAfterFailure) {")
        assertTrue(transition.contains("currentItemUserSeekForwardMs = 0L"))
    }

    @Test
    fun `finding 27 - the extraction gate keeps only the current and the next song`() {
        val transition = between("override fun onMediaItemTransition(", "if (skippingAfterFailure) {")
        assertTrue(transition.contains("retainWantedExtractions()"))
        val retain = between("private fun retainWantedExtractions() {", "/**")
        assertTrue(retain.contains("extractionGate.retainOnly("))
        assertTrue(retain.contains("player.nextMediaItemIndex"))
    }
}
