package app.transittracker.ui

import app.transittracker.ui.feature.plan.TakeState
import app.transittracker.ui.feature.plan.JourneyStateHolder
import app.transittracker.model.LegPoint
import app.transittracker.model.LegKind
import app.transittracker.model.ItineraryLeg
import app.transittracker.model.Itinerary
import app.transittracker.ui.gallery.SampleData
import app.transittracker.model.WallClock
import app.transittracker.ui.feature.follow.FollowStage
import app.transittracker.ui.feature.follow.FollowMatch
import app.transittracker.core.FakeData
import app.transittracker.model.SavedTrip
import app.transittracker.model.TripLeg
import app.transittracker.model.wayIdFor
import app.transittracker.model.RouteOption
import app.transittracker.model.DepartureStatus
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.fake.CoreScenario
import app.transittracker.ui.fake.FakeSetup
import app.transittracker.ui.fake.LiveData
import app.transittracker.ui.feature.home.BannerKind
import app.transittracker.ui.feature.home.DataChip
import app.transittracker.ui.feature.home.HomeStateHolder
import app.transittracker.ui.feature.home.HomeUiState
import app.transittracker.ui.feature.home.LeaveByState
import app.transittracker.ui.settings.CardDensity
import app.transittracker.ui.settings.UiSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The home state holder against FakeTransitCore at Wed 11 Nov 07:38 (AppDeps.fake). */
class HomeStateHolderTest {
    private fun holder(deps: AppDeps, scope: CoroutineScope) =
        HomeStateHolder(deps.core, deps.places, deps.settings, deps.clock, scope)

    private suspend fun HomeStateHolder.ready(extra: (HomeUiState) -> Boolean = { true }): HomeUiState =
        state.first { !it.loading && extra(it) }

    @Test
    fun liveDataGivesFoldersHeadlinesAndALiveChip() = runTest {
        val h = holder(AppDeps.fake(), backgroundScope)
        val s = h.ready { st -> st.folders.flatMap { it.cards }.all { it.headline != null } }
        assertEquals(listOf("To work", "Home"), s.folders.map { it.name })
        assertTrue(s.chip is DataChip.Live)
        assertTrue(s.banners.isEmpty())
        val cards = s.folders.flatMap { it.cards }
        // The first trip of each folder is a full card, the rest compact.
        assertEquals(listOf(true, false, true, false), cards.map { it.full })
        for (c in cards) {
            val headline = assertNotNull(c.headline, c.trip.id)
            assertTrue(headline.legs.none { it.status == DepartureStatus.CANCELLED }, "cancelled headline on ${c.trip.id}")
            assertTrue(c.following.size <= 2)
        }
        // Home to Work is titled with the places and the stations beneath.
        val first = cards.first()
        assertEquals(listOf("Home", "Work"), first.titleParts)
        assertEquals("STRATHFIELD TO CENTRAL", first.stationsLine)
        // The multi-leg trip shows its change.
        val multi = cards.first { it.trip.id == FakeSetup.TRIP_TOWNHALL_TALLAWONG }
        assertEquals(1, multi.changes.size)
    }

    @Test
    fun lateServicesAreLiveAndCancelledOnesNeverHeadline() = runTest {
        val h = holder(AppDeps.fake(), backgroundScope)
        val s = h.ready { st -> st.folders.flatMap { it.cards }.all { it.headline != null } }
        val all = s.folders.flatMap { it.cards }.flatMap { listOfNotNull(it.headline) + it.following }.map { it.departure }
        val late = all.filter { it.status == DepartureStatus.LATE }
        assertTrue(late.isNotEmpty(), "the fake has late services around 07:38")
        assertTrue(late.all { it.expectedDepartureMs > it.scheduledDepartureMs && (it.delaySeconds ?: 0) >= 60 })
        for (card in s.folders.flatMap { it.cards }) {
            assertTrue(card.headline!!.legs.none { it.status == DepartureStatus.CANCELLED })
        }
    }

    @Test
    fun staleDataShowsTheChipBannerAndStaleDepartures() = runTest {
        val h = holder(AppDeps.fake(scenario = CoreScenario(live = LiveData.STALE)), backgroundScope)
        val s = h.ready { it.chip == DataChip.Stale && it.folders.isNotEmpty() && it.folders[0].cards[0].headline != null }
        assertEquals(listOf(BannerKind.STALE), s.banners.map { it.kind })
        val d = s.folders[0].cards[0].headline!!.departure
        assertTrue(d.stale)
    }

    @Test
    fun offlineShowsTheTimetable() = runTest {
        val h = holder(AppDeps.fake(scenario = CoreScenario(live = LiveData.OFFLINE)), backgroundScope)
        val s = h.ready { it.chip == DataChip.Offline && it.folders.isNotEmpty() && it.folders[0].cards[0].headline != null }
        assertEquals(listOf(BannerKind.OFFLINE), s.banners.map { it.kind })
        val all = s.folders.flatMap { it.cards }.mapNotNull { it.headline?.departure }
        assertTrue(all.all { it.status == DepartureStatus.TIMETABLED })
    }

