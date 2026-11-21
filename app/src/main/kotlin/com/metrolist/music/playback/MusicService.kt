/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:Suppress("DEPRECATION")

package com.metrolist.music.playback

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.SQLException
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import com.metrolist.music.playback.audio.LoudnessGain
import com.metrolist.music.playback.audio.ReverbAudioProcessor
import com.metrolist.music.ui.component.SystemIcons
import com.metrolist.music.playback.audio.SoundVibe
import com.metrolist.music.playback.audio.soundVibeNeedsSoftwareSpeed
import com.metrolist.music.playback.audio.VolumeNormalizationAudioProcessor
import com.metrolist.music.utils.ListeningStreakManager
import com.metrolist.music.utils.safeDataStoreEdit
import android.net.ConnectivityManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.Preferences
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_POSITION_DISCONTINUITY
import androidx.media3.common.Player.EVENT_TIMELINE_CHANGED
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Player.STATE_IDLE
import androidx.media3.common.Timeline
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.MoreExecutors
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.lastfm.LastFM
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import com.metrolist.music.constants.AddToPlaylistPosition
import com.metrolist.music.constants.AddToPlaylistPositionKey
import com.metrolist.music.constants.AndroidAutoTargetPlaylistKey
import com.metrolist.music.constants.AudioNormalizationKey
import com.metrolist.music.constants.AudioOffload
import com.metrolist.music.constants.AudioQualityKey
import com.metrolist.music.constants.AudioTrackPlaybackParamsKey
import com.metrolist.music.constants.AutoDownloadOnLikeKey
import com.metrolist.music.constants.AutoLoadMoreKey
import com.metrolist.music.constants.AutoSkipNextOnErrorKey
import com.metrolist.music.constants.AutoplayKey
import com.metrolist.music.constants.CrossfadeDurationKey
import com.metrolist.music.constants.CrossfadeEnabledKey
import com.metrolist.music.constants.CrossfadeGaplessKey
import com.metrolist.music.constants.DisableLoadMoreWhenRepeatAllKey
import com.metrolist.music.constants.DiscordActivityNameKey
import com.metrolist.music.constants.DiscordActivityTypeKey
import com.metrolist.music.constants.DiscordAdvancedModeKey
import com.metrolist.music.constants.DiscordButton1EnabledKey
import com.metrolist.music.constants.DiscordButton1LabelKey
import com.metrolist.music.constants.DiscordButton1UrlKey
import com.metrolist.music.constants.DiscordButton2EnabledKey
import com.metrolist.music.constants.DiscordButton2LabelKey
import com.metrolist.music.constants.DiscordButton2UrlKey
import com.metrolist.music.constants.DiscordDetailsTemplateKey
import com.metrolist.music.constants.DiscordStateTemplateKey
import com.metrolist.music.constants.DiscordUserStatusKey
import com.metrolist.music.constants.EnableDiscordRPCKey
import com.metrolist.music.discord.DiscordActivity
import com.metrolist.music.discord.DiscordDefaults
import com.metrolist.music.discord.DiscordRpcManager
import com.metrolist.music.discord.DiscordActivityBuilder
import com.metrolist.music.discord.DiscordTemplateRenderer
import com.metrolist.music.discord.PresenceStatus
import com.metrolist.music.constants.EnableLastFMScrobblingKey
import com.metrolist.music.constants.EnableSongCacheKey
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.HistoryDuration
import com.metrolist.music.constants.LastFMUseNowPlaying
import com.metrolist.music.constants.MediaSessionConstants
import com.metrolist.music.constants.MediaSessionConstants.CommandAddToTargetPlaylist
import com.metrolist.music.constants.MediaSessionConstants.CommandToggleLike
import com.metrolist.music.constants.MediaSessionConstants.CommandToggleRepeatMode
import com.metrolist.music.constants.MediaSessionConstants.CommandToggleShuffle
import com.metrolist.music.constants.MediaSessionConstants.CommandToggleStartRadio
import com.metrolist.music.constants.PauseListenHistoryKey
import com.metrolist.music.constants.PauseOnMute
import com.metrolist.music.constants.PersistentQueueKey
import com.metrolist.music.constants.PersistentShuffleAcrossQueuesKey
import com.metrolist.music.constants.PlayerVolumeKey
import com.metrolist.music.constants.PreventDuplicateTracksInQueueKey
import com.metrolist.music.constants.RememberShuffleAndRepeatKey
import com.metrolist.music.constants.RepeatModeKey
import com.metrolist.music.constants.KeepPlayingOnFocusLossKey
import com.metrolist.music.constants.ResumeOnBluetoothConnectKey
import com.metrolist.music.constants.ScrobbleDelayPercentKey
import com.metrolist.music.constants.ScrobbleDelaySecondsKey
import com.metrolist.music.constants.ScrobbleMinSongDurationKey
import com.metrolist.music.constants.ShowLyricsKey
import com.metrolist.music.constants.ShuffleModeKey
import com.metrolist.music.constants.ShufflePlaylistFirstKey
import com.metrolist.music.constants.SimilarContent
import com.metrolist.music.constants.SkipSilenceInstantKey
import com.metrolist.music.utils.OfflineArtworkStore
import com.metrolist.music.constants.SkipSilenceKey
import com.metrolist.music.constants.SoundVibeKey
import com.metrolist.music.constants.StopMusicOnTaskClearKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Event
import com.metrolist.music.db.entities.FormatEntity
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.RelatedSongMap
import com.metrolist.music.db.entities.Song
import com.metrolist.music.di.DownloadCache
import com.metrolist.music.di.PlayerCache
import com.metrolist.music.eq.EqualizerService
import com.metrolist.music.eq.audio.CustomEqualizerAudioProcessor
import com.metrolist.music.eq.data.EQProfileRepository
import com.metrolist.music.extensions.SilentHandler
import com.metrolist.music.extensions.collect
import com.metrolist.music.extensions.collectLatest
import com.metrolist.music.extensions.currentMetadata
import com.metrolist.music.extensions.findNextMediaItemById
import com.metrolist.music.extensions.mediaItems
import com.metrolist.music.extensions.metadata
import com.metrolist.music.extensions.setOffloadEnabled
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.extensions.toPersistQueue
import com.metrolist.music.extensions.toQueue
import com.metrolist.music.lyrics.LyricsHelper
import com.metrolist.music.models.PersistPlayerState
import com.metrolist.music.models.PersistQueue
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.alarm.MusicAlarmScheduler
import com.metrolist.music.playback.alarm.MusicAlarmStore
import com.metrolist.music.playback.audio.SilenceDetectorAudioProcessor
import com.metrolist.music.playback.queues.EmptyQueue
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.Queue
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.playback.queues.YouTubePlaylistQueue
import com.metrolist.music.playback.queues.filterExplicit
import com.metrolist.music.playback.queues.filterVideoSongs
// 4.3.4 queue / shuffle / autoplay (B)
import com.metrolist.music.constants.AutoplayNoRepeatKey
import com.metrolist.music.playback.queues.PageFill
import com.metrolist.music.playback.queues.PersistQueueCursor
import com.metrolist.music.playback.queues.PlayerShuffle
import com.metrolist.music.playback.queues.QueueDefaults
import com.metrolist.music.playback.queues.QueueSignature
import com.metrolist.music.playback.queues.RecentPlays
import com.metrolist.music.playback.queues.ShuffleOrders
import com.metrolist.music.playback.queues.SuggestionFilter
import com.metrolist.music.playback.queues.pagesAreSuggestions
import com.metrolist.music.constants.LoudnessLevel
import com.metrolist.music.constants.LoudnessLevelKey
import com.metrolist.music.utils.CoilBitmapLoader
import com.metrolist.music.utils.NetworkConnectivityObserver
import com.metrolist.music.utils.ScrobbleManager
import com.metrolist.music.utils.SyncUtils
import com.metrolist.music.utils.getArtistSeparator
import com.metrolist.music.utils.joinToArtistString
import com.metrolist.music.local.DeviceAwareDataSourceFactory
import com.metrolist.music.local.DeviceAwareExtractorsFactory
import com.metrolist.music.local.LocalMusic
import com.metrolist.music.utils.InnerTubeXPlayer
import com.metrolist.music.utils.AlternativeMatch
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.reportException
import com.metrolist.music.widget.AuralysWidgetManager
import com.metrolist.music.widget.AuralysWidgetReceiver
import com.metrolist.music.ui.utils.resize
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import java.time.LocalDateTime
import javax.inject.Inject
import kotlin.random.Random
import java.util.Collections

