package app.transittracker.android

import app.transittracker.model.JourneyPlanner
import app.transittracker.model.LatLng
import app.transittracker.model.PlacesResult
import app.transittracker.model.PlanRequest
import app.transittracker.model.PlanResult

/**
 * The live build's journey planner, in one place. The real planner client is being added to
 * `:shared:core`; until it lands, planning answers Unavailable and the screens say so.
 * When it is there, change [livePlanner] to return it (one line).
 */
internal fun livePlanner(): JourneyPlanner = UnavailablePlanner

private object UnavailablePlanner : JourneyPlanner {
    override suspend fun searchPlaces(query: String, near: LatLng?, limit: Int): PlacesResult = PlacesResult.Unavailable

    override suspend fun plan(request: PlanRequest): PlanResult = PlanResult.Unavailable
}
