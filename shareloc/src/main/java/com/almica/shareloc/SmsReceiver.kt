package com.almica.shareloc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import timber.log.Timber

class SmsReceiver : BroadcastReceiver() {

    fun interface SmsListener {
        fun onSmsReceived(sender: String, messageBody: String)
    }

    private var listener: SmsListener? = null

    fun setSmsListener(listener: SmsListener?) {
        Timber.i("SmsReceiver setSmsListener")
        this.listener = listener
    }

    override fun onReceive(context: Context, intent: Intent) {
        Timber.i("SmsReceiver onReceive ${intent.action}")
        try {
            when (intent.action) {
                Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> handleSmsReceived(context, intent)
                ACTION_REPLY_SMS -> handleReplySms(context, intent)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error processing broadcast in SmsReceiver")
        }
    }

    private fun handleSmsReceived(context: Context, intent: Intent) {
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) {
            Timber.w("SMS Intent received, but no messages extracted.")
            return
        }

        val sender = messages.firstOrNull()?.displayOriginatingAddress.orEmpty()
        val body = messages.joinToString(separator = "") { it.displayMessageBody.orEmpty() }
        Timber.i("SMS from $sender: $body")

        if (body.containsRelevantKeyword()) {
            Timber.i("SMS from $sender contains relevant keywords.")
            listener?.onSmsReceived(sender, body) ?: Timber.w("SmsListener is null")
        }

        if (shouldShowNotification(context, sender, body)) {
            showSmsNotification(context, sender, body)
        } else {
            Timber.i("SMS from $sender filtered - no notification sent.")
        }
    }

    private fun handleReplySms(context: Context, intent: Intent) {
        val results = RemoteInput.getResultsFromIntent(intent)
        val replyText = results?.getCharSequence(KEY_TEXT_REPLY)?.toString()
        val recipientPhone = intent.getStringExtra(EXTRA_SENDER)

        if (!replyText.isNullOrEmpty() && !recipientPhone.isNullOrEmpty()) {
            sendReplySms(context, recipientPhone, replyText)
        }
    }

    private fun shouldShowNotification(context: Context, sender: String, body: String): Boolean {
        if (body.isBlank()) return false

        val preferenceManager = PreferenceManager(context)
        if (!preferenceManager.isSmsFilterEnabled()) return true

        val configuredNumber = preferenceManager.getPartnerPhoneNumber().trim()
        val isConfiguredSender = configuredNumber.isNotEmpty() &&
                PhoneNumberUtils.compare(context, sender, configuredNumber)

        if (isConfiguredSender) return true

        return body.containsRelevantKeyword()
    }

    private fun showSmsNotification(context: Context, sender: String, body: String) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        createNotificationChannel(notificationManager)

        val pendingIntent = createContentPendingIntent(context, body)
        val title = if (sender.isNotBlank()) "SMS von $sender" else "SMS empfangen"
        val text = body.ifBlank { "Neue SMS empfangen" }
        val largeIcon = getLargeIconBitmap(context)

        val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        largeIcon?.let { notificationBuilder.setLargeIcon(it) }

        if (sender.isNotBlank()) {
            addReplyAction(context, notificationBuilder, sender)
        }

        if (hasNotificationPermission(context)) {
            val notificationId = if (sender.isNotBlank()) sender.hashCode() else NOTIFICATION_ID
            notificationManager.notify(notificationId, notificationBuilder.build())
        } else {
            Timber.w("POST_NOTIFICATIONS permission not granted. Cannot show SMS notification.")
        }
    }

    private fun createContentPendingIntent(context: Context, body: String): PendingIntent {
        val coordinates = extractCoordinates(body)
        val intent = if (coordinates != null) { // ==> MainActivity onNewIntent
            Intent(context, MainActivity::class.java).apply {
                Timber.i("createContentPendingIntent MainActivity coordinates: $coordinates")
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(Constants.SMS_LATITUDE, coordinates.first)
                putExtra(Constants.SMS_LONGITUDE, coordinates.second)
            }
        } else if (body.containsImageKeyword()) {
            Timber.i("createContentPendingIntent showImages.php")
            Intent(Intent.ACTION_VIEW, "http://almica.bplaced.net/showImages.php".toUri()).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        } else {
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        }

        return PendingIntent.getActivity(
            context,
            coordinates.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun addReplyAction(context: Context, builder: NotificationCompat.Builder, sender: String) {
        val replyIntent = Intent(context, SmsReceiver::class.java).apply {
            action = ACTION_REPLY_SMS
            putExtra(EXTRA_SENDER, sender)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            sender.hashCode(),
            replyIntent,
            flags
        )

        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel("Antworten...")
            .build()

        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Antworten",
            replyPendingIntent
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()

        builder.addAction(replyAction)
    }

    private fun sendReplySms(context: Context, phone: String, message: String) {
        val hasSmsPermission = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasSmsPermission) {
            Timber.w("SEND_SMS permission not granted. Cannot send reply SMS.")
            return
        }

        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager?.sendTextMessage(phone, null, message, null, null)
            Timber.i("Reply SMS sent to $phone: $message")

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (notificationManager != null && hasNotificationPermission(context)) {
                val updatedNotification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Antwort gesendet an $phone")
                    .setContentText(message)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setAutoCancel(true)
                    .build()

                notificationManager.notify(phone.hashCode(), updatedNotification)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error sending reply SMS")
        }
    }

    private fun createNotificationChannel(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SMS Benachrichtigungen",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Benachrichtigungen für empfangene SMS"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun getLargeIconBitmap(context: Context): Bitmap? {
        return try {
            ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap()
        } catch (e: Exception) {
            Timber.e(e, "Error generating large icon bitmap")
            null
        }
    }

    private fun String.containsRelevantKeyword(): Boolean {
        return RELEVANT_KEYWORDS.any { keyword -> this.contains(keyword, ignoreCase = true) }
    }
    private fun String.containsImageKeyword(): Boolean {
        return IMAGE_KEYWORDS.any { keyword -> this.contains(keyword, ignoreCase = true) }
    }
    private fun extractCoordinates(body: String): Pair<Double, Double>? {
        val matches = COORDINATE_REGEX.findAll(body)
            .mapNotNull { it.groupValues[1].toDoubleOrNull() }
            .toList()

        return if (matches.size >= 2) Pair(matches[0], matches[1]) else null
    }

    companion object {
        private val RELEVANT_KEYWORDS = listOf("turn on", "turn off", "status", "foto", "photo", "position", "check")
        private val IMAGE_KEYWORDS = listOf("foto", "photo")
        private val COORDINATE_REGEX = Regex("""(-?\d+(?:\.\d+)?)\s*°""")

        const val ACTION_REPLY_SMS = "com.almica.shareloc.ACTION_REPLY_SMS"
        const val EXTRA_SENDER = "extra_sender"
        const val KEY_TEXT_REPLY = "key_text_reply"
        const val CHANNEL_ID = "sms_notifications_channel"
        const val NOTIFICATION_ID = 1001
    }
}