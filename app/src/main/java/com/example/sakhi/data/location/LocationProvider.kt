package com.example.sakhi.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Locale

data class SakhiLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
    val timestamp: Long,
    val isStale: Boolean,
    val address: String? = null,
    val provider: String? = null
) {
    val mapsUrl: String
        get() = "https://maps.google.com/?q=$latitude,$longitude"

    val isHighAccuracy: Boolean
        get() = accuracy != null && accuracy <= 20f

    val accuracyStatus: String
        get() = when {
            isStale -> "LOCATION STALE"
            accuracy == null -> "APPROXIMATE LOCATION"
            accuracy <= 20f -> "HIGH ACCURACY FIX (±${accuracy.toInt()}m)"
            accuracy <= 100f -> "MEDIUM ACCURACY FIX (±${accuracy.toInt()}m)"
            else -> "APPROXIMATE LOCATION (±${accuracy.toInt()}m)"
        }

    fun isFresh(currentTime: Long = System.currentTimeMillis()): Boolean {
        if (timestamp <= 0L || timestamp > currentTime + 10_000L) return false
        val ageMs = currentTime - timestamp
        return ageMs <= AndroidLocationProvider.FRESHNESS_THRESHOLD_MS
    }

    fun isStale(currentTime: Long = System.currentTimeMillis()): Boolean {
        return !isFresh(currentTime)
    }

    fun withFreshness(currentTime: Long = System.currentTimeMillis()): SakhiLocation {
        val stale = isStale(currentTime)
        return if (stale == this.isStale) this else copy(isStale = stale)
    }
}

interface LocationProvider {
    val currentLocation: StateFlow<SakhiLocation?>
    fun startLocationUpdates()
    fun stopLocationUpdates()
    suspend fun getBestLocation(): SakhiLocation?
    fun requestImmediateFreshLocation() {}
}

