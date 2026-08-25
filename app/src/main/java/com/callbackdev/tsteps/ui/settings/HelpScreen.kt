package com.callbackdev.tsteps.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.callbackdev.tsteps.R
import com.callbackdev.tsteps.ui.components.CodeCanvas
import com.callbackdev.tsteps.ui.components.EditorTabs
import com.callbackdev.tsteps.ui.components.StatusBarDivider
import com.callbackdev.tsteps.ui.components.TerminalStatusBar
import com.callbackdev.tsteps.ui.components.buildMarkdownLines
import com.callbackdev.tsteps.ui.theme.TstepsTheme

/**
 * `HELP.md` — the second file behind the Settings tab bar (Fase 17), and the app's
 * answer to "what is a commit?" for someone who does not read `git` for a living.
 *
 * Deliberately a file and not an intro carousel: a definition offered before you
 * have seen the thing it defines does not stick, and a screen shown once cannot be
 * consulted the day the question actually arrives. Developers learn a tool from its
 * `--help`, not from slides — so the explanation lives where it can be re-opened
 * forever, and the first run only points at it (the one-shot hint in the editor).
 *
 * Prose, so fully localized, headings included: the same rule the `README.md` day
 * tab follows. The words in `code spans` are the app's own file and key names.
 */
@Composable
fun HelpScreen(
    onSelectFile: (Int) -> Unit,
    modifier: Modifier = Modifier,
    canvasState: LazyListState = rememberLazyListState()
) {
    val syntax = TstepsTheme.syntax
    val resources = LocalContext.current.resources
    // One resource item per rendered line: the document is markdown SOURCE, and a
    // real newline inside an Android string resource is collapsed into a space.
    val lines = buildMarkdownLines(resources.getStringArray(R.array.help_md).toList(), syntax)
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            EditorTabs(
                fileNames = SettingsFiles,
                activeIndex = HelpFileIndex,
                onSelect = onSelectFile
            )
            CodeCanvas(
                lines = lines,
                state = canvasState,
                modifier = Modifier.weight(1f)
            )
            TerminalStatusBar {
                Text("⎇ config")
                StatusBarDivider()
                Text("ro") // read-only: the only file in the app you cannot edit
                StatusBarDivider()
                Text("UTF-8")
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF10141A)
@Composable
private fun HelpScreenPreview() {
    TstepsTheme {
        HelpScreen(onSelectFile = {})
    }
}
