package com.americankimchilabs.whitebox.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import com.americankimchilabs.whitebox.core.Clock
import com.americankimchilabs.whitebox.core.LocationSource
import com.americankimchilabs.whitebox.domain.LocationFix
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "WhiteBox/Location"
private const val INTERVAL_MS = 1_000L

/**
 * The car's position from the platform GPS, behind [LocationSource]. Pleos FusedLocation would fit the same port, but
 * its runtime permission reaches the driver as an unnamed action ("알 수 없는 작업") in Android's prompt, which first-run
 * setup does not ask anyone to accept. Needs ACCESS_FINE_LOCATION, granted at first run while parked; without it the
 * flow completes empty.
 */
class PlatformLocationSource(
    context: Context,
    private val clock: Clock,
) : LocationSource {

    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)

    @SuppressLint("MissingPermission") // checked explicitly below
    override fun fixes(): Flow<LocationFix> = callbackFlow {
        if (appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "ACCESS_FINE_LOCATION not granted; no location")
            close()
            return@callbackFlow
        }
        val listener = LocationListener { location: Location ->
            trySendBlocking(
                LocationFix(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                    gpsTimeMillis = location.time,
                    receivedAt = clock.now(),
                ),
            )
        }
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER, INTERVAL_MS, 0f, listener, Looper.getMainLooper(),
        )
        awaitClose { locationManager.removeUpdates(listener) }
    }
}
