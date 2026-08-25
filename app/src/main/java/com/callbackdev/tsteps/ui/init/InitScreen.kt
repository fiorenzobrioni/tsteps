package com.callbackdev.tsteps.ui.init

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import com.callbackdev.tsteps.R
import com.callbackdev.tsteps.ui.components.CanvasLine
import com.callbackdev.tsteps.ui.components.CodeCanvas
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
    val lines = buildInitLines(
        syntax = syntax,
        intro = stringResource(R.string.init_intro),
        privacy = stringResource(R.string.init_privacy),
        grant = stringResource(R.string.init_option_grant),
        grantNote = stringResource(R.string.init_option_grant_note),
        skip = stringResource(R.string.init_option_skip),
        skipNote = stringResource(R.string.init_option_skip_note),
        denied = if (permissionDenied) stringResource(R.string.init_permission_denied) else null,
        onGrant = onGrant,
        onSkip = onSkip
    )
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            EditorTabs(fileNames = listOf(SetupFile), activeIndex = 0, onSelect = {})
            CodeCanvas(
                lines = lines,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
                showIndentGuides = false
            )
            TerminalStatusBar {
                Text("⎇ setup")
                StatusBarDivider()
                Text("1/1")
            }
        }
    }
}

/** The "file" this screen opens: a session, not a document — hence the shell name. */
internal const val SetupFile = "tsteps.sh"

internal fun buildInitLines(
    syntax: SyntaxColors,
    intro: String,
    privacy: String,
    grant: String,
    grantNote: String,
    skip: String,
    skipNote: String,
    denied: String?,
    onGrant: () -> Unit,
    onSkip: () -> Unit
): List<CanvasLine> = buildList {
    add(
        CodeLine(
            buildAnnotatedString {
                withStyle(SpanStyle(color = syntax.comment)) { append("$ ") }
                withStyle(SpanStyle(color = syntax.string)) { append("tsteps init") }
            }
        )
    )
    add(blank())
    add(comment(intro, syntax))
    add(comment(privacy, syntax))
    denied?.let {
        add(blank())
        add(CodeLine(AnnotatedString(it, SpanStyle(color = syntax.diffDel))))
    }
    option(grant, grantNote, syntax, onGrant)
    option(skip, skipNote, syntax, onSkip)
}

/** `> choice` plus its `#` note: one tap target, the note says what it costs. */
private fun MutableList<CanvasLine>.option(
    label: String,
    note: String,
    syntax: SyntaxColors,
    onClick: () -> Unit
) {
    add(blank())
    add(
        CodeLine(
            buildAnnotatedString {
                withStyle(SpanStyle(color = syntax.comment)) { append("> ") }
                withStyle(SpanStyle(color = syntax.key)) { append(label) }
            },
            onClick = onClick,
            onClickLabel = label
        )
    )
    add(comment(note, syntax, indent = 1))
}

private fun comment(text: String, syntax: SyntaxColors, indent: Int = 0): CodeLine =
    CodeLine(AnnotatedString("# $text", SpanStyle(color = syntax.comment)), indent)

private fun blank(): CodeLine = CodeLine(AnnotatedString(""))

@Preview(showBackground = true, backgroundColor = 0xFF10141A)
@Composable
private fun InitScreenPreview() {
    TstepsTheme {
        InitScreen(onGrant = {}, onSkip = {})
    }
}
