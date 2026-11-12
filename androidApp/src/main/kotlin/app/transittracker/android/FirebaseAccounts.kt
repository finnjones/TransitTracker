package app.transittracker.android

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import app.transittracker.core.KeyValueStore
import app.transittracker.ui.platform.Account
import app.transittracker.ui.platform.AccountData
import app.transittracker.ui.platform.AccountService
import app.transittracker.ui.platform.SignInResult
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "TtAccounts"

/**
 * Sign-in with Google through Firebase Authentication. The Google sheet comes from Credential
 * Manager ("Sign in with Google"); its Google ID token signs in to Firebase, and Firebase's own
 * ID token is what the app hands tt-server (`POST /v1/join`), which checks it.
 *
 * After signing in, the user's saved data is brought down from their account ([CloudKeyValueStore.pull]).
 * Needs `default_web_client_id`, which the Google services plugin writes from google-services.json
 * once Google is enabled as a sign-in provider in the Firebase console (androidApp/README.md).
 */
internal class FirebaseAccountService(
    private val app: Application,
    /** The visible activity: the sign-in sheet needs a window. */
    private val activity: () -> Activity?,
    private val cloud: CloudKeyValueStore,
) : AccountService {
    private val auth = FirebaseAuth.getInstance()
    private val state = MutableStateFlow(auth.currentUser?.toAccount())
    override val account: StateFlow<Account?> = state.asStateFlow()

    init {
        auth.addAuthStateListener { state.value = it.currentUser?.toAccount() }
    }

    override suspend fun signIn(): SignInResult {
        return try {
            val user = auth.currentUser ?: when (val google = signInWithGoogle()) {
                is GoogleResult.Done -> google.user
                is GoogleResult.Ended -> return google.result
            }
            val token = user.getIdToken(true).await().token
                ?: return SignInResult.Failed("Sign-in didn't work. Try again.")
            val restored = cloud.pull(user.uid)
            SignInResult.Success(user.toAccount(), token, restored)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sign-in failed", e)
            SignInResult.Failed("Sign-in didn't work. Check your connection and try again.")
        }
    }

    override suspend fun signOut() {
        auth.signOut()
        // Next time the sheet offers every account again rather than signing straight back in.
        runCatching { CredentialManager.create(app).clearCredentialState(ClearCredentialStateRequest()) }
    }

    private sealed interface GoogleResult {
        data class Done(val user: FirebaseUser) : GoogleResult
        data class Ended(val result: SignInResult) : GoogleResult
    }

    private suspend fun signInWithGoogle(): GoogleResult {
        val clientId = webClientId()
            ?: return GoogleResult.Ended(SignInResult.Failed("Google sign-in is not set up for this build of the app yet."))
        val window = activity() ?: return GoogleResult.Ended(SignInResult.Failed("Open the app and try again."))
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        val credential = try {
            CredentialManager.create(window).getCredential(window, request).credential
        } catch (e: GetCredentialCancellationException) {
            return GoogleResult.Ended(SignInResult.Cancelled)
        } catch (e: NoCredentialException) {
            return GoogleResult.Ended(SignInResult.Failed("There is no Google account on this phone. Add one in the phone's settings."))
        } catch (e: GetCredentialException) {
            Log.w(TAG, "Credential Manager failed", e)
            return GoogleResult.Ended(SignInResult.Failed("Google sign-in didn't work. Try again."))
        }
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return GoogleResult.Ended(SignInResult.Failed("Google sign-in didn't work. Try again."))
        }
        val google = GoogleIdTokenCredential.createFrom(credential.data)
        val user = auth.signInWithCredential(GoogleAuthProvider.getCredential(google.idToken, null)).await().user
            ?: return GoogleResult.Ended(SignInResult.Failed("Sign-in didn't work. Try again."))
        return GoogleResult.Done(user)
    }

    /** The OAuth web client id from google-services.json, or null when Google sign-in is not enabled there. */
    @SuppressLint("DiscouragedApi") // Generated by the Google services plugin only when configured.
    private fun webClientId(): String? {
        val id = app.resources.getIdentifier("default_web_client_id", "string", app.packageName)
        return if (id == 0) null else app.getString(id).takeIf { it.isNotBlank() }
    }
}

private fun FirebaseUser.toAccount() = Account(uid = uid, email = email, displayName = displayName)

/**
 * The phone's key-value store, with the user's own data ([AccountData.KEYS]: display settings,
 * places, saved trips, recent searches) also kept in Firestore while signed in, at
 * `users/{uid}/prefs/{key}` as `{value, updatedMs}` (a null value marks a removed key).
 *
 * Writes go to the phone first and then to Firestore, which queues them while offline. [pull]
 * brings the account's copy down: the account wins for keys it has, and keys only the phone has
 * go up. Only the signed-in user can read or write their documents (firebase/firestore.rules).
 */
internal class CloudKeyValueStore(private val local: KeyValueStore) : KeyValueStore {
    private val auth get() = FirebaseAuth.getInstance()
    private val firestore get() = FirebaseFirestore.getInstance()

    override suspend fun get(key: String): String? = local.get(key)

    override suspend fun put(key: String, value: String?) {
        local.put(key, value)
        if (key !in AccountData.KEYS) return
        val uid = auth.currentUser?.uid ?: return
        // Not awaited: Firestore keeps the write and sends it when it can.
        prefs(uid).document(key).set(mapOf("value" to value, "updatedMs" to System.currentTimeMillis()))
            .addOnFailureListener { Log.w(TAG, "Could not save $key to the account", it) }
    }

    /**
     * Makes the phone and the account agree for [uid]: values in the account replace the phone's,
     * the phone's values the account lacks are uploaded. True when the phone's data changed (the
     * app should read it again). False, changing nothing, when Firestore does not answer in time.
     */
    suspend fun pull(uid: String, timeoutMs: Long = PULL_TIMEOUT_MS): Boolean {
        val snapshot = try {
            withTimeoutOrNull(timeoutMs) { prefs(uid).get().await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the account's saved data", e)
            null
        } ?: return false
        val remote = snapshot.documents.filter { it.id in AccountData.KEYS }.associate { it.id to it.getString("value") }
        var changed = false
        for (key in AccountData.KEYS) {
            val mine = local.get(key)
            if (key in remote) {
                val theirs = remote[key]
                if (theirs != mine) {
                    local.put(key, theirs)
                    changed = true
                }
            } else if (mine != null) {
                prefs(uid).document(key).set(mapOf("value" to mine, "updatedMs" to System.currentTimeMillis()))
            }
        }
        return changed
    }

    private fun prefs(uid: String) = firestore.collection("users").document(uid).collection("prefs")

    companion object {
        const val PULL_TIMEOUT_MS = 10_000L
    }
}

/** Waits for a Play services [Task]. */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val e = task.exception
        when {
            e != null -> cont.resumeWithException(e)
            task.isCanceled -> cont.cancel()
            else -> cont.resume(task.result)
        }
    }
}
