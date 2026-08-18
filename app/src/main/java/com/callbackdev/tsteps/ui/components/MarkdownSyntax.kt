package com.callbackdev.tsteps.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import com.callbackdev.tsteps.ui.theme.SyntaxColors
import com.callbackdev.tsteps.ui.theme.TstepsTheme

/**
 * Markdown SOURCE → syntax-highlighted [CodeLine]s (Fase 10, `README.md`): the
 * GitHub "Code" view, not the Preview — a rendered document with proportional
 * headings would break JetBrains Mono, the 4px grid and the gutter. Everything
 * maps onto the existing tokens, no new colors:
 *
 * - headings: `#` marks in comment gray, text bold in key-blue
 * - blockquotes: diff-deletion red — README.md only uses them for `## Status`
 *   warnings, the repo's "failing badge"
 * - tables: pipes and separator rows in comment gray, cell text as body text
 * - `**bold**` keeps its asterisks (source view) with the inner text bold
 * - full-line `*italics*` (the footer) in comment gray, italic
 * - `<!-- -->` HTML comments in comment gray, markdown's `//`
 * - numbers (with °C/°F/%/clock-time forms) in orange, like every other screen
 *
 * Body text carries no span and falls through to the canvas' on-surface color.
 */
fun buildMarkdownLines(markdown: List<String>, syntax: SyntaxColors): List<CodeLine> =
    markdown.map { line -> CodeLine(highlightMarkdownLine(line, syntax)) }

private val NumberToken = Regex("""\d+(?:[.,:]\d+)*(?:°[CF]?|%)?""")

private fun highlightMarkdownLine(line: String, syntax: SyntaxColors): AnnotatedString {
    val headingMarks = line.takeWhile { it == '#' }.length
    return when {
        line.isBlank() -> AnnotatedString(line)
        line.startsWith("<!--") -> AnnotatedString(line, SpanStyle(color = syntax.comment))
        headingMarks in 1..6 && line.getOrNull(headingMarks) == ' ' -> buildAnnotatedString {
            withStyle(SpanStyle(color = syntax.comment)) { append(line.take(headingMarks + 1)) }
            withStyle(SpanStyle(color = syntax.key, fontWeight = FontWeight.Bold)) {
                append(line.drop(headingMarks + 1))
            }
        }
        line.startsWith(">") -> buildAnnotatedString {
            withStyle(SpanStyle(color = syntax.comment)) { append(">") }
            withStyle(SpanStyle(color = syntax.diffDel)) { append(line.drop(1)) }
        }
        line.trimStart().startsWith("|") -> tableRow(line, syntax)
        isFullLineItalic(line) -> AnnotatedString(
            line,
            SpanStyle(color = syntax.comment, fontStyle = FontStyle.Italic)
        )
        else -> buildAnnotatedString { appendInlineMarkdown(line, syntax) }
    }
}

/** `*text*` over the whole line but not `**text**` (that's bold, not the footer). */
private fun isFullLineItalic(line: String): Boolean =
    line.length > 2 && line.startsWith("*") && line.endsWith("*") &&
        !line.startsWith("**") && !line.endsWith("**")

private fun tableRow(line: String, syntax: SyntaxColors): AnnotatedString =
    buildAnnotatedString {
        // split keeps empty first/last chunks for leading/trailing pipes
        val cells = line.split("|")
        cells.forEachIndexed { i, cell ->
            if (i > 0) withStyle(SpanStyle(color = syntax.comment)) { append("|") }
            if (cell.isNotEmpty() && cell.all { it == '-' || it == ':' || it == ' ' }) {
                // separator row cells (`---`) are punctuation
                withStyle(SpanStyle(color = syntax.comment)) { append(cell) }
            } else {
                appendInlineMarkdown(cell, syntax)
            }
        }
    }

/** Body text: `**bold**` runs (asterisks in comment gray) and numbers in orange. */
private fun AnnotatedString.Builder.appendInlineMarkdown(text: String, syntax: SyntaxColors) {
    text.split("**").forEachIndexed { i, segment ->
        if (i > 0) withStyle(SpanStyle(color = syntax.comment)) { append("**") }
        val bold = i % 2 == 1
        if (bold) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendWithNumbers(segment, syntax)
            }
        } else {
            appendWithNumbers(segment, syntax)
        }
    }
}

private fun AnnotatedString.Builder.appendWithNumbers(text: String, syntax: SyntaxColors) {
    var cursor = 0
    NumberToken.findAll(text).forEach { match ->
        if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
        withStyle(SpanStyle(color = syntax.number)) { append(match.value) }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) append(text.substring(cursor))
}

@Preview(showBackground = true, backgroundColor = 0xFF10141A, heightDp = 480)
@Composable
private fun MarkdownSyntaxPreview() {
    TstepsTheme {
        val syntax = TstepsTheme.syntax
        val markdown = listOf(
            "# Milan",
            "Lombardy, Italy",
            "",
            "## Current",
            "**34.2°C** · Clear ☀️",
            "",
            "| Day | High | Low |",
            "| --- | ---- | --- |",
            "| Mon | 34° | 24° |",
            "",
            "## Status",
            "> ⚠️ Thunderstorm ⛈️ expected around 18:00",
            "",
            "*Last updated 14:32 · data by Open-Meteo*"
        )
        val lines = remember(markdown, syntax) { buildMarkdownLines(markdown, syntax) }
        CodeCanvas(lines = lines)
    }
}
