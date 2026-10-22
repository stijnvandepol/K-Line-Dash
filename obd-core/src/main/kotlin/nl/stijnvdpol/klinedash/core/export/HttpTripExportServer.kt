package nl.stijnvdpol.klinedash.core.export

import java.io.Closeable
import java.io.IOException
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

private const val WORKER_THREADS = 4
private const val PENDING_CONNECTIONS = 8
private const val ACCEPT_BACKLOG = 16
private const val SOCKET_READ_TIMEOUT_MS = 10_000
private const val DRAIN_TIMEOUT_MS = 300
private const val DRAIN_CHUNK_BYTES = 4096
private const val MAX_DRAIN_BYTES = 64 * 1024
private const val IDLE_CHECK_INTERVAL_MS = 5_000L

/**
 * Plain-HTTP trip export server (approved exception to guidelines §6, see [TripExportServer]).
 * Short-lived `Connection: close` requests on a small fixed worker pool; nothing about the PIN or
 * session tokens is logged.
 */
class HttpTripExportServer(
    private val config: ExportServerConfig,
    private val files: TripFileSource,
    private val clock: () -> Long = System::currentTimeMillis,
) : TripExportServer {

    private class Running(
        val serverSocket: ServerSocket,
        val workers: ThreadPoolExecutor,
        val scheduler: ScheduledExecutorService,
        val handler: ExportRequestHandler,
    )

    private val lifecycleLock = Any()
    @Volatile private var running: Running? = null
    private val openSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val activeConnections = AtomicInteger(0)
    @Volatile private var lastRequestMs = 0L

    override val isRunning: Boolean get() = running != null

    /** @throws IOException when the port cannot be bound. */
    override fun start(): Int = synchronized(lifecycleLock) {
        running?.let { return it.serverSocket.localPort }
        val serverSocket = ServerSocket(config.port, ACCEPT_BACKLOG).also { it.reuseAddress = true }
        val workers = ThreadPoolExecutor(
            WORKER_THREADS, WORKER_THREADS, 0, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(PENDING_CONNECTIONS), daemonThreads("trip-export-worker"),
        )
        val scheduler = Executors.newSingleThreadScheduledExecutor(daemonThreads("trip-export-idle"))
        val handler = ExportRequestHandler(ExportAuth(config.pin, config.maxPinAttempts), files)
        lastRequestMs = clock()
        running = Running(serverSocket, workers, scheduler, handler)
        scheduler.scheduleWithFixedDelay(::checkIdle, IDLE_CHECK_INTERVAL_MS, IDLE_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS)
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

    /** Stops the server when no request has arrived for the configured idle time. */
    internal fun checkIdle() {
        if (running == null || activeConnections.get() > 0) return
        if (clock() - lastRequestMs >= config.idleTimeoutMs) stop()
    }

    private fun acceptLoop(serverSocket: ServerSocket, workers: ThreadPoolExecutor, handler: ExportRequestHandler) {
        while (!serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (_: IOException) {
                // Closed by stop(), or a failed handshake; the loop condition decides which.
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

    private fun serve(socket: Socket, handler: ExportRequestHandler) {
        activeConnections.incrementAndGet()
        openSockets.add(socket)
        try {
            socket.soTimeout = SOCKET_READ_TIMEOUT_MS
            if (running == null) return
            val request = RequestReader.read(socket.getInputStream())
            lastRequestMs = clock()
            handler.handle(request, socket.getOutputStream())
        } catch (rejected: RequestRejected) {
            replyError(socket, rejected.status)
        } catch (_: IOException) {
            // Client went away, timed out or the server is stopping: nothing useful left to do.
        } catch (bug: RuntimeException) {
            replyError(socket, 500)
            throw bug
        } finally {
            openSockets.remove(socket)
            closeQuietly(socket)
            lastRequestMs = clock()
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
