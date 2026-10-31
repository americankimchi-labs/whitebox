package com.americankimchilabs.whitebox.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.americankimchilabs.whitebox.core.ConnectivityPort
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Online = the default network reaches the internet (validated), which is what the car assistant needs. */
class PlatformConnectivity(context: Context) : ConnectivityPort {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun online(): Flow<Boolean> = callbackFlow {
        fun current() = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        trySend(current())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            }
            // The default network is gone; the active network may still report it at this moment, so do not ask.
            override fun onLost(network: Network) {
                trySend(false)
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
