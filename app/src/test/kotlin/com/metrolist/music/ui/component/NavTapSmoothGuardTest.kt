package com.metrolist.music.ui.component

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source guards for the tab-tap smoothness work of 2026-11-18 (see NAVSMOOTH-WORKLOG.md). The page of a tapped tab is built in ONE long frame of
 * the main thread (measured: 100-250 ms on an APK that has not been compiled yet, 30-70 ms when it has), so the order of events and the work in
 * that frame are what the owner sees as stutter. These are written as text checks because the behaviour needs a running phone.
 */
class NavTapSmoothGuardTest {
    private fun source(path: String): String {
        val roots = listOf("src/main/kotlin", "app/src/main/kotlin")
        val root = roots.map { File(it) }.first { it.isDirectory }
        return File(root, path).readText()
    }

    @Test
    fun `a tap swells the bubble, builds the page while nothing moves, then glides`() {
        val tabs = source("com/metrolist/music/ui/component/backdrop/catalog/components/LiquidBottomTabs.kt")
        assertTrue("the bar tells the tabs which page is on screen", tabs.contains("routeIndex: (() -> Int)? = null"))
        val branch = tabs.indexOf("if (buildsPage) {")
        assertTrue("the tap branch exists", branch > 0)
        val press = tabs.indexOf("dampedDragAnimation.press()", branch)
        val select = tabs.indexOf("onTabSelected(index)", branch)
        val glide = tabs.indexOf("dampedDragAnimation.animateToValue(index.toFloat())", branch)
        assertTrue("swell, then build the page, then glide", press in (branch + 1) until select && select in (press + 1) until glide)
        // the glide must wait for the frame that builds the page: frames between onTabSelected and animateToValue
        val between = tabs.substring(select, glide)
        assertTrue("the glide starts after the page frame", Regex("""withFrameNanos \{ }""").findAll(between).count() >= 2)
        // a drag keeps its own order (the finger placed the puck), and so does every other user of the component
        assertTrue(tabs.contains("selectionFromDrag[0] = targetIndex != currentIndex"))
        assertTrue(tabs.contains("val buildsPage = routeIndex != null && !fromDrag && index != routeIndex()"))
    }

    @Test
    fun `only the navigation bar passes the route index`() {
        val nav = source("com/metrolist/music/ui/component/AppNavigation.kt")
        assertTrue(nav.contains("routeIndex = { selState.value }"))
        // the Search pill selector is a LiquidBottomTabs too: it changes state at once and must not wait for a page
        val search = source("com/metrolist/music/ui/component/SearchSourceSwitch.kt")
        assertFalse(search.contains("routeIndex"))
    }

    @Test
    fun `the main scope does not repeat work on every tab change`() {
        val main = source("com/metrolist/music/MainActivity.kt")
        // previousTab as a destructured pair was read in composition and written on every tab change: a second recomposition of the whole scope
        assertFalse(main.contains("val (previousTab, setPreviousTab)"))
        assertTrue(main.contains("previousTabState.value = it"))
        // a new Room flow per recomposition restarted the query on every tab tap
        assertFalse(main.contains("database.eventCount().collectAsStateWithLifecycle"))
        assertTrue(main.contains("remember(database) { database.eventCount() }"))
        // the Hilt factory (the whole ViewModel key set) was rebuilt on every recomposition of the main scope
        assertFalse("hiltViewModel() in the main scope", Regex("""val homeViewModel: HomeViewModel = hiltViewModel\(\)""").containsMatchIn(main))
        assertTrue(main.contains("ViewModelProvider(this@MainActivity)[HomeViewModel::class.java]"))
        // navigate(route) compiled a Regex per destination on every tap: tabs go by destination id
        assertFalse("tab clicks use the route matcher again", main.contains("navController.navigate(screen.route)"))
        assertTrue(main.contains("navController.navigateToTab(screen.route)"))
        assertTrue(main.contains("popUpTo(graph.startDestinationId) { saveState = true }"))
        assertTrue(main.contains("restoreState = true"))
    }
}
