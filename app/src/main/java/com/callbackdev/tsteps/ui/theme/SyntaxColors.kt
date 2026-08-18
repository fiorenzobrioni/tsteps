package com.callbackdev.tsteps.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Syntax highlighting tokens shared by every "fake file" screen. Not part of the
 * Material 3 color scheme, so they travel alongside it per theme profile and are
 * read via [TstepsTheme.syntax].
 */
@Immutable
data class SyntaxColors(
    /** JSON keys / structural labels */
    val key: Color,
    /** String values */
    val string: Color,
    /** Numbers and booleans */
    val number: Color,
    /** Comments, braces, punctuation */
    val comment: Color,
    /** Diff additions (+) */
    val diffAdd: Color,
    /** Diff deletions (-) */
    val diffDel: Color,
    /** 1px structural borders */
    val border: Color,
    /** FAB glow — the only allowed shadow-like effect */
    val glow: Color
)

internal val LocalSyntaxColors = staticCompositionLocalOf { ObsidianSyntax }
