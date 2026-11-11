package app.transittracker.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackUpdaterTest {
    @Test
    fun firstInstallDownloadsVerifiesAndOpens() = runTest {
        val h = Harness(this)
        h.join()
        h.serveConfig(h.packInfo("v2"))
        h.core.start()
        runCurrent()

        val report = h.core.lastPackReport.first { it.isNotEmpty() }
        assertEquals(listOf<PackUpdateResult>(PackUpdateResult.Updated("syd-rail", null, "v2")), report)
        assertEquals(setOf("mem:syd-rail-v2.sqlite"), h.opened.keys)
        assertTrue(h.files.read("syd-rail-v2.sqlite")!!.contentEquals(h.sqlite("v2")))
        assertTrue(h.files.paths().none { it.startsWith("mem:tmp/") }, "temp file moved into place")
        assertTrue(h.kv.get(KvKeys.INSTALLED_PACKS)!!.contains("\"version\":\"v2\""))
        val status = h.core.dataStatus.first()
        assertEquals("v2", status.packs.single().version)
        assertFalse(status.packs.single().downloading)
        assertEquals("Test attribution", status.attribution)
        assertEquals("Bearer tok", h.server.requestsTo("/v1/packs/syd-rail/v2").single().headers["Authorization"])
        // The pack is usable.
        assertEquals("Alpha", h.core.searchStops("alp").single().name)
    }

    @Test
    fun updateSwapsInNewPackAndClosesOldOne() = runTest {
        val h = Harness(this)
        h.join()
        h.preinstall("v1")
        h.serveConfig(h.packInfo("v2"))
        h.core.start()
        runCurrent()
        val old = h.opened.getValue("mem:syd-rail-v1.sqlite")
        val new = h.opened.getValue("mem:syd-rail-v2.sqlite")
        assertEquals(listOf<PackUpdateResult>(PackUpdateResult.Updated("syd-rail", "v1", "v2")), h.core.lastPackReport.first())
        assertTrue(old.closed)
        assertFalse(new.closed)
        assertFalse(h.files.exists("syd-rail-v1.sqlite"), "old file deleted once closed")
    }

    @Test
    fun hashMismatchKeepsOldPack() = runTest {
        val h = Harness(this)
        h.join()
        h.preinstall("v1")
        h.serveConfig(h.packInfo("v2", sha256 = "00".repeat(32)))
        h.core.start()
        runCurrent()
        val result = h.core.lastPackReport.first().single()
        assertIs<PackUpdateResult.Failed>(result)
        assertEquals("sha256 mismatch", result.reason)
        assertFalse(h.opened.getValue("mem:syd-rail-v1.sqlite").closed)
        assertNull(h.opened["mem:syd-rail-v2.sqlite"])
        assertFalse(h.files.exists("syd-rail-v2.sqlite"))
        val status = h.core.dataStatus.first().packs.single()
        assertEquals("v1", status.version)
        assertTrue(status.updateAvailable)
    }

    @Test
    fun sqliteHashMismatchKeepsOldPack() = runTest {
        val h = Harness(this)
        h.join()
        h.preinstall("v1")
        h.serveConfig(h.packInfo("v2", sqliteSha = "ab".repeat(32)))
        h.core.start()
        runCurrent()
        val result = h.core.lastPackReport.first().single()
        assertIs<PackUpdateResult.Failed>(result)
        assertEquals("sqliteSha256 mismatch", result.reason)
        assertTrue(h.files.paths().none { it.contains("v2") })
        assertFalse(h.opened.getValue("mem:syd-rail-v1.sqlite").closed)
    }

    @Test
    fun wifiOnlyDefersUpdatesOnMeteredNetwork() = runTest {
        val h = Harness(this)
        h.unmetered = false
        h.join()
        h.preinstall("v1")
        h.serveConfig(h.packInfo("v2"))
        h.core.start()
        runCurrent()
        assertEquals(listOf<PackUpdateResult>(PackUpdateResult.Deferred("syd-rail", "v2")), h.core.lastPackReport.first())
        assertTrue(h.server.requestsTo("/v1/packs/syd-rail/v2").isEmpty())
        assertTrue(h.core.dataStatus.first().packs.single().updateAvailable)

        // Six hours later on Wi-Fi the periodic check installs it.
        h.unmetered = true
        advanceTimeBy(6 * 3600_000L + 1)
        assertEquals(listOf<PackUpdateResult>(PackUpdateResult.Updated("syd-rail", "v1", "v2")), h.core.lastPackReport.first())
        assertEquals(2, h.server.requestsTo("/v1/config").size)
    }

    @Test
    fun firstInstallIgnoresWifiPolicy() = runTest {
        val h = Harness(this)
        h.unmetered = false
        h.join()
        h.serveConfig(h.packInfo("v1"))
        h.core.start()
        runCurrent()
        assertEquals(listOf<PackUpdateResult>(PackUpdateResult.Updated("syd-rail", null, "v1")), h.core.lastPackReport.first())
    }

    @Test
    fun upToDatePackIsNotDownloaded() = runTest {
        val h = Harness(this)
        h.join()
        h.preinstall("v1")
        h.serveConfig(h.packInfo("v1"))
        h.core.start()
        runCurrent()
        assertEquals(listOf<PackUpdateResult>(PackUpdateResult.UpToDate("syd-rail", "v1")), h.core.lastPackReport.first())
        assertTrue(h.server.requestsTo("/v1/packs/syd-rail/v1").isEmpty())
    }

    @Test
    fun notJoinedSkipsConfigUntilJoin() = runTest {
        val h = Harness(this)
        h.serveConfig(h.packInfo("v1"))
        h.core.start()
        runCurrent()
        assertTrue(h.server.requestsTo("/v1/config").isEmpty())
    }
}
