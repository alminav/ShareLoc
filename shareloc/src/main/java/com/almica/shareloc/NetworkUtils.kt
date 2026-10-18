package com.almica.shareloc

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import timber.log.Timber

object Constants {
    const val WORK_TAG = "mobile_data_check_work"
    const val NOTIFICATION_CHANNEL_ID = "mobile_data_channel"
    const val PREFS_NAME = "checker_prefs"
    const val KEY_PHONE_NUMBER = "phone_number"
    const val KEY_INTERVAL_MINS = "interval_minutes"
    const val KEY_INSTALLATION_ID = "installation_id"
    const val KEY_SEND_LOCATION_COUNT = "send_location_count"
    const val KEY_SERVICE_RESTART_COUNT = "service_restart_count"
    const val KEY_NETSTATE_NOTIFICATIONS_ENABLED = "notifications_enabled"
    const val KEY_SMS_FILTER_ENABLED = "sms_filter_enabled"
    const val KEY_IMAGE_URL = "image_url"
    const val KEY_EMAIL_ADDRESS = "email_address"
    const val KEY_BACKGROUND_CAMERA_ENABLED = "background_camera_enabled"
    const val KEY_BG_CAMERA_INTERVAL_MINS = "bg_camera_interval_minutes"
    const val KEY_BG_CAMERA_SOUND_ENABLED = "bg_camera_sound_enabled"
}

fun isNetworkAvailable(context: Context): Boolean {
    val connectivityManager = (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
        ?: return false
    val activeNetwork = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    val isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)
    Timber.i("isIgnoringBatteryOptimizations: $isIgnoringBatteryOptimizations")
    return isIgnoringBatteryOptimizations
}

fun requestDisableBatteryOptimization(context: Context) {
    // Play-Store-konform ist Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    // manifest definition kann gelöscht werden
    Timber.i("requestDisableBatteryOptimization")
    try {
        // NICHT Play-Store-konform
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = "package:${context.packageName}".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Timber.e(e, "Error requesting ignore battery optimization")
    }
}

fun requestEnableBatteryOptimization(context: Context) {
    Timber.i("requestEnableBatteryOptimization")
    try {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Timber.e(e, "Error opening battery optimization settings")
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:${context.packageName}".toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e2: Exception) {
            Timber.e(e2, "Error opening app details settings")
        }
    }
}

fun isShareLocEnabled(context: Context): Triple<Boolean, Float, Int> {
    val temp = getBatteryTemperature(context)
    val level = getBatteryLevel(context)
    Timber.i("Checking data status at battery temperature: $temp°C and level: $level%")

    // Check if permission is granted at runtime before accessing TelephonyManager
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
        != PackageManager.PERMISSION_GRANTED) {
        Timber.w("READ_PHONE_STATE permission not granted")
        return Triple(false, temp, level)
    }

    val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    val isEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Timber.i("TelephonyManager.getDataNetworkType() = ${telephonyManager.dataNetworkType}")
        Timber.i("TelephonyManager.isDataEnabled() = ${telephonyManager.isDataEnabled}")
        telephonyManager.isDataEnabled
    } else {
        @Suppress("DEPRECATION")
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val mobileDataMethod = connectivityManager.javaClass.getDeclaredMethod("getMobileDataEnabled")
        mobileDataMethod.isAccessible = true
        mobileDataMethod.invoke(connectivityManager) as Boolean
    }
    return Triple(isEnabled, temp, level)
}

fun getBatteryTemperature(context: Context): Float {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
    // Temperature is in tenths of a degree Celsius (e.g., 305 for 30.5°C)
    val celsius = temp / 10f
    Timber.i("Battery Temperature: $celsius°C")
    return celsius
}
fun getBatteryLevel(context: Context): Int {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
    Timber.i("Battery level: ${level}%")
    return level
}

