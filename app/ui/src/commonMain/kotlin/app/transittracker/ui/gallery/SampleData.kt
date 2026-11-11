package app.transittracker.ui.gallery

import androidx.compose.ui.graphics.Color
import app.transittracker.model.Mode
import app.transittracker.model.StopCall
import app.transittracker.model.WallClock

/** Fixed sample data for galleries and screenshots (Wed 11 Nov 2026, 07:38 in Sydney). */
object SampleData {
    /** 2026-11-11T07:38:00+11:00. */
    const val NOW_MS = 1_794_343_080_000L
    val clock = WallClock { NOW_MS }

    val T1 = Color(0xFFF99D1C)
    val T2 = Color(0xFF0098CD)
    val T3 = Color(0xFFF37021)
    val T4 = Color(0xFF005AA3)
    val T8 = Color(0xFF00954C)
    val T9 = Color(0xFFD11F2F)
    val M1 = Color(0xFF168388)
    val L1 = Color(0xFFEE343F)
    val BUS = Color(0xFF00B5EF)
    val COACH = Color(0xFFC98A1B)
    val FERRY = Color(0xFF5AB031)
    val OTHER = Color(0xFF8E5BD0)

    fun livery(mode: Mode): Color = when (mode) {
        Mode.TRAIN -> T9
        Mode.METRO -> M1
        Mode.LIGHT_RAIL -> L1
        Mode.BUS -> BUS
        Mode.COACH -> COACH
        Mode.FERRY -> FERRY
        Mode.OTHER -> OTHER
    }

    private const val MIN = 60_000L

    /**
     * A stop list with [names], one stop every [everyMin] minutes and 30 s dwell, starting so the
     * vehicle left stop [leftIndex] [sinceLeftMin] minutes ago, shifted by [delayMin] (live).
     */
    fun calls(
        names: List<String>,
        leftIndex: Int,
        sinceLeftMin: Double,
        everyMin: Int = 4,
        delayMin: Int = 0,
        live: Boolean = true,
    ): List<StopCall> {
        val leftDepScheduled = NOW_MS - (sinceLeftMin * MIN).toLong() - delayMin * MIN
        return names.mapIndexed { i, name ->
            val dep = leftDepScheduled + (i - leftIndex) * everyMin * MIN
            val arr = dep - 30_000
            val d = delayMin * MIN
            StopCall(
                stopSequence = i + 1,
                stopId = "S$i",
                stopName = name,
                platform = "${i % 4 + 1}",
                platformChanged = false,
                scheduledArrivalMs = arr,
                scheduledDepartureMs = dep,
                expectedArrivalMs = if (live) arr + d else null,
                expectedDepartureMs = if (live) dep + d else null,
                skipped = false,
                passed = (if (live) dep + d else dep) <= NOW_MS,
            )
        }
    }

    val westernLine = listOf("Lidcombe", "Flemington", "Homebush", "Rhodes", "Concord West", "Strathfield", "Burwood", "Redfern", "Central")
}
