package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Spreadsheet tools (ported from orangechat CsvParser / ExcelGenerator, adapted to the fork's
 * Tool architecture). Three tools, all path-sandboxed the same way as the file-manager
 * surface:
 *
 *  - excel_write: table data (sheets -> rows -> typed cells) -> a minimal OOXML .xlsx built
 *    by hand with java.util.zip + XML strings (NO Apache POI — orangechat's approach, kept
 *    dependency-free; cells are typed so numbers/booleans land as real Excel cells, which
 *    orangechat's all-shared-strings writer did not do).
 *  - csv_parse: a CSV file (or inline text) -> structured JSON rows + a bounded Markdown
 *    table preview. RFC4180 state machine with quoted fields, escaped quotes and CRLF.
 *  - csv_write: rows -> an Excel-compatible CSV (UTF-8 with BOM + RFC4180 quoting), so
 *    double-clicking it in Excel keeps non-ASCII intact.
 *
 * The pure logic below the tool factories is JVM-testable (no android.* calls on the
 * execute paths that take file:// arguments; content:// branches need an instrumented test).
 */

private fun spreadErr(code: String, vararg extra: Pair<String, String>) =
    listOf(UIMessagePart.Text(buildJsonObject {
        put("error", code)
        extra.forEach { put(it.first, it.second) }
    }.toString()))

// ---------- ceilings (LLM-supplied payload defence) ----------

/** Reject spreadsheets whose total cell count crosses this. */
internal const val SPREADSHEET_MAX_CELLS = 250_000

/** Reject CSV reads whose source file is larger than this. 5 MiB. */
internal const val CSV_MAX_SOURCE_BYTES = 5L * 1024 * 1024

// ---------- path-safety guard (same shape as ArchiveTools.guardArchivePath) ----------

private fun isContent(raw: String) = raw.startsWith("content://")

/**
 * Guard a single path argument: content:// URIs go through [ContentUriSafetyGuard]
 * (structural; the SAF grant is the real gate), every other path is `~`-expanded then run
 * through [PathSafetyGuard]. Returns null when safe, otherwise an error envelope.
 */
private fun guardSpreadsheetPath(raw: String, mutation: Boolean = false): List<UIMessagePart>? {
    if (isContent(raw)) {
        val violation = if (mutation) {
            ContentUriSafetyGuard.checkMutation(raw)
        } else {
            ContentUriSafetyGuard.check(raw)
        }
        violation?.let { return spreadErr(it.code, "detail" to it.detail) }
        return null
    }
    val expanded = AgentWorkspace.expand(raw).removePrefix("file://")
    val violation = if (mutation) {
        PathSafetyGuard.checkMutation(expanded)
    } else {
        PathSafetyGuard.check(expanded)
    }
    violation?.let { return spreadErr(it.code, "detail" to it.detail) }
    return null
}

private fun openSpreadsheetInput(context: Context, raw: String): InputStream? = if (isContent(raw)) {
    runCatching { ContentUriResolver.openInput(context, raw) }.getOrNull()
} else {
    val f = File(raw.removePrefix("file://"))
    if (f.exists() && f.isFile) runCatching { f.inputStream() }.getOrNull() else null
}

private fun openSpreadsheetOutput(context: Context, raw: String): OutputStream? = if (isContent(raw)) {
    runCatching { ContentUriResolver.openOutput(context, raw) }.getOrNull()
} else {
    val f = File(raw.removePrefix("file://"))
    runCatching { f.parentFile?.mkdirs(); f.outputStream() }.getOrNull()
}

// ---------- CSV core (RFC4180 state machine, adapted from orangechat CsvParser) ----------

/**
 * Parse [csv] into rows. Handles quoted fields, `""` escapes, CR / LF / CRLF line endings
 * and an arbitrary single-character [delimiter]. Never throws: malformed input degrades to
 * literal field text.
 */
internal fun parseCsv(csv: String, delimiter: Char = ','): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    val currentRow = mutableListOf<String>()
    val currentField = StringBuilder()
    var inQuotes = false
    var i = 0
    var sawAnyField = false

    fun endField() {
        currentRow.add(currentField.toString())
        currentField.setLength(0)
        sawAnyField = true
    }

    fun endRow() {
        if (sawAnyField) {
            rows.add(currentRow.toList())
            currentRow.clear()
            sawAnyField = false
        }
    }

    while (i < csv.length) {
        val c = csv[i]
        if (inQuotes) {
            if (c == '"') {
                if (i + 1 < csv.length && csv[i + 1] == '"') {
                    currentField.append('"')
                    i += 2
                } else {
                    inQuotes = false
                    i++
                }
            } else {
                if (c == '\r' && i + 1 < csv.length && csv[i + 1] == '\n') {
                    // CRLF inside a quoted field folds to LF (universal newline).
                    currentField.append('\n')
                    i += 2
                } else {
                    currentField.append(c)
                    i++
                }
            }
            continue
        }
        when (c) {
            delimiter -> {
                endField()
                i++
            }
            '"' -> {
                inQuotes = true
                i++
            }
            '\r' -> {
                endField()
                endRow()
                if (i + 1 < csv.length && csv[i + 1] == '\n') i += 2 else i++
            }
            '\n' -> {
                endField()
                endRow()
                i++
            }
            else -> {
                currentField.append(c)
                i++
            }
        }
    }
    if (sawAnyField || currentField.isNotEmpty()) {
        endField()
        endRow()
    }
    return rows
}

/**
 * Strip a leading U+FEFF BOM and decode [bytes] as UTF-8, falling back to GBK when the
 * bytes are not valid UTF-8 (CSV exports from Chinese Windows Excel are commonly GBK —
 * same rationale as the zip entry-name charset detection in ArchiveTools).
 */
internal fun decodeCsvBytes(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val utf8 = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()
    val text = utf8 ?: String(bytes, Charset.forName("GBK"))
    return text.removePrefix("\uFEFF")
}

/** Quote a single field per RFC4180 when it contains the delimiter, a quote or a newline. */
internal fun csvEscapeField(field: String, delimiter: Char): String =
    if (field.contains(delimiter) || field.contains('"') ||
        field.contains('\r') || field.contains('\n')
    ) {
        "\"" + field.replace("\"", "\"\"") + "\""
    } else {
        field
    }

/** Build a full CSV document (CRLF line endings, Excel-compatible). */
internal fun buildCsv(rows: List<List<String>>, delimiter: Char = ','): String =
    rows.joinToString("\r\n") { row ->
        row.joinToString(delimiter.toString()) { csvEscapeField(it, delimiter) }
    }

/** Render rows as a Markdown table for model consumption (bounded preview upstream). */
internal fun rowsToMarkdown(headers: List<String>?, rows: List<List<String>>): String {
    val sb = StringBuilder()
    fun esc(s: String) = s.replace("|", "\\|").replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
    fun emit(cells: List<String>) {
        sb.append("| ")
        sb.append(cells.joinToString(" | ") { esc(it) })
        sb.append(" |\n")
    }
    val width = (headers?.size ?: 0).coerceAtLeast(rows.maxOfOrNull { it.size } ?: 0)
    fun pad(cells: List<String>): List<String> = cells + List(width - cells.size) { "" }
    if (headers != null && headers.isNotEmpty()) {
        emit(pad(headers))
        sb.append("|")
        repeat(width) { sb.append(" --- |") }
        sb.append('\n')
    } else if (rows.isNotEmpty()) {
        sb.append("|")
        repeat(width) { sb.append(" --- |") }
        sb.append('\n')
    }
    rows.forEach { emit(pad(it)) }
    return sb.toString().trimEnd('\n')
}

// ---------- XLSX core (minimal OOXML by hand, adapted from orangechat ExcelGenerator) ----------

internal sealed class XlsxCell {
    data class Text(val value: String) : XlsxCell()
    data class Num(val value: Double) : XlsxCell()
    data class Bool(val value: Boolean) : XlsxCell()
    data object Blank : XlsxCell()
}

internal data class XlsxSheet(
    val name: String,
    val rows: List<List<XlsxCell>>,
)

/** Map a JSON cell value onto a typed [XlsxCell]: null->Blank, number->Num, bool->Bool, rest->Text. */
internal fun jsonToXlsxCell(element: JsonElement): XlsxCell = when {
    element is JsonNull -> XlsxCell.Blank
    element !is JsonPrimitive -> XlsxCell.Text(element.toString())
    element.isString -> XlsxCell.Text(element.content)
    element.booleanOrNull != null -> XlsxCell.Bool(element.booleanOrNull!!)
    element.doubleOrNull != null -> XlsxCell.Num(element.doubleOrNull!!)
    else -> XlsxCell.Text(element.content)
}

/** Excel sheet-name sanitation: reserved chars out, <=31 chars, non-empty, de-duplicated. */
internal fun sanitizeSheetNames(requested: List<String>): List<String> {
    val seen = HashSet<String>()
    return requested.mapIndexed { index, rawName ->
        var name = rawName.replace(Regex("[:\\\\/?*\\[\\]]"), "_").trim().take(31)
        if (name.isEmpty()) name = "Sheet${index + 1}"
        var unique = name
        var n = 2
        while (!seen.add(unique)) {
            // take(27) keeps "name_suffix" within Excel's 31-char ceiling even for n >= 100.
            unique = "${name.take(27)}_$n"
            n++
        }
        unique
    }
}

internal fun xlsxColumnName(index: Int): String {
    var result = ""
    var i = index
    while (i >= 0) {
        result = ('A' + (i % 26)) + result
        i = (i / 26) - 1
    }
    return result
}

internal fun escapeXml(text: String): String = buildString(text.length) {
    for (ch in text) {
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(ch)
        }
    }
}

