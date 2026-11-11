package app.transittracker.model

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The one place that converts between GTFS service time and real instants.
 *
 * GTFS times are seconds measured from "noon minus 12 hours" on the service date, in the
 * agency time zone, and may exceed 24:00:00 for after-midnight running. On daylight-saving
 * change days "noon minus 12h" is NOT local midnight, which is why this exists.
 * Server and phone must both use these functions.
 */
@OptIn(ExperimentalTime::class)
object ServiceTime {
    val SYDNEY: TimeZone = TimeZone.of("Australia/Sydney")

    /** Latest GTFS time we accept when looking back to the previous service day (TfNSW runs to ~28:00). */
    const val MAX_SERVICE_SECONDS: Int = 30 * 3600

    fun parseDate(serviceDate: ServiceDate): LocalDate {
        require(serviceDate.length == 8 && serviceDate.all { it.isDigit() }) { "Bad service date: $serviceDate" }
        return LocalDate(
            serviceDate.substring(0, 4).toInt(),
            serviceDate.substring(4, 6).toInt(),
            serviceDate.substring(6, 8).toInt(),
        )
    }

    fun formatDate(date: LocalDate): ServiceDate =
        date.year.toString().padStart(4, '0') +
            (date.month.ordinal + 1).toString().padStart(2, '0') +
            date.day.toString().padStart(2, '0')

    /** Parses `H:MM:SS` / `HH:MM:SS` (hours may be >= 24) into seconds. */
    fun parseGtfsTime(text: String): Int {
        val parts = text.trim().split(':')
        require(parts.size == 3) { "Bad GTFS time: $text" }
        return parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toInt()
    }

    fun formatGtfsTime(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    }

    /** Epoch millis of the reference point ("noon minus 12h") of a service date. */
    fun referenceMillis(serviceDate: ServiceDate, zone: TimeZone = SYDNEY): Long {
        val noon = LocalDateTime(parseDate(serviceDate), LocalTime(12, 0)).toInstant(zone)
        return noon.toEpochMilliseconds() - 12L * 3600 * 1000
    }

    /** Converts a GTFS service-day time to epoch millis. */
    fun toEpochMillis(serviceDate: ServiceDate, secondsSinceReference: Int, zone: TimeZone = SYDNEY): Long =
        referenceMillis(serviceDate, zone) + secondsSinceReference * 1000L

    /** Seconds since the reference of [serviceDate] for an instant (may be negative or > 24h). */
    fun secondsSinceReference(serviceDate: ServiceDate, epochMillis: Long, zone: TimeZone = SYDNEY): Int =
        floorDiv(epochMillis - referenceMillis(serviceDate, zone), 1000L).toInt()

    private fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q
    }

    /** Local calendar date of an instant, as a service date string. */
    fun localDate(epochMillis: Long, zone: TimeZone = SYDNEY): ServiceDate =
        formatDate(Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone).date)

    /**
     * Service dates whose trips may be running at [epochMillis]: the previous day (after-midnight
     * running) then the local date. Callers query both and merge by absolute time.
     */
    fun candidateServiceDates(epochMillis: Long, zone: TimeZone = SYDNEY): List<ServiceDate> {
        val today = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone).date
        return listOf(formatDate(today.minus(DatePeriod(days = 1))), formatDate(today))
    }

    /** ISO day of week of a service date: 1 = Monday ... 7 = Sunday. */
    fun isoDayOfWeek(serviceDate: ServiceDate): Int = parseDate(serviceDate).dayOfWeek.ordinal + 1

    /** Seconds after local midnight for an instant (used for watch windows, which are wall-clock). */
    fun secondsOfLocalDay(epochMillis: Long, zone: TimeZone = SYDNEY): Int =
        Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone).time.toSecondOfDay()
}
