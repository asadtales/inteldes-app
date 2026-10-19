package com.inteldes.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inteldes.app.data.model.RecordingMark
import com.inteldes.app.ui.theme.IdColor
import com.inteldes.app.ui.util.formatMmSs
import com.inteldes.app.ui.util.parseClock
import com.inteldes.app.ui.util.validRange

/**
 * Start/end picker shared by the transcribe-range dialog and the trim dialog: two mm:ss
 * fields, optional "use current playback position" shortcuts, and bookmark chips that fill
 * start then end on alternating taps (the chip currently used as start/end is highlighted).
 * A scrolling chip row — a plain Row squeezed long chip lists into vertical one-character
 * columns once there were more than a handful of marks.
 */
@Composable
fun RangeSelector(
    durationSec: Int,
    marks: List<RecordingMark>,
    startText: String,
    endText: String,
    onStartChange: (String) -> Unit,
    onEndChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    currentPositionSec: Int? = null,
) {
    // Both fields start pre-filled with a valid range, so "is start empty" can't tell us
    // whether the user has picked a start mark yet — track the alternation separately.
    var nextMarkTapSetsStart by remember { mutableStateOf(true) }
    val startSec = parseClock(startText)
    val endSec = parseClock(endText)
    val range = validRange(startText, endText, durationSec)

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ClockField("Dari (mm:ss)", startText, onStartChange, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            ClockField("Sampai (mm:ss)", endText, onEndChange, Modifier.weight(1f))
        }
        if (currentPositionSec != null) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallAction("DARI = ${formatMmSs(currentPositionSec)}", Modifier.weight(1f)) { onStartChange(formatMmSs(currentPositionSec)) }
                SmallAction("SAMPAI = ${formatMmSs(currentPositionSec)}", Modifier.weight(1f)) { onEndChange(formatMmSs(currentPositionSec)) }
            }
        }
        if (marks.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("PENANDA — ketuk untuk mengisi Dari lalu Sampai", color = IdColor.Neutral600, fontWeight = FontWeight.Bold, fontSize = 9.5.sp)
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                marks.forEach { mark ->
                    val asStart = mark.timestampSec == startSec
                    val asEnd = mark.timestampSec == endSec
                    Text(
                        formatMmSs(mark.timestampSec),
                        color = if (asStart || asEnd) IdColor.White else IdColor.Neutral800,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier
                            .background(if (asStart || asEnd) IdColor.Accent else IdColor.Neutral100)
                            .clickable {
                                if (nextMarkTapSetsStart) onStartChange(formatMmSs(mark.timestampSec)) else onEndChange(formatMmSs(mark.timestampSec))
                                nextMarkTapSetsStart = !nextMarkTapSetsStart
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (range != null) "Rentang: ${formatMmSs(range.first)} → ${formatMmSs(range.second)} (${formatMmSs(range.second - range.first)})"
            else "Rentang tidak valid — harus di antara 00:00 dan ${formatMmSs(durationSec)}, Dari lebih kecil dari Sampai.",
            color = if (range != null) IdColor.Neutral800 else IdColor.Accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SmallAction(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        color = IdColor.Neutral800,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier.background(IdColor.Neutral100).clickable(onClick = onClick).padding(vertical = 8.dp),
    )
}

@Composable
private fun ClockField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, color = IdColor.Neutral600, fontSize = 10.sp)
        Spacer(Modifier.height(3.dp))
        BasicTextField(
            value = value,
            onValueChange = { new -> onValueChange(new.filter { it.isDigit() || it == ':' }) },
            singleLine = true,
            textStyle = TextStyle(fontSize = 14.sp, color = IdColor.Text, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.fillMaxWidth().background(IdColor.Neutral100).padding(horizontal = 10.dp, vertical = 9.dp),
        )
    }
}
