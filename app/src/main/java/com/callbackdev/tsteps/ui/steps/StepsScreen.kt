package com.callbackdev.tsteps.ui.steps

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
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
import com.callbackdev.tsteps.ui.components.CodeLine
import com.callbackdev.tsteps.ui.components.buildMarkdownLines
import com.callbackdev.tsteps.ui.components.EditorTabs
import com.callbackdev.tsteps.ui.components.GlowFab
import com.callbackdev.tsteps.ui.components.StatusBarDivider
import com.callbackdev.tsteps.ui.components.StatusBarStart
import com.callbackdev.tsteps.ui.components.StatusBarText
import com.callbackdev.tsteps.ui.components.TerminalStatusBar
import com.callbackdev.tsteps.domain.SessionItem
import com.callbackdev.tsteps.ui.format.UnitFormat
import com.callbackdev.tsteps.ui.theme.TstepsTheme
import com.callbackdev.tsteps.work.SyncScheduler
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.delay

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
    onOpenHelp: () -> Unit = {},
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
    val tracking by viewModel.tracking.collectAsStateWithLifecycle()
    val showHelpHint by viewModel.showHelpHint.collectAsStateWithLifecycle()
    StepsScreen(
        state = state,
        activeFile = activeFile,
        trackingActive = tracking != null,
        trackingPaused = tracking?.session?.paused == true,
        onSelectFile = viewModel::selectFile,
        onGrantPermission = {
            permissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        },
        onToggleSession = viewModel::toggleSession,
        onAcceptGoal = viewModel::acceptSuggestedGoal,
        showHelpHint = showHelpHint,
        onOpenHelp = {
            viewModel.dismissHelpHint()
            onOpenHelp()
        },
        onStartTrack = {
            // Idempotent when a session already runs: the manager's start no-ops
            // and the screen simply reopens the process.
            TrackingService.start(context, "walk")
            onOpenTrack()
        },
        onRemoveSession = viewModel::removeSession,
        onResizeSession = viewModel::resizeSession
    )
}

