package app.transittracker.server.app

import app.transittracker.model.WallClock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * In-memory token buckets keyed by device id or client IP. Each key may make [burst] requests at
 * once (by default [capacity]), refilled continuously at [capacity] per [periodMs].
 * State is lost on restart, which is fine for abuse protection at this scale.
 */
class TokenBucketLimiter(
    val capacity: Int,
    val periodMs: Long = 60_000,
    private val clock: WallClock = SystemClock,
    val burst: Int = capacity,
) {
    private class Bucket(var tokens: Double, var updatedMs: Long)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    init {
        require(capacity > 0 && periodMs > 0 && burst > 0)
    }

    /**
     * Takes one token for [key]. Returns 0 if allowed, otherwise the number of seconds until a
     * token will be available (for `Retry-After`).
     */
    fun tryAcquire(key: String): Long {
        val now = clock.nowMillis()
        if (buckets.size > MAX_KEYS) evictFull(now)
        val bucket = buckets.computeIfAbsent(key) { Bucket(burst.toDouble(), now) }
        synchronized(bucket) {
            refill(bucket, now)
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                return 0
            }
            val waitMs = (1.0 - bucket.tokens) * periodMs / capacity
            return ceil(waitMs / 1000.0).toLong().coerceAtLeast(1)
        }
    }

    private fun refill(bucket: Bucket, now: Long) {
        val elapsed = (now - bucket.updatedMs).coerceAtLeast(0)
        bucket.tokens = (bucket.tokens + elapsed.toDouble() * capacity / periodMs).coerceAtMost(burst.toDouble())
        bucket.updatedMs = now
    }

    /** Drops buckets that have refilled completely (they behave exactly like new ones). */
    private fun evictFull(now: Long) {
        buckets.entries.removeIf { (_, b) -> synchronized(b) { refill(b, now); b.tokens >= burst } }
    }

    private companion object {
        const val MAX_KEYS = 10_000
    }
}

/** The API's rate limits (section 6 of the backend plan). */
class RateLimits(clock: WallClock = SystemClock) {
    /** Live endpoints (`/v1/rt*`, `/v1/vehicles`, `/v1/alerts`), per device. */
    val live = TokenBucketLimiter(capacity = 20, clock = clock)

    /** Watch endpoints, per device. */
    val watches = TokenBucketLimiter(capacity = 10, clock = clock)

    /** `POST /v1/join`, per client IP. */
    val join = TokenBucketLimiter(capacity = 5, clock = clock)

    /** `POST /v1/plan`, per device: about 30 an hour, at most 10 at once. Each may cost a TfNSW call. */
    val plan = TokenBucketLimiter(capacity = 30, periodMs = 3_600_000, clock = clock, burst = 10)

    /** `GET /v1/places`, per device: about 60 a minute (search as you type), at most 20 at once. */
    val places = TokenBucketLimiter(capacity = 60, periodMs = 60_000, clock = clock, burst = 20)
}
