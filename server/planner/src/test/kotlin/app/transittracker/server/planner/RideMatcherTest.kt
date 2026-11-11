package app.transittracker.server.planner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RideMatcherTest {
    private val matcher = RideMatcher(PlannerTestTimetable())

    private fun facts(
        line: String? = "T9",
        tripIds: List<String> = emptyList(),
        from: List<String> = listOf("2135233"),
        to: List<String> = listOf("2000336"),
        at: String = "08:02",
    ) = RideFacts(line, tripIds, from, to, listOf(local(at)))

    @Test
    fun `rule 1 matches the planner's real-time trip id on its service date`() {
        val match = matcher.match(facts(tripIds = listOf("121A.1450.133.8.T.8.86753001")))!!
        assertEquals("121A.1450.133.8.T.8.86753001", match.tripId)
        assertEquals(DAY, match.serviceDate)
        assertEquals(1, match.rule)
        assertEquals("2135233", match.boardingStopId)
        assertEquals("2000336", match.alightingStopId)
    }

    @Test
    fun `rule 1 needs the trip to leave at the planned time`() {
        // The id exists, but that trip leaves at 08:02, not 08:17: falls through to rule 2.
        val match = matcher.match(facts(tripIds = listOf("121A.1450.133.8.T.8.86753001"), at = "08:17"))!!
        assertEquals("121A.1450.133.8.T.8.86753002", match.tripId)
        assertEquals(2, match.rule)
    }

    @Test
    fun `rule 2 matches the only trip of the line at that time between those stations`() {
        // Any platform of the station counts: the planner may name the station only.
        val match = matcher.match(facts(from = listOf("213510"), to = listOf("200060"), at = "08:17"))!!
        assertEquals("121A.1450.133.8.T.8.86753002", match.tripId)
        assertEquals("2135233", match.boardingStopId)
    }

    @Test
    fun `never guesses`() {
        assertNull(matcher.match(facts(at = "08:32")), "two T9s leave Strathfield at 08:32")
        assertNull(matcher.match(facts(at = "07:58")), "no trip at that time")
        assertNull(matcher.match(facts(line = "T1")), "another line")
        assertNull(matcher.match(facts(line = null)), "no line name")
        assertNull(matcher.match(facts(from = emptyList())), "boarding stop not in the timetable")
        assertNull(matcher.match(facts(to = listOf("2121231"))), "does not call at the alighting station")
        assertNull(matcher.match(facts(tripIds = listOf("unknown"), line = null)))
    }
}
