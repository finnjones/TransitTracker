package app.transittracker.ui

import app.transittracker.core.TransitCoreException
import app.transittracker.model.WatchDto
import app.transittracker.model.WatchKind
import app.transittracker.model.WatchRules
import app.transittracker.ui.feature.notify.DELAY_CHOICES
import app.transittracker.ui.feature.notify.PAUSE_FOREVER_MS
import app.transittracker.ui.feature.notify.WATCH_LIMIT
import app.transittracker.ui.feature.notify.WINDOW_END_MAX_SECS
import app.transittracker.ui.feature.notify.clockOfSecs
import app.transittracker.ui.feature.notify.daysWords
import app.transittracker.ui.feature.notify.defaultWindow
import app.transittracker.ui.feature.notify.failureWords
import app.transittracker.ui.feature.notify.isPaused
import app.transittracker.ui.feature.notify.pausedWords
import app.transittracker.ui.feature.notify.slotWords
import app.transittracker.ui.feature.notify.steppedEnd
import app.transittracker.ui.feature.notify.steppedLead
import app.transittracker.ui.feature.notify.steppedStart
import app.transittracker.ui.feature.notify.steppedThreshold
import app.transittracker.ui.feature.notify.tripStationNames
import app.transittracker.ui.feature.notify.watchSummary
import app.transittracker.ui.feature.notify.windowWords
import app.transittracker.model.SavedTrip
import app.transittracker.model.TripLeg
import app.transittracker.ui.gallery.SampleData
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure Notify rules: wording of a watch, limits of the steppers, failure sentences. */
class NotifyRulesTest {
    private val sydney = TimeZone.of("Australia/Sydney")
    private val now = SampleData.NOW_MS
    private fun hm(h: Int, m: Int) = h * 3600 + m * 60

    // ---------------------------------------------------------------- wording

    @Test
    fun daysAreWrittenAsRangesWhereTheyCan() {
        assertEquals("Mon–Fri", daysWords(listOf(1, 2, 3, 4, 5)))
        assertEquals("Every day", daysWords((1..7).toList()))
        assertEquals("Sat–Sun", daysWords(listOf(6, 7)))
        assertEquals("Mon, Wed, Fri", daysWords(listOf(5, 3, 1)))
        assertEquals("Mon–Wed, Sat", daysWords(listOf(1, 2, 3, 6)))
        assertEquals("Mon–Fri, Sun", daysWords(listOf(1, 2, 3, 4, 5, 7)))
        assertEquals("Mon, Tue, Sat", daysWords(listOf(1, 2, 6)))
        assertEquals("Wed", daysWords(listOf(3)))
        assertEquals("No days", daysWords(emptyList()))
        assertEquals("Mon–Fri", daysWords(listOf(1, 2, 3, 4, 5, 9, 0))) // junk is ignored
    }

    @Test
    fun timesAreTwoDigitClockWords() {
        assertEquals("07:15", clockOfSecs(hm(7, 15)))
        assertEquals("00:00", clockOfSecs(0))
        assertEquals("07:15–08:00", windowWords(hm(7, 15), hm(8, 0)))
        assertEquals("WATCH 3 OF 10", slotWords(3))
    }

    private fun commute(
        days: List<Int> = listOf(1, 2, 3, 4, 5),
        start: Int = hm(7, 15),
        end: Int = hm(8, 0),
        threshold: Int = 5,
    ) = WatchDto(
        "w", WatchKind.COMMUTE, "Morning", "S", "C", daysOfWeek = days, windowStartSecs = start, windowEndSecs = end,
        rules = WatchRules(delayThresholdMin = threshold),
    )

