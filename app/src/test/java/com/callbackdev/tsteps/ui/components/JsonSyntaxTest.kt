package com.callbackdev.tsteps.ui.components

import androidx.compose.ui.graphics.Color
import com.callbackdev.tsteps.ui.theme.ObsidianSyntax
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * buildJsonLines is a pure function (JSON → colored AnnotatedString lines), so the
 * syntax highlighting contract is asserted here without rendering anything.
 */
class JsonSyntaxTest {

    private val syntax = ObsidianSyntax

    /** Color of the span covering [sub] (first occurrence) in this line. */
    private fun CodeLine.colorOf(sub: String): Color {
        val start = text.text.indexOf(sub)
        assertTrue("'$sub' not found in '${text.text}'", start >= 0)
        val range = text.spanStyles.first { it.start <= start && start + sub.length <= it.end }
        return range.item.color
    }

    @Test
    fun `keys strings numbers booleans and null get their token colors`() {
        // 4 entries → above the inline threshold, renders as a multi-line object.
        val json = buildJsonObject {
            put("app", "tsteps")
            put("temp_c", 21.5)
            put("cached", true)
            put("aqi", JsonNull)
        }
        val lines = buildJsonLines(json, syntax)

        assertEquals(6, lines.size) // { + 4 entries + }
        val byText = { sub: String -> lines.first { (it as CodeLine).text.text.contains(sub) } as CodeLine }
        assertEquals(syntax.key, byText("\"app\"").colorOf("\"app\""))
        assertEquals(syntax.string, byText("tsteps").colorOf("\"tsteps\""))
        assertEquals(syntax.number, byText("21.5").colorOf("21.5"))
        assertEquals(syntax.number, byText("true").colorOf("true"))
        assertEquals(syntax.comment, byText("null").colorOf("null"))
        // Punctuation (braces) in comment gray.
        assertEquals(syntax.comment, (lines.first() as CodeLine).colorOf("{"))
    }

    @Test
    fun `sparkline and goal-bar glyphs tokenize as plain string values`() {
        // steps_data.json renders its hourly sparkline and goal bar as string
        // values made of block glyphs; the tokenizer must not choke on them.
        val json = buildJsonObject {
            put("hourly", "▁▂▅▇▃▁")
            put("check", "▓▓▓░░ 60%")
            put("steps", 8432)
            put("goal", 10000)
        }
        val lines = buildJsonLines(json, syntax)

        assertEquals(6, lines.size) // { + 4 entries + }
        val byText = { sub: String -> lines.first { (it as CodeLine).text.text.contains(sub) } as CodeLine }
        assertEquals(syntax.string, byText("▁▂").colorOf("▅▇"))
        assertEquals(syntax.string, byText("60%").colorOf("▓"))
        assertEquals(syntax.key, byText("hourly").colorOf("\"hourly\""))
    }

    @Test
    fun `small flat object renders inline on one line`() {
        val json = buildJsonObject {
            putJsonObject("wind") {
                put("speed_kph", 12.5)
                put("direction", "NW")
            }
        }
        val lines = buildJsonLines(json, syntax)

        // Root braces + a single inline line for the whole small object.
        assertEquals(3, lines.size)
        val inline = lines[1] as CodeLine
        assertEquals("\"wind\": { \"speed_kph\": 12.5, \"direction\": \"NW\" }", inline.text.text)
        assertEquals(1, inline.indent)
    }

    @Test
    fun `array rows render inline up to four fields`() {
        val json = buildJsonObject {
            putJsonArray("sessions") {
                addJsonObject {
                    put("time", "15:00")
                    put("temp_c", 19)
                    put("status", "Sunny ☀️")
                    put("precip_chance", 0)
                }
            }
        }
        val lines = buildJsonLines(json, syntax)

        // Root { , "sessions": [ , the inline row, ] , }
        assertEquals(5, lines.size)
        val row = lines[2] as CodeLine
        assertEquals(
            "{ \"time\": \"15:00\", \"temp_c\": 19, \"status\": \"Sunny ☀️\", \"precip_chance\": 0 }",
            row.text.text
        )
    }

    @Test
    fun `four entries outside an array break into multiple lines`() {
        val json = buildJsonObject {
            putJsonObject("current") {
                put("a", 1); put("b", 2); put("c", 3); put("d", 4)
            }
        }
        val lines = buildJsonLines(json, syntax)
        assertEquals(8, lines.size) // { , "current": { , 4 entries , } , }
    }

    @Test
    fun `nesting increments indent by one level`() {
        val json = buildJsonObject {
            putJsonObject("location") {
                putJsonObject("coordinates") {
                    put("lat", 40.7)
                    put("lon", -74.0)
                    put("alt", 10)
                    put("datum", "WGS84")
                }
                put("x", 1); put("y", 2); put("z", 3)
            }
        }
        val lines = buildJsonLines(json, syntax).map { it as CodeLine }

        assertEquals(0, lines.first { it.text.text == "{" }.indent)
        assertEquals(1, lines.first { it.text.text.startsWith("\"location\"") }.indent)
        assertEquals(2, lines.first { it.text.text.startsWith("\"coordinates\"") }.indent)
        assertEquals(3, lines.first { it.text.text.startsWith("\"lat\"") }.indent)
    }

    @Test
    fun `siblings are separated by trailing commas`() {
        val json = buildJsonObject {
            put("a", 1); put("b", 2); put("c", 3); put("d", 4)
        }
        val lines = buildJsonLines(json, syntax).map { it as CodeLine }
        assertTrue(lines[1].text.text.endsWith(","))   // "a": 1,
        assertTrue(lines[2].text.text.endsWith(","))
        assertTrue(lines[3].text.text.endsWith(","))
        assertTrue(!lines[4].text.text.endsWith(",")) // last entry, no comma
    }

    @Test
    fun `comment lines are fully comment colored`() {
        val line = commentLine("// reading steps_data.json …", syntax)
        assertEquals(syntax.comment, line.colorOf("// reading"))
        assertEquals(1, line.text.spanStyles.size)
    }
}
