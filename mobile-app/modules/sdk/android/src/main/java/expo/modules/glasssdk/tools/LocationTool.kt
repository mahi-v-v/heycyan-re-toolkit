package expo.modules.glasssdk.tools

import android.Manifest
import android.content.Context
import android.location.Location
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import expo.modules.glasssdk.util.AppLog
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Location tool for getting GPS coordinates
 *
 * Request params:
 * {
 *   "accuracy": "high" | "balanced" | "low"  // Optional, default "high"
 * }
 *
 * Response:
 * {"success":true,"latitude":37.77,"longitude":-122.41,"accuracy":10.5,"altitude":123.4,"speed":5.2,"bearing":45.0,"timestamp":1234567890}
 * or
 * {"success":false,"error":"Location unavailable"}
 */
class LocationTool(context: Context) : BaseTool(context) {

    override val toolName = "getLocation"

    override val requiredPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    override suspend fun execute(params: JSONObject): String {
        return try {

            val highAccuracy = params.optBoolean("highAccuracy", false)
            val accuracy = if (highAccuracy) "high" else "low";


            val location = getCurrentLocation(accuracy)


            val response = JSONObject().apply {
                put("success", true)
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("accuracy", location.accuracy.toDouble())
                put("timestamp", location.time)

                if (location.hasAltitude()) {
                    put("altitude", location.altitude)
                }
                if (location.hasSpeed()) {
                    put("speed", location.speed.toDouble())
                }
                if (location.hasBearing()) {
                    put("bearing", location.bearing.toDouble())
                }
            }

            response.toString()

        } catch (e: SecurityException) {
            AppLog.e("LocationTool: Permission denied", e)
            """{"success":false,"error":"Location permission denied"}"""

        } catch (e: Exception) {
            AppLog.e("LocationTool: Failed to get location", e)
            """{"success":false,"error":"${e.message ?: "Location unavailable"}"}"""
        }
    }

    /**
     * Get current location using FusedLocationProvider
     */
    private suspend fun getCurrentLocation(accuracy: String): Location {
        return suspendCancellableCoroutine { continuation ->
            val priority = when (accuracy) {
                "high" -> Priority.PRIORITY_HIGH_ACCURACY
                "balanced" -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
                "low" -> Priority.PRIORITY_LOW_POWER
                else -> Priority.PRIORITY_HIGH_ACCURACY
            }

            val request = CurrentLocationRequest.Builder()
                .setPriority(priority)
                .setDurationMillis(10000L) // 10 second timeout
                .setMaxUpdateAgeMillis(5000L) // Use cached location if < 5 seconds old
                .build()

            try {
                fusedLocationClient.getCurrentLocation(request, null)
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            continuation.resume(location)
                        } else {
                            continuation.resumeWithException(
                                Exception("Location unavailable - GPS may be disabled")
                            )
                        }
                    }
                    .addOnFailureListener { exception ->
                        continuation.resumeWithException(exception)
                    }
            } catch (e: SecurityException) {
                continuation.resumeWithException(e)
            }
        }
    }
}
