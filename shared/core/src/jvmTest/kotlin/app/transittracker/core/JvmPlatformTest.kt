package app.transittracker.core

import com.github.luben.zstd.Zstd
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmPlatformTest {
    @Test
    fun zstdRoundTrip() {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        assertContentEquals(data, ZstdDecompressor.decompress(Zstd.compress(data, 19)))
    }

    @Test
    fun sha256MatchesJdk() {
        val data = ByteArray(12_345) { (it * 31).toByte() }
        val jdk = java.security.MessageDigest.getInstance("SHA-256").digest(data)
        assertEquals(jdk.toHex(), Sha256.hex(data))
    }

    @Test
    fun fileStoreReplacesAtomically() = runTest {
        val dir = Files.createTempDirectory("tt-packs")
        val store = JvmFileStore(dir)
        val tmp = store.writeTemp(byteArrayOf(1, 2, 3))
        val path = store.replace(tmp, "syd-rail-v1.sqlite")
        assertTrue(store.exists("syd-rail-v1.sqlite"))
        assertFalse(Files.exists(java.nio.file.Path.of(tmp)))
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(java.nio.file.Path.of(path)))
        store.replace(store.writeTemp(byteArrayOf(4)), "syd-rail-v1.sqlite")
        assertContentEquals(byteArrayOf(4), Files.readAllBytes(java.nio.file.Path.of(path)))
        store.delete("syd-rail-v1.sqlite")
        assertFalse(store.exists("syd-rail-v1.sqlite"))
    }

    @Test
    fun fileKeyValueStorePersists() = runTest {
        val file = Files.createTempDirectory("tt-kv").resolve("kv.properties")
        val kv = FileKeyValueStore(file)
        kv.put("device.token", "abc")
        kv.put("trips", """[{"id":"x"}]""")
        kv.put("gone", "1")
        kv.put("gone", null)
        val reopened = FileKeyValueStore(file)
        assertEquals("abc", reopened.get("device.token"))
        assertEquals("""[{"id":"x"}]""", reopened.get("trips"))
        assertNull(reopened.get("gone"))
    }
}
