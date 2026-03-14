package com.example.uai.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

data class ImportedFileAttachment(
    val displayName: String,
    val extractedText: String
)

sealed interface FileAttachmentImportResult {
    data class Success(val attachment: ImportedFileAttachment) : FileAttachmentImportResult
    data class Unsupported(val message: String) : FileAttachmentImportResult
    data class Failure(val message: String) : FileAttachmentImportResult
}

private class FileTooLargeException : IllegalStateException()

private const val MAX_FILE_BYTES = 12 * 1024 * 1024
private const val MAX_EXTRACTED_CHARACTERS = 40_000
private const val MAX_PDF_PAGES = 25
private const val MAX_SPREADSHEET_SHEETS = 5
private const val MAX_SPREADSHEET_ROWS_PER_SHEET = 200
private const val MAX_PRESENTATION_SLIDES = 25

private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "csv", "tsv", "json", "xml", "html", "htm", "yaml", "yml",
    "ini", "cfg", "conf", "log", "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx",
    "css", "scss", "sql", "sh", "bash", "zsh", "properties", "gradle", "toml", "rtf"
)

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

suspend fun importFileAttachment(
    context: Context,
    uri: Uri
): FileAttachmentImportResult = withContext(Dispatchers.IO) {
    val displayName = resolveDisplayName(context, uri)
    val mimeType = context.contentResolver.getType(uri).orEmpty()
    val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)

    if (extension in IMAGE_EXTENSIONS || mimeType.startsWith("image/")) {
        return@withContext FileAttachmentImportResult.Unsupported(
            "Use Photo or Camera for image attachments."
        )
    }

    try {
        val extracted = when {
            mimeType == "application/pdf" || extension == "pdf" -> {
                extractPdfText(readUriBytes(context, uri))
            }
            extension == "docx" -> extractDocxText(readUriBytes(context, uri))
            extension == "xlsx" -> extractXlsxText(readUriBytes(context, uri))
            extension == "pptx" -> extractPptxText(readUriBytes(context, uri))
            isTextLike(mimeType, extension) -> decodeTextContent(readUriBytes(context, uri))
            else -> {
                return@withContext FileAttachmentImportResult.Unsupported(
                    "Unsupported file type. Supported: PDF, DOCX, XLSX, PPTX, and text-like files."
                )
            }
        }

        val normalized = normalizeExtractedText(extracted)
        if (normalized.isBlank()) {
            FileAttachmentImportResult.Failure(
                "No readable text was found in \"$displayName\"."
            )
        } else {
            FileAttachmentImportResult.Success(
                ImportedFileAttachment(
                    displayName = displayName,
                    extractedText = normalized
                )
            )
        }
    } catch (_: FileTooLargeException) {
        FileAttachmentImportResult.Failure(
            "This file is too large to import. Try a file up to 12 MB."
        )
    } catch (e: Exception) {
        FileAttachmentImportResult.Failure(
            e.message ?: "Could not read \"$displayName\"."
        )
    }
}

