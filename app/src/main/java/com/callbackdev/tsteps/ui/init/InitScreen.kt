package com.callbackdev.tsteps.ui.init

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import com.callbackdev.tsteps.R
import com.callbackdev.tsteps.ui.components.CodeLine
import com.callbackdev.tsteps.ui.components.EditorTabs
import com.callbackdev.tsteps.ui.components.StatusBarDivider
import com.callbackdev.tsteps.ui.components.TerminalStatusBar
import com.callbackdev.tsteps.ui.theme.SyntaxColors
import com.callbackdev.tsteps.ui.theme.TstepsTheme

/**
 * `$ tsteps init` — the first run (Fase 17, tweather's Fase 14c ported). tsteps has
 * a stronger case for it than its sibling: without `ACTIVITY_RECOGNITION` the app
 * cannot count a single step, and until now that permission was asked by a cold
 * system dialog with the reason nowhere in sight. Here the reason is the screen.
 *
 * Deliberately not a carousel — onboarding slides are the most skipped surface in
 * mobile, and a definition offered before you have seen the thing it defines does
 * not stick. This screen does the one job the app cannot start without; the
 * vocabulary lives in `HELP.md`, where it can be re-opened the day the question
 * actually turns up.
 *
 * Two answers, not three: tsteps has one thing to grant. Skipping is an answer too
 * — the document behind already says the counter is off and offers the grant.
 *
 * Since Fase 23 the transcript **prints itself** rather than being already there:
 * see [TypedTranscript] for the two speeds, the tap that ends it and the two
 * accessibility switches that never start it. The four `#` lines above the choices
 * grew with it — a session that takes a second and a half to print can afford to
 * say what the app *is* before saying what it needs, and a still screen could not.
 *
 * Localized, unlike the terminal output elsewhere in the app: the same exception
 * the `README.md` day tab already makes. The fiction is carried by the shape — the
 * prompt, the `>` choices, the `#` notes — not by the language, and this is the one
 * screen whose whole purpose is being understood by someone who does not read `git`
 * for a living. `$ tsteps init` itself is a command, so it stays as it is.
 */
@Composable
fun InitScreen(
    onGrant: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    permissionDenied: Boolean = false
) {
    val syntax = TstepsTheme.syntax
    val script = buildInitScript(
        syntax = syntax,
        intro = stringResource(R.string.init_intro),
        files = stringResource(R.string.init_files),
        privacy = stringResource(R.string.init_privacy),
        ask = stringResource(R.string.init_ask),
        grant = stringResource(R.string.init_option_grant),
        grantNote = stringResource(R.string.init_option_grant_note),
        skip = stringResource(R.string.init_option_skip),
        skipNote = stringResource(R.string.init_option_skip_note),
        denied = if (permissionDenied) stringResource(R.string.init_permission_denied) else null,
        onGrant = onGrant,
        onSkip = onSkip
    )
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // The insets the workspace has applied since it existed, and this screen
        // never did: it is not a Scaffold and has no nav bar, so the tab strip sat
        // under the clock and the terminal bar under the gesture pill (device,
        // Fase 27b). Same `statusBarsPadding()` as the workspace's own root Column.
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            EditorTabs(fileNames = listOf(SetupFile), activeIndex = 0, onSelect = {})
            TypedTranscript(script = script, modifier = Modifier.weight(1f))
            TerminalStatusBar(
                // Bottom-most element of this screen, unlike in the workspace where
                // EditorNavBar is: so it takes the gesture bar's inset the way that
                // bar does — the strip's colour reaches the edge, the text sits above
                // the pill. Painted here because the padding has to be INSIDE the
                // background, and the component applies its own after the modifier.
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .navigationBarsPadding()
            ) {
                Text("⎇ setup")
                StatusBarDivider()
                Text("1/1")
            }
        }
    }
}

/** The "file" this screen opens: a session, not a document — hence the shell name. */
internal const val SetupFile = "tsteps.sh"

/**
 * The transcript as a pure value, with the time each line takes to arrive.
 *
 * The command is the only line typed at a hand's speed; everything else is the
 * program answering. The beats are where a real session breathes — after the
 * command, and between one offered answer and the next.
 */
internal fun buildInitScript(
    syntax: SyntaxColors,
    intro: String,
    files: String,
    privacy: String,
    ask: String,
    grant: String,
    grantNote: String,
    skip: String,
    skipNote: String,
    denied: String?,
    onGrant: () -> Unit,
    onSkip: () -> Unit
): List<TypedLine> = buildList {
    add(
        TypedLine(
            CodeLine(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = syntax.comment)) { append("$ ") }
                    withStyle(SpanStyle(color = syntax.string)) { append("tsteps init") }
                }
            ),
            msPerChar = PromptMsPerChar,
            pauseAfterMs = PromptPauseMs
        )
    )
    add(blank())
    add(printed(comment(intro, syntax)))
    add(printed(comment(files, syntax)))
    add(printed(comment(privacy, syntax)))
    add(printed(comment(ask, syntax), pauseAfterMs = StanzaPauseMs))
    denied?.let {
        add(blank())
        add(
            printed(
                CodeLine(AnnotatedString(it, SpanStyle(color = syntax.diffDel))),
                pauseAfterMs = StanzaPauseMs
            )
        )
    }
    option(grant, grantNote, syntax, onGrant)
    option(skip, skipNote, syntax, onSkip)
}

/** `> choice` plus its `#` note: one tap target, the note says what it costs. */
private fun MutableList<TypedLine>.option(
    label: String,
    note: String,
    syntax: SyntaxColors,
    onClick: () -> Unit
) {
    add(blank())
    add(
        printed(
            CodeLine(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = syntax.comment)) { append("> ") }
                    withStyle(SpanStyle(color = syntax.key)) { append(label) }
                },
                onClick = onClick,
                onClickLabel = label
            )
        )
    )
    add(printed(comment(note, syntax, indent = 1), pauseAfterMs = StanzaPauseMs))
}

private fun printed(line: CodeLine, pauseAfterMs: Int = LinePauseMs): TypedLine =
    TypedLine(line, msPerChar = PrintMsPerChar, pauseAfterMs = pauseAfterMs)

private fun comment(text: String, syntax: SyntaxColors, indent: Int = 0): CodeLine =
    CodeLine(AnnotatedString("# $text", SpanStyle(color = syntax.comment)), indent)

private fun blank(): TypedLine = TypedLine(CodeLine(AnnotatedString("")), pauseAfterMs = 0)

@Preview(showBackground = true, backgroundColor = 0xFF10141A)
@Composable
private fun InitScreenPreview() {
    TstepsTheme {
        InitScreen(onGrant = {}, onSkip = {})
    }
}
