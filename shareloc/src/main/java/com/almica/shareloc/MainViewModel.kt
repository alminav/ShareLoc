package com.almica.shareloc

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.almica.shareloc.backgroundcamera.BackgroundCameraService
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.util.concurrent.TimeUnit
import timber.log.Timber
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val networkMonitor = NetworkMonitor(application)
    private val workManager = WorkManager.getInstance(application)
    private val prefs = PreferenceManager(application)
    val isShareLocActive: StateFlow<Boolean> = networkMonitor.isShareLocActive
    // Track work status more robustly by checking for any active work with the tag
    val isWorkerRunning: StateFlow<Boolean> = workManager
        .getWorkInfosByTagFlow(Constants.WORK_TAG)
        .map { list ->
            list.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    private val _locationList = MutableStateFlow<List<LocationItem>>(emptyList())
    val locationList: StateFlow<List<LocationItem>> = _locationList.asStateFlow()

    private val _locationState = MutableStateFlow<Location?>(null)
    val locationState: StateFlow<Location?> = _locationState.asStateFlow()

    private val _notificationsEnabled = MutableStateFlow(prefs.getNetworkStateNotificationsEnabled())
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.setNotificationsEnabled(enabled)
        _notificationsEnabled.value = enabled
    }

    val workerLastRunTime: StateFlow<Long> = workManager
        .getWorkInfosByTagFlow(Constants.WORK_TAG)
        .map { list ->
            if (list.isNotEmpty()) System.currentTimeMillis() else 0L
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0L
        )

    val sendLocationCount: StateFlow<Int> = workManager
        .getWorkInfosByTagFlow(Constants.WORK_TAG)
        .map {
            prefs.getSendLocationCount()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = prefs.getSendLocationCount()
        )

    val serviceRestartCount: StateFlow<Int> = workManager
        .getWorkInfosByTagFlow(Constants.WORK_TAG)
        .map {
            prefs.getServiceRestartCount()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = prefs.getSendLocationCount()
        )

    fun startWorker() {
        val interval = prefs.getIntervalMinutes()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<ShareLocWorker>(
            interval, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .addTag(Constants.WORK_TAG)
            .build()

        // Use unique periodic work to avoid multiple instances and ensure correct status tracking
        workManager.enqueueUniquePeriodicWork(
            Constants.WORK_TAG,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
        prefs.resetSendLocationCount()
        prefs.resetServiceRestartCount()

        if (prefs.isBackgroundCameraEnabled()) {
            startRepeatingCamera()
        }
    }

    fun stopWorker() {
        // Cancel by tag ensures all instances (unique or tagged) are stopped
        workManager.cancelAllWorkByTag(Constants.WORK_TAG)
        stopRepeatingCamera()
    }

    fun updatePreferences(
        phoneNumber: String,
        partnerPhone: String,
        interval: Long,
        email: String,
        backgroundCameraEnabled: Boolean,
        bgCameraInterval: Float,
        backgroundCameraSoundEnabled: Boolean = true
    ) {
        val oldInterval = prefs.getIntervalMinutes()
        val oldBgCamEnabled = prefs.isBackgroundCameraEnabled()
        val oldBgCamInterval = prefs.getBackgroundCameraIntervalMinutes()

        prefs.setPhoneNumber(phoneNumber)
        prefs.setPartnerPhoneNumber(partnerPhone)
        prefs.setIntervalMinutes(interval)
        prefs.setEmailAddress(email)
        prefs.setBackgroundCameraEnabled(backgroundCameraEnabled)
        prefs.setBackgroundCameraIntervalMinutes(bgCameraInterval)
        prefs.setBackgroundCameraSoundEnabled(backgroundCameraSoundEnabled)

        // If worker is running and interval changed, restart it
        if (isWorkerRunning.value && oldInterval != interval) {
            startWorker()
        }

        if (backgroundCameraEnabled) {
            if (!oldBgCamEnabled || oldBgCamInterval != bgCameraInterval) {
                startRepeatingCamera()
            }
        } else if (oldBgCamEnabled) {
            stopRepeatingCamera()
        }
    }

    fun getPartnerPhoneNumber() = prefs.getPartnerPhoneNumber()
    fun getPhoneNumber() = prefs.getPhoneNumber()
    fun getIntervalMinutes() = prefs.getIntervalMinutes()
    fun getEmailAddress() = prefs.getEmailAddress()
    fun isBackgroundCameraEnabled() = prefs.isBackgroundCameraEnabled()
    fun getBackgroundCameraIntervalMinutes() = prefs.getBackgroundCameraIntervalMinutes()
    fun isBackgroundCameraSoundEnabled() = prefs.isBackgroundCameraSoundEnabled()

    fun testWorkerImmediately() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val testRequest = OneTimeWorkRequestBuilder<ShareLocWorker>()
            .setConstraints(constraints)
            .addTag(Constants.WORK_TAG)
            .addTag("test_worker")
            .build()
        workManager.enqueue(testRequest)
    }

    fun executeLocationCleanup(feedBack: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = NetworkClient.bplacedApiService.deleteAllLocations()
                if (response.isSuccessful && response.body() != null) {
                    val apiResponse = response.body()!!
                    if (apiResponse.status == "success") {
                        // Updating this StateFlow will trigger the collector in MainActivity
                        _locationList.value = emptyList()
                        val msg = apiResponse.message
                        feedBack(msg)
                        Timber.i(msg)
                    } else {
                        val msg = "Fehler: ${apiResponse.message}"
                        feedBack(msg)
                        Timber.e(msg)
                    }
                } else {
                    val msg = "Server-Fehler: ${response.code()}"
                    feedBack(msg)
                    Timber.e(msg)
                }
            } catch (e: Exception) {
                val msg = "Netzwerkfehler: ${e.localizedMessage}"
                feedBack(msg)
                Timber.e(msg)
            }
        }
    }

    fun executeLocationDeleteByFilter(filter: String, feedBack: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = NetworkClient.bplacedApiService.deleteLocationByFilter(filter)
                if (response.isSuccessful && response.body() != null) {
                    val apiResponse = response.body()!!
                    if (apiResponse.status == "success") {
                        // Updating this StateFlow will trigger the collector in MainActivity
                        _locationList.value = emptyList()
                        val msg = apiResponse.message
                        feedBack(msg)
                        Timber.i(msg)
                    } else {
                        val msg = "Fehler: ${apiResponse.message}"
                        feedBack(msg)
                        Timber.e(msg)
                    }
                } else {
                    val msg = "Server-Fehler: ${response.code()}"
                    feedBack(msg)
                    Timber.e(msg)
                }
            } catch (e: Exception) {
                val msg = "Netzwerkfehler: ${e.localizedMessage}"
                feedBack(msg)
                Timber.e(e, msg)
            }
        }
    }

    fun executeDeleteOldRecords(days: Int, feedBack: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Parameter 'days' wird hier übergeben
                val response = NetworkClient.bplacedApiService.deleteOldRecords(days)

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && response.body() != null) {
                        val apiResponse = response.body()!!

                        if (apiResponse.status == "success") {
                            val msg = apiResponse.message
                            feedBack(msg)
                            Timber.i(msg)
                            //fetchLocationsFromBplaced()
                        } else {
                            val msg = "Old records delete error: ${apiResponse.message}"
                            feedBack(msg)
                            Timber.e(msg)
                        }
                    } else {
                        val msg = "Server-Fehler: ${response.code()}"
                        feedBack(msg)
                        Timber.e(msg)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val msg = "Netzwerkfehler: ${e.localizedMessage}"
                    feedBack(msg)
                    Timber.e( "Netzwerkfehler: ${e.localizedMessage}")
                }
            }
        }
    }


    // Wir setzen den Standardwert auf null, falls kein Limit übergeben wird
    fun executeFetchLocationsCount(limit: Int? = null, feedBack: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Hier wird das Limit an die Retrofit-Schnittstelle übergeben
                val response = NetworkClient.bplacedApiService.getLocationsCount(count = limit)
                Timber.i("Anzahl der Orte: ${response.body()?.data?.size}")
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && response.body() != null) {
                        val apiResponse = response.body()!!

                        if (apiResponse.status == "success" && apiResponse.data != null) {
                            // Updating this StateFlow will trigger the collector in MainActivity
                            _locationList.value = apiResponse.data
                            Timber.i("Anzahl geladener Orte: ${apiResponse.data.size}")
                            for (location in apiResponse.data) {
                                Timber.i("Ort: ${location.title} (${location.latitude}, ${location.longitude}, ${location.altitude}, ${location.temperature})")
                            }
                        } else {
                            Timber.i("Fehler: ${apiResponse.message}")
                        }
                    } else {
                        val msg = "Server-Fehler: ${response.code()}"
                        feedBack(msg)
                        Timber.e(msg)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val msg = "Netzwerkfehler: ${e.localizedMessage}"
                    feedBack(msg)
                    Timber.e( "Netzwerkfehler: ${e.localizedMessage}")
                }
            }
        }
    }

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
        viewModelScope.launch(Dispatchers.IO) {
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

    fun saveBitmapToGallery(context: Context, bitmap: Bitmap, fileName: String,
                            feedBack: (Boolean) -> Unit) {
        val contentResolver = context.contentResolver

        // 1. Set up the metadata metadata for the image file
        val imageDetails = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$fileName.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")

            // Android 10 (API 29) and above uses Scoped Storage paths
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Locations")
                put(MediaStore.Images.Media.IS_PENDING, 1) // 1 means true / processing
            }
        }

        // 2. Select the external storage collection
        val collectionUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        viewModelScope.launch(Dispatchers.IO) {
            // 3. Insert the metadata row to grab a destination URI
            val imageUri = contentResolver.insert(collectionUri, imageDetails)

            // 4. Open an OutputStream and compress the bitmap into it
            imageUri?.let { uri ->
                try {
                    val isSuccess = contentResolver.openOutputStream(uri)?.use { stream ->
                        // Compress the bitmap into JPEG format with 95% quality
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                    } ?: false
                    feedBack(isSuccess)
                    if (isSuccess) {
                        // 5. Release the pending status so other apps (like the Gallery) can read it
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            imageDetails.clear()
                            imageDetails.put(MediaStore.Images.Media.IS_PENDING, 0)
                            contentResolver.update(uri, imageDetails, null, null)
                        }
                    } else {
                        contentResolver.delete(uri, null, null)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error saving bitmap to gallery")
                    // Clean up the failed database insertion
                    contentResolver.delete(uri, null, null)
                }
            }
        }
    }
    fun getDeviceName(): String {
        return prefs.getDeviceName()
    }
    @androidx.annotation.RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun fetchCurrentLocation(context: Context) {
        viewModelScope.launch {
            try {
                val client = LocationServices.getFusedLocationProviderClient(context)
                val request = CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .build()
                var loc = client.getCurrentLocation(request, null).await()
                if (loc == null) {
                    loc = client.lastLocation.await()
                }
                _locationState.value = loc
                Timber.i("Fetched location: $loc")
            } catch (e: SecurityException) {
                Timber.e(e, "Location permission missing")
                _locationState.value = null
            } catch (e: Exception) {
                Timber.e(e, "Error fetching location")
                _locationState.value = null
            }
        }
    }
    fun startRepeatingCamera() {
        val context = getApplication<Application>()
        val hasCameraPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCameraPermission) {
            Timber.w("Cannot start BackgroundCameraService: CAMERA permission is not granted.")
            return
        }

        val intent = Intent(context, BackgroundCameraService::class.java).apply {
            action = BackgroundCameraService.ACTION_START_REPEATING
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start BackgroundCameraService foreground service")
        }
    }
    fun stopRepeatingCamera() {
        val context = getApplication<Application>()
        val intent = Intent(context, BackgroundCameraService::class.java).apply {
            action = BackgroundCameraService.ACTION_STOP_REPEATING
        }
        ContextCompat.startForegroundService(context, intent)
    }
    override fun onCleared() {
        super.onCleared()
        networkMonitor.unregister()
        stopRepeatingCamera()
    }

    fun updatePartnerPhoneNumber(partnerPhone: String) {
        prefs.setPartnerPhoneNumber(partnerPhone)
    }
}
