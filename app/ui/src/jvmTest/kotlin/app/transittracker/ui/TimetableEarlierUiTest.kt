package app.transittracker.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.app.PagePreview
import app.transittracker.ui.fake.FakeSetup
import app.transittracker.ui.feature.timetable.TimetableActions
import app.transittracker.ui.feature.timetable.TimetableContent
import app.transittracker.ui.feature.timetable.TimetableStateHolder
import app.transittracker.ui.state.rememberStateHolder
import app.transittracker.ui.theme.TtTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** Opening the earlier services adds them above NOW and leaves NOW where it was, so you read on from now. */
@OptIn(ExperimentalTestApi::class)
class TimetableEarlierUiTest {
    @Test
    fun showingEarlierServicesKeepsTheNowMarkerInPlace() = runComposeUiTest {
        val deps = AppDeps.fake()
        var showEarlier by mutableStateOf(false)
        setContent {
            TtTheme { PagePreview(deps) {
                val holder = rememberStateHolder { scope ->
                    TimetableStateHolder(deps.core, deps.places, deps.clock, FakeSetup.TRIP_HOME_WORK, null, scope)
                }
                val state by holder.state.collectAsState()
                val actions = remember { TimetableActions(toggleEarlier = { showEarlier = !showEarlier }) }
                TimetableContent(state, showEarlier, actions, Modifier.size(400.dp, 800.dp))
            } }
        }
        val now = hasContentDescription("Services above have left", substring = true)
        waitUntil(timeoutMillis = 10_000) { onAllNodes(now).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        val before = onNode(now).getBoundsInRoot().top

        onNodeWithContentDescription("earlier", substring = true).performClick()
        waitForIdle()

        assertEquals(true, showEarlier)
        assertEquals(before, onNode(now).getBoundsInRoot().top)
    }
}

