package app.transittracker.server.watch

import app.transittracker.model.Alert
import app.transittracker.model.AlertSeverity
import app.transittracker.model.PushKeys
import app.transittracker.model.TripStatus
import app.transittracker.model.WatchRules
import app.transittracker.server.spi.ApiException
import app.transittracker.server.spi.PushResult
import app.transittracker.server.spi.TripChangeBatch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EvaluationTest {

    private fun harness(
        time: String = "07:30",
        watch: app.transittracker.model.WatchDto = commute(),
    ) = Harness(now = sydney(WED, time)).apply { engine.upsert(DEVICE, watch) }

    // ---- delays ----

    @Test
    fun `delay reaching the threshold notifies once`() = runTest {
        val h = harness()
        h.feed(h.delta("T1-0742", delayS = 240))
        assertEquals(emptyList(), h.bodies, "4 min is below the 5 min threshold")

        h.feed(h.delta("T1-0742", delayS = 360))
        assertEquals(listOf("7:42 to Central is running 6 min late (now 7:48)"), h.bodies)
        val (token, message) = h.push.sent.single()
        assertEquals("token-$DEVICE", token)
        assertEquals("Strathfield → Central", message.title)
        assertEquals("T1-0742:$TODAY", message.collapseKey)
        assertEquals(12 * 60L, message.ttlSeconds, "TTL runs until the scheduled departure")
        assertTrue(message.highPriority)
        assertEquals(
            mapOf(PushKeys.KIND to "delay", PushKeys.TRIP_ID to "T1-0742", PushKeys.SERVICE_DATE to TODAY, PushKeys.WATCH_ID to "w1"),
            message.data,
        )

        // The same data again (next refresh) must not repeat the notification.
        h.feed(h.delta("T1-0742", delayS = 360))
        h.feed(h.delta("T1-0742", delayS = 400))
        assertEquals(1, h.push.sent.size)
    }

    @Test
    fun `delay growing by the step notifies again`() = runTest {
        val h = harness()
        h.feed(h.delta("T1-0742", delayS = 360))
        h.feed(h.delta("T1-0742", delayS = 600)) // +4 min: below the 5 min step
        h.feed(h.delta("T1-0742", delayS = 480)) // shrinking: nothing
        h.feed(h.delta("T1-0742", delayS = 660)) // +5 min since the last notification
        assertEquals(
            listOf(
                "7:42 to Central is running 6 min late (now 7:48)",
                "7:42 to Central now 11 min late (7:53)",
            ),
            h.bodies,
        )
    }

    @Test
    fun `recovery notifies when enabled and re-arms the threshold`() = runTest {
        val h = harness(watch = commute(rules = WatchRules(notifyRecovered = true)))
        h.feed(h.delta("T1-0742", delayS = 360))
        h.feed(h.delta("T1-0742", delayS = 30))
        h.feed(h.delta("T1-0742", delayS = 0))
        h.feed(h.delta("T1-0742", delayS = 300))
        assertEquals(
            listOf(
                "7:42 to Central is running 6 min late (now 7:48)",
                "7:42 to Central back on time",
                "7:42 to Central is running 5 min late (now 7:47)",
            ),
            h.bodies,
        )
        assertEquals("recovered", h.push.sent[1].second.data[PushKeys.KIND])
    }

    @Test
    fun `recovery is silent by default`() = runTest {
        val h = harness()
        h.feed(h.delta("T1-0742", delayS = 360))
        h.feed(h.delta("T1-0742", delayS = 0))
        assertEquals(1, h.push.sent.size)
    }

    // ---- cancellations ----

    @Test
    fun `cancellation names the next covered service and its live state`() = runTest {
        val h = harness()
        h.live.cached["T9-0751" to TODAY] = h.delta("T9-0751", delayS = 20)
        h.feed(h.delta("T1-0742", status = TripStatus.CANCELLED))
        h.feed(h.delta("T1-0742", status = TripStatus.CANCELLED))
        assertEquals(listOf("7:42 Strathfield → Central is cancelled. Next: 7:51 (on time)"), h.bodies)
        val message = h.push.sent.single().second
        assertEquals("cancel", message.data[PushKeys.KIND])
        assertEquals("T1-0742:$TODAY", message.collapseKey)
    }

    @Test
    fun `cancellation next service variants`() = runTest {
        val late = harness()
        late.live.cached["T9-0751" to TODAY] = late.delta("T9-0751", delayS = 240)
        late.feed(late.delta("T1-0742", status = TripStatus.CANCELLED))
        assertEquals(listOf("7:42 Strathfield → Central is cancelled. Next: 7:51 (4 min late)"), late.bodies)

        val unknown = harness()
        unknown.feed(unknown.delta("T1-0742", status = TripStatus.CANCELLED))
        assertEquals(listOf("7:42 Strathfield → Central is cancelled. Next: 7:51 (timetabled)"), unknown.bodies)

        val none = harness()
        none.live.cached["T9-0751" to TODAY] = none.delta("T9-0751", status = TripStatus.CANCELLED)
        none.feed(none.delta("T1-0742", status = TripStatus.CANCELLED))
        assertEquals(listOf("7:42 Strathfield → Central is cancelled. No later service in your window"), none.bodies)
    }

    @Test
    fun `skipped origin is treated like a cancellation`() = runTest {
        val h = harness()
        h.feed(h.delta("T1-0742", originSkipped = true))
        assertEquals(listOf("7:42 Strathfield → Central won't stop at Strathfield. Next: 7:51 (timetabled)"), h.bodies)
    }

    @Test
    fun `cancellation is silent when notifyCancel is off`() = runTest {
        val h = harness(watch = commute(rules = WatchRules(notifyCancel = false)))
        h.feed(h.delta("T1-0742", status = TripStatus.CANCELLED))
        assertEquals(emptyList(), h.bodies)
    }

    // ---- platforms ----

    @Test
    fun `departure platform change notifies with the platform code`() = runTest {
        val h = harness()
        h.feed(h.delta("T1-0742", actualStopId = "STR5"))
        h.feed(h.delta("T1-0742", actualStopId = "STR5"))
        h.feed(h.delta("T1-0742"))
        assertEquals(
            listOf("7:42 to Central now leaves from platform 5", "7:42 to Central now leaves from platform 3"),
            h.bodies,
        )
        assertEquals("platform", h.push.sent[0].second.data[PushKeys.KIND])
    }

    @Test
    fun `platform change and delay together send one combined message`() = runTest {
        val h = harness()
        h.feed(h.delta("T1-0742", delayS = 360, actualStopId = "STR6"))
        assertEquals(
            listOf("7:42 to Central now leaves from platform 6. 7:42 to Central is running 6 min late (now 7:48)"),
            h.bodies,
        )
    }

    // ---- never notify ----

    @Test
    fun `stale data is ignored`() = runTest {
        val h = harness()
        h.feed(h.delta("T1-0742", delayS = 600), stale = true)
        h.feed(h.delta("T1-0742", delayS = 600, stale = true))
        h.feed(h.delta("T1-0742", delayS = 600, updatedMs = h.clock.now - 4 * 60_000))
        h.engine.onTripChanges(TripChangeBatch("sydneytrains", h.clock.now - 10 * 60_000, false, listOf(h.delta("T1-0742", delayS = 600))))
        assertEquals(emptyList(), h.bodies)
        h.feed(h.delta("T1-0742", delayS = 600))
        assertEquals(1, h.push.sent.size, "fresh data still notifies")
    }

    @Test
    fun `departed trips are ignored`() = runTest {
        val h = harness(time = "07:43")
        h.feed(h.delta("T1-0742", status = TripStatus.CANCELLED))
        h.feed(h.delta("T1-0742", delayS = 0, actualStopId = "STR5"))
        assertEquals(emptyList(), h.bodies)

        // Origin no longer among the remaining stops: it has left, whatever the delay says.
        val h2 = harness(time = "07:40")
        h2.feed(h2.delta("T1-0742", delayS = 600, includeOrigin = false))
        assertEquals(emptyList(), h2.bodies)
    }

    @Test
    fun `paused watches are silent until the pause ends`() = runTest {
        val h = Harness(now = sydney(WED, "07:30"))
        h.engine.upsert(DEVICE, commute(pausedUntilMs = h.clock.now + 60_000))
        h.feed(h.delta("T1-0742", delayS = 600))
        h.engine.onAlerts(listOf(Alert("a1", "Trackwork", routeIds = listOf("T1"))))
        assertEquals(emptyList(), h.bodies)
        h.clock.now += 2 * 60_000
        h.feed(h.delta("T1-0742", delayS = 600))
        assertEquals(listOf("7:42 to Central is running 10 min late (now 7:52)"), h.bodies)
    }

    @Test
    fun `devices with notifications disabled or no token get nothing`() = runTest {
        val h = harness()
        h.devices.add(DEVICE, enabled = false)
        h.feed(h.delta("T1-0742", delayS = 600))
        h.devices.add(DEVICE, token = null)
        h.feed(h.delta("T1-0742", delayS = 600))
        assertEquals(emptyList(), h.bodies)
        assertEquals(emptyList(), h.log.entries)
    }

    @Test
    fun `inactive watches and uncovered trips are ignored`() = runTest {
        val h = harness(time = "06:00") // active from 06:30
        h.feed(h.delta("T1-0742", delayS = 600))
        h.at(WED, "07:30")
        h.feed(h.delta("T1-0812", delayS = 600)) // outside the window
        assertEquals(emptyList(), h.bodies)
    }

    // ---- alerts ----

    @Test
    fun `alert touching the watch notifies once per alert per day`() = runTest {
        val h = harness()
        val alert = Alert("a1", "Trackwork between Strathfield and Central", routeIds = listOf("T1"), severity = AlertSeverity.WARNING)
        h.engine.onAlerts(listOf(alert))
        h.engine.onAlerts(listOf(alert))
        h.engine.tick()
        assertEquals(listOf("Trackwork between Strathfield and Central"), h.bodies)
        val message = h.push.sent.single().second
        assertEquals("alert", message.data[PushKeys.KIND])
        assertEquals("a1", message.data[PushKeys.ALERT_ID])
        assertEquals("alert:a1", message.collapseKey)
    }

    @Test
    fun `alerts must touch the watch's routes or stations`() = runTest {
        val h = harness()
        h.engine.onAlerts(
            listOf(
                Alert("other-line", "T3 delays", routeIds = listOf("T3"), stopIds = listOf("LID1")),
                Alert("info", "Lifts at Strathfield", routeIds = listOf("T1"), severity = AlertSeverity.INFO),
                Alert("later", "Weekend trackwork", routeIds = listOf("T1"), activePeriods = listOf(app.transittracker.model.TimeRange(startMs = h.clock.now + 86_400_000))),
            ),
        )
        assertEquals(emptyList(), h.bodies)
        h.engine.onAlerts(listOf(Alert("station", "Strathfield platform 3 closed", stopIds = listOf("STR3"))))
        assertEquals(listOf("Strathfield platform 3 closed"), h.bodies)
    }

    @Test
    fun `alert received before the watch is active is sent when it becomes active`() = runTest {
        val h = harness(time = "06:00")
        h.engine.onAlerts(listOf(Alert("a1", "Signal fault at Central", stopIds = listOf("CEN"))))
        assertEquals(emptyList(), h.bodies)
        h.at(WED, "06:31")
        h.engine.tick()
        h.engine.tick()
        assertEquals(listOf("Signal fault at Central"), h.bodies)
    }

    // ---- sending ----

    @Test
    fun `every attempt is logged and counted`() = runTest {
        val h = harness()
        h.feed(h.delta("T1-0742", delayS = 360))
        val entry = h.log.entries.single()
        assertEquals(DEVICE, entry.deviceId)
        assertEquals("w1", entry.watchId)
        assertEquals("T1-0742", entry.tripId)
        assertEquals(TODAY, entry.serviceDate)
        assertEquals("delay", entry.kind)
        assertEquals("sent", entry.result)
        h.push.next = PushResult.Failed("boom", retryable = true)
        h.feed(h.delta("T1-0751", delayS = 0), h.delta("T9-0751", delayS = 600))
        val status = h.engine.status()
        assertEquals(1, status.sentToday)
        assertEquals(1, status.failedToday)
        assertEquals(2, h.log.entries.size)
    }

    @Test
    fun `invalid token clears the device token`() = runTest {
        val h = harness()
        h.push.next = PushResult.InvalidToken
        h.feed(h.delta("T1-0742", delayS = 360))
        assertNull(h.devices.get(DEVICE)!!.pushToken)
        assertEquals("invalid_token", h.log.entries.single().result)
        // With the token gone, later changes are not attempted.
        h.feed(h.delta("T1-0742", delayS = 900))
        assertEquals(1, h.push.sent.size)
    }

    @Test
    fun `single-service watch notifies and expires after arrival`() = runTest {
        val h = Harness(now = sydney(WED, "07:30"))
        h.engine.upsert(DEVICE, single())
        h.feed(h.delta("T1-0742", delayS = 360))
        assertEquals(listOf("7:42 to Central is running 6 min late (now 7:48)"), h.bodies)

        // Running late: expected arrival 8:03, so it is kept past the scheduled 7:57 (+2 min grace).
        h.live.cached["T1-0742" to TODAY] = h.delta("T1-0742", delayS = 360)
        h.at(WED, "08:00")
        h.engine.tick()
        assertNotNull(h.watches.get(DEVICE, "s1"))
        h.at(WED, "08:06")
        h.engine.tick()
        assertNull(h.watches.get(DEVICE, "s1"))
        assertTrue(h.engine.coveredTrips(DEVICE, "s1").isEmpty())
    }

    @Test
    fun `sendTest sends a test message`() = runTest {
        val h = harness(watch = commute(label = "Work"))
        val result = h.engine.sendTest(DEVICE, "w1")
        assertTrue(result is PushResult.Sent)
        val message = h.push.sent.single().second
        assertEquals("Work", message.title)
        assertEquals("test", message.data[PushKeys.KIND])
        assertEquals("test:w1", message.collapseKey)
        assertEquals("test", h.log.entries.single().kind)
        assertEquals(404, assertFailsWith<ApiException> { h.engine.sendTest(DEVICE, "nope") }.status)
        h.devices.add(DEVICE, token = null)
        assertTrue(h.engine.sendTest(DEVICE, "w1") is PushResult.Failed)
    }

    @Test
    fun `start wires live trip and alert changes`() = runTest {
        val h = harness()
        h.engine.start(backgroundScope)
        runCurrent()
        h.live.tripChanges.emit(TripChangeBatch("sydneytrains", h.clock.now, false, listOf(h.delta("T1-0742", delayS = 420))))
        runCurrent()
        h.live.alertChanges.emit(listOf(Alert("a9", "Track equipment repairs", routeIds = listOf("T9"))))
        runCurrent()
        assertEquals(listOf("7:42 to Central is running 7 min late (now 7:49)", "Track equipment repairs"), h.bodies)
    }
}
