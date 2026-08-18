package com.callbackdev.tsteps.ui.theme

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

// "No shadow" rule: depth comes from 1px borders and tonal stacking, never from
// elevation. Components must not use Modifier.shadow / elevated Material variants;
// they stack surface-container tones and draw an editorBorder instead. The FAB glow
// below is the single sanctioned exception.

/** Standard 1px structural border (`#30363d` in Obsidian), 4px corner radius. */
@Composable
fun Modifier.editorBorder(shape: Shape = MaterialTheme.shapes.small): Modifier =
    border(1.dp, TstepsTheme.syntax.border, shape)

/** Focused/active variant: same 1px border in primary blue. */
@Composable
fun Modifier.editorFocusBorder(shape: Shape = MaterialTheme.shapes.small): Modifier =
    border(1.dp, MaterialTheme.colorScheme.primaryContainer, shape)

/**
 * Glow for the FAB — the only shadow-like effect in the app, the equivalent of the
 * design system's `box-shadow: 0 0 15px #79c0ff88` around the button's rectangular
 * footprint (same 4px radius as every other element).
 */
@Composable
fun Modifier.fabGlow(color: Color = TstepsTheme.syntax.glow): Modifier = drawBehind {
    val blur = 15.dp.toPx()
    if (blur <= 0f || size.minDimension <= 0f) return@drawBehind
    val corner = 4.dp.toPx()
    val paint = Paint().asFrameworkPaint().apply {
        isAntiAlias = true
        this.color = color.toArgb()
        maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
    }
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, corner, corner, paint)
    }
}
