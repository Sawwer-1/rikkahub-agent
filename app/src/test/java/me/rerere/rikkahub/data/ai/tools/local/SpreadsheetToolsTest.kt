package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/**
 * Spreadsheet tools (excel_write / csv_parse / csv_write). The file:// execute paths never
 * touch the Context, so the tools round-trip fully on the JVM; the content:// branches need
 * an instrumented test.
 */
class SpreadsheetToolsTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private fun zipEntryNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                names.add(entry.name)
                zis.closeEntry()
            }
        }
        return names
    }

    // ---------- CSV core ----------

    @Test fun `parseCsv handles quotes escapes and line endings`() {
        val csv = "name,note\r\n\"李,四\",\"said \"\"hi\"\"\"\r\nplain,second\nlast,\"multi\r\nline\"\r\n"
        val rows = parseCsv(csv)
        assertEquals(4, rows.size)
        assertEquals(listOf("name", "note"), rows[0])
        assertEquals(listOf("李,四", "said \"hi\""), rows[1])
        assertEquals(listOf("plain", "second"), rows[2])
        assertEquals(listOf("last", "multi\nline"), rows[3])
    }

    @Test fun `parseCsv honours a custom delimiter`() {
        val rows = parseCsv("a;b\nc;d\n", ';')
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), rows)
    }

    @Test fun `parseCsv keeps an empty last field`() {
        val rows = parseCsv("a,b,\n")
        assertEquals(listOf(listOf("a", "b", "")), rows)
    }

    @Test fun `buildCsv and parseCsv round-trip tricky fields`() {
        val rows = listOf(
            listOf("名称", "值"),
            listOf("has, comma", "has\"quote"),
            listOf("has\nnewline", "plain"),
        )
        val csv = buildCsv(rows)
        assertEquals(rows, parseCsv(csv))
    }

    @Test fun `decodeCsvBytes strips BOM and falls back to GBK`() {
        val bomUtf8 = "\uFEFF名称,值\n"
        assertEquals("名称,值\n", decodeCsvBytes(bomUtf8.toByteArray(Charsets.UTF_8)))
        val gbkText = "名称,值\n测试,1\n"
        val decoded = decodeCsvBytes(gbkText.toByteArray(Charset.forName("GBK")))
        assertEquals(gbkText, decoded)
        assertEquals("", decodeCsvBytes(ByteArray(0)))
    }

    @Test fun `rowsToMarkdown renders a header table and escapes pipes`() {
        val md = rowsToMarkdown(listOf("a|b", "c"), listOf(listOf("1", "2")))
        assertTrue(md.startsWith("| a\\|b | c |"))
        assertTrue(md.contains("| --- | --- |"))
        assertTrue(md.contains("| 1 | 2 |"))
    }

    // ---------- XLSX core ----------

    @Test fun `xlsxColumnName goes past Z`() {
        assertEquals("A", xlsxColumnName(0))
        assertEquals("Z", xlsxColumnName(25))
        assertEquals("AA", xlsxColumnName(26))
        assertEquals("BA", xlsxColumnName(52))
    }

    @Test fun `sanitizeSheetNames clamps and dedupes`() {
        val names = sanitizeSheetNames(listOf("报:表", "Sheet1", "Sheet1", "", "x".repeat(40)))
        assertEquals(listOf("报_表", "Sheet1", "Sheet1_2", "Sheet4", "x".repeat(31)), names)
    }

    @Test fun `excel_write produces a structurally valid xlsx zip`() {
        val xlsxPath = "${tmp.root.absolutePath}/report.xlsx"
        val result = obj(execTool(
            excelWriteTool(NULL_CONTEXT),
            """{"path":"$xlsxPath","sheets":[{"name":"数据","rows":[["名称","数量"],["苹果",42],["熟",true],["空",null]]}]}""",
        ))
        assertTrue("expected success, got $result", result["success"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(File(xlsxPath).exists())

        val names = zipEntryNames(File(xlsxPath).readBytes())
        // xlsx is a zip with a mandatory OOXML part set.
        assertTrue(names.contains("[Content_Types].xml"))
        assertTrue(names.contains("_rels/.rels"))
        assertTrue(names.contains("xl/workbook.xml"))
        assertTrue(names.contains("xl/_rels/workbook.xml.rels"))
        assertTrue(names.contains("xl/sharedStrings.xml"))
        assertTrue(names.contains("xl/styles.xml"))
        assertTrue(names.contains("xl/worksheets/sheet1.xml"))

        val zip = java.util.zip.ZipFile(File(xlsxPath))
        val sheet1 = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).bufferedReader().readText()
        zip.close()
        // Header cells are shared strings; number/boolean cells are typed.
        assertTrue(sheet1.contains("<c r=\"A1\" t=\"s\">"))
        assertTrue(sheet1.contains("<c r=\"B2\"><v>42</v></c>"))
        assertTrue(sheet1.contains("<c r=\"B3\" t=\"b\"><v>1</v></c>"))
        assertFalse(sheet1.contains("空</t>")) // null cell must not leak into shared strings
        val workbook = java.util.zip.ZipFile(File(xlsxPath)).let { zf ->
            val text = zf.getInputStream(zf.getEntry("xl/workbook.xml")).bufferedReader().readText()
            zf.close()
            text
        }
        assertTrue(workbook.contains("name=\"数据\""))
    }

    @Test fun `excel_write refuses to overwrite by default and honours overwrite`() {
        val xlsxPath = "${tmp.root.absolutePath}/dup.xlsx"
        val args = """{"path":"$xlsxPath","sheets":[{"rows":[["a"]]}]}"""
        val first = obj(execTool(excelWriteTool(NULL_CONTEXT), args))
        assertTrue(first["success"]!!.jsonPrimitive.content.toBoolean())
        val second = obj(execTool(excelWriteTool(NULL_CONTEXT), args))
        assertEquals("file_exists", second["error"]?.jsonPrimitive?.content)
        val third = obj(execTool(excelWriteTool(NULL_CONTEXT), args.dropLast(1) + ",\"overwrite\":true}"))
        assertTrue(third["success"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test fun `excel_write rejects system paths`() {
        val result = obj(execTool(
            excelWriteTool(NULL_CONTEXT),
            """{"path":"/system/etc/pwn.xlsx","sheets":[{"rows":[["a"]]}]}""",
        ))
        assertEquals("path_blocked", result["error"]?.jsonPrimitive?.content)
    }

    @Test fun `excel_write rejects an empty sheet list`() {
        val result = obj(execTool(
            excelWriteTool(NULL_CONTEXT),
            """{"path":"${tmp.root.absolutePath}/empty.xlsx","sheets":[]}""",
        ))
        assertEquals("empty_sheets", result["error"]?.jsonPrimitive?.content)
    }

    // ---------- csv tools ----------

    @Test fun `csv_write then csv_parse round-trips via the tools`() {
        val csvPath = "${tmp.root.absolutePath}/table.csv"
        val writeArgs = """{"path":"$csvPath","rows":[["名称","值"],["中文",1],[true,"x,y"]]}"""
        val written = obj(execTool(csvWriteTool(NULL_CONTEXT), writeArgs))
        assertTrue("expected success, got $written", written["success"]!!.jsonPrimitive.content.toBoolean())
        // BOM + CRLF: Excel-compatible on-disk shape.
        val rawBytes = File(csvPath).readBytes()
        assertTrue(rawBytes.size >= 3 && rawBytes[0] == 0xEF.toByte() && rawBytes[1] == 0xBB.toByte() && rawBytes[2] == 0xBF.toByte())
        val rawText = rawBytes.toString(Charsets.UTF_8)
        assertTrue(rawText.contains("\r\n"))

        val parsed = obj(execTool(csvParseTool(NULL_CONTEXT), """{"path":"$csvPath"}"""))
        assertTrue(parsed["success"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(2, parsed["row_count"]!!.jsonPrimitive.content.toInt())
        val columns = parsed["columns"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("名称", "值"), columns)
        val rows = parsed["rows"]!!.jsonArray
        assertEquals(2, rows.size)
        assertEquals("1", rows[0].jsonArray[1].jsonPrimitive.content)
        assertEquals("x,y", rows[1].jsonArray[1].jsonPrimitive.content)
        assertNotNull(parsed["markdown"]!!.jsonPrimitive.content)
    }

    @Test fun `csv_parse caps rows and reports truncation`() {
        val csvPath = tmp.newFile("big.csv").apply {
            writeText((1..20).joinToString("\n") { "r$it,${it * 2}" } + "\n")
        }
        val parsed = obj(execTool(
            csvParseTool(NULL_CONTEXT),
            """{"path":"${csvPath.absolutePath}","header":false,"max_rows":5}""",
        ))
        assertEquals(20, parsed["row_count"]!!.jsonPrimitive.content.toInt())
        assertEquals(5, parsed["rows"]!!.jsonArray.size)
        assertTrue(parsed["truncated"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test fun `csv_parse requires a source`() {
        val parsed = obj(execTool(csvParseTool(NULL_CONTEXT), "{}"))
        assertEquals("missing_source", parsed["error"]?.jsonPrimitive?.content)
    }

    @Test fun `csv_write rejects system paths`() {
        val parsed = obj(execTool(
            csvWriteTool(NULL_CONTEXT),
            """{"path":"/data/data/com.other/app.csv","rows":[["a"]]}""",
        ))
        assertEquals("path_blocked", parsed["error"]?.jsonPrimitive?.content)
    }
}
