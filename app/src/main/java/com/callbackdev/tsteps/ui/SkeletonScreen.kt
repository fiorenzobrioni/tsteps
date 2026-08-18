package com.callbackdev.tsteps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.callbackdev.tsteps.ui.theme.TstepsTheme

/**
 * Fase 0 placeholder: a static, read-only rendering of the future steps_data.json
 * in the editor style (line-number gutter + syntax colors), so the very first build
 * already looks like tsteps. Replaced by the real editor screen in later phases.
 */
@Composable
fun SkeletonScreen() {
    val syntax = TstepsTheme.syntax
    val lines = listOf(
        buildAnnotatedString {
            withStyle(SpanStyle(color = syntax.comment)) { append("{") }
        },
        buildAnnotatedString {
            withStyle(SpanStyle(color = syntax.comment)) {
                append("  // fase 0 — nothing is counted yet")
            }
        },
        buildAnnotatedString {
            withStyle(SpanStyle(color = syntax.key)) { append("  \"app\"") }
            withStyle(SpanStyle(color = syntax.comment)) { append(": ") }
            withStyle(SpanStyle(color = syntax.string)) { append("\"tsteps\"") }
            withStyle(SpanStyle(color = syntax.comment)) { append(",") }
        },
        buildAnnotatedString {
            withStyle(SpanStyle(color = syntax.key)) { append("  \"version\"") }
            withStyle(SpanStyle(color = syntax.comment)) { append(": ") }
            withStyle(SpanStyle(color = syntax.string)) { append("\"0.1.0\"") }
            withStyle(SpanStyle(color = syntax.comment)) { append(",") }
        },
        buildAnnotatedString {
            withStyle(SpanStyle(color = syntax.key)) { append("  \"steps_today\"") }
            withStyle(SpanStyle(color = syntax.comment)) { append(": ") }
            withStyle(SpanStyle(color = syntax.number)) { append("0") }
            withStyle(SpanStyle(color = syntax.comment)) { append(",") }
        },
        buildAnnotatedString {
            withStyle(SpanStyle(color = syntax.key)) { append("  \"status\"") }
            withStyle(SpanStyle(color = syntax.comment)) { append(": ") }
            withStyle(SpanStyle(color = syntax.string)) { append("\"under_construction\"") }
        },
        buildAnnotatedString {
            withStyle(SpanStyle(color = syntax.comment)) { append("}") }
        }
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(16.dp)
    ) {
        lines.forEachIndexed { index, line ->
            Row {
                Text(
                    text = "%2d".format(index + 1),
                    style = MaterialTheme.typography.bodySmall,
                    color = syntax.comment
                )
                Spacer(Modifier.width(16.dp))
                Text(text = line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF10141A)
@Composable
private fun SkeletonScreenPreview() {
    TstepsTheme { SkeletonScreen() }
}
