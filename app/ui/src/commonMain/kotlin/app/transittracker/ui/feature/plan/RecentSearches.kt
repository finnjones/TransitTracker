package app.transittracker.ui.feature.plan

import app.transittracker.core.KeyValueStore
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.nav.JourneyEnd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
 * Recent searches kept in the app's key-value store (JSON under `plan.recent`), so they survive
 * a restart. Loading and saving run on [scope]; a search added before the stored list has loaded
 * stays first.
 */
class KeyValueRecentSearches(
    private val kv: KeyValueStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : RecentSearchStore {
    private val state = MutableStateFlow<List<RecentSearch>>(emptyList())
    private val writes = Mutex()
    override val recent: StateFlow<List<RecentSearch>> = state.asStateFlow()

    init {
        scope.launch {
            val stored = runCatching { kv.get(KEY)?.let { json.decodeFromString<List<RecentSearch>>(it) } }.getOrNull().orEmpty()
            state.update { added -> added.reversed().fold(stored.take(MAX_RECENT)) { list, search -> addRecent(list, search) } }
        }
    }

    override fun add(search: RecentSearch) {
        state.update { addRecent(it, search) }
        scope.launch {
            writes.withLock { runCatching { kv.put(KEY, json.encodeToString(state.value)) } }
        }
    }

    private companion object {
        const val KEY = "plan.recent"
        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * The recent searches of one running app: kept in `AppDeps.kv` when the shell gives one (so they
 * survive a restart), otherwise in memory for as long as the app runs. One store per [AppDeps].
 * Previews and tests can put a list in first with [seed].
 */
object RecentSearches {
    private val stores = mutableMapOf<AppDeps, RecentSearchStore>()

    fun of(deps: AppDeps): RecentSearchStore = stores.getOrPut(deps) {
        deps.kv?.let { KeyValueRecentSearches(it) } ?: InMemoryRecentSearches()
    }

    fun seed(deps: AppDeps, searches: List<RecentSearch>): RecentSearchStore =
        InMemoryRecentSearches(searches).also { stores[deps] = it }
}
