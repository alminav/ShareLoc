package com.almica.shareloc

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.almica.shareloc.backgroundcamera.ServiceLifecycleOwner
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Problem with BG Camera
 * android.hardware.camera2.CameraAccessException: CAMERA_DISABLED (1): validateClientPermissionsLocked:1810:
 * Caller "com.almica.shareloc" (PID 1744, UID 10215) cannot open camera "0" from background
 * ==> Enabled BackgroundCameraService again
 * Disable camera logic here with: val prefs_isBackgroundCameraEnabled = false
 */
class ShareLocWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "CameraWorker"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "shareloc_camera_worker"
        const val LUX_THRESHOLD = 30.0f
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "ShareLoc Background Worker",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("ShareLoc Background Worker")
            .setContentText("Standortbestimmung wird durchgeführt...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun doWork(): Result {
        val (isEnabled, temp) = isShareLocEnabled(applicationContext)
        val prefs = PreferenceManager(applicationContext)

        if (isEnabled && prefs.getNetworkStateNotificationsEnabled()) {
            sendShareLocNotification(applicationContext)
        }
        Timber.i("Mobile Data Check Worker executed, temperature: $temp°C")

        if (!isNetworkAvailable(applicationContext)) {
            Timber.w("No network connection available. Retrying ShareLocWorker.")
            return Result.retry()
        }

        // 1. Measure light level non-blockingly if background camera is enabled
        var currentLuxValue = 0f
        val prefs_isBackgroundCameraEnabled = false // disable BG Camera for now
        //if (prefs.isBackgroundCameraEnabled()) {
        if (prefs_isBackgroundCameraEnabled) {
            currentLuxValue = getLightLevel(timeoutMs = 3000L)
            Timber.i("Measured brightness: $currentLuxValue Lux")
        }

        // 2. Permission Check
        val hasLocationPermission = applicationContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocationPermission) {
            Timber.w("LOCATION permission not granted. Cannot process location update.")
            return Result.failure()
        }

        return try {
            val imageUrl = prefs.getImageUrl()
            val location = getCurrentLocation()
            val lat = location?.latitude ?: 0.0
            val lon = location?.longitude ?: 0.0
            val alt = location?.altitude ?: 0.0
            val deviceName = getDeviceName()

            val isLocationSent = sendLocation(deviceName, imageUrl, lat, lon, altitude = alt, temperature = temp)
            if (isLocationSent) {
                prefs.incrementSendLocationCount()
                Timber.i("sendLocation completed successfully")

                val recipientEmail = prefs.getEmailAddress()
                if (recipientEmail.isNotBlank() && imageUrl.isNotEmpty()) {
                    val fileName = imageUrl.substringAfterLast("/")
                    Timber.i("Sending email with image: $fileName and location: $lat, $lon to $recipientEmail")
                    sendEmail(fileName, recipientEmail, lat, lon)
                }
                prefs.setImageUrl("")

                // 3. Capture photo & Upload sequentially if brightness threshold is met
                //if (prefs.isBackgroundCameraEnabled()) {
                if (prefs_isBackgroundCameraEnabled) {
                    if (currentLuxValue >= LUX_THRESHOLD) {
                        val photoFile = capturePhoto(location)
                        if (photoFile != null) {
                            uploadImageToBplaced(photoFile, location)
                        } else {
                            Timber.w("$TAG Photo capture failed or returned null")
                        }
                    } else {
                        Timber.i("$TAG Brightness too low ($currentLuxValue Lux < $LUX_THRESHOLD Lux): Camera skipped.")
                    }
                }
                Result.success()
            } else {
                Timber.w("sendLocation failed, skipping email send and photo capture")
                Result.retry()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to execute ShareLocWorker")
            Result.retry()
        }
    }

    /**
     * Reads ambient light level using [Sensor.TYPE_LIGHT] asynchronously.
     * Resumes immediately when the first value is emitted, or defaults to 999f
     * if the sensor is missing or times out (to prevent erroneously skipping camera capture).
     */
    private suspend fun getLightLevel(timeoutMs: Long = 3000L): Float = withContext(Dispatchers.Main) {
        val sensorManager = applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (sensorManager == null) {
            Timber.w("$TAG SensorManager not available. Defaulting lux to 999f")
            return@withContext 999f
        }

        val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (lightSensor == null) {
            Timber.w("$TAG Device has no light sensor (TYPE_LIGHT). Defaulting lux to 999f")
            return@withContext 999f
        }

        val measuredLux = withTimeoutOrNull(timeoutMs.milliseconds) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        if (event?.sensor?.type == Sensor.TYPE_LIGHT && event.values.isNotEmpty()) {
                            val lux = event.values[0]
                            sensorManager.unregisterListener(this)
                            if (continuation.isActive) {
                                continuation.resume(lux)
                            }
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }

                continuation.invokeOnCancellation {
                    sensorManager.unregisterListener(listener)
                }

                val registered = sensorManager.registerListener(
                    listener,
                    lightSensor,
                    SensorManager.SENSOR_DELAY_FASTEST
                )

                if (!registered) {
                    sensorManager.unregisterListener(listener)
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
        }

        if (measuredLux == null) {
            Timber.w("$TAG Light sensor measurement timed out ($timeoutMs ms). Defaulting lux to 999f to allow photo capture.")
            999f
        } else {
            measuredLux
        }
    }

    /**
     * Binds CameraX and captures a photo asynchronously without blocking threads.
     */
    private suspend fun capturePhoto(location: Location?): File? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(applicationContext)
            cameraProviderFuture.addListener({
                val lifecycleOwner = ServiceLifecycleOwner()
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    cameraProvider.unbindAll()
                    lifecycleOwner.start()

                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        imageCapture
                    )

                    val outputDirectory = applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir
                    val photoFile = File(
                        outputDirectory,
                        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis()) + ".jpg"
                    )
                    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                    imageCapture.takePicture(
                        outputOptions,
                        ContextCompat.getMainExecutor(applicationContext),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                Timber.i("$TAG Photo saved successfully: ${photoFile.absolutePath}")
                                cameraProvider.unbindAll()
                                lifecycleOwner.stop()
                                if (continuation.isActive) {
                                    continuation.resume(photoFile)
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                Timber.e(exception, "$TAG Error saving image capture: ${exception.message}")
                                cameraProvider.unbindAll()
                                lifecycleOwner.stop()
                                if (continuation.isActive) {
                                    continuation.resume(null)
                                }
                            }
                        }
                    )
                } catch (e: Exception) {
                    Timber.e(e, "$TAG Failed to setup camera")
                    lifecycleOwner.stop()
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }, ContextCompat.getMainExecutor(applicationContext))
        }
    }

    /**
     * Uploads captured image to server sequentially within worker scope.
     */
    private suspend fun uploadImageToBplaced(
        imageFile: File,
        location: Location?
    ): Response<UploadResponse>? = withContext(Dispatchers.IO) {
        if (!imageFile.exists()) {
            Timber.w("Upload failed: File does not exist (${imageFile.absolutePath})")
            return@withContext null
        }

        try {
            val compressedFile = compressImageFile(imageFile, imageFile, location)
            Timber.i("Compressed file size: ${compressedFile.length()} bytes")

            val requestFile = compressedFile.asRequestBody("image/*".toMediaTypeOrNull())
            val body = MultipartBody.Part.createFormData("image", compressedFile.name, requestFile)
            val titleRequestBody = compressedFile.name.toRequestBody("text/plain".toMediaTypeOrNull())

            val response = NetworkClient.bplacedApiService.uploadLocationWithPhoto(titleRequestBody, body)

            if (response.isSuccessful && response.body() != null) {
                val serverResponse = response.body()!!
                if (serverResponse.status == "success") {
                    Timber.i("Upload success: ${serverResponse.message} | Link: ${serverResponse.url}")
                } else {
                    Timber.w("Server reported upload failure: ${serverResponse.message}")
                }
            } else {
                Timber.e("Server error during upload, HTTP code: ${response.code()}")
            }
            response
        } catch (e: Exception) {
            if (e is retrofit2.HttpException) {
                val errorBody = e.response()?.errorBody()?.string()
                Timber.e("HTTP Error body: $errorBody")
            }
            Timber.e(e, "Network error during photo upload")
            null
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun getCurrentLocation(): Location? {
        val locationManager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(applicationContext)

        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            try {
                val directGpsLocation = withTimeoutOrNull(20.seconds) {
                    suspendCancellableCoroutine { continuation ->
                        val cancellationSignal = CancellationSignal()
                        continuation.invokeOnCancellation { cancellationSignal.cancel() }

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
                    return directGpsLocation
                } else {
                    Timber.w("Direct GPS Location request timed out after 20 seconds. Falling back to FusedLocation.")
                }
            } catch (e: SecurityException) {
                Timber.e(e, "Location permission not granted")
                return null
            } catch (e: Exception) {
                Timber.w(e, "Failed to get direct GPS location, falling back to FusedLocation")
            }
        }

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