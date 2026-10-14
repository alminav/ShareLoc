package com.almica.shareloc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.google.gson.GsonBuilder
import com.google.gson.Strictness
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import timber.log.Timber

sealed interface LocationUiState {
    data object Idle : LocationUiState
    data object Loading : LocationUiState
    data class Success(val message: String) : LocationUiState
    data class Error(val message: String) : LocationUiState
}

object NetworkClient {
    private const val BASE_URL = "http://almica.bplaced.net/"

    private val gson = GsonBuilder()
        .setStrictness(Strictness.LENIENT)
        .create()

    private val okHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val request = chain.request()
            Timber.i("HTTP Request: ${request.method} ${request.url}")
            chain.proceed(request)
        }
        .build()

    val bplacedApiService: BplacedApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(BplacedApiService::class.java)
    }
}

class BplacedViewModel : ViewModel() {

    private val apiService: BplacedApiService = NetworkClient.bplacedApiService

    private val _uiState = MutableStateFlow<LocationUiState>(LocationUiState.Idle)
    val uiState: StateFlow<LocationUiState> = _uiState.asStateFlow()

    fun sendLocation(title: String, latitude: Double, longitude: Double, altitude: Double) {
        _uiState.value = LocationUiState.Loading

        viewModelScope.launch {
            try {
                val response = apiService.saveLocation(title, "", latitude, longitude, altitude, null)
                val body = response.body()

                if (response.isSuccessful && (body != null)) {
                    if (body.status == "success") {
                        _uiState.value = LocationUiState.Success(body.message)
                    } else {
                        _uiState.value = LocationUiState.Error("Server-Fehler: ${body.message}")
                    }
                } else {
                    _uiState.value = LocationUiState.Error("Fehler beim Server: ${response.code()}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.net.UnknownHostException) {
                Timber.w("Unable to resolve host (network/DNS unavailable): ${e.message}")
                _uiState.value = LocationUiState.Error("Keine Internetverbindung oder Host nicht erreichbar.")
            } catch (e: Exception) {
                Timber.e(e, "Error sending location to bplaced")
                _uiState.value = LocationUiState.Error("Netzwerkfehler: ${e.localizedMessage}")
            }
        }
    }
}