    @Test
    fun noTripsIsTheEmptyState() = runTest {
        val h = holder(AppDeps.fake(trips = emptyList()), backgroundScope)
        val s = h.ready()
        assertTrue(s.isEmpty)
        assertTrue(s.folders.isEmpty())
    }

    @Test
    fun compactDensityMakesEveryCardCompact() = runTest {
        val h = holder(AppDeps.fake(settings = UiSettings(cardDensity = CardDensity.COMPACT)), backgroundScope)
        val s = h.ready()
        assertTrue(s.folders.flatMap { it.cards }.none { it.full })
    }

    @Test
    fun bannersFollowTheDataStatus() = runTest {
        val h = holder(
            AppDeps.fake(scenario = CoreScenario(statusMessage = "Maintenance tonight", packDownloading = true, packOutOfDate = true)),
            backgroundScope,
        )
        val s = h.ready { it.banners.size == 3 }
        assertEquals(listOf(BannerKind.MESSAGE, BannerKind.UPDATING, BannerKind.OUT_OF_DATE), s.banners.map { it.kind })
        assertEquals("Maintenance tonight", s.banners[0].text)
    }

    @Test
    fun aTripFromAPlaceHasLeaveByOthersDoNot() = runTest {
        val h = holder(AppDeps.fake(), backgroundScope)
        val s = h.ready { st -> st.folders.firstOrNull()?.cards?.firstOrNull()?.leaveBy != null }
        val homeWork = s.folders[0].cards[0]
        val lb = assertNotNull(homeWork.leaveBy)
        assertEquals(3, lb.walkMinutes)
        assertEquals(homeWork.headline!!.departure.expectedDepartureMs - 3 * 60_000, lb.leaveAtMs)
        assertEquals("Strathfield", lb.stopName)
        val expected = when {
            s.nowMs < lb.leaveAtMs - 120_000 -> LeaveByState.PLENTY
            s.nowMs < lb.leaveAtMs -> LeaveByState.NOW
            else -> LeaveByState.RUN
        }
        assertEquals(expected, lb.state)
        val other = s.folders[0].cards[1]
        assertNull(other.leaveBy)
        assertFalse(other.linkedToPlaces)
    }

    @Test
    fun onTheWayTheOtherWaysFromTheTrainCanBeSwitchedTo() = runTest {
        // Strathfield to Chatswood: stay on the T9, or off at Central for the T1.
        val direct = listOf(TripLeg(setOf(FakeData.STRATHFIELD), setOf(FakeData.CHATSWOOD), routeIds = setOf(FakeData.T9)))
        val viaCentral = listOf(
            TripLeg(setOf(FakeData.STRATHFIELD), setOf(FakeData.CENTRAL), routeIds = setOf(FakeData.T9)),
            TripLeg(setOf(FakeData.CENTRAL), setOf(FakeData.CHATSWOOD), routeIds = setOf(FakeData.T1)),
        )
        val route = SavedTrip("two-ways", "Strathfield", "Chatswood", direct)
            .withWays(listOf(RouteOption(wayIdFor(direct), direct), RouteOption(wayIdFor(viaCentral), viaCentral)))
        // 07:20: the 07:15 T9 is on its way to Central.
        val deps = AppDeps.fake(clock = WallClock { SampleData.NOW_MS - 18 * 60_000L }, trips = listOf(route))
        val follower = deps.follower
        // On the T9 that left last (it has not reached Central yet), following the direct way.
        val onDirect = deps.core.underway(route.id).first { l -> l.any { it.wayId == wayIdFor(direct) } }
            .filter { it.wayId == wayIdFor(direct) }.maxBy { it.departure.expectedDepartureMs }
        follower.foreground.value = false
        follower.showForPreview(FollowMatch(route.id, onDirect.departure.ref, 0, FollowStage.RIDING, 0.0, wayIdFor(direct)), null)
        val h = HomeStateHolder(deps.core, deps.places, deps.settings, deps.clock, backgroundScope, follower = follower)
        val card = h.ready { s -> s.folders.firstOrNull()?.cards?.firstOrNull()?.onTheWay?.alternatives?.isNotEmpty() == true }
            .folders.first().cards.first()
        val otw = card.onTheWay!!
        assertEquals(wayIdFor(direct), otw.journey.wayId)
        val alt = otw.alternatives.first { it.journey.wayId == wayIdFor(viaCentral) }
        assertTrue(alt.words.startsWith("CHANGE"))
        assertTrue(alt.words.endsWith("TO T1"))
        // SWITCH: the card follows the way via Central, on the same train.
        h.switchTo(alt.journey)
        val switched = h.ready { s -> s.folders.first().cards.first().onTheWay?.journey?.wayId == wayIdFor(viaCentral) }
            .folders.first().cards.first().onTheWay!!
        assertEquals(onDirect.departure.ref, switched.journey.departure.ref)
        assertEquals(2, switched.journey.legs.size)
        // ...and the direct way is now the other way offered.
        assertTrue(switched.alternatives.any { it.journey.wayId == wayIdFor(direct) && it.words.startsWith("STAY ON") })
        // ALL WAYS FROM THE NEXT STOP: the planner from the train's next stop to Chatswood, to look at only.
        val replan = h.replanFromHere()!!
        assertTrue(replan.browseOnly)
        assertEquals("Chatswood", replan.to.name)
    }

