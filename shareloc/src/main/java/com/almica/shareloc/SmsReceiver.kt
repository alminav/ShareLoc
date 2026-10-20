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
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import timber.log.Timber

class SmsReceiver : BroadcastReceiver() {
    interface SmsListener {
        fun onSmsReceived(sender: String?, messageBody: String)
    }

    private var listener: SmsListener? = null

    fun setSmsListener(listener: SmsListener) {
        Timber.i("SmsReceiver setSmsListener")
        this.listener = listener
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> {
                Timber.i("SMS empfangen")

                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                val sender = messages?.firstOrNull()?.displayOriginatingAddress.orEmpty()
                val body = messages?.joinToString(separator = "") { it.displayMessageBody.orEmpty() }.orEmpty()
                Timber.i("SMS von $sender: $body")
                for (sms in messages) {
                    val sender = sms.originatingAddress
                    val messageBody = sms.messageBody
                    Timber.i("SmsReceiver Sender: $sender, Body: $messageBody")
                    // Handle your message content here
                    // Daten an das Interface in der MainActivity übergeben
                    val relevantKeywords = listOf("turn on", "turn off", "status", "alert", "mobile", "warnung", "check")
                    val containsKeyword = relevantKeywords.any { keyword ->
                        body.contains(keyword, ignoreCase = true)
                    }
                    if (containsKeyword) {
                        Timber.i("SMS von $sender mit relevanten Schlüsselwörtern empfangen")
                        listener?.onSmsReceived(sender, messageBody)
                            ?: Timber.w("SmsListener ist null")
                    }
                }
                if (shouldShowNotification(context, sender, body)) {
                    showSmsNotification(context, sender, body)
                } else {
                    Timber.i("SMS von $sender gefiltert - keine Benachrichtigung gesendet.")
                }
            }
            ACTION_REPLY_SMS -> {
                Timber.i("SMS Antwort-Aktion empfangen")

                val results = RemoteInput.getResultsFromIntent(intent)
                val replyText = results?.getCharSequence(KEY_TEXT_REPLY)?.toString()
                val recipientPhone = intent.getStringExtra(EXTRA_SENDER)

                if (!replyText.isNullOrEmpty() && !recipientPhone.isNullOrEmpty()) {
                    sendReplySms(context, recipientPhone, replyText)
                }
            }
        }
    }

    private fun shouldShowNotification(context: Context, sender: String, body: String): Boolean {
        if (body.isBlank()) {
            Timber.d("SMS-Filter: Nachrichtentext ist leer.")
            return false
        }

        val preferenceManager = PreferenceManager(context)

        // Wenn SMS-Filter deaktiviert ist, alle nicht-leeren Nachrichten erlauben
        if (!preferenceManager.isSmsFilterEnabled()) {
            Timber.d("SMS-Filter ist deaktiviert - Benachrichtigung wird gesendet.")
            return true
        }

        val configuredNumber = preferenceManager.getPartnerPhoneNumber().trim()

        // Telefonnummern für Vergleich normalisieren
        val normalizedSender = sender.replace(Regex("[^0-9+]"), "")
        val normalizedConfigured = configuredNumber.replace(Regex("[^0-9+]"), "")

        // Regel 1: Absender stimmt mit der in den Einstellungen konfigurierten Telefonnummer überein
        val isConfiguredSender = normalizedConfigured.isNotEmpty() &&
                (normalizedSender.endsWith(normalizedConfigured.takeLast(8)) ||
                 normalizedConfigured.endsWith(normalizedSender.takeLast(8)))

        if (isConfiguredSender) {
            Timber.i("SMS-Filter Akzeptiert: Absender stimmt mit konfigurierter Nummer ($sender) überein.")
            return true
        }

        // Regel 2: Inhalt enthält relevante Schlüsselwörter für ShareLoc
        val relevantKeywords = listOf("turn on", "turn off", "status", "alert", "mobile", "warnung", "check")
        val containsKeyword = relevantKeywords.any { keyword ->
            body.contains(keyword, ignoreCase = true)
        }

        if (containsKeyword) {
            Timber.i("SMS-Filter Akzeptiert: Inhalt enthält relevantes Schlüsselwort.")
            return true
        }

        Timber.i("SMS-Filter Abgelehnt: Nachricht von $sender erfüllt keine Filterkriterien.")
        return false
    }

    private fun showSmsNotification(context: Context, sender: String, body: String) {
        val channelId = CHANNEL_ID
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "SMS Benachrichtigungen",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Benachrichtigungen für empfangene SMS"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val webHomeIntentUri = "http://almica.bplaced.net/showImages.php".toUri()
        val webIntent = Intent(Intent.ACTION_VIEW, webHomeIntentUri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
//        val activityIntent = Intent(context, MainActivity::class.java).apply {
//            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
//        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            webIntent, //activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (sender.isNotBlank()) "SMS von $sender" else "SMS empfangen"
        val text = body.ifBlank { "Neue SMS empfangen" }
        val largeIcon = getLargeIconBitmap(context)

        val notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (largeIcon != null) {
            notificationBuilder.setLargeIcon(largeIcon)
        }

        // Add direct reply action if sender is known
        if (sender.isNotBlank()) {
            val replyIntent = Intent(context, SmsReceiver::class.java).apply {
                action = ACTION_REPLY_SMS
                putExtra(EXTRA_SENDER, sender)
            }

            val replyPendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val replyPendingIntent = PendingIntent.getBroadcast(
                context,
                sender.hashCode(),
                replyIntent,
                replyPendingIntentFlags
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

            notificationBuilder.addAction(replyAction)
        }

        val notification = notificationBuilder.build()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } else {
            Timber.w("POST_NOTIFICATIONS permission not granted. Cannot show SMS notification.")
        }
    }

    private fun sendReplySms(context: Context, phone: String, message: String) {
        val hasSmsPermission = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.SEND_SMS
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
                Timber.i("Reply SMS sent to $phone: $message")

                // Update notification to inform user reply was sent
                val notificationManager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                val updatedNotification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Antwort gesendet an $phone")
                    .setContentText(message)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setAutoCancel(true)
                    .build()

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                ) {
                    notificationManager.notify(NOTIFICATION_ID, updatedNotification)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error sending reply SMS")
            }
        } else {
            Timber.w("SEND_SMS permission not granted. Cannot send reply SMS.")
        }
    }

    private fun getLargeIconBitmap(context: Context): Bitmap? {
        return try {
            ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap()
        } catch (e: Exception) {
            Timber.e(e, "Error generating large icon bitmap")
            null
        }
    }

    companion object {
        const val ACTION_REPLY_SMS = "com.almica.shareloc.ACTION_REPLY_SMS"
        const val EXTRA_SENDER = "extra_sender"
        const val KEY_TEXT_REPLY = "key_text_reply"
        const val CHANNEL_ID = "sms_notifications_channel"
        const val NOTIFICATION_ID = 1001
    }
}
