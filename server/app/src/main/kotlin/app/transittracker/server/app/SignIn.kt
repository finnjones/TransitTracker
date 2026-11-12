package app.transittracker.server.app

import app.transittracker.server.spi.ServerConfig
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.auth.oauth2.TokenVerifier
import org.slf4j.LoggerFactory
import java.nio.file.Files

/** An account whose Firebase Authentication ID token checked out. [email] is lower case. */
data class VerifiedUser(val uid: String, val email: String?, val emailVerified: Boolean)

/** Checks an ID token from the app's sign-in; null when it is not a valid token for this server's project. */
fun interface IdTokenVerifier {
    fun verify(idToken: String): VerifiedUser?
}

/**
 * Verifies Firebase Authentication ID tokens as Firebase documents it: an RS256 JWT signed by one
 * of Google's `securetoken` keys, for audience [projectId], issued by
 * `https://securetoken.google.com/<projectId>`, not expired, with a non-empty subject (the uid).
 * The keys are fetched from Google and cached by [TokenVerifier]. Blocking: call it off the
 * request thread.
 */
class FirebaseIdTokenVerifier(
    val projectId: String,
    certificatesUrl: String = CERTIFICATES_URL,
) : IdTokenVerifier {
    private val verifier = TokenVerifier.newBuilder()
        .setAudience(projectId)
        .setIssuer("https://securetoken.google.com/$projectId")
        .setCertificatesLocation(certificatesUrl)
        .build()

    override fun verify(idToken: String): VerifiedUser? {
        if (idToken.isBlank() || idToken.length > MAX_TOKEN) return null
        val payload = try {
            verifier.verify(idToken).payload
        } catch (e: TokenVerifier.VerificationException) {
            log.info("Sign-in token refused: {}", e.message)
            return null
        } catch (e: IllegalArgumentException) {
            // Not a JWT at all (the parser checks its shape with preconditions).
            log.info("Sign-in token refused: malformed")
            return null
        }
        val uid = payload.subject?.takeIf { it.isNotBlank() && it.length <= MAX_UID } ?: return null
        val email = (payload["email"] as? String)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val verified = payload["email_verified"] as? Boolean ?: false
        return VerifiedUser(uid, email, verified)
    }

    companion object {
        private val log = LoggerFactory.getLogger(FirebaseIdTokenVerifier::class.java)

        /** Google's public keys for Firebase Authentication ID tokens (x509 certificates by key id). */
        const val CERTIFICATES_URL = "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com"
        private const val MAX_TOKEN = 8192
        private const val MAX_UID = 128

        /**
         * The verifier for this server's Firebase project: [ServerConfig.firebaseProjectId], else
         * the project of the service account in [ServerConfig.firebaseServiceAccountPath] (the
         * one push uses). Null, with a warning, when neither names a project: sign-in is then off
         * and only invite codes can join.
         */
        fun fromConfig(config: ServerConfig): FirebaseIdTokenVerifier? {
            val projectId = config.firebaseProjectId
                ?: config.firebaseServiceAccountPath
                    ?.takeIf { Files.isReadable(it) }
                    ?.let { path -> runCatching { Files.newInputStream(path).use { ServiceAccountCredentials.fromStream(it).projectId } }.getOrNull() }
            if (projectId.isNullOrBlank()) {
                log.warn("No Firebase project (FIREBASE_PROJECT_ID / FIREBASE_SERVICE_ACCOUNT): sign-in is off, only invite codes can join")
                return null
            }
            log.info("Sign-in with Firebase Authentication for project {}", projectId)
            return FirebaseIdTokenVerifier(projectId)
        }
    }
}

/**
 * Who may sign in, as patterns kept in the `allowed_users` table (`tt-admin allow`):
 *
 * - `name@example.com`: that account (its email must be verified, as Google accounts always are);
 * - `*@example.com`: every verified account at that domain;
 * - `*`: anyone who signs in.
 */
object AllowedUsers {
    const val ANYONE = "*"
    private val EMAIL = Regex("[^@\\s*]+@[^@\\s*]+\\.[^@\\s*]+")
    private val DOMAIN = Regex("\\*@[^@\\s*]+\\.[^@\\s*]+")

    /** [input] lower case and trimmed when it is one of the three forms; null otherwise. */
    fun normalize(input: String): String? {
        val p = input.trim().lowercase()
        return p.takeIf { it == ANYONE || EMAIL.matches(it) || DOMAIN.matches(it) }
    }

    /** True when [pattern] lets [user] in. */
    fun matches(pattern: String, user: VerifiedUser): Boolean = matches(pattern, user.email, user.emailVerified)

    fun matches(pattern: String, email: String?, emailVerified: Boolean): Boolean {
        if (pattern == ANYONE) return true
        if (email == null || !emailVerified) return false
        val e = email.lowercase()
        return if (pattern.startsWith("*@")) e.endsWith(pattern.substring(1)) else e == pattern
    }
}
