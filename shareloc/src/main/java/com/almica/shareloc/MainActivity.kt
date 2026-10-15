package com.almica.shareloc

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.SmsManager
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
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.webkit.WebView
import android.webkit.WebViewClient
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
import com.almica.shareloc.backgroundcamera.BackgroundCameraService

private fun launchMapIntent(context: Context, latitude: Double, longitude: Double) {
    val gmmIntentUri = "geo:$latitude,$longitude?q=$latitude,$longitude".toUri()
    val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
        setPackage("com.google.android.apps.maps")
    }
    try {
        context.startActivity(mapIntent)
    } catch (e: ActivityNotFoundException) {
        mapIntent.setPackage(null)
        try {
            context.startActivity(mapIntent)
        } catch (e2: ActivityNotFoundException) {
            Toast.makeText(context, "No map application found", Toast.LENGTH_SHORT).show()
        }
    }
}

class MainActivity : ComponentActivity() {
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val viewModel: MainViewModel = viewModel()
                    MainScreen(viewModel)
                }
            }
        }
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
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsStateWithLifecycle(initialValue = true)

    var lastStatusChangeTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showSettingsDialog by remember { mutableStateOf(false) }

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
        permissions.add(Manifest.permission.SEND_SMS)
        permissions.add(Manifest.permission.RECEIVE_SMS)

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
                isShareLocActive = isShareLocActive,
                isWorkerRunning = isWorkerRunning,
                workerInterval = viewModel.getIntervalMinutes(),
                sendLocationCount = sendLocationCount,
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
                    viewModel.startWorker()
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
            currentPhone = viewModel.getPhoneNumber(),
            currentInterval = viewModel.getIntervalMinutes(),
            currentEmail = viewModel.getEmailAddress(),
            currentBackgroundCameraEnabled = viewModel.isBackgroundCameraEnabled(),
            currentBackgroundCameraInterval = viewModel.getBackgroundCameraIntervalMinutes(),
            onDismiss = { showSettingsDialog = false },
            onSave = { phone, interval, email, backgroundCameraEnabled, bgCameraInterval ->
                viewModel.updatePreferences(phone, interval, email, backgroundCameraEnabled, bgCameraInterval)
                showSettingsDialog = false
            }
        )
    }

    if (showLocationDialog.first) {
        LocationListDialog(
            deviceName = getDeviceName(),
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
            initialPhone = viewModel.getPhoneNumber(),
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
    currentPhone: String,
    currentInterval: Long,
    currentEmail: String,
    currentBackgroundCameraEnabled: Boolean,
    currentBackgroundCameraInterval: Float,
    onDismiss: () -> Unit,
    onSave: (phone: String, interval: Long, email: String, backgroundCameraEnabled: Boolean, bgCameraInterval: Float) -> Unit
) {
    var phone by remember { mutableStateOf(currentPhone) }
    var intervalStr by remember { mutableStateOf(currentInterval.toString()) }
    var email by remember { mutableStateOf(currentEmail) }
    var backgroundCameraEnabled by remember { mutableStateOf(currentBackgroundCameraEnabled) }
    var bgCameraIntervalStr by remember { mutableStateOf(currentBackgroundCameraInterval.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column(modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text(stringResource(R.string.sms_phone_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
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
                    OutlinedTextField(
                        value = bgCameraIntervalStr,
                        onValueChange = { bgCameraIntervalStr = it },
                        label = { Text(stringResource(R.string.bg_camera_interval_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val interval = intervalStr.toLongOrNull() ?: 15L
                    val bgCameraInterval = bgCameraIntervalStr.replace(',', '.').toFloatOrNull() ?: 0.25f
                    onSave(
                        phone,
                        if (interval < 15) 15L else interval,
                        email.trim(),
                        backgroundCameraEnabled,
                        if (bgCameraInterval <= 0f) 0.25f else bgCameraInterval
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendSmsDialog(
    initialPhone: String,
    initialMessage: String = "Test SMS vom Standort Monitor",
    onDismiss: () -> Unit,
    onSend: (phone: String, message: String) -> Unit
) {
    var phone by remember { mutableStateOf(initialPhone) }
    var message by remember { mutableStateOf(initialMessage) }
    // 1. String-Array aus den Ressourcen laden
    val options = stringArrayResource(id = R.array.sms_messages)

    // 2. Zustände für die Auswahl und Sichtbarkeit des Menüs
    var expanded by remember { mutableStateOf(false) }
    var selectedOptionText by remember { mutableStateOf(options.getOrNull(0) ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.send_sms_title)) },
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
}

private fun Double.format(digits: Int): String = String.format(Locale.getDefault(), "%.${digits}f", this)

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
    var onlyPhotos by remember { mutableStateOf(onlyPhotos) }
    var selectedImageLocation by remember { mutableStateOf<LocationItem?>(null) }
    var loadedImageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var isImageLoading by remember { mutableStateOf(false) }

    val filteredLocations = remember(locations, searchQuery, onlyPhotos) {
        locations.filter { location ->
            val matchesPhoto = !onlyPhotos || !location.image_url.isNullOrBlank()
            val matchesQuery = searchQuery.isBlank() || location.title?.contains(searchQuery, ignoreCase = true) == true
            matchesPhoto && matchesQuery
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
                        launchMapIntent(context, it.latitude, it.longitude)
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
                                        launchMapIntent(context, location.latitude, location.longitude)
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
    containerColor: Color = CardDefaults.cardColors().containerColor,
    contentColor: Color = CardDefaults.cardColors().contentColor,
    onSendSmsNow: () -> Unit,
    onTakePicture: () -> Unit
) {
    val context = LocalContext.current
    val prefs = PreferenceManager(context)
    val phoneNumber = remember { prefs.getPhoneNumber() }
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
                    data = "tel:${phoneNumber}".toUri()
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
                    Text(modifier = Modifier.weight(1f), text = stringResource(R.string.telephone_call, phoneNumber))
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
    onDeleteOldRecords: (Int) -> Unit
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
            Text(
                text = stringResource(R.string.locations_sent_count, sendLocationCount),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
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
                    modifier = Modifier.height(50.dp).weight(1f)
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
    onTakePicture: () -> Unit
) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val deviceName = getDeviceName()
    val statusBoxColor by animateColorAsState(
        targetValue = if (isShareLocActive) MaterialTheme.colorScheme.errorContainer
                      else MaterialTheme.colorScheme.primaryContainer,
        label = "StatusColor"
    )
    
    val contentColor = if (isShareLocActive) MaterialTheme.colorScheme.onErrorContainer
                       else MaterialTheme.colorScheme.onPrimaryContainer
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    val scope = rememberCoroutineScope()
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
            text = stringResource(R.string.device_label, deviceName),
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
                        onSendSmsNow = onSendSmsNow,
                        onTakePicture = onTakePicture
                    )
                }
                1 -> {
                    WorkerControlCard(
                        isWorkerRunning = isWorkerRunning,
                        workerInterval = workerInterval,
                        sendLocationCount = sendLocationCount,
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
                onDeleteOldRecords = {},
                onFetchLocations = {},
                onSendSmsNow = {},
                onTakePicture = {}
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
                onDeleteOldRecords = {},
                onSendSmsNow = {},
                onTakePicture = {}
            )
        }
    }
}

