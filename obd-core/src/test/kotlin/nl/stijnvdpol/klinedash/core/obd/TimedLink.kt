package nl.stijnvdpol.klinedash.core.obd

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import nl.stijnvdpol.klinedash.core.elm.AdapterInfo
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.core.elm.ElmLink
import nl.stijnvdpol.klinedash.core.elm.ElmResult
import nl.stijnvdpol.klinedash.core.log.CommLog
import nl.stijnvdpol.klinedash.core.log.RingCommLog

/** One request as [TimedLink] received it, on the test scheduler's clock. */
internal class SentRequest(val timeMs: Long, val command: String, val timeoutMs: Long) {
    /** The command without the response-count digit the poller appends ("010C1" is "010C"). */
    val base: String get() = if (command.length >= 5 && command.length % 2 == 1) command.dropLast(1) else command
}

/** What the owner's Toyota 2ZZ-GE reports for Mode 01 (the audit's model of the car). */
internal val ZZ_GE_SUPPORTED_PIDS = setOf(
    0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x1C, 0x21,
)

internal val K_LINE_INFO = AdapterInfo("ELM327 v1.5", "3", "ISO 9141-2", 12.4, false, emptySet())
internal val CAN_INFO = AdapterInfo("ELM327 v1.5", "6", "ISO 15765-4 (CAN 11/500)", 12.4, false, emptySet())

/**
 * An [ElmLink] with a fixed time per request on a virtual [clock], that answers like an ECU with
 * [supportedPids] (single and multi-PID requests, supported-PID bitmaps, ATRV). [scripted] can
 * replace the answer to a request; returning null keeps the ECU's answer.
 */
internal class TimedLink(
    private val clock: () -> Long,
    info: AdapterInfo = K_LINE_INFO,
    var latencyMs: Long = 500,
    private val supportedPids: Set<Int> = ZZ_GE_SUPPORTED_PIDS,
    var scripted: (SentRequest) -> ElmResult? = { null },
) : ElmLink {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Connected(info))
    override val state: StateFlow<ConnectionState> = _state
    override val log: CommLog = RingCommLog()
    val sent = ArrayList<SentRequest>()

    override fun start() = Unit
    override suspend fun stop() = Unit
    override suspend fun forgetSavedProtocol() = Unit

    override suspend fun execute(command: String, timeoutMs: Long): ElmResult {
        val request = SentRequest(clock(), command, timeoutMs)
        sent += request
        delay(latencyMs)
        return scripted(request) ?: ecuAnswer(request.base)
    }

    /** Requests sent at or after [fromMs], without the supported-PID detection. */
    fun polled(fromMs: Long = 0): List<SentRequest> =
        sent.filter { it.timeMs >= fromMs && it.base !in DETECTION_COMMANDS }

    private fun ecuAnswer(base: String): ElmResult {
        if (base == "ATRV") return ElmResult.Data(listOf("13.9V"), "13.9V")
        val pids = ObdHex.toBytes(base)!!.drop(1)
        val first = pids.first()
        if (pids.size == 1 && first % PIDS_PER_BLOCK == 0) return data("41%02X%s".format(first, bitmap(first)))
        val reply = StringBuilder("41")
        for (pid in pids) {
            if (pid !in supportedPids) return ElmResult.NoData("NO DATA")
            reply.append("%02X".format(pid))
            repeat(dataLength(pid)) { reply.append("20") }
        }
        return data(reply.toString())
    }

    private fun data(line: String) = ElmResult.Data(listOf(line), line)

    private fun dataLength(pid: Int): Int = Signals.all.firstOrNull { it.pid == pid }?.pidDataLength ?: 1

    private fun bitmap(base: Int): String {
        var bits = 0L
        for (pid in supportedPids) if (pid in base + 1..base + PIDS_PER_BLOCK) bits = bits or (1L shl (PIDS_PER_BLOCK - (pid - base)))
        if (supportedPids.any { it > base + PIDS_PER_BLOCK }) bits = bits or 1L
        return "%08X".format(bits)
    }

    companion object {
        private const val PIDS_PER_BLOCK = 32
        val DETECTION_COMMANDS = setOf("0100", "0120", "0140", "0160", "0180", "01A0", "01C0", "01E0")
    }
}
