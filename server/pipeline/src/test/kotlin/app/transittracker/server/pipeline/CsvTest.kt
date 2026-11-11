package app.transittracker.server.pipeline

import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CsvTest {
    private fun records(text: String): List<List<String>> {
        val r = CsvReader(StringReader(text))
        return generateSequence { r.readRecord() }.toList()
    }

    @Test
    fun plainRecordsWithAllLineEndings() {
        assertEquals(
            listOf(listOf("a", "b"), listOf("1", "2"), listOf("3", "4"), listOf("5", "6")),
            records("a,b\n1,2\r\n3,4\r5,6"),
        )
    }

    @Test
    fun quotedFieldsWithCommasQuotesAndNewlines() {
        val text = "id,name\n1,\"Wynyard, York St\"\n2,\"Say \"\"hi\"\"\"\n3,\"two\r\nlines\"\n4,\"\"\n"
        assertEquals(
            listOf(
                listOf("id", "name"),
                listOf("1", "Wynyard, York St"),
                listOf("2", "Say \"hi\""),
                listOf("3", "two\nlines"),
                listOf("4", ""),
            ),
            records(text),
        )
    }

    @Test
    fun byteOrderMarkIsSkipped() {
        assertEquals(listOf(listOf("stop_id", "x"), listOf("1", "2")), records("\uFEFFstop_id,x\n1,2\n"))
    }

    @Test
    fun emptyFieldsAndTrailingComma() {
        assertEquals(listOf(listOf("a", "", "c", "")), records("a,,c,\n"))
    }

    @Test
    fun recordLinesAccountForEmbeddedNewlines() {
        val r = CsvReader(StringReader("h\n\"a\nb\"\nc\n"))
        r.readRecord()
        r.readRecord()
        assertEquals(2, r.recordLine)
        r.readRecord()
        assertEquals(4, r.recordLine)
        assertNull(r.readRecord())
    }

    @Test
    fun unterminatedQuoteFails() {
        assertFailsWith<CsvException> { records("a\n\"oops\n") }
    }

    @Test
    fun tableTrimsAndHandlesMissingColumnsAndBlankLines() {
        val text = "\uFEFF stop_id , stop_name ,platform_code\r\n S1 , Central ,\r\n\r\nS2,\"Town Hall\"\r\nS3\r\n"
        val rows = ArrayList<Triple<String?, String?, String?>>()
        val table = GtfsTable("stops.txt", StringReader(text))
        assertEquals(listOf("stop_id", "stop_name", "platform_code"), table.columns)
        table.forEachRow { rows += Triple(it["stop_id"], it["stop_name"], it["wheelchair_boarding"]) }
        assertEquals(
            listOf<Triple<String?, String?, String?>>(Triple("S1", "Central", null), Triple("S2", "Town Hall", null), Triple("S3", null, null)),
            rows,
        )
    }
}
