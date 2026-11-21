/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music

import androidx.compose.animation.core.spring
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.music.constants.OnboardingDoneKey
import com.metrolist.music.ui.component.FrameCappedSpec
import com.metrolist.music.ui.component.largeTitleBarTitle
import com.metrolist.music.ui.component.glassBlurDefault
import com.metrolist.music.ui.component.paperGrain
import com.metrolist.music.ui.theme.UiStyle
import com.metrolist.music.constants.UiStyleKey
import com.metrolist.music.constants.GlassBlurKey
import com.metrolist.music.ui.component.ambientGlow
import com.metrolist.music.ui.component.LocalGlassBlurEnabled
import com.metrolist.music.ui.component.backdrop.backdrops.layerBackdrop
import android.Manifest
import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import com.metrolist.music.ui.component.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import com.metrolist.music.ui.component.AuralysSnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.metrolist.music.ui.component.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import com.metrolist.music.ui.component.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.constants.AppBarHeight
import com.metrolist.music.constants.AppLanguageKey
import com.metrolist.music.constants.CheckForUpdatesKey
import com.metrolist.music.constants.DarkModeKey
import com.metrolist.music.constants.DefaultOpenTabKey
import com.metrolist.music.constants.DismissedKmpUpdateKey
import com.metrolist.music.constants.DismissedStandaloneUpdateKey
import com.metrolist.music.constants.DensityScaleKey
import com.metrolist.music.constants.DisableScreenshotKey
import com.metrolist.music.constants.DynamicThemeKey
import com.metrolist.music.constants.EnableHighRefreshRateKey
import com.metrolist.music.constants.EnableLandscapeScalingKey
import com.metrolist.music.constants.ExperimentalLyricsKey
import com.metrolist.music.constants.ForceBottomNavBarKey
import com.metrolist.music.constants.LastSeenVersionKey
import com.metrolist.music.constants.ListenTogetherInTopBarKey
import com.metrolist.music.constants.ListenTogetherUsernameKey
import com.metrolist.music.constants.LyricsProviderOrderKey
import com.metrolist.music.constants.MiniPlayerBottomSpacing
import com.metrolist.music.constants.MiniPlayerHeight
import com.metrolist.music.constants.NavigationBarAnimationSpec
import com.metrolist.music.constants.NavigationBarHeight
import com.metrolist.music.constants.PauseListenHistoryKey
import com.metrolist.music.constants.PauseSearchHistoryKey
import com.metrolist.music.constants.PreferredLyricsProvider
import com.metrolist.music.constants.PreferredLyricsProviderKey
import com.metrolist.music.constants.PureBlackKey
import com.metrolist.music.constants.SYSTEM_DEFAULT
import com.metrolist.music.constants.SelectedThemeColorKey
import com.metrolist.music.constants.SimpMusicMigrationDoneKey
import com.metrolist.music.constants.SlimNavBarHeight
import com.metrolist.music.constants.SlimNavBarKey
import com.metrolist.music.constants.StopMusicOnTaskClearKey
import com.metrolist.music.constants.UpdateNotificationsEnabledKey
import com.metrolist.music.constants.UseNewMiniPlayerDesignKey
import com.metrolist.music.constants.VideoThumbnailMigrationDoneKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.SearchHistory
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.lyrics.LyricsProviderRegistry
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.DownloadUtil
import com.metrolist.music.playback.MusicService
import com.metrolist.music.playback.MusicService.MusicBinder
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.ui.component.AccountSettingsDialog
import com.metrolist.music.ui.component.AppNavigationBar
import com.metrolist.music.ui.component.AppNavigationRail
import com.metrolist.music.ui.component.BottomSheetMenu
import com.metrolist.music.ui.component.BottomSheetPage
import com.metrolist.music.ui.component.LocalBottomSheetPageState
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.rememberBottomSheetState
import com.metrolist.music.ui.component.shimmer.ShimmerTheme
import com.metrolist.music.ui.menu.YouTubeSongMenu
import com.metrolist.music.ui.player.BottomSheetPlayer
import com.metrolist.music.ui.screens.Screens
import com.metrolist.music.ui.screens.navigationBuilder
import com.metrolist.music.ui.screens.settings.ChangelogScreen
import com.metrolist.music.ui.screens.settings.DarkMode
import com.metrolist.music.ui.screens.settings.NavigationTab
import com.metrolist.music.ui.theme.ColorSaver
import com.metrolist.music.ui.theme.DefaultThemeColor
import com.metrolist.music.ui.theme.MetrolistTheme
import com.metrolist.music.ui.theme.ColorMode
import com.metrolist.music.ui.theme.extractCoverTint
import com.metrolist.music.ui.theme.CoverShadeLevel
import com.metrolist.music.ui.theme.CoverShadeMath
import com.metrolist.music.ui.theme.LocalCoverShade
import com.metrolist.music.ui.theme.coverShade
import com.metrolist.music.ui.theme.drawCoverShade
import com.metrolist.music.ui.utils.appBarScrollBehavior
import com.metrolist.music.ui.utils.resetHeightOffset
import com.metrolist.music.utils.WebUpdate
import com.metrolist.music.utils.ReleaseInfo
import com.metrolist.music.utils.SearchRoutes
import com.metrolist.music.utils.SyncUtils
import com.metrolist.music.utils.ArtistNameAliases
import com.metrolist.music.utils.Updater
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import com.metrolist.music.utils.get
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.utils.reportException
import com.metrolist.music.utils.setAppLocale
import com.metrolist.music.viewmodels.HomeViewModel
import com.valentinilk.shimmer.LocalShimmerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject

private data class AvailableUpdate(
    val release: ReleaseInfo,
    val downloadUrl: String,
    val isKmp: Boolean,
) {
    val dismissalKey = if (isKmp) DismissedKmpUpdateKey else DismissedStandaloneUpdateKey
}

