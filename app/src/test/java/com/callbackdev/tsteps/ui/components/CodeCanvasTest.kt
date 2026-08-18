package com.callbackdev.tsteps.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import com.callbackdev.tsteps.ui.theme.TstepsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CodeCanvasTest {

    @get:Rule
    val compose = createComposeRule()

    private val lines = listOf(
        CodeLine(AnnotatedString("{")),
        CodeLine(AnnotatedString("\"active\": true"), indent = 1),
        CodeLine(AnnotatedString("}"))
    )

    @Test
    fun gutterShowsLineNumbersWhenEnabled() {
        compose.setContent {
            TstepsTheme {
                CodeCanvas(lines = lines, options = EditorOptions(showLineNumbers = true))
            }
        }
        compose.onNodeWithText("1").assertExists()
        compose.onNodeWithText("2").assertExists()
        compose.onNodeWithText("3").assertExists()
    }

    @Test
    fun gutterIsHiddenByDefault() {
        compose.setContent {
            TstepsTheme {
                CodeCanvas(lines = lines) // mobile default: no line numbers
            }
        }
        compose.onNodeWithText("1").assertDoesNotExist()
    }

    /**
     * Regression (found on device, Fase 11): the shared row width used to measure
     * only [CodeLine]s, so a widget row wider than every text line was squeezed to
     * their width and its content truncated (`[rm]` → `[r`, placeholder → `Cerc`).
     * With [WidgetLine.measureText] the row takes part in the measurement.
     */
    @Test
    fun widgetLineWiderThanEveryCodeLineIsNotSqueezed() {
        val longText = "\"cavenago_di_brianza.json\",  // active  [rm]  extra width"
        // Same length in the same style = same monospace width: the free-standing
        // twin measures what the row's text SHOULD span when nothing squeezes it.
        val reference = longText.replace("extra", "extrb")
        compose.setContent {
            TstepsTheme {
                androidx.compose.foundation.layout.Column {
                    CodeCanvas(
                        lines = listOf(
                            CodeLine(AnnotatedString("{")), // the widest CodeLine is tiny
                            WidgetLine(indent = 1, measureText = longText) {
                                androidx.compose.material3.Text(
                                    text = longText,
                                    style = androidx.compose.material3.MaterialTheme
                                        .typography.bodySmall,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        ),
                        modifier = androidx.compose.ui.Modifier
                            .weight(1f)
                            .fillMaxSize()
                    )
                    androidx.compose.material3.Text(
                        text = reference,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
        val row = compose.onNodeWithText(longText).fetchSemanticsNode().size.width
        val intrinsic = compose.onNodeWithText(reference).fetchSemanticsNode().size.width
        // Without the measurement the row shares the tiny CodeLine width and its
        // text gets constrained way below the intrinsic width.
        org.junit.Assert.assertTrue(
            "widget row was squeezed: $row px vs intrinsic $intrinsic px",
            row >= intrinsic - 2
        )
    }

    @Test
    fun tappableCodeLineInvokesItsOnClick() {
        var clicks = 0
        compose.setContent {
            TstepsTheme {
                CodeCanvas(
                    lines = listOf(
                        CodeLine(AnnotatedString("\"word_wrap\": false"), onClick = { clicks++ })
                    )
                )
            }
        }
        compose.onNodeWithText("\"word_wrap\": false").performClick()
        assertEquals(1, clicks)
    }
}
