package com.inteldes.app.ui.theme

import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.RectangleShape

/** --radius-sm/md/lg are all 0 in the design system: everything is square. */
val IdShapes = Shapes(
    extraSmall = RectangleShape,
    small = RectangleShape,
    medium = RectangleShape,
    large = RectangleShape,
    extraLarge = RectangleShape,
)
