package com.inteldes.app.ui.screens.processing

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inteldes.app.data.model.ProcessingStep
import com.inteldes.app.ui.theme.IdColor
import com.inteldes.app.ui.util.formatDuration

@Composable
fun ProcessingScreen(
    factory: androidx.lifecycle.ViewModelProvider.Factory,
    recordingId: String,
    onDone: () -> Unit,
    onBackgroundIt: () -> Unit,
) {
    val vm: ProcessingViewModel = viewModel(factory = factory)
    val state by vm.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.isDone) {
        if (state.isDone) onDone()
    }

    val steps = ProcessingStep.entries
    val currentIndex = steps.indexOf(state.currentStep)
    val progress by animateFloatAsState(
        targetValue = if (currentIndex < 0) 0.04f else (currentIndex + 1f) / steps.size,
        animationSpec = tween(500),
        label = "progress",
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 16.dp)) {
            Text("MEMPROSES", color = IdColor.Accent, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(10.dp))
            Text(state.recording?.title ?: "", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                (state.recording?.let { formatDuration(it.durationSec) } ?: "") + " · mendeteksi pembicara",
                color = IdColor.Neutral700,
                fontSize = 12.5.sp,
            )
        }
        androidx.compose.material3.HorizontalDivider(thickness = 2.dp, color = IdColor.Divider)

        Box(modifier = Modifier.fillMaxWidth().height(6.dp).background(IdColor.Neutral300)) {
            Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(progress).background(IdColor.Accent))
        }

        if (state.error != null) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("Gagal memproses", color = IdColor.Accent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Text(state.error ?: "", color = IdColor.Neutral800, fontSize = 13.sp)
                Spacer(Modifier.height(14.dp))
                Text(
                    "COBA LAGI",
                    color = IdColor.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .background(IdColor.Accent)
                        .clickable { vm.retry(recordingId) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 18.dp)) {
                items(steps) { step ->
                    val idx = steps.indexOf(step)
                    StepRow(step, done = idx < currentIndex, active = idx == currentIndex, todo = idx > currentIndex)
                }
                item {
                    Column(
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .background(IdColor.Neutral200)
                            .padding(14.dp),
                    ) {
                        Text("PRIVASI", color = IdColor.Text, fontWeight = FontWeight.Bold, fontSize = 10.5.sp, letterSpacing = 1.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Audio dikirim terenkripsi ke layanan transkripsi awan dan dihapus dari server setelah transkrip selesai. Ringkasan disusun oleh agent AI yang dipilih di Pengaturan.",
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
        }

        Column(modifier = Modifier.padding(18.dp)) {
            androidx.compose.material3.HorizontalDivider(thickness = 2.dp, color = IdColor.Divider, modifier = Modifier.padding(bottom = 12.dp))
            Text(
                "PROSES DI LATAR BELAKANG",
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = IdColor.Text,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 0.6.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onBackgroundIt)
                    .padding(vertical = 13.dp),
            )
        }
    }
}

@Composable
private fun StepRow(step: ProcessingStep, done: Boolean, active: Boolean, todo: Boolean) {
    Row(modifier = Modifier.padding(vertical = 15.dp), verticalAlignment = Alignment.Top) {
        Box(modifier = Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            when {
                done -> Text("✓", color = IdColor.Accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                active -> Box(modifier = Modifier.width(11.dp).height(11.dp).background(IdColor.Accent))
                todo -> Box(modifier = Modifier.width(11.dp).height(11.dp))
            }
        }
        Spacer(Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(step.title, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
            Spacer(Modifier.height(3.dp))
            Text(step.desc, color = IdColor.Neutral700, fontSize = 12.5.sp)
        }
    }
}
