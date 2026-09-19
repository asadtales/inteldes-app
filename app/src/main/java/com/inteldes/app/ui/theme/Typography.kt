package com.inteldes.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Font sizes/weights/letter-spacing mirror the prototype's inline `font:` shorthand
 * values ( e.g. `font:800 46px/1 var(--font-heading);letter-spacing:-.03em` ).
 * Individual screens override size/weight per element rather than relying solely on
 * these roles, since the prototype is not built from a small fixed type scale.
 */
val IdTypography = Typography(
    displayLarge = TextStyle(fontFamily = IdFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = 46.sp, letterSpacing = (-0.03).sp),
    headlineLarge = TextStyle(fontFamily = IdFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, letterSpacing = (-0.03).sp),
    headlineMedium = TextStyle(fontFamily = IdFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, letterSpacing = (-0.025).sp),
    headlineSmall = TextStyle(fontFamily = IdFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, letterSpacing = (-0.025).sp),
    titleLarge = TextStyle(fontFamily = IdFontFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = (-0.015).sp),
    titleMedium = TextStyle(fontFamily = IdFontFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp),
    titleSmall = TextStyle(fontFamily = IdFontFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp),
    bodyLarge = TextStyle(fontFamily = IdFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = IdFontFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = IdFontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = IdFontFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.6.sp),
    labelMedium = TextStyle(fontFamily = IdFontFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.9.sp),
    labelSmall = TextStyle(fontFamily = IdFontFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.9.sp),
)
