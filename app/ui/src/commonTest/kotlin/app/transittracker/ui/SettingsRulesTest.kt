package app.transittracker.ui

import app.transittracker.model.DataStatus
import app.transittracker.model.PackStatus
import app.transittracker.ui.feature.settings.PackState
import app.transittracker.ui.feature.settings.TestOutcome
import app.transittracker.ui.feature.settings.agoWords
import app.transittracker.ui.feature.settings.copyrightLine
import app.transittracker.ui.feature.settings.countWords
import app.transittracker.ui.feature.settings.liveWords
import app.transittracker.ui.feature.settings.modifiedNote
import app.transittracker.ui.feature.settings.packRow
import app.transittracker.ui.feature.settings.packTitle
import app.transittracker.ui.feature.settings.permissionWords
import app.transittracker.ui.feature.settings.testWords
import app.transittracker.ui.feature.settings.validToWords
import app.transittracker.ui.format.Lamp
import app.transittracker.ui.platform.PermissionState
import app.transittracker.ui.theme.ColorRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The words Settings uses for data, packs, permission and test results. */
class SettingsRulesTest {
    private val now = 1_794_343_080_000L

    private fun status(
        reachable: Boolean = true, stale: Boolean = false, last: Long? = now - 12_000, packs: List<PackStatus> = emptyList(),
    ) = DataStatus(packs, last, stale, reachable, true, "Data: Transport for NSW")

    @Test
    fun agesAreWrittenInPlainWords() {
        assertEquals("just now", agoWords(1_000))
        assertEquals("12 seconds ago", agoWords(12_000))
        assertEquals("1 minute ago", agoWords(60_000))
        assertEquals("3 minutes ago", agoWords(3 * 60_000L + 5_000))
        assertEquals("1 hour ago", agoWords(3_600_000))
        assertEquals("5 hours ago", agoWords(5 * 3_600_000L))
        assertEquals("yesterday", agoWords(30 * 3_600_000L))
        assertEquals("4 days ago", agoWords(4 * 86_400_000L))
        assertEquals("just now", agoWords(-5_000), "a clock a little behind is not a negative age")
    }

    @Test
    fun liveDataIsUpToDateStaleOrOffline() {
        val live = liveWords(status(), now)
        assertEquals("Live data is up to date.", live.headline)
        assertEquals("Last live update 12 seconds ago", live.lastUpdate)
        assertEquals(Lamp.CIRCLE, live.lamp)
        assertEquals(ColorRole.GO, live.role)

        val stale = liveWords(status(stale = true, last = now - 5 * 60_000), now)
        assertEquals("Live data is out of date.", stale.headline)
        assertEquals(Lamp.TRIANGLE, stale.lamp)
        assertEquals("Last live update 5 minutes ago", stale.lastUpdate)

        val offline = liveWords(status(reachable = false, last = null), now)
        assertTrue(offline.headline.startsWith("Offline"))
        assertEquals("No live update yet", offline.lastUpdate)
        assertEquals(ColorRole.INFO, offline.role, "offline is information, not an error")

        assertEquals("Checking", liveWords(null, now).headline)
    }

    @Test
    fun packsShowVersionValidityAndState() {
        val ok = packRow(PackStatus("syd-rail", "20261110", "20261231", false), "20261111")
        assertEquals("Syd rail", ok.title)
        assertEquals("Version 20261110", ok.version)
        assertEquals("Valid to 31 Dec 2026", ok.validTo)
        assertEquals(PackState.UP_TO_DATE, ok.state)
        assertEquals("Up to date", ok.stateWords)
        assertEquals("Syd rail. Up to date. Version 20261110. Valid to 31 Dec 2026.", ok.spoken)

        assertEquals("Update available", packRow(PackStatus("p", "1", "20261231", true), "20261111").stateWords)
        assertEquals("Downloading", packRow(PackStatus("p", "1", "20261231", true, downloading = true), "20261111").stateWords)
        val missing = packRow(PackStatus("p", null, null, false, downloading = false), "20261111")
        assertEquals(PackState.MISSING, missing.state)
        assertEquals("Not downloaded yet", missing.version)
        assertEquals("No end date", missing.validTo)
    }

