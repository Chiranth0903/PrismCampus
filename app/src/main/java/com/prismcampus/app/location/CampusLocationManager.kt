package com.prismcampus.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Native Android GPS & Network Location Manager for PrismCampus.
 * Tracks user live latitude/longitude on campus without relying on external Google Play Services.
 */
class CampusLocationManager(private val context: Context) : LocationListener {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val _isGpsActive = MutableStateFlow(false)
    val isGpsActive: StateFlow<Boolean> = _isGpsActive.asStateFlow()

    private val _isSimulatedOnCampus = MutableStateFlow(false)
    val isSimulatedOnCampus: StateFlow<Boolean> = _isSimulatedOnCampus.asStateFlow()

    // Default MSRIT Main Gate anchor: Lat 13.0295, Lng 77.5650
    val defaultCampusAnchor = Pair(13.0295, 77.5650)

    init {
        startLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        val lm = locationManager ?: return
        try {
            val isGpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            _isGpsActive.value = isGpsEnabled || isNetworkEnabled

            // Obtain last known location immediately
            val lastKnownGps = if (isGpsEnabled) lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) else null
            val lastKnownNet = if (isNetworkEnabled) lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) else null
            val bestLast = lastKnownGps ?: lastKnownNet

            if (bestLast != null) {
                _currentLocation.value = bestLast
            }

            // Request periodic updates
            if (isGpsEnabled) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2000L, // 2 seconds
                    2f,    // 2 meters
                    this,
                    Looper.getMainLooper()
                )
            }
            if (isNetworkEnabled) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    3000L,
                    3f,
                    this,
                    Looper.getMainLooper()
                )
            }
        } catch (e: SecurityException) {
            _isGpsActive.value = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopLocationUpdates() {
        locationManager?.removeUpdates(this)
    }

    override fun onLocationChanged(location: Location) {
        if (!_isSimulatedOnCampus.value) {
            _currentLocation.value = location
            _isGpsActive.value = true
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) { _isGpsActive.value = true }
    override fun onProviderDisabled(provider: String) { _isGpsActive.value = false }

    /**
     * Toggles campus simulation mode (places user at Main Gate or student quad for testing).
     */
    fun toggleSimulatedCampusLocation(latitude: Double = 13.0298, longitude: Double = 77.5651) {
        _isSimulatedOnCampus.value = !_isSimulatedOnCampus.value
        if (_isSimulatedOnCampus.value) {
            val sim = Location("simulated").apply {
                this.latitude = latitude
                this.longitude = longitude
                accuracy = 3f
                time = System.currentTimeMillis()
            }
            _currentLocation.value = sim
        } else {
            startLocationUpdates()
        }
    }

    /**
     * Calculates distance in meters between user's current position and destination.
     */
    fun calculateDistanceMeters(targetLat: Double, targetLng: Double): Float {
        val loc = _currentLocation.value
        if (loc != null) {
            val results = FloatArray(1)
            Location.distanceBetween(loc.latitude, loc.longitude, targetLat, targetLng, results)
            return results[0]
        }
        // Fallback distance from Main Gate
        val results = FloatArray(1)
        Location.distanceBetween(defaultCampusAnchor.first, defaultCampusAnchor.second, targetLat, targetLng, results)
        return results[0]
    }
}