private const val INSTANT_SILENCE_SKIP_STEP_MS = 15_000L
private const val INSTANT_SILENCE_SKIP_SETTLE_MS = 350L

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@androidx.annotation.OptIn(UnstableApi::class)
@AndroidEntryPoint
class MusicService :
    MediaLibraryService(),
    Player.Listener,
    PlaybackStatsListener.Callback {
    @Inject
    lateinit var database: MusicDatabase

    @Inject
    lateinit var lyricsHelper: LyricsHelper

    @Inject
    lateinit var syncUtils: SyncUtils

    @Inject
    lateinit var downloadUtil: DownloadUtil

    @Inject
    lateinit var mediaLibrarySessionCallback: MediaLibrarySessionCallback

    @Inject
    lateinit var equalizerService: EqualizerService

    @Inject
    lateinit var listeningStreakManager: ListeningStreakManager

    @Inject
    lateinit var eqProfileRepository: EQProfileRepository

    @Inject
    lateinit var widgetManager: AuralysWidgetManager

    @Inject
    lateinit var listenTogetherManager: com.metrolist.music.listentogether.ListenTogetherManager

    private lateinit var audioManager: AudioManager

    // Audio focus (pause on a call, duck on a notification, resume afterwards): AudioFocusCoordinator + VolumeDuckGuard.
    private var audioFocusCoordinator: AudioFocusCoordinator? = null
    private var wasPlayingBeforeAudioFocusLoss: Boolean
        get() = audioFocusCoordinator?.wasPlayingBeforeLoss == true
        set(value) {
            audioFocusCoordinator?.wasPlayingBeforeLoss = value
        }
    private val hasAudioFocus: Boolean
        get() = audioFocusCoordinator?.hasFocus == true
    private var wasPlayingBeforeVolumeMute = false
    private var isPausedByVolumeMute = false

    private var crossfadeEnabled = false
    private var crossfadeDuration = 5000f
    private var crossfadeGapless = true
    private var crossfadeMessage: PlayerMessage? = null

    private val secondaryPlayerListener =
        object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Timber.tag(TAG).e(error, "Secondary player error")
                secondaryPlayer?.let { failedPlayer ->
                    failedPlayer.removeListener(this)
                    failedPlayer.stop()
                    failedPlayer.clearMediaItems()
                    releaseExoPlayer(failedPlayer)
                }
                secondaryPlayer = null
            }
        }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Auralys: Dynamic Island overlay shown over other apps while playing in background.
    private var dynamicIsland: DynamicIslandController? = null
    private var lockScreenLyrics: LockScreenLyricsController? = null

    private val binder = MusicBinder()

    inner class MusicBinder : Binder() {
        val service: MusicService
            get() = this@MusicService
    }

    private lateinit var connectivityManager: ConnectivityManager
    lateinit var connectivityObserver: NetworkConnectivityObserver
    val waitingForNetworkConnection = MutableStateFlow(false)
    private val isNetworkConnected = MutableStateFlow(false)
    val currentStreamClient = MutableStateFlow<String?>(null)

    private lateinit var audioQuality: com.metrolist.music.constants.AudioQuality

    // Data saver (settings): while it applies, streams are fetched at low quality whatever the audio quality setting says.
    @Volatile private var dataSaverOn = false
    @Volatile private var dataSaverMobileOnly = true

    private fun dataSaverActive(): Boolean =
        dataSaverOn && (!dataSaverMobileOnly || connectivityManager.isActiveNetworkMetered)

    /**
     * Playback thread, at every load decision: the whole song may be buffered only on an unmetered network and without the
     * app's data saver for every network (on a metered network the moderate budget applies anyway). No binder call here:
     * the watcher keeps the flag from its network callback (review 2026-11-12, finding 7).
     */
    private fun wholeSongBuffering(): Boolean {
        val unmetered = networkWatcher?.unmetered ?: false
        return unmetered && !(dataSaverOn && !dataSaverMobileOnly)
    }

    /** The quality used for the next stream that is fetched (a song that is already playing keeps its quality). */
    private val streamQuality: com.metrolist.music.constants.AudioQuality
        get() = if (dataSaverActive()) com.metrolist.music.constants.AudioQuality.LOW else audioQuality

    private var currentQueue: Queue = EmptyQueue
    var queueTitle: String? = null

    val currentMediaMetadata = MutableStateFlow<com.metrolist.music.models.MediaMetadata?>(null)
    private val currentSong =
        currentMediaMetadata
            .flatMapLatest { mediaMetadata ->
                database.song(mediaMetadata?.id)
            }.stateIn(scope, SharingStarted.Lazily, null)
    private val currentFormat =
        currentMediaMetadata.flatMapLatest { mediaMetadata ->
            database.format(mediaMetadata?.id)
        }

    lateinit var playerVolume: MutableStateFlow<Float>
    val isMuted = MutableStateFlow(false)
    private val sleepTimerVolumeMultiplier = MutableStateFlow(1f)
    private val audioFocusVolumeMultiplier = MutableStateFlow(1f)

    fun toggleMute() {
        val newMutedState = !isMuted.value
        isMuted.value = newMutedState
        applyEffectiveVolume()
    }

    fun setMuted(muted: Boolean) {
        isMuted.value = muted
        applyEffectiveVolume()
    }

    /**
     * The fine part of the player page's volume bar (report 7): the bar sets the system step just above the level asked
     * for and this trims the rest in the app, so 0 % is silent and the level moves smoothly between the steps. 1 = no
     * trim. Not saved; reset when the system volume is changed from outside (hardware keys), see [onDeviceVolumeChanged].
     */
    val systemVolumeTrim = MutableStateFlow(1f)

    @Volatile
    private var systemVolumeTrimIndex = -1

    /** Called by the player page's volume bar after it set the music stream to [forIndex] for the bar position [fraction]. */
    fun setSystemVolumeTrim(
        trim: Float,
        forIndex: Int,
        fraction: Float,
    ) {
        systemVolumeTrimIndex = forIndex
        systemVolumeBarFraction = fraction
        systemVolumeTrim.value = trim.coerceIn(0f, 1f)
    }

    @Volatile
    private var systemVolumeBarFraction = -1f

    /** The bar position that goes with the trim, while the stream is still on [index] (null once the keys moved it). */
    fun systemVolumeBarFraction(index: Int): Float? = systemVolumeBarFraction.takeIf { index == systemVolumeTrimIndex && it >= 0f }

    private fun calculateEffectiveVolume(
        volume: Float = playerVolume.value,
        muted: Boolean = isMuted.value,
        sleepTimerMultiplier: Float = sleepTimerVolumeMultiplier.value,
        focusMultiplier: Float = audioFocusVolumeMultiplier.value,
        trim: Float = systemVolumeTrim.value,
    ): Float =
        VolumeDuckGuard.effectiveVolume(
            userVolume = volume * trim,
            muted = muted,
            sleepTimerMultiplier = sleepTimerMultiplier,
            focusMultiplier = focusMultiplier,
        )

    /**
     * The coordinator's view of the player. It always reads the CURRENT [player] (it is replaced by a rebuild or a
     * crossfade), and never stores the player's volume anywhere: the user's volume is [playerVolume] only.
     */
    private val audioFocusHost =
        object : AudioFocusHost {
            // "wants to play": Player.isPlaying is false while the song buffers, and a focus loss then did not pause it
            override val isPlaying: Boolean
                get() = player.playWhenReady && (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
            override val isCasting: Boolean get() = castConnectionHandler?.isCasting?.value == true
            override val isVolumeLocked: Boolean get() = isCrossfading
            override val currentVolume: Float get() = player.volume
            override val expectedVolume: Float
                get() =
                    calculateEffectiveVolume(
                        focusMultiplier = 1f,
                    )

            override fun pause() = player.pause()

            override fun play() = player.play()

            override fun applyVolume() = applyEffectiveVolume()
        }

    private fun applyEffectiveVolume() {
        if (!::player.isInitialized || isCrossfading) return
        player.volume = calculateEffectiveVolume()
    }

    var sleepTimer: SleepTimer? = null

    @Inject
    @PlayerCache
    lateinit var playerCache: Cache

    @Inject
    @DownloadCache
    lateinit var downloadCache: Cache

    lateinit var player: ExoPlayer
        private set
    private var secondaryPlayer: ExoPlayer? = null
    private var fadingPlayer: ExoPlayer? = null
    private var isCrossfading = false
    private var crossfadeJob: Job? = null
    private var taskCleared = false
    private var lastSavedPlayerState: PersistPlayerState? = null
    private var mediaSession: MediaLibrarySession? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    private val playerInitialized = MutableStateFlow(false)
    val isPlayerReady: kotlinx.coroutines.flow.StateFlow<Boolean> = playerInitialized.asStateFlow()

    // Single batch-read of all DataStore preferences needed during onCreate().
    // Populated once at the very top of onCreate() to replace 15+ individual
    // runBlocking reads that were each blocking the main thread.
    @Volatile
    private var startupPrefs: Preferences? = null

    private val _playerFlow = MutableStateFlow<ExoPlayer?>(null)
    val playerFlow = _playerFlow.asStateFlow()

    private val playerSilenceProcessors = HashMap<Player, SilenceDetectorAudioProcessor>()

    private val instantSilenceSkipEnabled = MutableStateFlow(false)

    private var isAudioEffectSessionOpened = false
    private var openedAudioEffectSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private val playerNormalizationProcessors = HashMap<Player, VolumeNormalizationAudioProcessor>()
    private val playerEqualizerProcessors = HashMap<Player, CustomEqualizerAudioProcessor>()

    private var loudnessSetupJob: Job? = null
    private var loudnessSetupGeneration: Long = 0L

    @Volatile
    private var normalizationEnabledCached: Boolean = false

    @Volatile
    private var loudnessLevelCached: LoudnessLevel = LoudnessGain.DEFAULT_LEVEL

    private var cachedNormalizationMediaId: String? = null
    private var cachedNormalizationGainMb: Int? = null
    private var cachedNormalizationEnabled: Boolean = false

    @Volatile private var discordRpcEnabled = false
    @Volatile private var lastDiscordReconnectAttemptAtMs: Long = 0L
    @Volatile private var discordIntentionalDisconnect = false
    @Volatile private var isScreenOff = false
    private val screenOffHandler = Handler(Looper.getMainLooper())
    private val screenOffTimeout = Runnable {
        Timber.tag("DiscordSvc").i("screenOffTimeout: isPlaying=%s, isReady=%s",
            player.isPlaying, DiscordRpcManager.isReady())
        if (!player.isPlaying && DiscordRpcManager.isReady()) {
            Timber.tag("DiscordSvc").i("screenOffTimeout: disconnecting after long idle")
            discordIntentionalDisconnect = true
            DiscordRpcManager.disconnect()
        }
    }
    private val pauseTimeout = Runnable {
        Timber.tag("DiscordSvc").i("pauseTimeout: isPlaying=%s, isReady=%s",
            player.isPlaying, DiscordRpcManager.isReady())
        if (!player.isPlaying && DiscordRpcManager.isReady()) {
            Timber.tag("DiscordSvc").i("pauseTimeout: disconnecting after 1m paused")
            discordIntentionalDisconnect = true
            DiscordRpcManager.disconnect()
        }
    }
    private var lastPlaybackSpeed = 1.0f

    @Volatile
    private var latestMediaNotification: Notification? = null

    private var scrobbleManager: ScrobbleManager? = null

    val automixItems = MutableStateFlow<List<MediaItem>>(emptyList())

    // Tracks the original queue size to distinguish original items from auto-added ones
    private var originalQueueSize: Int = 0

    private var retryJob: Job? = null
    private var retryCount = 0

    // Round 4.3.4: the rules after an error (PlaybackRecovery), the stall watchdog and the time limit of a stream
    // extraction (ExtractionGate). See those files for the reports they answer.
    private val recovery = PlaybackRecovery(clock = SystemClock::elapsedRealtime)
    private val stallWatchdog = StallWatchdog(SystemClock::elapsedRealtime)
    private var watchdogJob: Job? = null

    /** Songs whose stream address the resolver is waiting for right now (the watchdog does not count that wait). */
    private val resolvingNow = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val extractionGate =
        ExtractionGate<InnerTubeXPlayer.PlaybackData>(
            scope = scope + Dispatchers.IO,
            waitMs = EXTRACTION_WAIT_MS,
            hardCapMs = EXTRACTION_HARD_CAP_MS,
        )

    /**
     * The HTTP client of the media data source. Built once (it was built inside the data-source factory), so the stream
     * probe can share its connection pool: review 2026-11-12, finding 25 (the probe had its own client, and every new song
     * paid an extra DNS + TCP + TLS handshake before its first byte).
     */
    private val mediaHttpClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .proxy(YouTube.proxy)
            .proxyAuthenticator { _, response ->
                YouTube.proxyAuth?.let { auth ->
                    response.request
                        .newBuilder()
                        .header("Proxy-Authorization", auth)
                        .build()
                } ?: response.request
            }.build()
    }

    /**
     * Checks that a new stream address serves the end of the song too (StreamProbe); short limits, never blocks long.
     * `newBuilder()` keeps the media client's connection pool, dispatcher, proxy and DNS: the connection the probe opens
     * to the googlevideo host is the one the first piece of audio then reuses.
     */
    private val streamProbe by lazy {
        StreamProbe(
            mediaHttpClient
                .newBuilder()
                .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
        )
    }

    /** The default network for the player: validated (finding 6) and unmetered (finding 7). Set in onCreate. */
    @Volatile
    private var networkWatcher: PlaybackNetworkWatcher? = null

    /**
     * A retry after a connection error while "online" that may run at once when a validated network appears (finding 6).
     * Main thread only.
     */
    private var retryOnValidatedNetwork: (() -> Unit)? = null

    /**
     * Playback stopped after [PlaybackRecovery.UNREACHABLE_BUDGET_MS] of connection errors: resumed when a validated network
     * appears, only while the same song is there and nobody touched play/pause since (finding 6). Main thread only.
     */
    private class UnreachableResume(
        val mediaId: String,
        var ownPauseSeen: Boolean = false,
    )

    private var unreachableResume: UnreachableResume? = null

    /** How far the user moved the position forward in the current song (finding 19: only natural ends are "run through"). */
    private var currentItemUserSeekForwardMs = 0L

    /** Queues started since the service was created; the saved queue is not applied over one of them (finding 23). */
    private var queuesStartedSinceCreate = 0

    /** Set by our own skip after a failure: that move is not the user picking a song. */
    private var skippingAfterFailure = false

    /** Wall-clock time the current song has really been playing (watchdog ticks), for [PlaybackRecovery.onSongEnded]. */
    private var currentItemPlayedWallMs = 0L
    private var lastFailureNoticeAtMs = 0L
    // True only when stopOnError() paused playback purely because of a network outage
    // (waitOnNetworkError exhausting its attempts). Lets triggerRetry() know it's safe —
    // and necessary — to explicitly resume playback once connectivity returns, rather than
    // leaving the player "prepared but paused" forever.
    private var pausedDueToNetworkError = false
    private var silenceSkipJob: Job? = null

    // Cached preferences to avoid runBlocking DataStore reads in hot paths
    @Volatile
    private var cachedPersistentQueue = true
    @Volatile
    private var cachedAutoplay = true
    @Volatile
    private var cachedDisableLoadMoreWhenRepeatAll = false
    @Volatile
    private var cachedHideExplicit = false
    @Volatile
    private var cachedHideVideoSongs = false
    @Volatile
    private var cachedShufflePlaylistFirst = false
    @Volatile
    private var cachedAutoLoadMore = true

    // URL cache for stream URLs - class-level so it can be invalidated on errors
    private val songUrlCache = StreamUrlCache()

    // Tracks mediaIds for which a recoverSong() coroutine is currently in flight.
    //
    // resolveDataSpec() (createDataSourceFactory()) calls recoverSong() on every
    // dataSpec/chunk resolution, not just once per song. For a song cached a long
    // time ago the cache index can be fragmented into many small CacheSpans
    // (interrupted downloads, LeastRecentlyUsedCacheEvictor reclaiming arbitrary
    // spans), so a single playback can re-resolve dozens or hundreds of times in a
    // very short window. Without this guard, every single resolve would launch its
    // own coroutine doing a Room read + a hop to Dispatchers.Main + a Room
    // transaction — all redundant, since they all converge on the same mediaId and
    // mostly no-op. If those launches outpace how fast they can drain (e.g. the
    // Main thread is busy with playback/UI work), dozens of them pile up in memory
    // at once, which is enough to blow past this app's heap limit on low-RAM
    // devices and surface as an OutOfMemoryError that looks like a leak.
    //
    // This set makes recoverSong() effectively a no-op while a call for the same
    // mediaId is already running, so at most one is ever in flight per song.
    private val recoveringSongs = Collections.synchronizedSet(mutableSetOf<String>())

    private val sessionKey
        get() = YouTube.dataSyncId.takeIf { !it.isNullOrBlank() }
            ?: YouTube.visitorData.takeIf { !it.isNullOrBlank() }
            ?: ""

    private fun cacheKey(mediaId: String) = "${sessionKey}:$mediaId"

    private val playbackUrlCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(0, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
                return size > 500
            }
        }
    )

    // Flag to bypass cache when quality changes - forces fresh stream fetch
    private val bypassCacheForQualityChange = mutableSetOf<String>()

    /** Set after a hardware audio decoder failed; the codec selector then lists software decoders first. */
    @Volatile
    private var preferSoftwareAudioDecoder = false

    var castConnectionHandler: CastConnectionHandler? = null
        private set

    private val screenStateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        isScreenOff = true
                        stopWidgetUpdates()
                        Timber.tag("DiscordSvc").i("SCREEN_OFF: cancelling pause timeout, delaying disconnect 10m")
                        screenOffHandler.removeCallbacks(pauseTimeout)
                        screenOffHandler.postDelayed(screenOffTimeout, 600_000)
                    }

                    Intent.ACTION_SCREEN_ON -> {
                        isScreenOff = false
                        if (::player.isInitialized && player.isPlaying) {
                            startWidgetUpdates()
                        }
                        Timber.tag("DiscordSvc").i("SCREEN_ON: removing disconnect delay")
                        screenOffHandler.removeCallbacks(screenOffTimeout)
                        screenOffHandler.removeCallbacks(pauseTimeout)
                        discordIntentionalDisconnect = false
                        syncDiscordState()
                    }
                }
            }
        }

    /** The user's choice to keep playing when a game or another app takes the audio focus (read from the settings). */
    @Volatile
    private var keepPlayingOnFocusLoss = false

    /** The first call of an [AudioDeviceCallback] lists the devices that were already connected: that is not "a device connected". */
    private var audioDevicesListed = false

    private val audioDeviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                super.onAudioDevicesAdded(addedDevices)
                if (!audioDevicesListed) {
                    audioDevicesListed = true
                    return
                }
                val hasBluetooth =
                    addedDevices?.any {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            // LE Audio earbuds and speakers (Android 12+)
                            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
                    } == true

                if (hasBluetooth) {
                    if (dataStore.get(ResumeOnBluetoothConnectKey, false)) {
                        if (player.playbackState == Player.STATE_READY && !player.isPlaying) {
                            player.play()
                        }
                    }
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        // spends a streak recovery as soon as today's listening earns it, even with no screen open
        listeningStreakManager.start()
        shutdownDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()

        setListener(
            object : MediaSessionService.Listener {
                override fun onForegroundServiceStartNotAllowedException() {
                    handleForegroundServiceStartNotAllowed(null)
                }
            },
        )

        playerInitialized.value = false

        // Call startForeground() as early as possible to satisfy the
        // 5-second timeout imposed by Context.startForegroundService().
        // On some OEMs (e.g. MIUI), even a DataStore read can be slow
        // enough to miss the window, so we promote before any I/O.
        ensureForegroundChannelExists()
        // Report 1 ("lagu tidak bisa dipencet, harus force stop dulu"): a refused foreground start (the service created by a
        // bind or a start from the background) used to end onCreate HERE, before the player existed. That instance stayed
        // alive while something was bound to it, isRunning was true, so the app bound to it instead of starting a new one,
        // and every tap waited for a player that never came. Now the player is always built; only the foreground state
        // waits for a moment the system allows it (Media3 asks again as soon as playback starts).
        tryStartForegroundAtCreate()

        // Read ALL startup preferences in one shot so that subsequent code
        // never calls dataStore.get() (which does runBlocking internally).
        // This consolidates ~15 main-thread-blocking DataStore reads into 1.
        startupPrefs = runBlocking(Dispatchers.IO) { dataStore.data.first() }

        // 3. Connect the processor to the service
        // handled in createExoPlayer

        seedLoudnessCacheFromPrefs()

        val defaultMediaNotificationProvider =
            DefaultMediaNotificationProvider(
                this,
                { NOTIFICATION_ID },
                CHANNEL_ID,
                R.string.music_player,
            ).apply {
                setSmallIcon(R.drawable.small_icon)
            }

        setMediaNotificationProvider(
            object : MediaNotification.Provider {
                override fun createNotification(
                    mediaSession: MediaSession,
                    mediaButtonPreferences: ImmutableList<CommandButton>,
                    actionFactory: MediaNotification.ActionFactory,
                    onNotificationChangedCallback: MediaNotification.Provider.Callback,
                ): MediaNotification {
                    val trackingCallback =
                        MediaNotification.Provider.Callback { notification ->
                            if (taskCleared) return@Callback
                            latestMediaNotification = notification.notification
                            try {
                                onNotificationChangedCallback.onNotificationChanged(notification)
                            } catch (e: IllegalStateException) {
                                // Report 6 (vivo, Android 15): when a cover finishes loading this update runs from the main loop,
                                // outside Media3's own guard, and its foreground start is refused while the app is in the
                                // background. Handled like Media3's listener instead of crashing the app.
                                if (isForegroundServiceStartNotAllowedException(e)) handleForegroundServiceStartNotAllowed(e) else throw e
                            }
                        }

                    return defaultMediaNotificationProvider
                        .createNotification(
                            mediaSession,
                            mediaButtonPreferences,
                            actionFactory,
                            trackingCallback,
                        ).also { mediaNotification ->
                            latestMediaNotification = mediaNotification.notification
                        }
                }

                override fun handleCustomCommand(
                    session: MediaSession,
                    action: String,
                    extras: Bundle,
                ): Boolean = defaultMediaNotificationProvider.handleCustomCommand(session, action, extras)

                override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo =
                    defaultMediaNotificationProvider.notificationChannelInfo
            },
        )
        // before the player: its load control asks the watcher whether the network is unmetered (review 2026-11-12, 7)
        networkWatcher =
            getSystemService<ConnectivityManager>()?.let { cm ->
                PlaybackNetworkWatcher(cm, Handler(Looper.getMainLooper())) { onValidatedNetworkAppeared() }
            }
        player = createExoPlayer(prefs = startupPrefs!!)
        player.addListener(this@MusicService)
        networkWatcher?.start()
        sleepTimer =
            SleepTimer(scope, player) { multiplier ->
                sleepTimerVolumeMultiplier.value = multiplier
            }
        player.addListener(sleepTimer!!)

        playerInitialized.value = true
        Timber.tag(TAG).d("Player successfully initialized")
        dynamicIsland = DynamicIslandController(this, playerFlow, database, lyricsHelper).also { it.start() }
        lockScreenLyrics = LockScreenLyricsController(this, playerFlow, database, lyricsHelper).also { it.start() }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioFocusCoordinator =
            AudioFocusCoordinator(
                scope = scope,
                host = audioFocusHost,
                guard = VolumeDuckGuard(clock = SystemClock::elapsedRealtime),
                focusMultiplier = audioFocusVolumeMultiplier,
                keepPlaying = { keepPlayingOnFocusLoss },
            ) { listener -> AudioManagerFocusPort(audioManager, listener) }

        mediaLibrarySessionCallback.apply {
            service = this@MusicService
            toggleLike = ::toggleLike
            toggleStartRadio = ::toggleStartRadio
            toggleLibrary = ::toggleLibrary
            addToTargetPlaylist = ::addToTargetPlaylist
        }
        mediaSession =
            MediaLibrarySession
                .Builder(this, player, mediaLibrarySessionCallback)
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).setBitmapLoader(CoilBitmapLoader(this, scope))
                .build()
        player.repeatMode = startupPrefs!![RepeatModeKey] ?: REPEAT_MODE_OFF

        if (startupPrefs!![RememberShuffleAndRepeatKey] ?: true) {
            player.shuffleModeEnabled = startupPrefs!![ShuffleModeKey] ?: false
        }

        // Keep a connected controller so that notification works
        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                controllerFuture?.get()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to initialize MediaController")
                controllerFuture = null
                stopSelf()
            }
        }, MoreExecutors.directExecutor())

        connectivityManager = getSystemService()!!
        connectivityObserver = NetworkConnectivityObserver(this)

        isScreenOff = getSystemService<PowerManager>()?.isInteractive == false
        val screenStateFilter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
        registerReceiver(screenStateReceiver, screenStateFilter)

        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)

        audioQuality = startupPrefs!![AudioQualityKey]?.let { value ->
            if (value == "VERY_HIGH") com.metrolist.music.constants.AudioQuality.HIGH
            else com.metrolist.music.constants.AudioQuality.entries.find { it.name == value }
        } ?: com.metrolist.music.constants.AudioQuality.AUTO
        dataSaverOn = startupPrefs!![com.metrolist.music.constants.DataSaverKey] ?: false
        dataSaverMobileOnly = startupPrefs!![com.metrolist.music.constants.DataSaverMobileOnlyKey] ?: true
        scope.launch {
            dataStore.data
                .map { (it[com.metrolist.music.constants.DataSaverKey] ?: false) to (it[com.metrolist.music.constants.DataSaverMobileOnlyKey] ?: true) }
                .distinctUntilChanged()
                .collect { (on, mobileOnly) ->
                    dataSaverOn = on
                    dataSaverMobileOnly = mobileOnly
                }
        }
        // A level saved at (almost) 0 comes from a mute that was never undone; starting silent with no hint is a trap, so
        // it starts at full. The volume bar and the left icon still bring any other level back.
        playerVolume = MutableStateFlow((startupPrefs!![PlayerVolumeKey] ?: 1f).coerceIn(0f, 1f).let { if (it < 0.05f) 1f else it })

        initializeCast()

        // Collecting this flow activates the internal map that updates lyricsProviders in LyricsHelper
        lyricsHelper.preferred.collectLatest(scope) {}

        // 4. Watch for EQ profile changes
        scope.launch {
            var lastEqId: String? = null
            eqProfileRepository.activeProfile.collect { profile ->
                // The same profile again = its sliders moved (the easy equalizer saves while it is dragged): the sound already
                // follows, a seek here would stutter the song at every step.
                val sameProfile = profile != null && profile.id == lastEqId
                lastEqId = profile?.id
                if (profile != null) {
                    val result = equalizerService.applyProfile(profile)
                    if (result.isSuccess && !sameProfile && player.playbackState == Player.STATE_READY && player.isPlaying) {
                        // Instant update: flush buffers and seek slightly to re-process audio
                        player.seekTo(player.currentPosition)
                    }
                } else {
                    equalizerService.disable()
                    if (player.playbackState == Player.STATE_READY && player.isPlaying) {
                        player.seekTo(player.currentPosition)
                    }
                }
            }
        }

        scope.launch {
            connectivityObserver.networkStatus.collect { isConnected ->
                isNetworkConnected.value = isConnected
                if (isConnected && waitingForNetworkConnection.value) {
                    triggerRetry()
                }
                if (isConnected && DiscordRpcManager.isReady()) {
                    Timber.tag("DiscordSvc").i("Network reconnected, syncing RPC")
                    syncDiscordState()
                }
            }
        }

        // Watch for audio quality setting changes
        var isFirstQualityEmit = true
        scope.launch {
            dataStore.data
                .map {
                    it[AudioQualityKey]?.let { value ->
                        if (value == "VERY_HIGH") com.metrolist.music.constants.AudioQuality.HIGH
                        else com.metrolist.music.constants.AudioQuality.entries.find { it.name == value }
                    } ?: com.metrolist.music.constants.AudioQuality.AUTO
                }.distinctUntilChanged()
                .collect { newQuality ->
                    val oldQuality = audioQuality
                    audioQuality = newQuality

                    if (isFirstQualityEmit) {
                        isFirstQualityEmit = false
                        Timber.tag(TAG).i("QUALITY INIT: $newQuality")
                        return@collect
                    }

                    Timber.tag(TAG).i("QUALITY CHANGED: $oldQuality -> $newQuality")

                    val mediaId = player.currentMediaItem?.mediaId ?: return@collect
                    val currentPosition = player.currentPosition
                    val wasPlaying = player.isPlaying
                    val currentIndex = player.currentMediaItemIndex

                    Timber.tag(TAG).i("RELOADING STREAM: $mediaId at position ${currentPosition}ms")

                    songUrlCache.invalidate(mediaId)

                    // CRITICAL: Clear caches synchronously to prevent format parsing errors
                    runBlocking(Dispatchers.IO) {
                        try {
                            playerCache.removeResource(mediaId)
                            downloadCache.removeResource(mediaId)
                            Timber.tag(TAG).d("Cleared player and download cache for $mediaId")
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Failed to clear cache for $mediaId")
                        }
                    }

                    // Set bypass flag so resolver skips cache checks
                    bypassCacheForQualityChange.add(mediaId)
                    Timber.tag(TAG).d("Set bypass cache flag for $mediaId")

                    player.stop()
                    player.seekTo(currentIndex, currentPosition)
                    player.prepare()
                    if (wasPlaying) {
                        player.play()
                    }
                }
        }

        combine(
            playerVolume,
            isMuted,
            sleepTimerVolumeMultiplier,
            audioFocusVolumeMultiplier,
            systemVolumeTrim,
        ) { volume, muted, timerMultiplier, focusMultiplier, trim ->
            calculateEffectiveVolume(
                volume = volume,
                muted = muted,
                sleepTimerMultiplier = timerMultiplier,
                focusMultiplier = focusMultiplier,
                trim = trim,
            )
        }.collectLatest(scope) {
            if (!isCrossfading) {
                player.volume = it
            }
        }

        playerVolume.debounce(1000).collect(scope) { volume ->
            safeDataStoreEdit { settings ->
                settings[PlayerVolumeKey] = volume
            }
        }

        dataStore.data
            .map { it[KeepPlayingOnFocusLossKey] ?: false }
            .distinctUntilChanged()
            .collect(scope) { keepPlayingOnFocusLoss = it }

        currentSong.debounce(1000).collect(scope) { song ->
            updateNotification()
            updateWidgetUI(player.isPlaying)
        }

        combine(
            currentMediaMetadata.distinctUntilChangedBy { it?.id },
            dataStore.data.map { it[ShowLyricsKey] ?: false }.distinctUntilChanged(),
        ) { mediaMetadata, showLyrics ->
            mediaMetadata to showLyrics
        }.collectLatest(scope) { (mediaMetadata, showLyrics) ->
            if (showLyrics && mediaMetadata != null && database
                    .lyrics(mediaMetadata.id)
                    .first() == null
            ) {
                // L's single write path (lyrics/LyricsStore.kt, FILL mode): waits while offline, never stores a network
                // "not found" and never overwrites a source the user picked during that wait
                lyricsHelper.fillLyrics(mediaMetadata)
            }
        }

        dataStore.data
            .map { (it[SkipSilenceKey] ?: false) to (it[SkipSilenceInstantKey] ?: false) }
            .distinctUntilChanged()
            .collectLatest(scope) { (skipSilence, instantSkip) ->
                player.skipSilenceEnabled = skipSilence
                secondaryPlayer?.skipSilenceEnabled = skipSilence

                val enableInstant = skipSilence && instantSkip
                instantSilenceSkipEnabled.value = enableInstant

                playerSilenceProcessors.values.forEach { processor ->
                    processor.instantModeEnabled = enableInstant
                    if (!enableInstant) {
                        processor.resetTracking()
                    }
                }

                if (!enableInstant) {
                    silenceSkipJob?.cancel()
                }
            }

        // Keep the cover of every song that plays, and of the library when on an unmetered connection,
        // so lists and the player still show pictures with no connection.
        scope.launch(Dispatchers.IO) {
            currentSong
                .map { it?.song?.thumbnailUrl }
                .distinctUntilChanged()
                .collect { OfflineArtworkStore.saveIfMissing(this@MusicService, it) }
        }
        scope.launch(Dispatchers.IO) {
            delay(30.seconds)
            try {
                // only the cover addresses, not every song object with its artists and album (a big library held in memory for the
                // whole backfill added to the out-of-memory crashes on phones with a 256 MB heap)
                val urls = database.coverUrlsToKeep().distinct()
                for (url in urls) {
                    if (connectivityManager.activeNetwork == null || connectivityManager.isActiveNetworkMetered || dataSaverActive()) break
                    OfflineArtworkStore.saveIfMissing(this@MusicService, url)
                    delay(120)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).d(e, "Cover backfill stopped")
            }
        }

        combine(
            currentFormat,
            dataStore.data
                .map { it[AudioNormalizationKey] ?: true }
                .distinctUntilChanged(),
            dataStore.data
                .map { prefs -> prefs[LoudnessLevelKey].toEnum(LoudnessGain.DEFAULT_LEVEL) }
                .distinctUntilChanged(),
        ) { format, normalizeAudio, loudnessLevel ->
            Triple(format, normalizeAudio, loudnessLevel)
        }.collectLatest(scope) { (_, normalizeAudio, loudnessLevel) ->
            normalizationEnabledCached = normalizeAudio
            loudnessLevelCached = loudnessLevel
            setupAudioNormalization()
        }

        var firstSoundVibe = true
        dataStore.data
            .map { prefs -> prefs[SoundVibeKey].toEnum(SoundVibe.NORMAL) }
            .distinctUntilChanged()
            .collectLatest(scope) { vibe ->
                ReverbAudioProcessor.apply(vibe)
                // at start-up a normal style must not undo a tempo the listener set by hand
                if (!(firstSoundVibe && vibe == SoundVibe.NORMAL)) {
                    player.playbackParameters = PlaybackParameters(vibe.speed, vibe.pitch)
                }
                firstSoundVibe = false
            }

        combine(
            dataStore.data.map { it[AudioOffload] ?: false },
            dataStore.data.map { it[CrossfadeEnabledKey] ?: false },
            dataStore.data.map { prefs -> prefs[SoundVibeKey].toEnum(SoundVibe.NORMAL).processesAudio },
        ) { offloadPref, crossfadeEnabled, styleChangesAudio ->
            // Force disable offload if crossfade is enabled to prevent volume ramp issues.
            // Offload hands the compressed audio straight to the DSP, so reverb, lo-fi and 8D would never run.
            if (crossfadeEnabled || styleChangesAudio) false else offloadPref
        }.distinctUntilChanged()
            .collectLatest(scope) { useOffload ->
                player.setOffloadEnabled(useOffload)
                secondaryPlayer?.setOffloadEnabled(useOffload)
            }

        var isFirstAudioTrackParamsEmit = true
        dataStore.data
            .map { prefs -> (prefs[AudioTrackPlaybackParamsKey] ?: true) && !soundVibeNeedsSoftwareSpeed(prefs[SoundVibeKey]) }
            .distinctUntilChanged()
            .collectLatest(scope) { useAudioTrackParams ->
                if (isFirstAudioTrackParamsEmit) {
                    isFirstAudioTrackParamsEmit = false
                    return@collectLatest
                }

                Timber.tag("MusicService").i("AudioTrackPlaybackParams changed to: $useAudioTrackParams")

                val currentIndex = player.currentMediaItemIndex
                val currentPosition = player.currentPosition
                val playWhenReady = player.playWhenReady
                val repeatMode = player.repeatMode
                val shuffleModeEnabled = player.shuffleModeEnabled
                val shuffleOrder = player.shuffleOrder
                val playbackParameters = player.playbackParameters
                val mediaItems = List(player.mediaItemCount) { index ->
                    player.getMediaItemAt(index)
                }

                player.removeListener(this)
                sleepTimer?.let { player.removeListener(it) }
                releaseExoPlayer(player)

                val newPlayer = createExoPlayer()
                newPlayer.addListener(this@MusicService)
                sleepTimer?.let { newPlayer.addListener(it) }

                sleepTimer?.player = newPlayer

                try {
                    mediaSession?.let { (it as MediaSession).player = newPlayer }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to swap player in MediaSession")
                }

                newPlayer.setMediaItems(mediaItems, currentIndex, currentPosition)
                newPlayer.repeatMode = repeatMode
                newPlayer.shuffleModeEnabled = shuffleModeEnabled
                newPlayer.setShuffleOrder(shuffleOrder)
                newPlayer.playbackParameters = playbackParameters
                // Computed from the user volume and the current multipliers, not copied from the old player: a copy would
                // freeze whatever duck or fade the old player had at this moment.
                newPlayer.volume = calculateEffectiveVolume()
                newPlayer.playWhenReady = playWhenReady
                newPlayer.prepare()

                player = newPlayer
                _playerFlow.value = newPlayer

                Timber.tag("MusicService").i("Player recreated with AudioTrackPlaybackParams: $useAudioTrackParams")
            }

        // Discord presence was taken out of the app (its settings page is gone, it showed the Metrolist name): the manager is no
        // longer started, and a token an older version saved is forgotten so nothing connects to Discord behind the user's back.
        runCatching {
            com.metrolist.music.discord.DiscordTokenStore.init(applicationContext)
            com.metrolist.music.discord.DiscordTokenStore.clear()
        }

        dataStore.data
            .map { false }
            .debounce(300)
            .distinctUntilChanged()
            .collect(scope) { enabled ->
                Timber.tag("DiscordSvc").i("RPC toggle: enabled=%s, isReady=%s, hasToken=%s",
                    enabled, DiscordRpcManager.isReady(), DiscordRpcManager.getAccessToken() != null)
                discordRpcEnabled = enabled
                if (enabled) {
                    discordIntentionalDisconnect = false
                    if (DiscordRpcManager.isReady()) {
                        Timber.tag("DiscordSvc").i("RPC toggle: already ready, syncing RPC")
                        syncDiscordState()
                    } else if (DiscordRpcManager.getAccessToken() != null) {
                        Timber.tag("DiscordSvc").i("RPC toggle: not ready but has token, reconnecting")
                        scope.launch(Dispatchers.IO) {
                            if (!DiscordRpcManager.isInitialized()) {
                                Timber.tag("DiscordSvc").i("RPC toggle: initializing")
                                DiscordRpcManager.init(this@MusicService)
                            }
                            DiscordRpcManager.reconnectWithToken(DiscordRpcManager.getAccessToken()!!)
                        }
                    } else {
                        Timber.tag("DiscordSvc").w("RPC toggle: enabled but no token and not ready")
                    }
                } else if (DiscordRpcManager.isReady()) {
                    Timber.tag("DiscordSvc").i("RPC toggle: disabled, disconnecting")
                    scope.launch(Dispatchers.IO) {
                        DiscordRpcManager.disconnect()
                    }
                }
            }

        DiscordRpcManager.accessTokenFlow.collectLatest(scope) { token ->
                Timber.tag("DiscordSvc").i("Token change: hasToken=%s, initialized=%s, authorized=%s, enabled=%s",
                    token != null, DiscordRpcManager.isInitialized(), DiscordRpcManager.isAuthorized(), discordRpcEnabled)
                if (token == null) {
                    if (DiscordRpcManager.isReady()) {
                        Timber.tag("DiscordSvc").i("Token change: empty token, disconnecting")
                        DiscordRpcManager.disconnect()
                    }
                    return@collectLatest
                }
                if (!discordRpcEnabled) {
                    Timber.tag("DiscordSvc").i("Token change: RPC disabled, skipping reconnect")
                    return@collectLatest
                }
                if (!DiscordRpcManager.isInitialized()) {
                    Timber.tag("DiscordSvc").i("Token change: initializing")
                    DiscordRpcManager.init(this@MusicService)
                }
                if (!DiscordRpcManager.isAuthorized()) {
                    Timber.tag("DiscordSvc").i("Token change: reconnecting with token")
                    DiscordRpcManager.reconnectWithToken(token)
                } else {
                    Timber.tag("DiscordSvc").i("Token change: already authorized, skipping reconnect")
                }
            }

        scope.launch {
            DiscordRpcManager.connectionStatus.collect { status ->
                Timber.tag("DiscordSvc").i("Status change: %s (discordRpcEnabled=%s, playing=%s)",
                    status, discordRpcEnabled, player.isPlaying)
                if (status == DiscordRpcManager.Status.Connected && discordRpcEnabled) {
                    Timber.tag("DiscordSvc").i("Status change: connected, syncing RPC")
                    syncDiscordState()
                }
            }
        }

        scope.launch {
            DiscordRpcManager.settingsChanged.collect {
                if (discordRpcEnabled && DiscordRpcManager.isReady()) {
                    Timber.tag("DiscordSvc").i("Settings changed, syncing RPC")
                    syncDiscordState()
                }
            }
        }

        scope.launch {
            while (isActive) {
                delay(5_000)
                Timber.tag("DiscordSvc").v("polling: periodic syncDiscordState tick")
                syncDiscordState()
            }
        }

        dataStore.data
            // Last.fm scrobbling was taken out of the app together with its settings page: it can no longer be switched on
            .map { false }
            .debounce(300)
            .distinctUntilChanged()
            .collect(scope) { enabled ->
                if (enabled && scrobbleManager == null) {
                    val delayPercent = dataStore.get(ScrobbleDelayPercentKey, LastFM.DEFAULT_SCROBBLE_DELAY_PERCENT)
                    val minSongDuration =
                        dataStore.get(ScrobbleMinSongDurationKey, LastFM.DEFAULT_SCROBBLE_MIN_SONG_DURATION)
                    val delaySeconds = dataStore.get(ScrobbleDelaySecondsKey, LastFM.DEFAULT_SCROBBLE_DELAY_SECONDS)
                    scrobbleManager =
                        ScrobbleManager(
                            scope,
                            minSongDuration = minSongDuration,
                            scrobbleDelayPercent = delayPercent,
                            scrobbleDelaySeconds = delaySeconds,
                        )
                    scrobbleManager?.useNowPlaying = dataStore.get(LastFMUseNowPlaying, false)
                } else if (!enabled && scrobbleManager != null) {
                    scrobbleManager?.destroy()
                    scrobbleManager = null
                }
            }

        dataStore.data
            .map { it[LastFMUseNowPlaying] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) {
                scrobbleManager?.useNowPlaying = it
            }

        dataStore.data
            .map { prefs ->
                Triple(
                    prefs[ScrobbleDelayPercentKey] ?: LastFM.DEFAULT_SCROBBLE_DELAY_PERCENT,
                    prefs[ScrobbleMinSongDurationKey] ?: LastFM.DEFAULT_SCROBBLE_MIN_SONG_DURATION,
                    prefs[ScrobbleDelaySecondsKey] ?: LastFM.DEFAULT_SCROBBLE_DELAY_SECONDS,
                )
            }.distinctUntilChanged()
            .collect(scope) { (delayPercent, minSongDuration, delaySeconds) ->
                scrobbleManager?.let {
                    it.scrobbleDelayPercent = delayPercent
                    it.minSongDuration = minSongDuration
                    it.scrobbleDelaySeconds = delaySeconds
                }
            }

        combine(
            dataStore.data.map { prefs ->
                Triple(
                    prefs[CrossfadeEnabledKey] ?: false,
                    prefs[CrossfadeDurationKey] ?: 5f,
                    prefs[CrossfadeGaplessKey] ?: true,
                )
            },
            listenTogetherManager.roomState,
        ) { (enabled, duration, gapless), roomState ->
            Triple(enabled && roomState == null, duration, gapless)
        }.distinctUntilChanged()
            .collect(scope) { (enabled, duration, gapless) ->
                crossfadeEnabled = enabled
                crossfadeDuration = duration * 1000f // Convert to ms
                crossfadeGapless = gapless
            }

        // Observe and cache common preferences to avoid runBlocking reads in playback callbacks
        scope.launch {
            dataStore.data.map { it[PersistentQueueKey] ?: true }.distinctUntilChanged().collect { cachedPersistentQueue = it }
        }
        scope.launch {
            dataStore.data.map { it[AutoplayKey] ?: true }.distinctUntilChanged().collect { cachedAutoplay = it }
        }
        scope.launch {
            dataStore.data.map { it[DisableLoadMoreWhenRepeatAllKey] ?: false }.distinctUntilChanged().collect { cachedDisableLoadMoreWhenRepeatAll = it }
        }
        scope.launch {
            dataStore.data.map { it[HideExplicitKey] ?: false }.distinctUntilChanged().collect { cachedHideExplicit = it }
        }
        scope.launch {
            dataStore.data.map { it[HideVideoSongsKey] ?: false }.distinctUntilChanged().collect { cachedHideVideoSongs = it }
        }
        scope.launch {
            dataStore.data.map { it[ShufflePlaylistFirstKey] ?: false }.distinctUntilChanged().collect { cachedShufflePlaylistFirst = it }
        }
        scope.launch {
            dataStore.data.map { it[AutoLoadMoreKey] ?: true }.distinctUntilChanged().collect { cachedAutoLoadMore = it }
        }
        if (startupPrefs!![PersistentQueueKey] ?: true) {
            // Review 2026-11-12, finding 23: the saved queue (up to 5 000 songs) used to be read, deserialised and turned
            // into MediaItems HERE, on the main thread, while the app drew its first frames (report 1's path: open the
            // app, tap a song). Now the files are read on IO and only the result is applied on the main thread. The player
            // already exists; a song the user taps before the saved queue is read wins (see applyRestoredPlayback).
            scope.launch {
                val restored = withContext(Dispatchers.IO) { readPersistedPlayback() }
                applyRestoredPlayback(restored)
            }
        }

        // Save queue periodically to prevent queue loss from crash or force kill
        scope.launch {
            while (isActive) {
                delay(60.seconds)
                if (cachedPersistentQueue) {
                    saveQueueToDisk()
                }
            }
        }

        scope.launch {
            while (isActive) {
                delay(15.seconds)
                if (cachedPersistentQueue) {
                    savePlayerStateToDisk()
                }
                val currentMetadata = player.currentMediaItem?.metadata
                if (currentMetadata?.isEpisode == true && player.isPlaying && player.currentPosition > 0) {
                    previousEpisodePosition = player.currentPosition
                    saveEpisodePosition(currentMetadata.id, player.currentPosition)
                }
            }
        }
    }

    private fun createExoPlayer(prefs: Preferences? = null): ExoPlayer {
        val normalizationProcessor = VolumeNormalizationAudioProcessor()
        val eqProcessor = CustomEqualizerAudioProcessor()

        val silenceProcessor = SilenceDetectorAudioProcessor { handleLongSilenceDetected() }

        // Set initial state — use pre-read prefs when available, otherwise fall back to DataStore
        val useAudioTrackPlaybackParams: Boolean
        if (prefs != null) {
            val skipSilence = prefs[SkipSilenceKey] ?: false
            val instantSkip = prefs[SkipSilenceInstantKey] ?: false
            silenceProcessor.instantModeEnabled = skipSilence && instantSkip
            // a sound style changes the speed in the app (software) so the system gets plain audio, see soundVibeNeedsSoftwareSpeed
            useAudioTrackPlaybackParams = (prefs[AudioTrackPlaybackParamsKey] ?: true) && !soundVibeNeedsSoftwareSpeed(prefs[SoundVibeKey])
        } else {
            runBlocking {
                val skipSilence = dataStore.get(SkipSilenceKey, false)
                val instantSkip = dataStore.get(SkipSilenceInstantKey, false)
                silenceProcessor.instantModeEnabled = skipSilence && instantSkip
                useAudioTrackPlaybackParams = dataStore.get(AudioTrackPlaybackParamsKey, true) &&
                    !soundVibeNeedsSoftwareSpeed(dataStore.data.first()[SoundVibeKey])
            }
        }

        var createdPlayer: ExoPlayer? = null
        val player =
            ExoPlayer
                .Builder(this)
                .setMediaSourceFactory(
                    createMediaSourceFactory(normalizationProcessor) { createdPlayer },
                )
                .setRenderersFactory(createRenderersFactory(normalizationProcessor, eqProcessor, silenceProcessor, useAudioTrackPlaybackParams))
                .setLoadControl(
                    // Start playback once ~750ms is buffered (media3's default is 1000ms) so first
                    // audio is audible a touch sooner. Round 4.3.4 (reports 2 and 4, "muter mulu pas ngegame"):
                    // the buffer may grow to the whole song (up to 15 min, capped at 8 MiB, like BitChord) instead
                    // of 50 s, so a song that has loaded keeps playing through a weak or busy connection. Only on an
                    // unmetered network without the data saver (review 2026-11-12, finding 7): on mobile data every
                    // skipped song was downloaded whole; there the buffer stays at 60-90 s / 2 MiB (LoadBudget).
                    NetworkAwareLoadControl(
                        delegate =
                            DefaultLoadControl
                                .Builder()
                                .setBufferDurationsMs(50_000, LOAD_AHEAD_MAX_MS, 750, 2_000)
                                .setTargetBufferBytes(LOAD_AHEAD_MAX_BYTES)
                                .build(),
                        wholeSong = ::wholeSongBuffering,
                    ),
                )
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    false,
                ).setSeekBackIncrementMs(5000)
                .setSeekForwardIncrementMs(5000)
                .setDeviceVolumeControlEnabled(true)
                .build()
        createdPlayer = player

        playerNormalizationProcessors[player] = normalizationProcessor
        playerSilenceProcessors[player] = silenceProcessor
        playerEqualizerProcessors[player] = eqProcessor
        equalizerService.addAudioProcessor(eqProcessor)

        if (prefs != null) {
            val offload = prefs[AudioOffload] ?: false
            val crossfade = prefs[CrossfadeEnabledKey] ?: false
            player.setOffloadEnabled(if (crossfade) false else offload)
            player.skipSilenceEnabled = prefs[SkipSilenceKey] ?: false
        } else {
            player.apply {
                runBlocking {
                    val offload = dataStore.get(AudioOffload, false)
                    val crossfade = dataStore.get(CrossfadeEnabledKey, false)
                    setOffloadEnabled(if (crossfade) false else offload)
                    skipSilenceEnabled = dataStore.get(SkipSilenceKey, false)
                }
            }
        }
        player.addAnalyticsListener(PlaybackStatsListener(false, this@MusicService))

        // Cleanup handled manually in onDestroy/release
        _playerFlow.value = player
        return player
    }

    /** Asks for the audio focus unless we already hold it (a duck still counts as holding it). Logic: [AudioFocusCoordinator]. */
    private fun requestAudioFocus(): Boolean = audioFocusCoordinator?.request() ?: false

    private fun abandonAudioFocus() {
        audioFocusCoordinator?.abandon()
    }

    private fun waitOnNetworkError() {
        if (waitingForNetworkConnection.value) return

        // Always arm the reconnect listener (connectivityObserver.networkStatus.collect)
        // before anything else can return early. Previously, hitting MAX_RETRY_COUNT while
        // still offline called stopOnError() and returned WITHOUT setting
        // waitingForNetworkConnection = true. That meant the "isConnected && waitingFor...
        // -> triggerRetry()" listener never fired once the network actually came back —
        // the player was left paused in a post-error state, and since ExoPlayer requires an
        // explicit prepare() after a fatal error before play() does anything, no song would
        // play again until the app was killed and relaunched (which recreates the player).
        waitingForNetworkConnection.value = true
        pausedDueToNetworkError = false

        if (retryCount >= MAX_RETRY_COUNT) {
            Timber.tag(TAG).w("Max retry count ($MAX_RETRY_COUNT) reached, pausing until network returns")
            pausedDueToNetworkError = true
            stopOnError()
            retryCount = 0
            // Don't schedule another backoff job — we're out of attempts for now — but stay
            // "waiting" so the connectivity listener can still auto-resume on reconnect.
            retryJob?.cancel()
            return
        }

        retryJob?.cancel()
        retryJob =
            scope.launch {
                // Exponential backoff: 3s, 6s, 12s, 24s... max 30s
                val delayMs = minOf(3000L * (1 shl retryCount), 30000L)
                Timber.tag(TAG).d("Waiting ${delayMs}ms before retry attempt ${retryCount + 1}/$MAX_RETRY_COUNT")
                delay(delayMs)

                if (isNetworkConnected.value && waitingForNetworkConnection.value) {
                    retryCount++
                    triggerRetry()
                }
                // If still offline when the timer fires, just let the job end — we stay
                // "waiting" and the connectivityObserver listener (not this job) is what
                // will catch the eventual reconnection and call triggerRetry().
            }
    }

    private fun triggerRetry() {
        waitingForNetworkConnection.value = false
        retryJob?.cancel()
        val shouldResumePlayback = pausedDueToNetworkError
        pausedDueToNetworkError = false

        if (player.currentMediaItem != null) {
            // After 3+ failed retries, try to refresh the stream URL by seeking to current position
            // This forces ExoPlayer to re-resolve the data source and get a fresh URL
            if (retryCount > 3) {
                Timber.tag(TAG).d("Retry count > 3, attempting to refresh stream URL")
                val currentPosition = player.currentPosition
                player.seekTo(player.currentMediaItemIndex, currentPosition)
            }
            player.prepare()
            if (shouldResumePlayback) {
                // We explicitly paused this ourselves (stopOnError) purely because of the
                // network outage — playWhenReady is now false, so prepare() alone would just
                // sit there "ready but paused" until the user manually pressed play again on
                // this exact item. Resume explicitly so reconnecting actually resumes audio.
                player.playWhenReady = true
            }
            // Otherwise (we never force-paused), leave playWhenReady as-is and let the
            // player auto-resume on its own — this avoids stealing audio focus on ordinary
            // mid-stream retries where the user never lost the "should be playing" intent.
        }
    }

    /** Skips a song that cannot be played. How many in a row may be skipped is decided by [PlaybackRecovery]. */
    private fun skipOnError() {
        val nextWindowIndex = player.nextMediaItemIndex
        if (nextWindowIndex == C.INDEX_UNSET) {
            skippingAfterFailure = false
            stopOnError()
            return
        }
        player.seekTo(nextWindowIndex, C.TIME_UNSET)
        player.prepare()
        if (castConnectionHandler?.isCasting?.value != true) {
            player.play()
        }
    }

    private fun stopOnError() {
        player.pause()
    }

    private fun updateNotification(isLiked: Boolean? = currentSong.value?.song?.let { if (it.isEpisode) it.inLibrary != null else it.liked }) {
        // The buttons of the media notification take a drawable id, so they follow the icon family chosen in the settings.
        val family = SystemIcons.family(this)
        mediaSession?.setCustomLayout(
            listOf(
                CommandButton
                    .Builder()
                    .setDisplayName(getString(if (player.shuffleModeEnabled) R.string.action_shuffle_off else R.string.action_shuffle_on))
                    .setIconResId(SystemIcons.resolve(family, if (player.shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle))
                    .setSessionCommand(CommandToggleShuffle)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(
                        getString(
                            if (isLiked == true) {
                                R.string.action_remove_like
                            } else {
                                R.string.action_like
                            },
                        ),
                    ).setIconResId(SystemIcons.resolve(family, if (isLiked == true) R.drawable.ic_heart else R.drawable.ic_heart_outline))
                    .setSessionCommand(CommandToggleLike)
                    .setEnabled(currentSong.value != null)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(
                        getString(
                            when (player.repeatMode) {
                                REPEAT_MODE_OFF -> R.string.repeat_mode_off
                                REPEAT_MODE_ONE -> R.string.repeat_mode_one
                                REPEAT_MODE_ALL -> R.string.repeat_mode_all
                                else -> throw IllegalStateException()
                            },
                        ),
                    ).setIconResId(
                        SystemIcons.resolve(
                            family,
                            when (player.repeatMode) {
                                REPEAT_MODE_OFF -> R.drawable.repeat
                                REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                                REPEAT_MODE_ALL -> R.drawable.repeat_on
                                else -> throw IllegalStateException()
                            },
                        ),
                    ).setSessionCommand(CommandToggleRepeatMode)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(getString(R.string.start_radio))
                    .setIconResId(SystemIcons.resolve(family, R.drawable.radio))
                    .setSessionCommand(CommandToggleStartRadio)
                    .setEnabled(currentSong.value != null)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(getString(R.string.android_auto_target_playlist))
                    .setIconResId(SystemIcons.resolve(family, R.drawable.playlist_add))
                    .setSessionCommand(CommandAddToTargetPlaylist)
                    .setEnabled(currentSong.value != null)
                    .build(),
            ),
        )
    }

    /**
     * Registers / refreshes song metadata (title, duration, isVideo, related songs)
     * for [mediaId]. Pure metadata bookkeeping only — does NOT touch [dateDownload].
     *
     * Looks across player, secondaryPlayer and fadingPlayer so metadata is still
     * found correctly while a crossfade swap is in progress.
     *
     * Safe to call frequently (e.g. on every dataSpec resolution) since it no longer
     * has any side effect tied to caching completeness.
     */
    private suspend fun recoverSong(
        mediaId: String,
        playbackData: InnerTubeXPlayer.PlaybackData? = null,
    ) {
        val song = database.song(mediaId).first()
        val mediaMetadata =
            withContext(Dispatchers.Main) {
                player.findNextMediaItemById(mediaId)?.metadata
                    ?: secondaryPlayer?.findNextMediaItemById(mediaId)?.metadata
                    ?: fadingPlayer?.findNextMediaItemById(mediaId)?.metadata
            }

        if (mediaMetadata == null && song == null) return

        val duration =
            song?.song?.duration?.takeIf { it != -1 }
                ?: mediaMetadata?.duration?.takeIf { it != -1 }
                ?: (
                    playbackData?.videoDetails
                )?.lengthSeconds?.toInt()
                ?: -1

        database.query {
            if (mediaMetadata != null && (song == null || song.orderedArtists.isEmpty())) {
                insert(mediaMetadata.copy(duration = duration))
            }
            if (song != null) {
                var updatedSong = song.song
                if (song.song.duration == -1) {
                    updatedSong = updatedSong.copy(duration = duration)
                }
                if (mediaMetadata != null && song.song.isVideo != mediaMetadata.isVideoSong) {
                    updatedSong = updatedSong.copy(isVideo = mediaMetadata.isVideoSong)
                }
                if (updatedSong != song.song) {
                    update(updatedSong)
                }
            }
        }

        if (!database.hasRelatedSongs(mediaId)) {
            val relatedEndpoint =
                YouTube.next(WatchEndpoint(videoId = mediaId)).getOrNull()?.relatedEndpoint
                    ?: return
            val relatedPage = YouTube.related(relatedEndpoint).getOrNull() ?: return
            database.query {
                relatedPage.songs
                    .map(SongItem::toMediaMetadata)
                    .onEach(::insert)
                    .map {
                        RelatedSongMap(
                            songId = mediaId,
                            relatedSongId = it.id,
                        )
                    }.forEach(::insert)
            }
        }
    }

    /**
     * Launches [recoverSong] for [mediaId] unless a call for the same mediaId is
     * already in flight, in which case this is a no-op.
     *
     * recoverSong() is called from resolveDataSpec() on every dataSpec/chunk
     * resolution rather than once per song, so without this guard a heavily
     * fragmented (e.g. long-cached) file can fan out dozens of redundant,
     * concurrent recoverSong() coroutines — each doing a Room read, a hop to
     * Dispatchers.Main, and a Room transaction — for work that's already done
     * after the first one completes. Always call this instead of launching
     * recoverSong() directly.
     */
    private fun recoverSongDeduped(
        mediaId: String,
        playbackData: InnerTubeXPlayer.PlaybackData? = null,
    ) {
        if (LocalMusic.isLocalSongId(mediaId)) return
        if (!recoveringSongs.add(mediaId)) return
        scope.launch(Dispatchers.IO) {
            try {
                recoverSong(mediaId, playbackData)
            } finally {
                recoveringSongs.remove(mediaId)
            }
        }
    }

    /**
     * Marks [mediaId] as belonging to the Cache Playlist by setting [dateDownload],
     * but ONLY if the full file (byte 0 through contentLength) is actually present
     * in playerCache. This must only be called from a genuine "track finished
     * naturally" signal (see onMediaItemTransition's AUTO-reason handling) —
     * never from raw dataSpec/chunk resolution, since the player's background
     * prefetch can finish downloading a short file in seconds, long before the
     * user has actually listened to it (or even if they skipped away early).
     *
     * No-op if already marked downloaded, or if the file's content length is unknown.
     */
    private suspend fun markCachedIfFullyDownloaded(mediaId: String) {
        if (LocalMusic.isLocalSongId(mediaId)) return
        val song = database.song(mediaId).first() ?: return
        if (song.song.dateDownload != null || song.song.isDownloaded) return
        val contentLength =
            song.format?.contentLength
                ?: ContentMetadata
                    .getContentLength(playerCache.getContentMetadata(mediaId))
                    .takeIf { it > 0L }
                ?: return
        if (!playerCache.isCached(mediaId, 0, contentLength)) {
            delay(1_000)
            if (!playerCache.isCached(mediaId, 0, contentLength)) return
        }
        database.query {
            update(song.song.copy(dateDownload = java.time.LocalDateTime.now()))
        }
    }

    fun playQueue(
        queue: Queue,
        playWhenReady: Boolean = true,
        restoringQueue: Boolean = false,
        restoredShuffleOrder: IntArray? = null,
    ) {
        if (taskCleared) return
        if (!playerInitialized.value) {
            Timber.tag(TAG).w("playQueue called before player initialization, queuing request")
            scope.launch {
                playerInitialized.first { it }
                playQueue(queue, playWhenReady, restoringQueue, restoredShuffleOrder)
            }
            return
        }

        if (!restoringQueue) queuesStartedSinceCreate++
        currentQueue = queue
        queueTitle = null
        // Review 2026-11-12, finding 20: the old queue's page load (and its radio append) must not outlive it. A slow
        // nextPage() of the old radio used to block every load-more of the new queue until it returned (loadNextPage
        // returns while one is active), so a short first page could end the new queue ("berhenti sendiri").
        shuffleFillJob?.cancel()
        shuffleFillJob = null
        loadMoreJob?.cancel()
        loadMoreJob = null
        radioAppendJob?.cancel()
        radioAppendJob = null
        // 4.3.4 (B): "Acak" asks for shuffle on, "Putar" for off; a tapped song keeps the listener's shuffle while
        // "Acak persisten" is on (the default now), else it starts in order as before
        val shuffleRequest = queue.shuffle
        if (!restoringQueue) {
            if (shuffleRequest != null) {
                player.shuffleModeEnabled = shuffleRequest
            } else if (!dataStore.get(PersistentShuffleAcrossQueuesKey, QueueDefaults.PERSISTENT_SHUFFLE)) {
                player.shuffleModeEnabled = false
            }
        }
        originalQueueSize = 0
        if (queue.preloadItem != null) {
            player.setMediaItem(queue.preloadItem!!.toMediaItem())
            player.prepare()
            player.playWhenReady = playWhenReady
        }
        scope.launch(SilentHandler) {
            val initialStatus =
                withContext(Dispatchers.IO) {
                    queue
                        .getInitialStatus()
                        .filterExplicit(dataStore.get(HideExplicitKey, false))
                        .filterVideoSongs(dataStore.get(HideVideoSongsKey, false))
                }
            if (queue.preloadItem != null && player.playbackState == STATE_IDLE) return@launch
            // a newer queue was started while this one loaded (a tap during the restore of the saved queue): it wins
            if (currentQueue !== queue) return@launch
            if (initialStatus.title != null) {
                queueTitle = initialStatus.title
            }
            if (initialStatus.items.isEmpty()) return@launch
            // Track original queue size for shuffle playlist first feature
            originalQueueSize = initialStatus.items.size
            if (queue.preloadItem != null) {
                player.addMediaItems(
                    0,
                    initialStatus.items.subList(0, initialStatus.mediaItemIndex),
                )
                player.addMediaItems(
                    initialStatus.items.subList(
                        initialStatus.mediaItemIndex + 1,
                        initialStatus.items.size,
                    ),
                )
            } else {
                player.setMediaItems(
                    initialStatus.items,
                    if (initialStatus.mediaItemIndex >
                        0
                    ) {
                        initialStatus.mediaItemIndex
                    } else {
                        0
                    },
                    initialStatus.position,
                )
                player.prepare()
                player.playWhenReady = playWhenReady
            }

            if (player.shuffleModeEnabled) {
                // a restored queue gets its saved order back (songs heard stay heard), a new one starts a new pass
                val restored = restoredShuffleOrder != null && PlayerShuffle.setPlayOrder(player, restoredShuffleOrder)
                if (!restored) {
                    val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
                    applyShuffleOrder(player.currentMediaItemIndex, player.mediaItemCount, shufflePlaylistFirst)
                }
                fillShuffledPlaylist()
            }
        }
    }

    private var shuffleFillJob: kotlinx.coroutines.Job? = null
    private var loadMoreJob: kotlinx.coroutines.Job? = null

    /**
     * 4.3.4 (B): a shuffled YouTube playlist is loaded whole (in the background, page after page) so the shuffle covers
     * all of it; before, the next page came only when the last loaded songs were reached, so the top of a big playlist
     * always played first. The pages join the songs still waiting at random. Respects "Muat lagu lain otomatis".
     */
    private fun fillShuffledPlaylist() {
        val queue = currentQueue
        if (!player.shuffleModeEnabled || queue !is YouTubePlaylistQueue || !cachedAutoLoadMore) return
        if (shuffleFillJob?.isActive == true) return
        shuffleFillJob =
            scope.launch(SilentHandler) {
                // review 2026-11-12, finding 31: a failing page (no network) used to be asked 50 times back to back;
                // now it is retried after a pause and the fill stops after a few failures in a row
                PageFill.run(
                    maxPages = QueueDefaults.SHUFFLE_FILL_MAX_PAGES,
                    maxFailuresInARow = QueueDefaults.SHUFFLE_FILL_MAX_FAILURES,
                    backoffMs = QueueDefaults.SHUFFLE_FILL_BACKOFF_MS,
                    shouldContinue = { isActive && player.shuffleModeEnabled && currentQueue === queue && queue.hasNextPage() },
                ) loadPage@{
                    // one page at a time, also with the load at the end of the queue (they share the continuation)
                    loadMoreJob?.join()
                    if (currentQueue !== queue || !queue.hasNextPage()) return@loadPage true
                    val job = loadNextPage(queue) ?: loadMoreJob
                    job?.join()
                    // a job that ended with an error is cancelled; one we cancelled belongs to a queue that is gone
                    job?.isCancelled != true
                }
            }
    }

    /**
     * Loads the next page of [queue] once (never two requests for the same continuation: fast skipping used to start
     * several, and the same page landed twice). Suggestion pages (radio) are filtered against the queue and, with
     * "Putar otomatis tanpa lagu berulang", against the songs heard in this session.
     */
    private fun loadNextPage(queue: Queue): Job? {
        if (loadMoreJob?.isActive == true) return null
        return scope
            .launch(SilentHandler) {
                var tries = 0
                var lastPage: List<MediaItem> = emptyList()
                while (isActive && currentQueue === queue && queue.hasNextPage() && tries < 3) {
                    tries++
                    val page =
                        withContext(Dispatchers.IO) {
                            queue
                                .nextPage()
                                .filterExplicit(cachedHideExplicit)
                                .filterVideoSongs(cachedHideVideoSongs)
                        }
                    if (currentQueue !== queue || player.playbackState == STATE_IDLE) return@launch
                    lastPage = page
                    val items = if (queue.pagesAreSuggestions) suggestionsToAdd(page, strict = true) else page
                    // the filter runs off the main thread: the queue may have been replaced meanwhile
                    if (currentQueue !== queue) return@launch
                    if (items.isNotEmpty()) {
                        // songs of the same playlist join the waiting ones at random, a radio comes after them
                        val join = if (queue is YouTubePlaylistQueue) PlayerShuffle.Join.MIX else PlayerShuffle.Join.END
                        PlayerShuffle.addItems(player, items, join)
                        return@launch
                    }
                    // a page with nothing new: try the next one (a radio page can repeat the whole queue)
                }
                // only songs heard lately came back: better one of them than silence at the end of the queue
                if (currentQueue === queue && queue.pagesAreSuggestions && player.playbackState != STATE_IDLE) {
                    val again = suggestionsToAdd(lastPage, strict = false)
                    if (currentQueue === queue && player.playbackState != STATE_IDLE) PlayerShuffle.addItems(player, again, PlayerShuffle.Join.END)
                }
            }.also { loadMoreJob = it }
    }

    private val recentPlays = RecentPlays()

    /**
     * Suggestions worth adding: never a song already in the queue (from [keepUpTo] on the queue part that stays) nor
     * twice in one batch; [strict] with "Putar otomatis tanpa lagu berulang" on also never a song heard in this session
     * or the other cut (audio / video) of one.
     */
    private suspend fun suggestionsToAdd(
        items: List<MediaItem>,
        strict: Boolean,
        keepUpTo: Int = player.mediaItemCount - 1,
    ): List<MediaItem> {
        if (items.isEmpty()) return items
        val noRepeat =
            strict && withContext(Dispatchers.IO) { dataStore.get(AutoplayNoRepeatKey, QueueDefaults.AUTOPLAY_NO_REPEAT) }
        // the player is read on the main thread (references only); the title / artist keys of up to 5 000 queued songs
        // are made off it (review 2026-11-12, finding 26: it was ~15 000 regex passes on the main thread per batch)
        val staying = (0..keepUpTo.coerceAtMost(player.mediaItemCount - 1)).map { player.getMediaItemAt(it) }
        return withContext(Dispatchers.Default) {
            val fresh = SuggestionFilter.filter(items, staying, recentPlays, noRepeat)
            // suggested once is enough (BitChord does the same): a song the listener removed does not come back either
            if (noRepeat) fresh.forEach { recentPlays.add(it.metadata) }
            fresh
        }
    }

    /** A radio started from the playing song: filtered like autoplay, but never empty because of the history alone. */
    private suspend fun radioSuggestions(
        items: List<MediaItem>,
        keepUpTo: Int,
    ): List<MediaItem> = suggestionsToAdd(items, strict = true, keepUpTo = keepUpTo).ifEmpty { suggestionsToAdd(items, strict = false, keepUpTo = keepUpTo) }

    fun adoptQueue(queue: Queue, title: String? = null, initialQueueSize: Int = 0) {
        currentQueue = queue
        queueTitle = title
        originalQueueSize = initialQueueSize
    }

    fun startRadioSeamlessly() {
        if (!playerInitialized.value) {
            Timber.tag(TAG).w("startRadioSeamlessly called before player initialization")
            return
        }

        val currentMediaMetadata = player.currentMetadata ?: return

        val currentIndex = player.currentMediaItemIndex
        val currentMediaId = currentMediaMetadata.id
        // a song from the phone has no radio on YouTube
        if (LocalMusic.isLocalSongId(currentMediaId)) return

        scope.launch(SilentHandler) {
            // Use simple videoId to let YouTube personalize recommendations
            val radioQueue =
                YouTubeQueue(
                    endpoint =
                        WatchEndpoint(
                            videoId = currentMediaId,
                        ),
                )

            try {
                val initialStatus =
                    withContext(Dispatchers.IO) {
                        radioQueue
                            .getInitialStatus()
                            .filterExplicit(dataStore.get(HideExplicitKey, false))
                            .filterVideoSongs(dataStore.get(HideVideoSongsKey, false))
                    }

                if (initialStatus.title != null) {
                    queueTitle = initialStatus.title
                }

                val radioItems =
                    radioSuggestions(
                        initialStatus.items.filter { item ->
                            item.mediaId != currentMediaId
                        },
                        currentIndex,
                    )

                if (radioItems.isNotEmpty()) {
                    val itemCount = player.mediaItemCount

                    if (itemCount > currentIndex + 1) {
                        player.removeMediaItems(currentIndex + 1, itemCount)
                    }

                    player.addMediaItems(currentIndex + 1, radioItems)
                    // the radio plays next, in its order; the songs heard stay heard
                    PlayerShuffle.afterAdd(player, (currentIndex + 1) until (currentIndex + 1 + radioItems.size), PlayerShuffle.Join.NEXT)
                }

                currentQueue = radioQueue
            } catch (e: Exception) {
                try {
                    val nextResult =
                        withContext(Dispatchers.IO) {
                            YouTube.next(WatchEndpoint(videoId = currentMediaId)).getOrNull()
                        }
                    nextResult?.relatedEndpoint?.let { relatedEndpoint ->
                        val relatedPage =
                            withContext(Dispatchers.IO) {
                                YouTube.related(relatedEndpoint).getOrNull()
                            }
                        relatedPage?.songs?.let { songs ->
                            val radioItems =
                                radioSuggestions(
                                    songs
                                        .filter { it.id != currentMediaId }
                                        .map { it.toMediaItem() }
                                        .filterExplicit(cachedHideExplicit)
                                        .filterVideoSongs(cachedHideVideoSongs),
                                    currentIndex,
                                )

                            if (radioItems.isNotEmpty()) {
                                val itemCount = player.mediaItemCount
                                if (itemCount > currentIndex + 1) {
                                    player.removeMediaItems(currentIndex + 1, itemCount)
                                }
                                player.addMediaItems(currentIndex + 1, radioItems)
                                PlayerShuffle.afterAdd(
                                    player,
                                    (currentIndex + 1) until (currentIndex + 1 + radioItems.size),
                                    PlayerShuffle.Join.NEXT,
                                )
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun getAutomix(playlistId: String) {
        if (playlistId.isBlank()) return
        if (dataStore.get(SimilarContent, true) &&
            !(dataStore.get(DisableLoadMoreWhenRepeatAllKey, false) && player.repeatMode == REPEAT_MODE_ALL)
        ) {
            scope.launch(SilentHandler) {
                try {
                    YouTube
                        .next(WatchEndpoint(playlistId = playlistId))
                        .onSuccess { firstResult ->
                            YouTube
                                .next(WatchEndpoint(playlistId = firstResult.endpoint.playlistId))
                                .onSuccess { secondResult ->
                                    automixItems.value =
                                        secondResult.items.map { song ->
                                            song.toMediaItem()
                                        }
                                }.onFailure {
                                    if (firstResult.items.isNotEmpty()) {
                                        automixItems.value =
                                            firstResult.items.map { song ->
                                                song.toMediaItem()
                                            }
                                    }
                                }
                        }.onFailure {
                            val currentSong = player.currentMetadata
                            if (currentSong != null) {
                                // Use simple videoId for better personalized recommendations
                                YouTube
                                    .next(
                                        WatchEndpoint(
                                            videoId = currentSong.id,
                                        ),
                                    ).onSuccess { radioResult ->
                                        val filteredItems =
                                            radioResult.items
                                                .filter { it.id != currentSong.id }
                                                .map { it.toMediaItem() }
                                        if (filteredItems.isNotEmpty()) {
                                            automixItems.value = filteredItems
                                        }
                                    }.onFailure {
                                        YouTube
                                            .next(WatchEndpoint(videoId = currentSong.id))
                                            .getOrNull()
                                            ?.relatedEndpoint
                                            ?.let { relatedEndpoint ->
                                                YouTube.related(relatedEndpoint).onSuccess { relatedPage ->
                                                    val relatedItems =
                                                        relatedPage.songs
                                                            .filter { it.id != currentSong.id }
                                                            .map { it.toMediaItem() }
                                                    if (relatedItems.isNotEmpty()) {
                                                        automixItems.value = relatedItems
                                                    }
                                                }
                                            }
                                    }
                            }
                        }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun addToQueueAutomix(
        item: MediaItem,
        position: Int,
    ) {
        automixItems.value =
            automixItems.value.toMutableList().apply {
                removeAt(position)
            }
        addToQueue(listOf(item))
    }

    fun playNextAutomix(
        item: MediaItem,
        position: Int,
    ) {
        automixItems.value =
            automixItems.value.toMutableList().apply {
                removeAt(position)
            }
        playNext(listOf(item))
    }

    fun clearAutomix() {
        automixItems.value = emptyList()
    }

    /**
     * Runs [block] once the player exists. A menu tap can arrive while the service is still starting (or when the system refused to start it
     * as a foreground service and the player was never built): waiting a little and then giving up is better than crashing the app.
     */
    private fun whenPlayerReady(block: () -> Unit) {
        if (playerInitialized.value) {
            block()
            return
        }
        scope.launch {
            val ready =
                kotlinx.coroutines.withTimeoutOrNull(8_000) { playerInitialized.first { it } }
            if (ready == true) {
                try {
                    block()
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Queue action failed after the player became ready")
                }
            }
        }
    }

    fun playNext(items: List<MediaItem>) = whenPlayerReady { playNextNow(items) }

    private fun playNextNow(items: List<MediaItem>) {
        // If queue is empty or player is idle, play immediately instead
        if (player.mediaItemCount == 0 || player.playbackState == STATE_IDLE) {
            player.setMediaItems(items)
            player.prepare()
            if (castConnectionHandler?.isCasting?.value != true) {
                player.play()
            }
            return
        }

        if (dataStore.get(PreventDuplicateTracksInQueueKey, false)) {
            val itemIds = items.map { it.mediaId }.toSet()
            val indicesToRemove = mutableListOf<Int>()
            val currentIndex = player.currentMediaItemIndex

            for (i in 0 until player.mediaItemCount) {
                if (i != currentIndex && player.getMediaItemAt(i).mediaId in itemIds) {
                    indicesToRemove.add(i)
                }
            }

            // Remove from highest index to lowest to maintain index stability
            indicesToRemove.sortedDescending().forEach { index ->
                player.removeMediaItem(index)
            }
        }

        val insertIndex = player.currentMediaItemIndex + 1

        // Insert items immediately after the current item in the window/index space
        player.addMediaItems(insertIndex, items)
        player.prepare()

        // with shuffle on they also play next: songs heard before stay heard, the rest keeps its order (4.3.4, B)
        PlayerShuffle.afterAdd(player, insertIndex until (insertIndex + items.size), PlayerShuffle.Join.NEXT)
    }

    fun addToQueue(items: List<MediaItem>) = whenPlayerReady { addToQueueNow(items) }

    private fun addToQueueNow(items: List<MediaItem>) {
        if (dataStore.get(PreventDuplicateTracksInQueueKey, false)) {
            val itemIds = items.map { it.mediaId }.toSet()
            val indicesToRemove = mutableListOf<Int>()
            val currentIndex = player.currentMediaItemIndex

            for (i in 0 until player.mediaItemCount) {
                if (i != currentIndex && player.getMediaItemAt(i).mediaId in itemIds) {
                    indicesToRemove.add(i)
                }
            }

            // Remove from highest index to lowest to maintain index stability
            indicesToRemove.sortedDescending().forEach { index ->
                player.removeMediaItem(index)
            }
        }

        // 4.3.4 (B): with shuffle on the songs go to the end of the play order. The old full reshuffle put the songs
        // already heard back in front of the playing one, so they played again
        PlayerShuffle.addItems(player, items, PlayerShuffle.Join.END)
        player.prepare()
    }

    fun toggleLibrary() {
        scope.launch {
            val songToToggle = currentSong.first()
            songToToggle?.let {
                val isInLibrary = it.song.inLibrary != null
                val token = if (isInLibrary) it.song.libraryRemoveToken else it.song.libraryAddToken

                token?.let { feedbackToken ->
                    YouTube.feedback(listOf(feedbackToken))
                }

                database.query {
                    update(it.song.toggleLibrary())
                }
                currentMediaMetadata.value = player.currentMetadata
            }
        }
    }

    fun toggleLike() {
        scope.launch {
            val songToToggle = currentSong.first()
            songToToggle?.let { librarySong ->
                val songEntity = librarySong.song

                // For podcast episodes, toggle save for later instead of like
                if (songEntity.isEpisode) {
                    toggleEpisodeSaveForLater(songEntity)
                    return@let
                }

                val song = songEntity.toggleLike(syncToYouTube = false)

                updateNotification(isLiked = song.liked)
                updateWidgetUI(player.isPlaying, isLiked = song.liked)

                database.query {
                    update(song)
                    syncUtils.likeSong(song)

                    if (dataStore.get(AutoDownloadOnLikeKey, false) && song.liked && !song.isLocal) {
                        downloadUtil.download(song.id)
                    }
                }
                currentMediaMetadata.value = player.currentMetadata
            }
        }
    }

    fun addToTargetPlaylist() {
        scope.launch {
            val currentSong = currentSong.first() ?: return@launch
            val targetPlaylistId = dataStore.get(AndroidAutoTargetPlaylistKey, MediaSessionConstants.TARGET_PLAYLIST_AUTO)

            if (targetPlaylistId == MediaSessionConstants.TARGET_PLAYLIST_AUTO) {
                Handler(Looper.getMainLooper()).post {
                    Toast
                        .makeText(
                            this@MusicService,
                            getString(R.string.android_auto_target_playlist_not_set),
                            Toast.LENGTH_SHORT,
                        ).show()
                }
                return@launch
            }

            val targetPlaylist = database.playlist(targetPlaylistId).first()
            if (targetPlaylist != null) {
                val addToPlaylistPosition =
                    dataStore
                        .get(AddToPlaylistPositionKey, AddToPlaylistPosition.BEGINNING.name)
                        .toEnum(AddToPlaylistPosition.BEGINNING)
                database.addSongsToPlaylist(
                    targetPlaylist,
                    listOf(currentSong.id to null),
                    prepend = addToPlaylistPosition.prepend,
                )
            }
        }
    }

    private suspend fun toggleEpisodeSaveForLater(songEntity: com.metrolist.music.db.entities.SongEntity) {
        val isCurrentlySaved = songEntity.inLibrary != null
        val shouldBeSaved = !isCurrentlySaved

        updateNotification(isLiked = shouldBeSaved)
        updateWidgetUI(player.isPlaying, isLiked = shouldBeSaved)

        // Update database first (optimistic update)
        // Also ensure isEpisode = true so it appears in saved episodes list
        database.query {
            update(
                songEntity.copy(
                    inLibrary = if (isCurrentlySaved) null else java.time.LocalDateTime.now(),
                    isEpisode = true,
                ),
            )
        }
        currentMediaMetadata.value = player.currentMetadata

        // Sync with YouTube (handles login check internally)
        val setVideoId = if (isCurrentlySaved) database.getSetVideoId(songEntity.id)?.setVideoId else null
        syncUtils.saveEpisode(songEntity.id, shouldBeSaved, setVideoId)
    }

    fun toggleStartRadio() {
        startRadioSeamlessly()
    }

    private fun seedLoudnessCacheFromPrefs() {
        val prefs = startupPrefs!!
        normalizationEnabledCached = prefs[AudioNormalizationKey] ?: true
        loudnessLevelCached = prefs[LoudnessLevelKey].toEnum(LoudnessGain.DEFAULT_LEVEL)

        Timber.tag(TAG).d(
            "Seeded loudness cache: normalization=$normalizationEnabledCached, level=$loudnessLevelCached"
        )
    }

    private fun applyCachedAudioNormalizationNow() {
        if (isCrossfading) return
        val processor = playerNormalizationProcessors[player] ?: return
        try {
            val gain = cachedNormalizationGainMb
            if (cachedNormalizationMediaId == player.currentMediaItem?.mediaId &&
                cachedNormalizationEnabled &&
                gain != null
            ) {
                processor.setTargetGain(gain)
                processor.enabled = true
            } else {
                processor.enabled = false
            }
        } catch (e: Exception) {
            reportException(e)
            processor.enabled = false
        }
    }

    private fun applyAudioNormalization(
        processor: VolumeNormalizationAudioProcessor,
        mediaId: String,
        loudnessDb: Double?,
        perceptualLoudnessDb: Double?,
        updateCache: Boolean,
    ) {
        val gain =
            if (normalizationEnabledCached) {
                normalizationGainMb(loudnessDb, perceptualLoudnessDb, loudnessLevelCached.targetLufs)
            } else {
                null
            }

        if (gain != null) {
            processor.setTargetGain(gain)
            processor.enabled = true
        } else {
            processor.setTargetGain(0)
            processor.enabled = false
        }

        if (updateCache) {
            cachedNormalizationMediaId = mediaId
            cachedNormalizationGainMb = gain
            cachedNormalizationEnabled = gain != null
        }
    }

    private fun applyAudioNormalizationBeforePlayback(
        processor: VolumeNormalizationAudioProcessor,
        playerProvider: () -> ExoPlayer?,
        mediaId: String,
        loudnessDb: Double?,
        perceptualLoudnessDb: Double?,
        preserveCachedIfMissing: Boolean = false,
    ) = runBlocking(Dispatchers.Main.immediate) {
        val targetPlayer = playerProvider() ?: return@runBlocking
        if (playerNormalizationProcessors[targetPlayer] !== processor ||
            targetPlayer.currentMediaItem?.mediaId != mediaId
        ) {
            return@runBlocking
        }

        val isCurrentPlayer = ::player.isInitialized && targetPlayer === player
        if (preserveCachedIfMissing &&
            loudnessDb == null &&
            perceptualLoudnessDb == null &&
            isCurrentPlayer &&
            cachedNormalizationMediaId == mediaId
        ) {
            return@runBlocking
        }
        if (isCurrentPlayer) {
            loudnessSetupGeneration++
            loudnessSetupJob?.cancel()
            loudnessSetupJob = null
        }
        applyAudioNormalization(
            processor = processor,
            mediaId = mediaId,
            loudnessDb = loudnessDb,
            perceptualLoudnessDb = perceptualLoudnessDb,
            updateCache = isCurrentPlayer,
        )
    }

    private fun setupAudioNormalization() {
        val requestGeneration = ++loudnessSetupGeneration
        loudnessSetupJob?.cancel()

        loudnessSetupJob = scope.launch {
            try {
                val currentMediaId = withContext(Dispatchers.Main) {
                    player.currentMediaItem?.mediaId
                }

                val normalizeAudio = normalizationEnabledCached

                if (normalizeAudio && currentMediaId != null) {
                    val format = withContext(Dispatchers.IO) {
                        database.format(currentMediaId).first()
                    }

                    Timber.tag(TAG).d("Audio normalization enabled: $normalizeAudio")

                    withContext(Dispatchers.Main) {
                        if (!isActive || requestGeneration != loudnessSetupGeneration) return@withContext
                        if (player.currentMediaItem?.mediaId != currentMediaId) return@withContext

                        val processor = playerNormalizationProcessors[player] ?: return@withContext
                        if (format != null || cachedNormalizationMediaId != currentMediaId) {
                            applyAudioNormalization(
                                processor = processor,
                                mediaId = currentMediaId,
                                loudnessDb = format?.loudnessDb,
                                perceptualLoudnessDb = format?.perceptualLoudnessDb,
                                updateCache = true,
                            )
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        if (!isActive || requestGeneration != loudnessSetupGeneration) return@withContext
                        cachedNormalizationMediaId = null
                        cachedNormalizationGainMb = null
                        cachedNormalizationEnabled = false
                        playerNormalizationProcessors.values.forEach { it.enabled = false }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportException(e)
                playerNormalizationProcessors.values.forEach { it.enabled = false }
            }
        }
    }

    private fun openAudioEffectSession() {
        val audioSessionId = player.audioSessionId
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId <= 0) {
            return
        }

        if (isAudioEffectSessionOpened && openedAudioEffectSessionId == audioSessionId) {
            return
        }

        if (isAudioEffectSessionOpened && openedAudioEffectSessionId > 0) {
            closeAudioEffectSession(sessionIdOverride = openedAudioEffectSessionId, clearNormalizationCache = false)
        }

        isAudioEffectSessionOpened = true
        openedAudioEffectSessionId = audioSessionId

        sendBroadcast(
            Intent(android.media.audiofx.AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(android.media.audiofx.AudioEffect.EXTRA_CONTENT_TYPE, android.media.audiofx.AudioEffect.CONTENT_TYPE_MUSIC)
            },
        )
    }

    private fun closeAudioEffectSession(sessionIdOverride: Int? = null, clearNormalizationCache: Boolean = true) {
        val sessionIdToClose = sessionIdOverride ?: openedAudioEffectSessionId

        loudnessSetupGeneration++
        loudnessSetupJob?.cancel()
        loudnessSetupJob = null

        // Guard: only release/reset state if closing the currently active session
        val isClosingCurrentSession =
            isAudioEffectSessionOpened &&
                    openedAudioEffectSessionId != C.AUDIO_SESSION_ID_UNSET &&
                    sessionIdToClose == openedAudioEffectSessionId

        if (isClosingCurrentSession) {

            isAudioEffectSessionOpened = false
            openedAudioEffectSessionId = C.AUDIO_SESSION_ID_UNSET
        }

        if (sessionIdToClose != C.AUDIO_SESSION_ID_UNSET && sessionIdToClose > 0) {
            sendBroadcast(
                Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                    putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionIdToClose)
                    putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                },
            )
        }
    }

    private var previousMediaItemIndex = C.INDEX_UNSET
    private var previousEpisodeId: String? = null

    // Tracks the mediaId that was playing immediately before the current
    // onMediaItemTransition call, so we can decide whether IT finished
    // naturally (and is therefore safe to mark as fully cached).
    private var lastTransitionedMediaId: String? = null
    private var previousEpisodePosition: Long = 0L

    /**
     * Save podcast episode playback position to database.
     * Only saves if the item is an episode and position is meaningful (> 3 seconds).
     */
    private fun saveEpisodePosition(
        episodeId: String,
        positionMs: Long,
    ) {
        if (positionMs < 3000) return // Don't save if less than 3 seconds played
        scope.launch(Dispatchers.IO + SilentHandler) {
            database.updatePlaybackPosition(episodeId, positionMs)
            Timber.tag(TAG).d("Saved episode position: $episodeId at ${positionMs}ms")
        }
    }

    /**
     * Restore podcast episode playback position from database.
     * Seeks to saved position if available.
     */
    private fun restoreEpisodePosition(episodeId: String) {
        scope.launch(Dispatchers.IO + SilentHandler) {
            val savedPosition = database.getPlaybackPosition(episodeId)
            if (savedPosition != null && savedPosition > 0) {
                withContext(Dispatchers.Main) {
                    if (player.currentMediaItem?.mediaId == episodeId) {
                        player.seekTo(savedPosition)
                        Timber.tag(TAG).d("Restored episode position: $episodeId to ${savedPosition}ms")
                    }
                }
            }
        }
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        // Only natural completion transitions mark the previous track as fully cached,
        // never a manual skip or seek. Read lastTransitionedMediaId before replacing it.
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
        ) {
            lastTransitionedMediaId?.let { previousId ->
                scope.launch(Dispatchers.IO) { markCachedIfFullyDownloaded(previousId) }
            }
        }
        lastTransitionedMediaId = mediaItem?.mediaId
        currentItemPlayedWallMs = 0L
        currentItemUserSeekForwardMs = 0L
        retryJob?.cancel()
        retryJob = null
        retryOnValidatedNetwork = null
        // another song: a stop after connection errors is not resumed over it
        if (unreachableResume?.mediaId != mediaItem?.mediaId) unreachableResume = null
        // the extractions of songs that are neither this one nor the next stop when nobody waits for them (finding 27)
        retainWantedExtractions()
        if (skippingAfterFailure) {
            skippingAfterFailure = false
        } else if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK || reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
            // the user picked this song or started a new queue: it gets a fresh set of tries
            recovery.onUserAction(mediaItem?.mediaId)
        }
        syncWatchdog()

        previousEpisodeId?.let { episodeId ->
            if (previousEpisodePosition > 0) {
                saveEpisodePosition(episodeId, previousEpisodePosition)
            }
        }
        previousEpisodeId = null
        previousEpisodePosition = 0L

        val newMetadata = mediaItem?.metadata
        if (newMetadata?.isEpisode == true) {
            previousEpisodeId = newMetadata.id
            scope.launch {
                delay(100)
                restoreEpisodePosition(newMetadata.id)
            }
        }

        // Force Repeat One if the player ignored it and auto-advanced
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            if (player.repeatMode == REPEAT_MODE_ONE &&
                previousMediaItemIndex != C.INDEX_UNSET &&
                previousMediaItemIndex != player.currentMediaItemIndex
            ) {
                player.seekTo(previousMediaItemIndex, 0)
            }
        }
        previousMediaItemIndex = player.currentMediaItemIndex

        lastPlaybackSpeed = -1.0f // force update song

        setupAudioNormalization()

        scrobbleManager?.onSongStop()
        if (player.playWhenReady && player.playbackState == Player.STATE_READY) {
            scrobbleManager?.onSongStart(player.currentMetadata, duration = player.duration)
        }

        // Skip if this change was triggered by Cast sync (to prevent loops)
        if (castConnectionHandler?.isCasting?.value == true &&
            castConnectionHandler?.isSyncingFromCast != true &&
            mediaItem != null
        ) {
            val metadata = mediaItem.metadata
            if (metadata != null) {
                // Try to navigate to the item if it's already in Cast queue
                // This avoids a full reload which causes the widget to refresh
                val navigated = castConnectionHandler?.navigateToMediaIfInQueue(metadata.id) ?: false
                if (!navigated) {
                    castConnectionHandler?.loadMedia(metadata)
                }
            }
        }

        // 4.3.4 (B), queue only: remember the song for "tanpa lagu berulang"; with repeat all + shuffle the last song of
        // the pass gives the next pass a new random order; "near the end" is counted in the PLAY order (with shuffle on
        // the timeline index says nothing: a radio used to be added in the middle of a shuffled playlist)
        recentPlays.add(mediaItem?.metadata)
        PlayerShuffle.newPassIfLast(player)
        val remainingInPlayOrder = ShuffleOrders.remainingAfter(player, limit = 5)

        if (cachedAutoLoadMore &&
            reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
            remainingInPlayOrder <= 4 &&
            currentQueue.hasNextPage() &&
            !(cachedDisableLoadMoreWhenRepeatAll && player.repeatMode == REPEAT_MODE_ALL)
        ) {
            loadNextPage(currentQueue)
        }

        // A playlist / library queue has no next page. When its last song starts, quietly line up a radio built from that
        // song so the music keeps going instead of stopping when the playlist ends.
        if (cachedAutoLoadMore &&
            cachedAutoplay &&
            reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
            player.repeatMode == REPEAT_MODE_OFF &&
            remainingInPlayOrder == 0 &&
            !currentQueue.hasNextPage()
        ) {
            appendRadioAfterQueue()
        }

        if (cachedPersistentQueue) {
            saveQueueToDisk()
        }
    }

    private var radioAppendJob: kotlinx.coroutines.Job? = null

    private fun appendRadioAfterQueue() {
        if (radioAppendJob?.isActive == true) return
        val last = player.currentMetadata ?: return
        if (LocalMusic.isLocalSongId(last.id)) return
        radioAppendJob =
            scope.launch(SilentHandler) {
                val radioQueue = YouTubeQueue(endpoint = WatchEndpoint(videoId = last.id))
                val status =
                    withContext(Dispatchers.IO) {
                        radioQueue
                            .getInitialStatus()
                            .filterExplicit(dataStore.get(HideExplicitKey, false))
                            .filterVideoSongs(dataStore.get(HideVideoSongsKey, false))
                    }
                // the song may have changed (or the queue grown) while the request was running
                if (player.playbackState == STATE_IDLE) return@launch
                if (ShuffleOrders.remainingAfter(player, limit = 1) > 0) return@launch
                // nothing already queued, nothing heard in this session (setting), never empty because of that alone
                val items = radioSuggestions(status.items, player.mediaItemCount - 1)
                if (items.isEmpty()) return@launch
                // the filter ran off the main thread: the queue may have grown or ended meanwhile
                if (player.playbackState == STATE_IDLE || ShuffleOrders.remainingAfter(player, limit = 1) > 0) return@launch
                PlayerShuffle.addItems(player, items, PlayerShuffle.Join.END)
                currentQueue = radioQueue
            }
    }

    override fun onPlaybackStateChanged(
        @Player.State playbackState: Int,
    ) {
        syncWatchdog()

        if (playbackState == Player.STATE_ENDED) {
            player.currentMediaItem?.mediaId?.let { mediaId ->
                scope.launch(Dispatchers.IO) { markCachedIfFullyDownloaded(mediaId) }
            }

            // Check sleep timer guard - don't autoplay/repeat if sleep timer will pause
            val timer = sleepTimer ?: return
            if (timer.isActive && timer.pauseWhenSongEnd) {
                return
            }

            val repeatMode = player.repeatMode

            if (player.playWhenReady && repeatMode == REPEAT_MODE_ALL && player.mediaItemCount > 0) {
                // the first song of the PLAY order (with shuffle on, timeline index 0 is just the top of the list)
                player.seekTo(player.currentTimeline.getFirstWindowIndex(player.shuffleModeEnabled).coerceAtLeast(0), 0)
                player.prepare()
                player.play()
                return
            }

            if (player.playWhenReady && repeatMode == REPEAT_MODE_ONE) {
                player.seekTo(player.currentMediaItemIndex, 0)
                player.prepare()
                player.play()
                return
            }

            if (player.playWhenReady && cachedAutoplay && player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                if (castConnectionHandler?.isCasting?.value != true) {
                    player.play()
                }
            }
        }

        // Save state when playback state changes (but not during silence skipping)
        if (cachedPersistentQueue && !isSilenceSkipping) {
            saveQueueToDisk()
        }

        if (playbackState == Player.STATE_READY) {
            retryCount = 0
            waitingForNetworkConnection.value = false
            retryJob?.cancel()
            // READY alone does not reset the tries of a song any more: a song that was ready for a moment and then failed
            // reset them each time, and a whole playlist ran through in seconds. Real progress resets them (PlaybackRecovery).
            scheduleCrossfade()
        }

        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
            scrobbleManager?.onSongStop()
        }
    }

    override fun onPlayWhenReadyChanged(
        playWhenReady: Boolean,
        reason: Int,
    ) {
        // Safety net: if local player tries to start while casting, immediately pause it
        if (playWhenReady && castConnectionHandler?.isCasting?.value == true) {
            player.pause()
            return
        }

        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
            // our own pause after connection errors comes here first; any later play/pause is the user's choice
            unreachableResume?.let { resume ->
                if (!playWhenReady && !resume.ownPauseSeen) resume.ownPauseSeen = true else unreachableResume = null
            }
            if (playWhenReady) {
                isPausedByVolumeMute = false
            }

            if (!playWhenReady && !isPausedByVolumeMute) {
                wasPlayingBeforeVolumeMute = false
            }
        }

        if (!playWhenReady) {
            val currentMetadata = player.currentMediaItem?.metadata
            if (currentMetadata?.isEpisode == true && player.currentPosition > 0) {
                saveEpisodePosition(currentMetadata.id, player.currentPosition)
                previousEpisodePosition = player.currentPosition
            }
        }

        if (playWhenReady) {
            applyCachedAudioNormalizationNow()
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                // the user pressed play (or retry): the song gets a fresh set of tries
                recovery.onUserAction(player.currentMediaItem?.mediaId)
            }
        }

        syncWatchdog()
    }

    /** The current and the next song may keep a stream extraction nobody waits for; the others are cancelled (finding 27). */
    private fun retainWantedExtractions() {
        if (!::player.isInitialized) return
        val next = player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET && it < player.mediaItemCount }
        extractionGate.retainOnly(setOfNotNull(player.currentMediaItem?.mediaId, next?.let { player.getMediaItemAt(it).mediaId }))
    }

    /**
     * Main thread, from [PlaybackNetworkWatcher]: a validated default network appeared (finding 6).
     *  - a retry waiting after a connection error runs now instead of after its pause;
     *  - playback that stopped after [PlaybackRecovery.UNREACHABLE_BUDGET_MS] of connection errors resumes, but only on
     *    the same song and only when nobody pressed play or pause since (the user's choice always wins).
     */
    private fun onValidatedNetworkAppeared() {
        if (!::player.isInitialized) return
        retryOnValidatedNetwork?.let { retry ->
            retryOnValidatedNetwork = null
            retryJob?.cancel()
            retryJob = null
            Timber.tag(TAG).d("Validated network: retrying at once")
            retry()
            return
        }
        val resume = unreachableResume ?: return
        unreachableResume = null
        val current = player.currentMediaItem?.mediaId
        if (current != resume.mediaId || player.playWhenReady || player.playbackState != STATE_IDLE) return
        if (castConnectionHandler?.isCasting?.value == true) return
        Timber.tag(TAG).i("Validated network after connection errors: resuming $current")
        recovery.onNetworkBack()
        songUrlCache.invalidate(resume.mediaId)
        extractionGate.forget(resume.mediaId)
        val index = player.currentMediaItemIndex
        if (index != C.INDEX_UNSET) player.seekTo(index, player.currentPosition)
        player.prepare()
        player.play()
    }

    /** Runs the stall watchdog once a second while playback is wanted, and not at all while paused (no wake-ups). */
    private fun syncWatchdog() {
        val wanted = ::player.isInitialized && player.playWhenReady && player.playbackState != Player.STATE_ENDED
        if (!wanted) {
            watchdogJob?.cancel()
            watchdogJob = null
            return
        }
        if (watchdogJob?.isActive == true) return
        watchdogJob =
            scope.launch {
                while (isActive) {
                    delay(1_000L)
                    watchdogTick()
                }
            }
    }

    private fun watchdogTick() {
        if (!playerInitialized.value || isCrossfading) return
        val mediaId = player.currentMediaItem?.mediaId
        val position = player.currentPosition
        recovery.onProgress(mediaId, position, player.isPlaying)
        if (player.isPlaying) currentItemPlayedWallMs += 1_000L
        val casting = castConnectionHandler?.isCasting?.value == true
        val action =
            stallWatchdog.onTick(
                mediaId = mediaId,
                // the wait for a stream address has its own limit (ExtractionGate): not counted as a stall
                buffering = player.playbackState == Player.STATE_BUFFERING && (mediaId == null || (resolvingNow[mediaId] ?: 0) == 0),
                playing = player.isPlaying,
                playWhenReady = player.playWhenReady,
                positionMs = position,
                networkUp = isNetworkConnected.value,
                eligible = mediaId != null && !LocalMusic.isLocalSongId(mediaId) && !casting && retryJob?.isActive != true,
            )
        when (action) {
            is StallWatchdog.Action.Recover -> recoverStalledStream(mediaId ?: return, action.attempt)
            is StallWatchdog.Action.GiveUp -> {
                val id = mediaId ?: return
                Timber.tag(TAG).w("Stream of $id still stalled after every recovery (neverStarted=${action.neverStarted})")
                applyRecovery(recovery.onStallGiveUp(id, action.neverStarted, canSkipOnFailure()), id, songUrlCache.clientName(id))
            }
            StallWatchdog.Action.None -> Unit
        }
    }

    /**
     * Report 2 (pause icon, time at 0:00 for ever): a stream that stays in "buffering" gets a fresh address and the song is
     * prepared again at its position. stop() is what really cancels a load that hangs: a seek to the same position while
     * buffering is ignored by ExoPlayer and prepare() is ignored unless the player is idle (the old guard did only those).
     */
    private fun recoverStalledStream(
        mediaId: String,
        attempt: Int,
    ) {
        val client = songUrlCache.clientName(mediaId)
        Timber.tag(TAG).w("Stream stalled: mediaId=$mediaId attempt=$attempt client=$client")
        songUrlCache.invalidate(mediaId)
        extractionGate.forget(mediaId)
        if (attempt >= 2 && client != null) InnerTubeXPlayer.markStreamClientFailed(mediaId, client)
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        player.stop()
        if (index != C.INDEX_UNSET) player.seekTo(index, position)
        player.prepare()
    }

    override fun onEvents(
        player: Player,
        events: Player.Events,
    ) {
        if (events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
            )
        ) {
            scheduleCrossfade()
            val isBufferingOrReady =
                player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY
            if (isBufferingOrReady && player.playWhenReady) {
                val focusGranted = requestAudioFocus()
                if (focusGranted) {
                    openAudioEffectSession()
                }
            } else if (player.playbackState == Player.STATE_IDLE) {
                closeAudioEffectSession()
            }
        }
        if (events.containsAny(EVENT_TIMELINE_CHANGED, EVENT_POSITION_DISCONTINUITY)) {
            currentMediaMetadata.value = player.currentMetadata
        }
        // "play next", a removed song, a new shuffle order: the next song may be another one now
        if (events.contains(EVENT_TIMELINE_CHANGED)) retainWantedExtractions()

        if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED)) {
            updateWidgetUI(player.isPlaying)
            if (player.isPlaying) {
                // Defensive: a play after a pause, a song change or a focus event must never leave the music quieter than
                // the user's volume while no duck is active.
                audioFocusCoordinator?.onPlaybackResumed()
                discordIntentionalDisconnect = false
                screenOffHandler.removeCallbacks(screenOffTimeout)
                screenOffHandler.removeCallbacks(pauseTimeout)
                startWidgetUpdates()
            } else {
                stopWidgetUpdates()
                if (isScreenOff) {
                    screenOffHandler.removeCallbacks(screenOffTimeout)
                    screenOffHandler.postDelayed(screenOffTimeout, 600_000)
                } else {
                    screenOffHandler.postDelayed(pauseTimeout, 60_000)
                }
            }
        }

        if (events.containsAny(
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_IS_PLAYING_CHANGED,
            )
        ) {
            syncDiscordState()
        }

        if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED)) {
            scrobbleManager?.onPlayerStateChanged(player.isPlaying, player.currentMetadata, duration = player.duration)
        }
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        updateNotification()
        if (shuffleModeEnabled) {
            if (player.mediaItemCount == 0) return

            val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
            val currentIndex = player.currentMediaItemIndex
            val totalCount = player.mediaItemCount

            applyShuffleOrder(currentIndex, totalCount, shufflePlaylistFirst)
            // a YouTube playlist that is only partly loaded: load the rest so the shuffle covers all of it (B)
            fillShuffledPlaylist()
        }

        if (dataStore.get(RememberShuffleAndRepeatKey, true)) {
            scope.launch {
                safeDataStoreEdit { settings ->
                    settings[ShuffleModeKey] = shuffleModeEnabled
                }
            }
        }

        if (cachedPersistentQueue) {
            saveQueueToDisk()
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        updateNotification()
        scope.launch {
            safeDataStoreEdit { settings ->
                settings[RepeatModeKey] = repeatMode
            }
        }

        if (cachedPersistentQueue) {
            saveQueueToDisk()
        }
    }

    /**
     * Applies a new shuffle order to the player, maintaining the current item's position.
     * If `shufflePlaylistFirst` is true, it attempts to shuffle original items separately from added items.
     */
    private fun applyShuffleOrder(
        currentIndex: Int,
        totalCount: Int,
        shufflePlaylistFirst: Boolean,
    ) {
        if (totalCount == 0 || totalCount != player.mediaItemCount) return
        // 4.3.4 (B): a new pass, the playing song first, every other song once in a uniform random order. The order is a
        // QueueShuffleOrder, so songs added later go after the waiting ones instead of a random place of the pass.
        PlayerShuffle.startPass(player, listFirst = shufflePlaylistFirst, originalSize = originalQueueSize)
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        super.onPlaybackParametersChanged(playbackParameters)
        if (playbackParameters.speed != lastPlaybackSpeed) {
            Timber.tag("DiscordSvc").d("onPlaybackParametersChanged: speed changed %s -> %s", lastPlaybackSpeed, playbackParameters.speed)
            lastPlaybackSpeed = playbackParameters.speed
            DiscordRpcManager.notifySettingsChanged()
            scope.launch {
                delay(1000)
                if (player.playWhenReady && player.playbackState == Player.STATE_READY) {
                    syncDiscordState()
                }
            }
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)

        if (!playerInitialized.value) {
            Timber.tag(TAG).e(error, "Player error occurred but player not initialized")
            return
        }

        val mediaId = player.currentMediaItem?.mediaId
        val kind = PlaybackFailures.classify(error, isLocalSong = LocalMusic.isLocalSongId(mediaId))
        val failedStreamClient = mediaId?.let(songUrlCache::clientName)
        Timber
            .tag(TAG)
            .w(error, "Player error for $mediaId: code=${error.errorCode} kind=$kind client=$failedStreamClient")
        reportException(error)

        if (mediaId == null) {
            stopOnError()
            return
        }
        // Round 4.3.5: bytes that do not parse = a broken copy on the phone (a download or cache made of two files by
        // 4.3.3/4.3.4). It is not read again: the retries below play the song from the network at the same position, and a
        // download is marked as not downloaded and downloaded again (once per run, so a bad network copy cannot loop).
        if (kind == PlaybackFailureKind.CACHE_MISMATCH && PlaybackFailures.isParseError(error)) {
            clearPlayerCacheOf(mediaId)
            songUrlCache.invalidate(mediaId)
            val wasDownload = runCatching { downloadUtil.repairBrokenDownload(mediaId) }.getOrDefault(false)
            if (wasDownload) noticeFailure(R.string.auralys_download_repaired, throttle = false)
        }
        // a song that cannot be played (every client and the alternative tried) is skipped whenever there is a next song,
        // with a short message, instead of leaving the error view on screen (round 4.3.5)
        val canSkip =
            if (kind == PlaybackFailureKind.SONG_UNAVAILABLE) player.nextMediaItemIndex != C.INDEX_UNSET else canSkipOnFailure()
        val decision = recovery.onError(mediaId, kind, networkUp = isNetworkConnected.value, canSkip = canSkip)
        Timber.tag(TAG).d("Recovery for $mediaId: $decision")
        applyRecovery(decision, mediaId, failedStreamClient, httpStatus = PlaybackFailures.httpStatus(error))
    }

    /**
     * Round 4.3.5: the title, artists and length of [mediaId] for the last-resort alternative upload (InnerTubeXPlayer,
     * AlternativeMatch); null for songs the database does not know.
     */
    private fun alternativeSongOf(mediaId: String): AlternativeMatch.Song? =
        runCatching { database.getSongByIdBlocking(mediaId) }.getOrNull()?.let { s ->
            AlternativeMatch.Song(
                videoId = mediaId,
                title = s.song.title,
                artists = s.artists.map { it.name },
                durationSeconds = s.song.duration.takeIf { it > 0 },
            )
        }

    /** The plain message for a song no client could give (shown on the player's error view). */
    private fun streamFailureText(error: Throwable): Int {
        val reason =
            generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }
                .take(12)
                .firstNotNullOfOrNull { it as? com.metrolist.innertubex.extraction.StreamResolveException }
                ?.reason
        return if (reason == com.metrolist.innertubex.extraction.StreamResolveException.Reason.AGE_RESTRICTED) {
            R.string.auralys_song_age_restricted
        } else {
            R.string.auralys_song_no_stream
        }
    }

    /** A failed song may be skipped when "auto skip on error" or autoplay allows it, and only when there is a next song. */
    private fun canSkipOnFailure(): Boolean =
        player.nextMediaItemIndex != C.INDEX_UNSET && (cachedAutoplay || dataStore.get(AutoSkipNextOnErrorKey, false))

    /** Carries out a [PlaybackRecovery.Decision] (the rules live there, this only touches the player and the caches). */
    private fun applyRecovery(
        decision: PlaybackRecovery.Decision,
        mediaId: String,
        failedStreamClient: String?,
        httpStatus: Int? = null,
    ) {
        if (decision.softwareDecoder) preferSoftwareAudioDecoder = true
        if (decision.freshStream) {
            songUrlCache.invalidate(mediaId)
            extractionGate.forget(mediaId)
        }
        if (decision.excludeClient && failedStreamClient != null) {
            InnerTubeXPlayer.markStreamClientFailed(mediaId, failedStreamClient)
        }
        if (decision.excludeClient && httpStatus == 403) {
            // a refusal can mean the cipher gave a wrong signature: a rate-limited refresh lets the failed clients back in
            scope.launch {
                if (InnerTubeXPlayer.refreshAfterStreamRejection()) {
                    Timber.tag(TAG).d("Player config changed after stream rejection: restoring stream clients")
                    InnerTubeXPlayer.clearStreamClientFailures()
                }
            }
        }
        if (decision.clearCache) clearPlayerCacheOf(mediaId)

        retryOnValidatedNetwork = null
        when (decision.action) {
            PlaybackRecovery.Action.RETRY -> {
                retryJob?.cancel()
                val retry = {
                    // the user moved on meanwhile, or something else already restarted it: nothing to do
                    if (player.currentMediaItem?.mediaId == mediaId && player.playbackState == STATE_IDLE) {
                        val index = player.currentMediaItemIndex
                        if (index != C.INDEX_UNSET) player.seekTo(index, player.currentPosition)
                        player.prepare()
                        Timber.tag(TAG).d("Retry ${decision.attempt} of $mediaId at ${player.currentPosition} ms")
                    }
                }
                // after a connection error while "online" the retry also runs as soon as a validated network appears
                if (decision.onValidatedNetwork) retryOnValidatedNetwork = retry
                retryJob =
                    scope.launch {
                        delay(decision.delayMs)
                        retryOnValidatedNetwork = null
                        retry()
                    }
            }

            PlaybackRecovery.Action.WAIT_FOR_NETWORK -> waitOnNetworkError()

            PlaybackRecovery.Action.SKIP -> {
                noticeFailure(R.string.auralys_playback_skipped, throttle = true)
                skippingAfterFailure = true
                skipOnError()
            }

            PlaybackRecovery.Action.STOP -> {
                retryJob?.cancel()
                // connection errors for minutes while "online": resumed when a validated network appears, but only if the
                // user wanted playback and does not touch play/pause or the song meanwhile (see onPlayWhenReadyChanged)
                unreachableResume =
                    if (decision.onValidatedNetwork && player.playWhenReady) UnreachableResume(mediaId) else null
                stopOnError()
                noticeFailure(
                    when (decision.stopReason) {
                        PlaybackRecovery.StopReason.NETWORK -> R.string.auralys_playback_stopped_network
                        PlaybackRecovery.StopReason.UNREACHABLE -> R.string.auralys_playback_stopped_unreachable
                        PlaybackRecovery.StopReason.SONGS_FAILING -> R.string.auralys_playback_stopped_songs
                        PlaybackRecovery.StopReason.AUDIO_OUTPUT -> R.string.auralys_playback_stopped_audio
                        else -> R.string.auralys_playback_song_failed
                    },
                    throttle = false,
                )
            }
        }
    }

    /** Drops the bytes of [mediaId] in the player cache (never a download). */
    private fun clearPlayerCacheOf(mediaId: String) {
        try {
            playerCache.removeResource(mediaId)
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to clear the player cache of $mediaId: ${e::class.simpleName}")
        }
    }

    /** A short message about a failure; skips are told once per run, not once per song. */
    private fun noticeFailure(
        text: Int,
        throttle: Boolean,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (throttle && now - lastFailureNoticeAtMs < 30_000L) return
        lastFailureNoticeAtMs = now
        runCatching { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }

    override fun onDeviceVolumeChanged(
        volume: Int,
        muted: Boolean,
    ) {
        super.onDeviceVolumeChanged(volume, muted)
        // the volume keys (or another app) moved the music stream: the page bar's trim belonged to the old step
        if (systemVolumeTrimIndex >= 0 && volume != systemVolumeTrimIndex) {
            systemVolumeTrimIndex = -1
            systemVolumeTrim.value = 1f
        }
        val pauseOnMute = dataStore.get(PauseOnMute, false)

        if ((volume == 0 || muted) && pauseOnMute) {
            if (player.isPlaying) {
                wasPlayingBeforeVolumeMute = true
                isPausedByVolumeMute = true
                player.pause()
            }
        } else if (volume > 0 && !muted && pauseOnMute) {
            if (wasPlayingBeforeVolumeMute && !player.isPlaying && castConnectionHandler?.isCasting?.value != true) {
                wasPlayingBeforeVolumeMute = false
                isPausedByVolumeMute = false
                player.play()
            }
        }
    }

    private fun createCacheDataSource(): CacheDataSource.Factory =
        CacheDataSource
            .Factory()
            .setCache(downloadCache)
            // round 4.3.5: only a read the resolver marked as a COMPLETE download sees the downloaded bytes; a partial
            // download (running, failed, or of another file) is never mixed into a stream (CacheFormatPin)
            .setCacheKeyFactory(CacheFormatPin.downloadLayerKeys)
            .setUpstreamDataSourceFactory(
                CacheDataSource
                    .Factory()
                    .setCache(playerCache)
                    .setCacheKeyFactory(CacheFormatPin.playerLayerKeys)
                    .setUpstreamDataSourceFactory(
                        // a stream that only answers bounded ranges is fetched range by range but read as one stream
                        RangeChunkDataSource.Factory(
                            DefaultDataSource.Factory(
                                this,
                                OkHttpDataSource.Factory(mediaHttpClient),
                            ),
                        ),
                    ),
            ).setCacheWriteDataSinkFactory(null)
            .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)

    private var isSilenceSkipping = false

    private fun handleLongSilenceDetected() {
        if (!instantSilenceSkipEnabled.value) return
        if (silenceSkipJob?.isActive == true) return

        silenceSkipJob =
            scope.launch {
                // Debounce so short fades or transitions do not trigger a jump.
                delay(200)
                performInstantSilenceSkip()
            }
    }

    private suspend fun performInstantSilenceSkip() {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        if (duration <= INSTANT_SILENCE_SKIP_STEP_MS) return

        isSilenceSkipping = true
        try {
            var hops = 0
            val silenceProcessor = playerSilenceProcessors[player] ?: return
            while (coroutineContext.isActive && instantSilenceSkipEnabled.value && silenceProcessor.isCurrentlySilent()) {
                val current = player.currentPosition
                val target = (current + INSTANT_SILENCE_SKIP_STEP_MS).coerceAtMost(duration - 500)

                if (target <= current) break

                // Reset silence tracking before seeking to prevent immediate re-trigger
                silenceProcessor.resetTracking()
                player.seekTo(target)
                hops++

                if (hops >= 80 || target >= duration - 500) break

                delay(INSTANT_SILENCE_SKIP_SETTLE_MS)
            }
            if (hops > 0) {
                Timber.tag(TAG).d("Silence skip: jumped $hops times")
            }
        } finally {
            isSilenceSkipping = false
        }
    }

    private fun syncDiscordState() {
        if (!discordRpcEnabled) return

        val songId = player.currentMetadata?.id
        if (songId == null) {
            Timber.tag("DiscordSvc").d("syncDiscordState: no song, clearing presence")
            DiscordRpcManager.clear()
            return
        }

            if (!DiscordRpcManager.isReady()) {
            if (discordIntentionalDisconnect) {
                Timber.tag("DiscordSvc").d("syncDiscordState: not ready, skipping (intentional disconnect)")
                return
            }
            val token = DiscordRpcManager.getAccessToken()
            val now = System.currentTimeMillis()
            if (token != null && (now - lastDiscordReconnectAttemptAtMs) > 30_000L) {
                lastDiscordReconnectAttemptAtMs = now
                Timber.tag("DiscordSvc").i("syncDiscordState: not ready, attempting reconnect")
                scope.launch(Dispatchers.IO) {
                    if (!DiscordRpcManager.isInitialized()) {
                        DiscordRpcManager.init(this@MusicService)
                    }
                    DiscordRpcManager.reconnectWithToken(token)
                }
            }
            return
        }

        val isPlaying = player.isPlaying
        if (DiscordRpcManager.isShowingSong(songId, isPlaying)) {
            Timber.tag("DiscordSvc").d("syncDiscordState: dedup, already showing songId=%s isPlaying=%s", songId, isPlaying)
            return
        }

        scope.launch(Dispatchers.IO) {
            val (freshSongId, freshIsPlaying) = withContext(Dispatchers.Main.immediate) {
                player.currentMetadata?.id to player.isPlaying
            }
            if (freshSongId == null) return@launch
            val song = database.song(freshSongId).first() ?: return@launch
            updateDiscordRPC(song, freshIsPlaying)
        }
    }

    private suspend fun updateDiscordRPC(song: Song, isPlaying: Boolean) {
        if (!DiscordRpcManager.isReady()) {
            Timber.tag("DiscordSvc").w("updateDiscordRPC: skipping — not ready")
            return
        }
        if (!discordRpcEnabled) {
            Timber.tag("DiscordSvc").w("updateDiscordRPC: skipping — RPC disabled")
            return
        }

        Timber.tag("DiscordSvc").i("updateDiscordRPC: song=%s, isPlaying=%s", song.song.title, isPlaying)

        // ExoPlayer must be accessed on the main thread
        val (currentPosition, speed) = withContext(Dispatchers.Main.immediate) {
            player.currentPosition to player.playbackParameters.speed
        }
        val adjustedTime = (currentPosition / speed).toLong()
        val now = System.currentTimeMillis()
        val startTime = if (isPlaying) now - adjustedTime else 0L
        val durationMs = song.song.duration.takeIf { it > 0 }?.times(1000L)
        val remainingMs = durationMs?.minus(currentPosition)?.coerceAtLeast(0L)
        val adjustedRemainingMs = remainingMs?.let { (it / speed).toLong() }
        val endTime = if (isPlaying && adjustedRemainingMs != null) now + adjustedRemainingMs else null

        val artistName = song.artists.joinToString { it.name }.ifEmpty { DiscordDefaults.UNKNOWN_ARTIST }
        val albumName = song.album?.title
        val songTitle = if (speed != 1.0f) {
            "${song.song.title} [${String.format("%.2fx", speed)}]"
        } else {
            song.song.title
        }
        val artistThumbnail = song.artists.firstOrNull()?.thumbnailUrl

        val advancedMode = dataStore.get(DiscordAdvancedModeKey, false)
        val activityType = dataStore.get(DiscordActivityTypeKey, DiscordDefaults.ACTIVITY_TYPE).toIntOrNull() ?: DiscordActivity.TYPE_LISTENING
        val activityName = dataStore.get(DiscordActivityNameKey, DiscordDefaults.ACTIVITY_NAME)
        val stateTemplate = dataStore.get(DiscordStateTemplateKey, DiscordDefaults.STATE_TEMPLATE)
        val detailsTemplate = dataStore.get(DiscordDetailsTemplateKey, DiscordDefaults.DETAILS_TEMPLATE)
        val btn1Enabled = dataStore.get(DiscordButton1EnabledKey, true)
        val btn1Label = dataStore.get(DiscordButton1LabelKey, DiscordDefaults.BUTTON1_LABEL)
        val btn1Url = dataStore.get(DiscordButton1UrlKey, DiscordDefaults.BUTTON1_URL_TEMPLATE)
        val btn2Enabled = dataStore.get(DiscordButton2EnabledKey, true)
        val btn2Label = dataStore.get(DiscordButton2LabelKey, DiscordDefaults.BUTTON2_LABEL)
        val btn2Url = dataStore.get(DiscordButton2UrlKey, DiscordDefaults.BUTTON2_URL)

        Timber.tag("DiscordSvc").d(
            "updateDiscordRPC: prefs — advancedMode=%s, activityType=%d, activityName=%s, stateTemplate=%s, detailsTemplate=%s",
            advancedMode, activityType, activityName, stateTemplate, detailsTemplate,
        )

        val activity = DiscordActivityBuilder.build(
            song = song,
            artistName = artistName,
            albumName = albumName,
            artistThumbnail = artistThumbnail,
            songTitle = songTitle,
            startTimestamp = startTime,
            endTimestamp = endTime,
            advancedMode = advancedMode,
            activityType = activityType,
            activityName = activityName,
            stateTemplate = stateTemplate,
            detailsTemplate = detailsTemplate,
            btn1Enabled = btn1Enabled,
            btn1Label = btn1Label,
            btn1Url = btn1Url,
            btn2Enabled = btn2Enabled,
            btn2Label = btn2Label,
            btn2Url = btn2Url,
        )

        Timber.tag("DiscordSvc").i("updateDiscordRPC: type=%d name=%s state=%s details=%s start=%d end=%d isPlaying=%s",
            activity.activityType, activity.name, activity.state, activity.details, startTime, endTime ?: 0L, isPlaying)

        val statusStr = dataStore.get(DiscordUserStatusKey, DiscordDefaults.USER_STATUS)
        val presenceStatus = when (statusStr) {
            DiscordDefaults.STATUS_IDLE -> if (advancedMode) PresenceStatus.Idle else PresenceStatus.Online
            DiscordDefaults.STATUS_DND -> if (advancedMode) PresenceStatus.Dnd else PresenceStatus.Online
            else -> PresenceStatus.Online
        }

        DiscordRpcManager.setActivity(
            activity,
            songId = song.song.id,
            isPlaying = isPlaying,
            status = presenceStatus,
        )

        val fetched = fetchArtistThumbnail(song)
        if (fetched != null && DiscordRpcManager.isReady() && discordRpcEnabled) {
            Timber.tag("DiscordSvc").i("updateDiscordRPC: updating with fetched thumbnail")
            val fetchedArtistName = fetched.artists.joinToString { it.name }.ifEmpty { DiscordDefaults.UNKNOWN_ARTIST }
            val fetchedAlbumName = fetched.album?.title
            val fetchedArtistThumbnail = fetched.artists.firstOrNull()?.thumbnailUrl

            val (freshPosition, freshSpeed, freshIsPlaying) = withContext(Dispatchers.Main.immediate) {
                Triple(player.currentPosition, player.playbackParameters.speed, player.isPlaying)
            }
            val freshAdjustedTime = (freshPosition / freshSpeed).toLong()
            val freshNow = System.currentTimeMillis()
            val freshStartTime = if (freshIsPlaying) freshNow - freshAdjustedTime else 0L
            val freshDurationMs = fetched.song.duration.takeIf { it > 0 }?.times(1000L)
            val freshRemainingMs = freshDurationMs?.minus(freshPosition)?.coerceAtLeast(0L)
            val freshAdjustedRemainingMs = freshRemainingMs?.let { (it / freshSpeed).toLong() }
            val freshEndTime = if (freshIsPlaying && freshAdjustedRemainingMs != null) freshNow + freshAdjustedRemainingMs else null

            val fetchedActivity = DiscordActivityBuilder.build(
                song = fetched,
                artistName = fetchedArtistName,
                albumName = fetchedAlbumName,
                artistThumbnail = fetchedArtistThumbnail,
                songTitle = songTitle,
                startTimestamp = freshStartTime,
                endTimestamp = freshEndTime,
                advancedMode = advancedMode,
                activityType = activityType,
                activityName = activityName,
                stateTemplate = stateTemplate,
                detailsTemplate = detailsTemplate,
                btn1Enabled = btn1Enabled,
                btn1Label = btn1Label,
                btn1Url = btn1Url,
                btn2Enabled = btn2Enabled,
                btn2Label = btn2Label,
                btn2Url = btn2Url,
            )

            DiscordRpcManager.setActivity(
                fetchedActivity,
                songId = song.song.id,
                isPlaying = freshIsPlaying,
                status = presenceStatus,
            )
        } else {
            Timber.tag("DiscordSvc").i("updateDiscordRPC: fetched=%s (no thumbnail update)", fetched != null)
        }
    }

    private suspend fun fetchArtistThumbnail(song: Song): Song? {
        val artist = song.artists.firstOrNull()
        if (artist == null) {
            Timber.tag("DiscordSvc").d("fetchArtistThumbnail: no artist, skipping")
            return null
        }
        if (LocalMusic.isLocalArtistId(artist.id)) return null
        if (artist.thumbnailUrl != null) {
            Timber.tag("DiscordSvc").d("fetchArtistThumbnail: artist already has thumbnail, skipping")
            return null
        }

        val browseId = when {
            artist.channelId != null && !artist.channelId.startsWith("LA")
                && !artist.channelId.startsWith("FEmusic_library_privately_owned") -> {
                artist.channelId
            }
            !artist.id.startsWith("LA")
                && !artist.id.startsWith("FEmusic_library_privately_owned") -> {
                artist.id
            }
            else -> {
                Timber.tag("DiscordSvc").d("fetchArtistThumbnail: no valid browseId for artist %s", artist.name)
                return null
            }
        }

        Timber.tag("DiscordSvc").d("fetchArtistThumbnail: fetching for artist=%s, browseId=%s", artist.name, browseId)

        return try {
            val artistPage = withContext(Dispatchers.IO) {
                YouTube.artist(browseId).getOrNull()
            }
            val thumbnail = artistPage?.artist?.thumbnail?.resize(1080, 1080)
            if (thumbnail != null) {
                Timber.tag("DiscordSvc").d("fetchArtistThumbnail: got thumbnail for %s", artist.name)
                withContext(Dispatchers.IO) {
                    database.updateArtistThumbnail(artist.id, thumbnail)
                }
                database.getSongById(song.song.id)
            } else {
                Timber.tag("DiscordSvc").d("fetchArtistThumbnail: no thumbnail found for %s", artist.name)
                null
            }
        } catch (e: Exception) {
            Timber.tag("DiscordSvc").w(e, "fetchArtistThumbnail: failed for artist=%s", artist.name)
            null
        }
    }

    private fun createDataSourceFactory(
        normalizationProcessor: VolumeNormalizationAudioProcessor,
        playerProvider: () -> ExoPlayer?,
    ): DataSource.Factory {
        val streamFactory = ResolvingDataSource.Factory(createCacheDataSource()) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media id")

            val storedFormat = runBlocking(Dispatchers.IO) { database.format(mediaId).first() }
            applyAudioNormalizationBeforePlayback(
                processor = normalizationProcessor,
                playerProvider = playerProvider,
                mediaId = mediaId,
                loudnessDb = storedFormat?.loudnessDb,
                perceptualLoudnessDb = storedFormat?.perceptualLoudnessDb,
                preserveCachedIfMissing = true,
            )

            val shouldBypassCache = bypassCacheForQualityChange.contains(mediaId)

            if (!shouldBypassCache) {
                val usePlayerCache = dataStore.get(EnableSongCacheKey, true)

                val contentLength =
                    storedFormat?.contentLength
                        ?: ContentMetadata
                            .getContentLength(downloadCache.getContentMetadata(mediaId))
                            .takeIf { it > 0L }
                val requiredLength =
                    when {
                        dataSpec.length >= 0 -> dataSpec.length
                        contentLength != null -> (contentLength - dataSpec.position).coerceAtLeast(1)
                        else -> CHUNK_LENGTH // contentLength unknown yet — fall back to old probe size
                    }

                // a complete download is played from the phone; one being repaired (it did not parse) is not
                if (!downloadUtil.isRepairing(mediaId) && CacheFormatPin.isComplete(downloadCache, mediaId)) {
                    recoverSongDeduped(mediaId)
                    return@Factory dataSpec.buildUpon().setCustomData(CacheFormatPin.FromDownload).build()
                }

                if (usePlayerCache && playerCache.isCached(mediaId, dataSpec.position, requiredLength)) {
                    recoverSongDeduped(mediaId)
                    return@Factory dataSpec
                }

                songUrlCache[mediaId]?.let { cachedStream ->
                    recoverSongDeduped(mediaId)
                    currentStreamClient.value = cachedStream.clientName
                    return@Factory dataSpec.withResolvedStream(cachedStream)
                }
            } else {
                Timber.tag(TAG).i("BYPASSING CACHE for $mediaId due to quality change")
            }

            val cacheGeneration = songUrlCache.generation(mediaId)
            val fetchQuality = streamQuality
            Timber.tag(TAG).i("FETCHING STREAM: $mediaId | quality=$fetchQuality (setting=$audioQuality)")
            // Reports 2 and 4: the player waits at most EXTRACTION_WAIT_MS for the address; the extraction itself goes on in
            // the background and the next try joins it (ExtractionGate). It used to block this loader with no limit at all.
            resolvingNow.merge(mediaId, 1, Int::plus)
            val outcome =
                try {
                    runBlocking(Dispatchers.IO) {
                        extractionGate.await(mediaId) {
                            val song = database.songEntity(mediaId)
                            InnerTubeXPlayer.playerResponseForPlayback(
                                mediaId,
                                audioQuality = fetchQuality,
                                connectivityManager = connectivityManager,
                                contentHints = ContentHints(
                                    isExplicit = song?.explicit,
                                    isUploaded = song?.isUploaded,
                                ),
                                song = alternativeSongOf(mediaId),
                            )
                        }
                    }
                } finally {
                    resolvingNow.computeIfPresent(mediaId) { _, n -> (n - 1).takeIf { it > 0 } }
                }
            val nonNullPlayback =
                when {
                    outcome == null ->
                        throw StreamResolveFailure(PlaybackFailureKind.NETWORK_SLOW, getString(R.string.error_timeout))

                    outcome.isFailure -> {
                        val throwable = outcome.exceptionOrNull() ?: IllegalStateException("no stream")
                        val kind =
                            PlaybackFailures.classify(throwable).let {
                                // a failure the classifier does not know is most often a client that gave nothing usable
                                if (it == PlaybackFailureKind.UNKNOWN) PlaybackFailureKind.STREAM_REJECTED else it
                            }
                        val message =
                            when (kind) {
                                PlaybackFailureKind.NETWORK_DOWN -> getString(R.string.error_no_internet)
                                PlaybackFailureKind.NETWORK_SLOW -> getString(R.string.error_timeout)
                                // round 4.3.5: the real reason in plain words instead of the library's English text
                                else -> getString(streamFailureText(throwable))
                            }
                        throw StreamResolveFailure(kind, message, throwable, userText = message)
                    }

                    else -> outcome.getOrThrow()
                }
            run {
                val format = nonNullPlayback.format
                // Bytes of another file of this song (another quality after a change of network, another client, the DRC
                // variant or a re-encoded version with the same itag) are still in the player cache under the same key:
                // the cache would mix them with the new stream. Round 4.3.5: compared by file (itag + lmt + xtags) instead
                // of by the itag in the database, which a download of another format could have overwritten.
                val fileSignature = CacheFormatPin.signature(format.itag, nonNullPlayback.streamUrl)
                if (
                    CacheFormatPin.mustDrop(
                        hasBytes = CacheFormatPin.hasBytes(playerCache, mediaId),
                        pinned = CacheFormatPin.pinned(playerCache, mediaId),
                        newSignature = fileSignature,
                        legacyItag = storedFormat?.itag,
                    )
                ) {
                    Timber.tag(TAG).i("File of $mediaId changed (-> $fileSignature): its cached bytes are dropped")
                    clearPlayerCacheOf(mediaId)
                    if (dataSpec.position > 0L) {
                        // the player is in the middle of the old file: it has to read the new one from its start
                        extractionGate.forget(mediaId)
                        throw StreamResolveFailure(PlaybackFailureKind.CACHE_MISMATCH, "stream format changed mid-song")
                    }
                }
                CacheFormatPin.pin(playerCache, mediaId, fileSignature)
                // A new address that serves only the start of the song (about 1 MiB, then 403: a stale cipher or a missing
                // token, see StreamProbe) is caught here, before it plays a minute and stops: another client is taken.
                val probeAt = StreamProbe.positionFor(format.contentLength)
                if (probeAt != null && isGoogleVideoUrl(nonNullPlayback.streamUrl)) {
                    val status = streamProbe.statusAt(nonNullPlayback.streamUrl, nonNullPlayback.streamHeaders, probeAt)
                    if (status != null && StreamLoadErrorPolicy.isRefusal(status)) {
                        Timber.tag(TAG).w("Stream of $mediaId from ${nonNullPlayback.streamClient} refuses byte $probeAt ($status): another client")
                        InnerTubeXPlayer.markStreamClientFailed(mediaId, nonNullPlayback.streamClient)
                        extractionGate.forget(mediaId)
                        throw StreamResolveFailure(
                            PlaybackFailureKind.STREAM_REJECTED,
                            "the stream refuses its end ($status)",
                            HttpDataSource.InvalidResponseCodeException(status, null, null, emptyMap(), dataSpec, ByteArray(0)),
                        )
                    }
                }
                val loudnessDb = nonNullPlayback.audioConfig?.loudnessDb
                val perceptualLoudnessDb = nonNullPlayback.audioConfig?.perceptualLoudnessDb

                Timber
                    .tag(TAG)
                    .d("Storing format for $mediaId with loudnessDb: $loudnessDb, perceptualLoudnessDb: $perceptualLoudnessDb")
                if (loudnessDb == null && perceptualLoudnessDb == null) {
                    Timber.tag(TAG).w("No loudness data available from YouTube for video: $mediaId")
                }
                applyAudioNormalizationBeforePlayback(
                    processor = normalizationProcessor,
                    playerProvider = playerProvider,
                    mediaId = mediaId,
                    loudnessDb = loudnessDb,
                    perceptualLoudnessDb = perceptualLoudnessDb,
                )

                format.contentLength?.let { contentLength ->
                    database.query {
                        upsert(
                            FormatEntity(
                                id = mediaId,
                                itag = format.itag,
                                mimeType = format.mimeType.substringBefore(";"),
                                codecs =
                                    format.mimeType
                                        .substringAfter("codecs=", missingDelimiterValue = "")
                                        .substringBefore(";")
                                        .trim()
                                        .removeSurrounding("\""),
                                bitrate = format.bitrate,
                                sampleRate = format.audioSampleRate,
                                contentLength = contentLength,
                                loudnessDb = loudnessDb,
                                perceptualLoudnessDb = perceptualLoudnessDb,
                                playbackUrl = nonNullPlayback.playbackTracking?.videostatsPlaybackUrl?.baseUrl,
                            ),
                        )
                    }
                } ?: Timber.tag(TAG).w("Skipping format persistence without content length for $mediaId")
                recoverSongDeduped(mediaId, nonNullPlayback)

                if (bypassCacheForQualityChange.remove(mediaId)) {
                    Timber.tag(TAG).d("Cleared bypass cache flag for $mediaId after fresh fetch")
                }

                val streamUrl = nonNullPlayback.streamUrl
                currentStreamClient.value = nonNullPlayback.streamClient
                if (nonNullPlayback.alternativeVideoId != null) {
                    Timber.tag(TAG).w("$mediaId plays the audio of ${nonNullPlayback.alternativeVideoId}")
                    scope.launch { noticeFailure(R.string.auralys_playing_alternative, throttle = false) }
                }

                songUrlCache.put(
                    mediaId = mediaId,
                    url = streamUrl,
                    requestHeaders = nonNullPlayback.streamHeaders,
                    clientName = nonNullPlayback.streamClient,
                    expiresInSeconds = nonNullPlayback.streamExpiresInSeconds,
                    requireBoundedRange = nonNullPlayback.requireBoundedRange,
                    rangeChunkSizeBytes = nonNullPlayback.rangeChunkSizeBytes,
                    useRangeChunks = nonNullPlayback.useRangeChunks,
                    expectedGeneration = cacheGeneration,
                )

                nonNullPlayback.playbackTracking?.videostatsPlaybackUrl?.baseUrl?.let {
                    playbackUrlCache[cacheKey(mediaId)] = it
                }

                return@Factory dataSpec.withResolvedStream(
                    CachedStreamUrl(
                        url = streamUrl,
                        requestHeaders = nonNullPlayback.streamHeaders,
                        clientName = nonNullPlayback.streamClient,
                        requireBoundedRange = nonNullPlayback.requireBoundedRange,
                        rangeChunkSizeBytes = nonNullPlayback.rangeChunkSizeBytes,
                        useRangeChunks = nonNullPlayback.useRangeChunks,
                    ),
                )
            }
        }
        // songs from the phone (content:// and file:// addresses) skip the YouTube resolver and the caches
        return DeviceAwareDataSourceFactory(remote = streamFactory, local = DefaultDataSource.Factory(this))
    }

    private fun createMediaSourceFactory(
        normalizationProcessor: VolumeNormalizationAudioProcessor,
        playerProvider: () -> ExoPlayer?,
    ) =
        DefaultMediaSourceFactory(
            createDataSourceFactory(normalizationProcessor, playerProvider),
            DeviceAwareExtractorsFactory(
                ExtractorsFactory {
                    arrayOf(MatroskaExtractor(), FragmentedMp4Extractor(), Mp4Extractor())
                },
            ),
        ).setLoadErrorHandlingPolicy(StreamLoadErrorPolicy())

    private fun createRenderersFactory(
        normalizationProcessor: VolumeNormalizationAudioProcessor,
        eqProcessor: CustomEqualizerAudioProcessor,
        silenceProcessor: SilenceDetectorAudioProcessor,
        useAudioTrackPlaybackParams: Boolean,
    ) = object : DefaultRenderersFactory(this) {
        init {
            // Some devices fail to decode AAC with their hardware codec (DECODING_FAILED / mp4a-latm). Allow
            // falling back to the next decoder, and after such an error list software decoders first.
            setEnableDecoderFallback(true)
            setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                if (preferSoftwareAudioDecoder && MimeTypes.isAudio(mimeType)) decoders.sortedBy { !it.softwareOnly } else decoders
            }
        }

        override fun buildAudioRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            audioSink: AudioSink,
            eventHandler: Handler,
            eventListener: AudioRendererEventListener,
            out: ArrayList<Renderer>,
        ) {
            super.buildAudioRenderers(
                context,
                extensionRendererMode,
                mediaCodecSelector,
                enableDecoderFallback,
                audioSink,
                eventHandler,
                eventListener,
                out,
            )
            val coreRendererIndex = out.indexOfFirst { it is MediaCodecAudioRenderer }
            if (coreRendererIndex == -1) return
            out[coreRendererIndex] =
                object : MediaCodecAudioRenderer(
                    context,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    audioSink,
                ) {
                    override fun getCodecOperatingRateV23(
                        targetPlaybackSpeed: Float,
                        format: Format,
                        streamFormats: Array<Format>,
                    ): Float =
                        super.getCodecOperatingRateV23(
                            if (targetPlaybackSpeed in 0.95f..1.05f) 1f else targetPlaybackSpeed,
                            format,
                            streamFormats,
                        )
                }
        }

        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ) = DefaultAudioSink
            .Builder(this@MusicService)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(useAudioTrackPlaybackParams)
            .setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(
                    // Last on purpose: the loudness gain and its limiter must see what the equaliser and the reverb made.
                    arrayOf(
                        eqProcessor,
                        ReverbAudioProcessor(),
                        silenceProcessor,
                        normalizationProcessor,
                    ),
                    SilenceSkippingAudioProcessor(2_000_000, 20_000, 256),
                    SonicAudioProcessor(),
                ),
            ).build()
    }

    override fun onPlaybackStatsReady(
        eventTime: AnalyticsListener.EventTime,
        playbackStats: PlaybackStats,
    ) {
        val mediaItem = eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem
        val historyDurationMs = dataStore[HistoryDuration]?.times(1000f) ?: 30000f

        if (playbackStats.totalPlayTimeMs >= historyDurationMs &&
            !dataStore.get(PauseListenHistoryKey, false)
        ) {
            database.query {
                incrementTotalPlayTime(mediaItem.mediaId, playbackStats.totalPlayTimeMs)
                try {
                    insert(
                        Event(
                            songId = mediaItem.mediaId,
                            timestamp = LocalDateTime.now(),
                            playTime = playbackStats.totalPlayTimeMs,
                        ),
                    )
                } catch (_: SQLException) {
                }
            }
        }

        if (playbackStats.totalPlayTimeMs >= historyDurationMs && !LocalMusic.isLocalSongId(mediaItem.mediaId)) {
            scope.launch(Dispatchers.IO) {
                val playbackUrl =
                    playbackUrlCache[cacheKey(mediaItem.mediaId)]
                if (playbackUrl == null) {
                    Timber.tag(TAG).w("No playback tracking URL available; skipping YouTube history registration")
                    return@launch
                }
                YouTube
                    .registerPlayback(null, playbackUrl)
                    .onFailure {
                        reportException(it)
                    }
            }
        }
    }

    private var queueSaveJob: Job? = null
    private val queueWriteLock = Any()

    private class QueueSnapshot(
        val queue: PersistQueue,
        val automix: PersistQueue,
        /** the play order over [queue]'s items when shuffle is on (B, 4.3.4) */
        val shuffleOrder: IntArray? = null,
    )

    /** Fingerprint of the queue file on disk: the big file is rewritten only when the queue itself changed. */
    private var lastWrittenQueueSignature: Long? = null

    /** The queue restored at start and when its cursor was saved (for the player-state position refinement). */
    private var restoredQueueFromDisk: Queue? = null
    private var restoredCursorSavedAt: Long? = null

    /** What the saved files hold, read and turned into a [Queue] off the main thread (finding 23). */
    private class RestoredPlayback(
        val queue: PersistQueue? = null,
        val restoredQueue: Queue? = null,
        val cursor: PersistQueueCursor.Resolved? = null,
        val automix: List<MediaItem>? = null,
        val playerState: PersistPlayerState? = null,
    )

    /**
     * IO thread. Each file stands alone (finding 3): a missing or broken file is skipped or dropped by itself, never the
     * others with it. A broken queue drops its cursor too (the cursor means nothing without it); a broken cursor only
     * loses the position (the queue's own index is used).
     */
    private fun readPersistedPlayback(): RestoredPlayback {
        val dir = filesDir
        runCatching { PersistFiles.sweepTemps(dir, PERSISTED_FILES) }

        var queue: PersistQueue? = null
        var restoredQueue: Queue? = null
        var cursor: PersistQueueCursor.Resolved? = null
        when (val read = PersistFiles.read(dir.resolve(PERSISTENT_QUEUE_FILE)) { it as PersistQueue }) {
            is PersistFiles.ReadResult.Ok -> {
                try {
                    // the cursor file knows the song, the position and the shuffle order of this queue (B, 4.3.4)
                    cursor = readQueueCursor()?.resolve(read.value)
                    val start =
                        cursor?.let { read.value.copy(mediaItemIndex = it.mediaItemIndex, position = it.position) } ?: read.value
                    restoredQueue = start.toQueue()
                    queue = read.value
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to restore persisted queue, dropping it")
                    dropPersisted(PERSISTENT_QUEUE_FILE, PERSISTENT_QUEUE_CURSOR_FILE)
                    cursor = null
                }
            }
            is PersistFiles.ReadResult.Broken -> {
                Timber.tag(TAG).w(read.error, "Failed to read persisted queue, dropping it")
                dropPersisted(PERSISTENT_QUEUE_FILE, PERSISTENT_QUEUE_CURSOR_FILE)
            }
            PersistFiles.ReadResult.Missing -> Unit
        }

        val automix =
            when (val read = PersistFiles.read(dir.resolve(PERSISTENT_AUTOMIX_FILE)) { it as PersistQueue }) {
                is PersistFiles.ReadResult.Ok ->
                    runCatching { read.value.items.map { it.toMediaItem() } }
                        .onFailure {
                            Timber.tag(TAG).w(it, "Failed to restore automix queue, dropping it")
                            dropPersisted(PERSISTENT_AUTOMIX_FILE)
                        }.getOrNull()
                is PersistFiles.ReadResult.Broken -> {
                    Timber.tag(TAG).w(read.error, "Failed to read automix queue, dropping it")
                    dropPersisted(PERSISTENT_AUTOMIX_FILE)
                    null
                }
                PersistFiles.ReadResult.Missing -> null
            }

        val playerState =
            when (val read = PersistFiles.read(dir.resolve(PERSISTENT_PLAYER_STATE_FILE)) { it as PersistPlayerState }) {
                is PersistFiles.ReadResult.Ok -> read.value
                is PersistFiles.ReadResult.Broken -> {
                    Timber.tag(TAG).w(read.error, "Failed to read player state, dropping it")
                    dropPersisted(PERSISTENT_PLAYER_STATE_FILE)
                    null
                }
                PersistFiles.ReadResult.Missing -> null
            }
        return RestoredPlayback(queue, restoredQueue, cursor, automix, playerState)
    }

    /**
     * Main thread. A queue the user started while the files were being read (a tap in the first moment, a song from a
     * notification or a widget) is never replaced by the saved one: their tap wins and the saved queue is dropped
     * (the next save writes theirs). The player exists already, so nothing waits for it here.
     */
    private fun applyRestoredPlayback(restored: RestoredPlayback) {
        val restoredQueue = restored.restoredQueue
        val queue = restored.queue
        val userStartedOne = queuesStartedSinceCreate > 0 || player.mediaItemCount > 0
        if (restoredQueue != null && queue != null && !userStartedOne) {
            restoredQueueFromDisk = restoredQueue
            restoredCursorSavedAt = restored.cursor?.savedAt
            synchronized(queueWriteLock) {
                lastWrittenQueueSignature = QueueSignature.of(queue.title, queue.items.map { it.id })
            }
            playQueue(
                queue = restoredQueue,
                playWhenReady = false,
                restoringQueue = true,
                restoredShuffleOrder = restored.cursor?.shuffleOrder,
            )
        } else if (restoredQueue != null) {
            Timber.tag(TAG).i("A queue was started before the saved one was read: the saved queue is not restored")
        }

        restored.automix?.let { items -> if (automixItems.value.isEmpty()) automixItems.value = items }

        val playerState = restored.playerState ?: return
        // Don't restore repeat/shuffle from playerState as they are already set from DataStore (source of truth)
        playerVolume.value = playerState.volume
        scope.launch {
            delay(1000) // Wait for queue to be loaded
            // The queue file (and its cursor) decides the song. The player state only refines the position of that same
            // song when it is newer, and only while the restored queue still plays: its index is of the whole queue (a
            // saved part of it has other indexes), and a song tapped in the first second must not be thrown to an old
            // index (B, 4.3.4)
            val restoredNow = restoredQueueFromDisk
            if (restoredNow != null &&
                currentQueue === restoredNow &&
                playerState.currentMediaItemIndex == player.currentMediaItemIndex &&
                playerState.timestamp > (restoredCursorSavedAt ?: 0L)
            ) {
                player.seekTo(playerState.currentMediaItemIndex, playerState.currentPosition)
            }
        }
    }

    private fun dropPersisted(vararg names: String) {
        for (name in names) runCatching { filesDir.resolve(name).delete() }
        if (PERSISTENT_QUEUE_FILE in names) synchronized(queueWriteLock) { lastWrittenQueueSignature = null }
    }

    private fun readQueueCursor(): PersistQueueCursor? =
        when (val read = PersistFiles.read(filesDir.resolve(PERSISTENT_QUEUE_CURSOR_FILE)) { it as PersistQueueCursor }) {
            is PersistFiles.ReadResult.Ok -> read.value
            // a broken cursor only costs the position: the queue starts at its own saved index
            is PersistFiles.ReadResult.Broken -> {
                runCatching { filesDir.resolve(PERSISTENT_QUEUE_CURSOR_FILE).delete() }
                null
            }
            PersistFiles.ReadResult.Missing -> null
        }

    /**
     * Saves the queue a moment later and off the main thread: a long queue (a whole phone library) used to be written
     * whole, on the main thread, at every track change. Several requests close together become one save.
     */
    private fun saveQueueToDisk() {
        queueSaveJob?.cancel()
        queueSaveJob =
            scope.launch {
                delay(QUEUE_SAVE_DELAY_MS)
                val snapshot = snapshotQueue() ?: return@launch
                withContext(Dispatchers.IO) { writeQueueSnapshot(snapshot) }
            }
    }

    /** The same save, at once and on this thread: for when the service is going away. */
    private fun saveQueueToDiskNow() {
        queueSaveJob?.cancel()
        snapshotQueue()?.let { writeQueueSnapshot(it, sync = false) }
    }

    /**
     * Main thread, cheap (references only): the whole queue up to [PersistQueueWindow.MAX_SAVED] songs and, with shuffle
     * on, its play order, so a restart neither cuts a big playlist to its top part nor reshuffles it (B, 4.3.4).
     */
    private fun snapshotQueue(): QueueSnapshot? {
        if (player.mediaItemCount == 0) {
            Timber.tag(TAG).d("Skipping queue save - no media items")
            return null
        }
        return try {
            val all = player.mediaItems
            val index = player.currentMediaItemIndex
            val playOrder = if (player.shuffleModeEnabled) ShuffleOrders.playOrder(player) else null
            val plan = PersistQueueWindow.plan(all.size, index, playOrder, keep = { all[it].metadata != null })
            QueueSnapshot(
                queue =
                    currentQueue.toPersistQueue(
                        title = queueTitle,
                        items = plan.kept.map { all[it].metadata!! },
                        mediaItemIndex = plan.currentIndex,
                        position = if (plan.kept.getOrNull(plan.currentIndex) == index) player.currentPosition else 0L,
                    ),
                shuffleOrder = plan.order,
                automix =
                    PersistQueue(
                        title = "automix",
                        items = automixItems.value.mapNotNull { it.metadata },
                        mediaItemIndex = 0,
                        position = 0,
                    ),
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error during queue save operation")
            reportException(e)
            null
        }
    }

    /**
     * Writes the snapshot. Every file goes through [PersistFiles.write] (a temporary file, synced, then renamed over the
     * old one): a kill in the middle of a write leaves the previous file, never a broken one (review 2026-11-12, 3).
     * [sync] is off when this runs on the main thread (onDestroy): the rename alone already survives a process kill.
     */
    private fun writeQueueSnapshot(
        snapshot: QueueSnapshot,
        sync: Boolean = true,
    ) =
        synchronized(queueWriteLock) {
            // the big file only when the queue changed (a few thousand songs are not rewritten at every song change);
            // the small cursor (song, position, shuffle order) every time (B, 4.3.4)
            val queue = snapshot.queue
            val signature = QueueSignature.of(queue.title, queue.items.map { it.id })
            val queueFile = filesDir.resolve(PERSISTENT_QUEUE_FILE)
            if (signature != lastWrittenQueueSignature || !queueFile.exists()) {
                runCatching {
                    PersistFiles.write(queueFile, queue, sync)
                    lastWrittenQueueSignature = signature
                    Timber.tag(TAG).d("Queue saved successfully")
                }.onFailure {
                    // the previous file is still whole; it no longer matches the queue, so no cursor is written for it
                    lastWrittenQueueSignature = null
                    Timber.tag(TAG).e(it, "Failed to save queue")
                    reportException(it)
                }
            }
            runCatching {
                val current = queue.items.getOrNull(queue.mediaItemIndex)
                val cursorFile = filesDir.resolve(PERSISTENT_QUEUE_CURSOR_FILE)
                if (current == null || lastWrittenQueueSignature != signature) {
                    cursorFile.delete()
                } else {
                    PersistFiles.write(
                        cursorFile,
                        PersistQueueCursor(
                            signature = signature,
                            size = queue.items.size,
                            mediaItemIndex = queue.mediaItemIndex,
                            currentId = current.id,
                            position = queue.position,
                            shuffleOrder = snapshot.shuffleOrder,
                        ),
                        sync,
                    )
                }
            }.onFailure {
                Timber.tag(TAG).e(it, "Failed to save queue cursor")
            }

            runCatching {
                PersistFiles.write(filesDir.resolve(PERSISTENT_AUTOMIX_FILE), snapshot.automix, sync)
                Timber.tag(TAG).d("Automix saved successfully")
            }.onFailure {
                Timber.tag(TAG).e(it, "Failed to save automix")
                reportException(it)
            }
        }

    private fun savePlayerStateToDisk() {
        if (player.mediaItemCount == 0) return

        val playerState = PersistPlayerState(
            playWhenReady = player.playWhenReady,
            repeatMode = player.repeatMode,
            shuffleModeEnabled = player.shuffleModeEnabled,
            volume = playerVolume.value,
            currentPosition = player.currentPosition,
            currentMediaItemIndex = player.currentMediaItemIndex,
            playbackState = player.playbackState,
        )
        // While paused nothing changes, so skip the periodic rewrite (the timestamp is new on every object: it is not
        // part of the comparison, or the rewrite was never skipped).
        val last = lastSavedPlayerState
        if (last != null && playerState.copy(timestamp = last.timestamp) == last) return
        runCatching {
            // main thread, a few bytes: atomic rename without a disk sync (a process kill cannot break the file)
            PersistFiles.write(filesDir.resolve(PERSISTENT_PLAYER_STATE_FILE), playerState, sync = false)
            lastSavedPlayerState = playerState
            Timber.tag(TAG).d("Player state saved successfully")
        }.onFailure {
            Timber.tag(TAG).e(it, "Failed to save player state")
            reportException(it)
        }
    }

    /**
     * [Context.startForegroundService] requires [startForeground] to succeed quickly. If we cannot
     * enter the foreground state, stop immediately so the system does not ANR the app process.
     */
    /**
     * onCreate: enter the foreground at once (a startForegroundService() start must call startForeground within seconds).
     * A refusal is logged and that is all: it only happens when the start or bind came from the background, which carries no
     * foreground promise. The service, its player and its session are built either way (report 1).
     */
    private fun tryStartForegroundAtCreate() {
        startForegroundSafely(
            notification = createFallbackForegroundNotification(),
            deniedMessage = "Foreground start refused at create (bound or started from the background); the player is built anyway",
            failureMessage = "Failed to enter foreground at create",
            stopOnFailure = false,
        )
    }

    private fun ensureForegroundWithLatestNotificationOrStop(): Boolean =
        startForegroundSafely(
            notification = latestMediaNotification ?: createFallbackForegroundNotification(),
            deniedMessage = "Foreground promotion denied during notification update; stopping service",
            failureMessage = "Failed to promote service during notification update; stopping service",
            stopOnFailure = true,
        )

    private fun tryEnsureForegroundWithLatestNotification(): Boolean =
        startForegroundSafely(
            notification = latestMediaNotification ?: createFallbackForegroundNotification(),
            deniedMessage = "Foreground promotion denied during notification update",
            failureMessage = "Failed to promote service during notification update",
            stopOnFailure = false,
        )

    private fun ensureForegroundChannelExists() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.music_player),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun createFallbackForegroundNotification(): Notification {
        ensureForegroundChannelExists()
        val pending =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.music_player))
            .setContentText("")
            .setSmallIcon(R.drawable.small_icon)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundSafely(
        notification: Notification,
        deniedMessage: String,
        failureMessage: String,
        stopOnFailure: Boolean = true,
    ): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Timber.tag(TAG).w(e, deniedMessage)
            if (stopOnFailure) {
                stopSelf()
            }
            false
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, failureMessage)
            reportException(e)
            if (stopOnFailure) {
                stopSelf()
            }
            false
        }

    override fun onDestroy() {
        isRunning = false

        if (!::player.isInitialized) {
            networkWatcher?.stop()
            try {
                scope.cancel()
            } catch (_: Exception) {
            }
            super.onDestroy()
            shutdownDeferred.complete(Unit)
            return
        }

        val currentMetadata = player.currentMediaItem?.metadata
        if (currentMetadata?.isEpisode == true && player.currentPosition > 0) {
            runBlocking(Dispatchers.IO) {
                database.updatePlaybackPosition(currentMetadata.id, player.currentPosition)
            }
        }

        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
        }
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        castConnectionHandler?.release()
        if (dataStore.get(PersistentQueueKey, true)) {
            saveQueueToDiskNow()
            savePlayerStateToDisk()
        }
        screenOffHandler.removeCallbacks(screenOffTimeout)
        screenOffHandler.removeCallbacks(pauseTimeout)
        if (DiscordRpcManager.isReady()) {
            Timber.tag("DiscordSvc").i("onDestroy: disconnecting Discord RPC")
            DiscordRpcManager.disconnect()
        }
        DiscordRpcManager.destroy()
        connectivityObserver.unregister()
        networkWatcher?.stop()
        abandonAudioFocus()
        audioFocusCoordinator?.release()
        closeAudioEffectSession()
        mediaLibrarySessionCallback.release()
        mediaSession?.release()
        crossfadeMessage?.cancel()
        crossfadeMessage = null
        crossfadeJob?.cancel()
        crossfadeJob = null
        secondaryPlayer?.let { pendingPlayer ->
            pendingPlayer.removeListener(secondaryPlayerListener)
            releaseExoPlayer(pendingPlayer)
        }
        secondaryPlayer = null
        fadingPlayer?.let(::releaseExoPlayer)
        fadingPlayer = null
        isCrossfading = false
        dynamicIsland?.release()
        dynamicIsland = null
        lockScreenLyrics?.release()
        lockScreenLyrics = null
        player.removeListener(this)
        sleepTimer?.let { player.removeListener(it) }
        watchdogJob?.cancel()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        releaseExoPlayer(player)
        scope.cancel()
        super.onDestroy()
        shutdownDeferred.complete(Unit)
    }

    override fun onBind(intent: Intent?): android.os.IBinder? {
        super.onBind(intent)?.let { return it }
        // Only the app's own explicit intent (no action) gets the service object. A media browser of another app used to get
        // it too when no session was there, a binder it cannot use, and its connection kept a broken instance alive.
        if (intent?.action != null) return null
        // The app UI binding means the user is back.
        taskCleared = false
        return binder
    }

    /**
     * The app's screen connected to this instance (PlayerConnection). onBind runs once per instance, so a screen that binds
     * again to an instance kept alive after "stop on task clear" did not lift [taskCleared], and playQueue ignored every tap.
     * Playback that runs without the foreground state (it was refused in the background) gets it back now that it can.
     */
    fun onUiAttached() {
        taskCleared = false
        if (::player.isInitialized && player.playWhenReady && !isPlaybackOngoing()) {
            tryEnsureForegroundWithLatestNotification()
        }
    }

    /**
     * Media3 starts the service again with startForegroundService() at each notification update that needs the foreground.
     * From the background Android 12+ may refuse it; the refusal becomes the same handling as Media3's own listener instead
     * of an exception in the main loop (report 6). The startForeground() that follows is guarded where Media3 calls it.
     */
    override fun startForegroundService(service: Intent): ComponentName? =
        try {
            super.startForegroundService(service)
        } catch (e: IllegalStateException) {
            if (!isForegroundServiceStartNotAllowedException(e)) throw e
            Timber.tag(TAG).w(e, "startForegroundService refused in the background")
            null
        }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (dataStore.get(StopMusicOnTaskClearKey, false)) {
            stopOnTaskClear()
        } else {
            super.onTaskRemoved(rootIntent)
        }
    }

    /**
     * Idempotent hard stop: besides the current player, in-flight playQueue work, crossfade
     * players and late Media3 notification updates can otherwise resume audio or repost the
     * notification after the task is cleared.
     */
    fun stopOnTaskClear() {
        if (taskCleared) return
        taskCleared = true
        crossfadeJob?.cancel()
        crossfadeJob = null
        crossfadeMessage?.cancel()
        // A cancelled fade never reaches its own cleanup. Without this isCrossfading would stay true and freeze the volume
        // (slider, mute, duck restore) for as long as this service instance lives on.
        if (isCrossfading) {
            runCatching { cleanupCrossfade() }
            isCrossfading = false
        }
        // Remote playback (Cast) is independent of the local ExoPlayer; ending the session
        // is required or audio keeps playing on the Cast device.
        runCatching {
            if (castConnectionHandler?.isCasting?.value == true) {
                castConnectionHandler?.disconnect()
            }
            if (::player.isInitialized) {
                listOfNotNull(player, secondaryPlayer, fadingPlayer).forEach {
                    it.pause()
                    it.stop()
                }
                wasPlayingBeforeAudioFocusLoss = false
                abandonAudioFocus()
            }
        }.onFailure { Timber.tag(TAG).e(it, "Failed to stop playback on task clear") }
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        latestMediaNotification = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        // Media3: coordinates notification/foreground teardown and stopSelf; required when
        // playback was ongoing (default super.onTaskRemoved keeps the service alive).
        runCatching { pauseAllPlayersAndStopSelf() }.onFailure { stopSelf() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        // External controllers (Android Auto, Bluetooth, Wear) only connect when the user is using
        // them, and they keep this instance alive after a task clear, so they must lift the guard.
        if (controllerInfo.packageName != packageName) taskCleared = false
        return mediaSession
    }

    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ) {
        if (taskCleared) return
        try {
            super.onUpdateNotification(session, startInForegroundRequired)
        } catch (e: ForegroundServiceStartNotAllowedException) {
            handleForegroundServiceStartNotAllowed(e)
        } catch (e: IllegalStateException) {
            if (isForegroundServiceStartNotAllowedException(e)) {
                handleForegroundServiceStartNotAllowed(e)
            } else {
                throw e
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (taskCleared) {
            // A media button press (headset, car) is the user resuming; anything else is a stale restart.
            if (intent?.action != Intent.ACTION_MEDIA_BUTTON) {
                stopSelf()
                return START_NOT_STICKY
            }
            taskCleared = false
        }
        // On Android O+, every startForegroundService() call requires
        // Service.startForeground() to be called within a short timeout.
        // Some OEMs (e.g. MIUI) strictly enforce this even when the
        // service is already in the foreground, so promote here unless Media3 is handling a
        // notification dismissal. Re-promoting that intent immediately restores the dismissed
        // media control.
        val isNotificationDismissal =
            intent?.getBooleanExtra(MediaNotification.NOTIFICATION_DISMISSED_EVENT_KEY, false) == true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isNotificationDismissal) {
            if (!ensureForegroundWithLatestNotificationOrStop()) {
                return START_NOT_STICKY
            }
        }

        // onCreate returns early when the system refuses the foreground start (for example a widget starting the service
        // from the background on Android 12+), and the framework still delivers this intent: there is no player to act on.
        if (!playerInitialized.value) {
            Timber.tag(TAG).w("onStartCommand without a player (action=${intent?.action}); stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_ALARM_TRIGGER -> {
                handleAlarmTrigger(intent)
            }

            AuralysWidgetReceiver.ACTION_PLAY_PAUSE -> {
                if (player.isPlaying) player.pause() else player.play()
                updateWidgetUI(player.isPlaying)
            }

            AuralysWidgetReceiver.ACTION_LIKE -> {
                toggleLike()
            }

            AuralysWidgetReceiver.ACTION_NEXT -> {
                player.seekToNext()
                updateWidgetUI(player.isPlaying)
            }

            AuralysWidgetReceiver.ACTION_PREVIOUS -> {
                player.seekToPrevious()
                updateWidgetUI(player.isPlaying)
            }

            AuralysWidgetReceiver.ACTION_UPDATE_WIDGET -> {
                updateWidgetUI(player.isPlaying)
            }
        }

        return super.onStartCommand(intent, flags, startId)
    }


    private fun handleAlarmTrigger(intent: Intent) {
        scope.launch(Dispatchers.IO) {
            try {
                MusicAlarmScheduler.scheduleFromPreferences(this@MusicService)
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Failed to reschedule alarms after trigger")
            }
        }
        val playlistId = intent.getStringExtra(EXTRA_ALARM_PLAYLIST_ID).orEmpty()
        val alarmId = intent.getStringExtra(EXTRA_ALARM_ID).orEmpty()
        if (playlistId.isBlank()) {
            if (alarmId.isNotBlank()) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val alarms = MusicAlarmStore.load(this@MusicService)
                        val updated =
                            alarms.map { alarm ->
                                if (alarm.id == alarmId) alarm.copy(enabled = false, nextTriggerAt = -1L) else alarm
                            }
                        MusicAlarmScheduler.scheduleAll(this@MusicService, updated)
                    } catch (t: Throwable) {
                        Timber.tag(TAG).e(t, "Failed to disable alarm with invalid playlist")
                    }
                }
            }
            return
        }
        val randomSong = intent.getBooleanExtra(EXTRA_ALARM_RANDOM_SONG, false)
        scope.launch {
            try {
                val playlistSongs =
                    withContext(Dispatchers.IO) {
                        database.playlistSongs(playlistId).first()
                    }
                if (playlistSongs.isEmpty()) {
                    if (alarmId.isNotBlank()) {
                        withContext(Dispatchers.IO) {
                            val alarms = MusicAlarmStore.load(this@MusicService)
                            val updated =
                                alarms.map { alarm ->
                                    if (alarm.id == alarmId) alarm.copy(enabled = false, nextTriggerAt = -1L) else alarm
                                }
                            MusicAlarmScheduler.scheduleAll(this@MusicService, updated)
                        }
                    }
                    return@launch
                }
                val items = playlistSongs.map { it.song.toMediaItem() }
                val playlistName =
                    withContext(Dispatchers.IO) {
                        database
                            .playlist(playlistId)
                            .first()
                            ?.playlist
                            ?.name
                    }
                withContext(Dispatchers.IO) {
                    MusicAlarmScheduler.scheduleFromPreferences(this@MusicService)
                }

                val alarmItems =
                    if (randomSong) {
                        val firstIndex = Random.nextInt(items.size)
                        buildList(items.size) {
                            add(items[firstIndex])
                            items.forEachIndexed { index, item ->
                                if (index != firstIndex) add(item)
                            }
                        }
                    } else {
                        items
                    }

                player.stop()
                player.clearMediaItems()
                playQueue(
                    ListQueue(
                        title = playlistName,
                        items = alarmItems,
                        startIndex = 0,
                        position = 0L,
                    ),
                    playWhenReady = true,
                )
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Failed to start alarm playback")
            }
        }
    }

    private fun handleForegroundServiceStartNotAllowed(error: Throwable?) {
        if (error != null) {
            Timber.tag(TAG).w(error, "Foreground service start denied during notification update")
        } else {
            Timber.tag(TAG).w("Foreground service start denied by MediaSessionService listener")
        }

        if (tryEnsureForegroundWithLatestNotification()) {
            return
        }

        if (!::player.isInitialized) {
            stopSelf()
            return
        }

        // Playback the user wants (a resume after a call or on Bluetooth, the next song starting, still buffering) is kept:
        // pausing it here was music that "stopped by itself". The notification is still shown, and the foreground state
        // comes back as soon as Android allows it (next notification update, or the app opened, see onUiAttached).
        if (player.playWhenReady) {
            Timber.tag(TAG).w("Keeping playback alive after denied foreground restart request")
            latestMediaNotification?.let { notification ->
                runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification) }
            }
            return
        }

        runCatching {
            pauseAllPlayersAndStopSelf()
        }.onFailure {
            Timber.tag(TAG).w(it, "Failed to stop service after foreground start denial")
            stopSelf()
        }
    }

    private fun isForegroundServiceStartNotAllowedException(error: IllegalStateException): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            error.javaClass.name == ForegroundServiceStartNotAllowedException::class.java.name

    /**
     * Updates all app widgets with current playback state
     */
    private var widgetUpdateInFlight = false
    private var pendingWidgetUpdate: Pair<Boolean, Boolean?>? = null

    private fun updateWidgetUI(
        isPlaying: Boolean,
        isLiked: Boolean? = currentSong.value?.song?.let { if (it.isEpisode) it.inLibrary != null else it.liked }
    ) {
        pendingWidgetUpdate = isPlaying to isLiked
        if (widgetUpdateInFlight) return
        widgetUpdateInFlight = true

        scope.launch {
            try {
                while (true) {
                    val (playing, isLikedRequested) = pendingWidgetUpdate ?: break
                    pendingWidgetUpdate = null

                    val songData = currentSong.value
                    val song = songData?.song
                    val songTitle = song?.title ?: getString(R.string.no_song_playing)
                    val artistName = songData?.artists?.joinToArtistString(getArtistSeparator(this@MusicService)) { it.name } ?: getString(R.string.tap_to_open)
                    val resolvedIsLiked = isLikedRequested == true

                    widgetManager.updateWidgets(
                        title = songTitle,
                        artist = artistName,
                        artworkUri = song?.thumbnailUrl,
                        isPlaying = playing,
                        isLiked = resolvedIsLiked,
                        duration = if (player.duration != C.TIME_UNSET) player.duration else 0,
                        currentPosition = player.currentPosition,
                    )
                }
            } catch (e: Exception) {
            } finally {
                widgetUpdateInFlight = false
            }
        }
    }

    private var widgetUpdateJob: Job? = null

    private fun startWidgetUpdates() {
        widgetUpdateJob?.cancel()
        if (isScreenOff) {
            widgetUpdateJob = null
            return
        }
        widgetUpdateJob =
            scope.launch {
                while (isActive) {
                    delay(1.seconds)
                    if (player.isPlaying) {
                        widgetManager.updateProgress(
                            duration = if (player.duration != C.TIME_UNSET) player.duration else 0,
                            currentPosition = player.currentPosition,
                        )
                    }
                }
            }
    }

    private fun stopWidgetUpdates() {
        widgetUpdateJob?.cancel()
        widgetUpdateJob = null
    }

    /**
     * Get the stream URL for a given media ID.
     * This is used for Google Cast to send the audio URL to Chromecast.
     */
    suspend fun getStreamUrl(mediaId: String): String? =
        withContext(Dispatchers.IO) {
            // a file on the phone has no stream address a Cast device could open
            if (LocalMusic.isLocalSongId(mediaId)) return@withContext null
            try {
                val song = database.songEntity(mediaId)
                val playbackData =
                    InnerTubeXPlayer
                        .playerResponseForPlayback(
                            videoId = mediaId,
                            audioQuality = streamQuality,
                            connectivityManager = connectivityManager,
                            contentHints = ContentHints(
                                isExplicit = song?.explicit,
                                isUploaded = song?.isUploaded,
                            ),
                        ).getOrNull()
                playbackData?.streamUrl
            } catch (e: Exception) {
                timber.log.Timber.e(e, "Failed to get stream URL for Cast")
                null
            }
        }

    /**
     * Initialize Google Cast support
     */
    private fun initializeCast() {
        if (dataStore.get(com.metrolist.music.constants.EnableGoogleCastKey, true)) {
            try {
                castConnectionHandler = CastConnectionHandler(this, scope, this)
                castConnectionHandler?.initialize()
                timber.log.Timber.d("Google Cast initialized")
            } catch (e: Exception) {
                timber.log.Timber.e(e, "Failed to initialize Google Cast")
            }
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            scheduleCrossfade()
            // finding 19: a jump forward inside the song (seek bar, seek buttons) covers that part; our own silence skip
            // does not count (a silent stream skipped to its end is exactly what the run-through guard is for), and our
            // retries seek to the same position (nothing added)
            if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex && !isSilenceSkipping) {
                currentItemUserSeekForwardMs += (newPosition.positionMs - oldPosition.positionMs).coerceAtLeast(0L)
            }
        }
        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
            if (stopIfRunThrough(oldPosition, newPosition)) return
            resumeIfEndedEarly(oldPosition, newPosition)
        }
    }

    /** Video A: songs that "end" in a few seconds one after the other. After three in a row playback stops with a message. */
    private fun stopIfRunThrough(
        old: Player.PositionInfo,
        new: Player.PositionInfo,
    ): Boolean {
        if (isCrossfading || old.mediaItemIndex == new.mediaItemIndex) return false
        val timeline = player.currentTimeline
        if (old.mediaItemIndex !in 0 until timeline.windowCount) return false
        val durationMs = timeline.getWindow(old.mediaItemIndex, Timeline.Window()).durationMs
        if (!recovery.onSongEnded(durationMs, currentItemPlayedWallMs, player.playbackParameters.speed, currentItemUserSeekForwardMs)) return false
        Timber.tag(TAG).w("Three songs in a row ended within seconds: stopping instead of running through the queue")
        stopOnError()
        noticeFailure(R.string.auralys_playback_stopped_songs, throttle = false)
        return true
    }

    /**
     * Report 3 ("lagu belum selesai sudah di-skip"): a song that ended well before its length (a stream cut short, a cache
     * that kept only part of it) is resumed where it stopped, with a fresh stream and its cached bytes dropped. Twice per
     * song at most (PlaybackRecovery.onPrematureEnd), then it is let go.
     */
    private fun resumeIfEndedEarly(
        old: Player.PositionInfo,
        new: Player.PositionInfo,
    ) {
        if (isCrossfading || old.mediaItemIndex == new.mediaItemIndex) return
        val mediaId = old.mediaItem?.mediaId ?: return
        if (LocalMusic.isLocalSongId(mediaId)) return
        val timeline = player.currentTimeline
        if (old.mediaItemIndex !in 0 until timeline.windowCount) return
        if (timeline.getWindow(old.mediaItemIndex, Timeline.Window()).mediaItem.mediaId != mediaId) return
        val durationMs = timeline.getWindow(old.mediaItemIndex, Timeline.Window()).durationMs
        if (!PlaybackRecovery.endedEarly(old.positionMs, durationMs)) return
        if (!recovery.onPrematureEnd(mediaId)) return
        Timber.tag(TAG).w("$mediaId ended at ${old.positionMs} of $durationMs ms: resuming it")
        songUrlCache.invalidate(mediaId)
        extractionGate.forget(mediaId)
        clearPlayerCacheOf(mediaId)
        skippingAfterFailure = true
        player.seekTo(old.mediaItemIndex, old.positionMs)
    }

    private fun scheduleCrossfade() {
        crossfadeMessage?.cancel()
        crossfadeMessage = null
        
        val mediaCrossfadeDuration = crossfadeDuration.toLong()

        if (!crossfadeEnabled || crossfadeDuration <= 0f || player.duration == C.TIME_UNSET || player.duration <= mediaCrossfadeDuration) return
        if (crossfadeGapless && isNextItemGapless()) return
        if (!player.hasNextMediaItem() && player.repeatMode != REPEAT_MODE_ONE) return

        val triggerTime = player.duration - mediaCrossfadeDuration
        val mediaTimeRemaining = triggerTime - player.currentPosition
        if (mediaTimeRemaining <= 0) return

        val targetMediaId = player.currentMediaItem?.mediaId

        crossfadeMessage = player.createMessage { _, _ ->
            val timer = sleepTimer
            if (player.isPlaying && player.currentMediaItem?.mediaId == targetMediaId && (timer == null || !timer.pauseWhenSongEnd)) {
                startCrossfade()
            }
        }.apply {
            setLooper(Looper.getMainLooper())
            setPosition(triggerTime)
            send()
        }
    }

    private fun isNextItemGapless(): Boolean {
        val current = player.currentMediaItem?.mediaMetadata ?: return false
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return false
        val next = player.getMediaItemAt(nextIndex).mediaMetadata
        return current.albumTitle != null && current.albumTitle == next.albumTitle
    }

    private fun startCrossfade() {
        if (isCrossfading) return

        val repeatMode = player.repeatMode
        val shuffleModeEnabled = player.shuffleModeEnabled

        // For repeat-one, crossfade back into the same track
        val targetIndex =
            if (repeatMode == REPEAT_MODE_ONE) {
                player.currentMediaItemIndex
            } else {
                player.nextMediaItemIndex
            }
        if (targetIndex == C.INDEX_UNSET) return

        secondaryPlayer = createExoPlayer()
        val secPlayer = secondaryPlayer!!
        secPlayer.addListener(secondaryPlayerListener)

        val itemCount = player.mediaItemCount
        val items = mutableListOf<MediaItem>()
        for (i in 0 until itemCount) {
            items.add(player.getMediaItemAt(i))
        }

        secPlayer.setMediaItems(items)
        secPlayer.seekTo(targetIndex, 0)
        secPlayer.volume = 0f

        secPlayer.setPlaybackParameters(player.playbackParameters)

        secPlayer.repeatMode = repeatMode
        secPlayer.shuffleModeEnabled = shuffleModeEnabled
        secPlayer.setShuffleOrder(player.shuffleOrder)
        secPlayer.playbackParameters = player.playbackParameters

        try {
            secPlayer.prepare()
            secPlayer.playWhenReady = true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to prepare secondary player for crossfade")
            secPlayer.removeListener(secondaryPlayerListener)
            releaseExoPlayer(secPlayer)
            secondaryPlayer = null
            return
        }

        performCrossfadeSwap()
    }

    private fun performCrossfadeSwap() {
        isCrossfading = true
        val nextPlayer = secondaryPlayer ?: return
        val currentPlayer = player

        fadingPlayer = currentPlayer
        player = nextPlayer
        _playerFlow.value = player
        secondaryPlayer = null

        // Do not persist the retired player's temporary repeat-off state.
        fadingPlayer?.removeListener(this)
        sleepTimer?.let { timer -> fadingPlayer?.removeListener(timer) }
        fadingPlayer?.repeatMode = Player.REPEAT_MODE_OFF
        fadingPlayer?.let {
            val currentIndex = it.currentMediaItemIndex
            if (currentIndex < it.mediaItemCount - 1) {
                it.removeMediaItems(currentIndex + 1, it.mediaItemCount)
            }
        }

        player.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isCrossfading && fadingPlayer != null) {
                        if (isPlaying) {
                            fadingPlayer?.play()
                        } else {
                            fadingPlayer?.pause()
                        }
                    } else {
                        player.removeListener(this)
                    }
                }
            },
        )

        nextPlayer.removeListener(secondaryPlayerListener)
        nextPlayer.addListener(this)
        sleepTimer?.let { nextPlayer.addListener(it) }

        sleepTimer?.player = player

        try {
            mediaSession?.let { (it as MediaSession).player = player }
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Failed to swap player in MediaSession")
        }

        // secondaryPlayer was playing this item silently in the background without
        // `this` attached as a listener, so its real transition into this item never
        // reached onMediaItemTransition. Re-fire it manually now that the swap is done
        // so metadata recovery, cache marking, scrobbling, and normalization all run.
        onMediaItemTransition(player.currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        val previousAudioSessionId = fadingPlayer?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET

        openAudioEffectSession()

        crossfadeJob =
            scope.launch {
                val speed = fadingPlayer?.playbackParameters?.speed?.coerceAtLeast(0.01f) ?: 1f
                val duration = (crossfadeDuration / speed).toLong()
                val steps = 20
                val stepTime = duration / steps
                // The target is read at every step (not captured once): the volume the fade ends on is then always the
                // user's volume with the CURRENT duck, mute and sleep fade, even if a notification starts or ends during the fade.
                for (i in 0..steps) {
                    if (!isActive) break
                    while (!player.isPlaying && isActive) {
                        delay(100)
                    }

                    val progress = i / steps.toFloat()
                    val fadeIn = 1.0f - (1.0f - progress) * (1.0f - progress)
                    val fadeOut = (1.0f - progress) * (1.0f - progress)
                    val target = calculateEffectiveVolume()

                    try {
                        player.volume = target * fadeIn
                        fadingPlayer?.volume = target * fadeOut
                    } catch (e: Exception) {
                        break
                    }

                    delay(stepTime)
                }

                try {
                    fadingPlayer?.volume = 0f
                    player.volume = calculateEffectiveVolume()
                } catch (e: Exception) {
                }

                cleanupCrossfade(fadingPlayerSessionId = previousAudioSessionId)
            }
    }

    private fun cleanupCrossfade(fadingPlayerSessionId: Int = C.AUDIO_SESSION_ID_UNSET) {
        fadingPlayer?.let { previousPlayer ->
            previousPlayer.stop()
            previousPlayer.clearMediaItems()
            releaseExoPlayer(previousPlayer)
        }
        fadingPlayer = null
        crossfadeJob = null
        isCrossfading = false
        applyEffectiveVolume()
        sleepTimer?.notifySongTransition()

        applyCachedAudioNormalizationNow()

        if (fadingPlayerSessionId != C.AUDIO_SESSION_ID_UNSET && fadingPlayerSessionId > 0) {
            closeAudioEffectSession(sessionIdOverride = fadingPlayerSessionId, clearNormalizationCache = true)
        }
    }

    private fun releaseExoPlayer(player: ExoPlayer) {
        playerNormalizationProcessors.remove(player)
        playerSilenceProcessors.remove(player)
        playerEqualizerProcessors.remove(player)?.let(equalizerService::removeAudioProcessor)
        player.release()
    }

    companion object {
        const val ACTION_ALARM_TRIGGER = "com.metrolist.music.action.ALARM_TRIGGER"
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_ALARM_PLAYLIST_ID = "extra_alarm_playlist_id"
        const val EXTRA_ALARM_RANDOM_SONG = "extra_alarm_random_song"

        const val ROOT = "root"
        const val SONG = "song"
        const val ARTIST = "artist"
        const val ALBUM = "album"
        const val PLAYLIST = "playlist"
        const val YOUTUBE_PLAYLIST = "youtube_playlist"
        const val SEARCH = "search"
        const val SHUFFLE_ACTION = "__shuffle__"

        const val CHANNEL_ID = "music_channel_01"
        const val NOTIFICATION_ID = 888
        const val ERROR_CODE_NO_STREAM = 1000001
        const val CHUNK_LENGTH = 512 * 1024L
        const val PERSISTENT_QUEUE_FILE = "persistent_queue.data"
        const val PERSISTENT_QUEUE_CURSOR_FILE = "persistent_queue_cursor.data"
        private const val QUEUE_SAVE_DELAY_MS = 1500L
        const val PERSISTENT_AUTOMIX_FILE = "persistent_automix.data"
        const val PERSISTENT_PLAYER_STATE_FILE = "persistent_player_state.data"

        /** The saved playback files (their temporary files left by a killed write are swept at start). */
        private val PERSISTED_FILES =
            listOf(PERSISTENT_QUEUE_FILE, PERSISTENT_QUEUE_CURSOR_FILE, PERSISTENT_AUTOMIX_FILE, PERSISTENT_PLAYER_STATE_FILE)
        const val MAX_CONSECUTIVE_ERR = 5
        const val MAX_RETRY_COUNT = 10

        /** How long the player waits for a stream address before it reports a slow network (the extraction goes on). */
        private const val EXTRACTION_WAIT_MS = 20_000L

        /** The longest one stream extraction may run at all. */
        private const val EXTRACTION_HARD_CAP_MS = 90_000L

        /**
         * How far ahead the player may load on an unmetered network: the whole song (a 15 min cap), at most this many
         * bytes in memory. On a metered network or with the data saver for every network, [LoadBudget] keeps 60-90 s.
         */
        private const val LOAD_AHEAD_MAX_MS = 15 * 60 * 1000
        private const val LOAD_AHEAD_MAX_BYTES = 8 * 1024 * 1024
        private const val TAG = "MusicService"

        @Volatile
        var isRunning = false
            private set
            
        @Volatile
        var shutdownDeferred = kotlinx.coroutines.CompletableDeferred<Unit>().apply { complete(Unit) }
    }
}

internal fun normalizationGainMb(
    loudnessDb: Double?,
    perceptualLoudnessDb: Double?,
    targetLufs: Float,
): Int? {
    // The rule lives in LoudnessGain (pure, tested): perceptual LUFS first, the YouTube Music reference for a bare
    // loudnessDb, then target - measured inside -15 dB .. +6 dB. The boost is safe because the processor limits peaks.
    return LoudnessGain.gainMb(loudnessDb, perceptualLoudnessDb, targetLufs.toDouble())
}
