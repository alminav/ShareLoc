package com.almica.shareloc

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class NetworkMonitor(context: Context) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isMobileDataActive = MutableStateFlow(false)
    val isMobileDataActive: StateFlow<Boolean> = _isMobileDataActive

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            // Prüft, ob das aktive Netzwerk zellulär ist UND tatsächliche Internetkapazität besitzt
            val isCellular = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            _isMobileDataActive.value = isCellular && hasInternet
        }

        override fun onLost(network: Network) {
            // Falls die Verbindung komplett verloren geht, prüfen wir den Gesamtstatus neu
            _isMobileDataActive.value = checkCurrentStatus()
        }
    }

    init {
        // Initialer Check beim Start der App
        _isMobileDataActive.value = checkCurrentStatus()

        // Registriert den Callback für kontinuierliche Live-Updates
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    private fun checkCurrentStatus(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    fun unregister() {
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }
}