@Suppress("DEPRECATION", "ASSIGNED_BUT_NEVER_ACCESSED_VARIABLE")
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    companion object {
        private const val ACTION_SEARCH = "com.metrolist.music.action.SEARCH"
        private const val ACTION_LIBRARY = "com.metrolist.music.action.LIBRARY"
        const val ACTION_RECOGNITION = "com.metrolist.music.action.RECOGNITION"
        const val EXTRA_AUTO_START_RECOGNITION = "auto_start_recognition"
    }

    @Inject
    lateinit var database: MusicDatabase

    @Inject
    lateinit var downloadUtil: DownloadUtil

    @Inject
    lateinit var syncUtils: SyncUtils

    @Inject
    lateinit var listenTogetherManager: com.metrolist.music.listentogether.ListenTogetherManager

    @Inject
    lateinit var remoteHistoryMerger: com.metrolist.music.utils.RemoteHistoryMerger

    private lateinit var navController: NavHostController
    private var pendingIntent: Intent? = null
    private var latestVersionName by mutableStateOf(BuildConfig.BASE_VERSION_NAME)
    private var appReady by mutableStateOf(com.metrolist.music.ui.component.AuralysSplashState.shown)

    // Keep PlayerConnection as regular property - NOT mutableStateOf to prevent UI recomposition
    // when it becomes null during onStop. Only update the snapshot for Compose when needed.
    private var playerConnection: PlayerConnection? = null

    // This is the snapshot we pass to Compose - changes here trigger recomposition
    private var playerConnectionSnapshot by mutableStateOf<PlayerConnection?>(null)

    private var isServiceBound = false

    private val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?,
            ) {
                if (service is MusicBinder) {
                    playerConnection = PlayerConnection(this@MainActivity, service, database, lifecycleScope)
                    playerConnectionSnapshot = playerConnection
                    listenTogetherManager.setPlayerConnection(playerConnection)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // Disconnect Listen Together manager
                listenTogetherManager.setPlayerConnection(null)
                playerConnection?.dispose()
                // DO NOT null out playerConnection here - keep it for when service reconnects
                // DO NOT update playerConnectionSnapshot - this is the key to preventing recomposition
            }
        }

    private fun safeUnbindService(source: String) {
        if (!isServiceBound) return
        try {
            unbindService(serviceConnection)
        } catch (e: IllegalArgumentException) {
            Timber.tag("MainActivity").w(e, "Service was not bound when attempting to unbind in $source")
        } finally {
            isServiceBound = false
            listenTogetherManager.setPlayerConnection(null)
            playerConnection?.dispose()
            // DO NOT null out playerConnection here - keep it for reconnection
            // DO NOT update playerConnectionSnapshot - this prevents UI recomposition
        }
    }

    override fun onStart() {
        super.onStart()
        // the account's listening history is merged in when it is due (Recap and the streak count every device)
        remoteHistoryMerger.onAppOpened()
        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1000)
            }
        }

        // Start the playback service explicitly once so it can outlive binding.
        // Re-issuing startForegroundService() while an existing service instance is already
        // running can trigger "did not then call startForeground" on some Android 9 devices
        // when the framework expects a fresh foreground promotion for that start request.
        if (!MusicService.isRunning) {
            val serviceIntent = Intent(this, MusicService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ContextCompat.startForegroundService(this, serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Timber.w(e, "Cannot start foreground service from background")
            } catch (e: IllegalStateException) {
                Timber.w(e, "Failed to start foreground service")
            }
        }

        // Bind to service - if already bound, this is a no-op but ensures we stay connected
        if (!isServiceBound) {
            bindService(
                Intent(this, MusicService::class.java),
                serviceConnection,
                BIND_AUTO_CREATE,
            )
            isServiceBound = true
        }
    }

    override fun onStop() {
        // Keep the service binding, PlayerConnection and Listen Together wiring alive while
        // the Activity is backgrounded. The MusicService is a foreground service and keeps
        // running, so the host must keep reporting playback state to the LT server; detaching
        // the player listener here used to break LT for any host that wasn't staring at the
        // app the whole session. Full teardown happens in onDestroy() via safeUnbindService().
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) {
            listenTogetherManager.disconnect()
        }
        super.onDestroy()
        val stopServiceOnClear = dataStore.get(StopMusicOnTaskClearKey, false) && isFinishing

        // Full cleanup - only on actual destroy
        playerConnection?.dispose()
        playerConnection = null
        playerConnectionSnapshot = null

        // Unbind before stopService: a started+bound service does not stop until all clients unbind.
        safeUnbindService("onDestroy()")

        if (stopServiceOnClear) {
            stopService(Intent(this, MusicService::class.java))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (::navController.isInitialized) {
            handleDeepLinkIntent(intent, navController)
        } else {
            pendingIntent = intent
        }
    }

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_LTR
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Horizon design: move the stored appearance choices once, before the splash and the first frame read them
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            com.metrolist.music.ui.theme.DesignMigration.run(applicationContext.dataStore)
        }

        // Initialize Listen Together manager
        listenTogetherManager.initialize()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val locale =
                dataStore[AppLanguageKey]
                    ?.takeUnless { it == SYSTEM_DEFAULT }
                    ?.let { Locale.forLanguageTag(it) }
                    ?: Locale.getDefault()
            setAppLocale(this, locale)
        }

        lifecycleScope.launch {
            dataStore.data
                .map { it[DisableScreenshotKey] ?: false }
                .distinctUntilChanged()
                .collectLatest {
                    if (it) {
                        window.setFlags(
                            WindowManager.LayoutParams.FLAG_SECURE,
                            WindowManager.LayoutParams.FLAG_SECURE,
                        )
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
        }

        // Defer migration and version tracking to avoid blocking first frame
        lifecycleScope.launch(Dispatchers.IO) {
            val preferences = dataStore.data.first()
            val currentVersion = BuildConfig.BASE_VERSION_NAME

            // SimpMusic Removal Migration
            if (preferences[SimpMusicMigrationDoneKey] != true) {
                safeDataStoreEdit { settings ->
                    val currentOrder = settings[LyricsProviderOrderKey] ?: ""
                    if (currentOrder.contains("SimpMusic")) {
                        val orderList =
                            currentOrder
                                .split(",")
                                .map { it.trim() }
                                .filter { it.isNotBlank() && it != "SimpMusic" }
                                .toMutableList()
                        if (orderList.isEmpty()) {
                            settings[LyricsProviderOrderKey] = ""
                        } else {
                            settings[LyricsProviderOrderKey] = orderList.joinToString(",")
                        }
                    }
                    if (settings[PreferredLyricsProviderKey] == "SIMPMUSIC") {
                        settings[PreferredLyricsProviderKey] = PreferredLyricsProvider.LRCLIB.name
                    }
                    settings[SimpMusicMigrationDoneKey] = true
                    settings[LastSeenVersionKey] = currentVersion
                }
            }

            if (preferences[VideoThumbnailMigrationDoneKey] != true) {
                database.repairMissingVideoThumbnails()
                safeDataStoreEdit { settings ->
                    settings[VideoThumbnailMigrationDoneKey] = true
                }
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            safeDataStoreEdit { settings ->
                settings[LastSeenVersionKey] = BuildConfig.BASE_VERSION_NAME
            }
        }

        setContent {
            if (appReady) {
                MetrolistApp(
                    latestVersionName = latestVersionName,
                    onLatestVersionNameChange = { latestVersionName = it },
                    playerConnection = playerConnectionSnapshot,
                    database = database,
                    downloadUtil = downloadUtil,
                    syncUtils = syncUtils,
                )
            }
        }

        if (!com.metrolist.music.ui.component.AuralysSplashState.shown) {
            val splashOverlay = androidx.compose.ui.platform.ComposeView(this)
            splashOverlay.setContent {
                val darkPref by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
                val systemDark = isSystemInDarkTheme()
                val dark = if (darkPref == DarkMode.AUTO) systemDark else darkPref == DarkMode.ON
                var splashVisible by remember { mutableStateOf(true) }
                if (splashVisible) {
                    val splashStyle by rememberEnumPreference(UiStyleKey, defaultValue = UiStyle.DEFAULT)
                    MetrolistTheme(darkTheme = dark, style = splashStyle) {
                        com.metrolist.music.ui.component.AuralysSplash(
                            onReady = { appReady = true },
                            onFinished = {
                                splashVisible = false
                                (splashOverlay.parent as? android.view.ViewGroup)?.removeView(splashOverlay)
                            },
                        )
                    }
                }
            }
            addContentView(
                splashOverlay,
                android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MetrolistApp(
        latestVersionName: String,
        onLatestVersionNameChange: (String) -> Unit,
        playerConnection: PlayerConnection?,
        database: MusicDatabase,
        downloadUtil: DownloadUtil,
        syncUtils: SyncUtils,
    ) {
        val checkForUpdates by rememberPreference(CheckForUpdatesKey, defaultValue = true)
        var availableUpdate by remember { mutableStateOf<AvailableUpdate?>(null) }

        if (BuildConfig.UPDATER_AVAILABLE) {
            LaunchedEffect(checkForUpdates) {
                if (checkForUpdates) {
                    val preferences = dataStore.data.first()
                    val notificationsEnabled = preferences[UpdateNotificationsEnabledKey] ?: true
                    val (releaseInfo, hasUpdate) = Updater.checkForUpdate().getOrNull() ?: (null to false)
                    releaseInfo?.let { onLatestVersionNameChange(it.versionName) }

                    val standaloneUpdate =
                        releaseInfo
                            ?.takeIf { hasUpdate }
                            ?.let { release ->
                                Updater.getDownloadUrlForCurrentVariant(release)?.let { downloadUrl ->
                                    AvailableUpdate(release, downloadUrl, isKmp = false)
                                }
                            }
                    val kmpUpdate =
                        Updater.getLatestKmpRelease().getOrNull()?.let { release ->
                            release.assets.firstOrNull()?.let { asset ->
                                AvailableUpdate(release, asset.downloadUrl, isKmp = true)
                            }
                        }
                    val update = kmpUpdate ?: standaloneUpdate
                    availableUpdate = update?.takeUnless {
                        it.release.tagName == preferences[it.dismissalKey]
                    }

                    if (update != null && notificationsEnabled) {
                        val intent = Intent(Intent.ACTION_VIEW, update.downloadUrl.toUri())
                        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        val pending = PendingIntent.getActivity(this@MainActivity, 1001, intent, flags)
                        val notificationText =
                            if (update.isKmp) {
                                getString(R.string.kmp_upgrade_warning)
                            } else {
                                update.release.versionName
                            }
                        val notification =
                            NotificationCompat
                                .Builder(this@MainActivity, "updates")
                                .setSmallIcon(com.metrolist.music.ui.component.SystemIcons.resolve(this@MainActivity, R.drawable.update))
                                .setContentTitle(
                                    if (update.isKmp) {
                                        getString(R.string.kmp_upgrade_title, update.release.versionName)
                                    } else {
                                        getString(R.string.update_available_title)
                                    },
                                )
                                .setContentText(notificationText)
                                .apply {
                                    if (update.isKmp) {
                                        setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
                                    }
                                }
                                .setContentIntent(pending)
                                .setAutoCancel(true)
                                .build()

                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                            ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            NotificationManagerCompat.from(this@MainActivity).notify(1001, notification)
                        }
                    }
                } else {
                    onLatestVersionNameChange(BuildConfig.BASE_VERSION_NAME)
                    availableUpdate = null
                }
            }
        }

        // Auralys: is there a newer version on the download page? Asked once per launch, in every build flavor.
        var webUpdate by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            if (!webUpdateChecked) {
                webUpdateChecked = true
                kotlinx.coroutines.delay(4000)
                webUpdate = WebUpdate.newerThan(BuildConfig.BASE_VERSION_NAME)
            }
        }
        // "Warna": NETRAL, SAMPUL (cover wash only, controls stay neutral) or KUSTOM (the user's own seed colour)
        val storedColorMode =
            remember { this@MainActivity.dataStore[com.metrolist.music.constants.ColorModeKey].toEnum(ColorMode.NETRAL) }
        val colorMode by rememberEnumPreference(
            com.metrolist.music.constants.ColorModeKey,
            defaultValue = ColorMode.NETRAL,
            initialValue = storedColorMode,
        )
        val coverTintEnabled = colorMode == ColorMode.SAMPUL
        // "Efek berbayang": how much the cover colour washes into every page background (independent of the colour mode)
        val storedCoverShade =
            remember { this@MainActivity.dataStore[com.metrolist.music.constants.CoverShadeKey].toEnum(CoverShadeLevel.DEFAULT) }
        val coverShadeLevel by rememberEnumPreference(
            com.metrolist.music.constants.CoverShadeKey,
            defaultValue = CoverShadeLevel.DEFAULT,
            initialValue = storedCoverShade,
        )
        val enableHighRefreshRate by rememberPreference(EnableHighRefreshRateKey, defaultValue = true)

        LaunchedEffect(enableHighRefreshRate) {
            val window = this@MainActivity.window
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val layoutParams = window.attributes
                if (enableHighRefreshRate) {
                    layoutParams.preferredDisplayModeId = 0
                } else {
                    val modes = window.windowManager.defaultDisplay.supportedModes
                    val mode60 =
                        modes.firstOrNull { kotlin.math.abs(it.refreshRate - 60f) < 1f }
                            ?: modes.minByOrNull { kotlin.math.abs(it.refreshRate - 60f) }

                    if (mode60 != null) {
                        layoutParams.preferredDisplayModeId = mode60.modeId
                    }
                }
                window.attributes = layoutParams
            } else {
                val params = window.attributes
                if (enableHighRefreshRate) {
                    params.preferredRefreshRate = 0f
                } else {
                    params.preferredRefreshRate = 60f
                }
                window.attributes = params
            }
        }

        val darkTheme by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
        val isSystemInDarkTheme = isSystemInDarkTheme()
        val useDarkTheme =
            remember(darkTheme, isSystemInDarkTheme) {
                if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
            }

        LaunchedEffect(useDarkTheme) {
            setSystemBarAppearance(useDarkTheme)
        }

        val enableLandscapeScaling by rememberPreference(EnableLandscapeScalingKey, defaultValue = false)
        val userDensityScale by rememberPreference(DensityScaleKey, defaultValue = 1f)
        val pureBlackEnabled by rememberPreference(PureBlackKey, defaultValue = false)
        val pureBlack =
            remember(pureBlackEnabled, useDarkTheme) {
                pureBlackEnabled && useDarkTheme
            }

        // the stored style is read once before the first frame too: without it a "Datar" user saw one frame of "Kaca" after
        // every recreation of the Activity (rotation, font scale, night mode)
        val storedUiStyle = remember { this@MainActivity.dataStore[UiStyleKey].toEnum(UiStyle.DEFAULT) }
        val uiStyle by rememberEnumPreference(UiStyleKey, defaultValue = UiStyle.DEFAULT, initialValue = storedUiStyle)
        // the stored family is read once before the first frame, so a user who chose another family never sees the default
        val storedIconFamily =
            remember {
                this@MainActivity.dataStore[com.metrolist.music.constants.IconFamilyKey]
                    .toEnum(com.metrolist.music.ui.component.IconFamily.DEFAULT)
            }
        val iconFamily by rememberEnumPreference(
            com.metrolist.music.constants.IconFamilyKey,
            defaultValue = com.metrolist.music.ui.component.IconFamily.DEFAULT,
            initialValue = storedIconFamily,
        )
        val (selectedThemeColorInt) = rememberPreference(SelectedThemeColorKey, defaultValue = DefaultThemeColor.toArgb())
        val selectedThemeColor = Color(selectedThemeColorInt)

        val showChangelog = rememberSaveable { mutableStateOf(false) }

        // The seed colour is only used by "Kustom"; the cover colour only by "Sampul" (a wash, never a control)
        val themeColor = selectedThemeColor
        var coverColor by remember { mutableStateOf<Color?>(null) }

        val themeColorCache = remember { mutableMapOf<String, Color?>() }

        // the one extraction per song feeds the hero washes ("Sampul") and the page shade ("Efek berbayang")
        val needsCoverColor = coverTintEnabled || coverShadeLevel != CoverShadeLevel.OFF
        LaunchedEffect(playerConnection, needsCoverColor) {
            val playerConnection = playerConnection
            if (!needsCoverColor || playerConnection == null) {
                coverColor = null
                return@LaunchedEffect
            }

            playerConnection.service.currentMediaMetadata
                .distinctUntilChanged { old, new -> old?.id == new?.id }
                .collectLatest { song ->
                    if (song?.thumbnailUrl != null) {
                        if (themeColorCache.containsKey(song.thumbnailUrl)) {
                            withFrameNanos { }
                            coverColor = themeColorCache[song.thumbnailUrl]
                            return@collectLatest
                        }
                        withContext(Dispatchers.IO) {
                            try {
                                val result =
                                    imageLoader.execute(
                                        ImageRequest
                                            .Builder(this@MainActivity)
                                            .data(song.thumbnailUrl)
                                            .size(128) // a colour needs no more pixels (Palette downsamples to about 112 anyway)
                                            .allowHardware(false)
                                            .memoryCachePolicy(CachePolicy.ENABLED)
                                            .diskCachePolicy(CachePolicy.ENABLED)
                                            .networkCachePolicy(CachePolicy.ENABLED)
                                            .crossfade(false)
                                            .build(),
                                    )
                                val extracted = result.image?.toBitmap()?.extractCoverTint()
                                themeColorCache[song.thumbnailUrl] = extracted
                                withFrameNanos { }
                                coverColor = extracted
                            } catch (e: Exception) {
                                withFrameNanos { }
                                coverColor = null
                            }
                        }
                    } else {
                        coverColor = null
                    }
                }
        }

        val appFont by rememberEnumPreference(com.metrolist.music.constants.AppFontKey, com.metrolist.music.ui.theme.AppFont.STYLE)
        val appFontShift by rememberPreference(com.metrolist.music.constants.AppFontWeightKey, 0)
        val iosMotion by rememberPreference(com.metrolist.music.constants.IosMotionKey, false)
        MetrolistTheme(
            darkTheme = useDarkTheme,
            pureBlack = pureBlack,
            themeColor = themeColor,
            style = uiStyle,
            appFont = appFont,
            fontWeightShift = appFontShift,
            iosMotion = iosMotion,
            iconFamily = iconFamily,
            colorMode = colorMode,
            coverColor = coverColor,
            shadeLevel = coverShadeLevel,
        ) {
            val currentDensity = LocalDensity.current
            val windowInfo = LocalWindowInfo.current
            val containerSize = windowInfo.containerDpSize
            val smallestDimensionDp = minOf(containerSize.width, containerSize.height)

            val landscapeDensityScale =
                remember(smallestDimensionDp, enableLandscapeScaling) {
                    if (enableLandscapeScaling) {
                        when {
                            smallestDimensionDp >= 840.dp -> 1.15f
                            smallestDimensionDp >= 720.dp -> 1.1f
                            smallestDimensionDp >= 600.dp -> 1.05f
                            else -> 1.0f
                        }
                    } else {
                        1.0f
                    }
                }
            val scaledDensity: Density = remember(currentDensity, landscapeDensityScale, userDensityScale) {
                Density(
                    density = currentDensity.density * landscapeDensityScale * userDensityScale,
                    fontScale = currentDensity.fontScale,
                )
            }

            CompositionLocalProvider(LocalDensity provides scaledDensity) {
            // The recorded layer starts opaque (page colour), like BitChord's backdrop: the page colour and glow used to be
            // painted by the root box OUTSIDE the recording, so wherever a page painted nothing the glass bars sampled
            // transparent pixels and showed only their flat tint.
            val backdropBase = if (pureBlack) Color.Black else MaterialTheme.colorScheme.surface
            // "Efek berbayang": the wash of the cover colour is drawn ONCE, here or in the root box below, never per page. It is
            // part of the recorded layer so the glass bars blur it too; the position and strength are read in the draw phase.
            val coverShade = LocalCoverShade.current
            val shadePageHeightPx = windowInfo.containerSize.height.toFloat()
            val paintBackdrop: androidx.compose.ui.graphics.drawscope.ContentDrawScope.() -> Unit =
                remember(backdropBase, coverShade, shadePageHeightPx) {
                    { drawRect(backdropBase); drawCoverShade(coverShade, shadePageHeightPx); drawContent() }
                }
            val appBackdrop = com.metrolist.music.ui.component.backdrop.backdrops.rememberLayerBackdrop(onDraw = paintBackdrop)
            // Horizon: the glass rim is static (the old tilt sensor was a 1 percent effect that cost battery), so no sensor is started
            val glassBlurPref by rememberPreference(GlassBlurKey, defaultValue = glassBlurDefault(androidx.compose.ui.platform.LocalContext.current))
            val glassBlurOn = glassBlurPref && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
            BoxWithConstraints(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(if (pureBlack) Color.Black else MaterialTheme.colorScheme.surface)
                        // Liquid Glass keeps its glow on pure black too: without it the status-bar and nav-bar strips stayed flat
                        // black while the page between them glowed, which read as two ugly bands.
                        // With glass blur on, the content box below already draws (and records) the glow: drawing it here too was a
                        // second full-screen glow draw per frame.
                        .then(
                            if (pureBlack && uiStyle != UiStyle.GLASS) {
                                Modifier
                            } else if (uiStyle == UiStyle.GLASS && glassBlurOn) {
                                Modifier.paperGrain()
                            } else {
                                Modifier.ambientGlow().paperGrain()
                            },
                        ),
            ) {
                // without glass blur the root box draws the wash (its own small layer, so the cross-fade only redraws it)
                if (coverShade.active && !(uiStyle == UiStyle.GLASS && glassBlurOn)) {
                    Box(Modifier.fillMaxSize().graphicsLayer { }.coverShade(coverShade, shadePageHeightPx))
                }
                val density = LocalDensity.current
                val configuration = LocalWindowInfo.current
                val cutoutInsets = WindowInsets.displayCutout
                val windowsInsets = WindowInsets.systemBars
                val bottomInset = with(density) { windowsInsets.getBottom(density).toDp() }
                val bottomInsetDp = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

                val navController = rememberNavController()

                // The activity's own HomeViewModel (its init starts the auto sync, the cookie and the network watchers). hiltViewModel() here
                // built the Hilt factory again (the whole ViewModel key set) on every recomposition of this scope, i.e. on every tab tap;
                // the instance is the same one, taken once. Its account image was collected here but never read.
                remember { androidx.lifecycle.ViewModelProvider(this@MainActivity)[HomeViewModel::class.java] }
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                // how far the current page has scrolled; page top bars fade their tint in from it (see TopBarScrollState)
                val topBarScroll =
                    rememberSaveable(saver = com.metrolist.music.ui.component.TopBarScrollStateSaver) {
                        com.metrolist.music.ui.component.TopBarScrollState()
                    }
                LaunchedEffect(navBackStackEntry?.id) { topBarScroll.switchTo(navBackStackEntry?.id) }
                // Read only inside the navigation effect below, never in composition: as a destructured pair every tab change wrote it
                // and recomposed this whole scope a second time, one frame after the page had been built.
                val previousTabState = rememberSaveable { mutableStateOf("home") }

                val (listenTogetherInTopBar) = rememberPreference(ListenTogetherInTopBarKey, defaultValue = true)
                // Listen Together lives on the You page now, so it is never a tab of its own
                val navigationItems =
                    remember {
                        Screens.MainScreens.filter { it != Screens.ListenTogether }
                    }
                val routeIndexMap = remember(navigationItems) {
                    navigationItems.mapIndexed { i, s -> s.route to i }.toMap()
                }
                val (forceBottomNav) = rememberPreference(ForceBottomNavBarKey, defaultValue = false)
                val (slimNav) = rememberPreference(SlimNavBarKey, defaultValue = false)
                val (collapseNavOnScroll) = rememberPreference(com.metrolist.music.constants.CollapseNavOnScrollKey, defaultValue = true)
                val (useNewMiniPlayerDesign) = rememberPreference(UseNewMiniPlayerDesignKey, defaultValue = true)
                val (defaultOpenTabInt) = rememberPreference(DefaultOpenTabKey, defaultValue = NavigationTab.HOME.name)
                val defaultOpenTab = remember(defaultOpenTabInt) {
                    try {
                        NavigationTab.valueOf(defaultOpenTabInt)
                    } catch (_: IllegalArgumentException) {
                        NavigationTab.HOME
                    }
                }
                val tabOpenedFromShortcut =
                    remember {
                        when (intent?.action) {
                            ACTION_SEARCH -> NavigationTab.SEARCH
                            ACTION_LIBRARY -> NavigationTab.LIBRARY
                            else -> null
                        }
                    }

                val topLevelScreens =
                    remember {
                        listOf(
                            Screens.Home.route,
                            Screens.Library.route,
                            Screens.You.route,
                            Screens.ListenTogether.route,
                            "settings",
                        )
                    }

                val (query, onQueryChange) =
                    rememberSaveable(stateSaver = TextFieldValue.Saver) {
                        mutableStateOf(TextFieldValue())
                    }

                val onSearch: (String) -> Unit =
                    remember {
                        { searchQuery ->
                            if (searchQuery.isNotEmpty()) {
                                navController.navigate(SearchRoutes.resultRoute(searchQuery))

                                if (dataStore[PauseSearchHistoryKey] != true) {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        runCatching {
                                            database.insert(SearchHistory(query = searchQuery))
                                        }.onFailure { throwable ->
                                            Timber
                                                .tag("MainActivity")
                                                .w(throwable, "Failed to save search history for query: %s", searchQuery)
                                        }
                                    }
                                }
                            }
                        }
                    }

                val currentRoute by remember {
                    derivedStateOf { navBackStackEntry?.destination?.route }
                }
                // a page with its own header colour (album, playlist, artist, podcast): the page shade fades out under it
                LaunchedEffect(coverShade, currentRoute) { coverShade.setPageOwnsHeader(CoverShadeMath.pageOwnsHeader(currentRoute)) }

                val inSearchScreen by remember {
                    derivedStateOf { currentRoute?.startsWith("search/") == true }
                }
                val navigationItemRoutes =
                    remember(navigationItems) {
                        navigationItems.map { it.route }.toSet()
                    }

                val shouldShowNavigationBar =
                    remember(currentRoute, navigationItemRoutes) {
                        currentRoute == null ||
                            navigationItemRoutes.contains(currentRoute) ||
                            currentRoute!!.startsWith("search/")
                    }

                val isLandscape = configuration.containerDpSize.width > configuration.containerDpSize.height
                val isTablet = configuration.containerDpSize.width >= 600.dp

                val showRail = !forceBottomNav && (isLandscape || isTablet) && !inSearchScreen

                val navPadding =
                    if (shouldShowNavigationBar && !showRail) {
                        if (slimNav) SlimNavBarHeight else NavigationBarHeight
                    } else {
                        0.dp
                    }

                val navigationBarHeight by animateDpAsState(
                    targetValue = if (shouldShowNavigationBar && !showRail) NavigationBarHeight else 0.dp,
                    animationSpec = NavigationBarAnimationSpec,
                    label = "navBarHeight",
                )

                val playerBottomSheetState =
                    rememberBottomSheetState(
                        dismissedBound = 0.dp,
                        collapsedBound =
                            bottomInset +
                                (if (!showRail && shouldShowNavigationBar) navPadding else 0.dp) +
                                (if (useNewMiniPlayerDesign) MiniPlayerBottomSpacing else 0.dp) +
                                MiniPlayerHeight,
                        expandedBound = maxHeight,
                    )

                val playerReadyState =
                    playerConnection?.service?.isPlayerReady?.collectAsStateWithLifecycle()
                        ?: remember { mutableStateOf(false) }
                val playerReady by playerReadyState
                val activePlayerConnection = if (playerReady) playerConnection else null

                val playerAwareWindowInsets =
                    remember(
                        bottomInset,
                        shouldShowNavigationBar,
                        playerBottomSheetState.isDismissed,
                        showRail,
                        useNewMiniPlayerDesign,
                    ) {
                        var bottom = bottomInset
                        if (shouldShowNavigationBar && !showRail) {
                            bottom += NavigationBarHeight
                        }
                        if (!playerBottomSheetState.isDismissed) {
                            bottom += MiniPlayerHeight
                            // the new mini player floats MiniPlayerBottomSpacing above the bar; without it the last row ended behind it
                            if (useNewMiniPlayerDesign) bottom += MiniPlayerBottomSpacing
                        }
                        windowsInsets
                            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
                            .add(WindowInsets(top = AppBarHeight, bottom = bottom))
                    }
                appBarScrollBehavior(
                    canScroll = {
                        !inSearchScreen &&
                            (playerBottomSheetState.isCollapsed || playerBottomSheetState.isDismissed)
                    },
                )

                val topAppBarScrollBehavior =
                    appBarScrollBehavior(
                        canScroll = {
                            !inSearchScreen &&
                                (playerBottomSheetState.isCollapsed || playerBottomSheetState.isDismissed)
                        },
                    )

                // Liquid Glass bar (Kaca, every Android version since 4.3.4): folds into a pill while content scrolls down
                val navFold =
                    com.metrolist.music.ui.component.motion.rememberNavBarFoldState(
                        enabled = com.metrolist.music.ui.component.GlassBarPolicy.foldEnabled(collapseNavOnScroll, uiStyle == UiStyle.GLASS, shouldShowNavigationBar, showRail),
                        canFold = { playerBottomSheetState.isCollapsed || playerBottomSheetState.isDismissed },
                    )

                // the mini player drops next to the folded pill: its centre moves to the pill's centre (NavFoldGeometry)
                navFold.hideFollow = !(shouldShowNavigationBar && !showRail)
                navFold.pillSizeDp = com.metrolist.music.ui.component.motion.NavFoldGeometry.pillSizeDp(slimNav)
                navFold.miniShiftDp =
                    com.metrolist.music.ui.component.motion.NavFoldGeometry.miniShiftDp(MiniPlayerHeight.value, MiniPlayerBottomSpacing.value, navFold.pillSizeDp)
                // opening the full player always brings the bar back
                LaunchedEffect(playerBottomSheetState.isCollapsed, playerBottomSheetState.isDismissed) {
                    if (!playerBottomSheetState.isCollapsed && !playerBottomSheetState.isDismissed) navFold.expand()
                }

                // Navigation tracking
                LaunchedEffect(navBackStackEntry) {
                    if (inSearchScreen) {
                        val searchQuery =
                            SearchRoutes.decodeQuery(
                                navBackStackEntry?.arguments?.getString("query").orEmpty(),
                            )
                        onQueryChange(
                            TextFieldValue(
                                searchQuery,
                                TextRange(searchQuery.length),
                            ),
                        )
                    } else if (navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route }) {
                        onQueryChange(TextFieldValue())
                    }

                    // Reset scroll behavior for main navigation items
                    if (navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route }) {
                        if (navigationItems.fastAny { it.route == previousTabState.value }) {
                            topAppBarScrollBehavior.state.resetHeightOffset()
                        }
                    }

                    topAppBarScrollBehavior.state.resetHeightOffset()

                    // Collapse player when navigating to equalizer
                    if (navBackStackEntry?.destination?.route == "equalizer" &&
                        playerBottomSheetState.isExpanded
                    ) {
                        playerBottomSheetState.collapseSoft()
                    }

                    // Track previous tab for animations
                    navController.currentBackStackEntry?.destination?.route?.let {
                        previousTabState.value = it
                    }
                }

                LaunchedEffect(activePlayerConnection) {
                    val player = runCatching { activePlayerConnection?.player }.getOrNull()
                    if (player?.currentMediaItem == null) {
                        if (!playerBottomSheetState.isDismissed) {
                            playerBottomSheetState.dismiss()
                        }
                        return@LaunchedEffect
                    }

                    if (playerBottomSheetState.isDismissed) {
                        playerBottomSheetState.collapseSoft()
                    }
                }

                DisposableEffect(activePlayerConnection, playerBottomSheetState) {
                    val player = runCatching { activePlayerConnection?.player }.getOrNull()
                        ?: return@DisposableEffect onDispose { }
                    val listener =
                        object : Player.Listener {
                            override fun onMediaItemTransition(
                                mediaItem: MediaItem?,
                                reason: Int,
                            ) {
                                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
                                    mediaItem != null &&
                                    playerBottomSheetState.isDismissed
                                ) {
                                    playerBottomSheetState.collapseSoft()
                                }
                            }
                        }
                    player.addListener(listener)
                    onDispose {
                        player.removeListener(listener)
                    }
                }

                var shouldShowTopBar by rememberSaveable { mutableStateOf(false) }

                LaunchedEffect(navBackStackEntry, listenTogetherInTopBar) {
                    val currentRoute = navBackStackEntry?.destination?.route
                    val isListenTogetherScreen =
                        currentRoute == Screens.ListenTogether.route ||
                            currentRoute == "listen_together_from_topbar"
                    shouldShowTopBar = currentRoute in topLevelScreens &&
                        currentRoute != "settings" &&
                        !(isListenTogetherScreen && listenTogetherInTopBar)
                }

                val coroutineScope = rememberCoroutineScope()
                var sharedSong: SongItem? by remember {
                    mutableStateOf(null)
                }
                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(Unit) {
                    if (pendingIntent != null) {
                        handleRecognitionIntent(pendingIntent!!, navController)
                        handleDeepLinkIntent(pendingIntent!!, navController)
                        pendingIntent = null
                    } else {
                        handleRecognitionIntent(intent, navController)
                        handleDeepLinkIntent(intent, navController)
                    }
                }

                DisposableEffect(Unit) {
                    val listener =
                        Consumer<Intent> { intent ->
                            handleRecognitionIntent(intent, navController)
                            handleDeepLinkIntent(intent, navController)
                        }

                    addOnNewIntentListener(listener)
                    onDispose { removeOnNewIntentListener(listener) }
                }

                val currentTitleRes =
                    remember(navBackStackEntry) {
                        when (navBackStackEntry?.destination?.route) {
                            Screens.Home.route -> R.string.home
                            Screens.Search.route -> R.string.search
                            Screens.Library.route -> R.string.filter_library
                            Screens.You.route -> R.string.you_title
                            Screens.ListenTogether.route -> R.string.together
                            else -> null
                        }
                    }


                val pauseListenHistory by rememberPreference(PauseListenHistoryKey, defaultValue = false)
                // the flow is made once: a new Room flow on every recomposition restarted the query on every tab tap
                val eventCountFlow = remember(database) { database.eventCount() }
                val eventCount by eventCountFlow.collectAsStateWithLifecycle(initialValue = 0)
                val showHistoryButton =
                    remember(pauseListenHistory, eventCount) {
                        !(pauseListenHistory && eventCount == 0)
                    }

                // strip under the nav capsule (gesture-bar inset): it used to be a flat surfaceContainer slab that differed from the
                // page colour. Now it is the page colour and fades in from transparent, so no edge shows (BitChord's bottom scrim idea).
                val pageColor = if (pureBlack) Color.Black else MaterialTheme.colorScheme.surface
                val insetStripBrush =
                    remember(uiStyle, pageColor) {
                        if (uiStyle == UiStyle.GLASS) {
                            androidx.compose.ui.graphics.SolidColor(Color.Transparent)
                        } else {
                            // Transparent -> page colour in the upper part, then SOLID: the 3-button bar used to sit on a half-clear strip and
                            // the list showed through under its icons (owner screenshot 2026-11-17, Datar theme)
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                colorStops =
                                    arrayOf(
                                        0f to Color.Transparent,
                                        0.28f to pageColor.copy(alpha = 0.94f),
                                        0.5f to pageColor,
                                    ),
                            )
                        }
                    }
                val artistNameAliases by ArtistNameAliases.aliases.collectAsStateWithLifecycle()

                // the large titles of Pustaka / Kamu (and Beranda): the pages write how far their title has scrolled, the top bar reads it
                val largeTitleState = remember { com.metrolist.music.ui.component.LargeTitleState() }

                CompositionLocalProvider(
                    com.metrolist.music.ui.component.LocalLargeTitle provides largeTitleState,
                    LocalDatabase provides database,
                    LocalNavController provides navController,
                    LocalContentColor provides if (pureBlack) Color.White else contentColorFor(MaterialTheme.colorScheme.surface),
                    // Screens get the connection only once the service's player exists: reading `player` before that
                    // throws "Player not yet initialized" (crash report 2026-11-13, media info screen on 4.3.3).
                    LocalPlayerConnection provides activePlayerConnection,
                    LocalPlayerAwareWindowInsets provides playerAwareWindowInsets,
                    LocalDownloadUtil provides downloadUtil,
                    LocalShimmerTheme provides ShimmerTheme,
                    LocalSyncUtils provides syncUtils,
                    LocalListenTogetherManager provides listenTogetherManager,
                    LocalChangelogState provides showChangelog,
                    com.metrolist.music.ui.component.LocalAppBackdrop provides (if (uiStyle == UiStyle.GLASS && glassBlurOn) appBackdrop else null),
                    LocalGlassBlurEnabled provides glassBlurOn,
                    // the pages read it too (floating buttons drop with the mini player when the bar folds), not only the bottom bar slot
                    com.metrolist.music.ui.component.motion.LocalNavFold provides navFold,
                    com.metrolist.music.ui.component.LocalTopBarScroll provides topBarScroll,
                    LocalArtistNameAliases provides artistNameAliases,
                ) {
                    if (showChangelog.value) {
                        ChangelogScreen(onDismiss = { showChangelog.value = false })
                    }

                    Scaffold(
                        containerColor = Color.Transparent,
                        snackbarHost = { AuralysSnackbarHost(snackbarHostState) },
                        topBar = {
                            AnimatedVisibility(
                                visible = shouldShowTopBar,
                                enter = fadeIn(animationSpec = tween(durationMillis = 300)),
                                exit = fadeOut(animationSpec = tween(durationMillis = 200)),
                            ) {
                                Row {
                                  com.metrolist.music.ui.component.GlassTopFadeContainer(topAppBarScrollBehavior, Modifier.fillMaxWidth()) {
                                    TopAppBar(
                                        title = {
                                            Text(
                                                text = currentTitleRes?.let { stringResource(it) } ?: "",
                                                style = MaterialTheme.typography.titleLarge,
                                                modifier = Modifier.largeTitleBarTitle(),
                                            )
                                        },
                                        // no icons here any more: history, stats, listen together and settings are on the You tab
                                        scrollBehavior = topAppBarScrollBehavior,
                                        autoScrim = false, // this bar has its own fade container (GlassTopFadeContainer)
                                        colors =
                                            TopAppBarDefaults.topAppBarColors(
                                                // with the shade on, the bar paints the page colour AND the wash itself (see StyledTopAppBar)
                                                containerColor = if (uiStyle == UiStyle.GLASS || coverShade.active) Color.Transparent else if (pureBlack) Color.Black else MaterialTheme.colorScheme.surface,
                                                scrolledContainerColor = if (uiStyle == UiStyle.GLASS || coverShade.active) Color.Transparent else if (pureBlack) Color.Black else MaterialTheme.colorScheme.surface,
                                                titleContentColor = MaterialTheme.colorScheme.onSurface,
                                                actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            ),
                                        modifier =
                                            Modifier.windowInsetsPadding(
                                                if (showRail) {
                                                    WindowInsets(left = NavigationBarHeight)
                                                        .add(cutoutInsets.only(WindowInsetsSides.Start))
                                                } else {
                                                    cutoutInsets.only(WindowInsetsSides.Start + WindowInsetsSides.End)
                                                },
                                            ),
                                    )
                                  }
                                }
                            }
                        },
                        bottomBar = {
                          CompositionLocalProvider(com.metrolist.music.ui.component.motion.LocalNavFold provides navFold) {
                            val currentBackStackEntry = navController.currentBackStackEntry // reads reactively outside remember

                            val onNavItemClick: (Screens, Boolean) -> Unit =
                                remember(
                                    navController,
                                    coroutineScope,
                                    topAppBarScrollBehavior,
                                    playerBottomSheetState,
                                    currentBackStackEntry,
                                ) {
                                    { screen: Screens, isSelected: Boolean ->
                                        if (playerBottomSheetState.isExpanded) {
                                            playerBottomSheetState.collapseSoft()
                                        }
                                        if (isSelected) {
                                            val targetEntry =
                                                try {
                                                    val route = navController.currentBackStackEntry?.destination?.route
                                                    if (route == SearchRoutes.ROUTE || route == "search_input") {
                                                        // For search screens, use search_input entry
                                                        navController.getBackStackEntry("search_input")
                                                    } else {
                                                        // For other screens, use current entry
                                                        navController.currentBackStackEntry
                                                    }
                                                } catch (e: Exception) {
                                                    null
                                                }

                                            // Use appropriate key based on screen type
                                            if (screen == Screens.Search) {
                                                val current = targetEntry?.savedStateHandle?.get<Int>("scrollToTopCount") ?: 0
                                                targetEntry?.savedStateHandle?.set("scrollToTopCount", current + 1)
                                            } else {
                                                targetEntry?.savedStateHandle?.set("scrollToTop", true)
                                            }

                                            coroutineScope.launch {
                                                topAppBarScrollBehavior.state.resetHeightOffset()
                                            }
                                        } else {
                                            navController.navigateToTab(screen.route)
                                        }
                                    }
                                }

                            val onSearchLongClick: () -> Unit =
                                remember(navController) {
                                    {
                                        navController.navigate("recognition") {
                                            launchSingleTop = true
                                        }
                                    }
                                }

                            // Pre-calculate values for graphicsLayer to avoid reading state during composition
                            val navBarTotalHeight = bottomInset + NavigationBarHeight

                            if (!showRail) {
                                Box {
                                    if (activePlayerConnection != null) {
                                        BottomSheetPlayer(
                                            state = playerBottomSheetState,
                                            navController = navController,
                                            pureBlack = pureBlack,
                                        )
                                    }

                                    AppNavigationBar(
                                        navigationItems = navigationItems,
                                        currentRoute = currentRoute,
                                        onItemClick = onNavItemClick,
                                        pureBlack = pureBlack,
                                        slimNav = slimNav,
                                        onSearchLongClick = onSearchLongClick,
                                        onHomeLongHold = { navController.navigate("settings") },
                                        foldState = navFold,
                                        modifier =
                                            Modifier
                                                .align(Alignment.BottomCenter)
                                                .height(bottomInset + navPadding)
                                                // Use graphicsLayer instead of offset to avoid recomposition
                                                // graphicsLayer runs during draw phase, not composition phase
                                                .graphicsLayer {
                                                    val navBarHeightPx = navigationBarHeight.toPx()
                                                    val totalHeightPx = navBarTotalHeight.toPx()

                                                    translationY =
                                                        if (navBarHeightPx == 0f) {
                                                            totalHeightPx
                                                        } else {
                                                            // Read progress only during draw phase
                                                            val progress = playerBottomSheetState.progress.coerceIn(0f, 1f)
                                                            val slideOffset = totalHeightPx * progress
                                                            val hideOffset =
                                                                totalHeightPx * (1 - navBarHeightPx / NavigationBarHeight.toPx())
                                                            slideOffset + hideOffset
                                                        }
                                                },
                                    )

                                    Box(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .align(Alignment.BottomCenter)
                                                .height(bottomInsetDp)
                                                // Use graphicsLayer for background color changes
                                                .graphicsLayer {
                                                    val progress = playerBottomSheetState.progress
                                                    alpha =
                                                        if (progress > 0f ||
                                                            (useNewMiniPlayerDesign && !shouldShowNavigationBar)
                                                        ) {
                                                            0f
                                                        } else {
                                                            1f
                                                        }
                                                }.background(insetStripBrush),
                                    )
                                }
                            } else {
                                if (true) {
                                    if (activePlayerConnection != null) {
                                        BottomSheetPlayer(
                                            state = playerBottomSheetState,
                                            navController = navController,
                                            pureBlack = pureBlack,
                                        )
                                    }
                                }

                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .align(Alignment.BottomCenter)
                                            .height(bottomInsetDp)
                                            // Use graphicsLayer for background color changes
                                            .graphicsLayer {
                                                val progress = playerBottomSheetState.progress
                                                alpha =
                                                    if (progress > 0f || (useNewMiniPlayerDesign && !shouldShowNavigationBar)) 0f else 1f
                                            }.background(insetStripBrush),
                                )
                            }
                          }
                        },
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .nestedScroll(navFold.connection)
                                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
                    ) {
                        // the root box no longer draws the glow when the content box does; keep it behind the side rail
                        Row(
                            Modifier.fillMaxSize()
                                .then(if (showRail && uiStyle == UiStyle.GLASS && glassBlurOn) Modifier.ambientGlow().coverShade(coverShade, shadePageHeightPx) else Modifier),
                        ) {
                            val onRailItemClick: (Screens, Boolean) -> Unit =
                                remember(navController, coroutineScope, topAppBarScrollBehavior, playerBottomSheetState) {
                                    { screen: Screens, isSelected: Boolean ->
                                        if (playerBottomSheetState.isExpanded) {
                                            playerBottomSheetState.collapseSoft()
                                        }

                                        if (isSelected) {
                                            navController.currentBackStackEntry?.savedStateHandle?.set("scrollToTop", true)
                                            coroutineScope.launch {
                                                topAppBarScrollBehavior.state.resetHeightOffset()
                                            }
                                        } else {
                                            navController.navigateToTab(screen.route)
                                        }
                                    }
                                }

                            val onRailSearchLongClick: () -> Unit =
                                remember(navController) {
                                    {
                                        navController.navigate("recognition") {
                                            launchSingleTop = true
                                        }
                                    }
                                }

                            if (showRail) {
                                AppNavigationRail(
                                    navigationItems = navigationItems,
                                    currentRoute = currentRoute,
                                    onItemClick = onRailItemClick,
                                    pureBlack = pureBlack,
                                    onSearchLongClick = onRailSearchLongClick,
                                    onHomeLongHold = { navController.navigate("settings") },
                                )
                            }
                            Box(Modifier.weight(1f).fillMaxSize().then(if (uiStyle == UiStyle.GLASS && glassBlurOn) Modifier.layerBackdrop(appBackdrop).ambientGlow() else Modifier)) {
                                // NavHost with animations (Material 3 Expressive style)
                                NavHost(
                                    navController = navController,
                                    startDestination =
                                        when (tabOpenedFromShortcut ?: defaultOpenTab) {
                                            NavigationTab.HOME -> Screens.Home
                                            NavigationTab.SEARCH -> Screens.Search
                                            NavigationTab.LIBRARY -> Screens.Library
                                        }.route,
                                    // iOS animations: pushed screens slide in with parallax on the one underneath; tab switches just cross-fade
                                    // (shorter now, 160 ms in and 90 ms out: two full pages faded on top of each other for 220 ms on every tab tap, which
                                    // the owner's videos showed next to a 100-140 ms stall)
                                    enterTransition = {
                                        if (iosMotion && !isTabSwitch(initialState.destination.route, targetState.destination.route)) {
                                            slideInHorizontally(FrameCappedSpec(spring(dampingRatio = 0.86f, stiffness = 400f))) { (it * 0.55f).toInt() } + fadeIn(FrameCappedSpec(tween(220)))
                                        } else {
                                            fadeIn(FrameCappedSpec(tween(160, easing = androidx.compose.animation.core.FastOutSlowInEasing)))
                                        }
                                    },
                                    exitTransition = {
                                        if (iosMotion && !isTabSwitch(initialState.destination.route, targetState.destination.route)) {
                                            slideOutHorizontally(FrameCappedSpec(spring(dampingRatio = 0.86f, stiffness = 400f))) { -(it * 0.25f).toInt() } + fadeOut(FrameCappedSpec(tween(180)))
                                        } else {
                                            fadeOut(FrameCappedSpec(tween(90, easing = androidx.compose.animation.core.FastOutLinearInEasing)))
                                        }
                                    },
                                    popEnterTransition = {
                                        if (iosMotion && !isTabSwitch(initialState.destination.route, targetState.destination.route)) {
                                            slideInHorizontally(FrameCappedSpec(spring(dampingRatio = 0.86f, stiffness = 400f))) { -(it * 0.25f).toInt() } + fadeIn(FrameCappedSpec(tween(220)))
                                        } else {
                                            fadeIn(FrameCappedSpec(tween(160, easing = androidx.compose.animation.core.FastOutSlowInEasing)))
                                        }
                                    },
                                    popExitTransition = {
                                        if (iosMotion && !isTabSwitch(initialState.destination.route, targetState.destination.route)) {
                                            slideOutHorizontally(FrameCappedSpec(spring(dampingRatio = 0.86f, stiffness = 400f))) { (it * 0.55f).toInt() } + fadeOut(FrameCappedSpec(tween(180)))
                                        } else {
                                            fadeOut(FrameCappedSpec(tween(90, easing = androidx.compose.animation.core.FastOutLinearInEasing)))
                                        }
                                    },
                                    modifier =
                                        Modifier
                                            .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                                            .nestedScroll(topBarScroll.connection),
                                ) {
                                    navigationBuilder(
                                        navController = navController,
                                        scrollBehavior = topAppBarScrollBehavior,
                                        latestVersionName = latestVersionName,
                                        activity = this@MainActivity,
                                        snackbarHostState = snackbarHostState,
                                    )
                                }
                            }
                        }
                    }

                    BottomSheetMenu(
                        state = LocalMenuState.current,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )

                    BottomSheetPage(
                        state = LocalBottomSheetPageState.current,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )

                    sharedSong?.let { song ->
                        playerConnection?.let {
                            Dialog(
                                onDismissRequest = { sharedSong = null },
                                properties = DialogProperties(usePlatformDefaultWidth = false),
                            ) {
                                Surface(
                                    modifier = Modifier.padding(24.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    color = AlertDialogDefaults.containerColor,
                                    tonalElevation = AlertDialogDefaults.TonalElevation,
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        YouTubeSongMenu(
                                            song = song,
                                            onDismiss = { sharedSong = null },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Auralys: the notice for a newer version listed on the download page
                    webUpdate?.let { version ->
                        com.metrolist.music.ui.component.WebUpdateDialog(version = version, onDismiss = { webUpdate = null })
                    }

                    if (!showChangelog.value) {
                        availableUpdate?.let { update ->
                            val dismissUpdate: () -> Unit = {
                                availableUpdate = null
                                lifecycleScope.launch {
                                    safeDataStoreEdit {
                                        it[update.dismissalKey] = update.release.tagName
                                    }
                                }
                            }
                            AlertDialog(
                                onDismissRequest = dismissUpdate,
                                title = { Text(stringResource(R.string.update_available_title)) },
                                text = {
                                    Column(
                                        modifier =
                                            Modifier
                                                .heightIn(max = 480.dp)
                                                .verticalScroll(rememberScrollState()),
                                    ) {
                                        Text(
                                            text =
                                                stringResource(
                                                    if (update.isKmp) {
                                                        R.string.kmp_upgrade_title
                                                    } else {
                                                        R.string.update_available_message
                                                    },
                                                    update.release.versionName,
                                                ),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        if (update.isKmp) {
                                            Text(
                                                text = stringResource(R.string.kmp_upgrade_warning),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.padding(top = 12.dp),
                                            )
                                        }
                                        Text(
                                            text = stringResource(R.string.changelog),
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                                        )
                                        Text(
                                            text = update.release.description.ifBlank { stringResource(R.string.changelog_empty) },
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            dismissUpdate()
                                            startActivity(Intent(Intent.ACTION_VIEW, update.downloadUrl.toUri()))
                                        },
                                    ) {
                                        Text(
                                            stringResource(
                                                if (update.isKmp) R.string.kmp_upgrade_action else R.string.update_action,
                                            ),
                                        )
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = dismissUpdate) {
                                        Text(stringResource(R.string.kmp_upgrade_later))
                                    }
                                },
                            )
                        }
                    }
                }

                // First launch: sign in to YouTube Music, or go on without an account
                val welcomeContext = androidx.compose.ui.platform.LocalContext.current
                val showWelcome by androidx.compose.runtime.produceState<Boolean?>(null) {
                    welcomeContext.dataStore.data
                        .map { prefs ->
                            val done = prefs[OnboardingDoneKey] == true
                            val loggedIn = "SAPISID" in parseCookieString(prefs[InnerTubeCookieKey].orEmpty())
                            !done && !loggedIn
                        }.collect { value = it }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = showWelcome == true,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(350)),
                ) {
                    val welcomeScope = rememberCoroutineScope()
                    com.metrolist.music.ui.component.WelcomeScreen(
                        onLogin = {
                            welcomeScope.launch {
                                welcomeContext.safeDataStoreEdit { it[OnboardingDoneKey] = true }
                                navController.navigate("login")
                            }
                        },
                        onSkip = {
                            welcomeScope.launch { welcomeContext.safeDataStoreEdit { it[OnboardingDoneKey] = true } }
                        },
                    )
                }
            }
            }
        }
    }

    /**
     * Handles the ACTION_RECOGNITION intent sent from the Music Recognizer Widget.
     * Always navigates to the recognition screen to show the result.
     */
    private fun handleRecognitionIntent(
        intent: Intent,
        navController: NavHostController,
    ) {
        if (intent.action != ACTION_RECOGNITION) return
        val autoStart = intent.getBooleanExtra(EXTRA_AUTO_START_RECOGNITION, false)
        intent.action = null
        intent.removeExtra(EXTRA_AUTO_START_RECOGNITION)
        navController.navigate(if (autoStart) "recognition?autoStart=true" else "recognition") {
            launchSingleTop = true
        }
    }

    private fun handleDeepLinkIntent(
        intent: Intent,
        navController: NavHostController,
    ) {
        val uri = intent.data ?: intent.extras?.getString(Intent.EXTRA_TEXT)?.toUri() ?: return
        intent.data = null
        intent.removeExtra(Intent.EXTRA_TEXT)
        val coroutineScope = lifecycle.coroutineScope

        val listenCode =
            uri.getQueryParameter("code")
                ?: uri.getQueryParameter("room")
                ?: uri.pathSegments.getOrNull(1)
        val isListenLink = uri.pathSegments.firstOrNull() == "listen" || uri.host?.equals("listen", ignoreCase = true) == true
        if (!listenCode.isNullOrBlank() && isListenLink) {
            val username = dataStore.get(ListenTogetherUsernameKey, "").ifBlank { "Guest" }
            listenTogetherManager.joinRoom(listenCode, username)
            return
        }

        when (val path = uri.pathSegments.firstOrNull()) {
            "playlist" -> {
                val playlistId = uri.getQueryParameter("list")
                if (playlistId.isNullOrBlank() || playlistId.equals("null", ignoreCase = true)) {
                    Toast.makeText(this, R.string.playlist_unavailable, Toast.LENGTH_SHORT).show()
                    return
                }

                if (playlistId.startsWith("OLAK5uy_")) {
                    coroutineScope.launch(Dispatchers.IO) {
                        YouTube
                            .albumSongs(playlistId)
                            .onSuccess { songs ->
                                songs.firstOrNull()?.album?.id?.let { browseId ->
                                    withContext(Dispatchers.Main) {
                                        navController.navigate("album/$browseId")
                                    }
                                }
                            }.onFailure { reportException(it) }
                    }
                } else {
                    navController.navigate("online_playlist/$playlistId")
                }
            }

            "browse" -> {
                uri.lastPathSegment?.let { browseId ->
                    navController.navigate("album/$browseId")
                }
            }

            "channel", "c" -> {
                uri.lastPathSegment?.let { artistId ->
                    navController.navigate("artist/$artistId")
                }
            }

            "search" -> {
                uri.getQueryParameter("q")?.let {
                    navController.navigate(SearchRoutes.resultRoute(it))
                }
            }

            else -> {
                val videoId =
                    when {
                        path == "watch" -> uri.getQueryParameter("v")
                        uri.host == "youtu.be" -> uri.pathSegments.firstOrNull()
                        else -> null
                    }

                val playlistId = uri.getQueryParameter("list")

                if (videoId != null) {
                    coroutineScope.launch(Dispatchers.IO) {
                        YouTube
                            .queue(listOf(videoId), playlistId)
                            .onSuccess { queue ->
                                withContext(Dispatchers.Main) {
                                    playerConnection?.playQueue(
                                        YouTubeQueue(
                                            WatchEndpoint(videoId = queue.firstOrNull()?.id, playlistId = playlistId),
                                            queue.firstOrNull()?.toMediaMetadata(),
                                        ),
                                    )
                                }
                            }.onFailure {
                                reportException(it)
                            }
                    }
                } else if (playlistId != null) {
                    coroutineScope.launch(Dispatchers.IO) {
                        YouTube
                            .queue(null, playlistId)
                            .onSuccess { queue ->
                                val firstItem = queue.firstOrNull()
                                withContext(Dispatchers.Main) {
                                    playerConnection?.playQueue(
                                        YouTubeQueue(
                                            WatchEndpoint(videoId = firstItem?.id, playlistId = playlistId),
                                            firstItem?.toMediaMetadata(),
                                        ),
                                    )
                                }
                            }.onFailure {
                                reportException(it)
                            }
                    }
                }
            }
        }
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun setSystemBarAppearance(isDark: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView.rootView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            window.statusBarColor = (if (isDark) Color.Transparent else Color.Black.copy(alpha = 0.2f)).toArgb()
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            window.navigationBarColor = (if (isDark) Color.Transparent else Color.Black.copy(alpha = 0.2f)).toArgb()
        }
    }
}

val LocalDatabase = staticCompositionLocalOf<MusicDatabase> { error("No database provided") }
val LocalNavController = staticCompositionLocalOf<NavController> { error("No NavController provided") }
val LocalPlayerConnection = staticCompositionLocalOf<PlayerConnection?> { error("No PlayerConnection provided") }
val LocalPlayerAwareWindowInsets = compositionLocalOf<WindowInsets> { error("No WindowInsets provided") }
val LocalDownloadUtil = staticCompositionLocalOf<DownloadUtil> { error("No DownloadUtil provided") }
val LocalSyncUtils = staticCompositionLocalOf<SyncUtils> { error("No SyncUtils provided") }
val LocalListenTogetherManager = staticCompositionLocalOf<com.metrolist.music.listentogether.ListenTogetherManager?> { null }
val LocalChangelogState = staticCompositionLocalOf<MutableState<Boolean>> { error("No LocalChangelogState provided") }
val LocalArtistNameAliases = staticCompositionLocalOf<Map<String, String>> { emptyMap() }
val LocalIsPlayerExpanded = compositionLocalOf { false }

private val TabRoutes: Set<String> by lazy { Screens.MainScreens.map { it.route }.toSet() }

private fun isTabSwitch(from: String?, to: String?): Boolean =
    from != null && to != null && from in TabRoutes && to in TabRoutes

/**
 * A tab change. navigate(route) matches the route against the deep-link pattern of every destination, which compiles a Regex for each
 * of them on every call (about 10 ms of the tap frame on a phone that has not compiled the app yet). The id of a route destination is
 * known, so go by id: same options, same back stack, no matching. Falls back to the route when the destination is not found.
 */
private fun NavHostController.navigateToTab(route: String) {
    val options =
        androidx.navigation.navOptions {
            popUpTo(graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    val node = graph.findNode(route)
    if (node != null) navigate(node.id, null, options) else navigate(route, options)
}

/** The download page is asked once per process, not again after the activity is recreated (a rotation, say). */
private var webUpdateChecked = false
