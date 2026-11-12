package app.transittracker.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import app.transittracker.ui.feature.timetable.TimetableActions
import app.transittracker.ui.feature.timetable.TimetableContent
import app.transittracker.ui.feature.timetable.TimetableUiState
import app.transittracker.ui.loading.LoaderTiming
import app.transittracker.ui.theme.TtTheme
import kotlin.test.Test

/** The timetable's loader: nothing for a quick read, the board for a slow one, gone when the services arrive. */
@OptIn(ExperimentalTestApi::class)
class TimetableLoaderUiTest {
    private val waiting = TimetableUiState(
        loading = false, titleParts = listOf("Home", "Work"), date = "20261112", today = "20261112", rowsLoaded = false,
    )

    @Test
    fun loaderShowsOnlyAfterTheWaitHasLasted() = runComposeUiTest {
        var state by mutableStateOf(waiting)
        mainClock.autoAdvance = false
        setContent { TtTheme { TimetableContent(state, showEarlier = false, actions = TimetableActions()) } }
        mainClock.advanceTimeBy(LoaderTiming.SHOW_AFTER_MS - 100)
        onNodeWithContentDescription("Loading the timetable").assertDoesNotExist()
        mainClock.advanceTimeBy(200)
        onNodeWithContentDescription("Loading the timetable").assertExists()

        state = waiting.copy(rowsLoaded = true)
        mainClock.advanceTimeByFrame()
        onNodeWithContentDescription("Loading the timetable").assertDoesNotExist()
    }

    @Test
    fun aQuickReadNeverShowsTheLoader() = runComposeUiTest {
        var state by mutableStateOf(waiting)
        mainClock.autoAdvance = false
        setContent { TtTheme { TimetableContent(state, showEarlier = false, actions = TimetableActions()) } }
        mainClock.advanceTimeBy(100)
        state = waiting.copy(rowsLoaded = true)
        mainClock.advanceTimeBy(1_000)
        onNodeWithContentDescription("Loading the timetable").assertDoesNotExist()
    }
}
