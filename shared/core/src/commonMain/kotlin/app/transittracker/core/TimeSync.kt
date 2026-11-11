package app.transittracker.core

import app.transittracker.model.WallClock
import kotlin.concurrent.Volatile

/**
 * Corrects the phone clock using the server's `X-Server-Time`.
 *
 * Each response yields one offset sample (server time minus the local time half-way through the
 * request); the offset used is the median of the most recent [maxSamples] samples, so a single
 * slow response cannot skew countdowns. Before any sample the local clock is used as is.
 *
 * Implements [WallClock]: everything in the core that needs "now" uses this.
 */
class TimeSync(private val local: WallClock, private val maxSamples: Int = 9) : WallClock {
    // Immutable snapshot swapped on each sample. Concurrent samples may occasionally drop one,
    // which is harmless for a median of recent samples.
    @Volatile
    private var samples: List<Long> = emptyList()

    @Volatile
    private var offset: Long = 0

    /** Current estimate of server time minus local time, in ms. */
    val offsetMillis: Long get() = offset

    /** Number of samples currently held. */
    val sampleCount: Int get() = samples.size

    /** Corrected current time (epoch ms). */
    override fun nowMillis(): Long = local.nowMillis() + offset

    /** Local (uncorrected) time; used to time requests for [addSample]. */
    fun localMillis(): Long = local.nowMillis()

    /** Records a server timestamp observed for a request sent at [sentLocalMs] and answered at [receivedLocalMs]. */
    fun addSample(serverTimeMs: Long, sentLocalMs: Long, receivedLocalMs: Long) {
        val midpoint = sentLocalMs + (receivedLocalMs - sentLocalMs) / 2
        val updated = (samples + (serverTimeMs - midpoint)).takeLast(maxSamples)
        samples = updated
        val sorted = updated.sorted()
        val n = sorted.size
        offset = if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }
}