@Composable
fun StepsScreen(
    state: StepsUiState,
    activeFile: MainEditorFile = MainEditorFile.JSON,
    trackingActive: Boolean = false,
    trackingPaused: Boolean = false,
    onSelectFile: (MainEditorFile) -> Unit = {},
    onGrantPermission: () -> Unit = {},
    onToggleSession: (Long) -> Unit = {},
    onAcceptGoal: () -> Unit = {},
    onStartTrack: () -> Unit = {},
    onRemoveSession: (Long) -> Unit = {},
    onResizeSession: (id: Long, startMillis: Long, endMillis: Long) -> Unit = { _, _, _ -> },
    /** Fase 17: the one-shot pointer to `HELP.md`, first line of the document. */
    showHelpHint: Boolean = false,
    onOpenHelp: () -> Unit = {}
) {
    val syntax = TstepsTheme.syntax
    val grantLabel = stringResource(R.string.cd_grant_activity_recognition)
    val acceptGoalLabel = stringResource(R.string.cd_accept_suggested_goal)
    val resources = LocalContext.current.resources
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

    // Fase 11 session verbs, working tree only. `[rm]` arms for a few seconds
    // (two-tap, like every destructive command of the series); the boundary
    // editor swaps start/end for one range prompt, errors fade on their own.
    var armedRemoveId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(armedRemoveId) {
        if (armedRemoveId != null) {
            delay(4_000)
            armedRemoveId = null
        }
    }
    var editingSessionId by remember { mutableStateOf<Long?>(null) }
    var editValue by remember { mutableStateOf("") }
    var editError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(editError) {
        if (editError != null) {
            delay(4_000)
            editError = null
        }
    }
    val controls = SessionControls(
        armedRemoveId = armedRemoveId,
        editingId = editingSessionId,
        editValue = editValue,
        editError = editError,
        onEditValue = { editValue = it },
        onStartEdit = { session ->
            editingSessionId = session.id
            editError = null
            editValue = UnitFormat.clockTime(session.startMillis, state.zone) + ".." +
                UnitFormat.clockTime(session.endMillis, state.zone)
        },
        onSubmitEdit = submit@{
            val id = editingSessionId ?: return@submit
            val date = state.snapshot?.date ?: return@submit
            when (
                val parsed = SessionBoundsInput.parse(
                    editValue, date, state.zone, System.currentTimeMillis()
                )
            ) {
                is SessionBounds.Value -> {
                    onResizeSession(id, parsed.startMillis, parsed.endMillis)
                    editingSessionId = null
                    editError = null
                }
                is SessionBounds.Invalid ->
                    editError = "// ERROR: " +
                        resources.getString(parsed.id, *parsed.args.toTypedArray())
            }
        },
        onCancelEdit = { editingSessionId = null },
        onRemove = { session ->
            if (armedRemoveId == session.id) {
                armedRemoveId = null
                onRemoveSession(session.id)
            } else {
                armedRemoveId = session.id
            }
        },
        removeLabel = { session ->
            resources.getString(
                if (armedRemoveId == session.id) {
                    R.string.cd_confirm_remove_session
                } else {
                    R.string.cd_remove_session
                },
                sessionStart(session, state)
            )
        },
        editLabel = { session ->
            resources.getString(R.string.cd_edit_session, sessionStart(session, state))
        },
        cancelLabel = stringResource(R.string.cd_cancel_edit)
    )

    val hint = if (showHelpHint) stringResource(R.string.help_hint) else null
    val lines = remember(
        state, syntax, activeFile, locale, armedRemoveId, editingSessionId, editValue,
        editError, hint
    ) {
        val head = hint?.let {
            listOf(
                CodeLine(
                    AnnotatedString("// $it", SpanStyle(color = syntax.key)),
                    onClick = onOpenHelp,
                    onClickLabel = it
                )
            )
        } ?: emptyList()
        head + when (activeFile) {
            MainEditorFile.JSON -> StepsDocument.build(
                resources = resources,
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
                onAcceptGoal = onAcceptGoal,
                acceptGoalLabel = acceptGoalLabel,
                onToggleSession = onToggleSession,
                sessionToggleLabel = { start ->
                    resources.getString(R.string.cd_toggle_session, start)
                },
                controls = controls,
                externalSteps = state.externalSteps
            )
            MainEditorFile.README -> buildMarkdownLines(
                StepsReadme.build(
                    snapshot = state.snapshot,
                    status = state.status,
                    sessions = state.sessions,
                    history = state.history,
                    records = state.records,
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
                // With a session running the FAB wears its state (device
                // feedback): active-state green with a slow breathing glow —
                // green means active in this design system, red means error —
                // steady while ^Z-paused. Tapping it reopens the process.
                if (state.status == SensorStatus.OK) {
                    val glowAlpha = if (trackingActive && !trackingPaused) {
                        rememberInfiniteTransition(label = "track-pulse").animateFloat(
                            initialValue = 0.15f,
                            targetValue = 0.55f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1_100, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "track-pulse-alpha"
                        ).value
                    } else {
                        0.53f
                    }
                    if (trackingActive) {
                        GlowFab(
                            onClick = onStartTrack,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 16.dp, bottom = 24.dp),
                            contentDescription = stringResource(R.string.cd_open_track),
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            glowColor = MaterialTheme.colorScheme.secondary.copy(alpha = glowAlpha)
                        )
                    } else {
                        GlowFab(
                            onClick = onStartTrack,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 16.dp, bottom = 24.dp),
                            contentDescription = stringResource(R.string.cd_start_track)
                        )
                    }
                }
            }
            StepsStatusBar(state, trackingActive)
        }
    }
}

@Composable
private fun StepsStatusBar(state: StepsUiState, trackingActive: Boolean = false) {
    TerminalStatusBar {
        StatusBarStart {
            // No date chip: "date" is the first key of the buffer right above, and
            // repeating it starved the sensor chip on narrow devices.
            StatusBarText("⎇ main")
            StatusBarDivider()
            when {
                // The running process outranks the sensor chip — with a session
                // live the sensor is self-evidently OK.
                trackingActive -> StatusBarText(
                    "▶ tracking",
                    color = MaterialTheme.colorScheme.secondary
                )
                else -> Unit
            }
            if (!trackingActive) when (state.status) {
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

/** The plain (tilde-free) start time — what accessibility labels speak. */
private fun sessionStart(session: SessionItem, state: StepsUiState): String =
    UnitFormat.clockTime(session.startMillis, state.zone)

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
