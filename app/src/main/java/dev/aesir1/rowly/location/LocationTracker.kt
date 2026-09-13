package dev.aesir1.rowly.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** What the app can observe about a location update. */
sealed interface LocationUpdate {
    data class Position(val fix: Fix) : LocationUpdate

    /** The provider told us it currently cannot produce fixes. */
    data class Availability(val available: Boolean) : LocationUpdate
}

/** Fused location updates as a cold flow. The caller is responsible for holding the permission. */
class LocationTracker(private val context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    fun updates(intervalMs: Long = 1000L): Flow<LocationUpdate> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location ->
                    trySend(
                        LocationUpdate.Position(
                            Fix(
                                timestamp = location.time,
                                latitude = location.latitude,
                                longitude = location.longitude,
                                speedMs = if (location.hasSpeed()) location.speed else null,
                                accuracyM = if (location.hasAccuracy()) location.accuracy else null,
                            ),
                        ),
                    )
                }
            }

            override fun onLocationAvailability(availability: com.google.android.gms.location.LocationAvailability) {
                trySend(LocationUpdate.Availability(availability.isLocationAvailable))
            }
        }

        client.requestLocationUpdates(request, callback, context.mainLooper)
        awaitClose { client.removeLocationUpdates(callback) }
    }

    companion object {
        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        /**
         * Permission granted is not the same as location working. The spec is explicit about this:
         * the user can hold the permission with location services switched off at the OS level.
         */
        fun isLocationEnabled(context: Context): Boolean {
            val manager = context.getSystemService(LocationManager::class.java) ?: return false
            return manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }
}