    @Test
    fun onTheWayAJourneyFoundFromTheNextStopCanBeTakenAndAddedToTheRoute() = runTest {
        val clock = WallClock { SampleData.NOW_MS - 18 * 60_000L }
        val direct = listOf(TripLeg(setOf(FakeData.STRATHFIELD), setOf(FakeData.CHATSWOOD), routeIds = setOf(FakeData.T9)))
        val viaCentral = listOf(
            TripLeg(setOf(FakeData.STRATHFIELD), setOf(FakeData.CENTRAL), routeIds = setOf(FakeData.T9)),
            TripLeg(setOf(FakeData.CENTRAL), setOf(FakeData.CHATSWOOD), routeIds = setOf(FakeData.T1)),
        )
        // The T1 from Central the planner would find (read from a route that has that way).
        val probe = AppDeps.fake(clock = clock, trips = listOf(SavedTrip("probe", "Strathfield", "Chatswood", viaCentral)))
        val via = probe.core.underway("probe").first { it.isNotEmpty() }.maxBy { it.departure.expectedDepartureMs }
        val t9 = via.legs[0]
        val t1 = via.legs[1]
        val t1Calls = probe.core.service(t1.ref).first().calls
        fun point(stop: String, ms: Long) = t1Calls.first { it.stopId == stop }.let { c -> LegPoint(c.stopName, c.position!!, stop, scheduledMs = ms) }
        val found = Itinerary(
            "found-1",
            listOf(
                ItineraryLeg(
                    LegKind.RIDE, point(t1.originStopId, t1.scheduledDepartureMs), point(t1.destinationStopId, t1.scheduledArrivalMs),
                    ((t1.scheduledArrivalMs - t1.scheduledDepartureMs) / 1000).toInt(), mode = t1.mode, routeName = t1.routeName,
                    routeId = t1.routeId, tripId = t1.ref.tripId, serviceDate = t1.ref.serviceDate,
                ),
            ),
        )

        // The route only has the direct way; the user is on the T9 that is on its way to Central.
        val route = SavedTrip("direct", "Strathfield", "Chatswood", direct)
        val deps = AppDeps.fake(clock = clock, trips = listOf(route))
        val follower = deps.follower
        follower.foreground.value = false
        follower.showForPreview(FollowMatch(route.id, t9.ref, 0, FollowStage.RIDING, 0.0, wayIdFor(direct)), null)
        val home = HomeStateHolder(deps.core, deps.places, deps.settings, deps.clock, backgroundScope, follower = follower)
        home.ready { st -> st.folders.firstOrNull()?.cards?.firstOrNull()?.onTheWay != null }

        // ALL WAYS FROM THE NEXT STOP: Central, with where the user is for TAKE THIS ONE.
        val options = home.replanFromHere()!!
        val takeFrom = options.takeFrom!!
        assertEquals(t9.ref, takeFrom.ride)
        assertEquals(t9.destinationStopId, takeFrom.nextStopId)

        // The journey found, opened from there: TAKE THIS ONE, and add it to the route.
        val journey = JourneyStateHolder(
            deps.core, deps.places, deps.clock, found, options.from, options.to, backgroundScope,
            takeFrom = takeFrom, follower = follower,
        )
        val ready = journey.state.first { it.take is TakeState.Ready }.take as TakeState.Ready
        assertTrue(ready.canAdd)
        assertEquals("Strathfield to Chatswood", ready.routeTitle)
        journey.take(addToRoute = true)

        // Home follows it: the T9 to Central, then the T1, as a one-off; staying on is the other way.
        val card = home.ready { st -> st.folders.first().cards.first().onTheWay?.oneOff == true }.folders.first().cards.first()
        val otw = card.onTheWay!!
        assertEquals(listOf(t9.ref, t1.ref), otw.journey.legs.map { it.ref })
        assertEquals(FollowStage.RIDING, otw.stage)
        assertTrue(otw.alternatives.any { it.journey.wayId == wayIdFor(direct) && it.words.startsWith("STAY ON") })
        // ...and the route has it as a way from Strathfield: the T9 to Central, then the T1.
        val saved = deps.core.trips.first { l -> l.first().ways.size == 2 }.first()
        val added = saved.ways[1].legs
        assertEquals(2, added.size)
        assertEquals(setOf(FakeData.STRATHFIELD), added[0].originStopIds)
        assertEquals(setOf(FakeData.CENTRAL), added[0].destinationStopIds)
        assertEquals(setOf(FakeData.T1), added[1].routeIds)
    }
}
