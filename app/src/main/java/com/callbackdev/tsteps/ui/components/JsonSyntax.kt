package com.callbackdev.tsteps.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import com.callbackdev.tsteps.ui.theme.SyntaxColors
import com.callbackdev.tsteps.ui.theme.TstepsTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * JSON → syntax-highlighted [CodeLine]s: keys in blue, strings in light blue, numbers
 * and booleans in orange, punctuation in gray (tokens from [SyntaxColors]). Nesting
 * adds one indent level; small all-primitive objects/arrays are rendered inline like
 * in the mockups (`{ "time": "14:00", "temp": 15 }`).
 */
fun buildJsonLines(root: JsonElement, syntax: SyntaxColors): List<CodeLine> {
    val lines = mutableListOf<CodeLine>()
    renderElement(root, key = null, indent = 0, trailingComma = false, syntax, lines)
    return lines
}

/** A whole line in comment gray; [text] should include the `//`. */
fun commentLine(text: String, syntax: SyntaxColors, indent: Int = 0): CodeLine =
    CodeLine(AnnotatedString(text, SpanStyle(color = syntax.comment)), indent)

/** Punctuation only: `{`, `},`, `]`… — the structural lines of a fake config file. */
fun punctLine(text: String, indent: Int, syntax: SyntaxColors): CodeLine =
    CodeLine(AnnotatedString(text, SpanStyle(color = syntax.comment)), indent)

/** `"editor": {` — the line that opens a nested block (or an array with `[`). */
fun keyOpenLine(
    key: String,
    indent: Int,
    syntax: SyntaxColors,
    bracket: String = "{",
    hint: String? = null
): CodeLine = CodeLine(
    buildAnnotatedString {
        withStyle(SpanStyle(color = syntax.key)) { append("\"$key\"") }
        withStyle(SpanStyle(color = syntax.comment)) { append(": $bracket") }
        appendHint(hint, syntax)
    },
    indent
)

/**
 * `"temperature": "celsius",  // hint` — with an [onClick] the whole line is the
 * control (flip, cycle, open a link); without one it is a read-only line.
 */
fun stringValueLine(
    key: String,
    value: String,
    comma: Boolean,
    syntax: SyntaxColors,
    indent: Int = 2,
    hint: String? = null,
    onClickLabel: String? = null,
    onClick: (() -> Unit)? = null
): CodeLine = CodeLine(
    text = buildAnnotatedString {
        withStyle(SpanStyle(color = syntax.key)) { append("\"$key\"") }
        withStyle(SpanStyle(color = syntax.comment)) { append(": ") }
        withStyle(SpanStyle(color = syntax.string)) { append("\"$value\"") }
        if (comma) withStyle(SpanStyle(color = syntax.comment)) { append(",") }
        appendHint(hint, syntax)
    },
    indent = indent,
    onClick = onClick,
    onClickLabel = onClickLabel
)

/** Trailing `  // hint`, dimmed like the mockups' inline annotations. */
private fun AnnotatedString.Builder.appendHint(hint: String?, syntax: SyntaxColors) {
    if (hint == null) return
    withStyle(SpanStyle(color = syntax.comment.copy(alpha = 0.6f))) { append("  $hint") }
}

/** Single monospaced code line outside a [CodeCanvas] (code-block style, no wrap). */
@Composable
fun SyntaxText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    softWrap: Boolean = false
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        softWrap = softWrap
    )
}

// Containers this small and flat render on one line. Array elements get one more
// slot so hourly rows render inline like the PRD sample
// (`{ "time": "15:00", "temp_c": 19, "status": "Sunny ☀️", "precip_chance": 0 }`).
private const val InlineMaxEntries = 3
private const val InlineMaxEntriesInArray = 4

