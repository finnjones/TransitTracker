package app.transittracker.ui.feature.follow

import app.transittracker.model.LatLng
import app.transittracker.model.StopCall
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.fake.FakeSetup
import app.transittracker.ui.gallery.SampleData
import app.transittracker.ui.platform.FakePlatformServices
import app.transittracker.ui.platform.PermissionState
import app.transittracker.ui.settings.UiSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
}
