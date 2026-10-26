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
import nl.stijnvdpol.klinedash.transport.BleScanNotice
import nl.stijnvdpol.klinedash.transport.BtDeviceInfo
import nl.stijnvdpol.klinedash.transport.ClassicPairing
import nl.stijnvdpol.klinedash.transport.PairingEvent
import nl.stijnvdpol.klinedash.transport.PairingFailure
import nl.stijnvdpol.klinedash.transport.ScanBlockedException
import nl.stijnvdpol.klinedash.transport.bondedDevices as queryBondedDevices
import nl.stijnvdpol.klinedash.transport.btDeviceInfo
import nl.stijnvdpol.klinedash.transport.classicSearchNotice
import nl.stijnvdpol.klinedash.transport.mergeClassicResult
import nl.stijnvdpol.klinedash.transport.mergeScanResult
import nl.stijnvdpol.klinedash.transport.next
import nl.stijnvdpol.klinedash.transport.nextAttempt
import nl.stijnvdpol.klinedash.transport.pairClassic
import nl.stijnvdpol.klinedash.transport.removeClassicBond
import nl.stijnvdpol.klinedash.transport.scanBle
import nl.stijnvdpol.klinedash.transport.searchClassic

private val ValidPorts = 1..65535
private const val MAX_PORT_DIGITS = 5

/** What the port field keeps of what was typed: digits only, at most five. */
internal fun portDigits(typed: String): String = typed.filter { it.isDigit() }.take(MAX_PORT_DIGITS)

/** The port typed so far, or null while it is not (yet) a usable TCP port; only a usable one is stored. */
internal fun validPort(digits: String): Int? = digits.toIntOrNull()?.takeIf { it in ValidPorts }

class ConnectionViewModel(private val graph: AppGraph) : ViewModel() {
    // The application context: it lives as long as the process, so holding it leaks nothing.
    @SuppressLint("StaticFieldLeak")
    private val context = graph.appContext
    private val repository = graph.settingsRepository

    /** Null until the first read from the database, which the app starts at launch. */
    val settings: StateFlow<ConnectionSettings?> = graph.connectionSettings

