package com.metrolist.music.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.ui.component.AccountBlock
import com.metrolist.music.ui.component.AccountProvider
import com.metrolist.music.ui.component.AccountTileState
import com.metrolist.music.ui.component.LoginFrame
import com.metrolist.music.ui.component.LoginPhase
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.Switch
import com.metrolist.music.ui.component.painterResource
import com.metrolist.music.ui.screens.YouLayout
import com.metrolist.music.ui.screens.settings.SpotifyConnectContent
import com.metrolist.music.ui.theme.MetrolistTheme
import com.metrolist.music.ui.theme.UiStyle
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The account experience, built from the real composables with fake data (strings in Indonesian): the two sign-in tiles on the
 * You tab (signed out, signed in, one of each), the Spotify page (connect and connected), and the frame of the two sign-in
 * screens (opening, painted, offline). Light and dark, Kaca and Datar. Output: build/design-gallery, files "acct-*".
 * Same limits as [DesignGalleryTest]: no blur, no lens, no web view (a plain box stands in for the page).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "in-w411dp-h860dp-xhdpi")
class DesignGalleryAccountsTest {
    @get:Rule
    val rule = createComposeRule()

    private fun save(name: String, bmp: android.graphics.Bitmap) {
        val dir = File("build/design-gallery").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(bmp.width > 100 && bmp.height > 100)
    }

    private class Look(val tag: String, val dark: Boolean, val style: UiStyle)

    /** Every combination the app can be in: light and dark, Kaca and Datar. */
    private val looks =
        listOf(
            Look("light-kaca", false, UiStyle.GLASS),
            Look("dark-kaca", true, UiStyle.GLASS),
            Look("light-datar", false, UiStyle.EDITORIAL),
            Look("dark-datar", true, UiStyle.EDITORIAL),
        )

