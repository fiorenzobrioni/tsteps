package com.callbackdev.tsteps.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.callbackdev.tsteps.ui.theme.ObsidianSyntax
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The markdown SOURCE highlighter (Fase 10, README.md): headings key-blue and
 * bold, markdown punctuation comment gray, blockquotes diff-red, numbers orange,
 * footer italics, HTML comments gray. Body text carries no color span (it falls
 * through to the canvas' on-surface).
 */
class MarkdownSyntaxTest {

    private val syntax = ObsidianSyntax

    private fun line(markdown: String): AnnotatedString =
        buildMarkdownLines(listOf(markdown), syntax).single().text

    private fun AnnotatedString.stylesOf(substring: String) = spanStyles.filter {
        val target = text.indexOf(substring)
        it.start <= target && it.end >= target + substring.length
    }

    @Test
    fun `text round-trips unchanged`() {
        val markdown = listOf("# Milan", "", "**34.2°C** · Clear ☀️", "| Mon | 34° |")
        assertEquals(markdown, buildMarkdownLines(markdown, syntax).map { it.text.text })
    }

    @Test
    fun `headings are bold key-blue with gray hash marks`() {
        val heading = line("## Current")
        assertTrue(heading.stylesOf("## ").any { it.item.color == syntax.comment })
        assertTrue(
            heading.stylesOf("Current").any {
                it.item.color == syntax.key && it.item.fontWeight == FontWeight.Bold
            }
        )
    }

    @Test
    fun `blockquotes render in the diff-deletion red`() {
        val quote = line("> ⚠️ Thunderstorm ⛈️ expected around 18:00")
        assertTrue(quote.stylesOf(">").any { it.item.color == syntax.comment })
        assertTrue(quote.stylesOf("Thunderstorm").any { it.item.color == syntax.diffDel })
    }

    @Test
    fun `table pipes and separator rows are punctuation, cell numbers orange`() {
        val row = line("| Mon | 34° | 24° |")
        // every pipe carries its own comment-gray span
        val pipeSpans = row.spanStyles.filter {
            it.item.color == syntax.comment && row.text.substring(it.start, it.end) == "|"
        }
        assertEquals(4, pipeSpans.size)
        assertTrue(row.stylesOf("34°").any { it.item.color == syntax.number })
        val separator = line("| --- | ---- |")
        assertTrue(separator.stylesOf("---").any { it.item.color == syntax.comment })
    }

    @Test
    fun `bold runs keep their asterisks and numbers stay orange inside them`() {
        val bold = line("**34.2°C** · Clear ☀️")
        assertTrue(bold.stylesOf("**").any { it.item.color == syntax.comment })
        assertTrue(bold.stylesOf("34.2°C").any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(bold.stylesOf("34.2°C").any { it.item.color == syntax.number })
    }

    @Test
    fun `full-line italics (the footer) are gray italic`() {
        val footer = line("*Last updated 14:32 · data by Open-Meteo*")
        assertTrue(
            footer.stylesOf("Last updated").any {
                it.item.color == syntax.comment && it.item.fontStyle == FontStyle.Italic
            }
        )
    }

    @Test
    fun `html comments are all gray`() {
        val comment = line("<!-- fetching README.md … -->")
        assertTrue(comment.stylesOf("fetching").any { it.item.color == syntax.comment })
    }

    @Test
    fun `clock times and percentages highlight as numbers in body text`() {
        val body = line("Sunrise: 06:12 · Sunset: 20:35")
        assertTrue(body.stylesOf("06:12").any { it.item.color == syntax.number })
        assertTrue(body.stylesOf("20:35").any { it.item.color == syntax.number })
        val humidity = line("💧 Humidity: 54%")
        assertTrue(humidity.stylesOf("54%").any { it.item.color == syntax.number })
    }
}
