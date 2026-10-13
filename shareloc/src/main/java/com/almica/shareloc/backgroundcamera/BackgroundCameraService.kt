package com.almica.shareloc.backgroundcamera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.almica.shareloc.NetworkClient
import com.almica.shareloc.PreferenceManager
import com.almica.shareloc.UploadResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class BackgroundCameraService : Service(), SensorEventListener {

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private val lifecycleOwner = ServiceLifecycleOwner()

    // Handler für das Timing der wiederholten Aufrufe
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isRepeating = false
    private val prefs by lazy { PreferenceManager(this) }
    private val captureIntervalMs get() = prefs.getIntervalMinutes() * 60000L
    // Sensor-Variablen
    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null
    private var currentLuxValue: Float = 0f

    // Das Runnable, das sich immer wieder selbst aufruft
    private val repeatingCaptureRunnable = object : Runnable {
        override fun run() {
            if (isRepeating) {
                if (lightSensor != null)
                    sensorManager.registerListener(this@BackgroundCameraService, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
                // Warte 1 Sekunde auf den Sensorwert, beende dann den Sensor und verarbeite das Foto
                mainHandler.postDelayed({

                    // AUCH HIER: this@BackgroundCameraService nutzen
                    if (lightSensor != null)
                        sensorManager.unregisterListener(this@BackgroundCameraService)
                    Timber.i("$TAG Sensor wieder schlafen gelegt. Wert: $currentLuxValue Lux")

                    if (currentLuxValue >= LUX_THRESHOLD) {
                        capturePhotoInBackground()
                    } else {
                        Timber.i("$TAG Foto übersprungen: Zu dunkel.")
                    }

                    // Nächstes Intervall planen
                    mainHandler.postDelayed(this, captureIntervalMs)

                }, 1000)
            }
        }
    }

    companion object {
        private const val TAG = "BgCameraService"
        private const val CHANNEL_ID = "BackgroundCameraChannel"
        private const val NOTIFICATION_ID = 101

        // Neue Actions für die Steuerung
        const val ACTION_START_REPEATING = "ACTION_START_REPEATING"
        const val ACTION_STOP_REPEATING = "ACTION_STOP_REPEATING"
        // Schwellenwert für ausreichende Helligkeit (in Lux)
        // 10 Lux = Sehr dunkler Raum, 50 Lux = Wohnzimmerbeleuchtung, 500 Lux = Helles Büro/Tageslicht
        const val LUX_THRESHOLD = 30.0f
    }

    override fun onCreate() {
        super.onCreate()
        cameraExecutor = Executors.newSingleThreadExecutor()
        lifecycleOwner.start()
        Timber.i("Interval in Minutes: ${prefs.getIntervalMinutes()}")
        startForeground(NOTIFICATION_ID, createNotification())
        initializeCameraX()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
//        //val customDelayMicroseconds = (0.5 * captureIntervalMs *1000).toInt()
//        //Timber.i("$TAG Custom Delay in Microseconds: $customDelayMicroseconds")
//        if (lightSensor != null) {
//            // Sensor registrieren (SENSOR_DELAY_NORMAL reicht für Intervallaufnahmen völlig aus)
//            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL) // customDelayMicroseconds
//            Timber.i("$TAG Lichtsensor erfolgreich registriert.")
//        } else {
//            Timber.e("$TAG Gerät besitzt keinen Lichtsensor! Es wird ohne Helligkeitsprüfung fotografiert.")
//            // Ausweichlösung: Schwellenwert auf 0 setzen, damit immer fotografiert wird
//            currentLuxValue = 999f
//        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_REPEATING -> {
                if (!isRepeating) {
                    isRepeating = true
                    // Starte die Zeitschleife
                    mainHandler.post(repeatingCaptureRunnable)
                    Timber.i("$TAG Wiederholte Aufnahme gestartet.")
                }
            }
            ACTION_STOP_REPEATING -> {
                stopRepeatingAndService()
            }
        }
        return START_NOT_STICKY
    }

    private fun initializeCameraX() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageCapture)
                Timber.i("$TAG CameraX erfolgreich initialisiert.")
            } catch (e: Exception) {
                Timber.e("$TAG Fehler bei CameraX Bindung $e")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhotoInBackground() {
        val imageCapture = imageCapture ?: run {
            Timber.e("$TAG Kamera noch nicht bereit.")
            return
        }

        val outputDirectory = externalCacheDir ?: cacheDir
        val photoFile = File(
            outputDirectory,
            SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis()) + ".jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    Timber.i("$TAG Foto gespeichert: ${photoFile.absolutePath}")
                    uploadImageToBplaced(photoFile) {response ->
                        Timber.i("$TAG Server Antwort: ${response.body()?.message}")
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Timber.e(exception, "$TAG Fehler beim Speichern: ${exception.message}")
                }
            }
        )
    }

    private fun stopRepeatingAndService() {
        isRepeating = false
        mainHandler.removeCallbacks(repeatingCaptureRunnable)
        Timber.i("$TAG Wiederholte Aufnahme gestoppt.")
        stopSelf() // Beendet den gesamten Service, wenn er nicht mehr gebraucht wird
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Hintergrund-Kamera", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Intervall-Kamera aktiv")
            .setContentText("Fotos werden automatisch im Hintergrund aufgenommen...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LIGHT) {
            currentLuxValue = event.values[0]
            Timber.i("$TAG Aktuelle Helligkeit: $currentLuxValue Lux")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Nicht benötigt
    }

    override fun onDestroy() {
        super.onDestroy()
        isRepeating = false
        mainHandler.removeCallbacks(repeatingCaptureRunnable)
        if (::sensorManager.isInitialized) {
            sensorManager.unregisterListener(this)
        }
        lifecycleOwner.stop()
        cameraExecutor.shutdown()
        Timber.i("$TAG Service zerstört.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun uploadImageToBplaced(imageFile: File, feedBack: (Response<UploadResponse>) -> Unit) {
        if (!imageFile.exists()) {
            Timber.i( "Datei existiert nicht!")
            return
        }
        Timber.i("image: ${imageFile.name}")
        // 1. Datei in RequestBody umwandeln (MIME-Type ermitteln oder allgemein "image/*" verwenden)
        val requestFile = imageFile.asRequestBody("image/*".toMediaTypeOrNull())

        // 2. MultipartBody.Part erstellen. Der Name "image" MUSS exakt mit $_FILES['image'] im PHP übereinstimmen
        val body = MultipartBody.Part.createFormData("image", imageFile.name, requestFile)

        // 3. Im Hintergrund per Coroutine hochladen
        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val titleRequestBody = imageFile.name.toRequestBody("text/plain".toMediaTypeOrNull())
                Timber.i("title: $titleRequestBody")
                val response = NetworkClient.bplacedApiService.uploadLocationWithPhoto(titleRequestBody, body)

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && response.body() != null) {
                        val serverResponse = response.body()!!
                        if (serverResponse.status == "success") {
                            Timber.i( "${serverResponse.message}\nLink: ${serverResponse.url}")
                        } else {
                            Timber.i( "Fehler: ${serverResponse.message}")
                        }
                    } else {
                        Timber.i("Server-Fehler Code: ${response.code()}")
                    }
                }
                feedBack(response)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (e is retrofit2.HttpException) {
                        val errorBody = e.response()?.errorBody()?.string()
                        Timber.e( "Roher Fehler-Text: $errorBody")
                    }
                    Timber.i( "Netzwerkfehler: ${e.localizedMessage}")
                }
            }
        }
    }

}
