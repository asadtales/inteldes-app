package com.inteldes.app.data.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

enum class RecorderState { IDLE, RECORDING, PAUSED }

/**
 * Thin real wrapper around [MediaRecorder]. One instance lives for the app process
 * (see [com.inteldes.app.IntelDesApp]) so it survives navigating away from the
 * record screen while [com.inteldes.app.data.audio.RecordingService] keeps the
 * process alive in the foreground.
 */
class AudioRecorderManager(private val appContext: Context) {
    private var recorder: MediaRecorder? = null
    private var amplitudeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    private val _state = MutableStateFlow(RecorderState.IDLE)
    val state: StateFlow<RecorderState> = _state

    /** Current 0..32767 mic amplitude, sampled 1x/sec while recording, 0 while paused/idle. */
    private val _amplitude = MutableStateFlow(0)
    val amplitude: StateFlow<Int> = _amplitude

    /** Rolling history of the last [WAVE_HISTORY] amplitude samples, oldest first — drives the live waveform. */
    private val _amplitudeHistory = MutableStateFlow(List(WAVE_HISTORY) { 0 })
    val amplitudeHistory: StateFlow<List<Int>> = _amplitudeHistory

    /** Wall-clock seconds actually recorded, excluding paused time. */
    private val _elapsedSec = MutableStateFlow(0)
    val elapsedSec: StateFlow<Int> = _elapsedSec

    var outputFile: File? = null
        private set

    @Throws(IOException::class)
    fun start(): File {
        val dir = File(appContext.filesDir, "recordings").apply { mkdirs() }
        val file = File(dir, "rec_${System.currentTimeMillis()}.m4a")
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(appContext)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        mr.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(96_000)
            setAudioSamplingRate(44_100)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        recorder = mr
        outputFile = file
        _elapsedSec.value = 0
        _amplitudeHistory.value = List(WAVE_HISTORY) { 0 }
        _state.value = RecorderState.RECORDING
        startAmplitudeLoop()
        return file
    }

    fun pause() {
        if (_state.value != RecorderState.RECORDING) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { recorder?.pause() }
        }
        _state.value = RecorderState.PAUSED
    }

    fun resume() {
        if (_state.value != RecorderState.PAUSED) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { recorder?.resume() }
        }
        _state.value = RecorderState.RECORDING
    }

    /** Stops and releases the recorder, returning the recorded file and its duration in seconds. */
    fun stop(): Pair<File?, Int>? {
        amplitudeJob?.cancel()
        val file = outputFile
        val duration = _elapsedSec.value
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (e: RuntimeException) {
            // stop() throws if called too soon after start(); the partial file is discarded.
            file?.delete()
        } finally {
            recorder = null
            _state.value = RecorderState.IDLE
            _amplitude.value = 0
        }
        return file to duration
    }

    fun discard() {
        amplitudeJob?.cancel()
        runCatching { recorder?.apply { stop(); release() } }
        recorder = null
        outputFile?.delete()
        _state.value = RecorderState.IDLE
        _amplitude.value = 0
        _elapsedSec.value = 0
    }

    private fun startAmplitudeLoop() {
        amplitudeJob?.cancel()
        amplitudeJob = scope.launch {
            while (_state.value != RecorderState.IDLE) {
                if (_state.value == RecorderState.RECORDING) {
                    val sample = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                    _amplitude.value = sample
                    _amplitudeHistory.value = (_amplitudeHistory.value + sample).takeLast(WAVE_HISTORY)
                    _elapsedSec.value += 1
                } else {
                    _amplitude.value = 0
                }
                delay(1000)
            }
        }
    }

    companion object {
        private const val WAVE_HISTORY = 40
    }
}
