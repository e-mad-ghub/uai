package com.example.uai.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Bullet(val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
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
            }
        }
    }
}

@Composable
private fun MarkdownInlineText(
    text: String,
    color: Color,
    style: TextStyle
) {
    Text(
        text = markdownInlineAnnotatedString(text = text, color = color),
        style = style,
        color = color
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

    normalized.lines().forEach { line ->
        val trimmed = line.trim()
        when {
            trimmed.isBlank() -> flushParagraph()

            trimmed.matches(Regex("""^#{1,6}\s+.*$""")) -> {
                flushParagraph()
                val level = trimmed.takeWhile { it == '#' }.length
                blocks += MarkdownBlock.Heading(
                    level = level,
                    text = trimmed.drop(level).trim()
                )
            }

            trimmed.matches(Regex("""^([-*]|\d+\.)\s+.*$""")) -> {
                flushParagraph()
                val bulletText = trimmed.replaceFirst(Regex("""^([-*]|\d+\.)\s+"""), "").trim()
                blocks += MarkdownBlock.Bullet(bulletText)
            }

            else -> paragraphLines += trimmed
        }
    }

    flushParagraph()
    return blocks
}

private fun markdownInlineAnnotatedString(
    text: String,
    color: Color
): AnnotatedString {
    return buildAnnotatedString {
        appendMarkdownInline(text = text, color = color)
    }
}

private fun AnnotatedString.Builder.appendMarkdownInline(
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
                    append(text.substring(index + 2, end))
                    pop()
                    index = end + 2
                } else {
                    append(text[index])
                    index += 1
                }
            }

            text[index] == '*' -> {
                val end = text.indexOf('*', startIndex = index + 1)
                if (end > index + 1) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(text.substring(index + 1, end))
                    pop()
                    index = end + 1
                } else {
                    append(text[index])
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
                    append(text.substring(index + 1, end))
                    pop()
                    index = end + 1
                } else {
                    append(text[index])
                    index += 1
                }
            }

            else -> {
                append(text[index])
                index += 1
            }
        }
    }
}
