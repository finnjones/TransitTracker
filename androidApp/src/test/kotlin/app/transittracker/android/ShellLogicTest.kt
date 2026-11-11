package app.transittracker.android

import android.content.Intent
import app.transittracker.model.PushKeys
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The shell's small pure pieces: server address checks, notification extras, channel choice. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: these tests need no AppGraph (no core, receivers or Firebase).
@Config(sdk = [34], application = android.app.Application::class)
class ShellLogicTest {
    @Test
    fun serverAddressNeedsHttps() {
        assertEquals("https://tt.example.ts.net", ServerAddress.normalize("  https://tt.example.ts.net/ "))
        assertEquals("https://tt.example.ts.net:8443/api", ServerAddress.normalize("HTTPS://tt.example.ts.net:8443/api"))
        assertNull(ServerAddress.normalize("http://tt.example.ts.net"))
        assertNull(ServerAddress.normalize("tt.example.ts.net"))
        assertNull(ServerAddress.normalize("https://"))
        assertNull(ServerAddress.normalize("https://user@tt.example.ts.net"))
        assertNull(ServerAddress.normalize(""))
    }

    @Test
    fun notificationExtrasBecomeTheDataMap() {
        val intent = Intent()
            .putExtra(PushKeys.KIND, "delay")
            .putExtra(PushKeys.TRIP_ID, "T1")
            .putExtra(PushKeys.SERVICE_DATE, "20261111")
            .putExtra("google.message_id", "0:123")
            .putExtra("from", "1234")
            .putExtra("collapse_key", "w1")
        assertEquals(
            mapOf(PushKeys.KIND to "delay", PushKeys.TRIP_ID to "T1", PushKeys.SERVICE_DATE to "20261111"),
            NotificationData.fromIntent(intent),
        )
        assertNull(NotificationData.fromIntent(Intent().putExtra("other", "x")))
        val roundTrip = NotificationData.putInto(Intent(), mapOf(PushKeys.KIND to "alert", PushKeys.ALERT_ID to "a1"))
        assertEquals(mapOf(PushKeys.KIND to "alert", PushKeys.ALERT_ID to "a1"), NotificationData.fromIntent(roundTrip))
    }

    @Test
    fun channelsMatchTheServer() {
        assertEquals("delays", Notifications.channelFor("delay"))
        assertEquals("delays", Notifications.channelFor("recovered"))
        assertEquals("cancellations", Notifications.channelFor("cancel"))
        assertEquals("alerts", Notifications.channelFor("alert"))
        assertEquals("general", Notifications.channelFor("platform"))
        assertEquals("general", Notifications.channelFor(null))
    }
}
