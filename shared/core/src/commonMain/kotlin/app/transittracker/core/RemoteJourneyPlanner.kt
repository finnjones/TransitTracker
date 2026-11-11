package app.transittracker.core

import app.transittracker.model.JourneyPlanner
import app.transittracker.model.LatLng
import app.transittracker.model.PlacesResult
import app.transittracker.model.PlanRequest
import app.transittracker.model.PlanResult

/**
 * The real [JourneyPlanner]: asks tt-server (`GET /v1/places`, `POST /v1/plan`), which asks
 * Transport for NSW's Trip Planner. The phone never talks to TfNSW.
 *
 * Outcomes:
 * - the server could not be reached → `Offline`;
 * - `503 planner_unavailable` (no key, today's quota used up, TfNSW throttling) → `Unavailable`;
 * - anything else that is not a success (including `429` and a body that cannot be read) →
 *   `Failed` with a plain sentence for the user ([PlannerMessages]).
 *
 * Local guards, so the server is not asked pointless questions: a place query is trimmed, a query
 * shorter than [MIN_QUERY_LENGTH] characters is answered with no places and no call, and a longer
 * one is cut to [MAX_QUERY_LENGTH]; `limit` is kept within 1..[MAX_PLACES], and
 * [PlanRequest.maxItineraries] within 1..[MAX_ITINERARIES].
 */
class RemoteJourneyPlanner(private val api: ApiClient) : JourneyPlanner {

    override suspend fun searchPlaces(query: String, near: LatLng?, limit: Int): PlacesResult {
        val q = normalizePlaceQuery(query) ?: return PlacesResult.Found(emptyList())
        return when (val result = api.places(q, near, limit.coerceIn(1, MAX_PLACES))) {
            is ApiResult.Success -> PlacesResult.Found(result.value.places)
            is ApiResult.HttpFailure ->
                if (isUnavailable(result)) PlacesResult.Unavailable else PlacesResult.Failed(httpMessage(result, PLACES))
            is ApiResult.NetworkFailure ->
                if (result.reachedServer) PlacesResult.Failed(PlannerMessages.UNREADABLE) else PlacesResult.Offline
        }
    }

    override suspend fun plan(request: PlanRequest): PlanResult {
        val sent = request.copy(maxItineraries = request.maxItineraries.coerceIn(1, MAX_ITINERARIES))
        return when (val result = api.plan(sent)) {
            is ApiResult.Success -> PlanResult.Found(result.value)
            is ApiResult.HttpFailure ->
                if (isUnavailable(result)) PlanResult.Unavailable else PlanResult.Failed(httpMessage(result, PLAN))
            is ApiResult.NetworkFailure ->
                if (result.reachedServer) PlanResult.Failed(PlannerMessages.UNREADABLE) else PlanResult.Offline
        }
    }

    private fun isUnavailable(failure: ApiResult.HttpFailure): Boolean =
        failure.status == 503 && failure.error?.code == CODE_UNAVAILABLE

    private fun httpMessage(failure: ApiResult.HttpFailure, what: Int): String = when {
        failure.status == 429 -> PlannerMessages.TOO_MANY
        failure.status == 401 || failure.status == 403 -> PlannerMessages.NOT_JOINED
        failure.status == 400 -> if (what == PLAN) PlannerMessages.BAD_JOURNEY else PlannerMessages.BAD_SEARCH
        failure.status == 502 && failure.error?.code == CODE_UPSTREAM -> PlannerMessages.UPSTREAM
        failure.status >= 500 -> PlannerMessages.SERVER_ERROR
        else -> PlannerMessages.GENERIC
    }

    companion object {
        /** Shortest place query sent to the server (after trimming). */
        const val MIN_QUERY_LENGTH = 2

        /** Longest place query sent to the server; longer queries are cut. */
        const val MAX_QUERY_LENGTH = 80

        /** Most places one search may ask for. */
        const val MAX_PLACES = 20

        /** Most itineraries one plan request may ask for. */
        const val MAX_ITINERARIES = 6

        /** Problem `code` of `503` when the server has no planner right now. */
        const val CODE_UNAVAILABLE = "planner_unavailable"

        /** Problem `code` of `502` when TfNSW answered with something unusable. */
        const val CODE_UPSTREAM = "planner_upstream"

        private const val PLACES = 0
        private const val PLAN = 1

        /**
         * The query actually sent for [query]: trimmed, cut to [MAX_QUERY_LENGTH] characters, or
         * null when it is shorter than [MIN_QUERY_LENGTH] (no search is made).
         */
        fun normalizePlaceQuery(query: String): String? {
            val q = query.trim().take(MAX_QUERY_LENGTH).trimEnd()
            return q.takeIf { it.length >= MIN_QUERY_LENGTH }
        }
    }
}

/** The plain sentences [RemoteJourneyPlanner] puts in `Failed` results, for the user to read. */
object PlannerMessages {
    const val TOO_MANY = "Too many searches. Try again in a few minutes."
    const val NOT_JOINED = "This phone is not connected to the server. Join again with an invite code."
    const val BAD_JOURNEY = "That journey cannot be planned. Check the start, the end and the time."
    const val BAD_SEARCH = "That search cannot be made. Try different words."
    const val UPSTREAM = "The journey planner sent an answer that could not be used. Try again."
    const val SERVER_ERROR = "The server had a problem. Try again soon."
    const val UNREADABLE = "The server sent an answer the app could not read. Try again soon."
    const val GENERIC = "Something went wrong. Try again."
}
