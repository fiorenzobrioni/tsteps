package com.callbackdev.tsteps.ui.steps

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.callbackdev.tsteps.R
import com.callbackdev.tsteps.data.MainEditorFile
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.tracking.TrackingService
import com.callbackdev.tsteps.ui.components.CodeCanvas
import com.callbackdev.tsteps.ui.components.buildMarkdownLines
import com.callbackdev.tsteps.ui.components.EditorTabs
import com.callbackdev.tsteps.ui.components.GlowFab
import com.callbackdev.tsteps.ui.components.StatusBarDivider
import com.callbackdev.tsteps.ui.components.StatusBarStart
import com.callbackdev.tsteps.ui.components.StatusBarText
import com.callbackdev.tsteps.ui.components.TerminalStatusBar
import com.callbackdev.tsteps.ui.theme.TstepsTheme
import com.callbackdev.tsteps.work.SyncScheduler
import java.time.LocalDate
import java.util.Locale

/**
 * Main screen: today as the open source file `steps_data.json` — the working
 * tree, ticking live while the user walks. Sensor state, estimates and errors
 * ride the `//` comment channel; the missing permission is fixed from inside the
 * file (`$ tsteps grant activity-recognition`). The FAB is the one glowing verb:
 * `$ tsteps track` — it starts (or reopens) the live session. Completed walks
 * are the `sessions` array; tapping one expands its detail object in place.
 */
@Composable
fun StepsScreen(
    onOpenTrack: () -> Unit = {},
    viewModel: StepsViewModel = viewModel(factory = StepsViewModel.Factory)
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refreshPermission()
        // Grant flips the "should the background jobs exist" answer.
        SyncScheduler.reconcile(context)
    }
    // Re-check on every resume: the permission can change in system settings
    // while we're paused, in both directions.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshPermission()
        SyncScheduler.reconcile(context)
        onPauseOrDispose { }
    }
    val activeFile by viewModel.activeFile.collectAsStateWithLifecycle()
    StepsScreen(
        state = state,
        activeFile = activeFile,
        onSelectFile = viewModel::selectFile,
        onGrantPermission = {
            permissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        },
        onToggleSession = viewModel::toggleSession,
        onStartTrack = {
            // Idempotent when a session already runs: the manager's start no-ops
            // and the screen simply reopens the process.
            TrackingService.start(context, "walk")
            onOpenTrack()
        }
    )
}

@Composable
fun StepsScreen(
    state: StepsUiState,
    activeFile: MainEditorFile = MainEditorFile.JSON,
    onSelectFile: (MainEditorFile) -> Unit = {},
    onGrantPermission: () -> Unit = {},
    onToggleSession: (Long) -> Unit = {},
    onStartTrack: () -> Unit = {}
) {
    val syntax = TstepsTheme.syntax
    val grantLabel = stringResource(R.string.cd_grant_activity_recognition)
    val resources = LocalContext.current.resources
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

    val lines = remember(state, syntax, activeFile, locale) {
        when (activeFile) {
            MainEditorFile.JSON -> StepsDocument.build(
                snapshot = state.snapshot,
                status = state.status,
                units = state.units,
                syntax = syntax,
                sessions = state.sessions,
                expandedSessionIds = state.expandedSessions,
                sessionMetric = state.sessionMetric,
                zone = state.zone,
                onGrantPermission = onGrantPermission,
                grantClickLabel = grantLabel,
                onToggleSession = onToggleSession,
                sessionToggleLabel = { start ->
                    resources.getString(R.string.cd_toggle_session, start)
                }
            )
            MainEditorFile.README -> buildMarkdownLines(
                StepsReadme.build(
                    snapshot = state.snapshot,
                    status = state.status,
                    sessions = state.sessions,
                    history = state.history,
                    units = state.units,
                    zone = state.zone,
                    locale = locale,
                    resources = resources
                ),
                syntax
            )
        }
    }
    // One scroll position per file (tweather): switching tab must not land
    // mid-document because the OTHER file was scrolled there.
    val jsonScroll = rememberLazyListState()
    val readmeScroll = rememberLazyListState()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            // One-element strip on purpose (tweather pre-v1 decision): the open
            // file keeps its indicator, and README.md lands here in Fase 7
            // without touching this layout.
            EditorTabs(
                fileNames = listOf("steps_data.json", "README.md"),
                activeIndex = if (activeFile == MainEditorFile.JSON) 0 else 1,
                onSelect = {
                    onSelectFile(if (it == 0) MainEditorFile.JSON else MainEditorFile.README)
                }
            )
            Box(Modifier.weight(1f)) {
                CodeCanvas(
                    lines = lines,
                    state = if (activeFile == MainEditorFile.JSON) jsonScroll else readmeScroll,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = FabClearance)
                )
                // The one glowing verb, only where it can act: no sensor or no
                // permission means no FAB — the error document explains instead.
                if (state.status == SensorStatus.OK) {
                    GlowFab(
                        onClick = onStartTrack,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 24.dp),
                        contentDescription = stringResource(R.string.cd_start_track)
                    )
                }
            }
            StepsStatusBar(state)
        }
    }
}

