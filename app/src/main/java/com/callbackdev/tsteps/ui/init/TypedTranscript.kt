package com.callbackdev.tsteps.ui.init

import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import com.callbackdev.tsteps.ui.components.CanvasLine
import com.callbackdev.tsteps.ui.components.CodeCanvas
import com.callbackdev.tsteps.ui.components.CodeLine
import com.callbackdev.tsteps.ui.components.LocalEditorOptions
import com.callbackdev.tsteps.ui.components.WidgetLine

/**
 * A hand at the keyboard: the rate of every line that opens with a prompt — the `$`
 * command and each `>` answer alike. About twenty-two characters a second, which is
 * what a hand does; fifty was the first guess and read as a machine pretending.
 */
internal const val PromptMsPerChar = 45

/** A program writing to a tty: ~165 characters a second. */
internal const val PrintMsPerChar = 6

/** The beat after a prompt line, where a session waits for what comes next. */
internal const val PromptPauseMs = 240

/** The beat after every printed line, so a line lands as a line and not as a stream. */
internal const val LinePauseMs = 100

/** The beat that closes a stanza, so an answer and its note do not run into the next. */
internal const val StanzaPauseMs = 160

/**
 * The block cursor. Deliberately not the `_` of `TerminalInput`: that one marks an
 * empty field waiting for a letter, this one marks the character being written.
 */
internal const val BlockCursor = "█"

/** One line of the transcript, and the time it takes to arrive. */
@Immutable
internal data class TypedLine(
    val line: CodeLine,
    val msPerChar: Int = PrintMsPerChar,
    /** A beat held *after* the line lands, with the cursor parked at its end. */
    val pauseAfterMs: Int = LinePauseMs
)

/**
 * The timeline as a pure value: given a millisecond, which lines are on screen and
 * how much of the last one.
 *
 * Pure on purpose — no clock, no Compose — so the animation can be asserted at any
 * instant by a plain unit test instead of being watched.
 */
@Immutable
internal class Typist(private val script: List<TypedLine>) {

    /** The finished transcript: every line whole, with its taps live again. */
    val lines: List<CanvasLine> = script.map { it.line }

    val totalMs: Long = script.sumOf { step ->
        step.line.text.length.toLong() * step.msPerChar + step.pauseAfterMs
    }

    /**
     * The transcript as it stands [elapsedMs] into the run: whole lines, then the
     * one being written with [cursor] sitting on its next character. Lines that
     * have not started yet are absent rather than blank — the canvas grows
     * downwards, the way a terminal does.
     */
    fun linesAt(elapsedMs: Long, cursor: AnnotatedString): List<CanvasLine> {
        var left = elapsedMs
        val shown = ArrayList<CanvasLine>(script.size)
        for (step in script) {
            val chars = step.line.text.length
            val typing = chars.toLong() * step.msPerChar
            if (left >= typing + step.pauseAfterMs) {
                shown += step.line
                left -= typing + step.pauseAfterMs
                continue
            }
            // Still inside this line: either writing it, or holding the beat after
            // it — which is the same expression, clamped to the line's own length.
            val written = when {
                step.msPerChar <= 0 -> chars
                else -> minOf(chars.toLong(), left / step.msPerChar).toInt()
            }
            shown += CodeLine(step.line.text.subSequence(0, written) + cursor, step.line.indent)
            return shown
        }
        return shown
    }
}

/**
 * The transcript of `$ tsteps init`, printed instead of pasted (Fase 23).
 *
 * The screen was already a terminal session; it was just a still photograph of one.
 * A shell that has plainly finished before you looked at it is the one thing a
 * shell never is, and the block cursor — the single glyph that says the machine is
 * *at* this character, right now — had nowhere to be.
 *
 * **Two speeds, because a transcript has two authors.** Every line that opens with a
 * prompt — the `$` command and each `>` answer — is *typed* ([PromptMsPerChar], a
 * hand at a keyboard) and is followed by a beat; everything else is *printed*
 * ([PrintMsPerChar], a program writing to a tty and not a typewriter). The prompt
 * glyph is the tell in both directions: it is where a hand belongs on screen, and
 * where a session waits.
 *
 * **The rates are the device's, not the desk's**, and they took two rounds. The
 * first pass printed everything at five hundred characters a second and was over in
 * a second and a half: that does not read as writing, it reads as a flicker, and
 * text arriving faster than the eye tracks is exactly the "fuffa" the animation
 * exists to avoid. The second pass halved the rate and still had the shape wrong —
 * one command, then the whole rest of the session in a single breath. The fix was
 * not more slowness but a **rhythm**: the answers are prompts too, so they are typed
 * at the command's own speed and wait after themselves, and the session reads as a
 * conversation with turns instead of an announcement. It runs six to seven seconds
 * now, which is a screen a fresh install sees once and a tap ends whenever.
 *
 * **Nothing here is a fake progress bar.** The series' rule is that the file must
 * not lie, and a spinner counting up to a number the app already holds would be the
 * purest form of that lie: an animation inventing work. What is animated is the
 * *arrival* of text that was going to be there anyway, which is what a terminal
 * does.
 *
 * **This canvas always wraps**, whatever `word_wrap` says — the same override
 * `HELP.md` takes (Fase 22b), for a sharper reason: a cursor walking off the right
 * edge is a cursor nobody can see, and a first-run screen cannot ask the reader to
 * drag sideways to find where the machine got to. Only the wrap moves, not
 * `line_numbers`.
 *
 * **Three ways out**, in the order they matter:
 *  - *A tap ends it.* While the transcript prints, the whole canvas is one
 *    invisible target: the first touch lands on the finished screen and not on a
 *    choice, and nobody is made to sit through a second reading.
 *  - *Reduced motion never starts it.* [prefersReducedMotion] reads the switch
 *    Android's "Remove animations" writes, and the screen opens complete and still
 *    — on the first frame, not one frame later, which is where a flash would come
 *    from.
 *  - *A screen reader skips it too*, for a reason of its own: a transcript that
 *    grows one character at a time is a semantics tree changing sixty times a
 *    second, and TalkBack would read the intro to pieces.
 *
 * The latch is a [rememberSaveable]: `> turn on the step counter` opens a system
 * dialog, and coming back from it — or from a rotation — to watch the intro type
 * itself again would turn a good first second into an obstacle.
 */
