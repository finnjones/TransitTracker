package app.transittracker.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.transittracker.model.PushKeys

/**
 * Notification channels and the notifications the app posts itself. The channel ids are the
 * ones the server names in every push (`FcmPushSender.channelFor`, plan 5.12).
 */
internal object Notifications {
    const val DELAYS = "delays"
    const val CANCELLATIONS = "cancellations"
    const val ALERTS = "alerts"
    const val GENERAL = "general"

    /** Creates (or updates) the four channels; safe to call on every start. */
    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                channel(DELAYS, "Delays", "A watched service is running late, or is back on time", NotificationManager.IMPORTANCE_HIGH),
                channel(CANCELLATIONS, "Cancellations", "A watched service is cancelled", NotificationManager.IMPORTANCE_HIGH),
                channel(ALERTS, "Service alerts", "Trackwork and disruptions on your lines", NotificationManager.IMPORTANCE_DEFAULT),
                channel(GENERAL, "General", "Platform changes, test messages and everything else", NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    /** The channel for a push's [PushKeys.KIND], as the server chooses it. */
    fun channelFor(kind: String?): String = when (kind) {
        "delay", "recovered" -> DELAYS
        "cancel" -> CANCELLATIONS
        "alert" -> ALERTS
        else -> GENERAL
    }

    /**
     * Shows a message that arrived while the app was open (FCM shows background ones itself).
     * Tapping it opens the app with [data] as the launch extras, like a background notification.
     */
    // POST_NOTIFICATIONS is checked in canPost() first; lint cannot see through the helper.
    @SuppressLint("MissingPermission")
    fun show(context: Context, title: String, body: String, data: Map<String, String>, channelId: String?, tag: String?) {
        if (!canPost(context)) return
        val intent = NotificationData.putInto(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            data,
        )
        val key = tag ?: data.toString()
        val tap = PendingIntent.getActivity(
            context,
            key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channelId ?: channelFor(data[PushKeys.KIND]))
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(tag, 0, notification)
        } catch (e: SecurityException) {
            // Permission withdrawn between the check and the post.
            android.util.Log.w("TransitTracker", "Notification not posted: ${e.message}")
        }
    }

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun channel(id: String, name: String, description: String, importance: Int) =
        NotificationChannel(id, name, importance).apply { this.description = description }
}
