package com.callbackdev.tsteps.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.callbackdev.tsteps.ui.theme.TstepsTheme

/**
 * Editor-style top bar: terminal prompt glyph + one tappable tab per open file,
 * 48dp tall, flat with a 1px bottom border (mockup TopAppBar). Border in
 * `syntax.border` like the nav and status bars — DESIGN.md wants one color for all
 * structural separation, and this bar is the same chrome. Active tab in primary with
 * a 2px bottom indicator (the nav bar's active treatment); inactive tabs in comment
 * gray. Tabs scroll horizontally when the file names outgrow a narrow screen.
 * [actions] land pinned on the right, outside the scrolling tab strip (tweather
 * learned that a pinned action steals fixed width from the strip and truncates the
 * file names — use sparingly).
 *
 * File name at bodyMedium + bold on every screen (tweather's pre-v1 decision):
 * the tab bar is chrome, not content — single-file titles at display size clashed
 * with real multi-tab bars on every tab switch.
 *
 * Single-file screens (`settings.config`, `stats.md`) pass a one-element list
 * rather than a plainer bar of their own (pre-v1): they were the only two places
 * where the open file had no indicator under it, which read as a different kind of
 * chrome on every switch into them. A one-element strip also means those screens
 * grow a second file without touching their layout.
 */
@Composable
fun EditorTabs(
    fileNames: List<String>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val syntax = TstepsTheme.syntax
    val borderColor = syntax.border
    val indicatorColor = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(MaterialTheme.colorScheme.background)
            .drawBehind {
                drawLine(
                    color = borderColor,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = ">_",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primaryContainer
        )
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            fileNames.forEachIndexed { index, fileName ->
                val active = index == activeIndex
                Box(
                    modifier = Modifier
                        // Tab height = bar height, so the 2px indicator lands on
                        // the bar's bottom edge (and keeps growing with font scale)
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = active,
                            role = Role.Tab,
                            onClick = { onSelect(index) }
                        )
                        .drawBehind {
                            if (active) {
                                drawLine(
                                    color = indicatorColor,
                                    start = Offset(0f, size.height),
                                    end = Offset(size.width, size.height),
                                    strokeWidth = 2.dp.toPx()
                                )
                            }
                        }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (active) indicatorColor else syntax.comment
                    )
                }
            }
        }
        actions()
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF10141A)
@Composable
private fun EditorTabSingleFilePreview() {
    TstepsTheme {
        EditorTabs(fileNames = listOf("stats.md"), activeIndex = 0, onSelect = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF10141A)
@Composable
private fun EditorTabsPreview() {
    TstepsTheme {
        EditorTabs(
            fileNames = listOf("steps_data.json", "README.md"),
            activeIndex = 0,
            onSelect = {}
        )
    }
}