fun sendShareLocNotification(context: Context) {
    val channelId = Constants.NOTIFICATION_CHANNEL_ID
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            channelId,
            "Mobile Data Alerts",
            NotificationManager.IMPORTANCE_HIGH,
        )
        notificationManager.createNotificationChannel(channel)
    }

    val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    val temp = getBatteryTemperature(context)

    val notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.stat_sys_warning)
        .setContentTitle("Mobile Daten aktiv ($currentTime)")
        .setContentText("Achtung: Mobile Daten sind aktiv! Temp: $temp°C")
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .build()

    notificationManager.notify(1, notification)
}

suspend fun sendLocation(
    title: String,
    imageUrl: String,
    latitude: Double,
    longitude: Double,
    altitude: Double = 0.0,
    temperature: Float? = null,
    level: Int? = null
): Boolean {
    return try {
        Timber.i( "sendLocation $title $temperature°C")
        val response = NetworkClient.bplacedApiService.saveLocation(
            title = title, //locationTitle,
            imageUrl = imageUrl,
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            temperature = temperature,
            level = level
        )
        val body = response.body()
        if (response.isSuccessful && (body?.status == "success")) {
            Timber.i("Location sent successfully: ${body.message}")
            true
        } else {
            Timber.e("Failed to send location: code ${response.code()}, message ${body?.message}")
            false
        }
    } catch (e: java.net.UnknownHostException) {
        Timber.w("Unable to resolve host (network/DNS unavailable): ${e.message}")
        false
    } catch (e: java.io.IOException) {
        Timber.w("Network error sending location: ${e.message}")
        false
    } catch (e: Exception) {
        Timber.e(e, "Error sending location")
        false
    }
}

suspend fun sendEmail(
    datei: String,
    mailto: String,
    latitude: Double,
    longitude: Double
): Boolean {
    return try {
        Timber.i("sendEmail datei=$datei mailto=$mailto lat=$latitude lon=$longitude")
        val response = NetworkClient.bplacedApiService.sendEmail(
            dateiQuery = datei,
            mailtoQuery = mailto,
            latitudeQuery = latitude,
            longitudeQuery = longitude,
            fileQuery = datei,
            filenameQuery = datei,
            emailQuery = mailto,
            toQuery = mailto,
            latQuery = latitude,
            lonQuery = longitude,
            dateiField = datei,
            mailtoField = mailto,
            latitudeField = latitude,
            longitudeField = longitude,
            fileField = datei,
            filenameField = datei,
            emailField = mailto,
            toField = mailto,
            latField = latitude,
            lonField = longitude
        )
        if (response.isSuccessful) {
            val responseText = response.body()?.string()?.trim() ?: ""
            Timber.i("sendEmail response: $responseText")

            val isSuccess = try {
                val json = com.google.gson.JsonParser.parseString(responseText)
                if (json.isJsonObject) {
                    val status = json.asJsonObject["status"]?.asString
                    status.equals("success", ignoreCase = true)
                } else if (json.isJsonPrimitive) {
                    val primitiveStr = json.asString
                    primitiveStr.contains("success", ignoreCase = true) ||
                            primitiveStr.contains("sent", ignoreCase = true) ||
                            primitiveStr.contains("gesendet", ignoreCase = true) ||
                            primitiveStr.contains("ok", ignoreCase = true)
                } else {
                    false
                }
            } catch (_: Exception) {
                val lower = responseText.lowercase()
                !lower.contains("error") && !lower.contains("failed") && !lower.contains("fehler") && responseText.isNotEmpty()
            }

            if (isSuccess) {
                Timber.i("mail sent successfully: $responseText")
                true
            } else {
                Timber.e("Failed to send mail: response $responseText")
                false
            }
        } else {
            Timber.e("Failed to send mail: code ${response.code()}")
            false
        }
    } catch (e: java.net.UnknownHostException) {
        Timber.w("Unable to resolve host sending mail (network/DNS unavailable): ${e.message}")
        false
    } catch (e: java.io.IOException) {
        Timber.w("Network error sending mail: ${e.message}")
        false
    } catch (e: Exception) {
        Timber.e(e, "Error sending mail")
        false
    }
}