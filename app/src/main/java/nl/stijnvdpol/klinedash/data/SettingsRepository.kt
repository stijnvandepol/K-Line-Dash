package nl.stijnvdpol.klinedash.data

import kotlinx.coroutines.flow.Flow
import nl.stijnvdpol.klinedash.core.transport.TransportKind
import nl.stijnvdpol.klinedash.core.transport.WifiTransport

data class ConnectionSettings(
    val kind: TransportKind = TransportKind.SIMULATOR,
    val btAddress: String? = null,
    val btName: String? = null,
    val bleAddress: String? = null,
    val bleName: String? = null,
    val wifiHost: String = WifiTransport.DEFAULT_HOST,
    val wifiPort: Int = WifiTransport.DEFAULT_PORT,
    val autoConnect: Boolean = true,
)

class SettingsRepository(dao: SettingsDao) {
    private val store = TypedSettings(dao, { it.toConnectionSettings() }, { it.toRows() })

    /** Re-emits only when the connection settings themselves change (not for protocol.* rows). */
    val connectionSettings: Flow<ConnectionSettings> = store.flow

    suspend fun update(transform: (ConnectionSettings) -> ConnectionSettings) = store.update(transform)

    private companion object {
        const val KIND = "conn.kind"
        const val BT_ADDRESS = "conn.btAddress"
        const val BT_NAME = "conn.btName"
        const val BLE_ADDRESS = "conn.bleAddress"
        const val BLE_NAME = "conn.bleName"
        const val WIFI_HOST = "conn.wifiHost"
        const val WIFI_PORT = "conn.wifiPort"
        const val AUTO_CONNECT = "conn.autoConnect"
    }

    /**
     * Missing rows mean "never set" and give the default. A row that does not parse is treated the
     * same way, so the user can simply pick again on the connection screen (and it is logged).
     */
    private fun Map<String, String>.toConnectionSettings(): ConnectionSettings {
        val d = ConnectionSettings()
        return ConnectionSettings(
            kind = parsed(KIND, d.kind) { name -> TransportKind.entries.firstOrNull { it.name == name } },
            btAddress = this[BT_ADDRESS],
            btName = this[BT_NAME],
            bleAddress = this[BLE_ADDRESS],
            bleName = this[BLE_NAME],
            wifiHost = this[WIFI_HOST] ?: d.wifiHost,
            wifiPort = parsed(WIFI_PORT, d.wifiPort) { it.toIntOrNull() },
            autoConnect = parsed(AUTO_CONNECT, d.autoConnect) { it.toBooleanStrictOrNull() },
        )
    }

    /** Null value means "remove the row". */
    private fun ConnectionSettings.toRows(): Map<String, String?> = mapOf(
        KIND to kind.name,
        BT_ADDRESS to btAddress,
        BT_NAME to btName,
        BLE_ADDRESS to bleAddress,
        BLE_NAME to bleName,
        WIFI_HOST to wifiHost,
        WIFI_PORT to wifiPort.toString(),
        AUTO_CONNECT to autoConnect.toString(),
    )
}
