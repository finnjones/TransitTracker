package app.transittracker.ui.feature.home

import app.transittracker.model.DataStatus
import app.transittracker.model.DepartureStatus
import app.transittracker.model.Journey
import app.transittracker.model.SavedTrip
import app.transittracker.model.ServiceDate
import app.transittracker.model.StopId
import app.transittracker.ui.places.SavedPlace
import app.transittracker.ui.places.TripPlaces
import app.transittracker.ui.settings.CardDensity

/** Everything the home screen draws (plan 5.2). Built by [buildHomeState]; times are epoch ms. */
data class HomeUiState(
    /** True until trips have been read once (Home shows nothing rather than a spinner, plan 6). */
    val loading: Boolean = true,
    val nowMs: Long = 0,
    val chip: DataChip? = null,
    val banners: List<HomeBanner> = emptyList(),
    val folders: List<HomeFolder> = emptyList(),
) {
    /** No saved trips: the empty platform and ADD YOUR FIRST TRIP. */
    val isEmpty: Boolean get() = !loading && folders.isEmpty()
}

/** The header chip (plan 2.7). */
sealed interface DataChip {
    /** Live and fresh; [ageMs] since the last update (null when unknown). */
    data class Live(val ageMs: Long?) : DataChip
    data object Stale : DataChip
    data object Offline : DataChip
}

enum class BannerKind { STALE, OFFLINE, MESSAGE, UPDATING, OUT_OF_DATE }

/** A banner under the header (plan 2.7, data-level states). */
data class HomeBanner(val kind: BannerKind, val text: String)

/** One folder ("TO WORK"); [name] null for trips without a folder (no heading). */
data class HomeFolder(val name: String?, val cards: List<TripCard>)

enum class LeaveByState {
    /** LEAVE BY 07:39, the walker stands. */
    PLENTY,
    /** Within two minutes of leave-by: LEAVE NOW, the walker walks. */
    NOW,
    /** Past leave-by, the service not yet gone: RUN, the walker runs. */
    RUN,
}

/** Leave-by for a trip from a saved place (plan 5.7): departure minus the walk to its stop. */
data class LeaveBy(val leaveAtMs: Long, val state: LeaveByState, val walkMinutes: Int, val stopName: String)

/** A change between rides of a multi-leg trip: "change at Chatswood · 6 min". */
data class ChangeSummary(val stationName: String?, val waitMinutes: Int)

/**
 * One saved trip's card. [full]: the first trip of each folder (headline, strip, two following
 * services) unless the density setting is compact; otherwise one line.
 */
data class TripCard(
    val trip: SavedTrip,
    val full: Boolean,
    /** "Home" ▶ "Work", or the stations; one part when the trip has a custom name. */
    val titleParts: List<String>,
    /** "STRATHFIELD TO CENTRAL" under a title made of place names or a custom name. */
    val stationsLine: String?,
    /** The next running service; never a cancelled one (plan 2.7). */
    val headline: Journey?,
    /** The next two other services in time order (cancelled ones included, struck through). */
    val following: List<Journey>,
    /** Changes of the headline journey (multi-leg trips). */
    val changes: List<ChangeSummary>,
    /** For trips from a saved place; null shows seats instead. */
    val leaveBy: LeaveBy?,
    val linkedToPlaces: Boolean,
    /** When nothing runs any more today: the first service of the next day (null if unknown). */
    val noMoreServices: NoMoreServices?,
)

data class NoMoreServices(val firstNextDayMs: Long?)

// ---------------------------------------------------------------- rules

/** The headline: the first journey with no cancelled ride (plan 2.7: cancelled never headlines). */
fun headlineOf(journeys: List<Journey>): Journey? =
    journeys.firstOrNull { j -> j.legs.none { it.status == DepartureStatus.CANCELLED } }

/** Up to [count] journeys other than [headline], in time order. */
fun followingOf(journeys: List<Journey>, headline: Journey?, count: Int = 2): List<Journey> =
    journeys.filter { it !== headline }.sortedBy { it.departure.expectedDepartureMs }.take(count)

/** Leave-by window: LEAVE NOW from two minutes before the leave-by time (plan 5.7). */
const val LEAVE_NOW_WINDOW_MS = 2 * 60_000L

/**
 * Leave-by for a service departing at [departureMs] (expected) with a [walkMinutes] walk to its
 * stop. Null once the service has gone.
 */
fun leaveByState(departureMs: Long, walkMinutes: Int, nowMs: Long): Pair<Long, LeaveByState>? {
    if (departureMs <= nowMs) return null
    val leaveAt = departureMs - walkMinutes * 60_000L
    val state = when {
        nowMs < leaveAt - LEAVE_NOW_WINDOW_MS -> LeaveByState.PLENTY
        nowMs < leaveAt -> LeaveByState.NOW
        else -> LeaveByState.RUN
    }
    return leaveAt to state
}

/**
 * The walk from [place] to the stop a service leaves from ([stopId], usually a platform): the
 * place's stop with that id, else the one enabled place stop among the trip's own origin stops
 * ([tripOrigins], `TripLeg.originStopIds` of the first leg). Switched-off stops never count;
 * null when it cannot be told (the card then shows seats instead).
 */
fun walkTo(place: SavedPlace, stopId: StopId, tripOrigins: Set<StopId>): Pair<Int, String>? {
    val enabled = place.enabledStops
    val s = enabled.firstOrNull { it.stopId == stopId }
        ?: enabled.filter { it.stopId in tripOrigins }.singleOrNull()
        ?: return null
    return s.walkMinutes to s.name
}

