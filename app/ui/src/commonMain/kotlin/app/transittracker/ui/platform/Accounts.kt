package app.transittracker.ui.platform

import app.transittracker.core.KvKeys
import app.transittracker.ui.feature.plan.KeyValueRecentSearches
import app.transittracker.ui.places.KeyValuePlacesStore
import app.transittracker.ui.settings.KeyValueSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The account the app is signed in with. */
data class Account(val uid: String, val email: String?, val displayName: String? = null) {
    /** What Settings shows: the email, else the name, else "Signed in". */
    val label: String get() = email ?: displayName ?: "Signed in"
}

/** How a sign-in ended. */
sealed interface SignInResult {
    /**
     * Signed in. [idToken] is a fresh ID token for the server's `POST /v1/join`. [restored] is
     * true when saved trips, places or settings were brought down from the account, so the app
     * should read them again.
     */
    data class Success(val account: Account, val idToken: String, val restored: Boolean = false) : SignInResult

    /** The user closed the sign-in sheet. */
    data object Cancelled : SignInResult

    /** Sign-in did not work; [message] is a plain sentence for the screen. */
    data class Failed(val message: String) : SignInResult
}

/**
 * Signing in (Firebase Authentication with Google on Android). It replaces the invite code: the
 * server lets in the accounts on its allow list. Builds without Firebase have no account service
 * ([PlatformServices.accounts] is null) and onboarding asks for an invite code instead.
 *
 * While signed in the platform also keeps saved trips, places, display settings and recent
 * searches in the account, so they come back on a new phone.
 */
interface AccountService {
    /** The signed-in account, or null. */
    val account: StateFlow<Account?>

    /** Shows the platform's sign-in. */
    suspend fun signIn(): SignInResult

    /** Signs out of the account on this phone. */
    suspend fun signOut()
}

/** [AccountService] for previews and tests: [nextResult] is what the next [signIn] returns. */
class FakeAccountService(
    account: Account? = null,
    var nextResult: SignInResult = SignInResult.Success(Account("fake-uid", "rider@example.com", "Rider"), "fake-id-token"),
) : AccountService {
    private val state = MutableStateFlow(account)
    override val account: StateFlow<Account?> = state.asStateFlow()

    var signIns = 0
        private set
    var signOuts = 0
        private set

    override suspend fun signIn(): SignInResult {
        signIns++
        val result = nextResult
        if (result is SignInResult.Success) state.value = result.account
        return result
    }

    override suspend fun signOut() {
        signOuts++
        state.value = null
    }
}

/** What is kept in the user's account while signed in: the key-value keys of the user's own data. */
object AccountData {
    /**
     * Display settings, saved places and their trip links, saved trips and the planner's recent
     * searches. Never the device token, push token, caches or timetable packs (they belong to
     * the phone).
     */
    val KEYS: List<String> = listOf(
        KeyValueSettingsStore.KEY_THEME,
        KeyValueSettingsStore.KEY_CLOCK,
        KeyValueSettingsStore.KEY_DENSITY,
        KeyValueSettingsStore.KEY_REDUCE_MOTION,
        KeyValueSettingsStore.KEY_SOUNDS,
        KeyValueSettingsStore.KEY_HAPTICS,
        KeyValueSettingsStore.KEY_FOLLOW,
        KeyValuePlacesStore.KEY_PLACES,
        KeyValuePlacesStore.KEY_LINKS,
        KvKeys.TRIPS,
        KeyValueRecentSearches.KEY,
    )
}
