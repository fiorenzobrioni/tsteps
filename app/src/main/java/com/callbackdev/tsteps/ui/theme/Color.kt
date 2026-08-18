package com.callbackdev.tsteps.ui.theme

import androidx.compose.ui.graphics.Color

// "Obsidian Syntax" — Material 3 palette from the obsidian_syntax/DESIGN.md frontmatter.
// Dracula and Monokai get their own objects in Fase 7.
object ObsidianColors {
    val surface = Color(0xFF10141A)
    val surfaceDim = Color(0xFF10141A)
    val surfaceBright = Color(0xFF353940)
    val surfaceContainerLowest = Color(0xFF0A0E14)
    val surfaceContainerLow = Color(0xFF181C22)
    val surfaceContainer = Color(0xFF1C2026)
    val surfaceContainerHigh = Color(0xFF262A31)
    val surfaceContainerHighest = Color(0xFF31353C)
    val onSurface = Color(0xFFDFE2EB)
    val onSurfaceVariant = Color(0xFFC0C7D1)
    val surfaceVariant = Color(0xFF31353C)
    val inverseSurface = Color(0xFFDFE2EB)
    val inverseOnSurface = Color(0xFF2D3137)
    val outline = Color(0xFF8A919B)
    val outlineVariant = Color(0xFF404750)
    val surfaceTint = Color(0xFF96CCFF)

    val primary = Color(0xFFB5D9FF)
    val onPrimary = Color(0xFF003353)
    val primaryContainer = Color(0xFF79C0FF)
    val onPrimaryContainer = Color(0xFF004E7B)
    val inversePrimary = Color(0xFF00639A)

    val secondary = Color(0xFF74DD7E)
    val onSecondary = Color(0xFF003910)
    val secondaryContainer = Color(0xFF007F2D)
    val onSecondaryContainer = Color(0xFFC4FFC2)

    val tertiary = Color(0xFFE6CBFF)
    val onTertiary = Color(0xFF421B6A)
    val tertiaryContainer = Color(0xFFD2A8FF)
    val onTertiaryContainer = Color(0xFF5D3885)

    val error = Color(0xFFFFB4AB)
    val onError = Color(0xFF690005)
    val errorContainer = Color(0xFF93000A)
    val onErrorContainer = Color(0xFFFFDAD6)

    val background = Color(0xFF10141A)
    val onBackground = Color(0xFFDFE2EB)

    // Fixed roles: not part of ColorScheme in material3 1.3.x, kept as reference tokens
    val primaryFixed = Color(0xFFCEE5FF)
    val primaryFixedDim = Color(0xFF96CCFF)
    val onPrimaryFixed = Color(0xFF001D32)
    val onPrimaryFixedVariant = Color(0xFF004A76)
    val secondaryFixed = Color(0xFF90FA97)
    val secondaryFixedDim = Color(0xFF74DD7E)
    val onSecondaryFixed = Color(0xFF002106)
    val onSecondaryFixedVariant = Color(0xFF00531B)
    val tertiaryFixed = Color(0xFFEFDBFF)
    val tertiaryFixedDim = Color(0xFFDBB8FF)
    val onTertiaryFixed = Color(0xFF2B0052)
    val onTertiaryFixedVariant = Color(0xFF593482)
}

// Syntax highlighting tokens for the Obsidian profile (see SyntaxColors)
val ObsidianSyntax = SyntaxColors(
    key = Color(0xFF79C0FF),
    string = Color(0xFFA5D6FF),
    number = Color(0xFFFFA657),
    comment = Color(0xFF8B949E),
    diffAdd = Color(0xFF2EA043),
    diffDel = Color(0xFFF85149),
    border = Color(0xFF30363D),
    glow = Color(0x8879C0FF)
)

// Dracula (draculatheme.com spec): bg #282a36, current line #44475a, fg #f8f8f2,
// comment #6272a4, cyan/green/orange/pink/purple/red/yellow accents. Material roles
// derived: purple = primary, green = secondary, pink = tertiary.
object DraculaColors {
    val background = Color(0xFF282A36)
    val surfaceContainerLowest = Color(0xFF1E1F29)
    val surfaceContainerLow = Color(0xFF2C2E3A)
    val surfaceContainer = Color(0xFF313342)
    val surfaceContainerHigh = Color(0xFF383A4C)
    val surfaceContainerHighest = Color(0xFF44475A)
    val surfaceBright = Color(0xFF44475A)
    val onSurface = Color(0xFFF8F8F2)
    val onSurfaceVariant = Color(0xFFC9CBDA)
    val outline = Color(0xFF6272A4)
    val outlineVariant = Color(0xFF44475A)

