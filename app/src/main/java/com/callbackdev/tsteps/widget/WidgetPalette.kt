package com.callbackdev.tsteps.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.callbackdev.tsteps.ui.theme.DraculaColors
import com.callbackdev.tsteps.ui.theme.DraculaSyntax
import com.callbackdev.tsteps.ui.theme.MonokaiColors
import com.callbackdev.tsteps.ui.theme.MonokaiSyntax
import com.callbackdev.tsteps.ui.theme.ObsidianColors
import com.callbackdev.tsteps.ui.theme.ObsidianSyntax
import com.callbackdev.tsteps.ui.theme.SyntaxColors
import com.callbackdev.tsteps.ui.theme.ThemeProfile

/**
 * The theme tokens a widget render needs, as ARGB ints (RemoteViews knows no
 * Compose Color). Prompt green = the profile's secondary, per DESIGN.md's
 * "Secondary (Strings): emerald green represents active states".
 */
data class WidgetPalette(
    val background: Int,
    val border: Int,
    val title: Int,
    val prompt: Int,
    val plain: Int,
    val key: Int,
    val string: Int,
    val number: Int,
    val comment: Int,
    val alert: Int
) {
    val divider: Int get() = border

    fun colorFor(role: TokenRole): Int = when (role) {
        TokenRole.PROMPT -> prompt
        TokenRole.PLAIN -> plain
        TokenRole.DIM -> comment
        TokenRole.KEY -> key
        TokenRole.STRING -> string
        TokenRole.NUMBER -> number
        TokenRole.COMMENT -> comment
        TokenRole.ALERT -> alert
    }
}

fun widgetPalette(profileName: String): WidgetPalette = when (ThemeProfile.fromName(profileName)) {
    ThemeProfile.Obsidian ->
        palette(ObsidianColors.background, ObsidianColors.secondary, ObsidianColors.onSurface, ObsidianSyntax)
    ThemeProfile.Dracula ->
        palette(DraculaColors.background, DraculaColors.secondary, DraculaColors.onSurface, DraculaSyntax)
    ThemeProfile.Monokai ->
        palette(MonokaiColors.background, MonokaiColors.secondary, MonokaiColors.onSurface, MonokaiSyntax)
}

private fun palette(
    background: Color,
    secondary: Color,
    onSurface: Color,
    syntax: SyntaxColors
) = WidgetPalette(
    background = background.toArgb(),
    border = syntax.border.toArgb(),
    title = onSurface.toArgb(),
    prompt = secondary.toArgb(),
    plain = onSurface.toArgb(),
    key = syntax.key.toArgb(),
    string = syntax.string.toArgb(),
    number = syntax.number.toArgb(),
    comment = syntax.comment.toArgb(),
    // the diff-deletion red already means "this is wrong" everywhere else in the app
    alert = syntax.diffDel.toArgb()
)