@Composable
private fun StepsStatusBar(state: StepsUiState) {
    TerminalStatusBar {
        StatusBarStart {
            StatusBarText("⎇ main")
            StatusBarDivider()
            StatusBarText(state.snapshot?.date?.toString() ?: "—", shrink = true)
            StatusBarDivider()
            when (state.status) {
                SensorStatus.OK -> StatusBarText("sensor: OK")
                SensorStatus.NO_PERMISSION -> StatusBarText(
                    "sensor: off",
                    color = MaterialTheme.colorScheme.error
                )
                SensorStatus.NO_SENSOR -> StatusBarText(
                    "sensor: ERR",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        StatusBarText(
            stringResource(
                R.string.status_last_commit,
                state.lastCommitDate?.toString() ?: "—"
            )
        )
    }
}

/**
 * Bottom room the canvas leaves for the floating FAB: 56dp of button + its 24dp
 * margin + a line of slack, so the closing `}` can always scroll clear of it
 * (tweather's FabClearance, same reasoning).
 */
private val FabClearance = 96.dp

private fun previewSnapshot() = TodaySnapshot(
    date = LocalDate.parse("2026-08-18"),
    steps = 8_432,
    goalSteps = 10_000,
    distanceMeters = 6_123.0,
    activeMinutes = 74,
    activeKcal = 327.0,
    hourlySteps = listOf<Long>(
        0, 0, 0, 0, 0, 0, 120, 340, 2_100, 2_600, 480, 220, 610, 900, 150, 90, 300, 280, 190, 52, 0, 0, 0, 0
    ),
    streakDays = 6
)

@Preview(showBackground = true, backgroundColor = 0xFF10141A, heightDp = 700)
@Composable
private fun StepsScreenPreview() {
    TstepsTheme {
        StepsScreen(
            state = StepsUiState(
                snapshot = previewSnapshot(),
                status = SensorStatus.OK,
                lastCommitDate = LocalDate.parse("2026-08-17")
            )
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF10141A, heightDp = 500)
@Composable
private fun StepsScreenNoPermissionPreview() {
    TstepsTheme {
        StepsScreen(
            state = StepsUiState(
                snapshot = previewSnapshot(),
                status = SensorStatus.NO_PERMISSION
            )
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF10141A, heightDp = 500)
@Composable
private fun StepsScreenNoGoalImperialPreview() {
    TstepsTheme {
        StepsScreen(
            state = StepsUiState(
                snapshot = previewSnapshot().copy(goalSteps = 0, activeKcal = null),
                status = SensorStatus.OK,
                units = UnitsSystem.IMPERIAL
            )
        )
    }
}
