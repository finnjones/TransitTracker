package app.transittracker.ui.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.app.PagePreview
import app.transittracker.ui.gallery.AppShot
import app.transittracker.ui.gallery.Shot
import app.transittracker.ui.theme.DayColors

/**
 * Screenshots of the `onboarding` feature, rendered by `./gradlew :app:ui:screenshots`. The steps
 * are drawn from hand-made states (the content is stateless); "onboarding-app" is the whole app
 * on a core that has not joined, which is how the shell shows it.
 */
object OnboardingShots {
    @Composable
    private fun Page(state: OnboardingUiState) {
        PagePreview(remember { AppDeps.fake(joined = false) }) { OnboardingContent(state, OnboardingActions()) }
    }

    private val invite = OnboardingUiState()
    private val typing = invite.copy(code = "K7M2-Q", canJoin = false)
    private val ready = invite.copy(code = "K7M2-QX9R", canJoin = true)
    private val downloading = OnboardingUiState(
        step = OnboardingStep.DOWNLOAD,
        download = DownloadUi(DownloadPhase.DOWNLOADING, 0.5f, 1, 2, "Downloading timetable 2 of 2"),
    )
    private val failed = downloading.copy(
        download = DownloadUi(
            DownloadPhase.OFFLINE, 0.25f, 0, 2,
            "Can't reach the server, so the timetable has not finished",
        ),
    )
    private val done = downloading.copy(download = DownloadUi(DownloadPhase.READY, 1f, 2, 2, "Timetable ready"))
    private val notifications = OnboardingUiState(step = OnboardingStep.NOTIFICATIONS)
    private val journey = OnboardingUiState(step = OnboardingStep.JOURNEY)

    val shots: List<Shot> = listOf(
        Shot("onboarding-invite") { Page(invite) },
        Shot("onboarding-invite-typing") { Page(typing) },
        Shot("onboarding-invite-ready") { Page(ready) },
        Shot("onboarding-invite-working") { Page(ready.copy(join = JoinStatus.WORKING, canJoin = false)) },
        Shot("onboarding-invite-invalid") { Page(ready.copy(code = "BADK-2222", join = JoinStatus.INVALID)) },
        Shot("onboarding-invite-hint") { Page(invite.copy(code = "K0M2-L", codeHint = "Invite codes never contain 0 or L.")) },
        Shot("onboarding-invite-unreachable") { Page(ready.copy(join = JoinStatus.UNREACHABLE)) },
        Shot("onboarding-download-start") {
            Page(downloading.copy(download = DownloadUi(DownloadPhase.DOWNLOADING, 0.1f, 0, 2, "Downloading timetable 1 of 2")))
        },
        Shot("onboarding-download-part") { Page(downloading) },
        Shot("onboarding-download-failed") { Page(failed) },
        Shot("onboarding-download-ready") { Page(done) },
        Shot("onboarding-download-still", reduceMotion = true) { Page(downloading) },
        Shot("onboarding-notifications") { Page(notifications) },
        Shot("onboarding-journey") { Page(journey) },
        Shot("onboarding-app") { AppShot(remember { AppDeps.fake(joined = false) }) },
        Shot("onboarding-invite-day", DayColors) { Page(ready.copy(join = JoinStatus.INVALID)) },
        Shot("onboarding-download-day", DayColors) { Page(downloading) },
        Shot("onboarding-notifications-day", DayColors) { Page(notifications) },
    )
}
