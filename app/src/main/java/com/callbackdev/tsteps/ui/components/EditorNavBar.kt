package com.callbackdev.tsteps.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.callbackdev.tsteps.R
import com.callbackdev.tsteps.ui.theme.TstepsTheme

/** One destination of [EditorNavBar]; [route] doubles as the selection key. */
data class EditorNavItem(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector
)

/**
 * Bottom navigation styled like the mockups: 56dp, flat on surface-container-low
 * with a 1px top border, label-sm typography. The active item is primary-colored
 * with a 2px indicator line on its top edge (no Material ripple pill).
 */
@Composable
fun EditorNavBar(
    items: List<EditorNavItem>,
    isSelected: (EditorNavItem) -> Boolean,
    onSelect: (EditorNavItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = TstepsTheme.syntax.border
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .drawBehind {
                drawLine(
                    color = borderColor,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .navigationBarsPadding()
            // 56dp on the default density; grows with the system font scale
            .heightIn(min = 56.dp)
            .height(IntrinsicSize.Min)
    ) {
        items.forEach { item ->
            val selected = isSelected(item)
            val tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .drawBehind {
                        if (selected) {
                            drawLine(
                                color = tint,
                                start = Offset(0f, 1.dp.toPx()),
                                end = Offset(size.width, 1.dp.toPx()),
                                strokeWidth = 2.dp.toPx()
                            )
                        }
                    }
                    .selectable(selected = selected, role = Role.Tab) { onSelect(item) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    item.icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = stringResource(item.labelRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = tint
                )
            }
        }
    }
}

/**
 * The app's four tabs. Glyph choices: the Editor keeps tweather's `{ }` (it opens
 * steps_data.json), the Log gets the commit glyph (a dot on a branch line — the
 * history IS a git log here), Stats gets insights for stats.md, Settings keeps
 * tweather's code glyph for settings.config.
 */
object EditorNavItems {
    val Editor = EditorNavItem("editor", R.string.nav_editor, Icons.Filled.DataObject)
    val Log = EditorNavItem("log", R.string.nav_log, Icons.Filled.Commit)
    val Stats = EditorNavItem("stats", R.string.nav_stats, Icons.Filled.Insights)
    val Settings = EditorNavItem("settings", R.string.nav_settings, Icons.Filled.Code)
    val All = listOf(Editor, Log, Stats, Settings)
}

@Preview(showBackground = true, backgroundColor = 0xFF10141A)
@Composable
private fun EditorNavBarPreview() {
    TstepsTheme {
        EditorNavBar(
            items = EditorNavItems.All,
            isSelected = { it == EditorNavItems.Editor },
            onSelect = {}
        )
    }
}
