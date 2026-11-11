package app.transittracker.ui.planning

import app.transittracker.model.Itinerary
import app.transittracker.model.ItineraryLeg
import app.transittracker.model.JourneyPlanner
import app.transittracker.model.LatLng
import app.transittracker.model.LegKind
import app.transittracker.model.LegPoint
import app.transittracker.model.Mode
import app.transittracker.model.Place
import app.transittracker.model.PlaceKind
import app.transittracker.model.PlacesResponse
import app.transittracker.model.PlacesResult
import app.transittracker.model.PlanRequest
import app.transittracker.model.PlanResponse
import app.transittracker.model.PlanResult
import app.transittracker.model.PlanTimeKind
import app.transittracker.model.WallClock

/**
 * A [JourneyPlanner] with canned answers for previews, screenshots and tests: whatever is asked,
 * it plans the Home (Albert Rd, Strathfield) to Bondi Beach example of the Options and Journey
 * mockups: three journeys (train, train, bus with walks and changes; train, train, bus; train,
 * train and a long walk), timed from the request time (or [clock]) as if it were 07:38.
 *
 * [answer] switches the answer: offline, planner unavailable, nothing found, or an error.
 */
class FakeJourneyPlanner(private val clock: WallClock) : JourneyPlanner {
    enum class Answer { NORMAL, OFFLINE, UNAVAILABLE, NOTHING_FOUND, FAILED }

    var answer: Answer = Answer.NORMAL

    /** Requests received, newest last (for tests). */
    val requests = mutableListOf<PlanRequest>()

    override suspend fun searchPlaces(query: String, near: LatLng?, limit: Int): PlacesResult = when (answer) {
        Answer.OFFLINE -> PlacesResult.Offline
        Answer.UNAVAILABLE -> PlacesResult.Unavailable
        Answer.FAILED -> PlacesResult.Failed("The planner had a problem")
        else -> {
            val q = query.trim().lowercase()
            val found = if (q.isEmpty()) emptyList() else PLACES.filter { p ->
                p.name.lowercase().contains(q) || p.detail.lowercase().contains(q)
            }
            PlacesResult.Found(found.take(limit))
        }
    }

    override suspend fun plan(request: PlanRequest): PlanResult {
        requests += request
        return when (answer) {
            Answer.OFFLINE -> PlanResult.Offline
            Answer.UNAVAILABLE -> PlanResult.Unavailable
            Answer.FAILED -> PlanResult.Failed("The planner had a problem")
            Answer.NOTHING_FOUND -> PlanResult.Found(PlanResponse(clock.nowMillis(), emptyList(), "fake-1", LIVE_MODES))
            Answer.NORMAL -> {
                val t = request.timeMs ?: clock.nowMillis()
                // "07:38" of the sample is the request time rounded down to the minute; for
                // ARRIVE_BY, the first journey arrives at the requested time.
                val base = (t / MIN) * MIN
                val start = if (request.timeKind == PlanTimeKind.ARRIVE_BY) base - 55 * MIN else base
                PlanResult.Found(PlanResponse(clock.nowMillis(), itineraries(start).take(request.maxItineraries), "fake-1", LIVE_MODES))
            }
        }
    }