private fun resolveDisplayName(context: Context, uri: Uri): String {
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
    runCatching {
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
}

private fun isTextLike(mimeType: String, extension: String): Boolean {
    if (mimeType.startsWith("text/")) return true
    if (extension in TEXT_EXTENSIONS) return true
    return mimeType in setOf(
        "application/json",
        "application/xml",
        "application/x-yaml",
        "application/javascript",
        "application/sql",
        "application/rtf"
    )
}

private fun readUriBytes(context: Context, uri: Uri): ByteArray {
    context.contentResolver.openInputStream(uri)?.use { input ->
        return readLimitedBytes(input)
    }
    throw IllegalStateException("Could not open the selected file.")
}

private fun readLimitedBytes(input: java.io.InputStream): ByteArray {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    val output = ByteArrayOutputStream()
    var totalRead = 0
    while (true) {
        val read = input.read(buffer)
        if (read <= 0) break
        totalRead += read
        if (totalRead > MAX_FILE_BYTES) throw FileTooLargeException()
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun decodeTextContent(bytes: ByteArray): String {
    val charset = detectCharset(bytes)
    return bytes.toString(charset)
}

private fun detectCharset(bytes: ByteArray): Charset {
    if (bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() &&
        bytes[1] == 0xBB.toByte() &&
        bytes[2] == 0xBF.toByte()
    ) {
        return StandardCharsets.UTF_8
    }
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
        return StandardCharsets.UTF_16LE
    }
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
        return StandardCharsets.UTF_16BE
    }
    val zeroCount = bytes.take(128).count { it == 0.toByte() }
    return if (zeroCount > 8) StandardCharsets.UTF_16LE else StandardCharsets.UTF_8
}

private fun extractPdfText(bytes: ByteArray): String {
    PDDocument.load(ByteArrayInputStream(bytes)).use { document ->
        if (document.numberOfPages <= 0) return ""
        val stripper = PDFTextStripper().apply {
            startPage = 1
            endPage = minOf(document.numberOfPages, MAX_PDF_PAGES)
        }
        val text = stripper.getText(document)
        val truncatedNote = if (document.numberOfPages > MAX_PDF_PAGES) {
            "\n\n[Truncated to the first $MAX_PDF_PAGES pages.]"
        } else {
            ""
        }
        return text + truncatedNote
    }
}

private fun extractDocxText(bytes: ByteArray): String {
    val documentXml = extractZipEntry(bytes, "word/document.xml")
        ?: throw IllegalStateException("Could not read the DOCX contents.")
    val document = parseXml(documentXml)
    val output = StringBuilder()
    appendWordProcessingNode(document.documentElement, output)
    return output.toString()
}

private fun appendWordProcessingNode(node: Node, output: StringBuilder) {
    when (node.nodeType) {
        Node.TEXT_NODE -> {
            val parent = node.parentNode?.normalizedName()
            if (parent == "t") {
                output.append(node.nodeValue.orEmpty())
            }
        }
        Node.ELEMENT_NODE -> {
            when (node.normalizedName()) {
                "tab" -> output.append('\t')
                "br", "cr" -> output.append('\n')
            }
            var child = node.firstChild
            while (child != null) {
                appendWordProcessingNode(child, output)
                child = child.nextSibling
            }
            if (node.normalizedName() == "p") {
                output.append('\n')
            }
        }
    }
}

private fun extractPptxText(bytes: ByteArray): String {
    val slideEntries = listZipEntries(bytes, prefix = "ppt/slides/", suffix = ".xml")
        .sortedWith(compareBy<Pair<String, ByteArray>> { numericPathOrder(it.first) }.thenBy { it.first })
        .take(MAX_PRESENTATION_SLIDES)
    if (slideEntries.isEmpty()) {
        throw IllegalStateException("Could not read the PPTX slides.")
    }

    return buildString {
        slideEntries.forEachIndexed { index, (_, slideXml) ->
            if (index > 0) append("\n\n")
            append("Slide ${index + 1}\n")
            val document = parseXml(slideXml)
            appendPresentationNode(document.documentElement, this)
        }
    }
}

private fun appendPresentationNode(node: Node, output: StringBuilder) {
    when (node.nodeType) {
        Node.TEXT_NODE -> {
            val parent = node.parentNode?.normalizedName()
            if (parent == "t") {
                output.append(node.nodeValue.orEmpty())
            }
        }
        Node.ELEMENT_NODE -> {
            if (node.normalizedName() == "br") {
                output.append('\n')
            }
            var child = node.firstChild
            while (child != null) {
                appendPresentationNode(child, output)
                child = child.nextSibling
            }
            if (node.normalizedName() == "p") {
                output.append('\n')
            }
        }
    }
}

private fun extractXlsxText(bytes: ByteArray): String {
    val sharedStrings = extractZipEntry(bytes, "xl/sharedStrings.xml")
        ?.let(::parseSharedStrings)
        .orEmpty()
    val sheetEntries = listZipEntries(bytes, prefix = "xl/worksheets/", suffix = ".xml")
        .sortedWith(compareBy<Pair<String, ByteArray>> { numericPathOrder(it.first) }.thenBy { it.first })
        .take(MAX_SPREADSHEET_SHEETS)
    if (sheetEntries.isEmpty()) {
        throw IllegalStateException("Could not read the XLSX sheets.")
    }

    return buildString {
        sheetEntries.forEachIndexed { sheetIndex, (_, sheetXml) ->
            if (sheetIndex > 0) append("\n\n")
            append("Sheet ${sheetIndex + 1}\n")
            val document = parseXml(sheetXml)
            val rows = document.getElementsByTagNameNS("*", "row")
            val rowCount = minOf(rows.length, MAX_SPREADSHEET_ROWS_PER_SHEET)
            for (rowIndex in 0 until rowCount) {
                val rowElement = rows.item(rowIndex) as? Element ?: continue
                append(parseSpreadsheetRow(rowElement, sharedStrings))
                append('\n')
            }
            if (rows.length > MAX_SPREADSHEET_ROWS_PER_SHEET) {
                append("[Truncated to the first $MAX_SPREADSHEET_ROWS_PER_SHEET rows.]\n")
            }
        }
    }
}

private fun parseSpreadsheetRow(row: Element, sharedStrings: List<String>): String {
    val valuesByColumn = linkedMapOf<Int, String>()
    val children = row.childNodes
    for (index in 0 until children.length) {
        val child = children.item(index)
        if (child !is Element || child.normalizedName() != "c") continue
        val reference = child.getAttribute("r")
        val columnIndex = parseColumnIndex(reference)
        val value = parseSpreadsheetCellValue(child, sharedStrings)
        if (value.isNotBlank()) {
            valuesByColumn[columnIndex] = value
        }
    }

    if (valuesByColumn.isEmpty()) return ""
    val maxColumn = valuesByColumn.keys.maxOrNull() ?: 0
    return buildList {
        for (columnIndex in 0..maxColumn) {
            add(valuesByColumn[columnIndex].orEmpty())
        }
    }.joinToString("\t").trimEnd()
}

private fun parseSpreadsheetCellValue(cell: Element, sharedStrings: List<String>): String {
    val type = cell.getAttribute("t")
    return when (type) {
        "s" -> cell.firstTextFromTag("v")
            ?.toIntOrNull()
            ?.let(sharedStrings::getOrNull)
            .orEmpty()
        "inlineStr" -> cell.firstTextFromTag("t").orEmpty()
        "b" -> when (cell.firstTextFromTag("v")) {
            "1" -> "TRUE"
            "0" -> "FALSE"
            else -> cell.firstTextFromTag("v").orEmpty()
        }
        else -> cell.firstTextFromTag("v").orEmpty()
    }
}

private fun parseSharedStrings(xmlBytes: ByteArray): List<String> {
    val document = parseXml(xmlBytes)
    val entries = document.getElementsByTagNameNS("*", "si")
    return buildList {
        for (index in 0 until entries.length) {
            val node = entries.item(index)
            val builder = StringBuilder()
            appendSharedStringNode(node, builder)
            add(builder.toString())
        }
    }
}

private fun appendSharedStringNode(node: Node, output: StringBuilder) {
    when (node.nodeType) {
        Node.TEXT_NODE -> {
            val parent = node.parentNode?.normalizedName()
            if (parent == "t") {
                output.append(node.nodeValue.orEmpty())
            }
        }
        Node.ELEMENT_NODE -> {
            var child = node.firstChild
            while (child != null) {
                appendSharedStringNode(child, output)
                child = child.nextSibling
            }
        }
    }
}

private fun parseColumnIndex(reference: String): Int {
    val letters = reference.takeWhile { it.isLetter() }.uppercase(Locale.ROOT)
    if (letters.isBlank()) return 0
    var index = 0
    letters.forEach { ch ->
        index = index * 26 + (ch - 'A' + 1)
    }
    return (index - 1).coerceAtLeast(0)
}

private fun parseXml(bytes: ByteArray) =
    DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

private fun extractZipEntry(zipBytes: ByteArray, entryName: String): ByteArray? {
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory && entry.name == entryName) {
                return readLimitedBytes(zip)
            }
        }
    }
    return null
}

