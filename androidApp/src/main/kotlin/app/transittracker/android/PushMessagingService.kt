package app.transittracker.android

import android.content.Context
import android.util.Log
import app.transittracker.model.PushKeys
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Firebase Cloud Messaging. Push is on only when the app was built with
 * `androidApp/google-services.json` (the Google services plugin then initialises Firebase);
 * otherwise [Push.isAvailable] is false and nothing here runs.
 *
 * - Tokens go to the core (`TransitCore.updatePushToken`): [Push.register] runs on every start
 *   and FCM hands back the current token (and any refreshed one). firebase-messaging 26 delivers
 *   it to [onRegistered] only when the manifest sets `firebase_messaging_installation_id_enabled`;
 *   otherwise (this app) it goes to [onNewToken]. Both are handled.
 * - A message that arrives while the app is open is shown as a notification on its channel;
 *   background messages are shown by FCM itself. Either way a tap opens the app with the
 *   message's data, which `MainActivity` hands to `AppController.openFromNotification`.
 */
class PushMessagingService : FirebaseMessagingService() {
    /** The registration token on FCM's default path (no installation-id meta-data in the manifest). */
    @Deprecated("Deprecated in firebase-messaging 26, but still how it delivers tokens by default")
    override fun onNewToken(token: String) {
        (application as TransitTrackerApplication).graph.onPushToken(token)
    }

    /** A new registration token, when installation-id registration is switched on in the manifest. */
    override fun onRegistered(token: String) {
        (application as TransitTrackerApplication).graph.onPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val notification = message.notification
        val title = notification?.title ?: data["title"] ?: return
        val body = notification?.body ?: data["body"].orEmpty()
        val channel = notification?.channelId ?: Notifications.channelFor(data[PushKeys.KIND])
        Notifications.show(this, title, body, data, channel, notification?.tag ?: message.collapseKey)
    }
}

internal object Push {
    /** True when this build has a Firebase configuration (google-services.json) and it initialised. */
    fun isAvailable(context: Context): Boolean =
        BuildConfig.FIREBASE_CONFIGURED && FirebaseApp.getApps(context).isNotEmpty()

    private const val TAG = "TransitTrackerPush"

    const val NOT_IN_BUILD = "Push is not set up in this build of the app."
    const val ASKING = "Asking Firebase for this phone's notification token..."
    const val WAITING = "Firebase accepted the request; waiting for it to hand over the token..."
    const val GOT_TOKEN = "Got a token from Firebase; sending it to the server..."
    const val REGISTERED = "Registered: the server has this phone's notification token."
    const val NOT_ACCEPTED = "The server has not accepted this phone's token yet. The app tries again on its next check."

    /** Settings > Notifications shows this ([AndroidPlatformServices.pushRegistration]). */
    val status = MutableStateFlow<String?>(null)

    /**
     * Registers with FCM; no-op without Firebase. Each call hands the current token (new or
     * already held) to [PushMessagingService], so every start re-sends it to the server.
     * Failures are logged and shown in [status].
     */
    fun register(context: Context) {
        if (!isAvailable(context)) {
            Log.i(TAG, "Firebase is not configured in this build: push is off")
            status.value = NOT_IN_BUILD
            return
        }
        status.value = ASKING
        try {
            FirebaseMessaging.getInstance().register()
                .addOnSuccessListener { status.compareAndSet(ASKING, WAITING) }
                .addOnFailureListener { e -> failed(e) }
        } catch (e: Exception) {
            failed(e)
        }
    }

    private fun failed(e: Throwable) {
        Log.w(TAG, "FCM registration failed", e)
        status.value = "Firebase did not give this phone a notification token: ${describe(e)}"
    }

    /** The exception and its causes, e.g. "FCM Registration failed!: SERVICE_NOT_AVAILABLE". */
    private fun describe(e: Throwable): String =
        generateSequence(e) { it.cause }.take(4)
            .map { it.message?.takeIf(String::isNotBlank) ?: it.javaClass.simpleName }
            .distinct().joinToString(": ")
}
