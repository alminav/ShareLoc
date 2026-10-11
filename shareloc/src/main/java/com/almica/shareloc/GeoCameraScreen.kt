package com.almica.shareloc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.media.MediaActionSound
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.collectAsState

@SuppressLint("MissingPermission")
@Composable
fun GeoCameraScreen(
    onDismiss: (msg: Pair<String, String?>?, link: String?) -> Unit,
    viewModel: MainViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var activeImageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    val imageLocation = remember { mutableStateOf<Location?>(null) }
// Standortabfrage vor der Aufnahme
    viewModel.fetchCurrentLocation(context)
    LaunchedEffect(viewModel.locationState.collectAsState().value) {
        Timber.i("Location: ${viewModel.locationState.value}")
        imageLocation.value = viewModel.locationState.value
    }

    val isInspection = LocalInspectionMode.current
    val actionSound = remember(isInspection) { if (isInspection) null else MediaActionSound() }

    DisposableEffect(isInspection) {
        onDispose {
            actionSound?.release()
        }
    }

    BackHandler {
        onDismiss(null, null)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (!isSaving) {
            CameraPreview(
                onReady = { captureObject ->
                    activeImageCapture = captureObject
                }
            )
        }

        // Close Button
        IconButton(
            onClick = { onDismiss(null, null) },
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(16.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = Color.Black.copy(alpha = 0.5f),
                contentColor = Color.White
            )
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.close)
            )
        }

        // Shutter Button / Progress Indicator
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    color = Color.Red,
                    strokeWidth = 4.dp,
                    modifier = Modifier
                        .size(56.dp)
                        .background(Color.White, CircleShape)
                )
            } else {
                Button(
                    onClick = {
                        isSaving = true
                        takePicture(imageLocation.value,
                            actionSound = actionSound,
                            context = context,
                            lifecycle = lifecycle,
                            viewModel = viewModel,
                            activeImageCapture = activeImageCapture,
                            onDismiss = { msg, link ->
                                isSaving = false
                                onDismiss(msg, link)
                            }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (imageLocation.value != null) Color.Red else Color.LightGray,
                        contentColor = Color.White
                    ),
                    border = BorderStroke(2.dp, Color.White)
                ) {
                    Text(stringResource(R.string.take_picture_and_upload))
                }
            }
        }
    }
}

private fun takePicture(
    location: Location?,
    actionSound: Any?,
    context: Context,
    lifecycle: androidx.lifecycle.Lifecycle,
    viewModel: MainViewModel,
    activeImageCapture: ImageCapture?,
    onDismiss: (msg: Pair<String, String?>?, link: String?) -> Unit
) {
    if (activeImageCapture == null) {
        Toast.makeText(context, context.getString(R.string.camera_loading), Toast.LENGTH_SHORT).show()
        return
    }

    (actionSound as? MediaActionSound)?.play(MediaActionSound.SHUTTER_CLICK)
    val deviceName = getDeviceName()
    val photoDate = getReadableDate(System.currentTimeMillis())
    val photoFile = File(context.cacheDir, "${deviceName}_${photoDate}.jpg")
    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile)
    location.let {
        Timber.i("Location: ${it?.latitude}, ${it?.longitude}")
        val metadata = ImageCapture.Metadata().apply { this.location = it }
        outputOptions.setMetadata(metadata)
    }
    activeImageCapture.takePicture(
        outputOptions.build(),
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                lifecycle.coroutineScope.launch {
                    val compressedFile = compressImageFile(photoFile, photoFile)
                    Timber.i("Compressed file size: ${compressedFile.length()}")

                    viewModel.uploadImageToBplaced(compressedFile) { response ->
                        val body = response.body()
                        val (msg, link) = if (response.isSuccessful && body != null) {
                            Pair(body.message, body.url)
                        } else {
                            Pair("Fehler beim Upload: ${response.code()}", null)
                        }
                        onDismiss(Pair(photoFile.name, msg), link)
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                onDismiss(Pair(photoFile.name, null), null)
                Toast.makeText(context, "Fehler: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
        }
    )
}

suspend fun compressImageFile(
    inputFile: File,
    outputFile: File,
    targetSizeBytes: Long = 500 * 1024 // 500 KB
): File = withContext(Dispatchers.IO) {
    Timber.i("Compressing file: ${inputFile.name}")
    if (inputFile.exists() && inputFile.length() <= targetSizeBytes) {
        if (inputFile != outputFile) {
            inputFile.copyTo(outputFile, overwrite = true)
        }
        return@withContext outputFile
    }

    val rotationDegrees = getExifRotation(inputFile)

    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(inputFile.absolutePath, options)

    options.inSampleSize = calculateInSampleSize(options, maxDimension = 2048)
    options.inJustDecodeBounds = false

    var bitmap = BitmapFactory.decodeFile(inputFile.absolutePath, options)
        ?: return@withContext inputFile

    if (rotationDegrees != 0) {
        val rotated = rotateBitmap(bitmap, rotationDegrees)
        if (rotated != bitmap) {
            bitmap.recycle()
            bitmap = rotated
        }
    }

    val stream = ByteArrayOutputStream()
    var quality = 90
    val minQuality = 70
    val minDimension = 400

    try {
        do {
            stream.reset()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            if (stream.size() <= targetSizeBytes) break

            if (quality > minQuality) {
                quality -= 10
            } else if (bitmap.width > minDimension && bitmap.height > minDimension) {
                val newWidth = (bitmap.width * 0.8).toInt()
                val newHeight = (bitmap.height * 0.8).toInt()
                val scaledBitmap = bitmap.scale(newWidth, newHeight)
                if (scaledBitmap != bitmap) {
                    bitmap.recycle()
                    bitmap = scaledBitmap
                }
                quality = 85
            } else {
                break
            }
        } while (stream.size() > targetSizeBytes)

        FileOutputStream(outputFile).use { out ->
            stream.writeTo(out)
        }
    } finally {
        bitmap.recycle()
    }

    return@withContext outputFile
}

private fun getExifRotation(file: File): Int {
    return try {
        val exif = androidx.exifinterface.media.ExifInterface(file.absolutePath)
        when (exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_UNDEFINED)) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (_: Exception) {
        0
    }
}

private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
    val matrix = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun calculateInSampleSize(options: BitmapFactory.Options, maxDimension: Int): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1

    if (height > maxDimension || width > maxDimension) {
        val halfHeight = height / 2
        val halfWidth = width / 2

        while ((halfHeight / inSampleSize) >= maxDimension && (halfWidth / inSampleSize) >= maxDimension) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onReady: (ImageCapture) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember { PreviewView(context) }
    val imageCapture = remember { ImageCapture.Builder().build() }

    LaunchedEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().apply {
                    surfaceProvider = previewView.surfaceProvider
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
                onReady(imageCapture)
            } catch (e: Exception) {
                Timber.e(e, "Error initializing camera provider")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize()
    )
}

fun getReadableDate(millis: Long): String {
    val timeFormatter = SimpleDateFormat("dd_MM_yyyy_HH_mm_ss", Locale.getDefault())
    return timeFormatter.format(Date(millis))
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
fun CameraPreviewPreview() {
    MaterialTheme {
        CameraPreview(
            onReady = {}
        )
    }
}