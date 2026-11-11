package app.transittracker.server.watch

import app.transittracker.model.StopDelta
import app.transittracker.model.Timetable
import app.transittracker.model.TripDelta
import app.transittracker.model.TripStatus
import app.transittracker.model.WatchDto
import app.transittracker.model.WatchKind
import app.transittracker.model.WatchRules
import app.transittracker.server.spi.ServerConfig
import app.transittracker.server.spi.TripChangeBatch
import kotlinx.coroutines.flow.MutableStateFlow

const val WED = "2026-11-11"
const val TODAY = "20261111"
const val DEVICE = "dev1"

/** A wired [WatchEngine] with fakes, the clock at [now]. */
class Harness(
    now: Long = sydney(WED, "07:30"),
    timetable: Timetable? = TestNetwork.timetable(),
    settings: WatchEngineSettings = WatchEngineSettings(),
) {
    val clock = MutableClock(now)
    val watches = InMemoryWatchRepository()
    val devices = InMemoryDeviceRepository().apply { add(DEVICE) }
    val log = InMemoryNotificationLog()
    val live = FakeLiveData()
    val push = RecordingPushSender()
    val timetableFlow = MutableStateFlow(timetable)
    val engine = WatchEngine(ServerConfig(), watches, devices, log, live, timetableFlow, push, clock, settings)

    fun at(date: String, time: String) {
        clock.now = sydney(date, time)
    }

    val bodies get() = push.sent.map { it.second.body }

    suspend fun feed(vararg deltas: TripDelta, stale: Boolean = false) =
        engine.onTripChanges(TripChangeBatch("sydneytrains", clock.now, stale, deltas.toList()))

    /** Live delta for an STR (seq 1, STR3) -> CEN (seq 2) trip. */
    fun delta(
        tripId: String,
        delayS: Int = 0,
        actualStopId: String? = null,
        status: TripStatus = TripStatus.SCHEDULED,
        date: String = TODAY,
        stale: Boolean = false,
        updatedMs: Long = clock.now,
        originSkipped: Boolean = false,
        includeOrigin: Boolean = true,
    ) = TripDelta(
        tripId = tripId,
        startDate = date,
        routeId = "T1",
        status = status,
        stops = if (status == TripStatus.CANCELLED) {
            emptyList()
        } else {
            listOfNotNull(
                StopDelta(1, "STR3", delayS, delayS, actualStopId, originSkipped).takeIf { includeOrigin },
                StopDelta(2, "CEN1", delayS, delayS),
            )
        },
        updatedMs = updatedMs,
        stale = stale,
    )
}

fun commute(
    id: String = "w1",
    from: Int = TestNetwork.secs("07:15"),
    to: Int = TestNetwork.secs("08:00"),
    days: List<Int> = listOf(1, 2, 3, 4, 5),
    routes: List<String> = emptyList(),
    lead: Int = 45,
    rules: WatchRules = WatchRules(),
    origin: String = "STR",
    destination: String = "CEN",
    label: String = "",
    pausedUntilMs: Long? = null,
) = WatchDto(
    id = id,
    kind = WatchKind.COMMUTE,
    label = label,
    originStationId = origin,
    destinationStationId = destination,
    routeIds = routes,
    daysOfWeek = days,
    windowStartSecs = from,
    windowEndSecs = to,
    leadMinutes = lead,
    rules = rules,
    pausedUntilMs = pausedUntilMs,
)

fun single(
    id: String = "s1",
    tripId: String? = "T1-0742",
    date: String? = TODAY,
    origin: String = "STR",
    destination: String = "CEN",
    lead: Int = 45,
) = WatchDto(
    id = id,
    kind = WatchKind.SINGLE_SERVICE,
    originStationId = origin,
    destinationStationId = destination,
    tripId = tripId,
    serviceDate = date,
    leadMinutes = lead,
)
