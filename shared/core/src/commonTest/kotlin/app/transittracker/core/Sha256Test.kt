package app.transittracker.core

import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256Test {
    @Test
    fun standardVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc".encodeToByteArray()))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )
        assertEquals(
            "cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1",
            Sha256.hex(
                ("abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu")
                    .encodeToByteArray(),
            ),
        )
    }

    @Test
    fun millionAs() {
        val bytes = ByteArray(1_000_000) { 'a'.code.toByte() }
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", Sha256.hex(bytes))
    }

    @Test
    fun incrementalUpdatesMatchOneShot() {
        val data = ByteArray(1000) { (it * 7 + 3).toByte() }
        val oneShot = Sha256.hex(data)
        for (chunk in listOf(1, 3, 63, 64, 65, 200)) {
            val h = Sha256()
            var i = 0
            while (i < data.size) {
                val n = minOf(chunk, data.size - i)
                h.update(data, i, n)
                i += n
            }
            assertEquals(oneShot, h.digest().toHex(), "chunk $chunk")
        }
    }

    @Test
    fun boundaryLengths() {
        // 55, 56 and 64 bytes exercise the padding edge cases; compare against known digests.
        assertEquals(
            "9f4390f8d30c2dd92ec9f095b65e2b9ae9b0a925a5258e241c9f1e910f734318",
            Sha256.hex(ByteArray(55) { 'a'.code.toByte() }),
        )
        assertEquals(
            "b35439a4ac6f0948b6d6f9e3c6af0f5f590ce20f1bde7090ef7970686ec6738a",
            Sha256.hex(ByteArray(56) { 'a'.code.toByte() }),
        )
        assertEquals(
            "ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb",
            Sha256.hex(ByteArray(64) { 'a'.code.toByte() }),
        )
    }
}
