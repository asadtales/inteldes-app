package com.inteldes.app.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.inteldes.app.data.model.LocationInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * One-shot "where am I" for tagging a recording: a GPS/network fix from the platform
 * [LocationManager] (no Google Play Services needed) turned into a readable place name with
 * [Geocoder]. When no address can be resolved (offline, no geocoder backend) the name falls back to
 * the raw coordinates, so the recording is still tagged and the user can edit the text.
 */
object LocationHelper {
    private const val FIX_TIMEOUT_MS = 15_000L

    val permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasPermission(context: Context): Boolean = permissions.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Null when permission is missing or no fix could be obtained in time. */
    suspend fun detect(context: Context): LocationInfo? {
        if (!hasPermission(context)) return null
        val location = currentFix(context) ?: return null
        return LocationInfo(placeName(context, location), location.latitude, location.longitude)
    }

    @Suppress("MissingPermission")
    private suspend fun currentFix(context: Context): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

        // A fresh fix when the OS can give one quickly...
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (provider in providers.filter { it != LocationManager.PASSIVE_PROVIDER }) {
                val fresh = withTimeoutOrNull(FIX_TIMEOUT_MS / 2) {
                    suspendCancellableCoroutine<Location?> { cont ->
                        val cancel = android.os.CancellationSignal()
                        cont.invokeOnCancellation { cancel.cancel() }
                        manager.getCurrentLocation(provider, cancel, Executors.newSingleThreadExecutor()) { cont.resume(it) }
                    }
                }
                if (fresh != null) return fresh
            }
        }
        // ...otherwise the newest cached one from any provider.
        return providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    @Suppress("DEPRECATION")
    private suspend fun placeName(context: Context, location: Location): String = withContext(Dispatchers.IO) {
        val coordinates = "%.5f, %.5f".format(Locale.US, location.latitude, location.longitude)
        if (!Geocoder.isPresent()) return@withContext coordinates
        runCatching {
            val address = Geocoder(context, Locale("id", "ID")).getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
                ?: return@runCatching coordinates
            // Street, village/kelurahan, kecamatan, kabupaten/kota — enough to place a village meeting.
            listOfNotNull(address.thoroughfare, address.subLocality, address.locality, address.subAdminArea)
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(", ")
                .ifBlank { address.getAddressLine(0) ?: coordinates }
        }.getOrDefault(coordinates)
    }
}
