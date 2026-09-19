package com.inteldes.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RectangleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inteldes.app.ui.theme.IdColor

/** Small uppercase accent-colored section label — used everywhere for "RINGKASAN", "POIN BAHASAN", etc. */
@Composable
fun KickerLabel(text: String, color: Color = IdColor.Accent, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = 10.5.sp,
        letterSpacing = 1.4.sp,
        modifier = modifier,
    )
}

/** Flat, square, colored tag — "Notulensi siap" / "Memproses" / status chips etc. */
@Composable
fun Pill(
    text: String,
    background: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    bordered: Boolean = false,
) {
    Box(
        modifier = modifier
            .clip(RectangleShape)
            .background(if (bordered) Color.Transparent else background)
            .let { if (bordered) it.border(BorderStroke(1.dp, IdColor.Divider)) else it }
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(
            text = text.uppercase(),
            color = contentColor,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 0.9.sp,
        )
    }
}

@Composable
fun SectionRule(color: Color = IdColor.Divider, modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(color))
}
