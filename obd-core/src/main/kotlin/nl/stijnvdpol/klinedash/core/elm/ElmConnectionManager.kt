package nl.stijnvdpol.klinedash.core.elm

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.stijnvdpol.klinedash.core.log.CommLog
import nl.stijnvdpol.klinedash.core.log.LogKind
import nl.stijnvdpol.klinedash.core.log.RingCommLog
import nl.stijnvdpol.klinedash.core.transport.Transport
import nl.stijnvdpol.klinedash.core.transport.TransportException

/**
 * Exponential reconnect delay: [initialMs] * [factor]^(attempt-1), capped at [maxMs], with
 * +/- [jitter] (fraction) of random spread so a flock of retries does not synchronise.
 *
 * Deliberately no maximum attempt count: ignition off is the normal state of a parked car, so
 * [ElmConnectionManager] keeps retrying at the capped delay until the ECU answers or [ElmLink.stop]
 * is called. The cap keeps that cheap; reconnecting (connect + ATZ init) is idempotent.
 */
data class BackoffPolicy(
    val initialMs: Long = 1_000,
    val maxMs: Long = 30_000,
    val factor: Double = 2.0,
    val jitter: Double = 0.2,
) {
    /** Delay before reconnect [attempt] (1 = first retry). Never exceeds [maxMs]. */
    fun delayFor(attempt: Int, random: Random): Long {
        val exponent = (attempt - 1).coerceAtLeast(0)
        val base = min(maxMs.toDouble(), initialMs * factor.pow(exponent))
        val spread = base * jitter
        val jittered = base + (random.nextDouble() * 2.0 - 1.0) * spread
        return jittered.toLong().coerceIn(0L, maxMs)
    }
}

/**
 * [ElmLink] implementation: owns the transport lifecycle (connect, init, reconnect with backoff)
 * and serialises commands with a mutex so exactly one is in flight.
 *
 * Failure model: a [TransportException] (link dropped, init failed, ECU silent) is retried
 * forever with [backoff], because the usual cause is the driver simply not having the ignition
 * on yet. A [SecurityException] (missing Bluetooth permission) is not retryable and ends in
 * [ConnectionState.Failed]. A fresh transport comes from [transportFactory] for every attempt.
 *
 * [execute] rethrows the [TransportException] that killed the link, so a poller can stop its
 * cycle and wait for [ConnectionState.Connected] again; until then it throws [IllegalStateException].
 */
