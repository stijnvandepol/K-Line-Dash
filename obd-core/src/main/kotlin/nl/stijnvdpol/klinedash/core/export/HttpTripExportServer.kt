package nl.stijnvdpol.klinedash.core.export

import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger
import nl.stijnvdpol.klinedash.core.time.monotonicMillis

private const val WORKER_THREADS = 4
private const val PENDING_CONNECTIONS = 8
private const val ACCEPT_BACKLOG = 16
private const val SOCKET_READ_TIMEOUT_MS = 10_000
private const val INTERNAL_ERROR_STATUS = 500
private val LOGGER: Logger = Logger.getLogger(HttpTripExportServer::class.java.name)
private const val DRAIN_TIMEOUT_MS = 300
private const val DRAIN_CHUNK_BYTES = 4096
private const val MAX_DRAIN_BYTES = 64 * 1024
private const val IDLE_CHECK_INTERVAL_MS = 5_000L

/** A response that sends nothing for this long is given up: the client stopped reading. */
internal const val WRITE_STALL_TIMEOUT_MS = 30_000L

/**
 * Plain-HTTP trip export server (approved exception to guidelines §6, see [TripExportServer]).
 * Short-lived `Connection: close` requests on a small fixed worker pool; nothing about the PIN or
 * session tokens is logged.
 *
 * [clock] times the idle stop and the write-stall check, so it has to be monotonic: a wall clock
 * stepped back by GPS or NTP time would keep the export open for hours, one stepped forward would
 * close it at once. A bug in the request handling is passed to [onInternalError] (logged by default)
 * and answered with a 500; it never leaves the worker thread, where it would kill the whole app.
 */