    companion object {
        private const val MIN = 60_000L
        val LIVE_MODES = listOf(Mode.TRAIN, Mode.METRO)

        val HOME = LatLng(-33.8745, 151.0890)
        val STRATHFIELD = LatLng(-33.8718, 151.0943)
        val CENTRAL = LatLng(-33.8840, 151.2062)
        val BONDI_JUNCTION = LatLng(-33.8914, 151.2478)
        val BONDI_BEACH = LatLng(-33.8915, 151.2767)
        val CAMPBELL_PDE = LatLng(-33.8910, 151.2780)

        /** Places the fake search knows. */
        val PLACES: List<Place> = listOf(
            Place("fake:bondi-beach", PlaceKind.LOCALITY, "Bondi Beach", "Sydney", BONDI_BEACH),
            Place("fake:campbell-pde", PlaceKind.ADDRESS, "Campbell Pde", "Bondi Beach", CAMPBELL_PDE),
            Place("fake:albert-rd", PlaceKind.ADDRESS, "Albert Rd", "Strathfield", HOME),
            Place("fake:strathfield", PlaceKind.STOP, "Strathfield Station", "Strathfield", STRATHFIELD, "213510", listOf(Mode.TRAIN)),
            Place("fake:central", PlaceKind.STOP, "Central Station", "Haymarket", CENTRAL, "200060", listOf(Mode.TRAIN)),
            Place("fake:bondi-junction", PlaceKind.STOP, "Bondi Junction Station", "Bondi Junction", BONDI_JUNCTION, null, listOf(Mode.TRAIN, Mode.BUS)),
            Place("fake:chatswood", PlaceKind.STOP, "Chatswood Station", "Chatswood", LatLng(-33.7969, 151.1803), "206710", listOf(Mode.TRAIN, Mode.METRO)),
            Place("fake:pitt-st", PlaceKind.ADDRESS, "Pitt St", "Haymarket", LatLng(-33.8810, 151.2065)),
        )

        private fun point(name: String, pos: LatLng, at: Long, expected: Long? = null, platform: String? = null) =
            LegPoint(name, pos, null, platform, at, expected)

        private fun walk(from: LegPoint, to: LegPoint, minutes: Int, meters: Int) =
            ItineraryLeg(LegKind.WALK, from, to, minutes * 60, meters)

        private fun ride(
            from: LegPoint, to: LegPoint, mode: Mode, line: String, headsign: String,
            color: String, stops: Int, realtime: Boolean,
        ) = ItineraryLeg(
            LegKind.RIDE, from, to,
            durationS = (((to.expectedMs ?: to.scheduledMs) - (from.expectedMs ?: from.scheduledMs)) / 1000).toInt(),
            mode = mode, routeName = line, headsign = headsign, routeColor = color, stopCount = stops, realtime = realtime,
        )

        /** The three sample journeys, with 07:38 at [t0]. */
        fun itineraries(t0: Long): List<Itinerary> {
            fun at(min: Int) = t0 + min * MIN
            val train = Mode.TRAIN
            val bus = Mode.BUS
            // 1. 07:39 - 08:33: walk, T9 (4 min late), walk, T4, walk, 333 (timetable), walk.
            val home1 = point("Home", HOME, at(1))
            val strath1 = point("Strathfield", STRATHFIELD, at(4), platform = "3")
            val t9From = point("Strathfield", STRATHFIELD, at(0), at(4), "3")
            val t9To = point("Central", CENTRAL, at(14), at(18), "16")
            val t4From = point("Central", CENTRAL, at(25), at(25), "24")
            val t4To = point("Bondi Junction", BONDI_JUNCTION, at(36), at(36))
            val busFrom = point("Bondi Junction", BONDI_JUNCTION, at(42), platform = "Stand B")
            val busTo = point("Bondi Beach", BONDI_BEACH, at(53))
            val one = Itinerary(
                "fake-1-${t0 / MIN}",
                listOf(
                    walk(home1, strath1, 3, 250),
                    ride(t9From, t9To, train, "T9", "Gordon", "D11F2F", 2, realtime = true),
                    walk(point("Central", CENTRAL, at(18), platform = "16"), point("Central", CENTRAL, at(22), platform = "24"), 4, 280),
                    ride(t4From, t4To, train, "T4", "Bondi Junction", "005AA3", 5, realtime = true),
                    walk(point("Bondi Junction", BONDI_JUNCTION, at(36)), point("Bondi Junction", BONDI_JUNCTION, at(39), platform = "Stand B"), 3, 200),
                    ride(busFrom, busTo, bus, "333", "North Bondi", "00B5EF", 6, realtime = false),
                    walk(point("Bondi Beach", BONDI_BEACH, at(53)), point("Campbell Pde", CAMPBELL_PDE, at(55)), 2, 150),
                ),
            )
            // 2. 07:44 - 08:40: walk, T1, walk, T4, walk, 380, walk.
            val two = Itinerary(
                "fake-2-${t0 / MIN}",
                listOf(
                    walk(point("Home", HOME, at(6)), point("Strathfield", STRATHFIELD, at(9), platform = "2"), 3, 250),
                    ride(point("Strathfield", STRATHFIELD, at(9), at(9), "2"), point("Central", CENTRAL, at(22), at(22), "18"), train, "T1", "Berowra", "F99D1C", 2, true),
                    walk(point("Central", CENTRAL, at(22), platform = "18"), point("Central", CENTRAL, at(25), platform = "24"), 3, 220),
                    ride(point("Central", CENTRAL, at(28), at(28), "24"), point("Bondi Junction", BONDI_JUNCTION, at(39), at(39)), train, "T4", "Bondi Junction", "005AA3", 5, true),
                    walk(point("Bondi Junction", BONDI_JUNCTION, at(39)), point("Bondi Junction", BONDI_JUNCTION, at(42), platform = "Stand D"), 3, 200),
                    ride(point("Bondi Junction", BONDI_JUNCTION, at(44), platform = "Stand D"), point("Bondi Beach", BONDI_BEACH, at(60)), bus, "380", "North Bondi", "00B5EF", 8, false),
                    walk(point("Bondi Beach", BONDI_BEACH, at(60)), point("Campbell Pde", CAMPBELL_PDE, at(62)), 2, 150),
                ),
            )
            // 3. 07:39 - 08:45: walk, T9, walk, T4, then a 31 minute walk.
            val three = Itinerary(
                "fake-3-${t0 / MIN}",
                listOf(
                    walk(point("Home", HOME, at(1)), point("Strathfield", STRATHFIELD, at(4), platform = "3"), 3, 250),
                    ride(point("Strathfield", STRATHFIELD, at(0), at(4), "3"), point("Central", CENTRAL, at(14), at(18), "16"), train, "T9", "Gordon", "D11F2F", 2, true),
                    walk(point("Central", CENTRAL, at(18), platform = "16"), point("Central", CENTRAL, at(22), platform = "24"), 4, 280),
                    ride(point("Central", CENTRAL, at(25), at(25), "24"), point("Bondi Junction", BONDI_JUNCTION, at(36), at(36)), train, "T4", "Bondi Junction", "005AA3", 5, true),
                    walk(point("Bondi Junction", BONDI_JUNCTION, at(36)), point("Campbell Pde", CAMPBELL_PDE, at(67)), 31, 2400),
                ),
            )
            return listOf(one, two, three)
        }

        /** A place search answer, for screens that want one without a planner. */
        fun placesResponse(nowMs: Long) = PlacesResponse(nowMs, PLACES)
    }
}
