package com.callbackdev.tsteps.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.callbackdev.tsteps.ui.theme.TstepsTheme

/**
 * Single-line terminal prompt input (`> Search Location _`): prompt glyph in gray,
 * typed text as a syntax string. The blinking underscore plays the idle cursor while
 * the field is empty; once there is text, the native caret (accent-colored) shows
 * the real edit position — an underscore pinned at the end would lie about it.
 */
@Composable
fun TerminalInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    prompt: String = ">",
    placeholder: String = "",
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    val syntax = TstepsTheme.syntax
    val textStyle = MaterialTheme.typography.bodySmall

    // Created only while the idle cursor is actually drawn (empty field): an
    // InfiniteTransition keeps the frame clock ticking every vsync for as long as
    // it exists, even though the underscore only changes twice a second.
    val cursorAlpha = if (value.isEmpty()) {
        val blink = rememberInfiniteTransition(label = "cursor-blink")
        blink.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 1000
                    1f at 0 using LinearEasing
                    1f at 499
                    0f at 500
                    0f at 999
                }
            ),
            label = "cursor-alpha"
        ).value
    } else {
        1f
    }

    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.replace("\n", "")) },
        modifier = modifier.semantics {
            // The placeholder Text below is a sibling drawn behind the field, so it
            // names the field here for screen readers (like Material text fields do).
            if (placeholder.isNotEmpty()) contentDescription = placeholder
        },
        textStyle = textStyle.copy(color = syntax.string),
        singleLine = true,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primaryContainer),
        decorationBox = { innerTextField ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (prompt.isNotEmpty()) {
                    Text(
                        text = prompt,
                        style = textStyle,
                        color = syntax.comment
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Box {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            style = textStyle,
                            color = syntax.comment.copy(alpha = 0.6f)
                        )
                    }
                    innerTextField()
                }
                if (value.isEmpty()) {
                    Text(
                        text = "_",
                        style = textStyle,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.alpha(cursorAlpha)
                    )
                }
            }
        }
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF10141A)
@Composable
private fun TerminalInputPreview() {
    TstepsTheme {
        var text by remember { mutableStateOf("") }
        TerminalInput(
            value = text,
            onValueChange = { text = it },
            placeholder = "Search Location",
            modifier = Modifier.padding(16.dp)
        )
    }
}