    @Test
    fun aPackPastItsEndDateIsOutOfDateEvenIfNoUpdateIsListed() {
        assertEquals(PackState.OUT_OF_DATE, packRow(PackStatus("p", "1", "20260101", false), "20261111").state)
        assertEquals(PackState.UP_TO_DATE, packRow(PackStatus("p", "1", "20261111", false), "20261111").state, "valid to today is still valid")
        assertEquals(PackState.OUT_OF_DATE, packRow(PackStatus("p", "1", "20261110", true), "20261111").state, "out of date outranks update available")
    }

    @Test
    fun datesAndNamesAreTidied() {
        assertEquals("Valid to 1 Jan 2027", validToWords("20270101"))
        assertEquals("No end date", validToWords(null))
        assertEquals("No end date", validToWords(""))
        assertEquals("No end date", validToWords("soon"))
        assertEquals("Syd rail", packTitle("syd-rail"))
        assertEquals("Metro north", packTitle("metro_north"))
        assertEquals("Timetable", packTitle(" "))
    }

    @Test
    fun permissionStatesAreSaidInWords() {
        val on = permissionWords(PermissionState.GRANTED)
        assertEquals("ON", on.state)
        assertEquals(ColorRole.GO, on.role)
        val off = permissionWords(PermissionState.DENIED)
        assertEquals("OFF", off.state)
        assertTrue("phone's settings" in off.sentence)
        assertEquals(ColorRole.CAUTION, off.role)
        val ask = permissionWords(PermissionState.NOT_ASKED)
        assertEquals("NOT SET", ask.state)
        assertTrue("Allow" in ask.sentence)
        assertEquals(3, setOf(on.sentence, off.sentence, ask.sentence).size)
    }

    @Test
    fun everyTestOutcomeHasPlainWordsAndNoWatchIsSaidPlainly() {
        for (o in TestOutcome.entries) assertTrue(testWords(o).isNotBlank())
        assertTrue("no watch" in testWords(TestOutcome.NO_WATCH).lowercase())
        assertTrue("Notify tab" in testWords(TestOutcome.NO_WATCH))
        assertEquals("Can't reach the server.", testWords(TestOutcome.UNREACHABLE))
        assertEquals("The server could not send it. Try again in a moment.", testWords(TestOutcome.REFUSED))
        assertTrue("reopen the app" in testWords(TestOutcome.REFUSED, "Device has no push token"))
        assertTrue("reopen the app" in testWords(TestOutcome.REFUSED, "Push token no longer valid; reopen the app"))
        assertEquals("The server could not send it: HTTP 429", testWords(TestOutcome.REFUSED, "HTTP 429"))
    }

    @Test
    fun counts() {
        assertEquals("No places yet", countWords(0, "place"))
        assertEquals("1 place", countWords(1, "place"))
        assertEquals("3 routes", countWords(3, "route"))
    }

    @Test
    fun licenceFilesGiveTheirCopyrightLine() {
        val plain = "Copyright 2024 The Doto Project Authors (https://x)\n\nThis Font Software is licensed under the SIL"
        assertEquals("Copyright 2024 The Doto Project Authors (https://x)", copyrightLine(plain))
        assertNull(modifiedNote(plain))
        val modified = "MODIFIED VERSION. The files were changed\nfor legibility.\n\nCopyright 2021 The Pixelify Sans Project Authors\n"
        assertEquals("Copyright 2021 The Pixelify Sans Project Authors", copyrightLine(modified))
        assertEquals("Modified for legibility by this app.", modifiedNote(modified))
        assertEquals("", copyrightLine(""))
    }
}
