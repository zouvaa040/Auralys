/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.metrolist.music.ui.component.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import com.metrolist.music.ui.component.AuralysSnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.metrolist.music.ui.component.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import com.metrolist.music.ui.component.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.RoundedPolygon
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.BuildConfig
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.glassCard
import com.metrolist.music.ui.theme.HorizonRadius
import com.metrolist.music.ui.utils.backToMain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

private data class Contributor(
    val name: String,
    val roleRes: Int,
    val githubHandle: String,
    val avatarUrl: String = "https://github.com/$githubHandle.png",
    val githubUrl: String = "https://github.com/$githubHandle",
    val sponsorUrl: String? = null,
    val polygon: RoundedPolygon? = null,
    val favoriteSongVideoId: String? = null
)

private data class CommunityLink(
    val labelRes: Int,
    val iconRes: Int,
    val url: String
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val leadDeveloper = Contributor(
    name = "DAPZ.DEV",
    roleRes = R.string.credits_developer,
    githubHandle = "",
    polygon = MaterialShapes.Cookie9Sided,
    favoriteSongVideoId = "Mh2JWGWvy_Y"
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val collaborators = listOf(
    Contributor(name = "Adriel O'Connel", roleRes = R.string.credits_collaborator, githubHandle = "adrielGGmotion", sponsorUrl = "https://github.com/sponsors/adrielGGmotion", polygon = MaterialShapes.Cookie4Sided, favoriteSongVideoId = "m2zUrruKjDQ"),
    Contributor(name = "Nyx", roleRes = R.string.credits_collaborator, githubHandle = "nyxiereal", sponsorUrl = "https://github.com/sponsors/nyxiereal", polygon = MaterialShapes.Cookie12Sided, favoriteSongVideoId = "zselaN6zPXw"),
)

private val communityLinks = listOf(
    CommunityLink(R.string.credits_license_name, R.drawable.info, "https://www.gnu.org/licenses/gpl-3.0.html")
)

private fun handleEasterEggClick(
    clickCount: Int,
    favoriteSongVideoId: String?,
    coroutineScope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    playerConnection: PlayerConnection?,
    wannaPlayStr: String,
    yeahStr: String,
    onCountUpdate: (Int) -> Unit
) {
    if (favoriteSongVideoId != null) {
        val newCount = clickCount + 1
        onCountUpdate(newCount)
        if (newCount >= 3) {
            onCountUpdate(0)
            coroutineScope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = wannaPlayStr,
                    actionLabel = yeahStr,
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) {
                    playerConnection?.playQueue(YouTubeQueue(WatchEndpoint(videoId = favoriteSongVideoId)))
                }
            }
        }
    }
}

@Composable
private fun ContributorAvatar(
    avatarUrl: String,
    sizeDp: Int,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null
) {
    val fallback = painterResource(R.drawable.small_icon)
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier = modifier.size(sizeDp.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        AsyncImage(
            model = avatarUrl,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            placeholder = fallback,
            fallback = fallback,
            error = fallback,
        )
    }
}

