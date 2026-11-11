package app.transittracker.ui

import app.transittracker.model.DataStatus
import app.transittracker.model.Departure
import app.transittracker.model.DepartureStatus
import app.transittracker.model.Journey
import app.transittracker.model.Mode
import app.transittracker.model.PackStatus
import app.transittracker.model.ServiceRef
import app.transittracker.ui.feature.home.BannerKind
import app.transittracker.ui.feature.home.DataChip
import app.transittracker.ui.feature.home.LeaveByState
import app.transittracker.ui.feature.home.banners
import app.transittracker.ui.feature.home.dataChip
import app.transittracker.ui.feature.home.followingOf
import app.transittracker.ui.feature.home.headlineOf
import app.transittracker.ui.feature.home.leaveByState
import app.transittracker.ui.feature.home.walkTo
import app.transittracker.ui.format.shortStopName
import app.transittracker.ui.places.PlaceIcon
import app.transittracker.ui.places.PlaceStop
import app.transittracker.ui.places.SavedPlace
import app.transittracker.model.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Pure home rules: headline choice, leave-by states, chip and banners. */
class HomeRulesTest {
    private val min = 60_000L
    private val now = 1_000_000_000L

    private fun dep(id: String, atMin: Int, status: DepartureStatus = DepartureStatus.ON_TIME) = Departure(
        ref = ServiceRef(id, "20261111"), routeId = "T9", routeName = "T9", routeColor = "D11F2F", mode = Mode.TRAIN,
        headsign = "Gordon", originStopId = "S-P3", originPlatform = "3", platformChanged = false,
        scheduledDepartureMs = now + atMin * min, expectedDepartureMs = now + atMin * min,
        destinationStopId = "C-P3", scheduledArrivalMs = now + (atMin + 14) * min, expectedArrivalMs = now + (atMin + 14) * min,
        delaySeconds = 0, status = status,
    )

    @Test
    fun cancelledNeverHeadlinesButStaysVisible() {
        val a = Journey(listOf(dep("a", 2, DepartureStatus.CANCELLED)))
        val b = Journey(listOf(dep("b", 5, DepartureStatus.LATE)))
        val c = Journey(listOf(dep("c", 9)))
        val d = Journey(listOf(dep("d", 15)))
        val list = listOf(a, b, c, d)
        val h = headlineOf(list)
        assertSame(b, h)
        assertEquals(listOf(a, c), followingOf(list, h))
        assertNull(headlineOf(listOf(a)))
    }

    @Test
    fun leaveByHasThreeStates() {
        val departure = now + 10 * min
        // Walk 3: leave by now + 7 min.
        assertEquals(now + 7 * min to LeaveByState.PLENTY, leaveByState(departure, 3, now))
        assertEquals(LeaveByState.PLENTY, leaveByState(departure, 3, now + 5 * min - 1)!!.second)
        assertEquals(LeaveByState.NOW, leaveByState(departure, 3, now + 5 * min)!!.second)
        assertEquals(LeaveByState.NOW, leaveByState(departure, 3, now + 7 * min - 1)!!.second)
        assertEquals(LeaveByState.RUN, leaveByState(departure, 3, now + 7 * min)!!.second)
        assertEquals(LeaveByState.RUN, leaveByState(departure, 3, departure - 1)!!.second)
        assertNull(leaveByState(departure, 3, departure))
    }

    @Test
    fun walkMatchesTheStopOrTheTripsOriginAndSkipsSwitchedOffStops() {
        val place = SavedPlace(
            "p", "Home", PlaceIcon.HOME, "Albert Rd", LatLng(0.0, 0.0),
            listOf(PlaceStop("S", "Strathfield", 3, 250), PlaceStop("H", "Homebush", 14, 1100, enabled = false)),
        )
        assertEquals(3 to "Strathfield", walkTo(place, "S", emptySet()))
        assertEquals(3 to "Strathfield", walkTo(place, "S-P3", setOf("S")))
        assertNull(walkTo(place, "H-P1", setOf("H")), "switched off")
        assertNull(walkTo(place, "X-P1", setOf("X")), "not a stop of this place")
        val two = place.copy(stops = place.stops.map { it.copy(enabled = true) })
        assertNull(walkTo(two, "S-P3", setOf("S", "H")), "ambiguous: the trip leaves from both")
        assertEquals(14 to "Homebush", walkTo(two, "H", setOf("S", "H")))
    }

    @Test
    fun chipAndBanners() {
        val live = DataStatus(listOf(PackStatus("syd-rail", "1", "20261231", false)), now - 12_000, liveStale = false, serverReachable = true, joined = true, attribution = "")
        assertEquals(DataChip.Live(12_000), dataChip(live, now))
        assertEquals(emptyList(), banners(live, "20261111"))
        assertEquals(DataChip.Stale, dataChip(live.copy(liveStale = true), now))
        assertEquals(listOf(BannerKind.STALE), banners(live.copy(liveStale = true), "20261111").map { it.kind })
        assertEquals(DataChip.Offline, dataChip(live.copy(serverReachable = false, liveStale = true), now))
        assertEquals(listOf(BannerKind.OFFLINE), banners(live.copy(serverReachable = false, liveStale = true), "20261111").map { it.kind })
        assertEquals(listOf(BannerKind.OUT_OF_DATE), banners(live, "20270101").map { it.kind })
        assertEquals("Server says hi", banners(live.copy(statusMessage = "Server says hi"), "20261111").single().text)
        // Joined, but the first timetable has not arrived yet.
        val none = live.copy(packs = listOf(PackStatus("syd-rail", null, null, false, downloading = true)))
        assertEquals("Getting the timetable. Your trips appear when it is ready.", banners(none, "20261111").single().text)
        assertEquals(listOf(BannerKind.UPDATING), banners(live.copy(packs = emptyList()), "20261111").map { it.kind })
    }

    @Test
    fun shortNames() {
        assertEquals("Strathfield", shortStopName("Strathfield Station Platform 3"))
        assertEquals("Central", shortStopName("Central Station"))
        assertEquals("Circular Quay", shortStopName("Circular Quay Wharf 4"))
        assertEquals("Rhodes", shortStopName("Rhodes"))
    }
}
