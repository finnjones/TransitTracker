package app.transittracker.ui

import app.transittracker.model.Alert
import app.transittracker.model.AlertSeverity
import app.transittracker.model.SavedTrip
import app.transittracker.model.TimeRange
import app.transittracker.model.TripLeg
import app.transittracker.ui.feature.alerts.AlertsInputs
import app.transittracker.ui.feature.alerts.LineInfo
import app.transittracker.ui.alerts.TripFootprint
import app.transittracker.ui.feature.alerts.agoWords
import app.transittracker.ui.alerts.alertEnded
import app.transittracker.ui.alerts.alertTouchesTrip
import app.transittracker.ui.feature.alerts.alertWhen
import app.transittracker.ui.feature.alerts.buildAlertsState
import app.transittracker.ui.feature.alerts.knownStopNames
import app.transittracker.ui.feature.alerts.onYourTripWords
import app.transittracker.ui.alerts.severityRank
import app.transittracker.ui.alerts.tripFootprint
import app.transittracker.ui.gallery.SampleData
import app.transittracker.ui.settings.ClockFormat
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure Alerts rules: wording of when an alert applies, which trips it touches, the state. */
class AlertsRulesTest {
    private val sydney = TimeZone.of("Australia/Sydney")
    private val now = SampleData.NOW_MS // Wed 11 Nov 2026 07:38

