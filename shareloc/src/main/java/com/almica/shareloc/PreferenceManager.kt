package com.almica.shareloc

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.util.UUID
import androidx.core.content.edit
import timber.log.Timber

class PreferenceManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        Constants.PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun getPhoneNumber(): String {
        return prefs.getString(Constants.KEY_PHONE_NUMBER, "+4915776049418") ?: "+4915776049418"
    }

    fun setPhoneNumber(number: String) {
        prefs.edit { putString(Constants.KEY_PHONE_NUMBER, number) }
    }

    fun getEmailAddress(): String {
        return prefs.getString(Constants.KEY_EMAIL_ADDRESS, "alt.micha@gmail.com") ?: "alt.micha@gmail.com"
    }

    fun setEmailAddress(email: String) {
        prefs.edit { putString(Constants.KEY_EMAIL_ADDRESS, email) }
    }

    fun getImageUrl(): String {
        return prefs.getString(Constants.KEY_IMAGE_URL, "") ?: ""
    }
    fun setImageUrl(url: String) {
        prefs.edit { putString(Constants.KEY_IMAGE_URL, url) }
    }

    fun getIntervalMinutes(): Long {
        return prefs.getLong(Constants.KEY_INTERVAL_MINS, 15L)
    }

    fun setIntervalMinutes(minutes: Long) {
        prefs.edit { putLong(Constants.KEY_INTERVAL_MINS, minutes) }
    }

    fun getOrCreateInstallationId(): String {
        var id = prefs.getString(Constants.KEY_INSTALLATION_ID, null)
        if (id.isNullOrEmpty()) {
            id = UUID.randomUUID().toString()
            prefs.edit { putString(Constants.KEY_INSTALLATION_ID, id) }
        }
        return id
    }

    fun getSendLocationCount(): Int {
        return prefs.getInt(Constants.KEY_SEND_LOCATION_COUNT, 0)
    }

    fun incrementSendLocationCount(): Int {
        val current = getSendLocationCount() + 1
        prefs.edit { putInt(Constants.KEY_SEND_LOCATION_COUNT, current) }
        return current
    }
    fun resetSendLocationCount() {
        prefs.edit { putInt(Constants.KEY_SEND_LOCATION_COUNT, 0) }
    }

    fun getNetworkStateNotificationsEnabled(): Boolean {
        return prefs.getBoolean(Constants.KEY_NETSTATE_NOTIFICATIONS_ENABLED, true)
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(Constants.KEY_NETSTATE_NOTIFICATIONS_ENABLED, enabled) }
    }

    fun isSmsFilterEnabled(): Boolean {
        return prefs.getBoolean(Constants.KEY_SMS_FILTER_ENABLED, true)
    }

    fun setSmsFilterEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(Constants.KEY_SMS_FILTER_ENABLED, enabled) }
    }

    fun isBackgroundCameraEnabled(): Boolean {
        return prefs.getBoolean(Constants.KEY_BACKGROUND_CAMERA_ENABLED, false)
    }

    fun setBackgroundCameraEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(Constants.KEY_BACKGROUND_CAMERA_ENABLED, enabled) }
    }
}

fun getDeviceName(): String {
    val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    val model = Build.MODEL

    val name = if (model.startsWith(manufacturer, ignoreCase = true)) {
        model
    } else {
        "$manufacturer $model"
    }
    Timber.i("Device name: $name")
    return name
}
