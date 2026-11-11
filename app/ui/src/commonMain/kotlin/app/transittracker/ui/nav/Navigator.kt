package app.transittracker.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import app.transittracker.ui.motion.PageTransition
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One page on a back stack. [id] is unique for the navigator's lifetime and keys the page's saved
 * state (`rememberSaveable` inside the page survives being covered by other pages and process
 * death). [enter] is the transition that brought the page in; going back plays it in reverse.
 */
@Serializable
data class NavEntry(val id: Long, val route: Route, val enter: PageTransition = PageTransition.Cut)

/**
 * One navigation step, for the transition host: [from] was on screen, [to] is now. [reverse] is
 * true for back (a vehicle runs right to left). [serial] increases with every change.
 */
data class NavChange(
    val from: NavEntry,
    val to: NavEntry,
    val transition: PageTransition,
    val reverse: Boolean,
    val serial: Long,
)

/** The navigator's saved form. */
@Serializable
internal data class NavState(
    val stacks: Map<Tab, List<NavEntry>>,
    val tab: Tab,
    val nextId: Long,
)

/**
 * The app's back stacks: one per bottom-bar tab, each starting at the tab's root, so switching
 * tabs keeps where you were in each (plan 4.1). Pushed pages go on the current tab's stack.
 * Read [current] in composition; it is snapshot state.
 *
 * Transitions: every call takes an optional [PageTransition]; when it is null the plan's
 * default for that kind of move is used (plan 3.4):
 *  - [push]: doors into or out of the map / live tracking, otherwise a vehicle pass (pass the
 *    service's own vehicle with `PageTransition.vehiclePass(mode, colour)`; without one a plain
 *    train crosses);
 *  - [back]: the reverse of whatever brought the page in;
 *  - [switchTab]: split-flap, or doors when the Map tab is on either side;
 *  - [openFromNotification]: cut.
 */
@Stable
class Navigator internal constructor(initial: NavState) {
    private var state by mutableStateOf(initial)
    private var serial = 0L

    /** The last navigation step (null before the first). The transition host plays it. */
    var lastChange: NavChange? by mutableStateOf(null)
        private set

    constructor(startTab: Tab = Tab.TRIPS) : this(freshState(startTab))

    val currentTab: Tab get() = state.tab

    /** The current tab's stack, root first. */
    val backStack: List<NavEntry> get() = state.stacks.getValue(state.tab)

    val current: NavEntry get() = backStack.last()

    /** Every entry on every stack (pages whose saved state must be kept). */
    val allEntries: List<NavEntry> get() = state.stacks.values.flatten()

    /** True when [back] would do something other than leave the app. */
    val canGoBack: Boolean get() = backHandlerFor(current) != null || backStack.size > 1 || state.tab != Tab.TRIPS

    // Pages that take back themselves for now (see `OnBack`). Not saved: pages register again
    // when they are composed.
    private val backHandlers = mutableListOf<PageBackHandler>()

    internal fun addBackHandler(handler: PageBackHandler) {
        backHandlers += handler
    }

    internal fun removeBackHandler(handler: PageBackHandler) {
        backHandlers -= handler
    }

    private fun backHandlerFor(entry: NavEntry): PageBackHandler? = backHandlers.lastOrNull { it.entryId == entry.id }

    /** Opens [route] on top of the current page. */
    fun push(route: Route, transition: PageTransition? = null) {
        val from = current
        val t = transition ?: defaultPushTransition(from.route, route)
        val entry = NavEntry(state.nextId, route, t)
        state = state.copy(
            stacks = state.stacks + (state.tab to backStack + entry),
            nextId = state.nextId + 1,
        )
        record(from, entry, t, reverse = false)
    }

    /** Replaces the current page with [route] (the stack does not grow). */
    fun replace(route: Route, transition: PageTransition? = null) {
        val from = current
        val t = transition ?: defaultPushTransition(from.route, route)
        val entry = NavEntry(state.nextId, route, t)
        state = state.copy(
            stacks = state.stacks + (state.tab to backStack.dropLast(1) + entry),
            nextId = state.nextId + 1,
        )
        record(from, entry, t, reverse = false)
    }

    /**
     * System back or a back button. A page that has something of its own to close first (an
     * `OnBack` handler: a search view, a sub-page) gets the call instead. Otherwise pops the
     * current page (playing its entry transition in reverse); on a tab root other than Trips,
     * returns to Trips. Returns false on the Trips root: the platform should leave the app.
     */
    fun back(): Boolean {
        backHandlerFor(current)?.let {
            it.onBack()
            return true
        }
        val stack = backStack
        if (stack.size > 1) {
            val from = stack.last()
            state = state.copy(stacks = state.stacks + (state.tab to stack.dropLast(1)))
            record(from, current, from.enter, reverse = true)
            return true
        }
        if (state.tab != Tab.TRIPS) {
            switchTab(Tab.TRIPS)
            return true
        }
        return false
    }

