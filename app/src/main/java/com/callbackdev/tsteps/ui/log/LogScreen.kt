package com.callbackdev.tsteps.ui.log

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.callbackdev.tsteps.R
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.domain.CommitHash
import com.callbackdev.tsteps.ui.components.CodeCanvas
import com.callbackdev.tsteps.ui.components.EditorTabs
import com.callbackdev.tsteps.ui.components.StatusBarDivider
import com.callbackdev.tsteps.ui.components.StatusBarStart
import com.callbackdev.tsteps.ui.components.StatusBarText
import com.callbackdev.tsteps.ui.components.TerminalStatusBar
import com.callbackdev.tsteps.ui.theme.TstepsTheme
import java.time.LocalDate
import java.util.Locale

/**
 * The Log tab: `steps_history.diff`, the git log made real. Today on top as
 * uncommitted changes, one commit per finished day (tap a commit to expand its
 * diff — steps are the added lines), week separators with the delta against the
 * week before, and records pinned as tags.
 */
@Composable
fun LogScreen(viewModel: LogViewModel = viewModel(factory = LogViewModel.Factory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LogScreen(state = state, onToggle = viewModel::toggle)
}

@Composable
fun LogScreen(
    state: LogUiState,
    onToggle: (LocalDate) -> Unit = {}
) {
    val syntax = TstepsTheme.syntax
    val resources = LocalContext.current.resources
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val lines = remember(state, syntax, locale) {
        LogDocument.build(
            today = state.today,
            days = state.days,
            expanded = state.expanded,
            bestDay = state.bestDay,
            units = state.units,
            locale = locale,
            syntax = syntax,
            onToggle = onToggle,
            toggleLabel = { date -> resources.getString(R.string.cd_toggle_commit, date) }
        )
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            EditorTabs(
                fileNames = listOf("steps_history.diff"),
                activeIndex = 0,
                onSelect = {}
            )
            CodeCanvas(
                lines = lines,
                state = rememberLazyListState(),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
            )
            TerminalStatusBar {
                StatusBarStart {
                    StatusBarText("⎇ main")
                    StatusBarDivider()
                    StatusBarText(stringResource(R.string.status_commits, state.days.size))
                }
                StatusBarText(
                    "HEAD → " + (state.days.firstOrNull()?.let { CommitHash.of(it.date) } ?: "none")
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF10141A, heightDp = 800)
@Composable
private fun LogScreenPreview() {
    TstepsTheme {
        LogScreen(
            state = LogUiState(
                today = UncommittedToday(
                    date = LocalDate.parse("2026-08-18"),
                    steps = 8_432,
                    distanceMeters = 6_123.0,
                    activeMinutes = 74
                ),
                days = listOf(
                    CommitDay(LocalDate.parse("2026-08-17"), 11_204, 96, 8_300.0, 421.0, 10_000, true),
                    CommitDay(LocalDate.parse("2026-08-16"), 4_113, 33, 2_900.0, null, 10_000, false),
                    CommitDay(LocalDate.parse("2026-08-14"), 9_800, 80, 7_000.0, null, 0, null)
                ),
                expanded = setOf(LocalDate.parse("2026-08-17")),
                bestDay = LocalDate.parse("2026-08-17"),
                units = UnitsSystem.METRIC
            )
        )
    }
}