class ElmConnectionManager(
    private val transportFactory: () -> Transport,
    private val adapterKey: String,
    private val protocolStore: ProtocolStore,
    private val scope: CoroutineScope,
    override val log: CommLog = RingCommLog(),
    private val backoff: BackoffPolicy = BackoffPolicy(),
    private val random: Random = Random.Default,
) : ElmLink {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = _state

    private val lifecycleLock = Any()
    private var job: Job? = null
    private val commandMutex = Mutex()

    /** The live, initialised connection; null whenever commands must not be sent. */
    @Volatile
    private var link: Link? = null

    private class Link(val session: Elm327Session, val lost: CompletableDeferred<String>) {
        /** Guarded by commandMutex. */
        var consecutiveTimeouts = 0
    }

    override fun start() {
        synchronized(lifecycleLock) {
            if (job?.isActive == true) return
            job = scope.launch { runLoop() }
        }
    }

    override suspend fun stop() {
        val running = synchronized(lifecycleLock) {
            val j = job
            job = null
            j
        }
        link = null
        // The loop's finally block closes the transport.
        running?.cancelAndJoin()
        _state.value = ConnectionState.Idle
    }

    override suspend fun execute(command: String, timeoutMs: Long): ElmResult = commandMutex.withLock {
        val current = link
            ?: throw IllegalStateException("ELM link is not connected (state: ${_state.value})")
        try {
            val result = current.session.execute(command, timeoutMs)
            // A hung clone keeps the socket open but never prompts again; only a reconnect
            // (fresh socket + ATZ) brings it back.
            if (result is ElmResult.Timeout) {
                if (++current.consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) {
                    log.record(LogKind.ERROR, "$MAX_CONSECUTIVE_TIMEOUTS timeouts in a row, reconnecting")
                    if (link === current) link = null
                    current.lost.complete("The adapter stopped responding")
                }
            } else {
                current.consecutiveTimeouts = 0
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransportException) {
            log.record(LogKind.ERROR, "Link lost while sending \"$command\": ${e.message}")
            if (link === current) link = null
            current.lost.complete(e.message ?: "Connection lost")
            throw e
        }
    }

    override suspend fun forgetSavedProtocol() {
        protocolStore.clearProtocol(adapterKey)
    }

    private suspend fun runLoop() {
        var attempt = 0
        while (true) {
            val transport = try {
                transportFactory()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Configuration problem (e.g. no adapter chosen): retrying cannot fix it, and an
                // exception escaping here would crash the whole process.
                val msg = e.message ?: e.javaClass.simpleName
                log.record(LogKind.ERROR, "Cannot create transport: $msg")
                _state.value = ConnectionState.Failed(msg)
                return
            }
            var reason = "Unknown error"
            try {
                _state.value = ConnectionState.Connecting(transport.description)
                log.record(LogKind.INFO, "Connecting to ${transport.description}")
                transport.connect()

                _state.value = ConnectionState.Initializing("Starting")
                val session = Elm327Session(transport, log)
                val saved = loadSavedProtocol()
                val info = session.initialize(saved) { step ->
                    _state.value = ConnectionState.Initializing(step)
                }
                if (!info.usedSavedProtocol && info.protocolNumber != Elm327Session.AUTO_PROTOCOL) {
                    saveProtocol(info.protocolNumber)
                }

                val lost = CompletableDeferred<String>()
                link = Link(session, lost)
                attempt = 0
                _state.value = ConnectionState.Connected(info)
                log.record(LogKind.INFO, "Connected: ${info.protocolName} (${info.elmVersion})")

                reason = awaitLinkLoss(transport, lost)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                val msg = e.message ?: "Permission denied"
                log.record(LogKind.ERROR, "Connect refused: $msg")
                _state.value = ConnectionState.Failed(msg)
                return
            } catch (e: TransportException) {
                reason = e.message ?: "Connection error"
                log.record(LogKind.ERROR, "Link error: $reason")
            } catch (e: Exception) {
                // Platform I/O exceptions the transport did not wrap; still worth retrying.
                reason = "${e.javaClass.simpleName}: ${e.message}"
                log.record(LogKind.ERROR, "Unexpected error: $reason")
            } finally {
                link = null
                withContext(NonCancellable) { transport.close() }
            }

            attempt++
            val waitMs = backoff.delayFor(attempt, random)
            _state.value = ConnectionState.Reconnecting(attempt, waitMs, reason)
            log.record(LogKind.INFO, "Reconnect attempt $attempt in ${waitMs}ms")
            delay(waitMs)
        }
    }

    /**
     * Suspends until the link is reported lost by [execute] or the transport notices the drop on
     * its own. The second path matters when nothing is polling: without it a Bluetooth drop
     * would leave the UI on "Connected" until the next command.
     */
    private suspend fun awaitLinkLoss(transport: Transport, lost: CompletableDeferred<String>): String =
        coroutineScope {
            val watchdog = launch {
                while (true) {
                    delay(LINK_CHECK_INTERVAL_MS)
                    if (!transport.isConnected) {
                        link = null
                        lost.complete("The connection to the adapter dropped")
                        break
                    }
                }
            }
            try {
                lost.await()
            } finally {
                watchdog.cancel()
            }
        }

    private suspend fun loadSavedProtocol(): String? = try {
        protocolStore.loadProtocol(adapterKey)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.record(LogKind.ERROR, "Could not load saved protocol: ${e.message}")
        null
    }

    private suspend fun saveProtocol(protocol: String) {
        try {
            protocolStore.saveProtocol(adapterKey, protocol)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.record(LogKind.ERROR, "Could not save protocol: ${e.message}")
        }
    }

    private companion object {
        const val LINK_CHECK_INTERVAL_MS = 1_000L
        const val MAX_CONSECUTIVE_TIMEOUTS = 3
    }
}
