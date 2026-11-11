package app.transittracker.server.pipeline

import java.io.Closeable
import java.io.Reader

/** Malformed CSV that cannot be read past (e.g. an unterminated quoted field). */
class CsvException(message: String) : RuntimeException(message)

/**
 * Streaming RFC 4180 CSV reader as used by GTFS: comma separated, fields optionally quoted with
 * `"`, quotes escaped by doubling, quoted fields may contain commas and line breaks. Accepts
 * LF, CRLF and lone CR line endings and skips a leading UTF-8 byte-order mark. Never holds more
 * than one record in memory.
 */
class CsvReader(private val reader: Reader) : Closeable {
    private val buf = CharArray(64 * 1024)
    private var len = 0
    private var pos = 0
    private var started = false
    private var line = 1

    /** 1-based line on which the most recently returned record started. */
    var recordLine: Int = 0
        private set

    private fun next(): Int {
        if (pos >= len) {
            len = reader.read(buf, 0, buf.size)
            pos = 0
            if (len <= 0) {
                len = 0
                return -1
            }
        }
        return buf[pos++].code
    }

    private fun peek(): Int {
        if (pos >= len) {
            len = reader.read(buf, 0, buf.size)
            pos = 0
            if (len <= 0) {
                len = 0
                return -1
            }
        }
        return buf[pos].code
    }

    /** Reads the next record, or null at end of input. A blank line yields a single empty field. */
    fun readRecord(): List<String>? {
        if (!started) {
            started = true
            if (peek() == 0xFEFF) next()
        }
        val fields = ArrayList<String>(16)
        val sb = StringBuilder(32)
        var inQuotes = false
        var any = false
        recordLine = line
        while (true) {
            val c = next()
            if (c == -1) {
                if (inQuotes) throw CsvException("Unterminated quoted field starting on line $recordLine")
                if (!any) return null
                fields += sb.toString()
                return fields
            }
            any = true
            val ch = c.toChar()
            if (inQuotes) {
                when (ch) {
                    '"' -> if (peek() == '"'.code) {
                        next()
                        sb.append('"')
                    } else {
                        inQuotes = false
                    }
                    '\n' -> {
                        line++
                        sb.append(ch)
                    }
                    '\r' -> {
                        if (peek() == '\n'.code) next()
                        line++
                        sb.append('\n')
                    }
                    else -> sb.append(ch)
                }
                continue
            }
            when (ch) {
                ',' -> {
                    fields += sb.toString()
                    sb.setLength(0)
                }
                // A quote opens a quoted section only at the start of a field; elsewhere it is literal.
                '"' -> if (sb.isBlank()) {
                    sb.setLength(0)
                    inQuotes = true
                } else {
                    sb.append(ch)
                }
                '\r' -> {
                    if (peek() == '\n'.code) next()
                    line++
                    fields += sb.toString()
                    return fields
                }
                '\n' -> {
                    line++
                    fields += sb.toString()
                    return fields
                }
                else -> sb.append(ch)
            }
        }
    }

    override fun close() = reader.close()
}

/**
 * A GTFS table: a [CsvReader] whose first record is the header. Column names are trimmed;
 * values are trimmed and empty values read as null. Missing columns read as null too, so
 * optional columns need no special handling.
 */
class GtfsTable(val fileName: String, reader: Reader) : Closeable {
    private val csv = CsvReader(reader)

    /** Header names in file order. */
    val columns: List<String> = csv.readRecord()?.map { it.trim() } ?: emptyList()

    private val index: Map<String, Int> = columns.withIndex().reversed().associate { it.value to it.index }

    fun has(column: String): Boolean = column in index

    /** Calls [action] for every non-blank data row. */
    fun forEachRow(action: (GtfsRow) -> Unit) {
        while (true) {
            val record = csv.readRecord() ?: return
            if (record.size == 1 && record[0].isBlank()) continue
            action(GtfsRow(fileName, csv.recordLine, record, index))
        }
    }

    override fun close() = csv.close()
}

/** One data row of a [GtfsTable]. */
class GtfsRow internal constructor(
    val fileName: String,
    val line: Int,
    private val values: List<String>,
    private val index: Map<String, Int>,
) {
    /** Trimmed value of [column], or null if the column is absent or the value empty. */
    operator fun get(column: String): String? =
        index[column]?.let { values.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }

    val fieldCount: Int get() = values.size
}
