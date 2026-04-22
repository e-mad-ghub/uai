package com.mad.screenagent.shared.chatui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.ClickableText

private sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Bullet(val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock
}

@Composable
fun MarkdownMessageText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    baseStyle: TextStyle? = null
) {
    val resolvedBaseStyle = baseStyle ?: MaterialTheme.typography.bodyMedium
    val blocks = remember(text) { parseMarkdownBlocks(text) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> {
                    Text(
                        text = markdownInlineAnnotatedString(
                            text = block.text,
                            color = color
                        ),
                        style = headingStyleFor(block.level),
                        color = color
                    )
                }

                is MarkdownBlock.Bullet -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "\u2022",
                            style = resolvedBaseStyle.copy(fontWeight = FontWeight.SemiBold),
                            color = color
                        )
                        MarkdownInlineText(
                            text = block.text,
                            color = color,
                            style = resolvedBaseStyle
                        )
                    }
                }

                is MarkdownBlock.Paragraph -> {
                    MarkdownInlineText(
                        text = block.text,
                        color = color,
                        style = resolvedBaseStyle
                    )
                }

                is MarkdownBlock.Table -> {
                    MarkdownTable(
                        table = block,
                        color = color,
                        baseStyle = resolvedBaseStyle
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownTable(
    table: MarkdownBlock.Table,
    color: Color,
    baseStyle: TextStyle
) {
    val borderColor = color.copy(alpha = 0.25f)
    val headerBg = color.copy(alpha = 0.10f)
    val cellStyle = baseStyle.copy(fontSize = baseStyle.fontSize)
    val colCount = table.headers.size

    Surface(
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, borderColor)
    ) {
        // Column-major layout: each Column owns one table column so cells in the
        // same column naturally share the same width (the widest cell determines it).
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            for (colIdx in 0 until colCount) {
                if (colIdx > 0) ColumnDivider(borderColor)
                Column {
                    // Header cell
                    Box(modifier = Modifier.background(headerBg)) {
                        Text(
                            text = markdownInlineAnnotatedString(table.headers[colIdx], color),
                            style = cellStyle.copy(fontWeight = FontWeight.Bold),
                            color = color,
                            modifier = Modifier
                                .defaultMinSize(minWidth = 72.dp)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                    HorizontalDivider(thickness = 1.dp, color = borderColor)
                    // Data cells
                    table.rows.forEachIndexed { rowIdx, row ->
                        if (rowIdx > 0) HorizontalDivider(thickness = 1.dp, color = borderColor)
                        Text(
                            text = markdownInlineAnnotatedString(row.getOrElse(colIdx) { "" }, color),
                            style = cellStyle,
                            color = color,
                            modifier = Modifier
                                .defaultMinSize(minWidth = 72.dp)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnDivider(color: Color) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(color)
    )
}

@Composable
private fun MarkdownInlineText(
    text: String,
    color: Color,
    style: TextStyle
) {
    val uriHandler = LocalUriHandler.current
    val annotated = remember(text, color) {
        buildAnnotatedString {
            appendMarkdownInlineWithUrls(text = text, color = color)
        }
    }

    ClickableText(
        text = annotated,
        style = style.copy(color = color),
        onClick = { offset ->
            annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()?.let { annotation ->
                    try {
                        uriHandler.openUri(annotation.item)
                    } catch (e: Exception) {
                        // Silently fail if URI handler is unavailable
                    }
                }
        }
    )
}

@Composable
private fun headingStyleFor(level: Int): TextStyle {
    return when (level) {
        1 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
        2 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        else -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
    }
}

private fun parseMarkdownBlocks(raw: String): List<MarkdownBlock> {
    val normalized = raw.replace("\r\n", "\n").trim()
    if (normalized.isBlank()) return emptyList()

    val blocks = mutableListOf<MarkdownBlock>()
    val paragraphLines = mutableListOf<String>()
    val tableLines = mutableListOf<String>()

    fun flushParagraph() {
        val paragraph = paragraphLines
            .joinToString(" ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (paragraph.isNotBlank()) {
            blocks += MarkdownBlock.Paragraph(paragraph)
        }
        paragraphLines.clear()
    }

    fun flushTable() {
        if (tableLines.isEmpty()) return
        val nonSeparator = tableLines.filterNot { isSeparatorRow(it) }
        if (nonSeparator.size >= 1) {
            val headers = parseTableRow(nonSeparator[0])
            val rows = nonSeparator.drop(1).map { parseTableRow(it) }
            if (headers.isNotEmpty()) {
                blocks += MarkdownBlock.Table(headers, rows)
            }
        }
        tableLines.clear()
    }

    normalized.lines().forEach { line ->
        val trimmed = line.trim()
        when {
            trimmed.isBlank() -> {
                flushParagraph()
                flushTable()
            }

            isTableRow(trimmed) -> {
                flushParagraph()
                tableLines += trimmed
            }

            trimmed.matches(Regex("""^#{1,6}\s+.*$""")) -> {
                flushParagraph()
                flushTable()
                val level = trimmed.takeWhile { it == '#' }.length
                blocks += MarkdownBlock.Heading(
                    level = level,
                    text = trimmed.drop(level).trim()
                )
            }

            trimmed.matches(Regex("""^([-*]|\d+\.)\s+.*$""")) -> {
                flushParagraph()
                flushTable()
                val bulletText = trimmed.replaceFirst(Regex("""^([-*]|\d+\.)\s+"""), "").trim()
                blocks += MarkdownBlock.Bullet(bulletText)
            }

            else -> {
                flushTable()
                paragraphLines += trimmed
            }
        }
    }

    flushParagraph()
    flushTable()
    return blocks
}

/** A line is a table row if it contains `|` and is not a heading/bullet/separator-only line. */
private fun isTableRow(line: String): Boolean {
    if (!line.contains('|')) return false
    // headings and bullets are not table rows
    if (line.matches(Regex("""^#{1,6}\s+.*"""))) return false
    if (line.matches(Regex("""^([-*]|\d+\.)\s+.*"""))) return false
    return true
}

/** A separator row consists only of `|`, `-`, `:`, and spaces. */
private fun isSeparatorRow(line: String): Boolean {
    val stripped = line.replace("|", "").replace("-", "").replace(":", "").replace(" ", "")
    return stripped.isEmpty() && line.contains('-')
}

private fun parseTableRow(line: String): List<String> {
    return line.trim().trimStart('|').trimEnd('|')
        .split('|')
        .map { it.trim() }
        .filter { it.isNotEmpty() || true } // keep empty cells for alignment
}

private fun markdownInlineAnnotatedString(
    text: String,
    color: Color
): AnnotatedString {
    return buildAnnotatedString {
        appendMarkdownInlineWithUrls(text = text, color = color)
    }
}

private fun AnnotatedString.Builder.appendTextWithLinkAnnotations(
    text: String,
    linkColor: Color
) {
    // Match markdown links: [text](url)
    val markdownLinkRegex = Regex("""\[([^\]]+)\]\(([^)]+)\)""")
    // Match bare URLs: https://... or www....
    val urlRegex = Regex("""(https?://[^\s<>\]]+|www\.[^\s<>\]]+)""")
    
    var currentIndex = 0
    var lastProcessedIndex = 0
    
    // Collect all matches with their types
    data class LinkMatch(val startInOriginal: Int, val endInOriginal: Int, val displayText: String, val url: String, val isMarkdown: Boolean)
    val allLinks = mutableListOf<LinkMatch>()
    
    // Find markdown links
    markdownLinkRegex.findAll(text).forEach { match ->
        val displayText = match.groupValues[1]
        val url = match.groupValues[2]
        allLinks.add(LinkMatch(match.range.first, match.range.last + 1, displayText, url, true))
    }
    
    // Find bare URLs (but skip if inside markdown links)
    val markdownRanges = allLinks.map { IntRange(it.startInOriginal, it.endInOriginal - 1) }
    urlRegex.findAll(text).forEach { match ->
        // Skip if this URL is inside a markdown link
        val isInsideMarkdownLink = markdownRanges.any { 
            match.range.first >= it.first && match.range.last <= it.last 
        }
        if (!isInsideMarkdownLink) {
            allLinks.add(LinkMatch(match.range.first, match.range.last + 1, match.value, match.value, false))
        }
    }
    
    // Sort by position
    allLinks.sortBy { it.startInOriginal }
    
    // Build the annotated string by processing in order
    for (link in allLinks) {
        // Append text before this link
        if (link.startInOriginal > lastProcessedIndex) {
            append(text.substring(lastProcessedIndex, link.startInOriginal))
        }
        
        // Add the link display text with annotation
        val displayStart = length
        append(link.displayText)
        val displayEnd = length
        
        val finalUrl = if (link.url.startsWith("www.")) "https://" + link.url else link.url
        
        addStringAnnotation(
            tag = "URL",
            annotation = finalUrl,
            start = displayStart,
            end = displayEnd
        )
        addStyle(
            SpanStyle(
                color = linkColor,
                textDecoration = TextDecoration.Underline
            ),
            displayStart,
            displayEnd
        )
        
        lastProcessedIndex = link.endInOriginal
    }
    
    // Append any remaining text after last link
    if (lastProcessedIndex < text.length) {
        append(text.substring(lastProcessedIndex))
    }
}

private fun AnnotatedString.Builder.appendMarkdownInlineWithUrls(
    text: String,
    color: Color
) {
    var index = 0
    while (index < text.length) {
        when {
            text.startsWith("**", index) -> {
                val end = text.indexOf("**", startIndex = index + 2)
                if (end > index + 2) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    appendTextWithLinkAnnotations(text.substring(index + 2, end), color)
                    pop()
                    index = end + 2
                } else {
                    appendTextWithLinkAnnotations(text.substring(index, index + 1), color)
                    index += 1
                }
            }

            text[index] == '*' -> {
                val end = text.indexOf('*', startIndex = index + 1)
                if (end > index + 1) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    appendTextWithLinkAnnotations(text.substring(index + 1, end), color)
                    pop()
                    index = end + 1
                } else {
                    appendTextWithLinkAnnotations(text.substring(index, index + 1), color)
                    index += 1
                }
            }

            text[index] == '`' -> {
                val end = text.indexOf('`', startIndex = index + 1)
                if (end > index + 1) {
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = color.copy(alpha = 0.14f)
                        )
                    )
                    appendTextWithLinkAnnotations(text.substring(index + 1, end), color)
                    pop()
                    index = end + 1
                } else {
                    appendTextWithLinkAnnotations(text.substring(index, index + 1), color)
                    index += 1
                }
            }

            else -> {
                // Collect consecutive plain text characters
                val start = index
                while (index < text.length && 
                       !text.startsWith("**", index) && 
                       text[index] != '*' && 
                       text[index] != '`') {
                    index++
                }
                appendTextWithLinkAnnotations(text.substring(start, index), color)
            }
        }
    }
}
