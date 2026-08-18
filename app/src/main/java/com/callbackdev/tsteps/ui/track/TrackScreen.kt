package com.callbackdev.tsteps.ui.track

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.callbackdev.tsteps.R
import com.callbackdev.tsteps.data.AppSettings
import com.callbackdev.tsteps.data.TrackingState
import com.callbackdev.tsteps.domain.LiveSessionTracker
import com.callbackdev.tsteps.tracking.TrackingService
import com.callbackdev.tsteps.ui.components.CodeCanvas
import com.callbackdev.tsteps.ui.components.StatusBarDivider
import com.callbackdev.tsteps.ui.components.StatusBarStart
import com.callbackdev.tsteps.ui.components.StatusBarText
import com.callbackdev.tsteps.ui.components.TerminalStatusBar
import com.callbackdev.tsteps.ui.theme.TstepsTheme
import com.callbackdev.tsteps.ui.theme.editorBorder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * `$ tsteps track` — a running process, not a file. Transcript lines accrue per
 * active minute; pause/resume are the shell's `^Z`/`fg`, stop is `^C` with a
 * two-tap confirm. When the process ends the session is committed as a hunk and
 * the screen exits back to the editor.
 */
@Composable
fun TrackScreen(
    onExit: () -> Unit,
    viewModel: TrackViewModel = viewModel(factory = TrackViewModel.Factory)
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val nowMillis by viewModel.nowMillis.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Process gone (^C here or anywhere else): leave the buffer. On entry the
    // service may still be spinning up, so a null state gets a short grace
    // before it means "nothing to show".
    var sawActive by remember { mutableStateOf(false) }
    LaunchedEffect(state == null) {
        if (state != null) {
            sawActive = true
        } else {
            if (!sawActive) delay(3_000L)
            onExit()
        }
    }
    TrackScreen(
        state = state,
        settings = settings,
        nowMillis = nowMillis,
        onPause = { TrackingService.pause(context) },
        onResume = { TrackingService.resume(context) },
        onStop = { TrackingService.stop(context) },
        onCycleType = { TrackingService.cycleType(context) }
    )
}

@Composable
fun TrackScreen(
    state: TrackingState?,
    settings: AppSettings,
    nowMillis: Long,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onStop: () -> Unit = {},
    onCycleType: () -> Unit = {}
) {
    val syntax = TstepsTheme.syntax
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val zone = ZoneId.systemDefault()

    var stopArmed by remember { mutableStateOf(false) }
    LaunchedEffect(stopArmed) {
        if (stopArmed) {
            delay(4_000L)
            stopArmed = false
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            if (state != null) {
                val lines = remember(state, nowMillis, settings, syntax, stopArmed) {
                    TrackDocument.build(
                        state = state,
                        nowMillis = nowMillis,
                        units = settings.units,
                        sessionMetric = settings.sessionMetric,
                        locale = locale,
                        zone = zone,
                        syntax = syntax,
                        stopArmed = stopArmed,
                        onCycleType = onCycleType
                    )
                }
                CodeCanvas(
                    lines = lines,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                )
                ProcessControls(
                    paused = state.session.paused,
                    stopArmed = stopArmed,
                    onPauseResume = { if (state.session.paused) onResume() else onPause() },
                    onStopTap = {
                        if (stopArmed) {
                            stopArmed = false
                            onStop()
                        } else {
                            stopArmed = true
                        }
                    }
                )
                TerminalStatusBar {
                    StatusBarStart {
                        StatusBarText("proc: tsteps track")
                        StatusBarDivider()
                        StatusBarText(if (state.session.paused) "^Z stopped" else "running")
                    }
                    StatusBarText(
                        "started: " + Instant.ofEpochMilli(state.session.startMillis)
                            .atZone(zone).format(StartClock)
                    )
                }
            } else {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "// no process running",
                        style = MaterialTheme.typography.bodySmall,
                        color = syntax.comment,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

/** `[ ^Z ]` / `[ fg ]` and `[ ^C ]` — controls rendered as text, editor rule. */
@Composable
private fun ProcessControls(
    paused: Boolean,
    stopArmed: Boolean,
    onPauseResume: () -> Unit,
    onStopTap: () -> Unit
) {
    val syntax = TstepsTheme.syntax
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        BracketButton(
            text = if (paused) "[ fg ]" else "[ ^Z ]",
            color = syntax.number,
            onClickLabel = stringResource(
                if (paused) R.string.cd_resume_track else R.string.cd_pause_track
            ),
            onClick = onPauseResume
        )
        BracketButton(
            text = "[ ^C ]",
            color = if (stopArmed) syntax.diffDel else MaterialTheme.colorScheme.onSurface,
            onClickLabel = stringResource(
                if (stopArmed) R.string.cd_confirm_stop_track else R.string.cd_stop_track
            ),
            onClick = onStopTap
        )
    }
}

@Composable
private fun BracketButton(
    text: String,
    color: Color,
    onClickLabel: String,
    onClick: () -> Unit
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier
            .editorBorder()
            .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

private val StartClock = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

@Preview(showBackground = true, backgroundColor = 0xFF10141A, heightDp = 600)
@Composable
private fun TrackScreenPreview() {
    TstepsTheme {
        val start = 1_000_000L
        var session = LiveSessionTracker.start("walk", start)
        session = LiveSessionTracker.onReading(session, 50_000)
        session = LiveSessionTracker.onReading(session, 52_431)
        TrackScreen(
            state = TrackingState(session = session, strideMeters = 0.72),
            settings = AppSettings(),
            nowMillis = start + 24 * 60_000L + 18_000L
        )
    }
}
