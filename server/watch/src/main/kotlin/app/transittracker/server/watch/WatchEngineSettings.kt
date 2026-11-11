package app.transittracker.server.watch

/**
 * Tunables for [WatchEngine]. Defaults follow the backend plan, section 7; tests may shrink them.
 */
data class WatchEngineSettings(
    /** Plan section 6: at most this many watches per device. */
    val maxWatchesPerDevice: Int = 10,
    /** Largest accepted `leadMinutes`. */
    val maxLeadMinutes: Int = 180,
    /** Largest accepted `delayThresholdMin` / `delayStepMin`. */
    val maxDelayRuleMinutes: Int = 120,
    /** A watch stays active this long after its last covered trip's expected departure. */
    val departureGraceMs: Long = 2 * 60_000L,
    /** Trip updates (or feed data) older than this are stale and never notified on (plan 4.1). */
    val staleAfterMs: Long = 3 * 60_000L,
    /** Local time (seconds after Sydney midnight) of the daily coverage recomputation. */
    val dailyCoverageSecondOfDay: Int = 3 * 3600 + 30 * 60,
    /** A delay of at most this many whole minutes counts as "on time" (recovery, "Next: … (on time)"). */
    val onTimeMaxDelayMin: Int = 1,
    /** Interval of the activation loop. */
    val tickIntervalMs: Long = 60_000L,
    /** Floor for a push message's time-to-live. */
    val minTtlSeconds: Long = 60L,
    /** Upper bound on departures fetched for one watch window. */
    val maxTripsPerWindow: Int = 500,
)
