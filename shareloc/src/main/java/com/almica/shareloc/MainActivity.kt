package com.almica.shareloc

import android.Manifest
import android.annotation.SuppressLint
import android.app.ComponentCaller
import android.content.ActivityNotFoundException
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.telephony.SubscriptionManager
import com.google.android.gms.common.api.ApiException
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Start
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.graphics.BitmapFactory
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Language
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import timber.log.Timber
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringArrayResource
import com.google.android.gms.auth.api.identity.Identity

/**
 * Öffnet die angegebenen Koordinaten in Google Maps oder einer alternativen Karten-App/im Browser.
 *
 * @param context Der Context zum Starten des Intents
 * @param latitude Breitengrad
 * @param longitude Längengrad
 * @param label Optionaler Name/Titel für den Stecknadel-Marker
 * @param zoom Optionaler Zoom-Level (1 bis 21, z. B. 15 für Straßenansicht)
 */
fun openCoordinatesInGoogleMaps(
    context: Context,
    latitude: Double,
    longitude: Double,
    label: String? = null,
    zoom: Int? = null
) {
    val encodedLabel = if (!label.isNullOrBlank()) "(${Uri.encode(label)})" else ""
    val zoomParam = if (zoom != null && zoom in 1..21) "&z=$zoom" else ""
    val gmmIntentUri = "geo:$latitude,$longitude?q=$latitude,$longitude$encodedLabel$zoomParam".toUri()

    val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
        setPackage("com.google.android.apps.maps") // Zunächst bevorzugt Google Maps ansprechen
    }

    try {
        context.startActivity(mapIntent)
    } catch (_: ActivityNotFoundException) {
        // Fallback 1: Versuchen, den Intent mit einer beliebigen anderen Karten-App zu öffnen
        mapIntent.setPackage(null)
        try {
            context.startActivity(mapIntent)
        } catch (_: ActivityNotFoundException) {
            // Fallback 2: Im Browser öffnen, falls gar keine Karten-App installiert ist
            val webUrl = if (zoom != null && zoom in 1..21) {
                "https://www.google.com/maps/@$latitude,$longitude,${zoom}z"
            } else {
                "https://www.google.com/maps/search/?api=1&query=$latitude,$longitude"
            }
            Timber.i("Opening web URL: $webUrl")
            val webIntent = Intent(Intent.ACTION_VIEW, webUrl.toUri())
            try {
                context.startActivity(webIntent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "Keine Karten-App oder Browser gefunden", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    // 1. Launcher für das Ergebnis registrieren
    private val phoneNumberHintIntentResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                try {
                    val phoneNumber = Identity.getSignInClient(this)
                        .getPhoneNumberFromIntent(result.data)
                    val prefs = PreferenceManager(application)
                    prefs.setPhoneNumber(phoneNumber)
                    // 'phoneNumber' enthält nun die Nummer im E.164 Format (z.B. +49123456789)
                    Timber.i("Erhaltene Nummer: $phoneNumber")
                } catch (e: Exception) {
                    Timber.i("Abrufen der Telefonnummer fehlgeschlagen ${e.message}")
                }
            }
        }

    // 2. Den Prozess starten
    private fun requestPhoneNumberHint() {
        val prefs = PreferenceManager(application)
        if (prefs.getPhoneNumber().isNotBlank()) {
            Timber.i("requestPhoneNumberHint: Telefonnummer bereits gespeichert (${prefs.getPhoneNumber()}), Überspringe Abfrage.")
            return
        }

        val request = com.google.android.gms.auth.api.identity.GetPhoneNumberHintIntentRequest.builder().build()

        Identity.getSignInClient(this)
            .getPhoneNumberHintIntent(request)
            .addOnSuccessListener { pendingIntent ->
                try {
                    val intentSenderRequest = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                    phoneNumberHintIntentResultLauncher.launch(intentSenderRequest)
                } catch (e: Exception) {
                    Timber.w("requestPhoneNumberHint: Fehler beim Starten des Intents ${e.message}")
                }
            }
            .addOnFailureListener { e ->
                if (e is ApiException) {
                    Timber.w("requestPhoneNumberHint: Google Play Services liefert keine Telefonnummer auf diesem Gerät (Code ${e.statusCode}): ${e.message}")
                } else {
                    Timber.w("requestPhoneNumberHint: API nicht verfügbar ${e.message}")
                }
                tryFallbackPhoneNumber(prefs)
            }
    }

    fun tryFallbackPhoneNumber(prefs: PreferenceManager) {
        if (prefs.getPhoneNumber().isNotBlank()) return
        try {
            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_PHONE_NUMBERS
                ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_SMS
                ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECEIVE_SMS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_PHONE_STATE
                ) == PackageManager.PERMISSION_GRANTED ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_PHONE_NUMBERS
                ) == PackageManager.PERMISSION_GRANTED) ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECEIVE_SMS
                ) == PackageManager.PERMISSION_GRANTED
            }

            if (hasPermission) {
                val telephonyManager = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
                val number = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val subscriptionManager = getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                    subscriptionManager?.getPhoneNumber(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID)
                } else {
                    @Suppress("DEPRECATION", "MissingPermission")
                    telephonyManager?.line1Number
                }
                if (!number.isNullOrBlank()) {
                    prefs.setPhoneNumber(number)
                    Timber.i("Fallback Telefonnummer von TelephonyManager erhalten: $number")
                } else {
                    Timber.i("TelephonyManager/SubscriptionManager hat keine Telefonnummer zurückgegeben.")
                }
            } else {
                Timber.i("Keine Telefon-Berechtigung für Fallback vorhanden.")
            }
        } catch (e: SecurityException) {
            Timber.w("Fallback-Abfrage der Telefonnummer aufgrund fehlender/eingeschränkter Berechtigung nicht möglich: ${e.message}")
        } catch (e: Exception) {
            Timber.w(e, "Fallback-Abfrage der Telefonnummer fehlgeschlagen")
        }
    }
    lateinit var viewModel: MainViewModel
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPhoneNumberHint()
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    viewModel = viewModel()
                    MainScreen(viewModel)
                }
            }
        }
    }

    private val smsReceiver = SmsReceiver()
    // DIESE METHODE WIRD GESTARTET, WENN DIE NACHRICHT MATCHT
    private fun onMessageMatched(sender: String?, messageBody: String) {
        Timber.i("Match gefunden von $sender! Text: $messageBody")
        try {
            if (messageBody.contains(getString(R.string.position), ignoreCase = true)) {
                Timber.i("Position found")
                var latitude: Double? = null
                var longitude: Double? = null
                messageBody.split(" ").forEach { item ->
                    if (item.endsWith("°")) {
                        val num = item.replace("°", "").toDoubleOrNull()
                        if (num != null) {
                            if (latitude == null) {
                                Timber.i("Latitude: $num")
                                latitude = num
                            } else {
                                Timber.i("Longitude: $num")
                                longitude = num
                            }
                        } else {
                            Timber.i("Fehlerhafte Koordinaten: $item")
                        }
                    }
                }
                val lat = latitude
                val lng = longitude
                if (lat != null && lng != null) {
                    openCoordinatesInGoogleMaps(
                        context = this,
                        lat,
                        lng,
                        getString(R.string.i_am_here),
                        zoom = 10
                    )
                }

            } else if (messageBody.contains(getString(R.string.turn_on_location_sharing), ignoreCase = true)) {
                Timber.i("MainActivity viewModel.startWorker()")
                if (!viewModel.isWorkerRunning.value)
                    viewModel.startSharelocWorker()
                else
                    Timber.i("MainActivity Worker already running")
            } else if (messageBody.contains(getString(R.string.turn_off_location_sharing), ignoreCase = true)) {
                Timber.i("MainActivity viewModel.stopWorker()")
                viewModel.stopWorker()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error processing SMS in onMessageMatched")
        }
    }

    override fun onNewIntent(intent: Intent, caller: ComponentCaller) {
        super.onNewIntent(intent, caller)
        val latitude = intent.getDoubleExtra(Constants.SMS_LATITUDE, 0.0)
        val longitude = intent.getDoubleExtra(Constants.SMS_LONGITUDE, 0.0)
        Timber.i("MainActivity onNewIntent latitude: $latitude, longitude: $longitude")
        if (latitude != 0.0 && longitude != 0.0) {
            openCoordinatesInGoogleMaps(
                this,
                latitude,
                longitude,
                getString(R.string.i_am_here),
                zoom = 10
            )
        }
    }


    override fun onStart() {
        super.onStart()
        Timber.i("MainActivity onStart")
        // Interface mit der MainActivity verknüpfen
        smsReceiver.setSmsListener { sender, messageBody ->
            Timber.i("MainActivity onSmsReceived Text: $messageBody")
            onMessageMatched(sender, messageBody)
        }

        // Receiver für eingehende SMS registrieren
        val intentFilter = IntentFilter("android.provider.Telephony.SMS_RECEIVED")
        registerReceiver(smsReceiver, intentFilter)
    }

    override fun onStop() {
        super.onStop()
        // Wichtig, um Memory Leaks und Akkuverbrauch zu verhindern
        unregisterReceiver(smsReceiver)
    }
}



