package com.callbackdev.tsteps.ui.stats

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
import com.callbackdev.tsteps.domain.Heatmap
import com.callbackdev.tsteps.domain.Records
import com.callbackdev.tsteps.domain.SessionItem
import com.callbackdev.tsteps.domain.WindowAverages
import com.callbackdev.tsteps.ui.components.CodeCanvas
import com.callbackdev.tsteps.ui.components.EditorTabs
import com.callbackdev.tsteps.ui.components.StatusBarDivider
import com.callbackdev.tsteps.ui.components.StatusBarStart
import com.callbackdev.tsteps.ui.components.StatusBarText
import com.callbackdev.tsteps.ui.components.TerminalStatusBar
import com.callbackdev.tsteps.ui.log.LogFocus
import com.callbackdev.tsteps.ui.theme.TstepsTheme
import java.time.LocalDate
import java.util.Locale

/**
 * The Stats tab: `stats.md` — your movement as a contribution graph, plus
 * streaks, averages and record tags. Read-only markdown source (the status bar
 * says `ro`); the tag rows are the file's only interaction, linking each record
 * to its commit in the log.
 */
@Composable
fun StatsScreen(
    onOpenLog: () -> Unit = {},
    viewModel: StatsViewModel = viewModel(factory = StatsViewModel.Factory)
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    StatsScreen(
        state = state,
        onOpenCommit = { date ->
            LogFocus.request(date)
            onOpenLog()
        }
    )
}

@Composable
fun StatsScreen(
    state: StatsUiState,
    onOpenCommit: (LocalDate) -> Unit = {}
) {
    val syntax = TstepsTheme.syntax
    val resources = LocalContext.current.resources
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val lines = remember(state, syntax, locale) {
        StatsDocument.build(
            grid = state.grid,
            streak = state.streak,
            averages = state.averages,
            totals = state.totals,
            bestDay = state.bestDay,
            longestWalk = state.longestWalk,
            bestWeek = state.bestWeek,
            committedDays = state.committedDays,
            units = state.units,
            locale = locale,
            zone = state.zone,
            syntax = syntax,
            onOpenCommit = onOpenCommit,
            openCommitLabel = { date -> resources.getString(R.string.cd_open_commit, date) }
        )
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            EditorTabs(
                fileNames = listOf("stats.md"),
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
                    // A stats file is computed, never edited.
                    StatusBarText("ro")
                }
                StatusBarText(stringResource(R.string.status_days, state.committedDays))
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF10141A, heightDp = 800)
@Composable
private fun StatsScreenPreview() {
    TstepsTheme {
        val today = LocalDate.parse("2026-08-18")
        val days = buildMap {
            var date = today.minusDays(60)
            var value = 0L
            while (!date.isAfter(today)) {
                value = (value + 3_777) % 14_000
                if (date.dayOfWeek.value != 7) put(date, value)
                date = date.plusDays(1)
            }
        }
        StatsScreen(
            state = StatsUiState(
                grid = Heatmap.build(days, today),
                streak = StreakInfo(current = 6, longest = 19),
                averages = listOf(
                    WindowAverages(7, 6, 8_120, 5_900.0, 68),
                    WindowAverages(30, 24, 7_890, 5_700.0, 64)
                ),
                bestDay = LocalDate.parse("2026-07-12") to 14_823L,
                longestWalk = SessionItem(
                    id = 1,
                    startMillis = 1_782_000_000_000L,
                    endMillis = 1_782_005_520_000L,
                    type = "walk",
                    steps = 9_120,
                    distanceMeters = 6_600.0,
                    activeMillis = 92 * 60_000L,
                    avgCadenceSpm = 99
                ),
                bestWeek = Records.BestWeek(2026, 33, 52_340),
                committedDays = 61
            )
        )
    }
}
