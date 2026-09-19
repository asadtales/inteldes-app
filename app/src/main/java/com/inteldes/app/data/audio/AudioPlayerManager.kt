package com.inteldes.app.data.audio

import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class PlaybackState(val playing: Boolean = false, val positionSec: Int = 0, val durationSec: Int = 0, val ready: Boolean = false)

/** Plays back one recording's audio file — real [MediaPlayer], backing the Transkrip tab's play/pause control. */
class AudioPlayerManager {
    private var player: MediaPlayer? = null
    private var tickJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state

    fun load(path: String) {
        release()
        val mp = MediaPlayer()
        runCatching {
            mp.setDataSource(path)
            mp.prepare()
            player = mp
            _state.value = PlaybackState(playing = false, positionSec = 0, durationSec = mp.duration / 1000, ready = true)
            mp.setOnCompletionListener {
                _state.value = _state.value.copy(playing = false, positionSec = _state.value.durationSec)
                tickJob?.cancel()
            }
        }
    }

    fun togglePlay() {
        val mp = player ?: return
        if (mp.isPlaying) {
            mp.pause()
            _state.value = _state.value.copy(playing = false)
            tickJob?.cancel()
        } else {
            mp.start()
            _state.value = _state.value.copy(playing = true)
            startTicking()
        }
    }

    fun seekTo(sec: Int) {
        player?.seekTo(sec * 1000)
        _state.value = _state.value.copy(positionSec = sec)
    }

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (player?.isPlaying == true) {
                _state.value = _state.value.copy(positionSec = (player?.currentPosition ?: 0) / 1000)
                delay(500)
            }
        }
    }

    fun release() {
        tickJob?.cancel()
        runCatching { player?.release() }
        player = null
        _state.value = PlaybackState()
    }
}