private fun renderElement(
    element: JsonElement,
    key: String?,
    indent: Int,
    trailingComma: Boolean,
    syntax: SyntaxColors,
    out: MutableList<CodeLine>
) {
    if (element.isInline(if (key == null && indent > 0) InlineMaxEntriesInArray else InlineMaxEntries)) {
        out += CodeLine(
            buildAnnotatedString {
                appendKey(key, syntax)
                appendInline(element, syntax)
                if (trailingComma) appendPunct(",", syntax)
            },
            indent
        )
        return
    }
    val (open, close) = if (element is JsonObject) "{" to "}" else "[" to "]"
    out += CodeLine(
        buildAnnotatedString {
            appendKey(key, syntax)
            appendPunct(open, syntax)
        },
        indent
    )
    val children: List<Pair<String?, JsonElement>> = when (element) {
        is JsonObject -> element.entries.map { it.key to it.value }
        is JsonArray -> element.map { null to it }
        else -> emptyList()
    }
    children.forEachIndexed { i, (childKey, child) ->
        renderElement(child, childKey, indent + 1, i != children.lastIndex, syntax, out)
    }
    out += CodeLine(
        buildAnnotatedString {
            appendPunct(close, syntax)
            if (trailingComma) appendPunct(",", syntax)
        },
        indent
    )
}

private fun JsonElement.isInline(maxEntries: Int): Boolean = when (this) {
    is JsonPrimitive -> true
    is JsonObject -> size <= maxEntries && values.all { it is JsonPrimitive }
    is JsonArray -> size <= maxEntries && all { it is JsonPrimitive }
}

private fun AnnotatedString.Builder.appendKey(key: String?, syntax: SyntaxColors) {
    if (key == null) return
    withStyle(SpanStyle(color = syntax.key)) { append("\"$key\"") }
    appendPunct(": ", syntax)
}

private fun AnnotatedString.Builder.appendInline(element: JsonElement, syntax: SyntaxColors) {
    when (element) {
        is JsonPrimitive -> appendPrimitive(element, syntax)
        is JsonObject -> {
            appendPunct("{ ", syntax)
            element.entries.forEachIndexed { i, (k, v) ->
                appendKey(k, syntax)
                appendInline(v, syntax)
                if (i != element.size - 1) appendPunct(", ", syntax)
            }
            appendPunct(" }", syntax)
        }
        is JsonArray -> {
            appendPunct("[", syntax)
            element.forEachIndexed { i, v ->
                appendInline(v, syntax)
                if (i != element.lastIndex) appendPunct(", ", syntax)
            }
            appendPunct("]", syntax)
        }
    }
}

private fun AnnotatedString.Builder.appendPrimitive(p: JsonPrimitive, syntax: SyntaxColors) {
    when {
        p is JsonNull -> withStyle(SpanStyle(color = syntax.comment)) { append("null") }
        p.isString -> withStyle(SpanStyle(color = syntax.string)) { append("\"${p.content}\"") }
        else -> withStyle(SpanStyle(color = syntax.number)) { append(p.content) }
    }
}

private fun AnnotatedString.Builder.appendPunct(s: String, syntax: SyntaxColors) {
    withStyle(SpanStyle(color = syntax.comment)) { append(s) }
}

@Preview(showBackground = true, backgroundColor = 0xFF10141A, heightDp = 480)
@Composable
private fun JsonSyntaxPreview() {
    com.callbackdev.tsteps.ui.theme.TstepsTheme {
        val syntax = TstepsTheme.syntax
        val sample = remember {
            buildJsonObject {
                put("location", "New York, NY")
                putJsonObject("current_conditions") {
                    put("status", "Overcast ☁️")
                    put("temp_c", 14.2)
                    put("feels_like_c", 13.5)
                }
                putJsonArray("sessions") {
                    addJsonObject { put("time", "14:00"); put("temp", 15) }
                    addJsonObject { put("time", "15:00"); put("temp", 14) }
                }
                putJsonObject("system_info") {
                    put("source", "Open-Meteo API")
                    put("cache", true)
                    put("retries", JsonNull)
                }
            }
        }
        val lines = remember(sample, syntax) { buildJsonLines(sample, syntax) }
        CodeCanvas(lines = lines)
    }
}
