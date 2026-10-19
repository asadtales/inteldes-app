package com.inteldes.app.data.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Cuts a [startSec, endSec) sub-clip out of a recording into its own small file, without
 * re-encoding (samples are copied as-is). Used both to upload only a chosen range to the
 * cloud transcriber and by the "Potong & simpan" editor — the source file is only ever read.
 *
 * The MPEG-4 muxer only accepts AAC/AMR, so anything else (an uploaded mp3/ogg) fails with
 * a clear message instead of a muxer crash.
 */
object AudioTrimmer {

    class UnsupportedFormatException(message: String) : IOException(message)

    @Throws(IOException::class)
    fun trim(source: File, destination: File, startSec: Int, endSec: Int) {
        require(endSec > startSec) { "Rentang potong tidak valid." }
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

            muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = try {
                muxer.addTrack(format)
            } catch (e: IllegalArgumentException) {
                throw UnsupportedFormatException("Format audio ini ($mime) belum bisa dipotong — hanya rekaman m4a/AAC.")
            } catch (e: IllegalStateException) {
                throw UnsupportedFormatException("Format audio ini ($mime) belum bisa dipotong — hanya rekaman m4a/AAC.")
            }
            muxer.start()
            muxerStarted = true

            val startUs = startSec.toLong() * 1_000_000L
            val endUs = endSec.toLong() * 1_000_000L
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val bufferInfo = MediaCodec.BufferInfo()
            val buffer = ByteBuffer.allocate(1 shl 20)
            while (true) {
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0 || sampleTime > endUs) break
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                bufferInfo.offset = 0
                bufferInfo.size = size
                // Re-baseline to 0 so the clip is a normal, independently playable file, not
                // one that starts several minutes into its own timeline.
                bufferInfo.presentationTimeUs = (sampleTime - startUs).coerceAtLeast(0)
                bufferInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(outTrack, buffer, bufferInfo)
                written++
                extractor.advance()
            }
            if (written == 0) throw IOException("Rentang yang dipilih tidak berisi audio.")
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
