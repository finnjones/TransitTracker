package app.transittracker.ui.feature.onboarding

import app.transittracker.model.JoinResult
import app.transittracker.model.TransitCore
import app.transittracker.ui.platform.AccountService
import app.transittracker.ui.platform.PermissionState
import app.transittracker.ui.platform.PlatformServices
import app.transittracker.ui.platform.SignInResult
import app.transittracker.ui.state.StateHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the onboarding screen draws. */
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.INVITE,
    /** The first step signs in with an account instead of asking for an invite code. */
    val signIn: Boolean = false,
    /** The server's or the sign-in's own words for [JoinStatus.NOT_ALLOWED] and [JoinStatus.FAILED]. */
    val joinMessage: String? = null,
    /** What the code field shows, dash included. */
    val code: String = "",
    val canJoin: Boolean = false,
    val join: JoinStatus = JoinStatus.IDLE,
    /** "Invite codes never contain O or 0." while the code holds such a character. */
    val codeHint: String? = null,
    val download: DownloadUi = DownloadUi(),
    /** Refused for good: ALLOW opens the phone's settings instead of the prompt. */
    val notificationsDenied: Boolean = false,
)

/**
 * Onboarding (plan 5.1): signs in with an account (or, in builds without sign-in, joins with the
 * invite code), follows the timetable download, asks for notifications. After a sign-in that
 * brought saved data down from the account, [onRestored] lets the app read it again. Opening the planner and leaving for Home are navigation, so they stay in the
 * screen. The screen keeps the step in saved state and passes it back as [initialStep], so coming
 * back from the planner lands on the last step.
 */
class OnboardingStateHolder(
    private val core: TransitCore,
    private val platform: PlatformServices,
    scope: CoroutineScope,
    initialStep: OnboardingStep = OnboardingStep.INVITE,
    private val stallMs: Long = STALL_MS,
    private val onRestored: suspend () -> Unit = {},
) : StateHolder<OnboardingUiState>(scope) {
    private val accounts: AccountService? = platform.accounts
    private val step = MutableStateFlow(initialStep)
    private val rawCode = MutableStateFlow("")
    private val join = MutableStateFlow(JoinStatus.IDLE)
    private val joinMessage = MutableStateFlow<String?>(null)
    private val attempt = MutableStateFlow(0)
    private val stalled = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val download = attempt.flatMapLatest { core.dataStatus.map { downloadProgress(it) } }
        .distinctUntilChanged()

    init {
        // Reachable but silent for a while: say so, so RETRY appears instead of an endless track.
        scope.launch {
            combine(download, step) { ui, s -> ui to s }.distinctUntilChanged().collectLatest { (ui, s) ->
                stalled.value = false
                if (s == OnboardingStep.DOWNLOAD && (ui.phase == DownloadPhase.DOWNLOADING || ui.phase == DownloadPhase.ASKING)) {
                    delay(stallMs)
                    stalled.value = true
                }
            }
        }
    }

    private class Basics(val step: OnboardingStep, val code: String, val join: JoinStatus, val message: String?)

    private val basics = combine(step, rawCode, join, joinMessage) { s, c, j, m -> Basics(s, c, j, m) }

    override val state: StateFlow<OnboardingUiState> = combine(
        basics, download, stalled, platform.notificationPermission,
    ) { b, dl, isStalled, permission ->
        OnboardingUiState(
            step = b.step,
            signIn = accounts != null,
            joinMessage = b.message,
            code = formatInviteInput(b.code),
            canJoin = (accounts != null || normaliseInviteCode(b.code) != null) && b.join != JoinStatus.WORKING,
            join = b.join,
            codeHint = impossibleCharactersHint(impossibleCharacters(b.code)),
            download = if (isStalled) dl.stalled() else dl,
            notificationsDenied = permission == PermissionState.DENIED,
        )
    }.stateIn(scope, SharingStarted.Eagerly, OnboardingUiState(step = initialStep, signIn = accounts != null))

    /** The user typed or pasted [raw]; an old "invalid code" message goes away. */
    fun onCodeChange(raw: String) {
        rawCode.value = cleanInviteInput(raw)
        if (join.value == JoinStatus.INVALID || join.value == JoinStatus.UNREACHABLE) join.value = JoinStatus.IDLE
    }

    /** JOIN or SIGN IN (and RETRY after "Can't reach the server"). */
    fun join() {
        if (accounts != null) return signIn(accounts)
        val code = normaliseInviteCode(rawCode.value) ?: return
        if (join.value == JoinStatus.WORKING) return
        join.value = JoinStatus.WORKING
        scope.launch {
            join.value = try {
                when (core.join(code)) {
                    is JoinResult.Joined -> {
                        step.value = OnboardingStep.DOWNLOAD
                        JoinStatus.IDLE
                    }
                    JoinResult.InvalidCode, is JoinResult.NotAllowed -> JoinStatus.INVALID
                    is JoinResult.Failed -> JoinStatus.UNREACHABLE
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                JoinStatus.UNREACHABLE
            }
        }
    }

    /**
     * SIGN IN WITH GOOGLE: the platform's sign-in, then the server join with its ID token. An
     * account the server refuses is signed out again, so the next try can pick another one.
     */
    private fun signIn(accounts: AccountService) {
        if (join.value == JoinStatus.WORKING) return
        join.value = JoinStatus.WORKING
        joinMessage.value = null
        scope.launch {
            val (status, message) = try {
                when (val signedIn = accounts.signIn()) {
                    SignInResult.Cancelled -> JoinStatus.IDLE to null
                    is SignInResult.Failed -> JoinStatus.FAILED to signedIn.message
                    is SignInResult.Success -> {
                        if (signedIn.restored) runCatching { onRestored() }
                        when (val joined = core.signIn(signedIn.idToken)) {
                            is JoinResult.Joined -> {
                                step.value = OnboardingStep.DOWNLOAD
                                JoinStatus.IDLE to null
                            }
                            is JoinResult.NotAllowed -> {
                                runCatching { accounts.signOut() }
                                JoinStatus.NOT_ALLOWED to joined.message
                            }
                            JoinResult.InvalidCode -> JoinStatus.FAILED to SIGN_IN_FAILED_WORDS
                            is JoinResult.Failed -> JoinStatus.UNREACHABLE to null
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                JoinStatus.UNREACHABLE to null
            }
            joinMessage.value = message
            join.value = status
        }
    }

    /** RETRY on the download step: ask the core to fetch the timetable again and give it another minute. */
    fun retryDownload() {
        stalled.value = false
        attempt.value++
        scope.launch { runCatching { core.checkForUpdates() } }
    }

    /** CONTINUE on the download step (also used to carry on without the timetable). */
    fun continueFromDownload() = advance()

    /** ALLOW: the system prompt, or the phone's settings once it was refused for good. */
    fun allowNotifications() {
        scope.launch {
            if (platform.notificationPermission.value == PermissionState.DENIED) platform.openNotificationSettings()
            else platform.requestNotificationPermission()
            advance()
        }
    }

    /** NOT NOW on the notifications step. */
    fun skipNotifications() = advance()

    private fun advance() {
        val allowed = platform.notificationPermission.value == PermissionState.GRANTED
        nextStep(step.value, allowed)?.let { step.value = it }
    }

    companion object {
        /** No movement for this long while "downloading": offer RETRY. */
        const val STALL_MS = 60_000L
    }
}
