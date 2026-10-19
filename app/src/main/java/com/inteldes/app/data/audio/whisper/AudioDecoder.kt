package com.inteldes.app.data.audio.whisper

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val WHISPER_SAMPLE_RATE = 16_000

/** Decodes a recorded .m4a to the mono 16kHz float32 PCM whisper.cpp requires. */
object AudioDecoder {

    /**
     * @param startSec/endSec Optional sub-range of the recording to decode (e.g. from two
     * marks the user picked) — transcribing only that span instead of the whole file, both
     * on-device and on the cloud, is the whole point of letting the range through this far
     * rather than trimming after the fact.
     */
    fun decodeToWhisperPcm(file: File, startSec: Int? = null, endSec: Int? = null): FloatArray {
        val (pcm16, sampleRate, channelCount) = decodeToPcm16(file, startSec, endSec)
        val mono = if (channelCount > 1) downmixToMono(pcm16, channelCount) else pcm16
        val resampled = if (sampleRate != WHISPER_SAMPLE_RATE) resample(mono, sampleRate, WHISPER_SAMPLE_RATE) else mono
        return FloatArray(resampled.size) { i -> (resampled[i] / 32768f).coerceIn(-1f, 1f) }
    }

    private data class Pcm16(val samples: ShortArray, val sampleRate: Int, val channelCount: Int)

    private fun decodeToPcm16(file: File, startSec: Int?, endSec: Int?): Pcm16 {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IllegalStateException("Tidak ada jalur audio di berkas ini.")
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val startUs = (startSec?.toLong() ?: 0L) * 1_000_000L
        val endUs = endSec?.let { it.toLong() * 1_000_000L }
        if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val output = java.io.ByteArrayOutputStream()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val pastEnd = endUs != null && extractor.sampleTime >= 0 && extractor.sampleTime > endUs
                    val sampleSize = if (pastEnd) -1 else extractor.readSampleData(codec.getInputBuffer(inputIndex)!!, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outputIndex >= 0) {
                if (bufferInfo.size > 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    val chunk = ByteArray(bufferInfo.size)
                    outputBuffer.get(chunk)
                    output.write(chunk)
                }
                codec.releaseOutputBuffer(outputIndex, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        val bytes = output.toByteArray()
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return Pcm16(shorts, sampleRate, channelCount)
    }

    private fun downmixToMono(samples: ShortArray, channelCount: Int): ShortArray {
        val frameCount = samples.size / channelCount
        val mono = ShortArray(frameCount)
        for (i in 0 until frameCount) {
            var sum = 0
            for (c in 0 until channelCount) sum += samples[i * channelCount + c]
            mono[i] = (sum / channelCount).toInt().toShort()
        }
        return mono
    }

    /**
     * Downsamples (e.g. 44.1kHz -> 16kHz) via a box-filter low-pass followed by linear
     * interpolation. Plain linear interpolation with no filtering first is a naive
     * decimator — it lets high frequencies above the new Nyquist limit fold back as
     * audible noise (aliasing), which showed up as a real accuracy problem: whisper
     * kept mis-hearing the same phrase on repeat on real recordings. A box filter isn't
     * a proper sinc filter, but it's cheap and cuts enough high-frequency content to
     * measurably reduce that artifact.
     */
    private fun resample(samples: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (samples.isEmpty()) return samples
        val ratio = fromRate.toDouble() / toRate.toDouble()
        val filterRadius = (ratio / 2).toInt().coerceAtLeast(1)
        val filtered = ShortArray(samples.size)
        for (i in samples.indices) {
            val lo = (i - filterRadius).coerceAtLeast(0)
            val hi = (i + filterRadius).coerceAtMost(samples.size - 1)
            var sum = 0L
            for (j in lo..hi) sum += samples[j]
            filtered[i] = (sum / (hi - lo + 1)).toInt().toShort()
        }
        val outLength = (samples.size / ratio).toInt()
        val out = ShortArray(outLength)
        for (i in 0 until outLength) {
            val srcPos = i * ratio
            val srcIndex = srcPos.toInt()
            val frac = srcPos - srcIndex
            val s0 = filtered[srcIndex.coerceIn(0, filtered.size - 1)]
            val s1 = filtered[(srcIndex + 1).coerceIn(0, filtered.size - 1)]
            out[i] = (s0 + (s1 - s0) * frac).toInt().toShort()
        }
        return out
    }
}
