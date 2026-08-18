package com.callbackdev.tsteps.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.callbackdev.tsteps.R

val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold)
)

private val M3Defaults = Typography()

private fun TextStyle.mono() = copy(fontFamily = JetBrainsMono)

// Exact line metrics: no Android font padding and centered line height, so code lines,
// gutter numbers and tree guides land on the 4px grid like in the HTML mockups.
private val ExactLineMetrics = PlatformTextStyle(includeFontPadding = false)
private val CenteredLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None
)

// Scale from obsidian_syntax/DESIGN.md. The design system allows no font other than
// JetBrains Mono, so every Material style is remapped — including the ones without an
// explicit spec, which keep default metrics but switch family.
val TstepsTypography = Typography(
    displayLarge = M3Defaults.displayLarge.mono(),
    displayMedium = M3Defaults.displayMedium.mono(),
    displaySmall = M3Defaults.displaySmall.mono(),
    // headline-lg
    headlineLarge = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.02).em,
        platformStyle = ExactLineMetrics,
        lineHeightStyle = CenteredLineHeight
    ),
    // headline-lg-mobile
    headlineMedium = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        platformStyle = ExactLineMetrics,
        lineHeightStyle = CenteredLineHeight
    ),
    headlineSmall = M3Defaults.headlineSmall.mono(),
    titleLarge = M3Defaults.titleLarge.mono(),
    titleMedium = M3Defaults.titleMedium.mono(),
    titleSmall = M3Defaults.titleSmall.mono(),
    bodyLarge = M3Defaults.bodyLarge.mono(),
    // body-md
    bodyMedium = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        platformStyle = ExactLineMetrics,
        lineHeightStyle = CenteredLineHeight
    ),
    // code-block
    bodySmall = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 22.sp,
        platformStyle = ExactLineMetrics,
        lineHeightStyle = CenteredLineHeight
    ),
    labelLarge = M3Defaults.labelLarge.mono(),
    // status-bar
    labelMedium = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 14.sp,
        platformStyle = ExactLineMetrics,
        lineHeightStyle = CenteredLineHeight
    ),
    // label-sm
    labelSmall = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.05.em,
        platformStyle = ExactLineMetrics,
        lineHeightStyle = CenteredLineHeight
    )
)
