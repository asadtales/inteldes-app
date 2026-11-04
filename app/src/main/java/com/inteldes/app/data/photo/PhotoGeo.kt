package com.inteldes.app.data.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.ExifInterface
import android.net.Uri
import com.inteldes.app.data.location.LocationHelper
import com.inteldes.app.data.prefs.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.floor

/** Where a photo was taken (WGS84), as kept with the photo and sent to the server. */
data class GeoFix(val latitude: Double, val longitude: Double, val accuracyM: Double?, val placeName: String?, val atMs: Long)

/** The rules and wording of the geotag, free of Android so they can be tested. */
object PhotoGeoRules {
    /** A fresh fix is waited for this long; the photo is kept without a geotag when none arrives. */
    const val FIX_TIMEOUT_MS = 5_000L
    const val MAX_CACHED_AGE_MS = 60_000L
    const val MAX_CACHED_ACCURACY_M = 50f

    /** The last known location may stand in for a fresh one only when it is recent and precise enough. */
    fun cachedAcceptable(ageMs: Long, accuracyM: Float?): Boolean =
        ageMs in 0..MAX_CACHED_AGE_MS && accuracyM != null && accuracyM <= MAX_CACHED_ACCURACY_M

    private val wib: TimeZone = TimeZone.getTimeZone("Asia/Jakarta")

    fun coordinates(lat: Double, lng: Double): String = "%.5f, %.5f".format(Locale.US, lat, lng)

    /** The lines written on the strip at the bottom of the photo: time (WIB), coordinates, place, title. */
    fun stampLines(takenAtMs: Long, fix: GeoFix?, titleLine: String?): List<String> {
        val time = SimpleDateFormat("d MMM yyyy HH.mm.ss", Locale("id", "ID")).apply { timeZone = wib }.format(Date(takenAtMs)) + " WIB (GMT+7)"
        return buildList {
            add(time)
            if (fix != null) {
                add(coordinates(fix.latitude, fix.longitude) + (fix.accuracyM?.let { " · ±${Math.round(it)} m" } ?: ""))
                fix.placeName?.takeIf { it.isNotBlank() && it != coordinates(fix.latitude, fix.longitude) }?.let { add(it) }
            }
            titleLine?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
    }

    /** EXIF wants degrees, minutes and seconds as three fractions: "7/1,7/1,24420/1000". */
    fun exifDms(value: Double): String {
        val v = abs(value)
        val deg = floor(v).toInt()
        val minFull = (v - deg) * 60
        val min = floor(minFull).toInt()
        val sec = (minFull - min) * 60
        return "$deg/1,$min/1,${Math.round(sec * 1000)}/1000"
    }

    fun exifLatRef(lat: Double) = if (lat >= 0) "N" else "S"
    fun exifLngRef(lng: Double) = if (lng >= 0) "E" else "W"

    /** What is read back from the EXIF strings above, to check a round trip. */
    fun fromExifDms(dms: String, ref: String): Double {
        val parts = dms.split(",").map { p -> p.split("/").let { it[0].toDouble() / it[1].toDouble() } }
        val v = parts[0] + parts[1] / 60 + parts[2] / 3600
        return if (ref == "S" || ref == "W") -v else v
    }
}

/** The organisation's logo for the photo strip, kept on this phone (one per phone for now). */
object PhotoLogo {
    private const val MAX_SOURCE_BYTES = 1_048_576L
    private const val MAX_SIDE = 512

    private fun file(context: Context) = File(context.filesDir, "org_logo.png")

    fun exists(context: Context): Boolean = file(context).let { it.exists() && it.length() > 0 }

    fun load(context: Context): Bitmap? = file(context).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }

    fun clear(context: Context) { file(context).delete() }

    /** Takes the chosen picture (PNG, JPG or WebP up to 1 MB), shrinks it, and keeps it as the logo. */
    suspend fun save(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (size > MAX_SOURCE_BYTES) throw IOException("Logo terlalu besar (maksimal 1 MB).")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: throw IOException("Logo tidak bisa dibaca.")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Berkas yang dipilih bukan gambar.")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw IOException("Logo tidak bisa dibaca.")
        file(context).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }
}

/** Takes the geotag of a photo, writes it into the picture (strip and EXIF) and returns it for storing and syncing. */
object PhotoGeo {
    private val wib: TimeZone = TimeZone.getTimeZone("Asia/Jakarta")

