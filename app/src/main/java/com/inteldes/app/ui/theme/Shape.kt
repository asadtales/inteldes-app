package com.inteldes.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * --radius-sm/md/lg are all 0 in the design system: everything is square.
 * Material3's Shapes requires CornerBasedShape, so this is a zero-radius
 * RoundedCornerShape rather than the plain (non-corner-based) RectangleShape.
 */
private val Square = RoundedCornerShape(0.dp)

val IdShapes = Shapes(
    extraSmall = Square,
    small = Square,
    medium = Square,
    large = Square,
    extraLarge = Square,
)
