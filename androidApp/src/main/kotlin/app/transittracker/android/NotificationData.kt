package app.transittracker.android

import android.content.Intent
import android.os.Bundle
import app.transittracker.model.PushKeys

/**
 * The data payload of a tapped notification. FCM puts a background message's `data` into the
 * launch intent's extras (next to its own `google.*` / `gcm.*` keys); notifications the app posts
 * itself while open carry the same keys.
 */
internal object NotificationData {
    private val ignoredPrefixes = listOf("google.", "gcm.", "android.")
    private val ignoredKeys = setOf("from", "collapse_key")

    /** The payload, or null when [intent] is not a notification tap (no [PushKeys.KIND]). */
    fun fromIntent(intent: Intent): Map<String, String>? = intent.extras?.let(::fromBundle)

    fun fromBundle(extras: Bundle): Map<String, String>? {
        if (!extras.containsKey(PushKeys.KIND)) return null
        val data = HashMap<String, String>()
        for (key in extras.keySet()) {
            if (key in ignoredKeys || ignoredPrefixes.any { key.startsWith(it) }) continue
            val value = extras.getString(key) ?: continue
            data[key] = value
        }
        return data
    }

    /** Copies [data] into [intent]'s extras (for notifications the app posts itself). */
    fun putInto(intent: Intent, data: Map<String, String>): Intent = intent.apply {
        data.forEach { (key, value) -> putExtra(key, value) }
    }
}
