package com.example.mikeyllmapplication.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Block-level structure of the small Markdown subset LLMs typically emit. */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val indent: Int, val marker: String, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
}

object MarkdownParser {
    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val bullet = Regex("^(\\s*)([-*+•]|\\d+[.)])\\s+(.*)$")

    fun parse(src: String): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val para = StringBuilder()
        var code: StringBuilder? = null
        fun flush() {
            if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim())
            para.clear()
        }
        for (line in src.lines()) {
            if (line.trimStart().startsWith("```")) {
                if (code == null) {
                    flush(); code = StringBuilder()
                } else {
                    out += MdBlock.Code(code.toString().trimEnd()); code = null
                }
                continue
            }
            if (code != null) {
                code.append(line).append('\n'); continue
            }
            val h = heading.find(line)
            val b = bullet.find(line)
            when {
                line.isBlank() -> flush()
                h != null -> {
                    flush(); out += MdBlock.Heading(h.groupValues[1].length, h.groupValues[2].trim())
                }
                b != null -> {
                    flush()
                    val marker = b.groupValues[2].let { if (it[0].isDigit()) it else "•" }
                    out += MdBlock.Bullet(b.groupValues[1].length / 2, marker, b.groupValues[3])
                }
                else -> {
                    if (para.isNotEmpty()) para.append('\n')
                    para.append(line)
                }
            }
        }
        code?.let { out += MdBlock.Code(it.toString().trimEnd()) }
        flush()
        return out
    }

    /** Inline **bold**, *italic*, `code`. Unclosed markers are rendered literally. */
    fun inline(text: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) && text.indexOf("**", i + 2) > i + 2 -> {
                    val end = text.indexOf("**", i + 2)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                }
                text[i] == '`' && text.indexOf('`', i + 1) > i + 1 -> {
                    val end = text.indexOf('`', i + 1)
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                }
                text[i] == '*' && i + 1 < text.length && text[i + 1] != ' ' &&
                    text.indexOf('*', i + 1) > i + 1 -> {
                    val end = text.indexOf('*', i + 1)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                }
                else -> {
                    append(text[i]); i++
                }
            }
        }
    }
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val typo = MaterialTheme.typography
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Heading -> Text(
                    MarkdownParser.inline(b.text, codeBg),
                    style = if (b.level <= 2) typo.titleMedium else typo.titleSmall,
                    fontWeight = FontWeight.SemiBold, color = color,
                )
                is MdBlock.Bullet -> Row(Modifier.padding(start = (b.indent * 14).dp)) {
                    Text(b.marker, Modifier.width(22.dp), style = typo.bodyMedium, color = color)
                    Text(MarkdownParser.inline(b.text, codeBg), style = typo.bodyMedium, color = color)
                }
                is MdBlock.Paragraph -> Text(MarkdownParser.inline(b.text, codeBg), style = typo.bodyMedium, color = color)
                is MdBlock.Code -> Text(
                    b.text, style = typo.bodySmall.copy(fontFamily = FontFamily.Monospace), color = color,
                    modifier = Modifier.padding(4.dp),
                )
            }
        }
    }
}
