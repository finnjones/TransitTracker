package app.transittracker.ui.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.transittracker.ui.app.LocalAppDeps
import app.transittracker.ui.components.ButtonKind
import app.transittracker.ui.components.FieldInput
import app.transittracker.ui.components.Label
import app.transittracker.ui.components.LabelSize
import app.transittracker.ui.components.PixelButton
import app.transittracker.ui.components.PixelIcon
import app.transittracker.ui.components.PixelIcons
import app.transittracker.ui.components.PixelPanel
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.components.PixelTextField
import app.transittracker.ui.components.SectionLabel
import app.transittracker.ui.components.StatusLamp
import app.transittracker.ui.format.Lamp
import app.transittracker.ui.motion.PageTransition
import app.transittracker.ui.motion.flapRow
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.nav.Route
import app.transittracker.ui.nav.Tab
import app.transittracker.ui.state.rememberStateHolder
import app.transittracker.ui.theme.TtTheme

/** What onboarding can ask for; the screen maps these to the holder and to navigation. */
class OnboardingActions(
    val onCodeChange: (String) -> Unit = {},
    val join: () -> Unit = {},
    val retryDownload: () -> Unit = {},
    val continueFromDownload: () -> Unit = {},
    val allowNotifications: () -> Unit = {},
    val skipNotifications: () -> Unit = {},
    val planJourney: () -> Unit = {},
    val skipJourney: () -> Unit = {},
)

/**
 * Onboarding: invite, timetable download, notifications, first journey (plan 5.1). Shown by the
 * shell until the device has joined; it ends with the doors onto Home, which is how the shell
 * lets go of it (README "Navigation": `resetTo(Tab.TRIPS, PageTransition.Doors)`).
 */
@Composable
fun OnboardingScreen() {
    val deps = LocalAppDeps.current
    val nav = LocalNavigator.current
    // The step survives the planner covering this page; the holder starts from it.
    var savedStep by rememberSaveable { mutableStateOf(OnboardingStep.INVITE.name) }
    val holder = rememberStateHolder { scope ->
        OnboardingStateHolder(deps.core, deps.platform, scope, OnboardingStep.valueOf(savedStep))
    }
    val state by holder.state.collectAsState()
    LaunchedEffect(state.step) { savedStep = state.step.name }
    val actions = remember(nav, holder) {
        OnboardingActions(
            onCodeChange = holder::onCodeChange,
            join = holder::join,
            retryDownload = holder::retryDownload,
            continueFromDownload = holder::continueFromDownload,
            allowNotifications = holder::allowNotifications,
            skipNotifications = holder::skipNotifications,
            planJourney = { nav.push(Route.PlanJourney(fromOnboarding = true)) },
            skipJourney = { nav.resetTo(Tab.TRIPS, PageTransition.Doors) },
        )
    }
    OnboardingContent(state, actions)
}

/** The onboarding page for [state]; stateless. */
@Composable
fun OnboardingContent(state: OnboardingUiState, actions: OnboardingActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(TtTheme.colors.screen)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.flapRow(0), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PixelText("Welcome", Modifier.semantics { heading() }, TtTheme.type.title22)
                StepIndicator(state.step)
            }
            Column(Modifier.flapRow(1), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (state.step) {
                    OnboardingStep.INVITE -> InviteStep(state, actions)
                    OnboardingStep.DOWNLOAD -> DownloadStep(state.download, actions)
                    OnboardingStep.NOTIFICATIONS -> NotificationsStep(state, actions)
                    OnboardingStep.JOURNEY -> JourneyStep(actions)
                }
            }
        }
    }
}

@Composable
private fun StepTitle(title: String, sentence: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PixelText(title, Modifier.semantics { heading() }, TtTheme.type.title20)
        PixelText(sentence, style = TtTheme.type.reading15, color = TtTheme.colors.textReading)
    }
}

// ---------------------------------------------------------------- 1. invite

@Composable
private fun InviteStep(state: OnboardingUiState, actions: OnboardingActions) {
    val c = TtTheme.colors
    StepTitle("Join with your invite", "TransitTracker is private. Type the code from the invite you were sent.")
    InviteTicket {
        SectionLabel("INVITE CODE")
        PixelTextField(
            value = state.code,
            onValueChange = actions.onCodeChange,
            label = "Invite code, eight characters",
            placeholder = "XXXX-XXXX",
            input = FieldInput.CODE,
            enabled = state.join != JoinStatus.WORKING,
            height = 56.dp,
            style = TtTheme.type.title22,
            onSubmit = { if (state.canJoin) actions.join() },
        )
        state.codeHint?.let { hint ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusLamp(Lamp.RING, c.info, size = 10.dp)
                PixelText(hint, Modifier.weight(1f), TtTheme.type.reading15, c.textReading)
            }
        }
        when (state.join) {
            JoinStatus.INVALID -> ResultLine(INVALID_CODE_WORDS, error = true)
            JoinStatus.UNREACHABLE -> {
                ResultLine(UNREACHABLE_WORDS, error = true)
                PixelButton("RETRY", actions.join, Modifier.fillMaxWidth(), kind = ButtonKind.PRIMARY)
            }
            else -> {}
        }
        if (state.join != JoinStatus.UNREACHABLE) {
            PixelButton(
                if (state.join == JoinStatus.WORKING) "JOINING..." else "JOIN",
                actions.join, Modifier.fillMaxWidth(), kind = ButtonKind.PRIMARY, enabled = state.canJoin,
            )
        }
    }
}