@Composable
private fun DeveloperSocials(
    uriHandler: androidx.compose.ui.platform.UriHandler
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        androidx.compose.material3.Button(shape = (if (com.metrolist.music.ui.theme.isGlassStyle) androidx.compose.foundation.shape.CircleShape else androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
            onClick = { uriHandler.openUri("https://metrolist.cc") },
            modifier = Modifier.weight(1f).height(48.dp)
        ) {
            Icon(painterResource(R.drawable.language), contentDescription = null)
        }
        androidx.compose.material3.Button(shape = (if (com.metrolist.music.ui.theme.isGlassStyle) androidx.compose.foundation.shape.CircleShape else androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
            onClick = { uriHandler.openUri("https://github.com/mostafaalagamy") },
            modifier = Modifier.weight(1f).height(48.dp)
        ) {
            Icon(painterResource(R.drawable.github), contentDescription = null)
        }
        androidx.compose.material3.Button(shape = (if (com.metrolist.music.ui.theme.isGlassStyle) androidx.compose.foundation.shape.CircleShape else androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
            onClick = { uriHandler.openUri("https://www.instagram.com/mostafaalagamy") },
            modifier = Modifier.weight(1f).height(48.dp)
        ) {
            Icon(painterResource(R.drawable.instagram), contentDescription = null)
        }
    }
}

/** App header: the brand mark, the name and the version on one flat block. */
@Composable
internal fun AboutHeaderCard() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .glassCard(com.metrolist.music.ui.component.glassShape(HorizonRadius.Lg, HorizonRadius.Lg))
            .padding(20.dp),
    ) {
        com.metrolist.music.ui.component.AuralysMark(
            modifier = Modifier.size(64.dp),
        )

        Spacer(Modifier.width(16.dp))

        Column {
            val metrolistName = stringResource(R.string.metrolist)
                .lowercase(Locale.getDefault())
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

            Text(
                text = metrolistName,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = listOfNotNull(BuildConfig.VERSION_NAME, BuildConfig.ARCHITECTURE, if (BuildConfig.DEBUG) "debug" else null)
                    .joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AboutScreen(
    navController: NavController,
) {
    val uriHandler = LocalUriHandler.current
    val playerConnection = LocalPlayerConnection.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val wannaPlayStr = stringResource(R.string.wanna_play_favorite_song)
    val yeahStr = stringResource(R.string.yeah)
    
    val windowInsets = LocalPlayerAwareWindowInsets.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(windowInsets.only(WindowInsetsSides.Horizontal))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                windowInsets.only(WindowInsetsSides.Top)
            )
        )

        Spacer(Modifier.height(16.dp))

        AboutHeaderCard()

        // asks the download page whether a newer version is listed (the same check that runs when the app opens)
        Spacer(Modifier.height(12.dp))
        val checkContext = androidx.compose.ui.platform.LocalContext.current
        var checking by remember { mutableStateOf(false) }
        var foundUpdate by remember { mutableStateOf<String?>(null) }
        val failedText = stringResource(R.string.web_update_failed)
        val latestText = stringResource(R.string.web_update_latest, BuildConfig.BASE_VERSION_NAME)
        Material3SettingsGroup(
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.update),
                    title = { Text(stringResource(if (checking) R.string.web_update_checking else R.string.web_update_check)) },
                    onClick = {
                        if (!checking) {
                            checking = true
                            coroutineScope.launch {
                                val latest = com.metrolist.music.utils.WebUpdate.latest()
                                checking = false
                                when {
                                    latest == null -> android.widget.Toast.makeText(checkContext, failedText, android.widget.Toast.LENGTH_LONG).show()
                                    com.metrolist.music.utils.WebUpdate.compare(latest, BuildConfig.BASE_VERSION_NAME) > 0 -> foundUpdate = latest
                                    else -> android.widget.Toast.makeText(checkContext, latestText, android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                ),
            ),
        )
        foundUpdate?.let { version ->
            com.metrolist.music.ui.component.WebUpdateDialog(version = version, onDismiss = { foundUpdate = null })
        }

        Spacer(Modifier.height(24.dp))

        // Developer header: live banner fading into the page, like a TikTok profile
        val fadeColor = MaterialTheme.colorScheme.surface
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            DevBannerVideo(Modifier.fillMaxSize())
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to androidx.compose.ui.graphics.Color.Transparent,
                                0.38f to androidx.compose.ui.graphics.Color.Transparent,
                                0.72f to fadeColor.copy(alpha = 0.85f),
                                0.92f to fadeColor,
                                1f to fadeColor,
                            ),
                        ),
                    ),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = leadDeveloper.name,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.credits_developer),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Image(
                    painter = painterResource(R.drawable.dapz_avatar),
                    contentDescription = leadDeveloper.name,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .size(104.dp)
                        .border(3.dp, fadeColor, CircleShape)
                        .padding(3.dp)
                        .clip(CircleShape),
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.dapz_social_title),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.telegram),
                    title = { Text("Telegram") },
                    trailingContent = { Text("@ryuzinetshh") },
                    onClick = { uriHandler.openUri("https://t.me/ryuzinetshh") },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.tiktok),
                    title = { Text("TikTok") },
                    trailingContent = { Text("@dappxml29") },
                    onClick = { uriHandler.openUri("https://www.tiktok.com/@dappxml29") },
                ),
            ),
        )

        Spacer(Modifier.height(20.dp))
        var showSupport by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        Button(
            onClick = { showSupport = true },
            shape = (if (com.metrolist.music.ui.theme.isGlassStyle) androidx.compose.foundation.shape.CircleShape else androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
            modifier = Modifier.fillMaxWidth().height(60.dp),
        ) {
            Icon(painterResource(R.drawable.favorite), contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.Start) {
                Text(stringResource(R.string.support_title), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.support_subtitle), style = MaterialTheme.typography.labelSmall)
            }
        }
        if (showSupport) com.metrolist.music.ui.component.SupportDialog(onDismiss = { showSupport = false })

        Spacer(Modifier.height(48.dp))
        com.metrolist.music.ui.utils.BarsBottomSpacer()
    }

    TopAppBar(
        title = { Text(stringResource(R.string.about)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back),
                    contentDescription = stringResource(R.string.cd_back),
                )
            }
        }
    )

    Box(Modifier.fillMaxSize()) {
        AuralysSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(windowInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
        )
    }
}


/** Muted, looping, cropped-to-fill video used as the developer banner. Never grabs audio focus. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun DevBannerVideo(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val player = remember {
        runCatching {
            androidx.media3.exoplayer.ExoPlayer.Builder(context)
                .setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, false)
                .build()
                .apply {
                    volume = 0f
                    repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                    setMediaItem(
                        androidx.media3.common.MediaItem.fromUri(
                            androidx.media3.datasource.RawResourceDataSource.buildRawResourceUri(R.raw.dev_banner),
                        ),
                    )
                    prepare()
                    playWhenReady = true
                }
        }.getOrNull()
    }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, player) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> player?.pause()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> player?.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player?.release()
        }
    }
    if (player != null) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                val tv = android.view.TextureView(ctx)
                var vw = 0f
                var vh = 0f
                fun applyCover() {
                    val w = tv.width.toFloat()
                    val h = tv.height.toFloat()
                    if (w == 0f || h == 0f || vw == 0f || vh == 0f) return
                    val s = maxOf(w / vw, h / vh)
                    val m = android.graphics.Matrix()
                    m.setScale(vw * s / w, vh * s / h, w / 2f, h / 2f)
                    tv.setTransform(m)
                }
                player.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        vw = videoSize.width * videoSize.pixelWidthHeightRatio
                        vh = videoSize.height.toFloat()
                        applyCover()
                    }
                })
                tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyCover() }
                player.setVideoTextureView(tv)
                tv
            },
            modifier = modifier,
        )
    }
}
