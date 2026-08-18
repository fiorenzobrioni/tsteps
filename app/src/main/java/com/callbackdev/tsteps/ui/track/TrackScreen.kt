package com.callbackdev.tsteps.ui.track

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.callbackdev.tsteps.ui.components.EditorTabs
import com.callbackdev.tsteps.ui.components.StatusBarDivider
import com.callbackdev.tsteps.ui.components.StatusBarStart
import com.callbackdev.tsteps.ui.components.StatusBarText
import com.callbackdev.tsteps.ui.components.TerminalStatusBar
import com.callbackdev.tsteps.ui.theme.TstepsTheme
import com.callbackdev.tsteps.ui.theme.fabGlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * `$ tsteps track` — a running process, not a file, shown the way an editor
 * shows one: as a terminal tab (VS Code labels integrated terminals by their
 * process; the `$` prefix marks the tab as a terminal, not a file). Transcript
 * lines accrue per active minute; pause/resume are the shell's `^Z`/`fg`, stop
 * is `^C` with a two-tap confirm. The controls carry glyph AND word
 * (`[ ^Z pause ]`) at FAB-sized targets — device feedback: the bare glyphs
 * didn't explain themselves — and the armed stop takes the screen's one glow.
 * When the process ends the session commits as a hunk and the screen exits.
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
            // Same chrome as every screen; the label is a terminal tab, not a file.
            EditorTabs(
                fileNames = listOf("$ tsteps track"),
                activeIndex = 0,
                onSelect = {}
            )
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

/**
 * The process controls: glyph AND word (`[ ^Z pause ]`) so the buttons explain
 * themselves — the shell glyphs stay for coherence, the word does the talking
 * (device feedback, recorded in PLANNING). FAB-sized targets; stop is the
 * screen's primary verb and, when armed, takes its one sanctioned glow in
 * diff-deletion red.
 */
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
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ProcessButton(
            text = if (paused) "[ fg resume ]" else "[ ^Z pause ]",
            modifier = Modifier.weight(1f),
            textColor = syntax.number,
            onClickLabel = stringResource(
                if (paused) R.string.cd_resume_track else R.string.cd_pause_track
            ),
            onClick = onPauseResume
        )
        ProcessButton(
            text = "[ ^C stop ]",
            modifier = Modifier.weight(1.3f),
            textColor = if (stopArmed) syntax.diffDel else MaterialTheme.colorScheme.onSurface,
            borderColor = if (stopArmed) syntax.diffDel else syntax.border,
            glowColor = if (stopArmed) syntax.diffDel.copy(alpha = 0.53f) else null,
            onClickLabel = stringResource(
                if (stopArmed) R.string.cd_confirm_stop_track else R.string.cd_stop_track
            ),
            onClick = onStopTap
        )
    }
}

/** A 56dp editor-shaped button: 1px border, 4px radius, monospace label. */
@Composable
private fun ProcessButton(
    text: String,
    modifier: Modifier,
    textColor: androidx.compose.ui.graphics.Color,
    onClickLabel: String,
    onClick: () -> Unit,
    borderColor: androidx.compose.ui.graphics.Color = TstepsTheme.syntax.border,
    glowColor: androidx.compose.ui.graphics.Color? = null
) {
    Box(
        modifier = modifier
            .heightIn(min = 56.dp)
            .then(if (glowColor != null) Modifier.fabGlow(glowColor) else Modifier)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, borderColor, MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor
        )
    }
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
