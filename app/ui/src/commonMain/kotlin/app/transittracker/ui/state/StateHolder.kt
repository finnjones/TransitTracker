package app.transittracker.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow

/**
 * The state holder convention (README "State holders"):
 *
 * - One plain class per screen, `XxxStateHolder(deps…, scope: CoroutineScope)`, in the screen's
 *   feature package. It takes only what it needs from `AppDeps` (not `AppDeps` itself), so tests
 *   build it with fakes and `runTest`'s `backgroundScope`.
 * - It exposes one `val state: StateFlow<XxxUiState>` (an immutable data class, everything the
 *   screen draws, already formatted where the plan says so) and plain methods for events
 *   (`fun refresh()`, `fun toggle(id)`). Navigation stays in the screen: holders never see the
 *   navigator.
 * - Work runs in [scope]. In the UI, [rememberStateHolder] gives each holder its own child scope,
 *   cancelled when the screen leaves composition. A page under another page on the back stack is
 *   not composed, so its holder stops (no polling for screens nobody sees, plan 3.6) and is
 *   rebuilt on return: keep anything the user typed in `rememberSaveable` or in a store.
 * - No Android ViewModel: nothing here needs to outlive the composition, and plain classes keep
 *   tests and iOS simple.
 */
abstract class StateHolder<S>(protected val scope: CoroutineScope) {
    abstract val state: StateFlow<S>
}

/**
 * Creates a state holder once per [keys] with its own scope, cancelled when it is forgotten.
 *
 * ```
 * val holder = rememberStateHolder(tripId) { scope -> TimetableStateHolder(deps.core, tripId, scope) }
 * val state by holder.state.collectAsState()
 * ```
 */
@Composable
fun <H : Any> rememberStateHolder(vararg keys: Any?, create: (CoroutineScope) -> H): H {
    val parent = rememberCoroutineScope()
    val box = remember(*keys) {
        val job = SupervisorJob(parent.coroutineContext[Job])
        val scope = CoroutineScope(parent.coroutineContext + job)
        HolderBox(create(scope), scope)
    }
    return box.holder
}

private class HolderBox<H>(val holder: H, private val scope: CoroutineScope) : RememberObserver {
    override fun onRemembered() = Unit
    override fun onForgotten() = scope.cancel()
    override fun onAbandoned() = scope.cancel()
}
