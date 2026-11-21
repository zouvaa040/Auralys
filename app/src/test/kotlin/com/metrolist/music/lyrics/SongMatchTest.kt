package com.metrolist.music.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KuGou and LrcLib answer with other songs of a similar length. These pin down when a hit counts as "the same song",
 * using the real case that put another song's words under "Hannah Montana".
 */
class SongMatchTest {
    @Test
    fun `another song of a similar length is not the same song`() {
        assertFalse(SongMatch.sameSong("We Can't Stop", "Miley Cyrus", "Hannah Montana", "Rex Orange County"))
    }

    @Test
    fun `the same title by another artist is not the same song`() {
        assertFalse(SongMatch.sameSong("Hannah Montana", "Someone Else", "Hannah Montana", "Rex Orange County"))
    }

    @Test
    fun `the same song matches`() {
        assertTrue(SongMatch.sameSong("Hannah Montana", "Rex Orange County", "Hannah Montana", "Rex Orange County"))
    }

    @Test
    fun `punctuation and case do not matter`() {
        assertTrue(SongMatch.sameSong("Take Me Home,Country Roads", "John Denver", "take me home, country roads", "JOHN DENVER"))
    }

    @Test
    fun `a title with an extra suffix still matches`() {
        assertTrue(SongMatch.sameSong("Sunflower (Spider-Man: Into the Spider-Verse)", "Post Malone", "Sunflower", "Post Malone"))
    }

    @Test
    fun `one of several artists is enough in either direction`() {
        assertTrue(SongMatch.sameArtist("Post Malone, Swae Lee", "Post Malone"))
        assertTrue(SongMatch.sameArtist("Swae Lee", "Post Malone, Swae Lee"))
        assertTrue(SongMatch.sameArtist("Post Malone & Swae Lee", "Swae Lee"))
        // simplified and traditional script of the same singer, with the same Latin name (live probe 2026-11-12)
        assertTrue(SongMatch.sameArtist("G.E.M. 邓紫棋", "G.E.M. 鄧紫棋"))
        assertTrue(SongMatch.sameArtist("G.E.M.邓紫棋、王力宏", "G.E.M. 鄧紫棋"))
        org.junit.Assert.assertFalse(SongMatch.sameArtist("A-Lin 黄丽玲", "G.E.M. 鄧紫棋"))
        org.junit.Assert.assertFalse(SongMatch.sameArtist("周杰伦", "林俊杰"))
    }

    @Test
    fun `names in different scripts are not held against each other`() {
        // KuGou often has 周杰伦 where the player has Jay Chou: text cannot tell them apart, so the length decides
        assertTrue(SongMatch.sameSong("青花瓷", "周杰伦", "Qing Hua Ci", "Jay Chou"))
    }

    @Test
    fun `a source that gives no names cannot be judged and is kept`() {
        assertTrue(SongMatch.sameSong("", "", "Hannah Montana", "Rex Orange County"))
        assertTrue(SongMatch.sameSong(null, null, "Hannah Montana", "Rex Orange County"))
    }

    @Test
    fun `non Latin titles in the same script are compared`() {
        assertTrue(SongMatch.sameTitle("青花瓷", "青花瓷"))
        assertFalse(SongMatch.sameTitle("夜曲", "青花瓷"))
    }
}
