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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.callbackdev.tsteps.R
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.ui.components.CodeCanvas
import com.callbackdev.tsteps.ui.components.EditorTabs
import com.callbackdev.tsteps.ui.components.GlowFab
import com.callbackdev.tsteps.ui.components.StatusBarDivider
import com.callbackdev.tsteps.ui.components.StatusBarStart
import com.callbackdev.tsteps.ui.components.StatusBarText
import com.callbackdev.tsteps.ui.components.TerminalStatusBar
import com.callbackdev.tsteps.ui.theme.TstepsTheme
import com.callbackdev.tsteps.work.SyncScheduler
import java.time.LocalDate
import kotlinx.coroutines.delay

/**
 * Main screen: today as the open source file `steps_data.json` — the working
 * tree, ticking live while the user walks. Sensor state, estimates and errors
 * ride the `//` comment channel; the missing permission is fixed from inside the
 * file (`$ tsteps grant activity-recognition`). The FAB is the future `$ tsteps
 * track` verb, disabled until Fase 6: tapping it answers with a comment, the
 * editor's way of saying "not yet".
 */
@Composable
fun StepsScreen(viewModel: StepsViewModel = viewModel(factory = StepsViewModel.Factory)) {
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
    StepsScreen(
        state = state,
        onGrantPermission = {
            permissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    )
}

@Composable
fun StepsScreen(
    state: StepsUiState,
    onGrantPermission: () -> Unit = {}
) {
    val syntax = TstepsTheme.syntax
    val grantLabel = stringResource(R.string.cd_grant_activity_recognition)

    // The disabled FAB's feedback: a transient comment at the top of the file.
    var trackComingSoon by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(trackComingSoon) {
        if (trackComingSoon) {
            delay(4_000L)
            trackComingSoon = false
        }
    }

    val lines = remember(state, syntax, trackComingSoon) {
        StepsDocument.build(
            snapshot = state.snapshot,
            status = state.status,
            units = state.units,
            syntax = syntax,
            trackComingSoon = trackComingSoon,
            onGrantPermission = onGrantPermission,
            grantClickLabel = grantLabel
        )
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            // One-element strip on purpose (tweather pre-v1 decision): the open
            // file keeps its indicator, and README.md lands here in Fase 7
            // without touching this layout.
            EditorTabs(
                fileNames = listOf("steps_data.json"),
                activeIndex = 0,
                onSelect = {}
            )
            Box(Modifier.weight(1f)) {
                CodeCanvas(
                    lines = lines,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = FabClearance)
                )
                // Disabled ▶: no glow, comment-gray glyph — present so the
                // screen's one verb has its place, inert until Fase 6 ships it.
                GlowFab(
                    onClick = { trackComingSoon = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 24.dp),
                    contentDescription = stringResource(R.string.cd_track_coming_soon),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = syntax.comment,
                    glowColor = Color.Transparent
                )
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