private fun formatXlsxNumber(value: Double): String =
    if (value == value.toLong().toDouble() && kotlin.math.abs(value) < 1e15) {
        value.toLong().toString()
    } else {
        value.toString()
    }

internal fun buildXlsx(sheets: List<XlsxSheet>): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    ZipOutputStream(out).use { zipOut ->
        val sharedStrings = LinkedHashSet<String>()
        sheets.forEach { sheet ->
            sheet.rows.forEach { row ->
                row.forEach { cell ->
                    if (cell is XlsxCell.Text) sharedStrings.add(cell.value)
                }
            }
        }
        val stringIndex = sharedStrings.withIndex().associate { it.value to it.index }

        writeZipEntry(zipOut, "[Content_Types].xml", buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
            append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
            append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
            append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
            append("<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>")
            append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
            sheets.forEachIndexed { index, _ ->
                append("<Override PartName=\"/xl/worksheets/sheet${index + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
            }
            append("</Types>")
        })

        writeZipEntry(zipOut, "_rels/.rels", buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
            append("<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>")
            append("</Relationships>")
        })

        writeZipEntry(zipOut, "xl/workbook.xml", buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
            append("<sheets>")
            sheets.forEachIndexed { index, sheet ->
                append("<sheet name=\"${escapeXml(sheet.name)}\" sheetId=\"${index + 1}\" r:id=\"rId${index + 1}\"/>")
            }
            append("</sheets></workbook>")
        })

        writeZipEntry(zipOut, "xl/_rels/workbook.xml.rels", buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
            sheets.forEachIndexed { index, _ ->
                append("<Relationship Id=\"rId${index + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${index + 1}.xml\"/>")
            }
            val sharedStringsId = sheets.size + 1
            val stylesId = sheets.size + 2
            append("<Relationship Id=\"rId$sharedStringsId\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>")
            append("<Relationship Id=\"rId$stylesId\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
            append("</Relationships>")
        })

        writeZipEntry(zipOut, "xl/styles.xml", buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            append("<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
            append("<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>")
            append("<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>")
            append("<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>")
            append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>")
            append("<cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs>")
            append("</styleSheet>")
        })

        writeZipEntry(zipOut, "xl/sharedStrings.xml", buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            append("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"${sharedStrings.size}\" uniqueCount=\"${sharedStrings.size}\">")
            sharedStrings.forEach { s ->
                append("<si><t xml:space=\"preserve\">")
                append(escapeXml(s))
                append("</t></si>")
            }
            append("</sst>")
        })

        sheets.forEachIndexed { index, sheet ->
            writeZipEntry(zipOut, "xl/worksheets/sheet${index + 1}.xml", buildSheetXml(sheet, stringIndex))
        }
    }
    return out.toByteArray()
}

