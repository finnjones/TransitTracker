package app.transittracker.ui.feature.service

import app.transittracker.model.ServiceDetail
import app.transittracker.model.ServiceRef
import app.transittracker.model.StopId
import app.transittracker.model.TransitCore
import app.transittracker.model.WallClock
import app.transittracker.ui.format.shortStopName
import app.transittracker.ui.state.StateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * State for the service screen (plan 5.4): one `core.service(ref)` subscription (live only while
 * the page is shown) and a tick every [tickMs] so the footer counts down and the marker moves.
 * What is late, skipped or passed comes from the core; this works out where the vehicle marker
 * sits, which countdown the footer shows and the user's stops (see [buildServiceState]).
 */
class ServiceStateHolder(
    private val core: TransitCore,
    private val ref: ServiceRef,
    private val boardStopId: StopId?,
    private val alightStopId: StopId?,
    private val clock: WallClock,
    scope: CoroutineScope,
    private val tickMs: Long = 1_000,
) : StateHolder<ServiceUiState>(scope) {
    /** Stop sequences of the user's stops, kept once matched so a platform change does not lose them. */
    private var boardSeq: Int? = null
    private var alightSeq: Int? = null

    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(clock.nowMillis())
            delay(tickMs)
        }
    }

    override val state: StateFlow<ServiceUiState> = combine(
        core.service(ref).map<ServiceDetail, ServiceDetail?> { it }.onStart { emit(null) },
        ticker,
    ) { detail, now ->
        if (detail == null) ServiceUiState(loading = true, nowMs = now)
        else buildServiceState(detail, boardStopId, alightStopId, now, boardSeq, alightSeq, ::shortStopName).also { s ->
            s.boardIndex?.let { boardSeq = s.calls[it].stopSequence }
            s.alightIndex?.let { alightSeq = s.calls[it].stopSequence }
        }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, ServiceUiState())
}
