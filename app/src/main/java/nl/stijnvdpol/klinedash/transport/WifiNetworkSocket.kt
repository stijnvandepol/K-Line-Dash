package nl.stijnvdpol.klinedash.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import java.io.IOException
import java.net.Socket

private const val TAG = "WifiNetworkSocket"

/**
 * A socket bound to the Wi-Fi network, for a Wi-Fi adapter. An adapter's access point has no
 * internet, so a phone keeps mobile data as its default network, and an unbound socket to
 * 192.168.0.10 would go there and fail. Without a Wi-Fi network (or on a head unit without mobile
 * data, where Wi-Fi is the default anyway) the socket stays unbound.
 */
fun wifiBoundSocket(context: Context): Socket {
    val socket = Socket()
    val wifi = wifiNetwork(context) ?: return socket
    try {
        wifi.bindSocket(socket)
    } catch (e: IOException) {
        // The network went away between the lookup and the bind: an unbound socket is the best left.
        Log.w(TAG, "Could not bind the adapter socket to Wi-Fi", e)
    }
    return socket
}

private fun wifiNetwork(context: Context): Network? {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
    // allNetworks is deprecated from API 31 in favour of callbacks, but a one-off lookup at connect
    // time is all this needs, and it still works on every supported version.
    @Suppress("DEPRECATION")
    return connectivity.allNetworks.firstOrNull { network ->
        connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
}
