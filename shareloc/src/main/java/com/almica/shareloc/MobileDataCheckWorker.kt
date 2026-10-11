package com.almica.shareloc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

class MobileDataCheckWorker(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        val (isEnabled, temp) = isMobileDataEnabled(applicationContext)
        val isNetworkStateNotificationEnabled = PreferenceManager(applicationContext).getNetworkStateNotificationsEnabled()
        if (isEnabled && isNetworkStateNotificationEnabled) {
            sendMobileDataNotification(applicationContext)
        }
        Timber.i("Mobile Data Check Worker executed, temperature: $temp°C")

        // 1. Check if the device is capable of sending SMS
        /*
        if (!applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)) {
            Timber.e("Device does not support SMS messaging.")
            return Result.failure()
        }
        */

        // 2. Check for permissions (Location)
        val hasLocationPermission = applicationContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (hasLocationPermission) {
            try {
                val prefs = PreferenceManager(applicationContext)
                val imageUrl = prefs.getImageUrl()

                val location = getCurrentLocation()
                val lat = location?.latitude ?: 0.0
                val lon = location?.longitude ?: 0.0
                val alt = location?.altitude ?: 0.0
                val deviceName = getDeviceName()

                val isLocationSent = sendLocation(deviceName, imageUrl, lat, lon, altitude = alt, temperature = temp)
                if (isLocationSent) {
                    prefs.incrementSendLocationCount() // 10okt2026
                    Timber.i("sendLocation completed successfully")
                    val recipientEmail = prefs.getEmailAddress()
                    if (recipientEmail.isNotBlank() && imageUrl.isNotEmpty()) {
                        val fileName = imageUrl.replace("http://almica.bplaced.net/uploads/", "")
                        Timber.i("Sending email with image: $fileName and location: $lat, $lon to $recipientEmail")
                        sendEmail(fileName, recipientEmail, lat, lon)
                    }
                    prefs.setImageUrl("")
                } else {
                    Timber.w("sendLocation failed, skipping email send")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to send Location")
                return Result.retry()
            }
        } else {
            Timber.w("SEND_SMS or LOCATION permission not granted. Cannot send text message.")
            return Result.failure()
        }

        return Result.success()
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun getCurrentLocation(): Location? {
        val locationManager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(applicationContext)

        // Attempt direct native GPS LocationManager request first
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            try {
                val directGpsLocation = withTimeoutOrNull(20.seconds) {
                    suspendCancellableCoroutine { continuation ->
                        val cancellationSignal = CancellationSignal()
                        continuation.invokeOnCancellation {
                            cancellationSignal.cancel()
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            locationManager.getCurrentLocation(
                                LocationManager.GPS_PROVIDER,
                                cancellationSignal,
                                ContextCompat.getMainExecutor(applicationContext)
                            ) { loc ->
                                if (continuation.isActive) {
                                    continuation.resume(loc)
                                }
                            }
                        } else {
                            val listener = object : LocationListener {
                                override fun onLocationChanged(loc: Location) {
                                    locationManager.removeUpdates(this)
                                    if (continuation.isActive) {
                                        continuation.resume(loc)
                                    }
                                }
                                override fun onProviderDisabled(provider: String) {}
                                override fun onProviderEnabled(provider: String) {}
                            }

                            continuation.invokeOnCancellation {
                                locationManager.removeUpdates(listener)
                            }

                            locationManager.requestSingleUpdate(
                                LocationManager.GPS_PROVIDER,
                                listener,
                                applicationContext.mainLooper
                            )
                        }
                    }
                }

                if (directGpsLocation != null) {
                    val ageMs = (SystemClock.elapsedRealtimeNanos() - directGpsLocation.elapsedRealtimeNanos) / 1_000_000
                    Timber.i("Direct GPS Location obtained successfully. Age: ${ageMs}ms")
                    Timber.i("Location: ${directGpsLocation.latitude}, ${directGpsLocation.longitude}, ${directGpsLocation.altitude}m")
                    return directGpsLocation
                } else {
                    Timber.w("Direct GPS Location request timed out after 20 seconds. Falling back to Fused / lastLocation.")
                }
            } catch (e: SecurityException) {
                Timber.e(e, "Location permission not granted")
                return null
            } catch (e: Exception) {
                Timber.w(e, "Failed to get direct GPS location, falling back to Fused / lastLocation")
            }
        } else {
            Timber.w("GPS Provider disabled on device, falling back to Fused / lastLocation")
        }

        // Fallback to Fused Location / lastLocation
        return try {
            val cts = CancellationTokenSource()
            val location = try {
                withTimeoutOrNull(10.seconds) {
                    fusedLocationClient.getCurrentLocation(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        cts.token
                    ).await()
                }
            } finally {
                cts.cancel()
            }
            location ?: fusedLocationClient.lastLocation.await()
        } catch (e: Exception) {
            Timber.e(e, "Failed to get fallback location")
            null
        }
    }
}
