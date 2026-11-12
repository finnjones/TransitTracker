package app.transittracker.android

import android.content.Context
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
 * - Tokens go to the core (`TransitCore.updatePushToken`) when FCM registers the app and on every refresh.
 * - A message that arrives while the app is open is shown as a notification on its channel;
 *   background messages are shown by FCM itself. Either way a tap opens the app with the
 *   message's data, which `MainActivity` hands to `AppController.openFromNotification`.
 */
class PushMessagingService : FirebaseMessagingService() {
    /** The registration token, after [Push.register] and whenever FCM issues a new one. */
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

    /**
     * Registers with FCM (firebase-messaging 26: the token arrives in
     * [PushMessagingService.onRegistered], now and on every refresh); no-op without Firebase.
     */
    fun register(context: Context) {
        if (!isAvailable(context)) return
        FirebaseMessaging.getInstance().register()
    }
}
