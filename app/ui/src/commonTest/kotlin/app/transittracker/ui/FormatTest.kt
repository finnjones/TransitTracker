package app.transittracker.ui

import androidx.compose.ui.graphics.Color
import app.transittracker.model.DepartureStatus
import app.transittracker.model.Occupancy
import app.transittracker.ui.format.CountdownText
import app.transittracker.ui.format.Lamp
import app.transittracker.ui.format.badgeTextColor
import app.transittracker.ui.format.carCountWords
import app.transittracker.ui.format.contrastRatio
import app.transittracker.ui.format.countdown
import app.transittracker.ui.format.countdownMmSs
import app.transittracker.ui.format.formatClock
import app.transittracker.ui.format.formatDayDate
import app.transittracker.ui.format.occupancyPresentation
import app.transittracker.ui.format.parseLineColor
import app.transittracker.ui.format.platformPresentation
import app.transittracker.ui.format.statusPresentation
import app.transittracker.ui.settings.ClockFormat
import app.transittracker.ui.theme.ColorRole
import app.transittracker.ui.theme.DayColors
import app.transittracker.ui.theme.NightColors
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormatTest {
    private val sydney = TimeZone.of("Australia/Sydney")

    /** 2026-11-11 07:38:00 in Sydney (UTC+11). */
    private val now = 1_794_343_080_000L

    @Test
    fun statusTable() {
        statusPresentation(DepartureStatus.ON_TIME).let {
            assertEquals(Lamp.CIRCLE, it.lamp); assertEquals("ON TIME", it.words); assertEquals(ColorRole.GO, it.role)
        }
        statusPresentation(DepartureStatus.LATE, 240).let {
            assertEquals(Lamp.TRIANGLE, it.lamp); assertEquals("4 MIN LATE", it.words); assertEquals(ColorRole.CAUTION, it.role)
            assertTrue(it.strikeScheduledTime); assertEquals("4 minutes late", it.spoken)
        }
        assertEquals("1 MIN EARLY", statusPresentation(DepartureStatus.EARLY, -50).words)
        statusPresentation(DepartureStatus.CANCELLED).let {
            assertEquals(Lamp.CROSS, it.lamp); assertEquals(ColorRole.STOP, it.role); assertTrue(it.strikeRow)
        }
        statusPresentation(DepartureStatus.ADDED).let { assertEquals(Lamp.PLUS, it.lamp); assertEquals("EXTRA SERVICE", it.words) }
        statusPresentation(DepartureStatus.TIMETABLED).let {
            assertEquals(Lamp.RING, it.lamp); assertEquals("TIMETABLE", it.words); assertEquals(ColorRole.INFO, it.role)
        }
    }

    @Test
    fun staleOverridesLiveButNotCancelled() {
        statusPresentation(DepartureStatus.LATE, 300, stale = true, dataAgeMs = 180_000).let {
            assertEquals(Lamp.RING, it.lamp); assertEquals("STALE · 3 MIN OLD", it.words)
            assertEquals(ColorRole.SECONDARY, it.role); assertEquals("~", it.countdownPrefix)
        }
        assertEquals("STALE · 45S OLD", statusPresentation(DepartureStatus.ON_TIME, stale = true, dataAgeMs = 45_000).words)
        assertEquals("CANCELLED", statusPresentation(DepartureStatus.CANCELLED, stale = true).words)
        assertEquals("TIMETABLE", statusPresentation(DepartureStatus.TIMETABLED, stale = true).words)
    }

    @Test
    fun platformOccupancyCars() {
        assertEquals("NOW PLAT", platformPresentation("6", true).prefix)
        assertEquals("PLAT", platformPresentation("3", false).prefix)
        assertNull(occupancyPresentation(Occupancy.UNKNOWN))
        assertEquals(1, occupancyPresentation(Occupancy.MANY_SEATS_AVAILABLE)!!.filled)
        assertEquals("FEW SEATS", occupancyPresentation(Occupancy.FEW_SEATS_AVAILABLE)!!.words)
        assertEquals("STANDING ONLY", occupancyPresentation(Occupancy.STANDING_ROOM_ONLY)!!.words)
        assertEquals("FULL", occupancyPresentation(Occupancy.FULL)!!.words)
        assertEquals("8 CARS", carCountWords(8))
        assertEquals("1 CAR", carCountWords(1))
    }

    @Test
    fun clockFormats() {
        assertEquals("07:38", formatClock(now, sydney))
        assertEquals("7:38 AM", formatClock(now, sydney, ClockFormat.H12))
        assertEquals("7:38", formatClock(now, sydney, ClockFormat.H12, withSuffix = false))
        assertEquals("19:38", formatClock(now + 12 * 3_600_000, sydney))
        assertEquals("7:38 PM", formatClock(now + 12 * 3_600_000, sydney, ClockFormat.H12))
        assertEquals("12:08 AM", formatClock(now + (16 * 60 + 30) * 60_000L, sydney, ClockFormat.H12))
        assertEquals("WED 11 NOV", formatDayDate(now, sydney))
    }

    @Test
    fun countdownRules() {
        countdown(now + 59_999, now, sydney).let { assertEquals("NOW", it.value); assertNull(it.unit); assertEquals(CountdownText.Kind.NOW, it.kind) }
        assertEquals("NOW", countdown(now - 30_000, now, sydney).value)
        countdown(now + 4 * 60_000 + 59_000, now, sydney).let { assertEquals("4", it.value); assertEquals("MIN", it.unit); assertEquals("leaves in 4 minutes", it.spoken) }
        assertEquals("1", countdown(now + 60_000, now, sydney).value)
        assertEquals("60", countdown(now + 60 * 60_000 + 59_000, now, sydney).value)
        countdown(now + 61 * 60_000, now, sydney).let { assertEquals("08:39", it.value); assertEquals(CountdownText.Kind.CLOCK, it.kind) }
        assertEquals("8:39 AM", countdown(now + 61 * 60_000, now, sydney, ClockFormat.H12).value)
        countdown(now + 3 * 60_000, now, sydney, prefix = "~").let { assertEquals("~3", it.value); assertEquals("leaves in about 3 minutes", it.spoken) }
    }

    @Test
    fun minutesAndSeconds() {
        assertEquals("04:59", countdownMmSs(now + 299_000, now))
        assertEquals("00:07", countdownMmSs(now + 7_400, now))
        assertEquals("00:00", countdownMmSs(now - 5_000, now))
        assertEquals("1:02:03", countdownMmSs(now + 3_723_000, now))
    }

    @Test
    fun badgeTextFollowsContrast() {
        // Plan 2.4: white on T8 green is 3.9 : 1, so T8 gets black.
        assertEquals(Color.Black, badgeTextColor(parseLineColor("00954C")!!, Color.White))
        assertEquals(Color.White, badgeTextColor(parseLineColor("005AA3")!!))
        assertEquals(Color.Black, badgeTextColor(parseLineColor("F99D1C")!!))
        // The feed's colour is kept when it passes.
        assertEquals(Color.White, badgeTextColor(parseLineColor("D11F2F")!!, Color.White))
        assertNull(parseLineColor("nope"))
        assertNull(parseLineColor(null))
    }

    @Test
    fun nightContrastMatchesPlan() {
        // Plan 2.4 lists the calculated values; check they hold at 4.5 : 1 or better.
        for (c in listOf(NightColors.text, NightColors.textSecondary, NightColors.go, NightColors.caution, NightColors.stop, NightColors.info)) {
            assertTrue(contrastRatio(c, NightColors.screen) >= 4.5, "$c on screen")
            assertTrue(contrastRatio(c, NightColors.panel) >= 4.5, "$c on panel")
        }
        assertEquals(16.6, contrastRatio(NightColors.text, NightColors.screen), 0.1)
        assertEquals(6.4, contrastRatio(NightColors.stop, NightColors.screen), 0.1)
    }

    @Test
    fun dayStatusColoursPass() {
        for (c in listOf(DayColors.text, DayColors.textSecondary, DayColors.go, DayColors.caution, DayColors.stop, DayColors.info)) {
            assertTrue(contrastRatio(c, DayColors.screen) >= 4.5, "$c on day screen: ${contrastRatio(c, DayColors.screen)}")
            assertTrue(contrastRatio(c, DayColors.panel) >= 4.5, "$c on day panel")
        }
    }
}
