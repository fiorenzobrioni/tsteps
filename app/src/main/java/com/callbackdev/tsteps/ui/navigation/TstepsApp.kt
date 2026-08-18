package com.callbackdev.tsteps.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.callbackdev.tsteps.ui.steps.StepsScreen
import com.callbackdev.tsteps.ui.components.EditorNavBar
import com.callbackdev.tsteps.ui.components.EditorNavItems
import com.callbackdev.tsteps.ui.theme.TstepsTheme

/**
 * App shell, Fase 1 edition: the editor-style bottom bar over one placeholder per
 * tab. Deliberately NOT Navigation Compose yet — the real NavHost (with per-tab
 * saved stacks, tweather's pattern) is Fase 4 work; this shell exists so the kit
 * components are exercised on device from day one. Selection is a plain saveable
 * route string, which Fase 4 will replace wholesale.
 */
@Composable
fun TstepsApp() {
    var route by rememberSaveable { mutableStateOf(EditorNavItems.Editor.route) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Box(Modifier.weight(1f)) {
                when (route) {
                    EditorNavItems.Editor.route -> StepsScreen()
                    EditorNavItems.Log.route -> PlaceholderFile("steps_history.diff")
                    EditorNavItems.Stats.route -> PlaceholderFile("stats.md")
                    EditorNavItems.Settings.route -> PlaceholderFile("settings.config")
                }
            }
            EditorNavBar(
                items = EditorNavItems.All,
                isSelected = { it.route == route },
                onSelect = { route = it.route }
            )
        }
    }
}

/** A file that exists in the plan but not on disk yet. */
@Composable
private fun PlaceholderFile(fileName: String) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Text(
            text = "// $fileName — not yet written",
            style = MaterialTheme.typography.bodySmall,
            color = TstepsTheme.syntax.comment
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF10141A)
@Composable
private fun TstepsAppPreview() {
    TstepsTheme { TstepsApp() }
}
