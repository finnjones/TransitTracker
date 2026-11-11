package app.transittracker.timetable

import app.cash.sqldelight.Query
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * A SQLDelight JDBC driver for read-mostly SQLite files that keeps idle connections open and
 * reuses them across threads. (`JdbcSqliteDriver` opens and closes a connection for every
 * statement outside a transaction, which is slow for the many small lookups a timetable does.)
 * Each statement borrows a connection, so concurrent readers never share one.
 */
internal class PooledSqliteDriver(
    private val url: String,
    private val properties: Properties,
    private val maxIdle: Int = 8,
) : JdbcDriver() {
    private val idle = ConcurrentLinkedDeque<Connection>()
    private val idleCount = AtomicInteger()

    @Volatile
    private var closed = false

    override fun getConnection(): Connection {
        check(!closed) { "Driver for $url is closed" }
        idle.pollFirst()?.let {
            idleCount.decrementAndGet()
            return it
        }
        return DriverManager.getConnection(url, properties)
    }

    override fun closeConnection(connection: Connection) {
        if (!closed && idleCount.incrementAndGet() <= maxIdle) {
            idle.offerFirst(connection)
            if (closed) drain()
        } else {
            if (!closed) idleCount.decrementAndGet()
            connection.close()
        }
    }

    private fun drain() {
        while (true) {
            val c = idle.pollFirst() ?: break
            runCatching { c.close() }
        }
    }

    override fun close() {
        closed = true
        drain()
    }

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) = Unit

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = Unit

    override fun notifyListeners(vararg queryKeys: String) = Unit
}
