package app.transittracker.ui.feature.home

import app.transittracker.model.DataStatus
import app.transittracker.model.Departure
import app.transittracker.model.DepartureStatus
import app.transittracker.model.Journey
import app.transittracker.model.SavedTrip
import app.transittracker.model.ServiceDate
import app.transittracker.model.StopId
import app.transittracker.model.bestRuns
import app.transittracker.ui.feature.follow.FollowMatch
import app.transittracker.ui.feature.follow.FollowStage
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
    /** Follow my journey: the user is on a journey of this trip (shown first, the rest as other services). */
    val onTheWay: OnTheWay? = null,
    /** For a route with several ways: "BEST OF 3 WAYS", or "PINNED · T9 FROM GORDON"; null for one way. */
    val waysLabel: String? = null,
)

data class NoMoreServices(val firstNextDayMs: Long?)

/** The journey Follow my journey says the user is on, with its live times from the core. */
data class Followed(val match: FollowMatch, val journey: Journey)

/**
 * The card of a trip the user is on (Follow my journey): the ride they are on and the connection
 * to make, or the service they are changing onto. Replaces the next departure as the headline;
 * the next departures stay underneath as other services.
 */
data class OnTheWay(
    val journey: Journey,
    val stage: FollowStage,
    val legIndex: Int,
    /** Riding: the leg being ridden. Changing: the leg being walked to. */
    val leg: Departure,
    /** Riding: the next leg (the connection to make); null on the last leg and while changing. */
    val next: Departure?,
    /** Minutes from [leg]'s expected arrival to [next]'s expected departure. */
    val changeMinutes: Int?,
    /** The change is shorter than the route allows for walking, or [next] is cancelled. */
    val tight: Boolean,
    /** Short name of where [leg] is left ("Parramatta"), when known. */
    val legEnd: String?,
    /** Short name of where [leg] is boarded, when known (the change stop while changing). */
    val legStart: String?,
) {
    val arrivalMs: Long get() = journey.arrival.expectedArrivalMs
    val isLastLeg: Boolean get() = legIndex == journey.legs.lastIndex
}

/** The [OnTheWay] for [trip] from [followed]; [stopNames] gives short stop names. */
fun onTheWayOf(trip: SavedTrip, followed: Followed, stopNames: Map<StopId, String>): OnTheWay {
    val legs = followed.journey.legs
    val k = followed.match.legIndex.coerceIn(0, legs.lastIndex)
    val leg = legs[k]
    val riding = followed.match.stage == FollowStage.RIDING
    val next = if (riding) legs.getOrNull(k + 1) else null
    val change = next?.let { ((it.expectedDepartureMs - leg.expectedArrivalMs) / 60_000).toInt() }
    val allowed = (trip.way(followed.journey.wayId)?.legs ?: trip.legs).getOrNull(k + 1)?.minConnectionMinutes
    val tight = next != null && (next.status == DepartureStatus.CANCELLED || (change != null && allowed != null && change < allowed))
    return OnTheWay(
        journey = followed.journey,
        stage = followed.match.stage,
        legIndex = k,
        leg = leg,
        next = next,
        changeMinutes = change,
        tight = tight,
        legEnd = stopNames[leg.destinationStopId],
        legStart = stopNames[leg.originStopId],
    )
}

// ---------------------------------------------------------------- rules

/** The headline: the first journey with no cancelled ride (plan 2.7: cancelled never headlines). */
fun headlineOf(journeys: List<Journey>): Journey? =
    journeys.firstOrNull { j -> j.legs.none { it.status == DepartureStatus.CANCELLED } }

/** Up to [count] journeys other than [headline], in time order. */
fun followingOf(journeys: List<Journey>, headline: Journey?, count: Int = 2): List<Journey> =
    journeys.filter { it !== headline }.sortedBy { it.departure.expectedDepartureMs }.take(count)

/**
 * The journeys of a route with several ways that Home offers: those of the pinned way when one is
 * pinned, else all of them.
 */
fun journeysShown(trip: SavedTrip, journeys: List<Journey>): List<Journey> {
    val pinned = trip.pinnedWay ?: return journeys
    return journeys.filter { it.follows(pinned.id) }
}

/** The origin stops of the way [journey] follows (the trip's first way when not known). */
fun wayOrigins(trip: SavedTrip, journey: Journey): Set<StopId> =
    (trip.way(journey.wayId)?.legs ?: trip.legs).firstOrNull()?.originStopIds.orEmpty()

/**
 * When the user has to leave for [journey]: its departure, less the walk from [from] to the stop
 * it leaves from when the route starts at a saved place and the walk is known.
 */
fun leaveAtFor(trip: SavedTrip, journey: Journey, from: SavedPlace?): Long {
    val dep = journey.departure
    val walk = from?.let { walkTo(it, dep.originStopId, wayOrigins(trip, journey))?.first } ?: 0
    return dep.expectedDepartureMs - walk * 60_000L
}

/**
 * The headline of a route with several ways (none pinned): the run that gets there first among
 * those the user can still make without running (leave-by not yet passed); if there is none,
 * among those not yet gone. Never a cancelled one. Ties go to the one that leaves later, then the
 * one with fewer changes, then the earlier way.
 */
fun bestHeadline(trip: SavedTrip, journeys: List<Journey>, from: SavedPlace?, nowMs: Long): Journey? {
    val running = journeys.filter { j -> j.legs.none { it.status == DepartureStatus.CANCELLED } && j.departure.expectedDepartureMs > nowMs }
    val order = compareBy<Journey> { it.arrival.expectedArrivalMs }
        .thenByDescending { leaveAtFor(trip, it, from) }
        .thenBy { it.legs.size }
    val relaxed = running.filter { leaveAtFor(trip, it, from) >= nowMs }
    return (relaxed.ifEmpty { running }).minWithOrNull(order) ?: headlineOf(journeys)
}

