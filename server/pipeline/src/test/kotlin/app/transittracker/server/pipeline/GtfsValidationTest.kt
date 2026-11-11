package app.transittracker.server.pipeline

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GtfsValidationTest {
    private val tmp: Path = Files.createTempDirectory("gtfs-validation")

    @AfterTest
    fun cleanUp() {
        tmp.toFile().deleteRecursively()
    }

    private fun reportFor(edit: (String, String) -> String?): ValidationReport {
        val zip = Fixtures.writeZip(tmp.resolve("feed-${System.nanoTime()}.zip"), edit = edit)
        return Fixtures.load(zip, LocalDate.of(2026, 11, 11)).second
    }

    private fun ValidationReport.codes(severity: Severity) = counts.keys.filter { it.first == severity }.map { it.second }.toSet()

    @Test
    fun fixtureIsClean() {
        val (feed, report) = Fixtures.load(Fixtures.miniRail, LocalDate.of(2026, 11, 11))
        assertFalse(report.hasErrors, report.summary())
        assertEquals(0, report.warningCount, report.summary())
        assertEquals(22, feed.stops.size)
        assertEquals(18, feed.trips.size)
        assertEquals("Wynyard Station, York St", feed.stopsById.getValue("WYN").name)
        assertEquals(listOf("mini-rail" to "mini-rail-1"), feed.sources)
        // Missing intermediate time is kept as null for the builder to interpolate.
        assertEquals(null, feed.stopTimes.getValue("T1.N.1700")[2].arrival)
    }

    @Test
    fun zippedFixtureLoadsTheSame() {
        val zip = Fixtures.writeZip(tmp.resolve("mini-rail.zip"))
        val (feed, report) = Fixtures.load(zip)
        assertFalse(report.hasErrors, report.summary())
        assertEquals(18, feed.trips.size)
    }

    @Test
    fun missingRequiredFileIsAnError() {
        val report = reportFor { name, text -> if (name == "stops.txt") null else text }
        assertTrue("missing_required_file" in report.codes(Severity.ERROR))
        assertTrue("unknown_stop" in report.codes(Severity.ERROR))
    }

    @Test
    fun missingRequiredColumnIsAnError() {
        val report = reportFor { name, text -> if (name == "trips.txt") text.replace("service_id", "svc") else text }
        assertTrue("missing_required_column" in report.codes(Severity.ERROR))
    }

    @Test
    fun referentialIntegrityErrors() {
        val report = reportFor { name, text ->
            when (name) {
                "stop_times.txt" -> text.replace("M1.N.0705,07:11:00,07:11:30,MPL_P1", "M1.N.0705,07:11:00,07:11:30,NOWHERE")
                "trips.txt" -> text.replace("T1,WD,T1.S.1730", "T9,WD,T1.S.1730").replace("M1,WE,M1.N.WE.0930", "M1,XX,M1.N.WE.0930")
                "transfers.txt" -> text + "CEN_P1,GHOST,2,60\n"
                else -> text
            }
        }
        val errors = report.codes(Severity.ERROR)
        assertTrue("unknown_stop" in errors, report.summary())
        assertTrue("unknown_route" in errors, report.summary())
        assertTrue("unknown_service" in errors, report.summary())
        assertTrue("transfer_unknown_stop" in errors, report.summary())
    }

    @Test
    fun nonMonotonicStopTimesAreErrors() {
        val report = reportFor { name, text ->
            if (name == "stop_times.txt") {
                text.replace("T1.N.0700,07:11:00,07:11:00,NSY_P1", "T1.N.0700,07:01:00,07:01:00,NSY_P1")
                    .replace("T1.N.0730,07:36:00,07:36:30,WYN_P1", "T1.N.0730,07:36:30,07:36:00,WYN_P1")
                    .replace("T1.N.0730,07:41:00,07:41:00,NSY_P1,4", "T1.N.0730,07:41:00,07:41:00,NSY_P1,3")
            } else {
                text
            }
        }
        val errors = report.codes(Severity.ERROR)
        assertTrue("decreasing_time" in errors, report.summary())
        assertTrue("departure_before_arrival" in errors, report.summary())
        assertTrue("duplicate_stop_sequence" in errors, report.summary())
    }

    @Test
    fun calendarSanity() {
        val report = reportFor { name, text ->
            when (name) {
                "calendar.txt" -> text.replace("WE,0,0,0,0,0,1,1,20260101,20271231", "WE,0,0,0,0,0,1,1,20271231,20260101")
                "calendar_dates.txt" -> text + "WD,20261226,3\nWD,2026-12-27,1\n"
                else -> text
            }
        }
        val errors = report.codes(Severity.ERROR)
        assertTrue("calendar_end_before_start" in errors, report.summary())
        assertTrue("bad_exception_type" in errors, report.summary())
        assertTrue("bad_date" in errors, report.summary())
    }

    @Test
    fun badValuesAndExpiredFeed() {
        val report = reportFor { name, text ->
            when (name) {
                "stop_times.txt" -> text.replace("T1.N.0700,07:03:00", "T1.N.0700,7h03")
                "stops.txt" -> text.replace("-33.8737,151.2069", "north,151.2069")
                "calendar.txt" -> text.replace("20271231", "20260601")
                "calendar_dates.txt" -> "service_id,date,exception_type\n"
                else -> text
            }
        }
        val errors = report.codes(Severity.ERROR)
        assertTrue("bad_time" in errors, report.summary())
        assertTrue("bad_number" in errors, report.summary())
        assertTrue("feed_expired" in report.codes(Severity.WARNING), report.summary())
    }

    @Test
    fun mobilityDataReportIsParsed() {
        val json = """
            {"summary":{"validatorVersion":"7.0.0"},
             "notices":[
               {"code":"stop_time_with_arrival_before_previous_departure_time","severity":"ERROR","totalNotices":3,"sampleNotices":[]},
               {"code":"unused_shape","severity":"WARNING","totalNotices":1},
               {"code":"feed_info_lang_and_agency_lang_mismatch","severity":"INFO","totalNotices":1}
             ]}
        """.trimIndent()
        val builder = ValidationReport.Builder()
        MobilityDataValidator.parseReport(json, "x.zip", builder)
        val report = builder.build()
        assertEquals(1, report.errorCount)
        assertEquals(1, report.warningCount)
        assertTrue(report.errors.single().message.startsWith("3 notice(s)"))
    }

    @Test
    fun issuesAreCappedPerCodeButCounted() {
        val builder = ValidationReport.Builder(maxPerCode = 2)
        repeat(5) { builder.error("x", "e$it") }
        val report = builder.build()
        assertEquals(2, report.issues.size)
        assertEquals(5, report.errorCount)
    }
}