@Composable
internal fun TypedTranscript(
    script: List<TypedLine>,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = rememberReducedMotion()
) {
    val typist = remember(script) { Typist(script) }
    val cursorColor = MaterialTheme.colorScheme.primaryContainer
    val cursor = remember(cursorColor) {
        AnnotatedString(BlockCursor, SpanStyle(color = cursorColor))
    }

    var finished by rememberSaveable { mutableStateOf(reduceMotion) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    val totalMs by rememberUpdatedState(typist.totalMs)

    LaunchedEffect(Unit) {
        if (finished) return@LaunchedEffect
        // The frame clock rather than a per-character `delay`: a two-millisecond
        // delay is a promise the scheduler cannot keep, and the drift shows up as
        // exactly the stuttering, too-slow typewriter this is not meant to be.
        var startNanos = 0L
        withFrameNanos { startNanos = it }
        while (!finished && elapsedMs < totalMs) {
            withFrameNanos { now -> elapsedMs = (now - startNanos) / 1_000_000L }
        }
        finished = true
    }

    val visible = if (finished) {
        typist.lines + CodeLine(AnnotatedString("")) +
            WidgetLine(measureText = BlockCursor) { IdleCursor(blink = !reduceMotion) }
    } else {
        typist.linesAt(elapsedMs, cursor)
    }

    val canvasState = rememberLazyListState()
    LaunchedEffect(visible.size) {
        // A terminal keeps its last line in sight. On a screen the transcript fits
        // this is a no-op; on a short one it is the difference between the choices
        // being on screen and being below it.
        if (visible.isNotEmpty()) canvasState.scrollToItem(visible.lastIndex)
    }

    Box(modifier) {
        CodeCanvas(
            lines = visible,
            state = canvasState,
            contentPadding = PaddingValues(vertical = 8.dp),
            // A transcript has no nesting: the `#` note under each choice is
            // indented one level to belong to it, and a guide rail drawn down the
            // middle of a two-line answer would be reading structure into a
            // conversation.
            showIndentGuides = false,
            options = LocalEditorOptions.current.copy(wordWrap = true)
        )
        if (!finished) {
            // Tap-to-skip. An overlay rather than a `clickable` on the canvas: it is
            // hit-tested first, so the touch that ends the animation cannot also
            // answer a question the reader has not finished reading — and it leaves
            // the composition the instant it has done its one job.
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false).consume()
                            finished = true
                        }
                    }
            )
        }
    }
}

/**
 * The cursor once there is nothing left to write: the shell, waiting for you.
 *
 * Blinks at 1 Hz, the rate every terminal emulator settled on. The infinite
 * transition is created only when it is going to move — under reduced motion the
 * block is simply there, which says "waiting" just as well and keeps the frame
 * clock still.
 */
@Composable
private fun IdleCursor(blink: Boolean) {
    val alpha = if (blink) {
        val transition = rememberInfiniteTransition(label = "init-cursor")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 1000
                    1f at 0 using LinearEasing
                    1f at 499
                    0f at 500
                    0f at 999
                }
            ),
            label = "init-cursor-alpha"
        ).value
    } else {
        1f
    }
    Text(
        text = BlockCursor,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.alpha(alpha)
    )
}

/** Read once: neither switch changes while a first-run screen is on screen. */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) { context.prefersReducedMotion() }
}

/**
 * Whether this device has asked for no animation.
 *
 * Android's accessibility "Remove animations" writes `ANIMATOR_DURATION_SCALE` to
 * zero — the same switch a developer flips in Developer options, and the one that
 * actually governs in-app animation. Touch exploration is the second reason and not
 * the same one: TalkBack is not asking for stillness, it is asking not to be handed
 * a text that rewrites itself under the reading finger.
 */
internal fun Context.prefersReducedMotion(): Boolean {
    val scale = Settings.Global.getFloat(
        contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f
    )
    if (scale == 0f) return true
    val accessibility = getSystemService(AccessibilityManager::class.java)
    return accessibility?.isTouchExplorationEnabled == true
}