@RequiresApi(Build.VERSION_CODES.Q)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val isShareLocActive by viewModel.isShareLocActive.collectAsStateWithLifecycle(initialValue = false)
    val isWorkerRunning by viewModel.isWorkerRunning.collectAsStateWithLifecycle(initialValue = false)
    val workerLastRunTime by viewModel.workerLastRunTime.collectAsStateWithLifecycle(initialValue = 0L)
    val sendLocationCount by viewModel.sendLocationCount.collectAsStateWithLifecycle(initialValue = 0)
    val serviceRestartCount by viewModel.serviceRestartCount.collectAsStateWithLifecycle(initialValue = 0)
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsStateWithLifecycle(initialValue = true)

    var lastStatusChangeTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showPhoneListDialog by remember { mutableStateOf(false) }

    val locationList by viewModel.locationList.collectAsStateWithLifecycle()
    var showLocationDialog by remember { mutableStateOf(Pair(false, false)) }
    var showSmsDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var hasRequiredPermissions by remember { mutableStateOf(false) }
    var showBackgroundRationale by remember { mutableStateOf(false) }
    var showGeoCamera by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showGeoCamera = true
        } else {
            val msg = "Kamera-Berechtigung verweigert"
            scope.launch {
                snackbarHostState.showSnackbar(msg)
            }
        }
    }

    val backgroundPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Timber.i("Background location permission granted: $isGranted")
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasRequiredPermissions = results.values.all { it }

        val phoneNumbersGranted = results[Manifest.permission.READ_PHONE_NUMBERS] == true ||
                results[Manifest.permission.READ_PHONE_STATE] == true
        if (phoneNumbersGranted) {
            (context as? MainActivity)?.tryFallbackPhoneNumber(PreferenceManager(context))
        }

        val foregroundLocationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                results[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (foregroundLocationGranted) {
            val hasBackground = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasBackground) {
                showBackgroundRationale = true
            }
        }
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf<String>()
        // Standard permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            permissions.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        permissions.add(Manifest.permission.SEND_SMS)
        permissions.add(Manifest.permission.RECEIVE_SMS)
        permissions.add(Manifest.permission.CAMERA)

        // Foreground location permissions
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        permissions.add(Manifest.permission.INTERNET)
        permissions.add(Manifest.permission.ACCESS_NETWORK_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_CAMERA)
        }

        permissionLauncher.launch(permissions.toTypedArray())

        // Note: ACCESS_BACKGROUND_LOCATION should ideally be requested
        // only after foreground permissions are granted, and often requires
        // a separate UI explanation to the user as per Google Play policies.
    }


    LaunchedEffect(isShareLocActive, isWorkerRunning, workerLastRunTime) {
        lastStatusChangeTime = System.currentTimeMillis()
    }

    // Auto-notification when live status becomes active
    LaunchedEffect(isShareLocActive) {
        if (notificationsEnabled && isShareLocActive && hasRequiredPermissions) {
            sendShareLocNotification(context)
        }
    }

    // When location list is updated, if it's not empty, show the dialog.
    // The dialog itself handles resetting showLocationDialog to false via onDismiss.
    // If you want to prevent it showing multiple times for the SAME list,
    // you would typically clear the list in the ViewModel after dismissing.

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_title)) },
                actions = {
/*                    IconButton(onClick = {
                        viewModel.executeFetchLocationsCount(99999) { msg ->
                            Timber.i("Fetch locations count: $msg")
                        }
                        showLocationDialog = Pair(true, true)
                    }) {
                        Icon(
                            imageVector = Icons.Default.PhotoCamera,
                            contentDescription = "Foto aufnehmen und hochladen"
                        )
                    }*/
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                    IconButton(onClick = {
                        val webHomeIntentUri = "http://almica.bplaced.net/".toUri()
                        val webIntent = Intent(Intent.ACTION_VIEW, webHomeIntentUri)
                        try {
                            context.startActivity(webIntent)
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(context, "No browser found", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(imageVector = Icons.Default.Language, contentDescription = "Web Portal")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            MainScreenContent(
                viewModel.getDeviceName(),
                viewModel.getPartnerPhoneNumber(),
                isShareLocActive = isShareLocActive,
                isWorkerRunning = isWorkerRunning,
                workerInterval = viewModel.getIntervalMinutes(),
                sendLocationCount = sendLocationCount,
                serviceRestartCount = serviceRestartCount,
                notificationsEnabled = notificationsEnabled,
                lastStatusChangeTime = lastStatusChangeTime,
                onNotificationsChanged = { viewModel.setNotificationsEnabled(it) },
                onCheckStatus = {
                    if (isShareLocActive && hasRequiredPermissions) {
                        sendShareLocNotification(context)
                    }
                    lastStatusChangeTime = System.currentTimeMillis()
//                    val intent = Intent(context, BplacedActivity::class.java)
//                    context.startActivity(intent)
                },
                onStartWorker = {
                    viewModel.startSharelocWorker()
                },
                onStopWorker = { viewModel.stopWorker() },
                onSendLocationNow = {
                    viewModel.testWorkerImmediately()
                    lastStatusChangeTime = System.currentTimeMillis()
                },
                onFetchLocations = { limit ->
                    Timber.i("Fetch locations: $limit")
                    viewModel.executeFetchLocationsCount(limit) { msg ->
                        Timber.i("Fetch locations count: $msg")
                    }
                    showLocationDialog = Pair(true, false)
                },
                onSendSmsNow = {
                    showSmsDialog = true
                },
                onDeleteOldRecords = {days ->
                    if (days < 1)
                        viewModel.executeLocationCleanup { msg ->
                            scope.launch {
                                snackbarHostState.showSnackbar(msg)
                            }
                        }
                    else {
                        viewModel.executeDeleteOldRecords(
                            days,
                            feedBack = { msg ->
                                scope.launch {
                                    snackbarHostState.showSnackbar(msg)
                                }
                            },
                        )
                    }
                }, onTakePicture = {
                    val hasCameraPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED

                    if (hasCameraPermission) {
                        showGeoCamera = true
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            )
        }
    }

    if (showGeoCamera) {
        GeoCameraScreen(
            onDismiss = { msgPair, link, location ->
                Timber.i("GeoCamera dismissed link: $link")
                Timber.i("GeoCamera dismissed fileName: ${msgPair?.first}")
                scope.launch {
                    msgPair?.let { snackbarHostState.showSnackbar(msgPair.first + " " + msgPair.second) }
                }
                Timber.i("GeoCamera dismissed photo link: $link")
                val prefs = PreferenceManager(context)
                link?.let {
                    if (isWorkerRunning) {
                        prefs.setImageUrl(it)
                        viewModel.testWorkerImmediately()
                        lastStatusChangeTime = System.currentTimeMillis()
                    } else {
                        val recipientEmail = prefs.getEmailAddress()
                        if (location != null && recipientEmail.isNotBlank() && link.isNotEmpty()) {
                            val fileName = link.replace("http://almica.bplaced.net/uploads/", "")
                            Timber.i("Sending email with image: $fileName and location: ${location.latitude}, ${location.longitude} to $recipientEmail")
                            scope.launch {
                                sendEmail(fileName, recipientEmail, location.latitude, location.longitude)
                            }
                        }
                    }
                }
                showGeoCamera = false
            }
        )
    }
    if (showSettingsDialog) {
        SettingsDialog(
            currentPhoneNumber = viewModel.getPhoneNumber(),
            currentPartnerPhoneNumber = viewModel.getPartnerPhoneNumber(),
            currentInterval = viewModel.getIntervalMinutes(),
            currentEmail = viewModel.getEmailAddress(),
            currentBackgroundCameraEnabled = viewModel.isBackgroundCameraEnabled(),
            currentBackgroundCameraInterval = viewModel.getBackgroundCameraIntervalMinutes(),
            currentBackgroundCameraSoundEnabled = viewModel.isBackgroundCameraSoundEnabled(),
            onDismiss = { showSettingsDialog = false },
            onSave = { phone, partnerPhone, interval, email, backgroundCameraEnabled, bgCameraInterval, bgCameraSoundEnabled ->
                    if (backgroundCameraEnabled) {
                        val hasCameraPermission = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!hasCameraPermission) {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }
                    viewModel.updatePreferences(
                        phone,
                        partnerPhone,
                        interval,
                        email,
                        backgroundCameraEnabled,
                        bgCameraInterval,
                        bgCameraSoundEnabled
                    )
                    showSettingsDialog = false
                },
            onPhoneList = {
                showPhoneListDialog = true
                showSettingsDialog = false
        })
    }

    if (showPhoneListDialog) {
        PhonePreferencesScreen(
            onNavigateBack = { showPhoneListDialog = false },
            onNumberSelected = { partnerNumber ->
                Timber.i("Selected number: $partnerNumber")
                viewModel.updatePartnerPhoneNumber(
                    partnerPhone = partnerNumber
                )
                showPhoneListDialog = false
            },
            onBack = { showPhoneListDialog = false }
        )
    }
    if (showLocationDialog.first) {
        LocationListDialog(
            deviceName = viewModel.getDeviceName(),
            locations = locationList,
            onlyPhotos = showLocationDialog.second,
            onDismiss = {
                showLocationDialog = (Pair(false, false))
            },
            onCleanup = {filter ->
                Timber.i("Filter: $filter")
                if (filter.isBlank())
                    viewModel.executeLocationCleanup(
                        feedBack = { msg ->
                            scope.launch {
                                snackbarHostState.showSnackbar(msg)
                            }
                        }
                    )
                else
                    viewModel.executeLocationDeleteByFilter(
                        filter,
                        feedBack = { msg ->
                            scope.launch {
                                snackbarHostState.showSnackbar(msg)
                            }
                        }
                    )
                showLocationDialog = Pair(false, false)

            },
            viewModel = viewModel
        )
    }

    if (showBackgroundRationale) {
        AlertDialog(
            onDismissRequest = { showBackgroundRationale = false },
            title = { Text(stringResource(R.string.background_location_title)) },
            text = {
                Text(stringResource(R.string.background_location_rationale))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBackgroundRationale = false
                        backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                ) {
                    Text(stringResource(R.string.allow))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackgroundRationale = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showSmsDialog) {
        SendSmsDialog(
            viewModel,
            initialPartnerPhone = viewModel.getPartnerPhoneNumber(),
            onDismiss = { showSmsDialog = false },
            onSend = { phone, message ->
                showSmsDialog = false
                val hasSmsPermission = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.SEND_SMS
                ) == PackageManager.PERMISSION_GRANTED

                if (hasSmsPermission) {
                    try {
                        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            context.getSystemService(SmsManager::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            SmsManager.getDefault()
                        }
                        smsManager.sendTextMessage(phone, null, message, null, null)
                        Toast.makeText(context, resources.getString(R.string.sms_sent_to, phone), Toast.LENGTH_SHORT).show()
                        Timber.i("SMS sent to $phone: $message")
                    } catch (e: Exception) {
                        Timber.e(e, resources.getString(R.string.sms_error))
                        Toast.makeText(context, "${resources.getString(R.string.sms_error)}: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(context, resources.getString(R.string.sms_permission_denied), Toast.LENGTH_LONG).show()
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    currentPhoneNumber: String,
    currentPartnerPhoneNumber: String,
    currentInterval: Long,
    currentEmail: String,
    currentBackgroundCameraEnabled: Boolean,
    currentBackgroundCameraInterval: Float,
    currentBackgroundCameraSoundEnabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        phone: String, partnerPhone: String,
        interval: Long, email: String, backgroundCameraEnabled: Boolean, bgCameraInterval: Float, bgCameraSoundEnabled: Boolean
    ) -> Unit,
    onPhoneList: () -> Unit
) {
    var partnerPhoneNumber by remember { mutableStateOf(currentPartnerPhoneNumber) }
    var phoneNumber by remember { mutableStateOf(currentPhoneNumber) }
    var intervalStr by remember { mutableStateOf(currentInterval.toString()) }
    var email by remember { mutableStateOf(currentEmail) }
    var backgroundCameraEnabled by remember { mutableStateOf(currentBackgroundCameraEnabled) }
    var bgCameraIntervalStr by remember { mutableStateOf(currentBackgroundCameraInterval.toString()) }
    var bgCameraSoundEnabled by remember { mutableStateOf(currentBackgroundCameraSoundEnabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column(modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = phoneNumber,
                    onValueChange = { phoneNumber = it },
                    label = { Text(stringResource(R.string.sms_phone_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    colors = CardDefaults.cardColors(
                        containerColor = CardDefaults.cardColors().containerColor,
                        contentColor = CardDefaults.cardColors().contentColor)
                    ) {
                    TextField(
                        enabled = false,
                        value = partnerPhoneNumber,
                        onValueChange = { partnerPhoneNumber = it },
                        label = { Text(stringResource(R.string.sms_partner_phone_label)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(onClick = {
                        onPhoneList()
                    }) { Text(stringResource(R.string.phone_list)) }
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.email_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = intervalStr,
                    onValueChange = { if (it.all { char -> char.isDigit() }) intervalStr = it },
                    label = { Text(stringResource(R.string.sms_interval_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.enable_background_camera_label),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = backgroundCameraEnabled,
                        onCheckedChange = { backgroundCameraEnabled = it }
                    )
                }
                if (backgroundCameraEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.enable_bg_camera_sound_label),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = bgCameraSoundEnabled,
                            onCheckedChange = { bgCameraSoundEnabled = it }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = bgCameraIntervalStr,
                        onValueChange = { bgCameraIntervalStr = it },
                        label = { Text(stringResource(R.string.bg_camera_interval_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                }

                val context = LocalContext.current
                var isIgnoringBattery by remember {
                    mutableStateOf(isIgnoringBatteryOptimizations(context))
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.battery_optimization_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isIgnoringBattery) {
                        stringResource(R.string.battery_optimization_disabled)
                    } else {
                        stringResource(R.string.battery_optimization_enabled)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isIgnoringBattery) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        if (isIgnoringBattery) {
                            requestEnableBatteryOptimization(context)
                        } else {
                            requestDisableBatteryOptimization(context)
                        }
                        onDismiss()
                        isIgnoringBattery = isIgnoringBatteryOptimizations(context)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isIgnoringBattery) {
                            stringResource(R.string.enable_battery_optimization)
                    } else {
                            stringResource(R.string.disable_battery_optimization)
                    },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isIgnoringBattery) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val interval = intervalStr.toLongOrNull() ?: 15L
                    val bgCameraInterval = bgCameraIntervalStr.replace(',', '.').toFloatOrNull() ?: 0.25f
                    onSave(
                        phoneNumber.trim(),
                        partnerPhoneNumber.trim(),
                        if (interval < 15) 15L else interval,
                        email.trim(),
                        backgroundCameraEnabled,
                        if (bgCameraInterval <= 0f) 0.25f else bgCameraInterval,
                        bgCameraSoundEnabled
                    )
                }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendSmsDialog(
    viewModel: MainViewModel,
    initialPartnerPhone: String,
    initialMessage: String = "Test SMS vom Standort Monitor",
    onDismiss: () -> Unit,
    onSend: (phone: String, message: String) -> Unit
) {
    // Collect location directly from ViewModel
    val currentLocation by viewModel.locationState.collectAsState()
    val context = LocalContext.current
    val resources = LocalResources.current
    // Fetch location once when the screen is launched
    LaunchedEffect(Unit) {
        viewModel.fetchCurrentLocation(context)
    }
    var phone by remember { mutableStateOf(initialPartnerPhone) }
    var message by remember { mutableStateOf(initialMessage) }
    // 1. String-Array aus den Ressourcen laden
    val options = stringArrayResource(id = R.array.sms_messages)

    // 2. Zustände für die Auswahl und Sichtbarkeit des Menüs
    var expanded by remember { mutableStateOf(false) }
    var selectedOptionText by remember { mutableStateOf(options.getOrNull(0) ?: "") }
    Timber.i("SendSmsDialog currentLocation: $currentLocation")
    if (currentLocation != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.send_sms_title) + "\n" + currentLocation!!.latitude.format(4) + "° " + currentLocation!!.longitude.format(4) + "°") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text(stringResource(R.string.sms_recipient_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it },
                        label = { Text(stringResource(R.string.sms_message_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5
                    )
                    // 3. Container für das Material 3 Dropdown-Menü
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it }
                    ) {
                        // Das Textfeld zeigt das aktuell ausgewählte Element an
                        TextField(
                            // Wichtig für Material 3: Verknüpft das Textfeld mit dem Menü-Anker
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth(),
                            readOnly = true,
                            value = selectedOptionText,
                            onValueChange = {},
                            label = { Text(stringResource(R.string.sms_template_label)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            colors = ExposedDropdownMenuDefaults.textFieldColors(),
                        )

                        // Das eigentliche Dropdown-Menü mit den Optionen
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            options.forEach { selectionOption ->
                                DropdownMenuItem(
                                    text = { Text(selectionOption) },
                                    onClick = {
                                        if (selectionOption.contains("position", ignoreCase = true) ||
                                            selectionOption.contains("🆘", ignoreCase = true) ||
                                            selectionOption.contains("📷", ignoreCase = true)) {
                                            currentLocation?.let {
                                                message =
                                                    selectionOption + " ${it.latitude.format(4)}° ${
                                                        it.longitude.format(
                                                            4
                                                        )
                                                    }°"
                                            } ?: run {
                                                message =
                                                    selectionOption + resources.getString(R.string.sms_location_error)
                                            }
                                        } else
                                            message = selectionOption
                                        selectedOptionText = selectionOption
                                        expanded = false
                                        // Hier kannst du eine Aktion ausführen (z. B. ein ViewModel benachrichtigen)
                                    },
                                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (phone.isNotBlank() && message.isNotBlank()) {
                            onSend(phone, message)
                        }
                    },
                    enabled = phone.isNotBlank() && message.isNotBlank()
                ) {
                    Text(stringResource(R.string.send))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    } else {
            Box(
                contentAlignment = Alignment.BottomCenter,
                modifier = Modifier.fillMaxSize(0.2f).padding(bottom = 46.dp)
            ) {
                Column {
                    Text(stringResource(R.string.waiting_for_location))
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 4.dp,
                        modifier = Modifier.padding(8.dp) // Padding prevents touching the container edge
                    )
                }
            }
        }
}

fun Double.format(digits: Int): String = String.format(Locale.ENGLISH, "%.${digits}f", this)

@Composable
fun LocationListDialog(
    locations: List<LocationItem>,
    onDismiss: () -> Unit,
    onCleanup: (String) -> Unit,
    deviceName: String,
    viewModel: MainViewModel = viewModel(),
    onlyPhotos: Boolean = false
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    //val picDeviceName = deviceName.replace(" ", "_")
    var searchQuery by remember { mutableStateOf("") }
    var sosFlag by remember { mutableIntStateOf(0) }
    var onlyPhotos by remember { mutableStateOf(onlyPhotos) }
    var selectedImageLocation by remember { mutableStateOf<LocationItem?>(null) }
    var loadedImageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var isImageLoading by remember { mutableStateOf(false) }

    val filteredLocations = remember(locations, searchQuery, onlyPhotos, sosFlag) {
        locations.filter { location ->
            val matchesSos = sosFlag == 0 || location.sos_flag == sosFlag
            val matchesPhoto = !onlyPhotos || !location.image_url.isNullOrBlank()
            val matchesQuery = searchQuery.isBlank() || location.title?.contains(searchQuery, ignoreCase = true) == true
            matchesPhoto && matchesQuery && matchesSos
        }
    }

    LaunchedEffect(selectedImageLocation) {
        val urlStr = selectedImageLocation?.image_url
        if (!urlStr.isNullOrBlank()) {
            isImageLoading = true
            loadedImageBitmap = null
            withContext(Dispatchers.IO) {
                try {
                    val url = URL(urlStr)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.doInput = true
                    connection.connect()
                    val inputStream = connection.inputStream
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    if (bitmap != null) {
                        loadedImageBitmap = bitmap.asImageBitmap()
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error loading image from $urlStr")
                } finally {
                    isImageLoading = false
                }
            }
        } else {
            loadedImageBitmap = null
            isImageLoading = false
        }
    }

    selectedImageLocation?.let { imageLocation ->
        AlertDialog(
            onDismissRequest = { selectedImageLocation = null },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { onDismiss() }) {
                        Text(stringResource(R.string.uc_close))
                    }
                    Text(
                        text = imageLocation.image_url.toString()
                            .replace("http://almica.bplaced.net/uploads/", "").replace(".jpg", ""),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isImageLoading) {
                        CircularProgressIndicator()
                    } else if (loadedImageBitmap != null) {
                        Image(
                            bitmap = loadedImageBitmap!!,
                            contentDescription = "Location Image",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    webViewClient = WebViewClient()
                                    settings.javaScriptEnabled = true
                                    loadUrl(selectedImageLocation!!.image_url!!)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    val bitmap = loadedImageBitmap?.asAndroidBitmap()
                    val fileName = selectedImageLocation?.image_url
                        ?.substringAfterLast("/")
                        ?.removeSuffix(".jpg")
                        ?: "location_${System.currentTimeMillis()}"
                    Timber.i("Store image: $fileName")
                    selectedImageLocation = null
                    if (bitmap != null) {
                        viewModel.saveBitmapToGallery(context, bitmap, fileName) { success ->
                            scope.launch {
                                val msg = if (success) resources.getString(R.string.image_saved) else resources.getString(R.string.image_not_saved)
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }) {
                    Text(stringResource(R.string.store_in_gallery))
                }
            },
            confirmButton = {
                selectedImageLocation?.let {
                    TextButton(onClick = {
                        openCoordinatesInGoogleMaps(context, it.latitude, it.longitude)
                    }) {
                        Text(stringResource(R.string.open_in_map))
                    }
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.locations_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text(stringResource(R.string.device_filter_label)) },
                        modifier = Modifier
                            .weight(1f)
                            .padding(bottom = 8.dp),
                        singleLine = true
                    )
                    IconButton(onClick = {
                        searchQuery = if (searchQuery.isBlank())
                            deviceName
                        else
                            ""
                    }) {
                        Icon(imageVector = Icons.Default.TabletAndroid, contentDescription = "Filter by device",
                            tint = if (searchQuery.isBlank()) MaterialTheme.colorScheme.primary else
                                MaterialTheme.colorScheme.error)
                    }
                    Text(text = "🆘")
                    Switch(
                        checked = (sosFlag == 1),
                        onCheckedChange = { isChecked ->
                            sosFlag = if (isChecked) 1 else 0
                            Timber.i("SOS Flag: $sosFlag")
                        }
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    filteredLocations.forEach { location ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val image_location = location
                                    if (!image_location.image_url.isNullOrBlank()) {
                                        selectedImageLocation = image_location
                                    } else {
                                        openCoordinatesInGoogleMaps(
                                            context,
                                            location.latitude,
                                            location.longitude
                                        )
                                    }
                                    searchQuery = location.title.orEmpty()
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp)
                        ) {
                            Text(
                                text = location.title.takeUnless { it.isNullOrBlank() }
                                    ?: "Ort #${location.id}",
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "📍 ${location.latitude.format(5)}°, ${location.longitude.format(5)}° ⛰️ ${location.altitude.format(0)}m",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (location.created_at.isNotBlank()) {
                                Text(
                                    text = "⏲️ ${location.created_at} 🌡 ${location.temperature?.format(1)}°C",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            location.image_url?.let {
                                if (it.isNotBlank()) {
                                    Timber.i("Image URL: $it")
                                    val result = "…"+location.image_url.takeLast(24).replace(".jpg", "")
//                                    val result = location.image_url.substringAfter("/uploads/", missingDelimiterValue = "")
//                                        .replace(".jpg", "").replace(picDeviceName, "…")
                                    Text(
                                        text = "\uD83D\uDCF7 ${result}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
        dismissButton = {
            if (searchQuery.isNotBlank())
                TextButton(onClick = { onCleanup(searchQuery) }) {
                    Text(stringResource(R.string.cleanup))
                }
        }
    )
}

@Composable
fun ContactControlCard(
    modifier: Modifier = Modifier,
    partnerPhoneNumber: String,
    isWorkerRunning: Boolean = false,
    onStartWorker: () -> Unit,
    onStopWorker: () -> Unit,
    containerColor: Color = CardDefaults.cardColors().containerColor,
    contentColor: Color = CardDefaults.cardColors().contentColor,
    onSendSmsNow: () -> Unit,
    onTakePicture: () -> Unit,
    viewModel: MainViewModel = viewModel(),
    onSendLocationNow: () -> Unit
) {
    val context = LocalContext.current
    val sosFlag by viewModel.sosFlag.collectAsStateWithLifecycle()
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Send SMS now
            OutlinedButton(
                onClick = onSendSmsNow,
                enabled = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(modifier = Modifier.weight(1f), text = stringResource(R.string.send_sms_now))
                }
            }
            // Take Picture
            OutlinedButton(
                onClick = onTakePicture,
                //enabled = isWorkerRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(modifier = Modifier.weight(1f), text = stringResource(R.string.take_picture))
                }
            }
            OutlinedButton(onClick = {
                // Bypasses permission requirements by letting the user hit "Send" themselves
                val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                    data = "tel:${partnerPhoneNumber}".toUri()
                }
                try {
                    context.startActivity(dialIntent)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(context, "No phone application found", Toast.LENGTH_SHORT).show()
                }
            }, modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Phone, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(modifier = Modifier.weight(1f), text = stringResource(R.string.telephone_call, partnerPhoneNumber))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.on_tour))
                Spacer(modifier = Modifier.width(3.dp))
                Switch(checked = isWorkerRunning, onCheckedChange = {
                    viewModel.setSosFlag(0)
                    if (isWorkerRunning)
                        onStopWorker()
                    else
                        onStartWorker()
                })
                if (isWorkerRunning) {
                    VerticalDivider(modifier = Modifier.fillMaxWidth(0.2f))
                    Text(text = "🆘")
                    Spacer(modifier = Modifier.width(3.dp))
                    Switch(
                        checked = (sosFlag == 1),
                        onCheckedChange = { isChecked ->
                            viewModel.setSosFlag(if (isChecked) 1 else 0)
                            if (isChecked)
                                onSendLocationNow()
                        }
                    )
                }
            }
        }
    }
}
@Composable
fun WorkerControlCard(
    isWorkerRunning: Boolean,
    workerInterval: Long,
    sendLocationCount: Int,
    onStartWorker: () -> Unit,
    onStopWorker: () -> Unit,
    onFetchLocations: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = CardDefaults.cardColors().containerColor,
    contentColor: Color = CardDefaults.cardColors().contentColor,
    onSendLocationNow: () -> Unit,
    onDeleteOldRecords: (Int) -> Unit,
    serviceRestartCount: Int
) {
    var selectedLimit by remember { mutableStateOf<Int?>(5) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    val limitOptions = listOf(null, 3, 5, 10, 20, 50)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row {
                Text(
                    text = stringResource(R.string.locations_sent_count, sendLocationCount),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(20.dp))
                Text(
                    text = stringResource(R.string.service_restart_count, serviceRestartCount),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            // Start Job Button
            OutlinedButton(
                onClick = onStartWorker,
                enabled = !isWorkerRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Start, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(modifier = Modifier.weight(1f),
                        text = if (isWorkerRunning) {
                            stringResource(R.string.worker_running, workerInterval)
                        } else {
                            stringResource(R.string.worker_start, workerInterval)
                        }
                    )
                }
            }

// Stop Job Button
            OutlinedButton(
                onClick = onStopWorker,
                enabled = isWorkerRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = null,
                        tint = if (isWorkerRunning) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(modifier = Modifier.weight(1f),
                        text = stringResource(R.string.worker_stop))
                }
            }

            // Fetch Locations Button with Dropdown
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { onFetchLocations(selectedLimit) },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(modifier = Modifier.weight(1f), text = stringResource(R.string.get_locations))
                    }
                }

                Box {
                    OutlinedButton(
                        onClick = { dropdownExpanded = true },
                        modifier = Modifier.height(50.dp)
                    ) {
                        Text(
                            text = selectedLimit?.toString() ?: stringResource(R.string.fetch_limit_all)
                        )
                    }
                    DropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false }
                    ) {
                        limitOptions.forEach { limit ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = limit?.toString() ?: stringResource(R.string.fetch_limit_all)
                                    )
                                },
                                onClick = {
                                    selectedLimit = limit
                                    dropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // Send Location now
            OutlinedButton(
                onClick = onSendLocationNow,
                enabled = isWorkerRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(modifier = Modifier.weight(1f), text = stringResource(R.string.send_location_now))
                }
            }

            var deleteDaysExpanded by remember { mutableStateOf(false) }
            var selectedDays by remember { mutableStateOf(3) }
            val deleteDaysOptions = listOf(0, 1, 2, 3, 4, 5, 7, 14, 30)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { onDeleteOldRecords(selectedDays) },
                    modifier = Modifier
                        .height(50.dp)
                        .weight(1f)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.cleanup_old_records),
                        modifier = Modifier.weight(1f))
                }
                Box() {
                    OutlinedButton(
                        onClick = { deleteDaysExpanded = true },
                        modifier = Modifier
                            .height(50.dp)
                    ) {
                        Text(text = if (selectedDays == 0) "> 0 (All)" else "> $selectedDays days")
                    }
                    DropdownMenu(
                        expanded = deleteDaysExpanded,
                        onDismissRequest = { deleteDaysExpanded = false }
                    ) {
                        deleteDaysOptions.forEach { days ->
                            DropdownMenuItem(
                                text = {
                                    Text(text = if (days == 0) "> 0 (All)" else "> $days days")
                                },
                                onClick = {
                                    selectedDays = days
                                    deleteDaysExpanded = false
                                }
                            )
                        }
                    }
                }
            }

        }
    }
}

@Composable
fun StatusCard(
    isShareLocActive: Boolean,
    lastStatusChangeTime: Long,
    notificationsEnabled: Boolean,
    statusBoxColor: Color,
    contentColor: Color,
    timeFormatter: SimpleDateFormat,
    onNotificationsChanged: (Boolean) -> Unit,
    onCheckStatus: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val formattedTime = remember(lastStatusChangeTime) {
        timeFormatter.format(Date(lastStatusChangeTime))
    }
    BackHandler { onBack() }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = statusBoxColor,
            contentColor = contentColor
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.current_status_label, formattedTime),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = if (isShareLocActive) stringResource(R.string.status_active) else stringResource(R.string.status_inactive),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onCheckStatus,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text(stringResource(R.string.check_status_now))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.enable_notifications_label),
                    style = MaterialTheme.typography.bodyLarge
                )
                Switch(
                    checked = notificationsEnabled,
                    onCheckedChange = onNotificationsChanged
                )
            }

        }
    }
}

@Composable
fun MainScreenContent(
    deviceName: String,
    partnerPhoneNumber: String,
    isShareLocActive: Boolean,
    isWorkerRunning: Boolean,
    workerInterval: Long,
    sendLocationCount: Int,
    notificationsEnabled: Boolean,
    lastStatusChangeTime: Long,
    onNotificationsChanged: (Boolean) -> Unit,
    onCheckStatus: () -> Unit,
    onStartWorker: () -> Unit,
    onStopWorker: () -> Unit,
    onSendLocationNow: () -> Unit,
    onFetchLocations: (Int?) -> Unit,
    onSendSmsNow: () -> Unit,
    onDeleteOldRecords: (Int) -> Unit,
    onTakePicture: () -> Unit,
    serviceRestartCount: Int
) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val statusBoxColor by animateColorAsState(
        targetValue = if (isShareLocActive) MaterialTheme.colorScheme.errorContainer
                      else MaterialTheme.colorScheme.primaryContainer,
        label = "StatusColor"
    )
    
    val contentColor = if (isShareLocActive) MaterialTheme.colorScheme.onErrorContainer
                       else MaterialTheme.colorScheme.onPrimaryContainer
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val buildTime = BuildConfig.BUILD_TIME
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
/*        Text(
            text = "Locations Monitor",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )*/
        Text(
            text = stringResource(R.string.device_label, deviceName, buildTime),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        SecondaryTabRow(selectedTabIndex = pagerState.currentPage) {
            Tab(
                selected = pagerState.currentPage == 0,
                onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                text = { Text(stringResource(R.string.contact)) }
            )
            Tab(
                selected = pagerState.currentPage == 1,
                onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                text = { Text(stringResource(R.string.actions)) }
            )
            Tab(
                selected = pagerState.currentPage == 1,
                onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                text = { Text(stringResource(R.string.status)) }
            )
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
        ) { page ->
            when (page) {
                0 -> {
                    ContactControlCard(
                        partnerPhoneNumber = partnerPhoneNumber,
                        isWorkerRunning = isWorkerRunning,
                        onStartWorker = onStartWorker,
                        onStopWorker = onStopWorker,
                        onSendSmsNow = onSendSmsNow,
                        onSendLocationNow = onSendLocationNow,
                        onTakePicture = onTakePicture,
                    )
                }
                1 -> {
                    WorkerControlCard(
                        isWorkerRunning = isWorkerRunning,
                        workerInterval = workerInterval,
                        sendLocationCount = sendLocationCount,
                        serviceRestartCount = serviceRestartCount,
                        onStartWorker = onStartWorker,
                        onStopWorker = onStopWorker,
                        onFetchLocations = onFetchLocations,
                        onSendLocationNow = onSendLocationNow,
                        onDeleteOldRecords = { days ->
                        onDeleteOldRecords(days) }
                    )
                }
                2 -> {
                    StatusCard(
                        isShareLocActive = isShareLocActive,
                        lastStatusChangeTime = lastStatusChangeTime,
                        notificationsEnabled = notificationsEnabled,
                        statusBoxColor = statusBoxColor,
                        contentColor = contentColor,
                        timeFormatter = timeFormatter,
                        onNotificationsChanged = onNotificationsChanged,
                        onCheckStatus = onCheckStatus,
                        onBack = {
                            scope.launch {
                                pagerState.animateScrollToPage(0)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Active")
@Composable
fun MainScreenActivePreview() {
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            MainScreenContent(
                deviceName = "0123456789",
                partnerPhoneNumber = "01111111",
                isShareLocActive = true,
                isWorkerRunning = false,
                workerInterval = 15L,
                sendLocationCount = 3,
                notificationsEnabled = true,
                lastStatusChangeTime = System.currentTimeMillis(),
                onNotificationsChanged = {},
                onCheckStatus = {},
                onStartWorker = {},
                onStopWorker = {},
                onSendLocationNow = {},
                onFetchLocations = {},
                onSendSmsNow = {},
                onDeleteOldRecords = {},
                onTakePicture = {},
                serviceRestartCount = 0,
            )
        }
    }
}

@Preview(showBackground = true, name = "Inactive")
@Composable
fun MainScreenInactivePreview() {
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            MainScreenContent(
                deviceName = "0123456789",
                partnerPhoneNumber = "01111111",
                isShareLocActive = false,
                isWorkerRunning = true,
                workerInterval = 15L,
                sendLocationCount = 5,
                notificationsEnabled = false,
                lastStatusChangeTime = System.currentTimeMillis(),
                onNotificationsChanged = {},
                onCheckStatus = {},
                onStartWorker = {},
                onStopWorker = {},
                onSendLocationNow = {},
                onFetchLocations = {},
                onSendSmsNow = {},
                onDeleteOldRecords = {},
                onTakePicture = {},
                serviceRestartCount = 0,
            )
        }
    }
}

