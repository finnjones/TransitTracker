package app.transittracker.android

import android.content.Context
import android.util.Log
import app.transittracker.model.PushKeys
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

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

    /**
     * Registers with FCM; no-op without Firebase. Each call hands the current token (new or
     * already held) to [PushMessagingService], so every start re-sends it to the server.
     * Failures are logged.
     */
    fun register(context: Context) {
        if (!isAvailable(context)) {
            Log.i(TAG, "Firebase is not configured in this build: push is off")
            return
        }
        FirebaseMessaging.getInstance().register()
            .addOnFailureListener { e -> Log.w(TAG, "FCM registration failed", e) }
    }
}
