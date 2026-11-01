package com.inteldes.app.data.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Cuts one or more stretches out of a recording into its own small file, joined in order, without
 * re-encoding (samples are copied as-is). Used both to upload only a chosen range to the cloud
 * transcriber and by the trim editor — the source file is only ever read.
 *
 * The MPEG-4 muxer accepts AAC/AMR and the WebM muxer Opus/Vorbis (see [AudioFormats]); anything
 * else (an uploaded mp3/wav/flac) fails with a clear message instead of a muxer crash.
 */
object AudioTrimmer {

    class UnsupportedFormatException(message: String) : IOException(message)

    /** What cutting [source] on the phone would produce (extension and MIME for the new file), or null when it needs the server. */
    fun outputFor(source: File): AudioFormats.Output? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(source.absolutePath)
            val codec = (0 until extractor.trackCount).firstNotNullOfOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.takeIf { it.startsWith("audio/") }
            }
            codec?.let(AudioFormats::onDeviceOutput)
        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    @Throws(IOException::class)
    fun trim(source: File, destination: File, startSec: Int, endSec: Int) {
        require(endSec > startSec) { "Rentang potong tidak valid." }
        trimRanges(source, destination, listOf(TimeRange(startSec, endSec)))
    }

    /** Copies [ranges] (sorted, not overlapping — see [TrimPlan.normalize]) back to back into [destination]. */
    @Throws(IOException::class)
    fun trimRanges(source: File, destination: File, ranges: List<TimeRange>) {
        require(ranges.isNotEmpty() && ranges.all { it.end > it.start }) { "Rentang potong tidak valid." }
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var written = 0
        try {
            extractor.setDataSource(source.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("Tidak ada jalur audio di berkas ini.")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()

            val output = AudioFormats.onDeviceOutput(mime)
                ?: throw UnsupportedFormatException("Format audio ini ($mime) belum bisa dipotong di HP. Yang bisa: m4a/AAC, 3gp/AMR, webm/Opus.")
            muxer = MediaMuxer(
                destination.absolutePath,
                if (output.muxer == AudioFormats.Muxer.WEBM) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
            val outTrack = try {
                muxer.addTrack(format)
            } catch (e: IllegalArgumentException) {
                throw UnsupportedFormatException("Format audio ini ($mime) belum bisa dipotong di HP.")
            } catch (e: IllegalStateException) {
                throw UnsupportedFormatException("Format audio ini ($mime) belum bisa dipotong di HP.")
            }
            muxer.start()
            muxerStarted = true

            val bufferInfo = MediaCodec.BufferInfo()
            val buffer = ByteBuffer.allocate(1 shl 20)
            // Where the next stretch starts on the new file's own timeline, so the pieces play back to back.
            var outputOffsetUs = 0L
            for (range in ranges) {
                val startUs = range.start.toLong() * 1_000_000L
                val endUs = range.end.toLong() * 1_000_000L
                // Land on or before the start, then skip forward: a closest-sync seek can land after it and drop the first words.
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                while (true) {
                    val sampleTime = extractor.sampleTime
                    if (sampleTime < 0 || sampleTime >= endUs) break
                    if (sampleTime < startUs) {
                        if (!extractor.advance()) break
                        continue
                    }
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    bufferInfo.offset = 0
                    bufferInfo.size = size
                    bufferInfo.presentationTimeUs = outputOffsetUs + (sampleTime - startUs)
                    bufferInfo.flags = extractor.sampleFlags
                    muxer.writeSampleData(outTrack, buffer, bufferInfo)
                    written++
                    if (!extractor.advance()) break
                }
                outputOffsetUs += endUs - startUs
            }
            if (written == 0) throw IOException("Bagian yang dipilih tidak berisi audio.")
        } catch (e: Exception) {
            destination.delete()
            throw if (e is IOException) e else IOException("Gagal memotong audio: ${e.message}", e)
        } finally {
            // stop() itself throws if nothing was ever written — never let that mask the real error.
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            extractor.release()
        }
    }
}
