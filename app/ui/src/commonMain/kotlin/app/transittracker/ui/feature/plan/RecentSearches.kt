package app.transittracker.ui.feature.plan

import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.nav.JourneyEnd
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

/** One search the user made, for one-tap repeats under the Plan a journey form (plan 5.6). */
@Serializable
data class RecentSearch(val from: JourneyEnd, val to: JourneyEnd)

const val MAX_RECENT = 5

private fun same(a: JourneyEnd, b: JourneyEnd) =
    a.name == b.name && a.position == b.position && a.savedPlaceId == b.savedPlaceId

/** [list] with [search] first, an older copy of it removed, at most [max] long. */
fun addRecent(list: List<RecentSearch>, search: RecentSearch, max: Int = MAX_RECENT): List<RecentSearch> =
    (listOf(search) + list.filterNot { same(it.from, search.from) && same(it.to, search.to) }).take(max)

/** Recent searches, newest first. */
interface RecentSearchStore {
    val recent: StateFlow<List<RecentSearch>>

    fun add(search: RecentSearch)
}

class InMemoryRecentSearches(initial: List<RecentSearch> = emptyList()) : RecentSearchStore {
    private val state = MutableStateFlow(initial)
    override val recent: StateFlow<List<RecentSearch>> = state.asStateFlow()

    override fun add(search: RecentSearch) = state.update { addRecent(it, search) }
}

/**
 * The recent searches of one running app. `AppDeps` has no store for them yet (see the report:
 * "Wanted from shared code"), so they live in memory, one list per [AppDeps], for as long as the
 * app runs. Previews and tests can put a list in first with [seed].
 */
object RecentSearches {
    private val stores = mutableMapOf<AppDeps, RecentSearchStore>()

    fun of(deps: AppDeps): RecentSearchStore = stores.getOrPut(deps) { InMemoryRecentSearches() }

    fun seed(deps: AppDeps, searches: List<RecentSearch>): RecentSearchStore =
        InMemoryRecentSearches(searches).also { stores[deps] = it }
}