/** Header chip from the core's data status (plan 2.7). */
fun dataChip(status: DataStatus?, nowMs: Long): DataChip? = when {
    status == null -> null
    !status.serverReachable -> DataChip.Offline
    status.liveStale -> DataChip.Stale
    else -> DataChip.Live(status.lastLiveUpdateMs?.let { (nowMs - it).coerceAtLeast(0) })
}

/** Banners from the data status (plan 2.7), most important first. [today] is `YYYYMMDD`. */
fun banners(status: DataStatus?, today: ServiceDate): List<HomeBanner> {
    if (status == null) return emptyList()
    return buildList {
        if (!status.serverReachable) add(HomeBanner(BannerKind.OFFLINE, "No live data. Showing the timetable."))
        else if (status.liveStale) add(HomeBanner(BannerKind.STALE, "Live data is delayed. Showing the last update."))
        status.statusMessage?.takeIf { it.isNotBlank() }?.let { add(HomeBanner(BannerKind.MESSAGE, it)) }
        // Joined, but the first timetable has not arrived yet (the app was closed during onboarding's download).
        if (status.joined && status.packs.none { it.version != null }) {
            val problem = status.timetableProblem?.takeIf { status.serverReachable && status.packs.none { it.downloading } }
            add(
                if (problem != null) HomeBanner(BannerKind.MESSAGE, "No timetable yet. $problem")
                else HomeBanner(BannerKind.UPDATING, "Getting the timetable. Your trips appear when it is ready."),
            )
        } else if (status.packs.any { it.downloading }) {
            add(HomeBanner(BannerKind.UPDATING, "Updating timetable…"))
        }
        if (status.packs.any { p -> p.validTo != null && p.validTo!! < today }) {
            add(HomeBanner(BannerKind.OUT_OF_DATE, "Timetable is out of date. Connect to update."))
        }
    }
}

/** Inputs the holder gathers; [buildHomeState] turns them into [HomeUiState]. */
data class HomeInputs(
    val trips: List<SavedTrip>?,
    val upcoming: Map<String, List<Journey>>,
    val status: DataStatus?,
    val density: CardDensity,
    val places: List<SavedPlace>,
    val links: Map<String, TripPlaces>,
    /** Stop id -> short station name, for "change at …". */
    val stopNames: Map<StopId, String> = emptyMap(),
    /** Trip id -> first departure of the next service day. */
    val firstNextDay: Map<String, Long?> = emptyMap(),
)

/** Pure: the home screen for [inputs] at [nowMs]; [today] is the service date `YYYYMMDD`. */
fun buildHomeState(inputs: HomeInputs, nowMs: Long, today: ServiceDate): HomeUiState {
    val trips = inputs.trips ?: return HomeUiState(loading = true, nowMs = nowMs)
    val placesById = inputs.places.associateBy { it.id }
    val compact = inputs.density == CardDensity.COMPACT
    // Folders in the order their first trip appears.
    val groups = LinkedHashMap<String?, MutableList<SavedTrip>>()
    for (t in trips.sortedBy { it.order }) groups.getOrPut(t.folder?.takeIf { it.isNotBlank() }) { mutableListOf() } += t
    val folders = groups.map { (name, list) ->
        HomeFolder(name, list.mapIndexed { i, trip -> card(trip, full = i == 0 && !compact, inputs, placesById, nowMs) })
    }
    return HomeUiState(
        loading = false,
        nowMs = nowMs,
        chip = dataChip(inputs.status, nowMs),
        banners = banners(inputs.status, today),
        folders = folders,
    )
}

private fun card(trip: SavedTrip, full: Boolean, inputs: HomeInputs, places: Map<String, SavedPlace>, nowMs: Long): TripCard {
    val journeys = inputs.upcoming[trip.id].orEmpty()
    val headline = headlineOf(journeys)
    val link = inputs.links[trip.id]
    val fromPlace = link?.fromPlaceId?.let { places[it] }
    val toPlace = link?.toPlaceId?.let { places[it] }
    val linked = fromPlace != null || toPlace != null
    val stations = "${trip.originName} to ${trip.destinationName}".uppercase()
    val titleParts = when {
        linked -> listOf(fromPlace?.name ?: trip.originName, toPlace?.name ?: trip.destinationName)
        trip.customName != null -> listOf(trip.customName!!)
        else -> listOf(trip.originName, trip.destinationName)
    }
    val leaveBy = if (fromPlace != null && headline != null) {
        val dep = headline.departure
        walkTo(fromPlace, dep.originStopId, trip.legs.firstOrNull()?.originStopIds.orEmpty())?.let { (walk, stopName) ->
            leaveByState(dep.expectedDepartureMs, walk, nowMs)?.let { (at, state) -> LeaveBy(at, state, walk, stopName) }
        }
    } else null
    val changes = headline?.legs?.zipWithNext { a, b ->
        ChangeSummary(inputs.stopNames[b.originStopId], ((b.expectedDepartureMs - a.expectedArrivalMs) / 60_000).toInt().coerceAtLeast(0))
    }.orEmpty()
    return TripCard(
        trip = trip,
        full = full,
        titleParts = titleParts,
        stationsLine = if (linked || trip.customName != null) stations else null,
        headline = headline,
        following = followingOf(journeys, headline),
        changes = changes,
        leaveBy = leaveBy,
        linkedToPlaces = linked,
        noMoreServices = if (journeys.isEmpty()) NoMoreServices(inputs.firstNextDay[trip.id]) else null,
    )
}

