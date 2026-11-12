package app.transittracker.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import app.transittracker.ui.motion.TransitionController
import app.transittracker.ui.motion.TransitionHost
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.nav.Navigator
import app.transittracker.ui.nav.Route
import app.transittracker.ui.nav.Tab
import app.transittracker.ui.nav.isTabRoot
import app.transittracker.ui.nav.rememberNavigator
import app.transittracker.ui.settings.UiSettings
import app.transittracker.ui.sprites.SpriteClock
import app.transittracker.ui.theme.TtTheme
import kotlinx.datetime.TimeZone

/**
 * The whole app (plan 4.1): theme from settings (plus the system's reduce-motion and battery-saver
 * state), the onboarding gate until the device has joined, the navigator with its page
 * transitions, and the bottom bar on the five tab roots with the Alerts count.
 *
 * The platform shell creates [deps] once, keeps a [controller] for back, visibility and
 * notification taps, and calls this as its only content. [spriteClock] / [timeZone] /
 * [driveTransitions] exist for screenshots and tests.
 *
 * Edge to edge: the screen colour fills the whole window (behind the status and navigation
 * bars) and the app itself is kept clear of the bars, display cutouts and the keyboard
 * (`WindowInsets.safeDrawing`; zero on the desktop, so screenshots are unchanged).
 * [onDarkTheme] tells the shell whether the current theme is dark, so it can colour the system
 * bar icons to match (light icons on Night, dark icons on Day).
 */
@Composable
fun TransitTrackerApp(
    deps: AppDeps,
    modifier: Modifier = Modifier,
    controller: AppController = rememberAppController(),
    navigator: Navigator = rememberNavigator(),
    transitions: TransitionController? = null,
    systemDark: Boolean = isSystemInDarkTheme(),
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    spriteClock: SpriteClock? = null,
    driveTransitions: Boolean = true,
    onDarkTheme: (Boolean) -> Unit = {},
) {
    val settings by remember(deps) { deps.settings.settings }.collectAsState(null)
    val systemReduce by deps.platform.systemReduceMotion.collectAsState()
    val batterySaver by deps.platform.batterySaver.collectAsState()
    val darkThemeCallback by rememberUpdatedState(onDarkTheme)
    TtTheme(settings ?: UiSettings(), systemDark, systemReduce || batterySaver, timeZone, spriteClock) {
        val dark = TtTheme.colors.screen.luminance() < 0.5f
        SideEffect { darkThemeCallback(dark) }
        Box(modifier.fillMaxSize().background(TtTheme.colors.screen)) {
            // Settings are local and quick; show nothing rather than the wrong theme for a frame.
            if (settings != null) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    CompositionLocalProvider(LocalAppDeps provides deps, LocalNavigator provides navigator) {
                        AppBody(deps, controller, navigator, transitions, driveTransitions)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppBody(
    deps: AppDeps,
    controller: AppController,
    navigator: Navigator,
    transitions: TransitionController?,
    drive: Boolean,
) {
    LaunchedEffect(deps) { deps.core.start() }
    DisposableEffect(controller, navigator, deps) {
        controller.navigator = navigator
        controller.core = deps.core
        controller.follower = deps.follower
        onDispose {
            controller.follower = null
            controller.navigator = null
            controller.core = null
        }
    }
    val reduce by rememberUpdatedState(TtTheme.motion.reduceMotion)
    val tc = transitions ?: remember { TransitionController { reduce } }

    val joined by remember(deps) { deps.core.joined }.collectAsState(null)
    if (joined == null) return
    if (joined == false && navigator.current.route !is Route.Onboarding) {
        SideEffect { navigator.startOnboarding() }
        return
    }
    val alertCount by remember(deps) { alertBadgeCount(deps.core, deps.clock) }.collectAsState(0)
    TransitionHost(navigator, tc, drive = drive) { entry -> AppPage(entry.route, alertCount) }
}

/**
 * One page as the app shows it: a tab root with the bottom bar under it, any other route full
 * screen. Needs [LocalAppDeps] and [LocalNavigator] (the app, or a preview, provides them).
 */
@Composable
fun AppPage(route: Route, alertCount: Int = 0) {
    if (route.isTabRoot) {
        val nav = LocalNavigator.current
        val tab = Tab.entries.first { it.root == route }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) { RouteContent(route) }
            BottomBar(tab, alertCount, onSelect = { nav.switchTab(it) })
        }
    } else {
        RouteContent(route)
    }
}

/** Provides [deps] and a navigator for previews of single pages (no shell, no transitions). */
@Composable
fun PagePreview(deps: AppDeps, navigator: Navigator = remember { Navigator() }, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppDeps provides deps, LocalNavigator provides navigator, content = content)
}
