package app.transittracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.transittracker.model.Mode
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.motion.PageTransition
import app.transittracker.ui.motion.TransitionController
import app.transittracker.ui.motion.TransitionHost
import app.transittracker.ui.nav.Navigator
import app.transittracker.ui.nav.Route
import app.transittracker.ui.theme.TtTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The real transition host: page state survives being covered; input reaches the new page by 300 ms. */
@OptIn(ExperimentalTestApi::class)
class NavHostUiTest {
    @Test
    fun pageStateIsKeptWhileCoveredAndInputArrivesBy300ms() = runComposeUiTest {
        val nav = Navigator()
        val tc = TransitionController()
        var opened = 0
        setContent {
            TtTheme {
                TransitionHost(nav, tc) { entry ->
                    Column(Modifier.fillMaxSize()) {
                        var count by rememberSaveable { mutableIntStateOf(0) }
                        PixelText("${entry.route.title}:$count", Modifier.testTag("label-${entry.id}"))
                        PixelText("ADD", Modifier.size(200.dp, 60.dp).testTag("add-${entry.id}").clickable { count++; if (entry.route is Route.Alerts) opened++ })
                    }
                }
            }
        }
        onNodeWithTag("add-0").performClick()
        onNodeWithTag("add-0").performClick()
        onNodeWithText("Trips:2").assertExists()

        // Push with a four-car train: about 640 ms of decoration, input after 300 ms.
        mainClock.autoAdvance = false
        runOnIdle { nav.push(Route.Alerts, PageTransition.vehiclePass(Mode.TRAIN, "D11F2F")) }
        mainClock.advanceTimeByFrame()
        assertNotNull(tc.running)
        mainClock.advanceTimeBy(320)
        assertNotNull(tc.running, "the train is still crossing")
        val newId = nav.current.id
        onNodeWithTag("add-$newId").performClick()
        mainClock.advanceTimeByFrame()
        assertEquals(1, opened, "the tap reached the new page")
        assertNull(tc.running, "and finished the transition")

        mainClock.autoAdvance = true
        runOnIdle { nav.back() }
        waitForIdle()
        onNodeWithText("Trips:2").assertExists()
    }
}