    val connectionState: StateFlow<ConnectionState> = ObdServiceState.connectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionState.Idle)

    val engineRunning: StateFlow<Boolean> = ObdServiceState.simulatorEngineRunning

    private val _bondedDevices = MutableStateFlow<List<BtDeviceInfo>>(emptyList())
    val bondedDevices: StateFlow<List<BtDeviceInfo>> = _bondedDevices.asStateFlow()

    private val _bleResults = MutableStateFlow<List<BtDeviceInfo>>(emptyList())
    val bleResults: StateFlow<List<BtDeviceInfo>> = _bleResults.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _scanNotice = MutableStateFlow<BleScanNotice?>(null)

    /** Why the last scan did not run, or what to check when it found nothing; null while there is nothing to say. */
    internal val scanNotice: StateFlow<BleScanNotice?> = _scanNotice.asStateFlow()

    private val _classicFound = MutableStateFlow<List<BtDeviceInfo>>(emptyList())

    /** Bluetooth Classic devices of the last search (those already paired are flagged), likely OBD adapters first. */
    val classicFound: StateFlow<List<BtDeviceInfo>> = _classicFound.asStateFlow()

    private val _classicSearching = MutableStateFlow(false)
    val classicSearching: StateFlow<Boolean> = _classicSearching.asStateFlow()

    private val _classicNotice = MutableStateFlow<BleScanNotice?>(null)

    /** Why the last Bluetooth Classic search did not run, or what to check when it found nothing. */
    internal val classicNotice: StateFlow<BleScanNotice?> = _classicNotice.asStateFlow()

    private val _pairing = MutableStateFlow<ClassicPairing>(ClassicPairing.Idle)

    /** Where the pairing of the tapped adapter stands; after [ClassicPairing.Paired] it is also the selected adapter. */
    internal val pairing: StateFlow<ClassicPairing> = _pairing.asStateFlow()

    private val message = TimedMessage(viewModelScope, NOTICE_MS)

    /** The answer to the last tap (a failed scan, a forgotten protocol), for a few seconds. */
    val notice: StateFlow<String?> = message.text

    private var scanJob: Job? = null
    private var searchJob: Job? = null
    private var pairJob: Job? = null
    private var wifiJob: Job? = null

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

    /**
     * Starts a scan unless one is running: the wizard and the Scan button can ask at nearly the same
     * moment, and restarting would burn through Android's budget of scans per 30 seconds.
     */
    fun startScan() {
        if (scanJob?.isActive == true) return
        _bleResults.value = emptyList()
        _scanNotice.value = null
        scanJob = viewModelScope.launch {
            _scanning.value = true
            try {
                val report = scanBle(context) { device -> _bleResults.update { list -> mergeScanResult(list, device) } }
                if (_bleResults.value.isEmpty()) _scanNotice.value = BleScanNotice.NothingFound(report.failureCode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ScanBlockedException) {
                _scanNotice.value = BleScanNotice.Blocked(e.block)
            } catch (e: Exception) {
                // SecurityException (permission) or IllegalStateException (Bluetooth went off), English message.
                message.show(context.getString(R.string.ble_scan_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                _scanning.value = false
            }
        }
    }

    /** Says the search cannot start until the Bluetooth permission is granted. */
    internal fun showClassicPermissionMissing() {
        _classicNotice.value = BleScanNotice.PermissionMissing
    }

    /** Searches for Bluetooth Classic devices unless a search or a pairing is running. */
    fun startClassicSearch() {
        if (searchJob?.isActive == true || pairing.value is ClassicPairing.Pairing) return
        _classicFound.value = emptyList()
        _classicNotice.value = null
        _pairing.update { it.next(PairingEvent.Dismiss) }
        searchJob = viewModelScope.launch {
            _classicSearching.value = true
            try {
                val isStarted = searchClassic(context) { device -> _classicFound.update { list -> mergeClassicResult(list, device) } }
                _classicNotice.value = classicSearchNotice(isStarted, _classicFound.value.isNotEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (e: ScanBlockedException) {
                _classicNotice.value = BleScanNotice.Blocked(e.block)
            } catch (e: Exception) {
                // SecurityException (permission) or IllegalStateException (Bluetooth went off), English message.
                message.show(context.getString(R.string.bt_search_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                _classicSearching.value = false
            }
        }
    }

    /**
     * Pairs with [device] from the search list; once it is paired it is the selected adapter. A device
     * that is already paired is selected without a new bond. Ignored while another pairing runs. Ends
     * the search first: bonding fails while discovery runs.
     */
    fun pairWith(device: BtDeviceInfo, attempt: Int = 0) = startPairing(device, attempt) { true }

    /**
     * Removes the pairing of the selected adapter and pairs again from scratch: for an adapter that was
     * re-flashed and forgot its link keys while this unit kept its own. Stops the link first, which
     * would otherwise keep connecting during the pairing.
     */
    fun pairAgain(address: String, name: String?) {
        startPairing(btDeviceInfo(name, address), attempt = 0) {
            ObdServiceState.link.value?.stop()
            removeClassicBond(context, address)
        }
    }

    /** Tries the failed pairing again; with the next PIN only when the adapter turned the last one down. */
    fun retryPairing() {
        val failed = pairing.value as? ClassicPairing.Failed ?: return
        pairWith(failed.device, failed.nextAttempt())
    }

    private fun startPairing(device: BtDeviceInfo, attempt: Int, prepare: suspend () -> Boolean) {
        if (pairJob?.isActive == true) return
        searchJob?.cancel()
        _pairing.update { it.next(PairingEvent.Start(device, attempt)) }
        pairJob = viewModelScope.launch {
            val result = try {
                if (prepare()) pairClassic(context, device, attempt) else blocked(PairingFailure.UNPAIR_FAILED)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                Log.w(TAG, "Pairing refused", e)
                blocked(PairingFailure.PERMISSION_MISSING)
            }
            _pairing.value = result
            if (result is ClassicPairing.Paired) {
                selectBtDevice(result.device)
                _classicFound.update { list -> list.filter { it.address != result.device.address } }
                refreshBonded()
            }
        }
    }

    private fun blocked(reason: PairingFailure) = _pairing.value.next(PairingEvent.Blocked(reason))

    fun selectKind(kind: TransportKind) = updateSettings { it.copy(kind = kind) }

    /**
     * Chooses the simulator and, once that is stored, runs [then] (starting the link): the link must
     * see the new choice. Also drives the simulated GPS circuit, so someone trying the app at home
     * sees laps and routes too, as the setup wizard does.
     */
    fun useSimulator(then: () -> Unit) {
        viewModelScope.launch {
            repository.update { it.copy(kind = TransportKind.SIMULATOR) }
            graph.gpsSettingsRepository.update { it.copy(useSimulatedGps = true) }
            then()
        }
    }

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

    fun setEngineRunning(running: Boolean) = ObdServiceState.setSimulatorEngineRunning(running)

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
            message.show(context.getString(R.string.notice_protocol_forgotten))
        }
    }

    private fun updateSettings(transform: (ConnectionSettings) -> ConnectionSettings) {
        viewModelScope.launch { repository.update(transform) }
    }

    private companion object {
        const val TAG = "ConnectionViewModel"
        const val WIFI_DEBOUNCE_MS = 700L
        const val NOTICE_MS = 5_000L
    }
}
