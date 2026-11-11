package app.transittracker.server.live

import app.transittracker.model.WallClock
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingTest {
    private var now = at(DAY, hms(12, 0))
    private val clock = WallClock { now }

    @Test
    fun `records at most once per minute per feed and kind`() = runBlocking {
        val dir = Files.createTempDirectory("tt-rec")
        val recorder = FeedRecorder(dir, clock)
        assertNotNull(recorder.record("sydneytrains", FeedKind.TRIP_UPDATES, byteArrayOf(1)))
        now += 30_000
        assertNull(recorder.record("sydneytrains", FeedKind.TRIP_UPDATES, byteArrayOf(2)))
        assertNotNull(recorder.record("sydneytrains", FeedKind.VEHICLES, byteArrayOf(3)), "other kinds are independent")
        assertNotNull(recorder.record("metro", FeedKind.TRIP_UPDATES, byteArrayOf(4)), "other feeds are independent")
        now += 30_000
        val second = recorder.record("sydneytrains", FeedKind.TRIP_UPDATES, byteArrayOf(5))
        assertNotNull(second)
        assertEquals(DAY, second.parent.fileName.toString())
    }

    @Test
    fun `days older than the retention period are deleted`() = runBlocking {
        val dir = Files.createTempDirectory("tt-rec")
        val old = Files.createDirectories(dir.resolve("20261028")) // 14 days before DAY: gone
        val kept = Files.createDirectories(dir.resolve("20261029")) // 13 days before: kept
        Files.write(old.resolve("sydneytrains-tripupdates-1.pb.gz"), byteArrayOf(1))
        val unrelated = Files.createDirectories(dir.resolve("notes"))
        val recorder = FeedRecorder(dir, clock, retentionDays = 14)
        assertEquals(1, recorder.prune())
        assertFalse(old.exists())
        assertTrue(kept.exists())
        assertTrue(unrelated.exists())

        // Recording on a new day also prunes.
        now += 24 * 3600_000L
        recorder.record("sydneytrains", FeedKind.ALERTS, byteArrayOf(1))
        assertFalse(kept.exists())
    }

    @Test
    fun `replay fetcher serves recordings in time order and fixtures`() = runBlocking {
        val dir = Files.createTempDirectory("tt-rec")
        val recorder = FeedRecorder(dir, clock)
        recorder.record("sydneytrains", FeedKind.TRIP_UPDATES, byteArrayOf(10))
        now += 60_000
        recorder.record("sydneytrains", FeedKind.TRIP_UPDATES, byteArrayOf(20))
        Files.write(dir.resolve("metro-alerts.pb"), byteArrayOf(30))

        val looping = ReplayFetcher(dir)
        assertEquals(listOf(10, 20, 10), List(3) { (looping.fetch(TRAINS, FeedKind.TRIP_UPDATES) as FetchResult.Success).bytes.single().toInt() })
        val stopping = ReplayFetcher(dir, loop = false)
        assertEquals(listOf(10, 20, 20), List(3) { (stopping.fetch(TRAINS, FeedKind.TRIP_UPDATES) as FetchResult.Success).bytes.single().toInt() })
        assertEquals(30, (looping.fetch(METRO, FeedKind.ALERTS) as FetchResult.Success).bytes.single().toInt())
        assertIs<FetchResult.NotConfigured>(looping.fetch(METRO, FeedKind.VEHICLES))
        assertFalse(looping.isRemote)
    }
}
