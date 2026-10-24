package com.almica.shareloc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Context.watchdogDataStore: DataStore<Preferences> by preferencesDataStore(name = "WatchDogWorker")

class WatchDogWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val NOTIFICATION_ID = 1002
        private const val NOTIFICATION_CHANNEL_ID = "watchdog_worker"
        val LAST_POSITION_KEY = stringPreferencesKey("last_position")
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "WatchDog Background Worker",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("WatchDog Background Worker")
            .setContentText("Standortbestimmung wird durchgeführt...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result {
        return try {
            // 1. Read last position timestamp safely from DataStore
            val lastPositionTime = applicationContext.watchdogDataStore.data.map { preferences ->
                preferences[LAST_POSITION_KEY] ?: ""
            }.first()

            Timber.i("Last Position timestamp: $lastPositionTime")

            val prefs = PreferenceManager(applicationContext)
            val partnerPhone = prefs.getPartnerPhoneNumber()

            // 2. Fetch locations from API
            val response = NetworkClient.bplacedApiService.getLocationsCount(count = null)
            if (!response.isSuccessful || response.body() == null) {
                Timber.w("Failed to fetch locations. HTTP code: ${response.code()}")
                return Result.retry()
            }

            val apiResponse = response.body()!!
            if (apiResponse.status != "success" || apiResponse.data == null) {
                Timber.w("API response status was not successful: ${apiResponse.status}")
                return Result.retry()
            }

            val locations = apiResponse.data
            Timber.i("Anzahl geladener Orte: ${locations.size}")

            // 3. Filter for partner phone number
            val filteredLocations = locations.filter { location ->
                partnerPhone.isNotEmpty() &&
                        location.title?.contains(partnerPhone, ignoreCase = true) == true
            }

            Timber.i("Anzahl gefilterter Orte: ${filteredLocations.size}")

            // Pick the newest location safely (returns null if empty list)
            val currentPosition = filteredLocations.maxByOrNull { it.created_at }
                ?: return Result.success()

            val currentPositionTime = currentPosition.created_at

            // 4. Notify if new position arrived
            if (lastPositionTime != currentPositionTime) {
                if (currentPosition.sos_flag == 1) {
                    Timber.i("Neue SOS-Meldung: $currentPosition")
                } else {
                    Timber.i("Neue Position: $currentPosition")
                }

                applicationContext.watchdogDataStore.edit { preferences ->
                    preferences[LAST_POSITION_KEY] = currentPositionTime
                }

                sendWatchDogNotification(applicationContext, currentPosition)
            }

            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Error during WatchDogWorker execution")
            Result.retry()
        }
    }
}

const val ACTION_CANCEL_NOTIFICATION = "com.almica.shareloc.ACTION_CANCEL_NOTIFICATION"
const val ACTION_MAP_NOTIFICATION = "com.almica.shareloc.ACTION_MAP_NOTIFICATION"
const val EXTRA_NOTIFICATION_ID = "NOTIFICATION_ID"
const val EXTRA_NOTIFICATION_LATITUDE = "NOTIFICATION_LATITUDE"
const val EXTRA_NOTIFICATION_LONGITUDE = "NOTIFICATION_LONGITUDE"

fun sendWatchDogNotification(context: Context, currentPosition: LocationItem) {
    Timber.i("sendWatchDogNotification ${currentPosition.latitude} ${currentPosition.longitude}")
    val channelId = Constants.WATCHDOG_CHANNEL_ID
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val notificationId = 1003

    // SOS cancel notification button intent
    val cancelIntent = Intent(context, NotificationCancelReceiver::class.java).apply {
        action = ACTION_CANCEL_NOTIFICATION
        putExtra(EXTRA_NOTIFICATION_ID, notificationId)
    }

    val pendingCancelIntent = PendingIntent.getBroadcast(
        context,
        notificationId,
        cancelIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val cancelAction = NotificationCompat.Action.Builder(
        android.R.drawable.ic_menu_close_clear_cancel,
        "Cancel",
        pendingCancelIntent
    ).build()

    val mapIntent = Intent(context, NotificationCancelReceiver::class.java).apply {
        action = ACTION_MAP_NOTIFICATION
        putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        putExtra(EXTRA_NOTIFICATION_LATITUDE, currentPosition.latitude)
        putExtra(EXTRA_NOTIFICATION_LONGITUDE, currentPosition.longitude)
    }

    val pendingMapIntent = PendingIntent.getBroadcast(
        context,
        notificationId,
        mapIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val mapAction = NotificationCompat.Action.Builder(
        android.R.drawable.ic_dialog_map,
        "Map",
        pendingMapIntent
    ).build()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            channelId,
            "sos_received",
            NotificationManager.IMPORTANCE_HIGH
        )
        notificationManager.createNotificationChannel(channel)
    }

    val webUrl = "https://www.google.com/maps/search/?api=1&query=${currentPosition.latitude},${currentPosition.longitude}"
    val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    val intent = Intent(Intent.ACTION_VIEW, webUrl.toUri()).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

    val pendingIntent = PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val title = if (currentPosition.sos_flag == 1) "🆘 Neue SOS-Meldung 🆘" else "Neue Position"
    val text = if (currentPosition.sos_flag == 1) "Achtung: Eine SOS-Meldung wurde empfangen!" else "Neue Position empfangen!"

    val notificationBuilder = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(if (currentPosition.sos_flag == 1) android.R.drawable.stat_sys_warning else android.R.drawable.ic_menu_mylocation)
        .setContentTitle("$title ($currentTime)")
        .setContentText(text)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(pendingIntent)
        .setAutoCancel(currentPosition.sos_flag == 0)

    if (currentPosition.sos_flag == 1) {
        notificationBuilder.addAction(cancelAction)
    }
    notificationBuilder.addAction(mapAction)

    notificationManager.notify(notificationId, notificationBuilder.build())
}

class NotificationCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Timber.i("NotificationCancelReceiver onReceive intent.action: ${intent.action}")
        if (intent.action == ACTION_CANCEL_NOTIFICATION) {
            val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
            Timber.i("NotificationCancelReceiver onReceive notificationId: $notificationId")

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(notificationId)
        } else if (intent.action == ACTION_MAP_NOTIFICATION) {
            val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
            Timber.i("NotificationCancelReceiver onReceive notificationId: $notificationId")
            val latitude = intent.getDoubleExtra(EXTRA_NOTIFICATION_LATITUDE, 0.0)
            val longitude = intent.getDoubleExtra(EXTRA_NOTIFICATION_LONGITUDE, 0.0)
            if (latitude != 0.0 && longitude != 0.0) {
                openCoordinatesInGoogleMaps(
                    context,
                    latitude,
                    longitude,
                    context.getString(R.string.i_am_here),
                    zoom = 10
                )
            }
        }
    }
}