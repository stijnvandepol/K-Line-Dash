package nl.stijnvdpol.klinedash.data

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

class SettingsRepository(private val dao: SettingsDao) {
    private val writeLock = Mutex()

    /** Re-emits on every row change (also protocol.* rows), hence the distinctUntilChanged. */
    val connectionSettings: Flow<ConnectionSettings> =
        dao.observeAll().map { rows -> rows.toConnectionSettings() }.distinctUntilChanged()

    /** Read-modify-write under a lock; only rows that actually changed are written. */
    suspend fun update(transform: (ConnectionSettings) -> ConnectionSettings) {
        writeLock.withLock {
            val old = dao.getAll().toConnectionSettings()
            val new = transform(old)
            if (new == old) return
            val oldRows = old.toRows()
            for ((key, value) in new.toRows()) {
                if (oldRows[key] == value) continue
                if (value == null) dao.delete(key) else dao.upsert(SettingEntry(key, value))
            }
        }
    }

    private companion object {
        const val TAG = "SettingsRepository"
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
     * Missing rows mean "never set" and give the default. A row that does not parse (written by a
     * newer app version, or corrupted) is treated the same way, so the user can simply pick again
     * on the connection screen, but it is logged instead of passing silently.
     */
    private fun List<SettingEntry>.toConnectionSettings(): ConnectionSettings {
        val map = associate { it.key to it.value }
        val d = ConnectionSettings()
        return ConnectionSettings(
            kind = map.parsed(KIND, d.kind) { name -> TransportKind.entries.firstOrNull { it.name == name } },
            btAddress = map[BT_ADDRESS],
            btName = map[BT_NAME],
            bleAddress = map[BLE_ADDRESS],
            bleName = map[BLE_NAME],
            wifiHost = map[WIFI_HOST] ?: d.wifiHost,
            wifiPort = map.parsed(WIFI_PORT, d.wifiPort) { it.toIntOrNull() },
            autoConnect = map.parsed(AUTO_CONNECT, d.autoConnect) { it.toBooleanStrictOrNull() },
        )
    }

    private fun <T> Map<String, String>.parsed(key: String, default: T, parse: (String) -> T?): T {
        val raw = this[key] ?: return default
        return parse(raw) ?: default.also { Log.w(TAG, "Unreadable setting $key=\"$raw\", using $it") }
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
