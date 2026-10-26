package nl.stijnvdpol.klinedash.service

import android.util.Log
import java.io.IOException
import java.net.NetworkInterface
import java.net.SocketException
import java.security.SecureRandom
import java.util.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nl.stijnvdpol.klinedash.core.export.ExportServerConfig
import nl.stijnvdpol.klinedash.core.export.HttpTripExportServer
import nl.stijnvdpol.klinedash.core.export.TripExportServer
import nl.stijnvdpol.klinedash.core.export.TripFileSource
import nl.stijnvdpol.klinedash.core.export.isLocalNetworkAddress

private const val TAG = "ExportController"
private const val PREFERRED_PORT = 8080
private const val ANY_FREE_PORT = 0
private const val PIN_DIGITS = 4
private const val PIN_BOUND = 10_000
private const val WATCH_INTERVAL_MS = 2_000L
private const val NO_NETWORK_MESSAGE =
    "No Wi-Fi or hotspot network found. Connect the head unit to Wi-Fi or turn on its hotspot, then try again."

/** Interface name prefixes of mobile data and tunnels: an address there is not reachable from the local network. */
private val NON_LAN_INTERFACE_PREFIXES = listOf("rmnet", "ccmni", "pdp", "ppp", "tun", "clat", "v4-", "dummy")

/** A usable IPv4 address of this device on [interfaceName]. */
data class LocalAddress(val interfaceName: String, val host: String)

/** Where the export page can be opened, e.g. "http://192.168.43.50:8080"; mobile data and duplicates left out. */
fun formatAddresses(candidates: List<LocalAddress>, port: Int): List<String> =
    candidates
        .filter { candidate -> NON_LAN_INTERFACE_PREFIXES.none { candidate.interfaceName.startsWith(it) } }
        .map { "http://${it.host}:$port" }
        .distinct()

/** A random PIN of [PIN_DIGITS] digits, leading zeros kept ("0042"). */
fun generatePin(random: Random = SecureRandom()): String =
    random.nextInt(PIN_BOUND).toString().padStart(PIN_DIGITS, '0')

/**
 * Private IPv4 addresses of the up, non-loopback interfaces (Wi-Fi, hotspot, Ethernet): the only
 * ones the export server answers on, see [isLocalNetworkAddress].
 * @throws SocketException when the network interfaces cannot be listed.
 */
fun findLocalAddresses(): List<LocalAddress> =
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { network ->
            network.inetAddresses.toList()
                .filter { isLocalNetworkAddress(it) && !it.isLoopbackAddress }
                .mapNotNull { address -> address.hostAddress?.let { LocalAddress(network.name, it) } }
        }

/** What [ExportController] is doing; shown on the export screen. */
sealed interface ExportState {
    data object Off : ExportState

    /** Open one of [addresses] in a browser on the same network and enter [pin]. */
    data class Running(val addresses: List<String>, val pin: String) : ExportState

    /** [reason] is a sentence for the user. */
    data class Failed(val reason: String) : ExportState
}

/**
 * Starts and stops the Wi-Fi trip export (plain HTTP, see TripExportServer for the documented
 * security exception): port 8080 or, when that is taken, any free port; a new random PIN for every
 * start. The state turns back to [ExportState.Off] when the server stops itself after its idle
 * timeout (polled every [WATCH_INTERVAL_MS]) and when [stop] is called, which the service does
 * when it ends. [start] and [stop] return immediately; watch [state].
 */
class ExportController internal constructor(
    private val files: TripFileSource,
    private val scope: CoroutineScope,
    private val addresses: () -> List<LocalAddress>,
    private val newPin: () -> String,
    private val newServer: (ExportServerConfig, TripFileSource) -> TripExportServer,
) {
    constructor(files: TripFileSource, scope: CoroutineScope) :
        this(files, scope, ::findLocalAddresses, { generatePin() }, { config, source -> HttpTripExportServer(config, source) })

    /** One start-to-stop run; [stop] cancels [job] and stops [server] directly so the port is free again at once. */
    private class Run {
        @Volatile var server: TripExportServer? = null
        lateinit var job: Job
    }

    private val lock = Any()
    private var current: Run? = null
    private val _state = MutableStateFlow<ExportState>(ExportState.Off)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    fun start() {
        synchronized(lock) {
            if (current != null) return
            val run = Run()
            current = run
            run.job = scope.launch { serve(run) }
        }
    }

    fun stop() {
        val run = synchronized(lock) { current.also { current = null } } ?: return
        run.job.cancel()
        // Off the caller's thread (the UI): stopping waits until the listening socket is released.
        scope.launch { run.server?.stop() }
        _state.value = ExportState.Off
    }

    private suspend fun serve(run: Run) {
        try {
            val pin = newPin()
            val bound = bind(run, pin) ?: return
            val urls = formatAddresses(addresses(), bound.port)
            if (urls.isEmpty()) {
                publish(run, ExportState.Failed(NO_NETWORK_MESSAGE))
                return
            }
            publish(run, ExportState.Running(urls, pin))
            while (bound.server.isRunning) delay(WATCH_INTERVAL_MS)
        } catch (e: SocketException) {
            Log.e(TAG, "Could not read the network interfaces", e)
            publish(run, ExportState.Failed("Could not read the network state. Try again."))
        } finally {
            finish(run)
        }
    }

    /** Sets the state unless [stop] already ended this run, so a late result cannot undo it. */
    private fun publish(run: Run, newState: ExportState) {
        synchronized(lock) { if (current === run) _state.value = newState }
    }

    private class Bound(val server: TripExportServer, val port: Int)

    /** Tries the preferred port, then any free one; null (after setting [ExportState.Failed]) when both fail. */
    private fun bind(run: Run, pin: String): Bound? {
        var lastError: IOException? = null
        for (port in listOf(PREFERRED_PORT, ANY_FREE_PORT)) {
            val server = newServer(ExportServerConfig(port = port, pin = pin), files)
            try {
                val bound = server.start()
                run.server = server
                return Bound(server, bound)
            } catch (e: IOException) {
                lastError = e
            }
        }
        Log.e(TAG, "Could not start the export server", lastError)
        publish(run, ExportState.Failed("Could not start the export server. Close other apps that use the network and try again."))
        return null
    }

    /** Ends a run: the server is always stopped (also when [stop] raced with the start); a run that stopped by itself goes back to Off. */
    private fun finish(run: Run) {
        run.server?.stop()
        synchronized(lock) {
            if (current !== run) return
            current = null
            if (_state.value is ExportState.Running) _state.value = ExportState.Off
        }
    }
}
