/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.ui.component.AuralysBrandColors
import com.metrolist.music.ui.component.AuralysMark
import com.metrolist.music.ui.component.AuralysSplash
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.WelcomeScreen
import com.metrolist.music.ui.component.painterResource
import com.metrolist.music.ui.screens.RecapEntryCard
import com.metrolist.music.ui.screens.StreakBannerContent
import com.metrolist.music.ui.screens.YouLayout
import com.metrolist.music.ui.screens.settings.AboutHeaderCard
import com.metrolist.music.ui.screens.settings.SettingsHubBody
import com.metrolist.music.ui.screens.settings.settingsTiles
import com.metrolist.music.ui.theme.HorizonColors
import com.metrolist.music.ui.theme.HorizonDark
import com.metrolist.music.ui.theme.HorizonLight
import com.metrolist.music.ui.theme.MetrolistTheme
import com.metrolist.music.ui.theme.UiStyle
import com.metrolist.music.utils.ListeningStreak
import com.metrolist.music.utils.LyricsCardBackground
import com.metrolist.music.utils.LyricsCardRenderer
import com.metrolist.music.utils.LyricsCardStyle
import com.metrolist.music.utils.StreakState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The second gallery: the screens of the settings, You and brand sweep, built from the real composables with fake data
 * (strings in Indonesian). Output: build/design-gallery. Same limits as [DesignGalleryTest]: no blur, no lens, and
 * the screens that need a view model are shown through their extracted layout.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "in-w411dp-h860dp-xhdpi")
class DesignGalleryScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private fun save(name: String, bmp: android.graphics.Bitmap) {
        val dir = File("build/design-gallery").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(bmp.width > 100 && bmp.height > 100)
    }

    private fun shot(name: String, dark: Boolean, style: UiStyle = UiStyle.GLASS, content: @Composable () -> Unit) {
        rule.setContent {
            MetrolistTheme(darkTheme = dark, style = style) {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    CompositionLocalProvider(LocalPlayerAwareWindowInsets provides WindowInsets(0.dp, 24.dp, 0.dp, 0.dp)) { content() }
                }
            }
        }
        rule.waitForIdle()
        save(name, rule.onRoot().captureToImage().asAndroidBitmap())
    }

    /** For pages with an endless animation: the clock is stepped by hand. */
    private fun shotTimed(name: String, dark: Boolean, ms: Long, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MetrolistTheme(darkTheme = dark, style = UiStyle.GLASS) {
                androidx.compose.material3.Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        rule.mainClock.advanceTimeBy(ms)
        save(name, rule.onRoot().captureToImage().asAndroidBitmap())
    }

    // ------------------------------------------------------------------ pieces

    @Composable
    private fun FakeTopBar(title: String) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.arrow_back), null) }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
    }

    @Composable
    private fun FakeAccountGroup(modifier: Modifier = Modifier, loggedIn: Boolean = false) {
        Material3SettingsGroup(
            modifier = modifier,
            title = androidx.compose.ui.res.stringResource(R.string.ytm_section),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(if (loggedIn) R.drawable.person else R.drawable.login),
                    title = { Text(if (loggedIn) "Dava" else androidx.compose.ui.res.stringResource(R.string.ytm_login)) },
                    description = { Text(if (loggedIn) "dava@contoh.id" else androidx.compose.ui.res.stringResource(R.string.ytm_login_desc)) },
                    onClick = {},
                ),
            ),
        )
    }

    @Composable
    private fun HubPage(query: String) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            FakeTopBar("Pengaturan")
            Column(Modifier.padding(horizontal = 16.dp)) {
                SettingsHubBody(
                    query = query,
                    onQueryChange = {},
                    tiles = settingsTiles(hasAndroidAuto = true, hasDefaultLinks = true, updaterAvailable = true, newVersionName = "4.3.4"),
                    onOpen = {},
                    account = { FakeAccountGroup() },
                )
            }
        }
    }

    @Composable
    private fun YouPage() {
        Column(Modifier.fillMaxSize()) {
            FakeTopBar("Kamu")
            YouLayout(
                onOpen = {},
                streak = {
                    StreakBannerContent(
                        streak = ListeningStreak(current = 12, longest = 30, totalDays = 64, state = StreakState.ACTIVE, todayMinutes = 25),
                        onClick = {},
                    )
                },
                account = { m -> FakeAccountGroup(m, loggedIn = true) },
            )
        }
    }

    @Composable
    private fun SwatchRow(label: String, colors: List<Color>) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                colors.forEach { Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(it)) }
            }
        }
    }

    @Composable
    private fun BrandPage(tokens: HorizonColors) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("Merek", style = MaterialTheme.typography.headlineLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                AuralysMark(Modifier.size(112.dp))
                Column {
                    Text("Auralys", style = MaterialTheme.typography.headlineSmall)
                    Text("Tanda merek di halaman netral", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // the drawable the share cards use, on a dark card
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF15171C)).padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Image(androidx.compose.ui.res.painterResource(R.drawable.app_logo), null, Modifier.size(72.dp))
                    Text("Auralys", color = Color.White, style = MaterialTheme.typography.titleLarge)
                }
            }
            SwatchRow("Warna merek", listOf(AuralysBrandColors.Navy, AuralysBrandColors.Blue, AuralysBrandColors.Cyan, AuralysBrandColors.Foam))
            SwatchRow("Halaman", listOf(tokens.bg, tokens.surface1, tokens.surface2, tokens.surface3, tokens.ink))
            AboutHeaderCard()
        }
    }

    // ------------------------------------------------------------------ tests

    @Test fun hubLight() = shot("hub-light", false) { HubPage("") }
    @Test fun hubDark() = shot("hub-dark", true) { HubPage("") }
    @Test fun hubSearchLight() = shot("hub-search-light", false) { HubPage("tampil") }
    @Test fun hubDatarLight() = shot("hub-light-datar", false, UiStyle.EDITORIAL) { HubPage("") }

    @Test fun youLight() = shot("you-light", false) { YouPage() }
    @Test fun youDark() = shot("you-dark", true) { YouPage() }

    @Test fun brandLight() = shot("brand-light", false) { BrandPage(HorizonLight) }
    @Test fun brandDark() = shot("brand-dark", true) { BrandPage(HorizonDark) }

    @Test fun emptyLight() = shot("empty-light", false) { com.metrolist.music.ui.component.EmptyPlaceholder(icon = R.drawable.history, text = "Belum ada riwayat") }
    @Test fun emptyDark() = shot("empty-dark", true) { com.metrolist.music.ui.component.EmptyPlaceholder(icon = R.drawable.history, text = "Belum ada riwayat") }

    @Test fun recapEntryLight() = shot("recap-entry-light", false) { Column(Modifier.padding(top = 40.dp)) { RecapEntryCard(onClick = {}) } }
    @Test fun recapEntryDark() = shot("recap-entry-dark", true) { Column(Modifier.padding(top = 40.dp)) { RecapEntryCard(onClick = {}) } }

    @Test fun welcomeLight() = shotTimed("welcome-light", false, 1800) { WelcomeScreen(onLogin = {}, onSkip = {}) }
    @Test fun welcomeDark() = shotTimed("welcome-dark", true, 1800) { WelcomeScreen(onLogin = {}, onSkip = {}) }

    @Test fun splashLight() = shotTimed("splash-light", false, 1000) { AuralysSplash(onReady = {}, onFinished = {}) }
    @Test fun splashDark() = shotTimed("splash-dark", true, 1000) { AuralysSplash(onReady = {}, onFinished = {}) }

    /** The lyric card with no cover colour: it must stay grey. */
    @Test
    fun lyricCardWithoutCover() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val lyrics = "Dan aku tak akan pergi\nSelama kau masih di sini\nBiar waktu yang menjawab"
        for ((name, bg) in listOf("glass" to LyricsCardBackground.GLASS, "gradient" to LyricsCardBackground.GRADIENT, "solid" to LyricsCardBackground.SOLID)) {
            val bmp = runBlocking {
                LyricsCardRenderer.render(context, null, null, "Judul Lagu", "Nama Artis", lyrics, LyricsCardStyle(background = bg), 540)
            }
            save("lyric-card-$name", bmp)
        }
    }
}
