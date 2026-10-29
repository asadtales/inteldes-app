package com.inteldes.app.data.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
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

    /** Bookmarked moments (in [elapsedSec] terms) — lives here, not in RecordViewModel, so a
     * tap on the recording notification's "TANDAI" action (or the app process outliving a
     * destroyed Activity while backgrounded) still lands in the same place the UI reads from. */
    private val _marks = MutableStateFlow<List<Int>>(emptyList())
    val marks: StateFlow<List<Int>> = _marks

    /** What Android is actually capturing from right now (e.g. "Bluetooth · Galaxy Buds"), for display. */
    private val _activeMic = MutableStateFlow<String?>(null)
    val activeMic: StateFlow<String?> = _activeMic

    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var scoStarted = false

    var outputFile: File? = null
        private set

    /** [mic] null = let Android choose (it switches to a connected Bluetooth earbud on its own). */
    @Throws(IOException::class)
    fun start(mic: MicOption? = null): File {
        // Idempotent: a session already in progress is returned as-is. Starting again used to
        // create a second MediaRecorder over the first (never released), reset the timer and
        // marks, and orphan the earlier file — which is what "continue the current recording"
        // turned into whenever the record screen was reopened mid-recording.
        outputFile?.let { if (_state.value != RecorderState.IDLE) return it }
        val dir = File(appContext.filesDir, "recordings").apply { mkdirs() }
        val file = File(dir, "rec_${System.currentTimeMillis()}.m4a")
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(appContext)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        val bluetooth = mic?.kind == MicKind.BLUETOOTH
        try {
            mr.apply {
                // Bluetooth headsets only deliver narrow-band mono voice audio, so ask for what they can give.
                setAudioSource(if (bluetooth) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(if (bluetooth) 48_000 else 96_000)
                setAudioSamplingRate(if (bluetooth) 16_000 else 44_100)
                setOutputFile(file.absolutePath)
                if (mic != null) route(this, mic)
                prepare()
                start()
            }
        } catch (e: Exception) {
            releaseBluetooth()
            runCatching { mr.release() }
            file.delete()
            throw if (e is IOException) e else IOException(e.message ?: "Gagal memulai perekaman", e)
        }
        recorder = mr
        refreshActiveMic()
        outputFile = file
        _elapsedSec.value = 0
        _amplitudeHistory.value = List(WAVE_HISTORY) { 0 }
        _marks.value = emptyList()
        _state.value = RecorderState.RECORDING
        startAmplitudeLoop()
        return file
    }

    /** Switches microphone, also while recording. null = back to the system's automatic choice. */
    fun selectMic(mic: MicOption?) {
        val mr = recorder ?: return
        if (mic == null) {
            runCatching { mr.setPreferredDevice(null) }
            releaseBluetooth()
        } else {
            route(mr, mic)
        }
        refreshActiveMic()
    }

    private fun route(mr: MediaRecorder, mic: MicOption) {
        runCatching { mr.setPreferredDevice(mic.device) }
        if (mic.kind == MicKind.BLUETOOTH) startBluetooth(mic.device) else releaseBluetooth()
    }

    /** Asks Android to open the Bluetooth voice link, the path that carries the earbud's mic audio. */
    @Suppress("DEPRECATION")
    private fun startBluetooth(input: AudioDeviceInfo) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val target = audioManager.availableCommunicationDevices
                    .firstOrNull { MicSources.isBluetoothType(it.type) && it.address == input.address }
                    ?: audioManager.availableCommunicationDevices.firstOrNull { MicSources.isBluetoothType(it.type) }
                if (target != null) audioManager.setCommunicationDevice(target)
            } else if (!scoStarted) {
                audioManager.startBluetoothSco()
                scoStarted = true
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun releaseBluetooth() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
            else if (scoStarted) audioManager.stopBluetoothSco()
        }
        scoStarted = false
    }

    private fun refreshActiveMic() {
        _activeMic.value = MicSources.describe(runCatching { recorder?.routedDevice }.getOrNull())
    }

    fun addMark() {
        if (_state.value == RecorderState.IDLE) return
        _marks.value = _marks.value + _elapsedSec.value
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
            releaseBluetooth()
            _activeMic.value = null
            _state.value = RecorderState.IDLE
            _amplitude.value = 0
        }
        return file to duration
    }

    fun discard() {
        amplitudeJob?.cancel()
        runCatching { recorder?.apply { stop(); release() } }
        recorder = null
        releaseBluetooth()
        _activeMic.value = null
        outputFile?.delete()
        _state.value = RecorderState.IDLE
        _amplitude.value = 0
        _elapsedSec.value = 0
        _marks.value = emptyList()
    }

    private fun startAmplitudeLoop() {
        amplitudeJob?.cancel()
        amplitudeJob = scope.launch {
            while (_state.value != RecorderState.IDLE) {
                if (_state.value == RecorderState.RECORDING) {
                    val sample = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                    if (_elapsedSec.value % 3 == 0) refreshActiveMic()
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
