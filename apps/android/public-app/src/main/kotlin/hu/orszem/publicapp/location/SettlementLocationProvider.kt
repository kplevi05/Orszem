package hu.orszem.publicapp.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

sealed interface SettlementLookupResult {
    data class Success(val settlement: String) : SettlementLookupResult
    data object PermissionDenied : SettlementLookupResult
    data object Failed : SettlementLookupResult
}

/**
 * GPS -> reverse geocode -> settlement name. Only the settlement string is used;
 * raw coordinates never leave the device (PRIVACY RULE).
 */
class SettlementLocationProvider(private val context: Context) {

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    suspend fun currentSettlement(): SettlementLookupResult {
        if (!hasPermission()) return SettlementLookupResult.PermissionDenied
        val location = awaitLocation() ?: return SettlementLookupResult.Failed
        val settlement = reverseGeocode(location) ?: return SettlementLookupResult.Failed
        return SettlementLookupResult.Success(settlement)
    }

    @Suppress("MissingPermission")
    private suspend fun awaitLocation(): Location? = suspendCancellableCoroutine { cont ->
        val client = LocationServices.getFusedLocationProviderClient(context)
        client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resume(null) }
    }

    private suspend fun reverseGeocode(location: Location): String? {
        val geocoder = Geocoder(context, Locale("hu", "HU"))
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(location.latitude, location.longitude, 1) { addresses ->
                    cont.resume(addresses.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea })
                }
            }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                geocoder.getFromLocation(location.latitude, location.longitude, 1)
                    ?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
            }.getOrNull()
        }
    }
}
