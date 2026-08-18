package com.callbackdev.tsteps.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Geometric and sharp: everything uses a strict 4px radius, the FAB included —
// nothing in an editor is circular; the FAB is set apart by its glow alone (GlowFab).
private val EditorCorner = RoundedCornerShape(4.dp)

val TstepsShapes = Shapes(
    extraSmall = EditorCorner,
    small = EditorCorner,
    medium = EditorCorner,
    large = EditorCorner,
    extraLarge = EditorCorner
)