class HttpTripExportServer(
    private val config: ExportServerConfig,
    private val files: TripFileSource,
    private val clock: () -> Long = ::monotonicMillis,
    private val onInternalError: (Throwable) -> Unit = ::logInternalError,
) : TripExportServer {

    private class Running(
        val serverSocket: ServerSocket,
        val workers: ThreadPoolExecutor,
        val scheduler: ScheduledExecutorService,
        val handler: ExportRequestHandler,
        val activity: ExportActivity,
    )

    private val lifecycleLock = Any()
    @Volatile private var running: Running? = null
    private val openSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val responses = ConcurrentHashMap<Socket, ProgressOutputStream>()
    private val activeConnections = AtomicInteger(0)

    override val isRunning: Boolean get() = running != null

    /** @throws IOException when the port cannot be bound. */
    override fun start(): Int = synchronized(lifecycleLock) {
        running?.let { return it.serverSocket.localPort }
        val serverSocket = bindServerSocket(config.port)
        val workers = ThreadPoolExecutor(
            WORKER_THREADS, WORKER_THREADS, 0, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(PENDING_CONNECTIONS), daemonThreads("trip-export-worker"),
        )
        val scheduler = Executors.newSingleThreadScheduledExecutor(daemonThreads("trip-export-idle"))
        val activity = ExportActivity(clock)
        val handler = ExportRequestHandler(ExportAuth(config.pin, config.maxPinAttempts), files, activity)
        running = Running(serverSocket, workers, scheduler, handler, activity)
        scheduler.scheduleWithFixedDelay(::checkIdle, IDLE_CHECK_INTERVAL_MS, IDLE_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS)
        scheduler.scheduleWithFixedDelay(
            ::closeStalledResponses, IDLE_CHECK_INTERVAL_MS, IDLE_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS,
        )
        daemonThreads("trip-export-accept").newThread { acceptLoop(serverSocket, workers, handler) }.start()
        serverSocket.localPort
    }

    override fun stop() {
        val current = synchronized(lifecycleLock) {
            val current = running ?: return
            running = null
            current
        }
        closeQuietly(current.serverSocket)
        openSockets.forEach(::closeQuietly)
        openSockets.clear()
        current.workers.shutdownNow()
        current.scheduler.shutdownNow()
    }

    /**
     * Listens on every interface (Java binds dual-stack even for 0.0.0.0, so [acceptLoop] filters the
     * peers instead). Reuse is set before the bind, so a restart right after a stop gets the same port.
     */
    private fun bindServerSocket(port: Int): ServerSocket {
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port), ACCEPT_BACKLOG)
        } catch (e: IOException) {
            closeQuietly(socket)
            throw e
        }
        return socket
    }

    /** Stops the server when nobody has logged in or used a session for the configured idle time. */
    internal fun checkIdle() {
        val current = running ?: return
        if (current.activity.isIdleFor(config.idleTimeoutMs)) stop()
    }

    /** Closes connections whose response has not moved for [WRITE_STALL_TIMEOUT_MS]; frees their workers. */
    internal fun closeStalledResponses() {
        val now = clock()
        responses.forEach { (socket, response) ->
            if (now - response.lastProgressMs >= WRITE_STALL_TIMEOUT_MS) closeQuietly(socket)
        }
    }

    /** Number of requests being served; for tests that check a stalled one was let go. */
    internal val activeConnectionCount: Int get() = activeConnections.get()

    private fun acceptLoop(serverSocket: ServerSocket, workers: ThreadPoolExecutor, handler: ExportRequestHandler) {
        val backoff = AcceptBackoff()
        while (!serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept().also { backoff.reset() }
            } catch (_: IOException) {
                // Closed by stop() (the loop condition ends it), or a real failure: pause so the
                // thread does not spin while the failure lasts.
                if (!pauseAfterFailedAccept(serverSocket, backoff)) return
                continue
            }
            if (!isLocalNetworkAddress(socket.inetAddress)) {
                // Not from a Wi-Fi or hotspot network: an IPv6 or public peer, e.g. over mobile data,
                // where the head unit may have a public IPv6 address. No answer at all.
                closeQuietly(socket)
                continue
            }
            try {
                workers.execute { serve(socket, handler) }
            } catch (_: RejectedExecutionException) {
                // Overloaded or stopping: drop the connection instead of queueing without bound.
                closeQuietly(socket)
            }
        }
    }

    /** Returns false when the accept thread should end (server closed or thread interrupted). */
    private fun pauseAfterFailedAccept(serverSocket: ServerSocket, backoff: AcceptBackoff): Boolean {
        if (serverSocket.isClosed) return false
        return try {
            Thread.sleep(backoff.nextDelayMs())
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun serve(socket: Socket, handler: ExportRequestHandler) {
        activeConnections.incrementAndGet()
        openSockets.add(socket)
        try {
            if (running == null) return
            val request = RequestReader.read(socket.getInputStream(), SOCKET_READ_TIMEOUT_MS) { socket.soTimeout = it }
            val response = ProgressOutputStream(socket.getOutputStream(), clock)
            responses[socket] = response
            handler.handle(request, response)
        } catch (rejected: RequestRejected) {
            replyError(socket, rejected.status)
        } catch (_: IOException) {
            // Client went away, timed out or the server is stopping: nothing useful left to do.
        } catch (bug: RuntimeException) {
            // Rethrowing would reach the default uncaught-exception handler of this daemon thread,
            // which ends the whole app (and a running trip); any device on the Wi-Fi could trigger it.
            onInternalError(bug)
            replyError(socket, INTERNAL_ERROR_STATUS)
        } finally {
            responses.remove(socket)
            openSockets.remove(socket)
            closeQuietly(socket)
            activeConnections.decrementAndGet()
        }
    }

    private fun replyError(socket: Socket, status: Int) {
        try {
            HttpResponseWriter(socket.getOutputStream())
                .sendHtml(status, PageRenderer.messagePage("Request refused", "This request could not be accepted."))
            drainInput(socket)
        } catch (_: IOException) {
            // The client may already have closed the connection; the refusal is best effort.
        }
    }

    /**
     * Reads and discards what the client still sends, briefly. Closing with unread data makes the
     * TCP stack reset the connection, which can destroy the error response before the client reads it.
     */
    private fun drainInput(socket: Socket) {
        socket.shutdownOutput()
        socket.soTimeout = DRAIN_TIMEOUT_MS
        val input = socket.getInputStream()
        val sink = ByteArray(DRAIN_CHUNK_BYTES)
        var drained = 0
        while (drained < MAX_DRAIN_BYTES) {
            val count = try {
                input.read(sink)
            } catch (_: SocketTimeoutException) {
                return
            }
            if (count < 0) return
            drained += count
        }
    }

    private fun daemonThreads(prefix: String): ThreadFactory {
        val counter = AtomicInteger(0)
        return ThreadFactory { task ->
            Thread(task, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = true }
        }
    }

    private fun closeQuietly(closeable: Closeable) {
        try {
            closeable.close()
        } catch (_: IOException) {
            // Best-effort cleanup: stop() promises not to throw.
        }
    }
}

private fun logInternalError(error: Throwable) {
    LOGGER.log(Level.SEVERE, "Trip export: unexpected error while serving a request", error)
}
