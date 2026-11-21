/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Which drawable is drawn with which vector in the "Bulat" icon family. Pure data, no Compose state, so a unit test
 * can check it. A drawable that is not in the table keeps its normal drawable (brand logos, launcher shortcuts,
 * the Dynamic Island glyphs, ...). Vectors come from Material Icons Rounded (Apache-2.0) or from [BulatIcons].
 */

package com.metrolist.music.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Explicit
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material.icons.rounded.LinearScale
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreTime
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOn
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.RepeatOneOn
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.ShuffleOn
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Token
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeMute
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.ui.graphics.vector.ImageVector
import com.metrolist.music.R

/** One drawable and the factory of its Bulat vector. The factories return cached instances. */
class BulatEntry(
    @DrawableRes val resId: Int,
    val vector: () -> ImageVector,
)

object BulatIconTable {
    private fun e(
        @DrawableRes id: Int,
        vector: () -> ImageVector,
    ) = BulatEntry(id, vector)

    val entries: List<BulatEntry> =
        listOf(
            // Tabs and navigation
            e(R.drawable.home_outlined) { BulatIcons.Home },
            e(R.drawable.home_filled) { BulatIcons.HomeFilled },
            e(R.drawable.explore_outlined) { BulatIcons.Explore },
            e(R.drawable.library_music) { BulatIcons.Library },
            e(R.drawable.library_music_outlined) { BulatIcons.Library },
            e(R.drawable.library_music_filled) { BulatIcons.LibraryFilled },
            e(R.drawable.group) { Icons.Rounded.Group },
            e(R.drawable.group_outlined) { BulatIcons.GroupOutline },
            e(R.drawable.group_filled) { Icons.Rounded.Group },
            e(R.drawable.group_add) { Icons.Rounded.GroupAdd },
            e(R.drawable.search) { BulatIcons.Search },
            e(R.drawable.search_off) { Icons.Rounded.SearchOff },
            e(R.drawable.manage_search) { Icons.Rounded.ManageSearch },
            e(R.drawable.settings) { Icons.Rounded.Settings },
            e(R.drawable.person) { Icons.Rounded.Person },
            e(R.drawable.artist) { Icons.Rounded.RecordVoiceOver },
            e(R.drawable.account) { Icons.Rounded.AccountCircle },
            e(R.drawable.arrow_back) { Icons.AutoMirrored.Rounded.ArrowBack },
            e(R.drawable.arrow_forward) { Icons.AutoMirrored.Rounded.ArrowForward },
            e(R.drawable.arrow_upward) { Icons.Rounded.ArrowUpward },
            e(R.drawable.arrow_downward) { Icons.Rounded.ArrowDownward },
            e(R.drawable.arrow_top_left) { Icons.Rounded.NorthWest },
            e(R.drawable.navigate_next) { BulatIcons.ChevronRight },
            e(R.drawable.expand_less) { Icons.Rounded.ExpandLess },
            e(R.drawable.expand_more) { Icons.Rounded.ExpandMore },
            // Player controls
            e(R.drawable.play) { BulatIcons.Play },
            e(R.drawable.pause) { Icons.Rounded.Pause },
            e(R.drawable.skip_next) { Icons.Rounded.SkipNext },
            e(R.drawable.skip_previous) { Icons.Rounded.SkipPrevious },
            e(R.drawable.fast_forward) { Icons.Rounded.FastForward },
            e(R.drawable.replay) { Icons.Rounded.Replay },
            e(R.drawable.shuffle) { Icons.Rounded.Shuffle },
            e(R.drawable.shuffle_on) { Icons.Rounded.ShuffleOn },
            e(R.drawable.repeat) { Icons.Rounded.Repeat },
            e(R.drawable.repeat_on) { Icons.Rounded.RepeatOn },
            e(R.drawable.repeat_one) { Icons.Rounded.RepeatOne },
            e(R.drawable.repeat_one_on) { Icons.Rounded.RepeatOneOn },
            e(R.drawable.favorite) { BulatIcons.HeartFilled },
            e(R.drawable.favorite_border) { BulatIcons.Heart },
            e(R.drawable.queue_music) { BulatIcons.Queue },
            e(R.drawable.lyrics) { BulatIcons.Lyrics },
            e(R.drawable.playlist_add) { Icons.AutoMirrored.Rounded.PlaylistAdd },
            e(R.drawable.playlist_play) { Icons.AutoMirrored.Rounded.PlaylistPlay },
            e(R.drawable.volume_up) { Icons.Rounded.VolumeUp },
            e(R.drawable.volume_down) { Icons.Rounded.VolumeDown },
            e(R.drawable.volume_mute) { Icons.Rounded.VolumeMute },
            e(R.drawable.volume_off) { Icons.Rounded.VolumeOff },
            e(R.drawable.timer) { Icons.Rounded.Timer },
            e(R.drawable.more_time) { Icons.Rounded.MoreTime },
            e(R.drawable.bedtime) { Icons.Rounded.Bedtime },
            e(R.drawable.speed) { Icons.Rounded.Speed },
            e(R.drawable.slow_motion_video) { Icons.Rounded.SlowMotionVideo },
            e(R.drawable.equalizer) { Icons.Rounded.Equalizer },
            e(R.drawable.graphic_eq) { Icons.Rounded.GraphicEq },
            e(R.drawable.tune) { Icons.Rounded.Tune },
            e(R.drawable.sliders) { Icons.Rounded.Tune },
            e(R.drawable.discover_tune) { Icons.Rounded.AutoFixHigh },
            e(R.drawable.cast) { Icons.Rounded.Cast },
            e(R.drawable.cast_connected) { Icons.Rounded.CastConnected },
            e(R.drawable.bluetooth) { Icons.Rounded.Bluetooth },
            e(R.drawable.fullscreen) { Icons.Rounded.Fullscreen },
            e(R.drawable.fullscreen_exit) { Icons.Rounded.FullscreenExit },
            e(R.drawable.music_note) { BulatIcons.MusicNote },
            e(R.drawable.album) { Icons.Rounded.Album },
            e(R.drawable.radio) { Icons.Rounded.Radio },
            e(R.drawable.mic) { Icons.Rounded.Mic },
            // Row and menu actions
            e(R.drawable.add) { BulatIcons.Plus },
            e(R.drawable.add_circle) { Icons.Rounded.AddCircleOutline },
            e(R.drawable.remove) { Icons.Rounded.Remove },
            e(R.drawable.close) { Icons.Rounded.Close },
            e(R.drawable.check) { BulatIcons.Check },
            e(R.drawable.done) { BulatIcons.Check },
            e(R.drawable.download) { BulatIcons.Download },
            e(R.drawable.upload) { Icons.Rounded.FileUpload },
            e(R.drawable.delete) { Icons.Rounded.Delete },
            e(R.drawable.delete_history) { Icons.Rounded.DeleteSweep },
            e(R.drawable.clear_all) { Icons.Rounded.ClearAll },
            e(R.drawable.edit) { Icons.Rounded.Edit },
            e(R.drawable.share) { Icons.Rounded.Share },
            e(R.drawable.more_vert) { Icons.Rounded.MoreVert },
            e(R.drawable.more_horiz) { Icons.Rounded.MoreHoriz },
            e(R.drawable.content_copy) { Icons.Rounded.ContentCopy },
            e(R.drawable.link) { Icons.Rounded.Link },
            e(R.drawable.select_all) { Icons.Rounded.SelectAll },
            e(R.drawable.library_add) { Icons.Rounded.LibraryAdd },
            e(R.drawable.library_add_check) { Icons.Rounded.LibraryAddCheck },
            e(R.drawable.drag_handle) { Icons.Rounded.DragHandle },
            e(R.drawable.list) { BulatIcons.ListView },
            e(R.drawable.grid_view) { BulatIcons.GridView },
            e(R.drawable.refresh) { Icons.Rounded.Refresh },
            e(R.drawable.sync) { Icons.Rounded.Sync },
            e(R.drawable.restore) { Icons.Rounded.Restore },
            e(R.drawable.history) { Icons.Rounded.History },
            e(R.drawable.subscribe) { Icons.Rounded.Notifications },
            e(R.drawable.subscribed) { Icons.Rounded.NotificationsActive },
            // Settings and status
            e(R.drawable.language) { Icons.Rounded.Language },
            e(R.drawable.translate) { Icons.Rounded.Translate },
            e(R.drawable.language_korean_latin) { Icons.Rounded.Translate },
            e(R.drawable.palette) { Icons.Rounded.Palette },
            e(R.drawable.contrast) { Icons.Rounded.Contrast },
            e(R.drawable.gradient) { Icons.Rounded.Gradient },
            e(R.drawable.notification) { Icons.Rounded.Notifications },
            e(R.drawable.storage) { Icons.Rounded.Storage },
            e(R.drawable.backup) { Icons.Rounded.Backup },
            e(R.drawable.cached) { Icons.Rounded.CloudDone },
            e(R.drawable.offline) { Icons.Rounded.WifiOff },
            e(R.drawable.security) { Icons.Rounded.Security },
            e(R.drawable.lock) { Icons.Rounded.Lock },
            e(R.drawable.lock_open) { Icons.Rounded.LockOpen },
            e(R.drawable.key) { Icons.Rounded.Key },
            e(R.drawable.info) { Icons.Rounded.Info },
            e(R.drawable.warning) { Icons.Rounded.Warning },
            e(R.drawable.error) { Icons.Rounded.Error },
            e(R.drawable.update) { Icons.Rounded.Update },
            e(R.drawable.login) { Icons.AutoMirrored.Rounded.Login },
            e(R.drawable.logout) { Icons.AutoMirrored.Rounded.Logout },
            e(R.drawable.stats) { Icons.Rounded.BarChart },
            e(R.drawable.trending_up) { Icons.Rounded.TrendingUp },
            e(R.drawable.explicit) { Icons.Rounded.Explicit },
            e(R.drawable.hide_image) { Icons.Rounded.HideImage },
            e(R.drawable.insert_photo) { Icons.Rounded.Image },
            e(R.drawable.crop) { Icons.Rounded.Crop },
            e(R.drawable.crown) { Icons.Rounded.WorkspacePremium },
            e(R.drawable.token) { Icons.Rounded.Token },
            e(R.drawable.swipe) { Icons.Rounded.Swipe },
            e(R.drawable.similar) { Icons.Rounded.Layers },
            e(R.drawable.tab) { Icons.Rounded.Tab },
            e(R.drawable.linear_scale) { Icons.Rounded.LinearScale },
            e(R.drawable.location_on) { Icons.Rounded.LocationOn },
            e(R.drawable.radio_button_checked) { Icons.Rounded.RadioButtonChecked },
            e(R.drawable.radio_button_unchecked) { Icons.Rounded.RadioButtonUnchecked },
            // Dynamic Island glyphs (filled and outline), same shapes as the player controls
            e(R.drawable.ic_island_previous) { Icons.Rounded.SkipPrevious },
            e(R.drawable.ic_island_previous_outline) { BulatIcons.SkipPreviousOutline },
            e(R.drawable.ic_island_play) { BulatIcons.Play },
            e(R.drawable.ic_island_play_outline) { BulatIcons.PlayOutline },
            e(R.drawable.ic_island_pause) { Icons.Rounded.Pause },
            e(R.drawable.ic_island_pause_outline) { BulatIcons.PauseOutline },
            e(R.drawable.ic_island_next) { Icons.Rounded.SkipNext },
            e(R.drawable.ic_island_next_outline) { BulatIcons.SkipNextOutline },
            // Notification actions and the like icons of the system surfaces
            e(R.drawable.ic_heart) { BulatIcons.HeartFilled },
            e(R.drawable.ic_heart_outline) { BulatIcons.Heart },
            // Settings rows and menu rows that had no Bulat glyph yet
            e(R.drawable.baseline_event_repeat_24) { Icons.Rounded.EventRepeat },
            e(R.drawable.export_playlist) { BulatIcons.Export },
            e(R.drawable.ic_android_auto) { Icons.Rounded.DirectionsCar },
            e(R.drawable.ic_dynamic_island) { BulatIcons.DynamicIsland },
            e(R.drawable.ic_push_pin) { Icons.Rounded.PushPin },
            e(R.drawable.ic_streak_flame) { Icons.Rounded.LocalFireDepartment },
            e(R.drawable.media_bookmark) { Icons.Rounded.Bookmark },
            e(R.drawable.media_feed) { Icons.Rounded.RssFeed },
            e(R.drawable.thumb_down) { Icons.Rounded.ThumbDown },
            e(R.drawable.thumb_up) { Icons.Rounded.ThumbUp },
            e(R.drawable.nav_bar) { BulatIcons.NavBar },
            e(R.drawable.screenshot) { Icons.Rounded.Screenshot },
            e(R.drawable.time_auto) { Icons.Rounded.Schedule },
            e(R.drawable.timer_arrow_down) { Icons.Rounded.HourglassBottom },
            e(R.drawable.volume_off_pause) { BulatIcons.VolumeOffPause },
            e(R.drawable.wifi_proxy) { Icons.Rounded.Lan },
        )

    private val byId: Map<Int, BulatEntry> = entries.associateBy { it.resId }

    /** The Bulat vector for [id], or null when the drawable has no Bulat twin. */
    fun vectorFor(
        @DrawableRes id: Int,
    ): ImageVector? = byId[id]?.vector?.invoke()
}
