package com.callbackdev.tsteps.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable

/** Selectable theme profiles (Settings, `"available_profiles"`). */
enum class ThemeProfile {
    Obsidian,
    Dracula,
    Monokai;

    companion object {
        /** Safe mapping from the persisted string (see AppSettings). */
        fun fromName(name: String): ThemeProfile =
            entries.firstOrNull { it.name == name } ?: Obsidian
    }
}

@Immutable
private data class ThemeSpec(
    val colorScheme: ColorScheme,
    val syntax: SyntaxColors
)

private val ObsidianSpec = ThemeSpec(
    colorScheme = darkColorScheme(
        primary = ObsidianColors.primary,
        onPrimary = ObsidianColors.onPrimary,
        primaryContainer = ObsidianColors.primaryContainer,
        onPrimaryContainer = ObsidianColors.onPrimaryContainer,
        inversePrimary = ObsidianColors.inversePrimary,
        secondary = ObsidianColors.secondary,
        onSecondary = ObsidianColors.onSecondary,
        secondaryContainer = ObsidianColors.secondaryContainer,
        onSecondaryContainer = ObsidianColors.onSecondaryContainer,
        tertiary = ObsidianColors.tertiary,
        onTertiary = ObsidianColors.onTertiary,
        tertiaryContainer = ObsidianColors.tertiaryContainer,
        onTertiaryContainer = ObsidianColors.onTertiaryContainer,
        error = ObsidianColors.error,
        onError = ObsidianColors.onError,
        errorContainer = ObsidianColors.errorContainer,
        onErrorContainer = ObsidianColors.onErrorContainer,
        background = ObsidianColors.background,
        onBackground = ObsidianColors.onBackground,
        surface = ObsidianColors.surface,
        onSurface = ObsidianColors.onSurface,
        surfaceVariant = ObsidianColors.surfaceVariant,
        onSurfaceVariant = ObsidianColors.onSurfaceVariant,
        surfaceTint = ObsidianColors.surfaceTint,
        inverseSurface = ObsidianColors.inverseSurface,
        inverseOnSurface = ObsidianColors.inverseOnSurface,
        outline = ObsidianColors.outline,
        outlineVariant = ObsidianColors.outlineVariant,
        surfaceBright = ObsidianColors.surfaceBright,
        surfaceDim = ObsidianColors.surfaceDim,
        surfaceContainer = ObsidianColors.surfaceContainer,
        surfaceContainerHigh = ObsidianColors.surfaceContainerHigh,
        surfaceContainerHighest = ObsidianColors.surfaceContainerHighest,
        surfaceContainerLow = ObsidianColors.surfaceContainerLow,
        surfaceContainerLowest = ObsidianColors.surfaceContainerLowest
    ),
    syntax = ObsidianSyntax
)

private val DraculaSpec = ThemeSpec(
    colorScheme = darkColorScheme(
        primary = DraculaColors.primary,
        onPrimary = DraculaColors.onPrimary,
        primaryContainer = DraculaColors.primaryContainer,
        onPrimaryContainer = DraculaColors.onPrimaryContainer,
        inversePrimary = DraculaColors.inversePrimary,
        secondary = DraculaColors.secondary,
        onSecondary = DraculaColors.onSecondary,
        secondaryContainer = DraculaColors.secondaryContainer,
        onSecondaryContainer = DraculaColors.onSecondaryContainer,
        tertiary = DraculaColors.tertiary,
        onTertiary = DraculaColors.onTertiary,
        tertiaryContainer = DraculaColors.tertiaryContainer,
        onTertiaryContainer = DraculaColors.onTertiaryContainer,
        error = DraculaColors.error,
        onError = DraculaColors.onError,
        errorContainer = DraculaColors.errorContainer,
        onErrorContainer = DraculaColors.onErrorContainer,
        background = DraculaColors.background,
        onBackground = DraculaColors.onSurface,
        surface = DraculaColors.background,
        onSurface = DraculaColors.onSurface,
        surfaceVariant = DraculaColors.surfaceContainerHighest,
        onSurfaceVariant = DraculaColors.onSurfaceVariant,
        surfaceTint = DraculaColors.primary,
        inverseSurface = DraculaColors.onSurface,
        inverseOnSurface = DraculaColors.surfaceContainerLow,
        outline = DraculaColors.outline,
        outlineVariant = DraculaColors.outlineVariant,
        surfaceBright = DraculaColors.surfaceBright,
        surfaceDim = DraculaColors.background,
        surfaceContainer = DraculaColors.surfaceContainer,
        surfaceContainerHigh = DraculaColors.surfaceContainerHigh,
        surfaceContainerHighest = DraculaColors.surfaceContainerHighest,
        surfaceContainerLow = DraculaColors.surfaceContainerLow,
        surfaceContainerLowest = DraculaColors.surfaceContainerLowest
    ),
    syntax = DraculaSyntax
)