    /** One composition, shot once per look (the rule allows one setContent per test, so the look is state). */
    private fun allLooks(name: String, content: @Composable () -> Unit) {
        var current by mutableStateOf(looks.first())
        rule.setContent {
            val look = current
            MetrolistTheme(darkTheme = look.dark, style = look.style) {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    CompositionLocalProvider(LocalPlayerAwareWindowInsets provides WindowInsets(0.dp, 24.dp, 0.dp, 0.dp)) { content() }
                }
            }
        }
        for (look in looks) {
            rule.runOnIdle { current = look }
            rule.waitForIdle()
            save("$name-${look.tag}", rule.onRoot().captureToImage().asAndroidBitmap())
        }
    }

    // ------------------------------------------------------------------ the You tab

    private fun tiles(youtubeIn: Boolean, spotifyIn: Boolean) =
        listOf(
            AccountTileState(AccountProvider.YOUTUBE_MUSIC, youtubeIn, accountName = if (youtubeIn) "Dava" else null),
            AccountTileState(AccountProvider.SPOTIFY, spotifyIn, accountName = if (spotifyIn) "dava.musik" else null),
        )

    @Composable
    private fun youtubeRows(youtubeIn: Boolean): List<Material3SettingsItem> =
        if (!youtubeIn) {
            emptyList()
        } else {
            listOf(
                Material3SettingsItem(icon = painterResource(R.drawable.sync), title = { Text("Sinkronisasi otomatis dengan akun") }, trailingContent = { Switch(checked = true, onCheckedChange = {}) }),
                Material3SettingsItem(icon = painterResource(R.drawable.logout), title = { Text("Keluar") }, onClick = {}),
            )
        }

    @Composable
    private fun YouPage(youtubeIn: Boolean, spotifyIn: Boolean) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(8.dp)) {}
            YouLayout(
                onOpen = {},
                streak = {},
                account = { m -> AccountBlock(tiles(youtubeIn, spotifyIn), youtubeRows(youtubeIn), m) },
            )
        }
    }

    @Test
    fun youTabSignedOut() = allLooks("acct-you-out") { YouPage(youtubeIn = false, spotifyIn = false) }

    @Test
    fun youTabSignedIn() = allLooks("acct-you-in") { YouPage(youtubeIn = true, spotifyIn = true) }

    @Test
    fun youTabYoutubeOnly() = allLooks("acct-you-ytm-only") { YouPage(youtubeIn = true, spotifyIn = false) }

    // ------------------------------------------------------------------ the Spotify page

    @Composable
    private fun SpotifyPage(connected: Boolean) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.arrow_back), null) }
                Text("Spotify", style = MaterialTheme.typography.titleLarge)
            }
            SpotifyConnectContent(connected = connected, accountName = if (connected) "dava.musik" else null, avatarUrl = null, onConnect = {}, onDisconnect = {})
        }
    }

    @Test
    fun spotifyPageConnect() = allLooks("acct-spotify-connect") { SpotifyPage(connected = false) }

    @Test
    fun spotifyPageConnected() = allLooks("acct-spotify-connected") { SpotifyPage(connected = true) }

    // ------------------------------------------------------------------ the sign-in screens

    @Composable
    private fun FakePage(provider: AccountProvider) {
        // a plain stand-in for the web page: white, a heading and a few lines
        Column(Modifier.fillMaxSize().background(if (provider == AccountProvider.SPOTIFY) Color(0xFF121212) else Color.White).padding(24.dp)) {
            val ink = if (provider == AccountProvider.SPOTIFY) Color.White else Color(0xFF202124)
            Text(if (provider == AccountProvider.SPOTIFY) "Masuk ke Spotify" else "Masuk", color = ink, style = MaterialTheme.typography.headlineSmall)
            Text("Halaman resmi (diganti kotak ini di uji)", color = ink.copy(alpha = 0.7f), modifier = Modifier.padding(top = 8.dp))
            Box(Modifier.fillMaxWidth().padding(top = 24.dp).height(48.dp).background(ink.copy(alpha = 0.12f)))
            Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp).background(ink.copy(alpha = 0.12f)))
        }
    }

    @Composable
    private fun LoginPage(provider: AccountProvider, phase: LoginPhase, progress: Float) {
        val spotify = provider == AccountProvider.SPOTIFY
        LoginFrame(
            provider = provider,
            title = if (spotify) "Masuk ke Spotify" else "Masuk ke YouTube Music",
            privacy = if (spotify) "Masuk lewat halaman resmi Spotify. Auralys tidak melihat kata sandimu." else "Masuk lewat halaman resmi Google. Auralys tidak melihat kata sandimu.",
            phase = phase,
            progress = progress,
            loadingText = "Membuka halaman masuk",
            onBack = {},
            onRetry = {},
            backIcon = if (spotify) R.drawable.close else R.drawable.arrow_back,
            modifier = Modifier.padding(top = 24.dp),
        ) { FakePage(provider) }
    }

    @Test
    fun loginYoutubeLoading() = allLooks("acct-login-ytm-loading") { LoginPage(AccountProvider.YOUTUBE_MUSIC, LoginPhase.Loading, 0.4f) }

    @Test
    fun loginYoutubeReady() = allLooks("acct-login-ytm-ready") { LoginPage(AccountProvider.YOUTUBE_MUSIC, LoginPhase.Ready, 1f) }

    @Test
    fun loginYoutubeOffline() = allLooks("acct-login-ytm-offline") { LoginPage(AccountProvider.YOUTUBE_MUSIC, LoginPhase.Offline, 0.2f) }

    @Test
    fun loginSpotifyLoading() = allLooks("acct-login-spotify-loading") { LoginPage(AccountProvider.SPOTIFY, LoginPhase.Loading, 0.4f) }

    @Test
    fun loginSpotifyReady() = allLooks("acct-login-spotify-ready") { LoginPage(AccountProvider.SPOTIFY, LoginPhase.Ready, 1f) }

    @Test
    fun loginSpotifyOffline() = allLooks("acct-login-spotify-offline") { LoginPage(AccountProvider.SPOTIFY, LoginPhase.Offline, 0.2f) }
}