/** A result line under a button: a lamp and a plain sentence. */
@Composable
private fun ResultLine(text: String, error: Boolean) {
    val c = TtTheme.colors
    val color = if (error) c.stop else c.go
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = text },
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusLamp(if (error) Lamp.CROSS else Lamp.CIRCLE, color, size = 14.dp)
        PixelText(text, Modifier.weight(1f), TtTheme.type.row15Strong, color)
    }
}

// ---------------------------------------------------------------- 2. download

@Composable
private fun DownloadStep(download: DownloadUi, actions: OnboardingActions) {
    val c = TtTheme.colors
    StepTitle(
        "Getting the timetable",
        "Your phone keeps the whole timetable so it can show you trains with no signal. " +
            "It needs the internet once now, then stays up to date by itself.",
    )
    PixelPanel(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = download.spoken }) {
        DownloadScene(download.fraction, ready = download.phase == DownloadPhase.READY, failed = download.failed)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val (lamp, color) = when {
                    download.failed -> Lamp.CROSS to c.stop
                    download.phase == DownloadPhase.READY -> Lamp.CIRCLE to c.go
                    else -> Lamp.RING to c.info
                }
                StatusLamp(lamp, color, size = 14.dp)
                PixelText(download.words, Modifier.weight(1f), TtTheme.type.row15Strong, if (download.failed) c.stop else c.text)
            }
            PixelText(download.percentWords, style = TtTheme.type.title20, color = c.text)
        }
    }
    when {
        download.phase == DownloadPhase.READY ->
            PixelButton("CONTINUE", actions.continueFromDownload, Modifier.fillMaxWidth(), kind = ButtonKind.PRIMARY)
        download.failed -> {
            val carryOn = "You can carry on, but the app has no timetable until this finishes."
            PixelText(
                when {
                    download.phase == DownloadPhase.OFFLINE -> "Connect to the internet and try again. $carryOn"
                    download.detail != null -> "${download.detail} $carryOn"
                    else -> "Nothing has arrived for a while. Try again. $carryOn"
                },
                style = TtTheme.type.reading15, color = c.textReading,
            )
            PixelButton("RETRY", actions.retryDownload, Modifier.fillMaxWidth(), kind = ButtonKind.PRIMARY)
            PixelButton("CARRY ON WITHOUT IT", actions.continueFromDownload, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
        }
        else -> PixelText(
            "This works on any network, including mobile data. Please keep the app open.",
            style = TtTheme.type.reading15, color = c.textSecondary,
        )
    }
}

// ---------------------------------------------------------------- 3. notifications

@Composable
private fun NotificationsStep(state: OnboardingUiState, actions: OnboardingActions) {
    val c = TtTheme.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PixelIcon(PixelIcons.Bell, tint = c.text, size = 64.dp)
    }
    StepTitle(
        "Know before you leave",
        "TransitTracker can tell you when a train you use is late, cancelled or changes platform.",
    )
    PixelButton("ALLOW", actions.allowNotifications, Modifier.fillMaxWidth(), kind = ButtonKind.PRIMARY)
    PixelButton("NOT NOW", actions.skipNotifications, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
    PixelText(
        if (state.notificationsDenied) "Your phone has notifications turned off for this app. ALLOW opens its settings."
        else "You can change this later in Settings.",
        style = TtTheme.type.reading15, color = c.textSecondary,
    )
}

// ---------------------------------------------------------------- 4. first journey

@Composable
private fun JourneyStep(actions: OnboardingActions) {
    val c = TtTheme.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        PixelIcon(PixelIcons.MapPin, tint = c.text, size = 64.dp)
    }
    StepTitle(
        "Your first journey",
        "Say where you start and where you are going, then save it as a route. Your next trains will be waiting on the home screen.",
    )
    PixelButton("PLAN A JOURNEY", actions.planJourney, Modifier.fillMaxWidth(), kind = ButtonKind.PRIMARY)
    PixelButton("SKIP", actions.skipJourney, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
    Label("YOU CAN ADD ROUTES ANY TIME FROM THE TRIPS TAB", color = c.textSecondary, size = LabelSize.L10)
}