    /** Pops back to the current tab's root. */
    fun popToRoot(transition: PageTransition? = null) {
        val stack = backStack
        if (stack.size <= 1) return
        val from = stack.last()
        state = state.copy(stacks = state.stacks + (state.tab to stack.take(1)))
        record(from, current, transition ?: from.enter, reverse = true)
    }

    /**
     * Shows [tab] with its stack as it was left. Tapping the tab you are on returns to its root.
     */
    fun switchTab(tab: Tab, transition: PageTransition? = null) {
        if (tab == state.tab) {
            popToRoot(transition)
            return
        }
        val from = current
        state = state.copy(tab = tab)
        val to = current
        val t = transition ?: if (from.route.isMap || to.route.isMap) PageTransition.Doors else PageTransition.SplitFlap
        record(from, to, t, reverse = false)
    }

    /**
     * Opened from a notification (plan 5.12): shows [route] at once (cut). A tab root switches to
     * that tab; anything else is pushed on the Trips stack above its root.
     */
    fun openFromNotification(route: Route) {
        val from = current
        val tab = Tab.entries.firstOrNull { it.root == route }
        if (tab != null) {
            state = state.copy(tab = tab, stacks = state.stacks + (tab to state.stacks.getValue(tab).take(1)))
        } else {
            val entry = NavEntry(state.nextId, route, PageTransition.Cut)
            val trips = state.stacks.getValue(Tab.TRIPS).take(1) + entry
            state = state.copy(tab = Tab.TRIPS, stacks = state.stacks + (Tab.TRIPS to trips), nextId = state.nextId + 1)
        }
        record(from, current, PageTransition.Cut, reverse = false)
    }

    /**
     * Clears every stack and shows [tab]'s root (e.g. Home after onboarding, with
     * `PageTransition.Doors`).
     */
    fun resetTo(tab: Tab = Tab.TRIPS, transition: PageTransition = PageTransition.Cut) {
        val from = current
        val fresh = freshState(tab, state.nextId)
        state = fresh
        record(from, current, transition, reverse = false)
    }

    /**
     * The shell's gate before the device has joined: clears every stack and shows
     * [Route.Onboarding] above the Trips root (cut). Onboarding ends with
     * `resetTo(Tab.TRIPS, PageTransition.Doors)`.
     */
    fun startOnboarding() {
        val from = current
        val fresh = freshState(Tab.TRIPS, state.nextId)
        val entry = NavEntry(fresh.nextId, Route.Onboarding, PageTransition.Cut)
        state = fresh.copy(
            stacks = fresh.stacks + (Tab.TRIPS to fresh.stacks.getValue(Tab.TRIPS) + entry),
            nextId = fresh.nextId + 1,
        )
        record(from, entry, PageTransition.Cut, reverse = false)
    }

    private fun record(from: NavEntry, to: NavEntry, transition: PageTransition, reverse: Boolean) {
        if (from.id == to.id) return
        lastChange = NavChange(from, to, transition, reverse, ++serial)
    }

    internal fun save(): String = json.encodeToString(NavState.serializer(), state)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private fun freshState(tab: Tab, firstId: Long = 0): NavState {
            val stacks = Tab.entries.mapIndexed { i, t -> t to listOf(NavEntry(firstId + i, t.root)) }.toMap()
            return NavState(stacks, tab, firstId + Tab.entries.size)
        }

        /** The default for a push from [from] to [to] (plan 3.4). */
        fun defaultPushTransition(from: Route, to: Route): PageTransition =
            if (from.isMap || to.isMap) PageTransition.Doors else PageTransition.DefaultVehiclePass

        /** Restores a navigator saved by [Saver]; falls back to a fresh one if the data is unreadable. */
        internal fun restore(saved: String): Navigator =
            runCatching { Navigator(json.decodeFromString(NavState.serializer(), saved)) }.getOrElse { Navigator() }

        /** Saves the back stacks (routes and entry ids) as one string. */
        val Saver: Saver<Navigator, String> = Saver(save = { it.save() }, restore = { restore(it) })
    }
}

/** A navigator that survives configuration changes and process death. */
@Composable
fun rememberNavigator(startTab: Tab = Tab.TRIPS): Navigator =
    rememberSaveable(saver = Navigator.Saver) { Navigator(startTab) }

/** The navigator of the running app. Every screen reads it to move. */
val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No Navigator: screens run inside TransitTrackerApp") }
