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
 * - Tokens go to the core (`TransitCore.updatePushToken`) now and whenever Firebase issues a new one.
 * - A message that arrives while the app is open is shown as a notification on its channel;
 *   background messages are shown by FCM itself. Either way a tap opens the app with the
 *   message's data, which `MainActivity` hands to `AppController.openFromNotification`.
 */
class PushMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
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
        BuildConfig.PUSH_CONFIGURED && FirebaseApp.getApps(context).isNotEmpty()

    /** Fetches the current token once and passes it to [onToken]; no-op without Firebase. */
    fun requestToken(context: Context, onToken: (String) -> Unit) {
        if (!isAvailable(context)) return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token -> if (token != null) onToken(token) }
    }
}
