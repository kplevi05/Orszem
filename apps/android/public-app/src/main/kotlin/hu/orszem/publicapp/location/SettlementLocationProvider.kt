package hu.orszem.publicapp.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Outcome of a settlement lookup.
 *
 * Every failure is a distinct, user-explainable state (Demo v1.1 §A). No
 * exception, provider name or raw Android message ever escapes this file — the
 * UI layer maps these values to Hungarian strings from `res/values/strings.xml`.
 */
sealed interface SettlementLookupResult {
    data class Success(val settlement: String) : SettlementLookupResult

    /** The app currently holds neither COARSE nor FINE location permission. */
    data object PermissionMissing : SettlementLookupResult

    /** Device-wide location/GPS is switched off; no permission prompt can fix this. */
    data object LocationServicesDisabled : SettlementLookupResult

    /** Permission and services are fine, but no fix could be obtained right now. */
    data object Unavailable : SettlementLookupResult

    /** A position was obtained, but it could not be resolved to a settlement name. */
    data object GeocodingFailed : SettlementLookupResult
}

/**
 * GPS -> reverse geocode -> settlement name.
 *
 * PRIVACY RULE (unchanged in v1.1): only the settlement string is ever returned.
 * Raw latitude/longitude never leaves this class, is never logged, and is never
 * sent to or stored by the backend.
 */
class SettlementLocationProvider(private val context: Context) {

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Whether device location services are switched on.
     *
     * `LocationManagerCompat` is used rather than `LocationManager.isLocationEnabled`
     * (API 28+) so the check also works on the supported minSdk 26/27 devices,
     * where it falls back to the `Settings.Secure.LOCATION_MODE` value.
     */
    private fun locationServicesEnabled(): Boolean {
        val manager = ContextCompat.getSystemService(context, LocationManager::class.java) ?: return false
        return runCatching { LocationManagerCompat.isLocationEnabled(manager) }.getOrDefault(false)
    }

    @Suppress("MissingPermission")
    suspend fun currentSettlement(): SettlementLookupResult {
        if (!hasPermission()) return SettlementLookupResult.PermissionMissing
        // Checked before requesting a fix: with location services off the fused
        // provider simply reports failure, which would otherwise be indistinguishable
        // from "no fix yet" and produce a misleading message.
        if (!locationServicesEnabled()) return SettlementLookupResult.LocationServicesDisabled

        val location = awaitLocation() ?: return SettlementLookupResult.Unavailable
        val settlement = reverseGeocode(location) ?: return SettlementLookupResult.GeocodingFailed
        return SettlementLookupResult.Success(settlement)
    }

    @Suppress("MissingPermission")
    private suspend fun awaitLocation(): Location? = suspendCancellableCoroutine { cont ->
        val client = LocationServices.getFusedLocationProviderClient(context)
        runCatching {
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }.onFailure { if (cont.isActive) cont.resume(null) }
    }

    private suspend fun reverseGeocode(location: Location): String? {
        val geocoder = runCatching { Geocoder(context, Locale("hu", "HU")) }.getOrNull() ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    geocoder.getFromLocation(location.latitude, location.longitude, 1) { addresses ->
                        if (cont.isActive) cont.resume(addresses.firstOrNull()?.toSettlement())
                    }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                geocoder.getFromLocation(location.latitude, location.longitude, 1)
                    ?.firstOrNull()?.toSettlement()
            }.getOrNull()
        }
    }

    private fun android.location.Address.toSettlement(): String? =
        (locality ?: subAdminArea ?: adminArea)?.takeIf { it.isNotBlank() }
}