private fun buildSheetXml(
    sheet: XlsxSheet,
    stringIndex: Map<String, Int>,
): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
    append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
    val colWidths = calculateColumnWidths(sheet)
    if (colWidths.isNotEmpty()) {
        append("<cols>")
        colWidths.forEachIndexed { colIdx, width ->
            append("<col min=\"${colIdx + 1}\" max=\"${colIdx + 1}\" width=\"$width\" customWidth=\"1\"/>")
        }
        append("</cols>")
    }
    append("<sheetData>")
    sheet.rows.forEachIndexed { rowIdx, row ->
        append("<row r=\"${rowIdx + 1}\">")
        row.forEachIndexed { colIdx, cell ->
            if (cell is XlsxCell.Blank) return@forEachIndexed
            val ref = "${xlsxColumnName(colIdx)}${rowIdx + 1}"
            when (cell) {
                is XlsxCell.Num -> append("<c r=\"$ref\"><v>${formatXlsxNumber(cell.value)}</v></c>")
                is XlsxCell.Bool -> append("<c r=\"$ref\" t=\"b\"><v>${if (cell.value) 1 else 0}</v></c>")
                is XlsxCell.Text -> {
                    val si = stringIndex[cell.value] ?: 0
                    append("<c r=\"$ref\" t=\"s\"><v>$si</v></c>")
                }
                XlsxCell.Blank -> Unit
            }
        }
        append("</row>")
    }
    append("</sheetData></worksheet>")
}

