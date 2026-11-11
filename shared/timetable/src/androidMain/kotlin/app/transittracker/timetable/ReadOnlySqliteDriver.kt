package app.transittracker.timetable

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteProgram
import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * A query-only SQLDelight driver over Android's framework SQLite for timetable packs: prebuilt
 * files that must never change.
 *
 * - Every connection is opened with [SQLiteDatabase.OPEN_READONLY], so SQLite itself refuses
 *   writes. There is no `SQLiteOpenHelper` (which would create or upgrade a schema and write
 *   `user_version`), [SQLiteDatabase.NO_LOCALIZED_COLLATORS] avoids the `android_metadata` table,
 *   and the corruption handler keeps the file (the framework's default handler deletes it).
 * - [execute] and transactions are refused: a pack is only queried.
 * - Like the JVM's `PooledSqliteDriver`, each statement borrows a connection of its own (a
 *   `SQLiteDatabase` opened on the file) and returns it when the cursor is fully read, so
 *   concurrent readers on several coroutines never share one; up to [maxIdle] stay open.
 * - Arguments are bound with their types (long, double, text, blob, null) through the cursor
 *   factory, the way `androidx.sqlite`'s framework driver binds them, instead of as strings.
 */
internal class ReadOnlySqliteDriver(
    private val path: String,
    private val maxIdle: Int = 4,
) : SqlDriver {
    private val idle = ConcurrentLinkedDeque<SQLiteDatabase>()
    private val idleCount = AtomicInteger()

    @Volatile
    private var closed = false

    /** Opens one connection now, so a missing or unreadable file fails at open time. */
    fun verify() {
        release(borrow())
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        val db = borrow()
        try {
            val factory = SQLiteDatabase.CursorFactory { _, masterQuery, editTable, query ->
                if (binders != null) TypedBinder(query).binders()
                SQLiteCursor(masterQuery, editTable, query)
            }
            val cursor = db.rawQueryWithFactory(factory, sql, null, null)
            return try {
                mapper(FrameworkCursor(cursor))
            } finally {
                cursor.close()
            }
        } finally {
            release(db)
        }
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> = throw UnsupportedOperationException("Timetable packs are read-only")

    override fun newTransaction(): QueryResult<Transacter.Transaction> =
        throw UnsupportedOperationException("Timetable packs are read-only")

    override fun currentTransaction(): Transacter.Transaction? = null

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) = Unit

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = Unit

    override fun notifyListeners(vararg queryKeys: String) = Unit

    override fun close() {
        closed = true
        drain()
    }

    private fun borrow(): SQLiteDatabase {
        check(!closed) { "Driver for $path is closed" }
        idle.pollFirst()?.let {
            idleCount.decrementAndGet()
            return it
        }
        return SQLiteDatabase.openDatabase(
            path,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            KeepFileOnCorruption,
        )
    }

    private fun release(db: SQLiteDatabase) {
        if (!closed && idleCount.incrementAndGet() <= maxIdle) {
            idle.offerFirst(db)
            if (closed) drain()
        } else {
            if (!closed) idleCount.decrementAndGet()
            db.close()
        }
    }

    private fun drain() {
        while (true) {
            val db = idle.pollFirst() ?: break
            runCatching { db.close() }
        }
    }

    /** The framework's default handler deletes a corrupt database file; a pack is replaced by the updater instead. */
    private object KeepFileOnCorruption : DatabaseErrorHandler {
        override fun onCorruption(dbObj: SQLiteDatabase) = Unit
    }

    /** SQLDelight indexes parameters from 0; SQLite from 1. */
    private class TypedBinder(private val program: SQLiteProgram) : SqlPreparedStatement {
        override fun bindBytes(index: Int, bytes: ByteArray?) {
            if (bytes == null) program.bindNull(index + 1) else program.bindBlob(index + 1, bytes)
        }

        override fun bindLong(index: Int, long: Long?) {
            if (long == null) program.bindNull(index + 1) else program.bindLong(index + 1, long)
        }

        override fun bindDouble(index: Int, double: Double?) {
            if (double == null) program.bindNull(index + 1) else program.bindDouble(index + 1, double)
        }

        override fun bindString(index: Int, string: String?) {
            if (string == null) program.bindNull(index + 1) else program.bindString(index + 1, string)
        }

        override fun bindBoolean(index: Int, boolean: Boolean?) {
            if (boolean == null) program.bindNull(index + 1) else program.bindLong(index + 1, if (boolean) 1L else 0L)
        }
    }

    private class FrameworkCursor(private val cursor: android.database.Cursor) : SqlCursor {
        override fun next(): QueryResult<Boolean> = QueryResult.Value(cursor.moveToNext())

        override fun getString(index: Int): String? = if (cursor.isNull(index)) null else cursor.getString(index)

        override fun getLong(index: Int): Long? = if (cursor.isNull(index)) null else cursor.getLong(index)

        override fun getBytes(index: Int): ByteArray? = if (cursor.isNull(index)) null else cursor.getBlob(index)

        override fun getDouble(index: Int): Double? = if (cursor.isNull(index)) null else cursor.getDouble(index)

        override fun getBoolean(index: Int): Boolean? = if (cursor.isNull(index)) null else cursor.getLong(index) == 1L
    }
}
