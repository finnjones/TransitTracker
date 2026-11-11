package app.transittracker.ui.feature.plan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.transittracker.model.JourneyPlanner
import app.transittracker.model.LatLng
import app.transittracker.model.PlacesResult
import app.transittracker.model.PlanEndpoint
import app.transittracker.model.PlanRequest
import app.transittracker.model.PlanResult
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.app.AppPage
import app.transittracker.ui.app.PagePreview
import app.transittracker.ui.fake.CoreScenario
import app.transittracker.ui.fake.FakeSetup
import app.transittracker.ui.fake.LiveData
import app.transittracker.ui.gallery.SampleData
import app.transittracker.ui.gallery.Shot
import app.transittracker.ui.nav.JourneyEnd
import app.transittracker.ui.nav.Route
import app.transittracker.ui.planning.FakeJourneyPlanner
import app.transittracker.ui.planning.FakeNetwork
import app.transittracker.ui.state.rememberStateHolder
import app.transittracker.ui.theme.DayColors
import kotlinx.coroutines.awaitCancellation

/**
 * Screenshots of the `plan` feature (Plan a journey, Journey options, One journey), on
 * `AppDeps.fake()` at Wed 11 Nov 07:38. Compare with the NewTrip, Options and Journey mockups.
 */
object PlanShots {
    private val home = JourneyEnd("Home", FakeJourneyPlanner.HOME, savedPlaceId = FakeSetup.HOME)
    private val work = JourneyEnd("Work", FakeJourneyPlanner.PITT_ST, savedPlaceId = FakeSetup.WORK)
    private val campbell = JourneyEnd("Campbell Pde", FakeJourneyPlanner.CAMPBELL_PDE, plannerPlaceId = "fake:campbell-pde")

    private val recent = listOf(
        RecentSearch(home, campbell),
        RecentSearch(work, JourneyEnd("Bondi Beach", FakeJourneyPlanner.BONDI_BEACH, plannerPlaceId = "fake:bondi-beach")),
    )

    private val t0 = SampleData.NOW_MS / 60_000 * 60_000

    private fun deps(answer: FakeJourneyPlanner.Answer = FakeJourneyPlanner.Answer.NORMAL, scenario: CoreScenario = CoreScenario()): AppDeps =
        AppDeps.fake(scenario = scenario).also {
            (it.planner as FakeJourneyPlanner).answer = answer
            RecentSearches.seed(it, recent)
        }

    /** A planner that never answers (loading). */
    private object Waiting : JourneyPlanner {
        override suspend fun searchPlaces(query: String, near: LatLng?, limit: Int): PlacesResult = awaitCancellation()
        override suspend fun stopsNear(position: LatLng, radiusMeters: Int): PlacesResult = awaitCancellation()
        override suspend fun plan(request: PlanRequest): PlanResult = awaitCancellation()
    }

    @Composable
    private fun page(route: Route, d: AppDeps = remember { deps() }) = PagePreview(d) { AppPage(route) }

    /** The form with a holder set up by [setup] (no search debounce, so results show at once). */
    @Composable
    private fun form(setup: (PlanJourneyStateHolder) -> Unit) {
        val d = remember { deps() }
        PagePreview(d) {
            val holder = rememberStateHolder { scope ->
                PlanJourneyStateHolder(
                    d.planner, d.core, d.places, d.platform, d.clock, RecentSearches.of(d), null, null, scope,
                    searchDebounceMs = 0,
                ).also(setup)
            }
            PlanJourneyPage(holder)
        }
    }

    private val options = Route.JourneyOptions(home, campbell)

    private fun bondi(delay: Int) = Route.JourneyDetail(home, campbell, FakeJourneyPlanner.itineraries(t0, delay).first())

    private val homeWork: Route.JourneyDetail by lazy {
        val req = PlanRequest(PlanEndpoint(home.position), PlanEndpoint(work.position))
        Route.JourneyDetail(home, work, FakeNetwork.plan(req, SampleData.NOW_MS, FakeJourneyPlanner.PLACES)!!.first())
    }

    val shots: List<Shot> = listOf(
        Shot("plan-form-empty") { page(Route.PlanJourney()) },
        Shot("plan-form-filled") {
            form { h ->
                h.choosePlace(FakeSetup.places.byId(FakeSetup.HOME), Field.FROM)
                h.chooseResult(FakeJourneyPlanner.PLACES.first { it.id == "fake:campbell-pde" }, Field.TO)
            }
        },
        Shot("plan-form-leave-at") {
            form { h ->
                h.choosePlace(FakeSetup.places.byId(FakeSetup.HOME), Field.FROM)
                h.choosePlace(FakeSetup.places.byId(FakeSetup.WORK), Field.TO)
                h.setWhen(WhenKind.ARRIVE_BY)
                h.stepTime(80)
            }
        },
        Shot("plan-form-search") {
            form { h ->
                h.choosePlace(FakeSetup.places.byId(FakeSetup.HOME), Field.FROM)
                h.edit(Field.TO)
                h.setQuery("bondi")
            }
        },
        Shot("plan-form-offline") { page(Route.PlanJourney(fromPlaceId = FakeSetup.HOME), remember { deps(scenario = CoreScenario(live = LiveData.OFFLINE)) }) },
        Shot("plan-options") { page(options) },
        Shot("plan-options-day", DayColors) { page(options) },
        Shot("plan-options-loading") {
            val d = remember { deps().let { AppDeps(it.core, Waiting, it.settings, it.places, it.platform, it.clock) } }
            page(options, d)
        },
        Shot("plan-options-nothing") { page(options, remember { deps(FakeJourneyPlanner.Answer.NOTHING_FOUND) }) },
        Shot("plan-options-offline") {
            page(options, remember { deps(FakeJourneyPlanner.Answer.OFFLINE, CoreScenario(live = LiveData.OFFLINE)) })
        },
        Shot("plan-options-failed") { page(options, remember { deps(FakeJourneyPlanner.Answer.FAILED) }) },
        Shot("plan-options-work") { page(Route.JourneyOptions(home, work)) },
        Shot("plan-journey") { page(bondi(4)) },
        Shot("plan-journey-tight") { page(bondi(6)) },
        Shot("plan-journey-missed") { page(bondi(9)) },
        Shot("plan-journey-day", DayColors) { page(bondi(4)) },
        Shot("plan-journey-work") { page(homeWork) },
        Shot("plan-journey-save") {
            val d = remember { deps() }
            PagePreview(d) {
                val r = homeWork
                val holder = rememberStateHolder { scope -> JourneyStateHolder(d.core, d.places, d.clock, r.itinerary, r.from, r.to, scope) }
                JourneyPage(r, holder, showSaveDialog = true)
            }
        },
    )
}