    val primary = Color(0xFFBD93F9)          // purple
    val onPrimary = Color(0xFF282A36)
    val primaryContainer = Color(0xFFBD93F9)
    val onPrimaryContainer = Color(0xFF21222C)
    val inversePrimary = Color(0xFF7048BA)

    val secondary = Color(0xFF50FA7B)        // green
    val onSecondary = Color(0xFF0B2912)
    val secondaryContainer = Color(0xFF2D8F4A)
    val onSecondaryContainer = Color(0xFFD9FFE3)

    val tertiary = Color(0xFFFF79C6)         // pink
    val onTertiary = Color(0xFF3D0F2C)
    val tertiaryContainer = Color(0xFFFF79C6)
    val onTertiaryContainer = Color(0xFF3D0F2C)

    val error = Color(0xFFFF5555)
    val onError = Color(0xFF3D0A0A)
    val errorContainer = Color(0xFF8B1A1A)
    val onErrorContainer = Color(0xFFFFD9D4)
}

// VS Code Dracula JSON scopes: keys cyan, strings yellow, numbers purple
val DraculaSyntax = SyntaxColors(
    key = Color(0xFF8BE9FD),
    string = Color(0xFFF1FA8C),
    number = Color(0xFFBD93F9),
    comment = Color(0xFF6272A4),
    diffAdd = Color(0xFF50FA7B),
    diffDel = Color(0xFFFF5555),
    border = Color(0xFF44475A),
    glow = Color(0x88BD93F9)
)

// Monokai (classic): bg #272822, fg #f8f8f2, comment #75715e, pink/green/orange/
// blue/purple/yellow accents. Material roles: blue = primary, green = secondary,
// purple = tertiary.
object MonokaiColors {
    val background = Color(0xFF272822)
    val surfaceContainerLowest = Color(0xFF1E1F1C)
    val surfaceContainerLow = Color(0xFF2D2E27)
    val surfaceContainer = Color(0xFF33342C)
    val surfaceContainerHigh = Color(0xFF3A3B32)
    val surfaceContainerHighest = Color(0xFF49483E)
    val surfaceBright = Color(0xFF49483E)
    val onSurface = Color(0xFFF8F8F2)
    val onSurfaceVariant = Color(0xFFCFCEC5)
    val outline = Color(0xFF75715E)
    val outlineVariant = Color(0xFF49483E)

    val primary = Color(0xFF66D9EF)          // blue
    val onPrimary = Color(0xFF103A42)
    val primaryContainer = Color(0xFF66D9EF)
    val onPrimaryContainer = Color(0xFF103A42)
    val inversePrimary = Color(0xFF2A8FA3)

    val secondary = Color(0xFFA6E22E)        // green
    val onSecondary = Color(0xFF263A05)
    val secondaryContainer = Color(0xFF6F9C14)
    val onSecondaryContainer = Color(0xFFECFFCB)

    val tertiary = Color(0xFFAE81FF)         // purple
    val onTertiary = Color(0xFF2E1A5E)
    val tertiaryContainer = Color(0xFFAE81FF)
    val onTertiaryContainer = Color(0xFF2E1A5E)

    val error = Color(0xFFF92672)
    val onError = Color(0xFF4A0620)
    val errorContainer = Color(0xFF8C1140)
    val onErrorContainer = Color(0xFFFFD9E3)
}

// VS Code Monokai JSON scopes: property names green, strings yellow, numbers purple
val MonokaiSyntax = SyntaxColors(
    key = Color(0xFFA6E22E),
    string = Color(0xFFE6DB74),
    number = Color(0xFFAE81FF),
    comment = Color(0xFF75715E),
    diffAdd = Color(0xFFA6E22E),
    diffDel = Color(0xFFF92672),
    border = Color(0xFF49483E),
    glow = Color(0x8866D9EF)
)
