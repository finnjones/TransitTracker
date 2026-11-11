package app.transittracker.ui

import app.transittracker.model.DataStatus
import app.transittracker.model.PackStatus
import app.transittracker.ui.feature.onboarding.DownloadPhase
import app.transittracker.ui.feature.onboarding.INVITE_ALPHABET
import app.transittracker.ui.feature.onboarding.OnboardingStep
import app.transittracker.ui.feature.onboarding.cleanInviteInput
import app.transittracker.ui.feature.onboarding.downloadProgress
import app.transittracker.ui.feature.onboarding.formatInviteInput
import app.transittracker.ui.feature.onboarding.impossibleCharacters
import app.transittracker.ui.feature.onboarding.impossibleCharactersHint
import app.transittracker.ui.feature.onboarding.nextStep
import app.transittracker.ui.feature.onboarding.normaliseInviteCode
import app.transittracker.ui.feature.onboarding.stalled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure rules of onboarding: the invite code, the steps, the download words. */
class OnboardingRulesTest {
    @Test
    fun theAlphabetHasNoLookAlikes() {
        for (c in "ILOU01") assertFalse(c in INVITE_ALPHABET, "$c should not be in the alphabet")
        assertEquals(30, INVITE_ALPHABET.length)
    }

    @Test
    fun lowerCaseSpacesAndMissingDashAreAccepted() {
        assertEquals("K7M2-QX9R", normaliseInviteCode("K7M2-QX9R"))
        assertEquals("K7M2-QX9R", normaliseInviteCode("k7m2qx9r"))
        assertEquals("K7M2-QX9R", normaliseInviteCode("  k7m2 - qx9r "))
        assertEquals("K7M2-QX9R", normaliseInviteCode("k7m2 qx9r"))
        assertEquals("K7M2-QX9R", normaliseInviteCode("K7M2QX9R"))
    }

    @Test
    fun onlyEightValidCharactersAreACode() {
        assertNull(normaliseInviteCode(""))
        assertNull(normaliseInviteCode("K7M2-QX9"))
        assertNull(normaliseInviteCode("K7M2-QX9RR"), "nine characters are too many")
        // each forbidden character makes an otherwise good code invalid
        for (bad in "ILOU01") assertNull(normaliseInviteCode("K7M2QX9$bad"), "$bad")
        assertNull(normaliseInviteCode("K7M2-QX9!"))
    }

    @Test
    fun theFieldShowsTheDashAsTheUserTypes() {
        assertEquals("", formatInviteInput(""))
        assertEquals("K7M", formatInviteInput("k7m"))
        assertEquals("K7M2", formatInviteInput("k7m2"))
        assertEquals("K7M2-Q", formatInviteInput("k7m2q"))
        assertEquals("K7M2-QX9R", formatInviteInput("k7m2qx9r"))
        assertEquals("K7M2-QX9R", formatInviteInput("k7m2qx9rzzzz"), "extra characters are dropped")
        // backspacing over the dash does not get stuck on it
        assertEquals("K7M2", formatInviteInput("K7M2-"))
        // pasting a code with a dash and spaces
        assertEquals("K7M2-QX9R", formatInviteInput(" K7M2 - QX9R "))
        assertEquals("K7M2QX9R", cleanInviteInput("K7M2-QX9R"))
    }

    @Test
    fun forbiddenCharactersAreKeptVisibleAndExplained() {
        // they are not silently dropped: the user sees what was typed, and a hint says why it cannot work
        assertEquals("K0M2-L", formatInviteInput("k0m2l"))
        assertEquals(listOf('0', 'L'), impossibleCharacters("K0M2L0"))
        assertEquals("Invite codes never contain 0.", impossibleCharactersHint(listOf('0')))
        assertEquals("Invite codes never contain 0 or L.", impossibleCharactersHint(listOf('0', 'L')))
        assertEquals("Invite codes never contain 0, L or O.", impossibleCharactersHint(listOf('0', 'L', 'O')))
        assertNull(impossibleCharactersHint(emptyList()))
    }

