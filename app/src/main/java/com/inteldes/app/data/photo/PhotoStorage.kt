package com.inteldes.app.data.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Photos attached to a recording. Every photo (camera or gallery) is re-encoded into the app's own
 * `files/photos` directory: scaled so its longest side is at most [MAX_SIDE] px and rotated upright
 * per its EXIF tag, so the notulensi shows the picture the right way up and 12 MP camera shots
 * don't eat storage.
 */
object PhotoStorage {
    private const val MAX_SIDE = 1600
    private const val JPEG_QUALITY = 85

    fun authority(context: Context) = "${context.packageName}.fileprovider"

    /** A fresh cache file plus the FileProvider [Uri] the camera app should write the picture to. */
    fun newCameraTarget(context: Context): Uri {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "capture_${UUID.randomUUID()}.jpg")
        return FileProvider.getUriForFile(context, authority(context), file)
    }

    suspend fun import(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // decodeStream returns null when only reading bounds, so the stream itself (not the
        // decode result) is what tells us whether the uri was readable.
        val boundsStream = resolver.openInputStream(uri) ?: throw IOException("Tidak bisa membaca foto yang dipilih.")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) throw IOException("Berkas yang dipilih bukan gambar yang valid.")

        var sample = 1
        while (longest / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("Tidak bisa membaca foto yang dipilih.")

        val degrees = readRotationDegrees(context, uri)
        val scale = min(1f, MAX_SIDE.toFloat() / max(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            if (degrees != 0) postRotate(degrees.toFloat())
            if (scale < 1f) postScale(scale, scale)
        }
        val finalBitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)

        val dir = File(context.filesDir, "photos").apply { mkdirs() }
        val out = File(dir, "photo_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg")
        out.outputStream().use { finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }

        if (finalBitmap !== decoded) decoded.recycle()
        finalBitmap.recycle()
        out
    }

    private fun readRotationDegrees(context: Context, uri: Uri): Int {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }
}
