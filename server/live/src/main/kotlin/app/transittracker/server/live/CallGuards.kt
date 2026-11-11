package app.transittracker.server.live

import app.transittracker.model.ServiceTime
import app.transittracker.model.WallClock
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.random.Random

/**
 * Token-bucket rate limiter (implemented as GCRA, so waiters reserve a slot and sleep exactly once).
 * Allows a burst of [burst] calls, then [permitsPerSecond] on average.
 *
 * @param nanoTime monotonic clock; injectable for tests running in virtual time.
 */
class RateLimiter(
    permitsPerSecond: Int,
    private val burst: Int = permitsPerSecond.coerceAtLeast(1),
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val intervalNanos: Long = 1_000_000_000L / permitsPerSecond.coerceAtLeast(1)
    private val toleranceNanos: Long = (burst - 1).coerceAtLeast(0) * intervalNanos
    private val lock = Any()
    private var theoreticalArrival: Long = Long.MIN_VALUE

    /** Suspends until a call is allowed. */
    suspend fun acquire() {
        val waitNanos = synchronized(lock) {
            val now = nanoTime()
            val tat = if (theoreticalArrival == Long.MIN_VALUE) now else maxOf(theoreticalArrival, now)
            val allowedAt = tat - toleranceNanos
            theoreticalArrival = tat + intervalNanos
            allowedAt - now
        }
        if (waitNanos > 0) delay((waitNanos + 999_999) / 1_000_000)
    }
}

/**
 * Counts TfNSW calls per Sydney calendar day and refuses calls once [budget] is reached.
 * The count is persisted to [file] so restarts do not reset it.
 */
class DailyCallBudget(
    private val budget: Int,
    private val file: Path,
    private val clock: WallClock,
) {
    private var day: String = ""
    private var count: Int = 0
    private var exhaustedLogged = false

    init {
        load()
    }

    /** Calls made so far today (Australia/Sydney). */
    val callsToday: Int
        @Synchronized get() {
            rollover()
            return count
        }

    /** True if the budget for today is used up. */
    val exhausted: Boolean
        @Synchronized get() {
            rollover()
            return count >= budget
        }

    /** Records one call and returns true, or returns false (recording nothing) if the budget is used up. */
    @Synchronized
    fun tryConsume(): Boolean {
        rollover()
        if (count >= budget) {
            if (!exhaustedLogged) {
                log.warn("Daily TfNSW call budget of {} reached for {}; serving cached data until Sydney midnight", budget, day)
                exhaustedLogged = true
            }
            return false
        }
        count++
        save()
        return true
    }

    private fun rollover() {
        val today = ServiceTime.localDate(clock.nowMillis())
        if (today != day) {
            day = today
            count = 0
            exhaustedLogged = false
        }
    }

    private fun load() {
        try {
            if (Files.exists(file)) {
                val parts = Files.readString(file).trim().split(' ')
                if (parts.size == 2) {
                    day = parts[0]
                    count = parts[1].toInt()
                }
            }
        } catch (e: Exception) {
            log.warn("Could not read call counter {}: {}", file, e.toString())
        }
        rollover()
    }

    private fun save() {
        try {
            Files.createDirectories(file.toAbsolutePath().parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(tmp, "$day $count\n")
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            log.warn("Could not persist call counter {}: {}", file, e.toString())
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(DailyCallBudget::class.java)
    }
}

/**
 * Exponential backoff for one feed: 30 s, 60 s, 120 s ... capped at 300 s, plus up to 20 % jitter
 * (never beyond the cap).
 */
class BackoffPolicy(
    private val baseMs: Long = 30_000,
    private val maxMs: Long = 300_000,
    private val jitter: Double = 0.2,
    private val random: Random = Random.Default,
) {
    /** Backoff after the [consecutiveErrors]-th consecutive failure (1-based). */
    fun delayMs(consecutiveErrors: Int): Long {
        val exponent = (consecutiveErrors - 1).coerceIn(0, 20)
        val base = minOf(maxMs, baseMs shl exponent)
        val withJitter = base + (base * jitter * random.nextDouble()).toLong()
        return minOf(maxMs, withJitter)
    }

    companion object {
        /** Statuses TfNSW has been seen to use for over-limit responses. */
        val THROTTLE_STATUSES = setOf(401, 403, 429, 503)
    }
}
