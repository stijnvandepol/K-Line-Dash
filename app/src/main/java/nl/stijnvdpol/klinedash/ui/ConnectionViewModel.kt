package nl.stijnvdpol.klinedash.ui

import android.annotation.SuppressLint
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.stijnvdpol.klinedash.AppGraph
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.core.transport.TransportKind
import nl.stijnvdpol.klinedash.data.ConnectionSettings
import nl.stijnvdpol.klinedash.service.ObdServiceState
import nl.stijnvdpol.klinedash.service.TransportFactory
import nl.stijnvdpol.klinedash.transport.BtDeviceInfo
import nl.stijnvdpol.klinedash.transport.bondedDevices as queryBondedDevices
import nl.stijnvdpol.klinedash.transport.scanBle

class ConnectionViewModel(private val graph: AppGraph) : ViewModel() {
    // The application context: it lives as long as the process, so holding it leaks nothing.
    @SuppressLint("StaticFieldLeak")
    private val context = graph.appContext
    private val repository = graph.settingsRepository

    /** Null until the first read from the database. */
    val settings: StateFlow<ConnectionSettings?> = repository.connectionSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val connectionState: StateFlow<ConnectionState> = ObdServiceState.connectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionState.Idle)

    private val _bondedDevices = MutableStateFlow<List<BtDeviceInfo>>(emptyList())
    val bondedDevices: StateFlow<List<BtDeviceInfo>> = _bondedDevices.asStateFlow()

    private val _bleResults = MutableStateFlow<List<BtDeviceInfo>>(emptyList())
    val bleResults: StateFlow<List<BtDeviceInfo>> = _bleResults.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var scanJob: Job? = null
    private var wifiJob: Job? = null
    private var noticeJob: Job? = null

    fun refreshBonded() {
        _bondedDevices.value = try {
            queryBondedDevices(context)
        } catch (e: RuntimeException) {
            // Missing permission is already handled inside; anything else from the stack ends up as
            // an empty list, whose hint tells the user to check Bluetooth and the permission.
            Log.w(TAG, "Listing bonded devices failed", e)
            emptyList()
        }
    }

    fun startScan() {
        scanJob?.cancel()
        _bleResults.value = emptyList()
        scanJob = viewModelScope.launch {
            _scanning.value = true
            try {
                scanBle(context, SCAN_DURATION_MS).collect { device ->
                    _bleResults.update { list ->
                        if (list.any { it.address == device.address }) list
                        else (list + device).sortedByDescending { it.looksLikeObd }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // SecurityException (permission) or IllegalStateException (Bluetooth off), Dutch message.
                showNotice(context.getString(R.string.ble_scan_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                _scanning.value = false
            }
        }
    }

    fun selectKind(kind: TransportKind) = updateSettings { it.copy(kind = kind) }

    fun selectBtDevice(device: BtDeviceInfo) = updateSettings { it.copy(btAddress = device.address, btName = device.name) }

    fun selectBleDevice(device: BtDeviceInfo) = updateSettings { it.copy(bleAddress = device.address, bleName = device.name) }

    fun setAutoConnect(enabled: Boolean) = updateSettings { it.copy(autoConnect = enabled) }

    /**
     * Debounced: every settings change rebuilds the running link, so do not do that per keystroke.
     */
    fun setWifi(host: String, port: Int) {
        wifiJob?.cancel()
        wifiJob = viewModelScope.launch {
            delay(WIFI_DEBOUNCE_MS)
            repository.update { it.copy(wifiHost = host, wifiPort = port) }
        }
    }

    fun disconnect() {
        viewModelScope.launch { ObdServiceState.link.value?.stop() }
    }

    fun forgetProtocol() {
        viewModelScope.launch {
            val link = ObdServiceState.link.value
            if (link != null) {
                link.forgetSavedProtocol()
            } else {
                val key = TransportFactory.adapterKey(repository.connectionSettings.first())
                graph.protocolStore.clearProtocol(key)
            }
            showNotice(context.getString(R.string.notice_protocol_forgotten))
        }
    }

    private fun updateSettings(transform: (ConnectionSettings) -> ConnectionSettings) {
        viewModelScope.launch { repository.update(transform) }
    }

    private fun showNotice(text: String) {
        _notice.value = text
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            _notice.value = null
        }
    }

    private companion object {
        const val TAG = "ConnectionViewModel"
        const val SCAN_DURATION_MS = 10_000L
        const val WIFI_DEBOUNCE_MS = 700L
        const val NOTICE_MS = 5_000L
    }
}
