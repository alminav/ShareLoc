package com.almica.shareloc

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// DataStore Extension auf dem Context anlegen
val Context.dataStore by preferencesDataStore(name = "user_preferences")

class PhonePreferencesManager(private val context: Context) {

    companion object {
        // Schlüssel, unter dem der JSON-String gespeichert wird
        private val PHONE_NUMBERS_KEY = stringPreferencesKey("phone_numbers_list")
    }

    // Stream der Telefonnummern aus den Preferences lesen
    val phoneNumbersFlow: Flow<List<String>> = context.dataStore.data
        .map { preferences ->
            val jsonString = preferences[PHONE_NUMBERS_KEY]
            if (!jsonString.isNullOrEmpty()) {
                try {
                    Json.decodeFromString<List<String>>(jsonString)
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
        }

    // Gesamte Liste aktualisieren und speichern
    suspend fun savePhoneNumbers(numbers: List<String>) {
        val jsonString = Json.encodeToString(numbers)
        context.dataStore.edit { preferences ->
            preferences[PHONE_NUMBERS_KEY] = jsonString
        }
    }
}