    @Test
    fun aCommuteWatchReadsAsOneLine() {
        val s = watchSummary(commute(), "Strathfield", "Central")
        assertEquals("Strathfield ▶ Central · Mon–Fri · 07:15–08:00 · late by 5 min", s.line)
        assertEquals(listOf("Strathfield", "Central"), s.title)
        assertEquals(listOf("Mon–Fri", "07:15–08:00", "late by 5 min"), s.details)
        assertEquals("Sat–Sun · 09:00–10:30 · late by 10 min", watchSummary(commute(listOf(6, 7), hm(9, 0), hm(10, 30), 10), "A", "B").details.joinToString(" · "))
    }

    @Test
    fun withoutStationNamesTheServersLabelStandsIn() {
        assertEquals("Morning · Mon–Fri · 07:15–08:00 · late by 5 min", watchSummary(commute(), null, null).line)
        assertEquals("Morning · Mon–Fri · 07:15–08:00 · late by 5 min", watchSummary(commute(), "Strathfield", null).line)
        assertEquals("Strathfield · Mon–Fri · 07:15–08:00 · late by 5 min", watchSummary(commute().copy(label = ""), "Strathfield", null).line)
        assertEquals("Watch · Mon–Fri · 07:15–08:00 · late by 5 min", watchSummary(commute().copy(label = ""), null, null).line)
    }

    @Test
    fun aOneOffWatchHasNoDaysOrWindow() {
        val w = WatchDto("o", WatchKind.SINGLE_SERVICE, "T9 07:42 Strathfield to Central", "S", "C", tripId = "t", serviceDate = "20261111")
        assertEquals("T9 07:42 Strathfield to Central · late by 5 min", watchSummary(w, "Strathfield", "Central").line)
        assertEquals("T9 07:42 Strathfield to Central · leaves 07:42 · late by 5 min", watchSummary(w, null, null, "07:42").line)
        val noLabel = w.copy(label = "")
        assertEquals("Strathfield to Central · late by 5 min", watchSummary(noLabel, "Strathfield", "Central").line)
    }

    @Test
    fun pausedWatches() {
        val w = commute()
        assertFalse(isPaused(w, now))
        assertNull(pausedWords(w, now, sydney))
        val forever = w.copy(pausedUntilMs = now + PAUSE_FOREVER_MS)
        assertTrue(isPaused(forever, now))
        assertEquals("PAUSED", pausedWords(forever, now, sydney))
        val holiday = w.copy(pausedUntilMs = now + 2 * 86_400_000L)
        assertEquals("PAUSED UNTIL FRI 13 NOV", pausedWords(holiday, now, sydney))
        // A pause that has run out no longer counts.
        assertFalse(isPaused(w.copy(pausedUntilMs = now - 1), now))
        assertNull(pausedWords(w.copy(pausedUntilMs = now - 1), now, sydney))
    }

    @Test
    fun stationNamesComeFromTheSavedTripsEnds() {
        val trip = SavedTrip("t", "Strathfield", "Central", listOf(TripLeg(setOf("S"), setOf("C"))))
        assertEquals(mapOf("S" to "Strathfield", "C" to "Central"), tripStationNames(listOf(trip)))
    }

    // ---------------------------------------------------------------- stepping limits

    @Test
    fun theWindowStartMovesInQuarterHoursAndStaysBeforeTheEnd() {
        assertEquals(hm(7, 30), steppedStart(hm(7, 15), hm(8, 0), 1))
        assertEquals(hm(7, 0), steppedStart(hm(7, 15), hm(8, 0), -1))
        assertNull(steppedStart(0, hm(8, 0), -1)) // not before midnight
        assertEquals(hm(7, 45), steppedStart(hm(7, 30), hm(8, 0), 1))
        assertNull(steppedStart(hm(7, 45), hm(8, 0), 1)) // would reach the end
        // Off the grid it snaps onto it.
        assertEquals(hm(7, 15), steppedStart(hm(7, 20), hm(8, 0), -1))
        assertEquals(hm(7, 30), steppedStart(hm(7, 20), hm(8, 0), 1))
    }