private fun calculateColumnWidths(sheet: XlsxSheet): List<Double> {
    if (sheet.rows.isEmpty()) return emptyList()
    val maxCols = sheet.rows.maxOf { it.size }
    if (maxCols == 0) return emptyList()
    val widths = MutableList(maxCols) { 8.0 }
    for (row in sheet.rows) {
        for ((colIdx, cell) in row.withIndex()) {
            if (colIdx >= maxCols) break
            val len = when (cell) {
                is XlsxCell.Text -> cell.value.length
                is XlsxCell.Num -> formatXlsxNumber(cell.value).length
                is XlsxCell.Bool -> 5
                XlsxCell.Blank -> 0
            }
            val w = (len * 1.2).coerceAtLeast(8.0).coerceAtMost(50.0)
            if (w > widths[colIdx]) widths[colIdx] = w
        }
    }
    return widths
}

private fun writeZipEntry(zipOut: ZipOutputStream, name: String, xml: String) {
    zipOut.putNextEntry(ZipEntry(name))
    zipOut.write(xml.toByteArray(Charsets.UTF_8))
    zipOut.closeEntry()
}

// ---------- tool: excel_write ----------

fun excelWriteTool(context: Context): Tool = Tool(
    name = "excel_write",
    description = """
        Write table data to an Excel .xlsx file. path accepts ~/ workspace paths, absolute
        paths and content:// URIs (SAF grant). sheets is an array of {name?, rows} where rows
        is an array of rows and each cell is a string, number, boolean or null (blank).
        Numbers and booleans are written as real Excel cells. overwrite defaults false.
        Returns {success, path, bytes_written, sheet_count, row_count}.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject { put("type", "string") })
                put("sheets", buildJsonObject {
                    put("type", "array")
                    put("description", "Array of sheets: {\"name\": \"optional\", \"rows\": [[cell, ...], ...]}")
                    put("items", buildJsonObject { put("type", "object") })
                })
                put("overwrite", buildJsonObject { put("type", "boolean") })
            },
            required = listOf("path", "sheets"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val rawPath = obj["path"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool spreadErr("missing_path", "detail" to "path is required")
        val path = AgentWorkspace.expand(rawPath)
        val overwrite = obj["overwrite"]?.jsonPrimitive?.booleanOrNull == true

        val sheetsJson = obj["sheets"]?.jsonArray
            ?: return@Tool spreadErr("missing_sheets", "detail" to "sheets is required")
        if (sheetsJson.isEmpty()) {
            return@Tool spreadErr("empty_sheets", "detail" to "sheets must contain at least one sheet")
        }

        guardSpreadsheetPath(rawPath, mutation = true)?.let { return@Tool it }

        val sheets = mutableListOf<XlsxSheet>()
        var totalCells = 0L
        var totalRows = 0
        for ((index, sheetElement) in sheetsJson.withIndex()) {
            val sheetObj = runCatching { sheetElement.jsonObject }.getOrElse {
                return@Tool spreadErr("invalid_sheets", "detail" to "sheets[$index] is not an object")
            }
            val name = sheetObj["name"]?.jsonPrimitive?.contentOrNull ?: "Sheet${index + 1}"
            val rowsJson = sheetObj["rows"]?.jsonArray
                ?: return@Tool spreadErr(
                    "invalid_sheets",
                    "detail" to "sheets[$index].rows is required",
                )
            val rows = rowsJson.mapIndexed { rowIdx, rowElement ->
                val cellsJson = runCatching { rowElement.jsonArray }.getOrElse {
                    return@Tool spreadErr(
                        "invalid_sheets",
                        "detail" to "sheets[$index].rows[$rowIdx] is not an array",
                    )
                }
                totalCells += cellsJson.size
                cellsJson.map { jsonToXlsxCell(it) }
            }
            totalRows += rows.size
            sheets.add(XlsxSheet(name = name, rows = rows))
        }
        if (totalCells > SPREADSHEET_MAX_CELLS) {
            return@Tool spreadErr(
                "too_many_cells",
                "detail" to "total cell count $totalCells exceeds $SPREADSHEET_MAX_CELLS",
            )
        }

        val fileTarget = !isContent(path)
        if (fileTarget && File(path).exists() && !overwrite) {
            return@Tool spreadErr(
                "file_exists",
                "detail" to "file '$path' already exists. Pass overwrite=true to replace it.",
            )
        }

        val sheetNames = sanitizeSheetNames(sheets.map { it.name })
        val normalizedSheets = sheets.mapIndexed { i, s -> s.copy(name = sheetNames[i]) }

        return@Tool try {
            val bytes = buildXlsx(normalizedSheets)
            val out = openSpreadsheetOutput(context, path)
                ?: return@Tool spreadErr("destination_unwritable", "detail" to "path '$path' cannot be opened for writing")
            out.use { it.write(bytes) }
            buildJsonObject {
                put("success", true)
                put("path", path)
                put("bytes_written", bytes.size)
                put("sheet_count", normalizedSheets.size)
                put("row_count", totalRows)
            }.let { listOf(UIMessagePart.Text(it.toString())) }
        } catch (e: Throwable) {
            spreadErr("excel_write_failed", "detail" to (e.message ?: e::class.simpleName ?: "unknown"))
        }
    },
)

// ---------- tool: csv_parse ----------

fun csvParseTool(context: Context): Tool = Tool(
    name = "csv_parse",
    description = """
        Parse a CSV file into structured data. Provide either path (~/ workspace, absolute
        or content:// URI) or csv (inline CSV text). delimiter defaults to a single comma;
        header defaults true (first row becomes columns). max_rows caps the returned rows
        (1-5000, default 500). UTF-8 is auto-detected with GBK fallback and the BOM is
        stripped. Returns {success, row_count, columns, rows, truncated, markdown}.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject { put("type", "string") })
                put("csv", buildJsonObject { put("type", "string") })
                put("delimiter", buildJsonObject { put("type", "string") })
                put("header", buildJsonObject { put("type", "boolean") })
                put("max_rows", buildJsonObject {
                    put("type", "integer"); put("minimum", 1); put("maximum", 5000)
                })
            },
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val rawPath = obj["path"]?.jsonPrimitive?.contentOrNull
        val inlineCsv = obj["csv"]?.jsonPrimitive?.contentOrNull
        if (rawPath.isNullOrBlank() && inlineCsv == null) {
            return@Tool spreadErr("missing_source", "detail" to "provide path or csv")
        }
        val delimiterChar = obj["delimiter"]?.jsonPrimitive?.contentOrNull?.let { d ->
            if (d.length == 1) d[0] else return@Tool spreadErr(
                "invalid_delimiter",
                "detail" to "delimiter must be exactly one character",
            )
        } ?: ','
        val header = obj["header"]?.jsonPrimitive?.booleanOrNull ?: true
        val maxRows = (obj["max_rows"]?.jsonPrimitive?.intOrNull ?: 500).coerceIn(1, 5000)

        val text = if (inlineCsv != null) {
            inlineCsv.removePrefix("\uFEFF")
        } else {
            val expanded = AgentWorkspace.expand(rawPath!!)
            guardSpreadsheetPath(rawPath)?.let { return@Tool it }
            if (!isContent(expanded)) {
                allFilesAccessGuard(expanded)?.let { envelope ->
                    return@Tool listOf(UIMessagePart.Text(envelope))
                }
            }
            val bytes = try {
                val ins = openSpreadsheetInput(context, expanded)
                    ?: return@Tool spreadErr("source_unreadable", "detail" to "path '$expanded' is not a readable file")
                ins.use { stream ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    var read: Int
                    var total = 0L
                    while (stream.read(chunk).also { read = it } >= 0) {
                        total += read
                        if (total > CSV_MAX_SOURCE_BYTES) {
                            return@Tool spreadErr(
                                "source_too_large",
                                "detail" to "source exceeds $CSV_MAX_SOURCE_BYTES bytes",
                            )
                        }
                        buffer.write(chunk, 0, read)
                    }
                    buffer.toByteArray()
                }
            } catch (e: Throwable) {
                return@Tool spreadErr(
                    "source_unreadable",
                    "detail" to (e.message ?: e::class.simpleName ?: "unknown"),
                )
            }
            decodeCsvBytes(bytes)
        }

        val parsed = parseCsv(text, delimiterChar)
        if (parsed.isEmpty()) {
            return@Tool spreadErr("empty_csv", "detail" to "no rows parsed")
        }

        val columns = if (header) parsed.first() else null
        val dataRows = if (header) parsed.drop(1) else parsed
        val returnedRows = dataRows.take(maxRows)
        val truncated = dataRows.size > returnedRows.size

        return@Tool listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true)
            put("row_count", dataRows.size)
            put("column_count", columns?.size ?: (parsed.maxOfOrNull { it.size } ?: 0))
            if (columns != null) {
                put("columns", buildJsonArray { columns.forEach { add(it) } })
            }
            put("rows", buildJsonArray {
                returnedRows.forEach { row ->
                    add(buildJsonArray { row.forEach { add(it) } })
                }
            })
            put("truncated", truncated)
            put("markdown", rowsToMarkdown(columns, returnedRows))
        }.toString()))
    },
)