    private fun at(month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        LocalDateTime(2026, month, day, hour, minute).toInstant(sydney).toEpochMilliseconds()

    private fun words(vararg p: TimeRange, format: ClockFormat = ClockFormat.H24) = alertWhen(p.toList(), now, sydney, format)

    // ---------------------------------------------------------------- when

    @Test
    fun noPeriodAndOpenPeriodsAreNow() {
        assertEquals("NOW", words())
        assertEquals("NOW", words(TimeRange(null, null)))
        assertEquals("NOW", words(TimeRange(at(11, 9), null)))
        assertEquals("NOW", words(TimeRange(null, null), TimeRange(at(11, 20), at(11, 21))))
    }

    @Test
    fun anAlertInForceSaysUntilWhen() {
        assertEquals("NOW UNTIL 17:00", words(TimeRange(at(11, 11, 5), at(11, 11, 17))))
        assertEquals("NOW UNTIL 5:00 PM", words(TimeRange(at(11, 11, 5), at(11, 11, 17)), format = ClockFormat.H12))
        assertEquals("NOW UNTIL SAT 14 NOV", words(TimeRange(at(11, 10), at(11, 14, 12))))
        // Ending at midnight means the day before.
        assertEquals("NOW UNTIL FRI 13 NOV", words(TimeRange(at(11, 10), at(11, 14))))
    }

    @Test
    fun aFutureAlertSaysWhichDays() {
        assertEquals("SAT 14 – SUN 15 NOV", words(TimeRange(at(11, 14), at(11, 16)))) // ends Mon 00:00
        assertEquals("FRI 13 – SUN 15 NOV", words(TimeRange(at(11, 13, 7, 38), at(11, 15, 7, 38))))
        assertEquals("FRI 13 NOV", words(TimeRange(at(11, 13, 9), at(11, 13, 17))))
        assertEquals("FRI 13 NOV", words(TimeRange(at(11, 13), at(11, 14)))) // one whole day
        assertEquals("FROM FRI 13 NOV", words(TimeRange(at(11, 13), null)))
        assertEquals("SAT 28 NOV – TUE 1 DEC", words(TimeRange(at(11, 28), at(12, 2))))
    }

    @Test
    fun ofSeveralPeriodsTheOneInForceElseTheNextToStart() {
        val later = TimeRange(at(11, 20), at(11, 21))
        val soon = TimeRange(at(11, 13), at(11, 14))
        assertEquals("FRI 13 NOV", words(later, soon))
        val current = TimeRange(at(11, 11, 6), at(11, 11, 9))
        assertEquals("NOW UNTIL 09:00", words(soon, current))
    }

    @Test
    fun anEndedAlertSaysSoAndIsNotShown() {
        val old = TimeRange(at(11, 1), at(11, 5))
        assertEquals("ENDED", words(old))
        assertTrue(alertEnded(Alert("a", "h", activePeriods = listOf(old)), now))
        assertFalse(alertEnded(Alert("a", "h"), now))
        assertFalse(alertEnded(Alert("a", "h", activePeriods = listOf(old, TimeRange(at(11, 13), null))), now))
    }

    @Test
    fun howOldAnAlertIs() {
        assertNull(agoWords(0, now))
        assertEquals("JUST NOW", agoWords(now - 20_000, now))
        assertEquals("5 MIN AGO", agoWords(now - 5 * 60_000, now))
        assertEquals("1 H AGO", agoWords(now - 61 * 60_000, now))
        assertEquals("2 D AGO", agoWords(now - 49 * 3_600_000L, now))
        assertEquals("JUST NOW", agoWords(now + 5000, now))
    }

    // ---------------------------------------------------------------- which trip

    private val trip = SavedTrip("t1", "Strathfield", "Central", listOf(TripLeg(setOf("S"), setOf("C"))))
    private val filtered = SavedTrip("t2", "Chatswood", "Tallawong", listOf(TripLeg(setOf("CH"), setOf("TW"), routeIds = setOf("M1"))))

    @Test
    fun anAlertTouchesATripByStopLineOrService() {
        val print = TripFootprint(stops = setOf("S", "C"), routes = setOf("T1"), services = setOf("svc-1"))
        assertTrue(alertTouchesTrip(Alert("a", "h", stopIds = listOf("X", "C")), print))
        assertTrue(alertTouchesTrip(Alert("a", "h", routeIds = listOf("T1")), print))
        assertTrue(alertTouchesTrip(Alert("a", "h", tripIds = listOf("svc-1")), print))
        assertFalse(alertTouchesTrip(Alert("a", "h", stopIds = listOf("X"), routeIds = listOf("T9"), tripIds = listOf("svc-2")), print))
        assertFalse(alertTouchesTrip(Alert("a", "h"), print))
    }

    @Test
    fun aTripFootprintHasItsStopsAndLineFilters() {
        val p = tripFootprint(filtered, emptyList())
        assertEquals(setOf("CH", "TW"), p.stops)
        assertEquals(setOf("M1"), p.routes)
        assertTrue(p.services.isEmpty())
    }

    @Test
    fun stationNamesComeFromTheTripsEnds() {
        val multi = SavedTrip(
            "t3", "Town Hall", "Tallawong",
            listOf(TripLeg(setOf("TH"), setOf("CH")), TripLeg(setOf("CH"), setOf("TW"))),
        )
        assertEquals(mapOf("TH" to "Town Hall", "TW" to "Tallawong"), knownStopNames(listOf(multi)))
    }

    // ---------------------------------------------------------------- the state

    private val stopAlert = Alert("stop", "Lift out", severity = AlertSeverity.INFO, stopIds = listOf("C"), updatedMs = now)
    private val lineAlert = Alert(
        "line", "Trackwork", severity = AlertSeverity.WARNING, routeIds = listOf("M1"),
        activePeriods = listOf(TimeRange(at(11, 13), at(11, 15))),
    )
    private val severe = Alert("severe", "No trains", severity = AlertSeverity.SEVERE, stopIds = listOf("CH"))
    private val elsewhere = Alert("far", "Far away", severity = AlertSeverity.SEVERE, stopIds = listOf("ZZ"))
    private val ended = Alert("old", "Old", stopIds = listOf("C"), activePeriods = listOf(TimeRange(at(11, 1), at(11, 2))))

    @Test
    fun loadingUntilAlertsAndTripsAreKnown() {
        assertTrue(buildAlertsState(AlertsInputs(null, listOf(trip)), now).loading)
        assertTrue(buildAlertsState(AlertsInputs(listOf(stopAlert), null), now).loading)
        assertFalse(buildAlertsState(AlertsInputs(emptyList(), emptyList()), now).loading)
    }

    @Test
    fun myTripsHoldsOnlyAlertsThatTouchATripAndAllLinesHoldsTheRest() {
        val s = buildAlertsState(AlertsInputs(listOf(elsewhere, stopAlert, lineAlert, ended), listOf(trip, filtered)), now)
        assertEquals(listOf("line", "stop"), s.mine.map { it.id })
        // My trips' alerts first, worst first; the others after; ended alerts are gone.
        assertEquals(listOf("line", "stop", "far"), s.all.map { it.id })
        assertEquals(listOf("t1"), s.mine.first { it.id == "stop" }.trips.map { it.id })
        assertEquals(listOf("t2"), s.mine.first { it.id == "line" }.trips.map { it.id })
        assertTrue(s.clearTrips.isEmpty())
        assertEquals(2, s.tripCount)
    }

    @Test
    fun aTripWithNothingActiveIsAllClear() {
        val s = buildAlertsState(AlertsInputs(listOf(severe, elsewhere), listOf(trip, filtered)), now)
        assertEquals(listOf("severe"), s.mine.map { it.id })
        assertEquals(listOf("t1"), s.clearTrips.map { it.id })
        // An alert that has ended does not count against a trip.
        val onlyEnded = buildAlertsState(AlertsInputs(listOf(ended), listOf(trip)), now)
        assertEquals(listOf("t1"), onlyEnded.clearTrips.map { it.id })
        assertTrue(onlyEnded.all.isEmpty())
    }

    @Test
    fun lineBadgesUseWhatIsKnownAndFallBackToTheId() {
        val s = buildAlertsState(
            AlertsInputs(listOf(lineAlert), listOf(filtered), lines = mapOf("M1" to LineInfo("M1", "M1", "168388", null))),
            now,
        )
        assertEquals(listOf(LineInfo("M1", "M1", "168388", null)), s.all.single().lines)
        val unknown = buildAlertsState(AlertsInputs(listOf(lineAlert), listOf(filtered)), now)
        assertEquals(listOf(LineInfo("M1", "M1")), unknown.all.single().lines)
    }

    @Test
    fun severityOrderAndTripWords() {
        assertEquals(
            listOf(AlertSeverity.SEVERE, AlertSeverity.WARNING, AlertSeverity.INFO, AlertSeverity.UNKNOWN),
            AlertSeverity.entries.sortedBy { severityRank(it) },
        )
        assertEquals("ON YOUR TRIP · STRATHFIELD TO CENTRAL", onYourTripWords(listOf(trip)))
        assertEquals("ON YOUR TRIPS · STRATHFIELD TO CENTRAL, CHATSWOOD TO TALLAWONG", onYourTripWords(listOf(trip, filtered)))
    }
}