    @Test
    fun theWindowEndMovesInQuarterHoursAndStaysAfterTheStart() {
        assertEquals(hm(8, 15), steppedEnd(hm(7, 15), hm(8, 0), 1))
        assertEquals(hm(7, 45), steppedEnd(hm(7, 15), hm(8, 0), -1))
        assertNull(steppedEnd(hm(7, 15), hm(7, 30), -1)) // a window is at least one step
        assertEquals(WINDOW_END_MAX_SECS, steppedEnd(hm(7, 15), hm(23, 30), 1))
        assertNull(steppedEnd(hm(7, 15), WINDOW_END_MAX_SECS, 1)) // not past 23:45
        assertEquals(hm(15, 0), steppedEnd(hm(7, 15), hm(14, 50), 1))
    }

    @Test
    fun aNewWatchStartsAtTheNextQuarterHourForFortyFiveMinutes() {
        assertEquals(hm(7, 45) to hm(8, 30), defaultWindow(hm(7, 38)))
        assertEquals(hm(8, 0) to hm(8, 45), defaultWindow(hm(8, 0)))
        // Late at night it is held inside the day, and still a valid window.
        val (start, end) = defaultWindow(hm(23, 50))
        assertTrue(start < end && end <= WINDOW_END_MAX_SECS)
        assertEquals(hm(0, 0) to hm(0, 45), defaultWindow(0))
    }

    @Test
    fun leadTimeRunsFromFifteenToOneEightyMinutes() {
        assertEquals(60, steppedLead(45, 1))
        assertEquals(30, steppedLead(45, -1))
        assertNull(steppedLead(15, -1))
        assertNull(steppedLead(180, 1))
        assertEquals(165, steppedLead(180, -1))
        assertEquals(45, steppedLead(40, 1)) // off the grid snaps
        assertEquals(30, steppedLead(40, -1))
    }

    @Test
    fun theLateByThresholdWalksThroughTheChoices() {
        assertEquals(6, steppedThreshold(5, 1))
        assertEquals(4, steppedThreshold(5, -1))
        assertEquals(15, steppedThreshold(10, 1))
        assertEquals(10, steppedThreshold(15, -1))
        assertNull(steppedThreshold(1, -1))
        assertNull(steppedThreshold(DELAY_CHOICES.last(), 1))
        // A value the server holds that is not a choice still steps to its neighbours.
        assertEquals(15, steppedThreshold(12, 1))
        assertEquals(10, steppedThreshold(12, -1))
        assertNotNull(steppedThreshold(0, 1))
    }

    // ---------------------------------------------------------------- failures

    @Test
    fun aFailureIsAlwaysAPlainSentence() {
        val offline = failureWords(TransitCoreException("Could not save watch: timeout"))
        assertEquals("No connection, so the watch was not saved. Check your internet and try again.", offline)
        assertEquals(
            "You already have $WATCH_LIMIT watches. Delete one to add another.",
            failureWords(TransitCoreException("Could not save watch: Conflict", status = 409)),
        )
        assertEquals(
            "You already have $WATCH_LIMIT watches. Delete one to add another.",
            failureWords(TransitCoreException("Could not save watch: watch limit reached", status = 400)),
        )
        assertEquals(
            "The server did not recognise this phone. Check Settings and try again.",
            failureWords(TransitCoreException("Could not save watch: Forbidden", status = 403)),
        )
        assertEquals(
            "The server could not delete the watch. Try again in a moment.",
            failureWords(TransitCoreException("Could not delete watch: HTTP 500", status = 500), "delete"),
        )
        assertEquals("Could not change the watch. Try again in a moment.", failureWords(IllegalStateException("boom"), "change"))
        for (e in listOf(TransitCoreException("x"), TransitCoreException("x", 500), IllegalStateException("x"))) {
            val words = failureWords(e)
            assertTrue(words.endsWith("."), words)
            assertFalse(words.contains("500") || words.contains("HTTP"), words)
        }
    }
}