// ---------- tool: csv_write ----------

fun csvWriteTool(context: Context): Tool = Tool(
    name = "csv_write",
    description = """
        Write rows to an Excel-compatible CSV file (UTF-8 with BOM, CRLF line endings,
        RFC4180 quoting) so double-clicking it in Excel keeps non-ASCII text intact. path
        accepts ~/ workspace paths, absolute paths and content:// URIs (SAF grant). rows is
        an array of arrays of strings/numbers/booleans/null. delimiter defaults to a single
        comma. overwrite defaults false.
        Returns {success, path, bytes_written, row_count}.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject { put("type", "string") })
                put("rows", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
                })
                put("delimiter", buildJsonObject { put("type", "string") })
                put("overwrite", buildJsonObject { put("type", "boolean") })
            },
            required = listOf("path", "rows"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val rawPath = obj["path"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool spreadErr("missing_path", "detail" to "path is required")
        val path = AgentWorkspace.expand(rawPath)
        val overwrite = obj["overwrite"]?.jsonPrimitive?.booleanOrNull == true
        val delimiterChar = obj["delimiter"]?.jsonPrimitive?.contentOrNull?.let { d ->
            if (d.length == 1) d[0] else return@Tool spreadErr(
                "invalid_delimiter",
                "detail" to "delimiter must be exactly one character",
            )
        } ?: ','
        val rowsJson = obj["rows"]?.jsonArray
            ?: return@Tool spreadErr("missing_rows", "detail" to "rows is required")

        guardSpreadsheetPath(rawPath, mutation = true)?.let { return@Tool it }

        var totalCells = 0L
        val rows = rowsJson.mapIndexed { rowIdx, rowElement ->
            val cellsJson = runCatching { rowElement.jsonArray }.getOrElse {
                return@Tool spreadErr("invalid_rows", "detail" to "rows[$rowIdx] is not an array")
            }
            totalCells += cellsJson.size
            cellsJson.map { cell ->
                when (cell) {
                    is JsonNull -> ""
                    is JsonPrimitive -> cell.content
                    else -> cell.toString()
                }
            }
        }
        if (totalCells > SPREADSHEET_MAX_CELLS) {
            return@Tool spreadErr(
                "too_many_cells",
                "detail" to "total cell count $totalCells exceeds $SPREADSHEET_MAX_CELLS",
            )
        }

        val fileTarget = !isContent(path)
        if (fileTarget && File(path).exists() && !overwrite) {
            return@Tool spreadErr(
                "file_exists",
                "detail" to "file '$path' already exists. Pass overwrite=true to replace it.",
            )
        }

        return@Tool try {
            val bytes = ("\uFEFF" + buildCsv(rows, delimiterChar)).toByteArray(Charsets.UTF_8)
            val out = openSpreadsheetOutput(context, path)
                ?: return@Tool spreadErr("destination_unwritable", "detail" to "path '$path' cannot be opened for writing")
            out.use { it.write(bytes) }
            buildJsonObject {
                put("success", true)
                put("path", path)
                put("bytes_written", bytes.size)
                put("row_count", rows.size)
            }.let { listOf(UIMessagePart.Text(it.toString())) }
        } catch (e: Throwable) {
            spreadErr("csv_write_failed", "detail" to (e.message ?: e::class.simpleName ?: "unknown"))
        }
    },
)
