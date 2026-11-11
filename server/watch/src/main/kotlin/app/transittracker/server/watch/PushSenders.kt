package app.transittracker.server.watch

import app.transittracker.server.spi.PushMessage
import app.transittracker.server.spi.PushResult
import app.transittracker.server.spi.PushSender
import app.transittracker.server.spi.ServerConfig
import com.google.auth.oauth2.ServiceAccountCredentials
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong

/**
 * [PushSender] used when Firebase is not configured: logs each message (never the token) and
 * reports it as sent, so the rest of the server behaves normally in development.
 */
class LoggingPushSender : PushSender {
    private val logger = LoggerFactory.getLogger(LoggingPushSender::class.java)
    private val counter = AtomicLong()

    override suspend fun send(token: String, message: PushMessage): PushResult {
        val id = "logged-${counter.incrementAndGet()}"
        logger.info(
            "Push (not sent, Firebase not configured) {}: [{}] {} | {} | collapse={} ttl={}s",
            id, message.data["kind"], message.title, message.body, message.collapseKey, message.ttlSeconds,
        )
        return PushResult.Sent(id)
    }
}

/** Builds the [PushSender] the server should use. */
object PushSenders {
    private val logger = LoggerFactory.getLogger(PushSenders::class.java)

    /**
     * [FcmPushSender] when [ServerConfig.firebaseServiceAccountPath] points at a readable
     * service-account JSON (project id from [ServerConfig.firebaseProjectId], else from the
     * service account); otherwise, or if the credentials cannot be loaded, [LoggingPushSender].
     */
    fun fromConfig(config: ServerConfig, transport: FcmTransport = JdkFcmTransport()): PushSender {
        val path = config.firebaseServiceAccountPath
        if (path == null) {
            logger.warn("FIREBASE_SERVICE_ACCOUNT not set: push notifications are logged, not sent")
            return LoggingPushSender()
        }
        if (!Files.isReadable(path)) {
            logger.error("Firebase service account file {} is not readable: push notifications are logged, not sent", path)
            return LoggingPushSender()
        }
        return try {
            val tokens = GoogleAccessTokenSource.fromServiceAccountFile(path)
            val projectId = config.firebaseProjectId ?: readProjectId(path)
            if (projectId.isNullOrBlank()) {
                logger.error("No Firebase project id (set FIREBASE_PROJECT_ID): push notifications are logged, not sent")
                LoggingPushSender()
            } else {
                logger.info("Push notifications via FCM for project {}", projectId)
                FcmPushSender(projectId, tokens, transport)
            }
        } catch (e: Exception) {
            logger.error("Could not load Firebase credentials: {}; push notifications are logged, not sent", e.toString())
            LoggingPushSender()
        }
    }

    private fun readProjectId(path: java.nio.file.Path): String? =
        runCatching { Files.newInputStream(path).use { ServiceAccountCredentials.fromStream(it).projectId } }.getOrNull()
}
