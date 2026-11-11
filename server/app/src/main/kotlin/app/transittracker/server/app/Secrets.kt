package app.transittracker.server.app

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Generation and hashing of the two kinds of secret the server hands out: device tokens and
 * invite codes. Only SHA-256 hashes of either are ever stored, and neither is ever logged.
 */
object Secrets {
    private val random = SecureRandom()

    /**
     * Invite alphabet: upper-case letters and digits without the easily confused
     * `0 O 1 I L` (and `U`, which reads like `V` in some fonts).
     */
    const val INVITE_ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789"

    /** Characters per half of an `XXXX-XXXX` invite code. */
    private const val INVITE_HALF = 4

    /** A new device token: 32 random bytes, base64url without padding (43 chars). */
    fun newDeviceToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** A new human-typeable one-time invite code, formatted `XXXX-XXXX`. */
    fun newInviteCode(): String {
        val chars = CharArray(INVITE_HALF * 2) { INVITE_ALPHABET[random.nextInt(INVITE_ALPHABET.length)] }
        return String(chars, 0, INVITE_HALF) + "-" + String(chars, INVITE_HALF, INVITE_HALF)
    }

    /**
     * Canonical form of a typed invite code (upper case, separators and spaces removed), or null
     * if it cannot be a code we issued. Lower-case input and missing dashes are accepted.
     */
    fun normalizeInviteCode(input: String): String? {
        val cleaned = input.uppercase().filter { it != '-' && it != ' ' && it != '\t' }
        if (cleaned.length != INVITE_HALF * 2) return null
        if (cleaned.any { it !in INVITE_ALPHABET }) return null
        return cleaned
    }

    /** Lower-case hex SHA-256 of the UTF-8 bytes of [value]. */
    fun sha256Hex(value: String): String = sha256Hex(value.toByteArray(Charsets.UTF_8))

    /** Lower-case hex SHA-256 of [bytes]. */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Constant-time comparison of two secrets. */
    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
