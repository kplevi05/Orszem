package hu.orszembejelento.app.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * GPS is an **optional assist for settlement selection only** (Phase 5 brief §27-30). This
 * class never decides a settlement: it turns a device fix into a reverse-geocoded text hint
 * that the caller feeds into the normal server-backed settlement search, exactly like the
 * user had typed it. The caller must still show the returned candidates and require an
 * explicit user choice - never auto-select on Geocoder output alone.
 *
 * Raw latitude/longitude/accuracy are used only transiently in memory here and in the
 * caller's in-flight state; they are never returned in a form that could be logged,
 * persisted, or attached to a report (§27 - this class exposes no coordinate getter at
 * all, deliberately, so a caller cannot even accidentally reach for one).
 */
class LocationAssist(private val context: Context) {

    private val geocoderExecutor = Executors.newSingleThreadExecutor()

    /**
     * Whether the device's location services (GPS or network provider) are currently on at
     * all. False here is what triggers the exact GPS-off dialog defined by the product
     * copy in §31 - this class does not own that dialog, only this check.
     */
    fun locationServicesEnabled(): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return runCatching {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
    }

    /**
     * One-shot current location. This never *requests* a permission itself (§30, a
     * UI/Activity concern) - but it does check one is already granted before touching the
     * platform location API, both as a defensive guard against a caller mistake and because
     * the lint-visible permission contract needs the check to live at the actual call site,
     * not only in a KDoc promise. Returns null on any failure: no permission, no provider,
     * no fix within a bounded wait, or a security exception.
     *
     * API 30+ uses the modern, cancellable `getCurrentLocation` API. Below that, this posts
     * a single location-update request and takes the first result - never the deprecated
     * blocking `getLastKnownLocation`-only path, and never on the main thread either way.
     */
    suspend fun currentLocation(): Location? {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return null

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) currentLocationModern() else currentLocationLegacy()
        }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @Suppress("MissingPermission") // verified by the caller, currentLocation(), immediately above
    private suspend fun currentLocationModern(): Location? = suspendCancellableCoroutine { continuation ->
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
        }
        val cancellationSignal = CancellationSignal()
        continuation.invokeOnCancellation { cancellationSignal.cancel() }
        manager.getCurrentLocation(provider, cancellationSignal, geocoderExecutor) { location ->
            if (continuation.isActive) continuation.resume(location)
        }
    }

    @Suppress("DEPRECATION", "MissingPermission") // verified by the caller, currentLocation(), immediately above
    private suspend fun currentLocationLegacy(): Location? = suspendCancellableCoroutine { continuation ->
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
        }
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: Location) {
                manager.removeUpdates(this)
                if (continuation.isActive) continuation.resume(location)
            }
        }
        continuation.invokeOnCancellation { manager.removeUpdates(listener) }
        manager.requestLocationUpdates(provider, 0L, 0f, listener, context.mainLooper)
    }

    /**
     * Reverse-geocodes into a free-text hint for the settlement search box - never a
     * settlement identity by itself. Returns null if the platform has no geocoder backend
     * ([Geocoder.isPresent]), or if reverse geocoding fails or returns nothing.
     */
    suspend fun reverseGeocodeHint(location: Location): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.forLanguageTag("hu"))

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            reverseGeocodeAsync(geocoder, location)
        } else {
            reverseGeocodeLegacy(geocoder, location)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun reverseGeocodeAsync(geocoder: Geocoder, location: Location): String? =
        suspendCancellableCoroutine { continuation ->
            geocoder.getFromLocation(location.latitude, location.longitude, 1) { addresses ->
                if (continuation.isActive) continuation.resume(addresses.firstOrNull()?.locality ?: addresses.firstOrNull()?.subAdminArea)
            }
        }

    @Suppress("DEPRECATION")
    private suspend fun reverseGeocodeLegacy(geocoder: Geocoder, location: Location): String? =
        suspendCancellableCoroutine { continuation ->
            geocoderExecutor.execute {
                val hint = runCatching {
                    geocoder.getFromLocation(location.latitude, location.longitude, 1)
                        ?.firstOrNull()
                        ?.let { it.locality ?: it.subAdminArea }
                }.getOrNull()
                if (continuation.isActive) continuation.resume(hint)
            }
        }
}