private val MonokaiSpec = ThemeSpec(
    colorScheme = darkColorScheme(
        primary = MonokaiColors.primary,
        onPrimary = MonokaiColors.onPrimary,
        primaryContainer = MonokaiColors.primaryContainer,
        onPrimaryContainer = MonokaiColors.onPrimaryContainer,
        inversePrimary = MonokaiColors.inversePrimary,
        secondary = MonokaiColors.secondary,
        onSecondary = MonokaiColors.onSecondary,
        secondaryContainer = MonokaiColors.secondaryContainer,
        onSecondaryContainer = MonokaiColors.onSecondaryContainer,
        tertiary = MonokaiColors.tertiary,
        onTertiary = MonokaiColors.onTertiary,
        tertiaryContainer = MonokaiColors.tertiaryContainer,
        onTertiaryContainer = MonokaiColors.onTertiaryContainer,
        error = MonokaiColors.error,
        onError = MonokaiColors.onError,
        errorContainer = MonokaiColors.errorContainer,
        onErrorContainer = MonokaiColors.onErrorContainer,
        background = MonokaiColors.background,
        onBackground = MonokaiColors.onSurface,
        surface = MonokaiColors.background,
        onSurface = MonokaiColors.onSurface,
        surfaceVariant = MonokaiColors.surfaceContainerHighest,
        onSurfaceVariant = MonokaiColors.onSurfaceVariant,
        surfaceTint = MonokaiColors.primary,
        inverseSurface = MonokaiColors.onSurface,
        inverseOnSurface = MonokaiColors.surfaceContainerLow,
        outline = MonokaiColors.outline,
        outlineVariant = MonokaiColors.outlineVariant,
        surfaceBright = MonokaiColors.surfaceBright,
        surfaceDim = MonokaiColors.background,
        surfaceContainer = MonokaiColors.surfaceContainer,
        surfaceContainerHigh = MonokaiColors.surfaceContainerHigh,
        surfaceContainerHighest = MonokaiColors.surfaceContainerHighest,
        surfaceContainerLow = MonokaiColors.surfaceContainerLow,
        surfaceContainerLowest = MonokaiColors.surfaceContainerLowest
    ),
    syntax = MonokaiSyntax
)

private val ThemeProfile.spec: ThemeSpec
    get() = when (this) {
        ThemeProfile.Obsidian -> ObsidianSpec
        ThemeProfile.Dracula -> DraculaSpec
        ThemeProfile.Monokai -> MonokaiSpec
    }

/**
 * App theme. Always dark: the design system has no light variant, so the profile —
 * not the system dark-mode setting — is the only axis of variation.
 */
@Composable
fun TstepsTheme(
    profile: ThemeProfile = ThemeProfile.Obsidian,
    content: @Composable () -> Unit
) {
    val spec = profile.spec
    CompositionLocalProvider(LocalSyntaxColors provides spec.syntax) {
        MaterialTheme(
            colorScheme = spec.colorScheme,
            typography = TstepsTypography,
            shapes = TstepsShapes,
            content = content
        )
    }
}

/** Accessor for theme values outside the Material color scheme, à la [MaterialTheme]. */
object TstepsTheme {
    val syntax: SyntaxColors
        @Composable
        @ReadOnlyComposable
        get() = LocalSyntaxColors.current
}
