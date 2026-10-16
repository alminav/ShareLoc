package com.almica.shareloc.backgroundcamera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.almica.shareloc.NetworkClient
import com.almica.shareloc.PreferenceManager
import com.almica.shareloc.compressImageFile
import com.almica.shareloc.getDeviceName
import com.almica.shareloc.sendLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

class BackgroundCameraService : LifecycleService() {

    private var captureJob: Job? = null
    private val prefs by lazy { PreferenceManager(this) }

    private val captureIntervalMs: Long
        get() = maxOf(5_000L, (prefs.getBackgroundCameraIntervalMinutes() * 60_000f).toLong())

    private val locationManager: LocationManager by lazy {
        getSystemService(LOCATION_SERVICE) as LocationManager
    }

    override fun onCreate() {
        super.onCreate()

        val hasCameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCameraPermission) {
            Timber.e("$TAG CAMERA permission is missing. Cannot start BackgroundCameraService.")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        createNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    )
                } else {
                    startForeground(NOTIFICATION_ID, createNotification())
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG Failed fallback startForeground")
            }
            stopSelf()
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG Exception during startForeground")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val hasCameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCameraPermission) {
            Timber.e("$TAG CAMERA permission not granted in onStartCommand. Stopping service.")
            stopRepeatingAndService()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START_REPEATING -> startRepeatingCapture()
            ACTION_STOP_REPEATING -> stopRepeatingAndService()
        }
        return START_NOT_STICKY
    }

    private fun startRepeatingCapture() {
        captureJob?.cancel()

        captureJob = lifecycleScope.launch {
            Timber.i("$TAG Wiederholte Aufnahme gestartet. Intervall: ${captureIntervalMs / 1000}s")
            while (isActive) {
                // 1. Fresh ambient light check on every cycle
                val lux = getLightLevel()
                Timber.i("$TAG Aktuelle Helligkeit: $lux Lux")

                if (lux >= LUX_THRESHOLD) {
                    capturePhotoAndProcess()
                } else {
                    Timber.i("$TAG Foto übersprungen: Zu dunkel ($lux Lux).")
                }

                // Wait for next interval
                delay(captureIntervalMs.milliseconds)
            }
        }
    }

    private fun stopRepeatingAndService() {
        captureJob?.cancel()
        captureJob = null
        Timber.i("$TAG Wiederholte Aufnahme gestoppt.")
        stopSelf()
    }

    private suspend fun capturePhotoAndProcess() {
        val cameraProvider = getProcessCameraProvider() ?: return

        val outputDirectory = externalCacheDir ?: cacheDir
        val photoFile = File(
            outputDirectory,
            SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis()) + ".jpg"
        )

        try {
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, imageCapture)

            val savedFile = suspendCancellableCoroutine { continuation ->
                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
                imageCapture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(this@BackgroundCameraService),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            if (continuation.isActive) continuation.resume(photoFile)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Timber.e(exception, "$TAG Fehler beim Speichern des Fotos")
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                )
            }

            if (savedFile != null && savedFile.exists()) {
                Timber.i("$TAG Foto gespeichert: ${savedFile.absolutePath}")
                uploadImage(savedFile)
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG Fehler während des Aufnahmeablaufs")
        } finally {
            // Unbind camera hardware to save battery
            cameraProvider.unbindAll()
            Timber.i("$TAG Kamera-Hardware entkoppelt.")
        }
    }

    private suspend fun getProcessCameraProvider(): ProcessCameraProvider? =
        suspendCancellableCoroutine { continuation ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener({
                try {
                    continuation.resume(cameraProviderFuture.get())
                } catch (e: Exception) {
                    Timber.e(e, "$TAG Fehler beim Kamera-Provider Setup")
                    if (continuation.isActive) continuation.resume(null)
                }
            }, ContextCompat.getMainExecutor(this))
        }

    private suspend fun uploadImage(imageFile: File) {
        withContext(Dispatchers.IO) {
            try {
                val temperature = getBatteryTemperature(applicationContext)
                val currentLocation = locationManager.awaitCurrentLocation(applicationContext)
                Timber.i("$TAG Upload Location: $currentLocation")

                val compressedFile = compressImageFile(imageFile, imageFile, currentLocation)
                val requestFile = compressedFile.asRequestBody("image/*".toMediaTypeOrNull())
                val body = MultipartBody.Part.createFormData("image", compressedFile.name, requestFile)
                val titleRequestBody = compressedFile.name.toRequestBody("text/plain".toMediaTypeOrNull())

                val response = NetworkClient.bplacedApiService.uploadLocationWithPhoto(titleRequestBody, body)
                if (response.isSuccessful && response.body() != null) {
                    val serverResponse = response.body()!!
                    Timber.i("$TAG Upload erfolgreich: ${serverResponse.message} ${serverResponse.url}")
                    val lat = currentLocation?.latitude ?: 0.0
                    val lon = currentLocation?.longitude ?: 0.0
                    val alt = currentLocation?.altitude ?: 0.0
                    val deviceName = getDeviceName()
                    serverResponse.url?.let {
                        sendLocation(deviceName, it,
                            lat, lon, altitude = alt, temperature = temperature)
                    }
                } else {
                    Timber.w("$TAG Server-Fehler Code: ${response.code()}")
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG Upload fehlgeschlagen")
            } finally {
                // Delete photo file to avoid clogging device storage
                if (imageFile.exists()) {
                    imageFile.delete()
                }
            }
        }
    }

    private suspend fun getLightLevel(timeoutMs: Long = 3000L): Float = withContext(Dispatchers.Main) {
        val sensorManager = applicationContext.getSystemService(SENSOR_SERVICE) as? SensorManager
            ?: return@withContext 999f

        val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
            ?: return@withContext 999f

        val measuredLux = withTimeoutOrNull(timeoutMs.milliseconds) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        if (event?.sensor?.type == Sensor.TYPE_LIGHT && event.values.isNotEmpty()) {
                            val lux = event.values[0]
                            sensorManager.unregisterListener(this)
                            if (continuation.isActive) continuation.resume(lux)
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

                if (!registered && continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }

        measuredLux ?: 999f
    }

    fun getBatteryTemperature(context: Context): Float {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        // Temperature is in tenths of a degree Celsius (e.g., 305 for 30.5°C)
        val celsius = temp / 10f
        Timber.i("Battery Temperature: $celsius°C")
        return celsius
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Hintergrund-Kamera", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Intervall-Kamera aktiv")
            .setContentText("Fotos werden automatisch im Hintergrund aufgenommen...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        captureJob?.cancel()
        super.onDestroy()
        Timber.i("$TAG Service zerstört.")
    }

    companion object {
        private const val TAG = "BgCameraService"
        private const val CHANNEL_ID = "BackgroundCameraChannel"
        private const val NOTIFICATION_ID = 101

        const val ACTION_START_REPEATING = "ACTION_START_REPEATING"
        const val ACTION_STOP_REPEATING = "ACTION_STOP_REPEATING"
        const val LUX_THRESHOLD = 30.0f
    }
}

@SuppressLint("MissingPermission")
suspend fun LocationManager.awaitCurrentLocation(
    context: Context,
    provider: String = LocationManager.GPS_PROVIDER
): Location? = withContext(Dispatchers.Main.immediate) {
    suspendCancellableCoroutine { continuation ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cancellationSignal = CancellationSignal()
            getCurrentLocation(
                provider,
                cancellationSignal,
                ContextCompat.getMainExecutor(context)
            ) { location ->
                if (continuation.isActive) continuation.resume(location)
            }

            continuation.invokeOnCancellation {
                cancellationSignal.cancel()
            }
        } else {
            val locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    removeUpdates(this)
                    if (continuation.isActive) continuation.resume(location)
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
            }

            requestLocationUpdates(provider, 0L, 0f, locationListener)

            continuation.invokeOnCancellation {
                removeUpdates(locationListener)
            }
        }
    }
}