/**
 * The services listed under the headline of a route with several ways: the next [count] in time
 * order that no other way beats (leaving no earlier and arriving sooner), so a slow way that
 * leaves first is not offered over a faster one that leaves later.
 */
fun bestFollowing(trip: SavedTrip, journeys: List<Journey>, headline: Journey?, from: SavedPlace?, count: Int = 2): List<Journey> {
    val best = bestRuns(journeys, { leaveAtFor(trip, it, from) }, { it.arrival.expectedArrivalMs })
    return best.filter { it !== headline && it != headline }.sortedBy { it.departure.expectedDepartureMs }.take(count)
}

/** "BEST OF 3 WAYS" / "PINNED · T9 FROM GORDON" for a route with several ways; null for one way. */
fun waysLabel(trip: SavedTrip, wayName: (app.transittracker.model.RouteOption) -> String): String? {
    if (trip.ways.size < 2) return null
    val pinned = trip.pinnedWay
    return if (pinned != null) "PINNED · " + wayName(pinned).uppercase() else "BEST OF ${trip.ways.size} WAYS"
}

/** Other services listed under a journey the user is on. */
const val OTHER_SERVICES = 3

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
    /** The journey the user is on (Follow my journey), if any. */
    val follow: Followed? = null,
)

/** Pure: the home screen for [inputs] at [nowMs]; [today] is the service date `YYYYMMDD`. */
fun buildHomeState(inputs: HomeInputs, nowMs: Long, today: ServiceDate): HomeUiState {
    val trips = inputs.trips ?: return HomeUiState(loading = true, nowMs = nowMs)
    val placesById = inputs.places.associateBy { it.id }
    val compact = inputs.density == CardDensity.COMPACT
    // Folders in the order their first trip appears.
    val groups = LinkedHashMap<String?, MutableList<SavedTrip>>()
    for (t in trips.sortedBy { it.order }) groups.getOrPut(t.folder?.takeIf { it.isNotBlank() }) { mutableListOf() } += t
    // The trip the user is on (Follow my journey) comes first, on its own, above every folder.
    val followedId = inputs.follow?.match?.tripId?.takeIf { id -> trips.any { it.id == id } }
    val onTheWay = followedId?.let { id -> HomeFolder(null, listOf(card(trips.first { it.id == id }, full = true, inputs, placesById, nowMs))) }
    val folders = listOfNotNull(onTheWay) + groups.mapNotNull { (name, list) ->
        val rest = list.filter { it.id != followedId }
        if (rest.isEmpty()) null
        else HomeFolder(name, rest.mapIndexed { i, trip -> card(trip, full = i == 0 && !compact, inputs, placesById, nowMs) })
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
    val link = inputs.links[trip.id]
    val fromPlace = link?.fromPlaceId?.let { places[it] }
    val journeys = journeysShown(trip, inputs.upcoming[trip.id].orEmpty())
    val choosing = trip.ways.size > 1 && trip.pinnedWay == null
    val headline = if (choosing) bestHeadline(trip, journeys, fromPlace, nowMs) else headlineOf(journeys)
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
        walkTo(fromPlace, dep.originStopId, wayOrigins(trip, headline))?.let { (walk, stopName) ->
            leaveByState(dep.expectedDepartureMs, walk, nowMs)?.let { (at, state) -> LeaveBy(at, state, walk, stopName) }
        }
    } else null
    val onTheWay = inputs.follow?.takeIf { it.match.tripId == trip.id }?.let { onTheWayOf(trip, it, inputs.stopNames) }
    val changes = headline?.legs?.zipWithNext { a, b ->
        ChangeSummary(inputs.stopNames[b.originStopId], ((b.expectedDepartureMs - a.expectedArrivalMs) / 60_000).toInt().coerceAtLeast(0))
    }.orEmpty()
    return TripCard(
        trip = trip,
        full = full,
        titleParts = titleParts,
        stationsLine = if (linked || trip.customName != null) stations else null,
        headline = headline,
        // On the way, the next departures are all "other services": the headline among them.
        following = when {
            onTheWay != null -> journeys.sortedBy { it.departure.expectedDepartureMs }.take(OTHER_SERVICES)
            choosing -> bestFollowing(trip, journeys, headline, fromPlace)
            else -> followingOf(journeys, headline)
        },
        changes = changes,
        leaveBy = leaveBy,
        linkedToPlaces = linked,
        noMoreServices = if (journeys.isEmpty()) NoMoreServices(inputs.firstNextDay[trip.id]) else null,
        onTheWay = onTheWay,
        waysLabel = waysLabel(trip) { w -> wayName(trip, w, inputs.upcoming[trip.id].orEmpty()) },
    )
}

/**
 * What a way is called: its label, else its lines as the services found for it run them
 * ("T9", "T1/T9", "333 then T4"), else "Way 2".
 */
fun wayName(trip: SavedTrip, way: app.transittracker.model.RouteOption, journeys: List<Journey>): String {
    way.label?.takeIf { it.isNotBlank() }?.let { return it }
    val runs = journeys.filter { it.wayId == way.id || way.id in it.alsoWays }
    if (runs.isNotEmpty()) {
        return way.legs.indices.joinToString(" then ") { i ->
            runs.mapNotNull { it.legs.getOrNull(i)?.routeName }.distinct().sorted().joinToString("/")
        }
    }
    return "Way ${trip.ways.indexOfFirst { it.id == way.id } + 1}"
}
