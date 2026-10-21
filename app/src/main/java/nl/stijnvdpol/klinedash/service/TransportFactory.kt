package nl.stijnvdpol.klinedash.service

import android.content.Context
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.sim.SimulatorTransport
import nl.stijnvdpol.klinedash.core.transport.Transport
import nl.stijnvdpol.klinedash.core.transport.TransportException
import nl.stijnvdpol.klinedash.core.transport.TransportKind
import nl.stijnvdpol.klinedash.core.transport.WifiTransport
import nl.stijnvdpol.klinedash.data.ConnectionSettings
import nl.stijnvdpol.klinedash.transport.BleTransport
import nl.stijnvdpol.klinedash.transport.BtClassicTransport

object TransportFactory {

    /**
     * Returns a factory (called by the connection manager on every (re)connect attempt, so each
     * attempt gets a fresh transport) and the adapter key used to store the detected protocol.
     * Problems such as a missing address are thrown from the factory, so the manager reports them
     * as a connection failure instead of crashing the service.
     */
    fun create(context: Context, settings: ConnectionSettings): Pair<() -> Transport, String> {
        val appContext = context.applicationContext
        return when (settings.kind) {
            TransportKind.SIMULATOR -> {
                val factory: () -> Transport = {
                    SimulatorTransport().also { ObdServiceState.attachSimulator(it) }
                }
                factory to adapterKey(settings)
            }

            TransportKind.BT_CLASSIC -> {
                val address = settings.btAddress
                val factory: () -> Transport = {
                    BtClassicTransport(appContext, address ?: throw noAdapter(appContext))
                }
                factory to adapterKey(settings)
            }

            TransportKind.BLE -> {
                val address = settings.bleAddress
                val factory: () -> Transport = {
                    BleTransport(appContext, address ?: throw noAdapter(appContext))
                }
                factory to adapterKey(settings)
            }

            TransportKind.WIFI -> {
                val host = settings.wifiHost.trim()
                val port = settings.wifiPort
                val factory: () -> Transport = {
                    if (host.isEmpty()) throw noAdapter(appContext)
                    WifiTransport(host, port)
                }
                factory to adapterKey(settings)
            }
        }
    }

    /**
     * Identifies the physical adapter the settings point at: only the fields of the selected
     * transport kind. Also what [ObdService] compares to decide whether the link must be rebuilt.
     */
    fun adapterKey(settings: ConnectionSettings): String = when (settings.kind) {
        TransportKind.SIMULATOR -> "sim"
        TransportKind.BT_CLASSIC -> "bt:${settings.btAddress.orEmpty()}"
        TransportKind.BLE -> "ble:${settings.bleAddress.orEmpty()}"
        TransportKind.WIFI -> "wifi:${settings.wifiHost.trim()}:${settings.wifiPort}"
    }

    private fun noAdapter(context: Context) = TransportException(context.getString(R.string.error_no_adapter))
}