class AndroidLocationProvider(
    private val context: Context,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) : LocationProvider {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _currentLocation = MutableStateFlow<SakhiLocation?>(null)
    override val currentLocation: StateFlow<SakhiLocation?> = _currentLocation.asStateFlow()

    private var isListening = false
    private var lastRawLocation: Location? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            updateFromLocation(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun updateFromLocation(location: Location) {
        if (!isValidCoordinate(location.latitude, location.longitude)) return

        val now = timeProvider()
        if (!isBetterLocation(location, lastRawLocation, now)) {
            return
        }
        lastRawLocation = location

        val ageMs = now - location.time
        val isStale = ageMs > FRESHNESS_THRESHOLD_MS || location.time <= 0L || location.time > now + 10_000L

        _currentLocation.value = SakhiLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = if (location.hasAccuracy()) location.accuracy else null,
            timestamp = location.time,
            isStale = isStale,
            address = getAddressFromCoords(location.latitude, location.longitude),
            provider = location.provider
        )
    }

    @SuppressLint("MissingPermission")
    override fun startLocationUpdates() {
        if (isListening || locationManager == null) return
        isListening = true

        val mainLooper = Looper.getMainLooper()

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0f,
                    locationListener,
                    mainLooper
                )
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission not granted for GPS_PROVIDER: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register GPS_PROVIDER updates: ${e.message}", e)
        }

        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    0f,
                    locationListener,
                    mainLooper
                )
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission not granted for NETWORK_PROVIDER: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NETWORK_PROVIDER updates: ${e.message}", e)
        }

        requestImmediateFreshLocation()
        getLastKnownLocationInternal()
    }

    @SuppressLint("MissingPermission")
    override fun requestImmediateFreshLocation() {
        val lm = locationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val executor = ContextCompat.getMainExecutor(context)
                val consumer = java.util.function.Consumer<Location?> { loc ->
                    if (loc != null) {
                        updateFromLocation(loc)
                    }
                }
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.getCurrentLocation(LocationManager.GPS_PROVIDER, null, executor, consumer)
                }
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, executor, consumer)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Permission missing for getCurrentLocation: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request getCurrentLocation: ${e.message}", e)
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, locationListener, Looper.getMainLooper())
                }
                @Suppress("DEPRECATION")
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, locationListener, Looper.getMainLooper())
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Permission missing for requestSingleUpdate: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request single location update on legacy API: ${e.message}", e)
            }
        }
    }

    override fun stopLocationUpdates() {
        if (!isListening || locationManager == null) return
        try {
            locationManager.removeUpdates(locationListener)
        } catch (e: SecurityException) {
            // Ignore
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove location updates: ${e.message}", e)
        } finally {
            isListening = false
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun getBestLocation(): SakhiLocation? = withContext(Dispatchers.IO) {
        val now = timeProvider()
        val current = _currentLocation.value
        if (current != null && current.isFresh(now)) {
            return@withContext current.copy(isStale = false)
        }

        val lastKnown = getLastKnownLocationInternal()
        if (lastKnown != null && lastKnown.isFresh(now)) {
            return@withContext lastKnown.copy(isStale = false)
        }

        requestImmediateFreshLocation()

        val candidate = lastKnown ?: current
        candidate?.withFreshness(now)
    }

    @SuppressLint("MissingPermission")
    private fun getLastKnownLocationInternal(): SakhiLocation? {
        val lm = locationManager ?: return null
        return try {
            var gpsLoc = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            } else null
            var netLoc = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } else null

            if (gpsLoc != null && !isValidCoordinate(gpsLoc.latitude, gpsLoc.longitude)) {
                gpsLoc = null
            }
            if (netLoc != null && !isValidCoordinate(netLoc.latitude, netLoc.longitude)) {
                netLoc = null
            }

            val now = timeProvider()
            val best = when {
                gpsLoc != null && netLoc != null -> if (isBetterLocation(gpsLoc, netLoc, now)) gpsLoc else netLoc
                gpsLoc != null -> gpsLoc
                else -> netLoc
            }

            val currentTimestamp = maxOf(lastRawLocation?.time ?: 0L, _currentLocation.value?.timestamp ?: 0L)
            if (best != null && isBetterLocation(best, lastRawLocation, now) && best.time >= currentTimestamp) {
                lastRawLocation = best
                val ageMs = now - best.time
                val isStale = ageMs > FRESHNESS_THRESHOLD_MS || best.time <= 0L || best.time > now + 10_000L
                val loc = SakhiLocation(
                    latitude = best.latitude,
                    longitude = best.longitude,
                    accuracy = if (best.hasAccuracy()) best.accuracy else null,
                    timestamp = best.time,
                    isStale = isStale,
                    address = getAddressFromCoords(best.latitude, best.longitude),
                    provider = best.provider
                )
                _currentLocation.value = loc
                loc
            } else {
                _currentLocation.value?.withFreshness(now)
            }
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching last known location: ${e.message}", e)
            null
        }
    }

    private fun getAddressFromCoords(lat: Double, lng: Double): String? {
        return try {
            if (!Geocoder.isPresent()) return null
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val list = geocoder.getFromLocation(lat, lng, 1)
            if (!list.isNullOrEmpty()) {
                val addr = list[0]
                val line = addr.getAddressLine(0)
                line ?: "${addr.locality ?: ""}, ${addr.adminArea ?: ""}".trim(',', ' ')
            } else null
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "LocationProvider"
        const val FRESHNESS_THRESHOLD_MS = 60_000L // 60 seconds

        fun isValidCoordinate(latitude: Double, longitude: Double): Boolean {
            if (latitude.isNaN() || longitude.isNaN() || latitude.isInfinite() || longitude.isInfinite()) return false
            if (latitude == 0.0 && longitude == 0.0) return false
            if (latitude < -90.0 || latitude > 90.0) return false
            if (longitude < -180.0 || longitude > 180.0) return false
            return true
        }

        fun isBetterLocation(
            newTime: Long,
            newAccuracy: Float?,
            newProvider: String?,
            currentTime: Long,
            currentAccuracy: Float?,
            currentProvider: String?,
            now: Long = System.currentTimeMillis()
        ): Boolean {
            if (newTime <= 0L || newTime > now + 10_000L) {
                return false
            }
            if (currentTime <= 0L || currentTime > now + 10_000L) {
                return true
            }

            val isCurrentStale = (now - currentTime) > FRESHNESS_THRESHOLD_MS
            val isNewFresh = (now - newTime) <= FRESHNESS_THRESHOLD_MS
            if (isCurrentStale && isNewFresh) {
                return true
            }

            val timeDelta: Long = newTime - currentTime
            val isSignificantlyNewer: Boolean = timeDelta > FRESHNESS_THRESHOLD_MS
            val isSignificantlyOlder: Boolean = timeDelta < -FRESHNESS_THRESHOLD_MS
            val isNewer: Boolean = timeDelta > 0

            if (isSignificantlyNewer) {
                return true
            } else if (isSignificantlyOlder) {
                return false
            }

            val newAcc = newAccuracy ?: 200f
            val bestAcc = currentAccuracy ?: 200f
            val accuracyDelta: Float = newAcc - bestAcc
            val isLessAccurate: Boolean = accuracyDelta > 0
            val isMoreAccurate: Boolean = accuracyDelta < 0
            val isSignificantlyLessAccurate: Boolean = accuracyDelta > 200f

            val isFromSameProvider: Boolean = newProvider != null && newProvider == currentProvider

            if (isMoreAccurate) {
                return true
            } else if (isNewer && !isLessAccurate) {
                return true
            } else if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) {
                return true
            }
            return false
        }

        fun isBetterLocation(newLocation: Location, currentBestLocation: Location?, now: Long = System.currentTimeMillis()): Boolean {
            if (currentBestLocation == null) {
                return newLocation.time > 0L && newLocation.time <= now + 10_000L
            }
            return isBetterLocation(
                newTime = newLocation.time,
                newAccuracy = if (newLocation.hasAccuracy()) newLocation.accuracy else null,
                newProvider = newLocation.provider,
                currentTime = currentBestLocation.time,
                currentAccuracy = if (currentBestLocation.hasAccuracy()) currentBestLocation.accuracy else null,
                currentProvider = currentBestLocation.provider,
                now = now
            )
        }

        fun isBetterLocation(newLoc: SakhiLocation, currentBest: SakhiLocation?, now: Long = System.currentTimeMillis()): Boolean {
            if (currentBest == null) {
                return newLoc.timestamp > 0L && newLoc.timestamp <= now + 10_000L
            }
            return isBetterLocation(
                newTime = newLoc.timestamp,
                newAccuracy = newLoc.accuracy,
                newProvider = newLoc.provider,
                currentTime = currentBest.timestamp,
                currentAccuracy = currentBest.accuracy,
                currentProvider = currentBest.provider,
                now = now
            )
        }
    }
}