    /**
     * Applies the user's photo settings to [file] (already upright and scaled): finds where the phone is, writes the
     * strip with the logo and the coordinates when asked, and puts the GPS position into the EXIF. Returns the geotag, or
     * null when geotagging is off, permission is missing, or no position was found in time (the photo is kept as it is).
     */
    suspend fun process(context: Context, file: File, settings: AppSettings, titleLine: String?, takenAtMs: Long = System.currentTimeMillis()): GeoFix? =
        withContext(Dispatchers.IO) {
            val fix = if (settings.photoGeotag) capture(context, takenAtMs) else null
            val stampGeo = settings.photoGeotag && settings.photoGeoStamp && fix != null
            if (stampGeo || settings.photoLogo) {
                runCatching {
                    stamp(file, PhotoGeoRules.stampLines(takenAtMs, if (stampGeo) fix else null, titleLine), settings.photoLogo, PhotoLogo.load(context))
                }
            }
            if (fix != null) runCatching { writeExif(file, fix, takenAtMs) }
            fix
        }

    /** Just the position (for a video), when the user has geotagging on; null otherwise. */
    suspend fun fixFor(context: Context, settings: AppSettings): GeoFix? =
        if (settings.photoGeotag) withContext(Dispatchers.IO) { capture(context, System.currentTimeMillis()) } else null

    private suspend fun capture(context: Context, takenAtMs: Long): GeoFix? {
        val location = LocationHelper.photoFix(context) ?: return null
        val place = runCatching { LocationHelper.placeNameOf(context, location) }.getOrNull()
        return GeoFix(location.latitude, location.longitude, if (location.hasAccuracy()) location.accuracy.toDouble() else null, place, takenAtMs)
    }

    /** [withLogo]: draw [logo], or the IntelDes mark when the user has not chosen a logo of their own. */
    private fun stamp(file: File, lines: List<String>, withLogo: Boolean, logo: Bitmap?) {
        val src = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inMutable = true }) ?: return
        val bmp = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true).also { src.recycle() }
        val canvas = Canvas(bmp)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val textSize = (w * 0.032f).coerceAtLeast(14f)
        val pad = textSize * 0.6f
        val lineH = textSize * 1.3f
        val stripH = pad * 2 + lineH * lines.size
        val top = h - stripH
        canvas.drawRect(0f, top, w, h, Paint().apply { color = Color.argb(150, 0, 0, 0) })
        val logoSize = stripH - pad * 1.4f
        var textX = pad
        if (withLogo && logoSize > 8f) {
            drawLogo(canvas, logo, RectF(pad, top + (stripH - logoSize) / 2, pad + logoSize, top + (stripH + logoSize) / 2))
            textX = pad * 2 + logoSize
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            setShadowLayer(textSize * 0.12f, 0f, 0f, Color.BLACK)
        }
        val avail = w - textX - pad
        lines.forEachIndexed { i, line ->
            var text = line
            while (text.length > 1 && paint.measureText(text) > avail) text = text.dropLast(2)
            if (text.length < line.length) text = text.trimEnd() + "…"
            canvas.drawText(text, textX, top + pad + lineH * i + textSize, paint)
        }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bmp.recycle()
    }

    /** The chosen logo scaled into [box], or the IntelDes mark (red square with three white bars) when there is none. */
    private fun drawLogo(canvas: Canvas, logo: Bitmap?, box: RectF) {
        if (logo != null) {
            val scale = minOf(box.width() / logo.width, box.height() / logo.height)
            val lw = logo.width * scale
            val lh = logo.height * scale
            val dst = RectF(box.centerX() - lw / 2, box.centerY() - lh / 2, box.centerX() + lw / 2, box.centerY() + lh / 2)
            canvas.drawBitmap(logo, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            return
        }
        canvas.drawRect(box, Paint().apply { color = Color.parseColor("#C4260C") })
        val bar = Paint().apply { color = Color.WHITE }
        val u = box.width() / 32f
        for (x in listOf(8f, 14f, 20f)) canvas.drawRect(box.left + x * u, box.top + 8 * u, box.left + (x + 4) * u, box.top + 24 * u, bar)
    }

    private fun writeExif(file: File, fix: GeoFix, takenAtMs: Long) {
        val exif = ExifInterface(file.absolutePath)
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, PhotoGeoRules.exifDms(fix.latitude))
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, PhotoGeoRules.exifLatRef(fix.latitude))
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, PhotoGeoRules.exifDms(fix.longitude))
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, PhotoGeoRules.exifLngRef(fix.longitude))
        val utc = TimeZone.getTimeZone("UTC")
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, SimpleDateFormat("yyyy:MM:dd", Locale.US).apply { timeZone = utc }.format(Date(fix.atMs)))
        exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = utc }.format(Date(fix.atMs)).split(":").joinToString(",") { "${it.toInt()}/1" })
        // the picture itself is already upright, whatever the camera wrote
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { timeZone = wib }.format(Date(takenAtMs)))
        exif.saveAttributes()
    }
}
