package app.transittracker.server.app

import app.transittracker.model.LatLng
import app.transittracker.model.PlacesResponse
import app.transittracker.model.PlanRequest
import app.transittracker.model.PlanResponse
import app.transittracker.server.spi.ApiException
import app.transittracker.server.spi.Planner
import app.transittracker.server.spi.PlannerStatus

/** The planner of a server built without one: every request is 503 `planner_unavailable`. */
object NoPlanner : Planner {
    override val available: Boolean get() = false

    override suspend fun places(query: String, near: LatLng?, limit: Int): PlacesResponse = unavailable()

    override suspend fun plan(request: PlanRequest): PlanResponse = unavailable()

    override fun status() = PlannerStatus(
        available = false, source = "off", placesRequests = 0, planRequests = 0, cacheHits = 0, upstreamCalls = 0,
        upstreamFailures = 0, matchedRides = 0, unmatchedRides = 0, lastCallMs = null, lastHttpStatus = null, backoffUntilMs = null,
    )

    private fun unavailable(): Nothing =
        throw ApiException(503, "planner_unavailable", "Journey planning is not set up on this server")
}