private fun listZipEntries(
    zipBytes: ByteArray,
    prefix: String,
    suffix: String
): List<Pair<String, ByteArray>> {
    val entries = mutableListOf<Pair<String, ByteArray>>()
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory && entry.name.startsWith(prefix) && entry.name.endsWith(suffix)) {
                entries += entry.name to readLimitedBytes(zip)
            }
        }
    }
    return entries
}

private fun normalizeExtractedText(raw: String): String {
    val normalized = raw
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lines()
        .joinToString("\n") { it.trimEnd() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    if (normalized.length <= MAX_EXTRACTED_CHARACTERS) {
        return normalized
    }
    return normalized.take(MAX_EXTRACTED_CHARACTERS).trimEnd() +
        "\n\n[Truncated to $MAX_EXTRACTED_CHARACTERS characters.]"
}

private fun numericPathOrder(path: String): Int {
    return Regex("(\\d+)").findAll(path)
        .mapNotNull { it.value.toIntOrNull() }
        .lastOrNull()
        ?: Int.MAX_VALUE
}

private fun Node.normalizedName(): String {
    return localName ?: nodeName.substringAfter(':', nodeName)
}

private fun Element.firstTextFromTag(tagName: String): String? {
    val nodes: NodeList = getElementsByTagNameNS("*", tagName)
    if (nodes.length == 0) return null
    return nodes.item(0)?.textContent
}
