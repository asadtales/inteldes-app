package com.inteldes.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inteldes.app.ui.theme.IdColor
import com.inteldes.app.ui.theme.IdFontFamily

/** Flat, square text field in the app's style (plain BasicTextField has no chrome of its own). */
@Composable
fun IdTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        minLines = minLines,
        textStyle = TextStyle(fontFamily = IdFontFamily, fontSize = 14.sp, color = IdColor.Text),
        cursorBrush = SolidColor(IdColor.Accent),
        modifier = modifier
            .fillMaxWidth()
            .background(IdColor.Neutral100)
            .border(BorderStroke(1.dp, IdColor.Neutral300)),
        decorationBox = { inner ->
            Box(modifier = Modifier.padding(10.dp)) {
                if (value.isEmpty()) Text(placeholder, color = IdColor.Neutral600, fontSize = 14.sp)
                inner()
            }
        },
    )
}
