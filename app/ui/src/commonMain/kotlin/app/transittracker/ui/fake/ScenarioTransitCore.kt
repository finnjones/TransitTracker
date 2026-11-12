package app.transittracker.ui.fake

import app.transittracker.model.DataStatus
import app.transittracker.model.Departure
import app.transittracker.model.DepartureStatus
import app.transittracker.model.Journey
import app.transittracker.model.Occupancy
import app.transittracker.model.SavedTrip
import app.transittracker.model.ServiceDate
import app.transittracker.model.ServiceDetail
import app.transittracker.model.ServiceRef
import app.transittracker.model.TransitCore
import app.transittracker.model.WallClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/** How live data looks in a [ScenarioTransitCore]. */
enum class LiveData { LIVE, STALE, OFFLINE }

/**
 * A data situation to show (previews, screenshots, tests): fresh / stale / offline live data,
 * a server message, a pack download or an out-of-date pack, no trips.
 */
data class CoreScenario(
    val live: LiveData = LiveData.LIVE,
    /** Replaces the fake's own status message ("Sample data"); null shows none. */
    val statusMessage: String? = null,
    val packDownloading: Boolean = false,
    val packOutOfDate: Boolean = false,
    val noTrips: Boolean = false,
    /** Age of the last live update shown in the header chip. */
    val liveAgeMs: Long = 12_000,
)

/**
 * Wraps a fake core so its data can be shown stale or offline, as the real core would present it
 * (stale: departures marked `stale` with their age; offline: timetable only, no live data). Every
 * other call goes to [base] unchanged (`by` delegation, so new `TransitCore` methods need nothing
 * here). Not a core implementation: it only re-labels the fake's output for previews and tests.
 */
class ScenarioTransitCore(
    private val base: TransitCore,
    private val clock: WallClock,
    scenario: CoreScenario = CoreScenario(),
) : TransitCore by base {
    val scenario = MutableStateFlow(scenario)

    override val trips: Flow<List<SavedTrip>> =
        combine(base.trips, this.scenario) { list, s -> if (s.noTrips) emptyList() else list }

    override val dataStatus: Flow<DataStatus> = combine(base.dataStatus, this.scenario) { d, s ->
        val now = clock.nowMillis()
        val packs = d.packs.map { p ->
            p.copy(
                downloading = s.packDownloading,
                validTo = if (s.packOutOfDate) "20260101" else p.validTo,
            )
        }
        d.copy(
            packs = packs,
            statusMessage = s.statusMessage,
            liveStale = s.live == LiveData.STALE,
            serverReachable = s.live != LiveData.OFFLINE,
            lastLiveUpdateMs = when (s.live) {
                LiveData.LIVE -> now - s.liveAgeMs
                LiveData.STALE -> now - STALE_AGE_MS
                LiveData.OFFLINE -> d.lastLiveUpdateMs?.let { now - 3_600_000 }
            },
        )
    }

    override fun upcoming(tripId: String, count: Int): Flow<List<Journey>> =
        combine(base.upcoming(tripId, count), scenario) { list, s -> list.map { j -> j.copy(legs = j.legs.map { relabel(it, s) }) } }

    override fun timetable(tripId: String, serviceDate: ServiceDate): Flow<List<Journey>> =
        combine(base.timetable(tripId, serviceDate), scenario) { list, s -> list.map { j -> j.copy(legs = j.legs.map { relabel(it, s) }) } }

    override fun service(ref: ServiceRef): Flow<ServiceDetail> =
        combine(base.service(ref), scenario) { d, s ->
            when (s.live) {
                LiveData.LIVE -> d
                LiveData.STALE -> if (d.status == DepartureStatus.TIMETABLED) d else d.copy(stale = true, dataAgeMs = STALE_AGE_MS)
                LiveData.OFFLINE -> {
                    val now = clock.nowMillis()
                    d.copy(
                        status = DepartureStatus.TIMETABLED, stale = false, dataAgeMs = null,
                        occupancy = Occupancy.UNKNOWN, carCount = null,
                        calls = d.calls.map { c ->
                            c.copy(
                                expectedArrivalMs = null, expectedDepartureMs = null, platformChanged = false,
                                passed = (c.scheduledDepartureMs ?: c.scheduledArrivalMs ?: Long.MAX_VALUE) <= now,
                            )
                        },
                    )
                }
            }
        }

    private fun relabel(d: Departure, s: CoreScenario): Departure = when (s.live) {
        LiveData.LIVE -> d
        LiveData.STALE -> if (d.status == DepartureStatus.TIMETABLED) d else d.copy(stale = true, dataAgeMs = STALE_AGE_MS)
        LiveData.OFFLINE -> d.copy(
            status = DepartureStatus.TIMETABLED,
            expectedDepartureMs = d.scheduledDepartureMs,
            expectedArrivalMs = d.scheduledArrivalMs,
            delaySeconds = null,
            platformChanged = false,
            occupancy = Occupancy.UNKNOWN,
            carCount = null,
            dataAgeMs = null,
            stale = false,
        )
    }

    companion object {
        const val STALE_AGE_MS = 3 * 60_000L
    }
}