    @Test
    fun stepsRunInOrderAndSkipNotificationsWhenAllowed() {
        assertEquals(OnboardingStep.DOWNLOAD, nextStep(OnboardingStep.INVITE, false))
        assertEquals(OnboardingStep.NOTIFICATIONS, nextStep(OnboardingStep.DOWNLOAD, false))
        assertEquals(OnboardingStep.JOURNEY, nextStep(OnboardingStep.DOWNLOAD, true))
        assertEquals(OnboardingStep.JOURNEY, nextStep(OnboardingStep.NOTIFICATIONS, true))
        assertNull(nextStep(OnboardingStep.JOURNEY, false))
        assertEquals("STEP 2 OF 4", OnboardingStep.DOWNLOAD.words)
    }

    private fun status(vararg packs: PackStatus, reachable: Boolean = true, problem: String? = null) = DataStatus(
        packs = packs.toList(), lastLiveUpdateMs = null, liveStale = false, serverReachable = reachable,
        joined = true, attribution = "", timetableProblem = problem,
    )

    @Test
    fun theCoresReasonIsShownWhenNothingIsDownloading() {
        val failed = downloadProgress(status(pack(null), problem = "The server has no timetable yet."))
        assertEquals(DownloadPhase.FAILED, failed.phase)
        assertEquals("The timetable did not arrive", failed.words)
        assertEquals("The server has no timetable yet.", failed.detail)
        assertTrue(failed.failed)

        val retrying = downloadProgress(status(pack(null, downloading = true), problem = "old reason"))
        assertEquals(DownloadPhase.DOWNLOADING, retrying.phase, "a download under way wins over the last failure")

        val offline = downloadProgress(status(pack(null), reachable = false, problem = "old reason"))
        assertEquals(DownloadPhase.OFFLINE, offline.phase)
    }

    private fun pack(version: String?, downloading: Boolean = false, update: Boolean = false) =
        PackStatus("syd-rail", version, null, updateAvailable = update, downloading = downloading)

    @Test
    fun noStatusYetMeansAskingTheServer() {
        val ui = downloadProgress(null)
        assertEquals(DownloadPhase.ASKING, ui.phase)
        assertEquals(0, ui.percent)
        assertEquals(DownloadPhase.ASKING, downloadProgress(status()).phase)
    }

    @Test
    fun progressIsByPackAndWordedPlainly() {
        val one = downloadProgress(status(pack(null, downloading = true)))
        assertEquals(DownloadPhase.DOWNLOADING, one.phase)
        assertEquals(50, one.percent)
        assertEquals("50%", one.percentWords)
        assertEquals("Downloading the timetable", one.words)

        val two = downloadProgress(status(pack("1"), pack(null, downloading = true), pack(null)))
        assertEquals(DownloadPhase.DOWNLOADING, two.phase)
        assertEquals(50, two.percent) // (1 + 0.5) / 3
        assertEquals("Downloading timetable 2 of 3", two.words)
        assertEquals("Downloading timetable 2 of 3, 50 percent", two.spoken)

        val waiting = downloadProgress(status(pack(null)))
        assertEquals(0, waiting.percent)
        assertEquals(DownloadPhase.DOWNLOADING, waiting.phase)
    }

    @Test
    fun everyPackWithAVersionIsReady() {
        val ready = downloadProgress(status(pack("1"), pack("2", update = true)))
        assertEquals(DownloadPhase.READY, ready.phase)
        assertEquals(100, ready.percent)
        assertEquals("Timetable ready", ready.words)
        assertEquals("Timetable ready", ready.spoken)
        assertFalse(ready.failed)
    }

    @Test
    fun anUnreachableServerIsOfflineUnlessTheTimetableIsAlreadyHere() {
        val offline = downloadProgress(status(pack(null, downloading = true), reachable = false))
        assertEquals(DownloadPhase.OFFLINE, offline.phase)
        assertTrue(offline.failed)
        assertTrue("Can't reach the server" in offline.words)
        assertEquals(DownloadPhase.OFFLINE, downloadProgress(status(reachable = false)).phase)
        assertEquals(DownloadPhase.READY, downloadProgress(status(pack("1"), reachable = false)).phase)
    }

    @Test
    fun aDownloadThatStopsIsStalledButAReadyOneIsNot() {
        val s = downloadProgress(status(pack(null, downloading = true))).stalled()
        assertEquals(DownloadPhase.STALLED, s.phase)
        assertTrue(s.failed)
        assertEquals(50, s.percent, "the train stays where it was")
        val ready = downloadProgress(status(pack("1")))
        assertEquals(ready, ready.stalled())
    }
}
