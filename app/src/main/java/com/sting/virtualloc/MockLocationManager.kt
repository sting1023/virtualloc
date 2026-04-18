package com.sting.virtualloc

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Wraps LocationManager.addTestProvider for mock location.
 * Requires the user to enable "Allow mock locations" in Developer Options.
 */
class MockLocationManager(private val context: Context) {

    private val locationManager: LocationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    private val providerName = LocationManager.GPS_PROVIDER

    /**
     * Register the test provider. Must have mock location permission.
     */
    fun startMocking(lat: Double, lng: Double): Boolean {
        return try {
            // Remove if exists to reset state
            try {
                locationManager.removeTestProvider(providerName)
            } catch (_: Exception) { }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ API
                val props = ProviderProperties.Builder()
                    .setHasAltitudeSupport(false)
                    .setHasSpeedSupport(false)
                    .setHasBearingSupport(false)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(ProviderProperties.ACCURACY_FINE)
                    .build()
                locationManager.addTestProvider(providerName, props)
            } else {
                // Legacy API (Android 6 - 11)
                @Suppress("DEPRECATION")
                locationManager.addTestProvider(
                    providerName,
                    false, // requiresNetwork
                    false, // requiresCell
                    false, // hasMonetaryCost
                    false, // supportsAltitude
                    false, // supportsSpeed
                    false, // supportsBearing
                    false, // false,
                    android.location.provider.ProviderProperties.POWER_USAGE_LOW,
                    android.location.provider.ProviderProperties.ACCURACY_FINE
                )
            }
            locationManager.setTestProviderEnabled(providerName, true)
            updateLocation(lat, lng)
            true
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Push a new location to the test provider.
     */
    fun updateLocation(lat: Double, lng: Double): Boolean {
        return try {
            val location = Location(providerName).apply {
                this.latitude = lat
                this.longitude = lng
                this.accuracy = 1f
                this.altitude = 0.0
                this.time = System.currentTimeMillis()
                this.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
            }
            @Suppress("DEPRECATION")
            locationManager.setTestProviderLocation(providerName, location)
            true
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Stop and remove the test provider.
     */
    fun stopMocking() {
        try {
            locationManager.removeTestProvider(providerName)
        } catch (_: Exception) { }
    }

    /**
     * Check if the app has location permission.
     */
    fun hasLocationPermission(): Boolean {
        return try {
            context.checkCallingOrSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Check if developer mock location is enabled in system settings.
     * Probed by attempting to add a temporary test provider.
     */
    fun isDeveloperMockEnabled(): Boolean {
        return try {
            val probeName = "__virtualloc_probe__"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val props = ProviderProperties.Builder()
                    .setHasAltitudeSupport(false)
                    .setHasSpeedSupport(false)
                    .setHasBearingSupport(false)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(ProviderProperties.ACCURACY_FINE)
                    .build()
                locationManager.addTestProvider(probeName, props)
            } else {
                @Suppress("DEPRECATION")
                locationManager.addTestProvider(
                    probeName,
                    false, false, false, false, false, false, false,
                    android.location.provider.ProviderProperties.POWER_USAGE_LOW,
                    android.location.provider.ProviderProperties.ACCURACY_FINE
                )
            }
            locationManager.removeTestProvider(probeName)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Get the current real GPS location using LocationManager.
     * Returns null if location cannot be retrieved.
     * Uses a one-shot request with a short timeout.
     */
    @SuppressLint("MissingPermission")
    fun getCurrentLocation(callback: (Double?, Double?) -> Unit) {
        try {
            // Try GPS provider first, then network
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            var received = false

            for (provider in providers) {
                if (!locationManager.isProviderEnabled(provider)) continue

                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (!received) {
                            received = true
                            locationManager.removeUpdates(this)
                            callback(location.latitude, location.longitude)
                        }
                    }

                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                    @Deprecated("Deprecated in API")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }

                // Request a single location update
                locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())

                // Also try to get last known location as fallback
                val lastKnown = locationManager.getLastKnownLocation(provider)
                if (lastKnown != null && !received) {
                    received = true
                    locationManager.removeUpdates(listener)
                    callback(lastKnown.latitude, lastKnown.longitude)
                    return
                }
            }

            if (!received) {
                callback(null, null)
            }
        } catch (e: Exception) {
            callback(null, null)
        }
    }
}
