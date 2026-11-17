package app.transittracker.ui.feature.follow

import app.transittracker.model.LatLng
import app.transittracker.model.StopCall
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.fake.FakeSetup
import app.transittracker.ui.gallery.SampleData
import app.transittracker.ui.platform.FakePlatformServices
import app.transittracker.ui.platform.LocationFix
import app.transittracker.ui.platform.PermissionState
import app.transittracker.ui.platform.PlatformServices
import app.transittracker.ui.settings.UiSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The follower against FakeTransitCore at Wed 11 Nov 07:38: the Town Hall to Tallawong trip has
 * a T1 between Town Hall and Chatswood. A phone where that train should be follows it.
 */
class JourneyFollowerTest {
    private val trip = FakeSetup.TRIP_TOWNHALL_TALLAWONG

    private fun positionAt(calls: List<StopCall>, t: Long): LatLng? {
        val located = calls.filter { it.position != null }
        for (i in 0 until located.size - 1) {
            val dep = located[i].expectedDepartureMs ?: located[i].scheduledDepartureMs ?: continue
            val arr = located[i + 1].expectedArrivalMs ?: located[i + 1].scheduledArrivalMs ?: continue
            if (t in dep..arr) {
                val f = (t - dep).toDouble() / (arr - dep).coerceAtLeast(1)
                val p = located[i].position!!
                val q = located[i + 1].position!!
                return LatLng(p.lat + (q.lat - p.lat) * f, p.lon + (q.lon - p.lon) * f)
            }
        }
        return null
    }

    private fun follower(deps: AppDeps, platform: FakePlatformServices) =
        JourneyFollower(deps.core, platform, deps.settings, deps.clock, intervalMs = 1_000, idleIntervalMs = 1_000)

    @Test
    fun followsTheTrainThePhoneIsOn() = runTest {
        val platform = FakePlatformServices(location = null)
        val deps = AppDeps.fake(platform = platform)
        val journey = deps.core.underway(trip).first().first { SampleData.NOW_MS < it.legs[0].expectedArrivalMs }
        val train = journey.legs[0]
        val calls = riddenCalls(deps.core.service(train.ref).first().calls, train.originStopId, train.destinationStopId)
        platform.location.value = assertNotNull(positionAt(calls, SampleData.NOW_MS))

        val s = follower(deps, platform).state.first { it.status == FollowStatus.FOLLOWING }
        val m = assertNotNull(s.match)
        assertEquals(trip, m.tripId)
        assertEquals(train.ref, m.key)
        assertEquals(0, m.legIndex)
        assertEquals(FollowStage.RIDING, m.stage)
        assertNotNull(s.fix)
    }

    @Test
    fun farFromEveryJourneyFollowsNothing() = runTest {
        val platform = FakePlatformServices(location = LatLng(-34.20, 150.60))
        val deps = AppDeps.fake(platform = platform)
        val s = follower(deps, platform).state.first { it.fix != null }
        assertEquals(FollowStatus.LOOKING, s.status)
        assertNull(s.match)
    }

    @Test
    fun offOrWithoutLocationAsksForNothing() = runTest {
        val off = AppDeps.fake(settings = UiSettings(followJourney = false))
        val p1 = FakePlatformServices()
        assertEquals(FollowStatus.OFF, follower(off, p1).state.first().status)

        val denied = FakePlatformServices(locationPermission = PermissionState.DENIED)
        val deps = AppDeps.fake(platform = denied)
        assertEquals(FollowStatus.NO_LOCATION, follower(deps, denied).state.first().status)
    }

    @Test
    fun hiddenAppKeepsTheAnswerWithoutPolling() = runTest {
        val platform = FakePlatformServices()
        val deps = AppDeps.fake(platform = platform)
        val f = follower(deps, platform)
        f.foreground.value = false
        assertEquals(FollowStatus.IDLE, f.state.first().status)
    }

    @Test
    fun withNoFixTheUserIsAskedAndOneTapFollowsUntilTakenBack() = runTest {
        // A tunnel: location is allowed, but there is no fix.
        val platform = FakePlatformServices(location = null)
        val deps = AppDeps.fake(platform = platform)
        val f = follower(deps, platform)
        val asked = f.state.first { it.unsure.isNotEmpty() }
        assertEquals(FollowStatus.LOOKING, asked.status)
        assertNull(asked.match)
        val journey = assertNotNull(asked.unsure[trip]).first { SampleData.NOW_MS < it.legs[0].expectedArrivalMs }

        // ON BOARD: followed at once, by the user's word, and nothing more is asked.
        f.onBoard(trip, journey)
        val s = f.state.first { it.status == FollowStatus.FOLLOWING }
        assertTrue(s.told)
        assertEquals(journey.departure.ref, s.match?.key)
        assertEquals(0, s.match?.legIndex)
        assertEquals(FollowStage.RIDING, s.match?.stage)
        assertTrue(s.unsure.isEmpty())

        // NOT ON THIS: back to asking.
        f.notOnThis()
        val back = f.state.first { it.unsure.isNotEmpty() }
        assertEquals(FollowStatus.LOOKING, back.status)
        assertNull(back.match)
    }

    @Test
    fun whatTheUserSaidOutlastsFixesThatDoNotFit() = runTest {
        // An accurate fix far from every journey, which would drop a journey followed by location.
        val fake = FakePlatformServices()
        var asked = 0
        val sharp = object : PlatformServices by fake {
            // Each fix a step further on, so every poll is a new state.
            override suspend fun currentFix(nowMs: Long) = LocationFix(LatLng(-34.20, 150.60 + asked++ * 1e-5), nowMs, accuracyM = 20.0)
        }
        val deps = AppDeps.fake(platform = fake)
        val f = JourneyFollower(deps.core, sharp, deps.settings, deps.clock, intervalMs = 1_000, idleIntervalMs = 1_000)
        // An accurate fix can tell: nothing is asked.
        assertTrue(f.state.first { it.fix != null }.unsure.isEmpty())

        val journey = deps.core.underway(trip).first().first { SampleData.NOW_MS < it.legs[0].expectedArrivalMs }
        f.onBoard(trip, journey)
        val from = asked
        val s = f.state.first { it.fix != null && asked >= from + 5 }
        assertEquals(FollowStatus.FOLLOWING, s.status)
        assertEquals(journey.departure.ref, s.match?.key)
        assertTrue(s.told)
    }
}
