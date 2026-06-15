package com.legion.orion.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * Lightweight markdown renderer for chat output. Supports the constructs models
 * actually emit: fenced code blocks, headers, bullet/numbered lists, blockquotes,
 * horizontal rules, and inline bold/italic/code/links/strikethrough.
 * Dependency-free by design (no WebView, no Markwon).
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = Cosmos.TextDim,
) {
    val blocks = remember(markdown) { parseBlocks(markdown) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Code -> CodeBlock(block.language, block.code)
                is MdBlock.Heading -> Text(
                    inline(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    color = Cosmos.Text,
                    fontWeight = FontWeight.SemiBold,
                )
                is MdBlock.Bullet -> Row {
                    Text("•  ", color = Cosmos.Violet3, style = MaterialTheme.typography.bodyMedium)
                    Text(inline(block.text), style = MaterialTheme.typography.bodyMedium, color = color)
                }
                is MdBlock.Numbered -> Row {
                    Text(
                        "${block.index}.  ",
                        color = Cosmos.Violet3,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(inline(block.text), style = MaterialTheme.typography.bodyMedium, color = color)
                }
                is MdBlock.Quote -> Row {
                    Box(
                        Modifier
                            .padding(end = 10.dp)
                            .size(width = 3.dp, height = 18.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Cosmos.Violet.copy(alpha = 0.5f)),
                    )
                    Text(
                        inline(block.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Cosmos.TextMuted,
                        fontStyle = FontStyle.Italic,
                    )
                }
                MdBlock.Rule -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(Cosmos.Hairline),
                )
                is MdBlock.Paragraph -> Text(
                    inline(block.text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(language: String, code: String) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xCC0A0714)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0x14FFFFFF))
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                language.ifBlank { "code" },
                style = MaterialTheme.typography.labelSmall,
                color = Cosmos.TextMuted,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = "Copy code",
                    tint = Cosmos.TextMuted,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Text(
            code,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily),
            color = Cosmos.TextDim,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        )
    }
}

private sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Numbered(val index: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data object Rule : MdBlock
}

private fun parseBlocks(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = src.lines()
    var i = 0
    val para = StringBuilder()
    fun flushPara() {
        if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim())
        para.setLength(0)
    }
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                flushPara()
                val lang = trimmed.removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.appendLine(lines[i])
                    i++
                }
                out += MdBlock.Code(lang, code.toString().trimEnd())
            }
            trimmed.startsWith("#") -> {
                flushPara()
                val level = trimmed.takeWhile { it == '#' }.length.coerceAtMost(3)
                out += MdBlock.Heading(level, trimmed.dropWhile { it == '#' }.trim())
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flushPara()
                out += MdBlock.Bullet(trimmed.drop(2).trim())
            }
            Regex("^\\d+\\.\\s").containsMatchIn(trimmed) -> {
                flushPara()
                val idx = trimmed.takeWhile { it.isDigit() }.toIntOrNull() ?: 1
                out += MdBlock.Numbered(idx, trimmed.dropWhile { it.isDigit() }.removePrefix(".").trim())
            }
            trimmed.startsWith("> ") -> {
                flushPara()
                out += MdBlock.Quote(trimmed.drop(2).trim())
            }
            trimmed == "---" || trimmed == "***" -> {
                flushPara()
                out += MdBlock.Rule
            }
            trimmed.isBlank() -> flushPara()
            else -> {
                if (para.isNotEmpty()) para.append(' ')
                para.append(line.trim())
            }
        }
        i++
    }
    flushPara()
    return out
}

/** Inline markdown → AnnotatedString: **bold**, *italic*, `code`, ~~strike~~, [text](url). */
private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Cosmos.Text)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end > 0) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > 0) {
                    withStyle(
                        SpanStyle(
                            fontFamily = MonoFamily,
                            color = Cosmos.Violet3,
                            background = Color(0x1AA88CFF),
                        ),
                    ) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            text[i] == '*' && i + 1 < text.length && text[i + 1] != '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end > 0) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            text[i] == '[' -> {
                val mid = text.indexOf("](", i)
                val end = if (mid > 0) text.indexOf(')', mid) else -1
                if (mid > 0 && end > 0) {
                    withStyle(SpanStyle(color = Cosmos.Blue, textDecoration = TextDecoration.Underline)) {
                        append(text.substring(i + 1, mid))
                    }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}
