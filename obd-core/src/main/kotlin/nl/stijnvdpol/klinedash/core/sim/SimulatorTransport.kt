package nl.stijnvdpol.klinedash.core.sim

import java.util.Locale
import kotlin.math.max
import kotlin.random.Random
import kotlinx.coroutines.delay
import nl.stijnvdpol.klinedash.core.transport.PromptBuffer
import nl.stijnvdpol.klinedash.core.transport.Transport
import nl.stijnvdpol.klinedash.core.transport.TransportException
import nl.stijnvdpol.klinedash.core.transport.TransportKind
import nl.stijnvdpol.klinedash.core.transport.TransportTimeoutException

/**
 * A fake ELM327 on a K-line car that speaks the real text protocol (echo, spaces, linefeeds,
 * headers, "SEARCHING...", "NO DATA", prompts), so the whole stack above [Transport] runs
 * unmodified against it. Latencies are realised with coroutine `delay()`, which makes virtual
 * time in `runTest` work.
 *
 * Replies are pushed through a real [PromptBuffer] (as a byte transport would), so injected NUL
 * and high-bit garbage is dropped exactly like on a real link while stray printable characters
 * survive.
 *
 * Pass the same [config] (and [engine], to keep the car's state, e.g. coolant temperature)
 * to every transport a reconnecting manager creates. [clock] only drives the engine model.
 */
class SimulatorTransport(
    val config: SimulatorConfig = SimulatorConfig(),
    val engine: EngineModel = EngineModel(config, Random(config.seed xor 0x5EEDL)),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : Transport {

    override val kind: TransportKind = TransportKind.SIMULATOR
    override val description: String = "Simulator (ELM327 emulation)"

    @Volatile
    private var connected = false
    override val isConnected: Boolean get() = connected

    private class Reply(val text: String, val latencyMs: Long, val partial: String)

    private val random = Random(config.seed)
    private val ecu = SimulatorEcu(config, engine, clock)

    // Adapter state; ATZ restores these defaults.
    private var echo = true
    private var spaces = true
    private var headers = false
    private var linefeeds = true
    private var protocol = "0"
    private var detectedProtocol: String? = null
    private var busReady = false

    private var commandCount = 0
    private var pending: Reply? = null

    /**
     * True after a read timed out while the adapter was still working on its command (a slow reply
     * that arrived too late). Any character sent now aborts that work: the adapter prints
     * "STOPPED" and a prompt, like a real ELM327 does.
     */
    private var busy = false

    /** True while a read is waiting out its latency; a write now would mean overlapping commands. */
    @Volatile
    private var reading = false

    /** Engine on/off for the debug screen: off = ignition on, engine stopped (rpm 0). */
    fun setEngineRunning(running: Boolean) = engine.setRunning(running, clock())

    override suspend fun connect() {
        if (connected) return
        delay(config.connectLatencyMs)
        if (config.failConnect) throw TransportException("Simulator: connection refused")
        resetAdapterState()
        commandCount = 0
        pending = null
        busy = false
        connected = true
    }

    override suspend fun write(data: String) {
        if (!connected) throw TransportException("Simulator: not connected")
        // The real adapter would abort the running command ("STOPPED"); flag it loudly in tests instead.
        check(!reading) { "Simulator: command written while another is still in flight" }
        commandCount++
        val dropAfter = config.dropConnectionAfterCommands
        if (dropAfter != null && commandCount > dropAfter) {
            config.dropConnectionAfterCommands = null
            connected = false
            pending = null
            throw TransportException("Simulator: connection dropped")
        }
        pending = buildReply(data.trimEnd('\r', '\n'))
    }

    override suspend fun readUntilPrompt(timeoutMs: Long): String {
        if (!connected) throw TransportException("Simulator: not connected")
        val reply = pending
        pending = null
        reading = true
        try {
            if (reply == null || reply.latencyMs > timeoutMs) {
                delay(timeoutMs)
                // A hung adapter (NEVER) does not react to anything; a slow one is merely busy.
                busy = reply != null && reply.latencyMs != NEVER
                throw TransportTimeoutException(reply?.partial ?: "", timeoutMs)
            }
            delay(reply.latencyMs)
            return viaWire(reply.text)
        } finally {
            reading = false
        }
    }

    override fun discardPending() {
        pending = null
    }

    override suspend fun close() {
        connected = false
        pending = null
        busy = false
    }

    // ---------------------------------------------------------------- reply construction

    private val eol: String get() = if (linefeeds) "\r\n" else "\r"

    private fun chance(rate: Double): Boolean = rate > 0.0 && random.nextDouble() < rate

    private fun resetAdapterState() {
        echo = true
        spaces = true
        headers = false
        linefeeds = true
        protocol = "0"
        detectedProtocol = null
        busReady = false
    }

    private fun buildReply(received: String): Reply {
        if (busy) {
            busy = false
            return textReply("", listOf("STOPPED"), config.atLatencyMs)
        }
        val echoText = if (echo) received + eol else ""
        val cmd = received.filter { it > ' ' }.uppercase()

        if (chance(config.timeoutRate)) return Reply("", NEVER, echoText)

        val resetAt = config.spontaneousResetAtCommand
        val forcedReset = resetAt != null && commandCount >= resetAt
        if (forcedReset) config.spontaneousResetAtCommand = null
        if (cmd != "ATZ" && (forcedReset || chance(config.spontaneousResetRate))) {
            resetAdapterState()
            return Reply("\r\r${config.elmVersion}\r\r", config.resetLatencyMs, "")
        }

        val reply = when {
            cmd.isEmpty() -> textReply(echoText, emptyList(), config.atLatencyMs)
            cmd.startsWith("AT") -> handleAt(cmd, echoText)
            cmd == "STI" -> textReply(echoText, listOf(config.stnDeviceId ?: "?"), config.atLatencyMs)
            else -> handleObd(cmd, echoText)
        }
        return if (chance(config.garbageRate)) corrupted(reply) else reply
    }

    private fun textReply(echoText: String, lines: List<String>, latencyMs: Long, partial: String = echoText): Reply {
        val e = eol
        val sb = StringBuilder(echoText)
        for (line in lines) sb.append(line).append(e)
        sb.append(e) // blank line before the prompt, as the real chip does
        return Reply(sb.toString(), latencyMs, partial)
    }

    private fun handleAt(cmd: String, echoText: String): Reply {
        fun say(vararg lines: String) = textReply(echoText, lines.toList(), config.atLatencyMs)

        if (cmd in config.cloneRejectsAt) return say("?")
        return when {
            cmd == "ATZ" || cmd == "ATWS" -> {
                resetAdapterState()
                Reply(echoText + "\r\r${config.elmVersion}\r\r", config.resetLatencyMs, echoText)
            }
            cmd == "ATD" -> {
                echo = true
                spaces = true
                headers = false
                linefeeds = true
                say("OK")
            }
            cmd == "ATE0" || cmd == "ATE1" -> {
                echo = cmd.endsWith('1')
                say("OK")
            }
            cmd == "ATL0" || cmd == "ATL1" -> {
                linefeeds = cmd.endsWith('1')
                say("OK")
            }
            cmd == "ATS0" || cmd == "ATS1" -> {
                spaces = cmd.endsWith('1')
                say("OK")
            }
            cmd == "ATH0" || cmd == "ATH1" -> {
                headers = cmd.endsWith('1')
                say("OK")
            }
            cmd == "ATAT0" || cmd == "ATAT1" || cmd == "ATAT2" -> say("OK")
            cmd == "ATLP" -> say("OK")
            cmd == "ATI" -> say(config.elmVersion)
            cmd == "AT@1" -> say("OBDII to RS232 Interpreter")
            cmd == "ATRV" -> {
                val volts = if (engine.isRunning) config.batteryVoltageEngineOn else config.batteryVoltageEngineOff
                say(String.format(Locale.US, "%.1fV", volts))
            }
            cmd == "ATDP" -> say(protocolDescription())
            cmd == "ATDPN" -> say(protocolNumberText())
            cmd.length == 5 && cmd.startsWith("ATSP") && cmd[4] in "0123456789ABC" -> {
                protocol = cmd.substring(4)
                detectedProtocol = null
                busReady = false
                say("OK")
            }
            cmd.startsWith("ATST") && isHex(cmd.substring(4)) -> say("OK")
            cmd.startsWith("ATSH") && isHex(cmd.substring(4)) -> say("OK")
            else -> say("?")
        }
    }

    private fun protocolDescription(): String {
        if (protocol != "0") return protocolName(protocol)
        val detected = detectedProtocol ?: return "AUTO"
        return "AUTO, ${protocolName(detected)}"
    }

    private fun protocolNumberText(): String =
        if (protocol == "0") "A${detectedProtocol ?: "0"}" else protocol

    /**
     * An odd number of hex digits is a request plus the ELM response-count digit ("010C1", "031").
     * Only the shorter latency is modelled; the number of frames returned is not capped by the digit.
     */
    private fun handleObd(received: String, echoText: String): Reply {
        if (!isHex(received)) return textReply(echoText, listOf("?"), config.atLatencyMs)
        val hasCount = received.length % 2 != 0
        if (hasCount && (received.length < MIN_COUNT_REQUEST_LENGTH || config.cloneRejectsResponseCount)) {
            return textReply(echoText, listOf("?"), config.atLatencyMs)
        }
        val cmd = if (hasCount) received.dropLast(1) else received
        var request = IntArray(cmd.length / 2) { cmd.substring(it * 2, it * 2 + 2).toInt(16) }
        if (request.size > SINGLE_PID_REQUEST_SIZE) {
            when (config.longRequestQuirk) {
                LongRequestQuirk.NONE -> Unit
                LongRequestQuirk.TRUNCATES -> request = request.copyOf(SINGLE_PID_REQUEST_SIZE)
                LongRequestQuirk.REJECTS -> return textReply(echoText, listOf("?"), config.atLatencyMs)
            }
        }

        var latency = when {
            config.isCanCar -> drawLatency(config.canLatencyMinMs, config.canLatencyMaxMs)
            hasCount -> responseCountLatency()
            else -> requestLatency()
        }
        var status: String? = null

        if (!busReady) {
            val auto = protocol == "0"
            val reachable = !config.initFails &&
                (if (auto) !config.autoDetectFails else protocol == config.carProtocol)
            if (!reachable) {
                val lines = ArrayList<String>()
                if (auto) lines.add("SEARCHING...")
                if (auto || protocol in K_LINE_PROTOCOLS) lines.add("BUS INIT: ...ERROR")
                if (auto || protocol !in K_LINE_PROTOCOLS) lines.add("UNABLE TO CONNECT")
                return textReply(echoText, lines, config.busInitMs, partial = echoText + lines.first() + eol)
            }
            busReady = true
            if (auto) detectedProtocol = config.carProtocol
            status = if (auto) "SEARCHING..." else "BUS INIT: ...OK"
            latency += if (config.isCanCar) config.canBusInitMs else config.busInitMs
        }

        var frames = ecu.answer(request)
        if (frames != null && chance(config.randomNoDataRate)) frames = null

        val lines = ArrayList<String>()
        if (status != null) lines.add(status)
        if (frames == null) {
            lines.add("NO DATA")
            latency += config.noDataLatencyMs
        } else {
            for (frame in frames) lines.addAll(if (config.isCanCar) canLines(frame) else listOf(formatFrame(frame)))
        }
        val partial = echoText + if (status != null) status + eol else ""
        return textReply(echoText, lines, latency, partial)
    }

    private fun requestLatency(): Long = drawLatency(config.latencyMinMs, config.latencyMaxMs)

    private fun responseCountLatency(): Long =
        drawLatency(config.responseCountLatencyMinMs, config.responseCountLatencyMaxMs)

    private fun drawLatency(lo: Long, maxMs: Long): Long {
        val hi = max(lo, maxMs)
        return if (hi == lo) lo else random.nextLong(lo, hi + 1)
    }

    /**
     * One CAN message as the ELM prints it with headers off: a single line up to seven bytes,
     * otherwise a three-digit length line and numbered ISO-TP frames ("0:" carries six data bytes,
     * every following frame seven).
     */
    private fun canLines(message: List<Int>): List<String> {
        if (message.size <= CAN_SINGLE_FRAME_BYTES) return listOf(hexText(message))
        val lines = arrayListOf(String.format(Locale.US, "%03X", message.size))
        lines.add("0: " + hexText(message.take(CAN_FIRST_FRAME_BYTES)))
        message.drop(CAN_FIRST_FRAME_BYTES).chunked(CAN_SINGLE_FRAME_BYTES).forEachIndexed { i, chunk ->
            val frameNumber = Integer.toHexString((i + 1) % CAN_FRAME_INDEX_COUNT).uppercase()
            lines.add("$frameNumber: " + hexText(chunk))
        }
        return lines
    }

    /** One ECU response frame as the adapter prints it, honouring ATS and ATH. */
    private fun formatFrame(frame: List<Int>): String {
        val bytes = ArrayList<Int>(frame.size + 4)
        if (headers) {
            if (config.carProtocol == "3") {
                bytes.add(0x48); bytes.add(0x6B); bytes.add(0x10)
            } else {
                bytes.add(0x80 or frame.size); bytes.add(0xF1); bytes.add(0x11)
            }
        }
        bytes.addAll(frame)
        if (headers) bytes.add(bytes.sum() and 0xFF)
        return hexText(bytes)
    }

    /** Bytes as hex text, honouring ATS. */
    private fun hexText(bytes: List<Int>): String {
        val sb = StringBuilder()
        for ((i, b) in bytes.withIndex()) {
            if (spaces && i > 0) sb.append(' ')
            sb.append(HEX[b shr 4]).append(HEX[b and 0x0F])
        }
        // The real chip leaves a trailing space after the last byte when spaces are on.
        if (spaces) sb.append(' ')
        return sb.toString()
    }

    private fun corrupted(reply: Reply): Reply {
        val sb = StringBuilder(reply.text)
        if (sb.isNotEmpty()) sb.insert(random.nextInt(sb.length), STRAY_CHARS[random.nextInt(STRAY_CHARS.length)])
        sb.insert(0, "\u0000\u00ff") // dropped by PromptBuffer, like on a real link
        return Reply(sb.toString(), reply.latencyMs, reply.partial)
    }

    /** Frames the way a byte transport would deliver them: text + '>' through a [PromptBuffer]. */
    private fun viaWire(text: String): String {
        val bytes = (text + ">").toByteArray(Charsets.ISO_8859_1)
        return PromptBuffer().feed(bytes).firstOrNull() ?: text
    }

    companion object {
        private const val NEVER = Long.MAX_VALUE
        private const val HEX = "0123456789ABCDEF"
        private const val STRAY_CHARS = "#@%*"
        private val K_LINE_PROTOCOLS = setOf("3", "4", "5")

        /** A request of service plus one PID; anything longer is a multi-PID request. */
        private const val SINGLE_PID_REQUEST_SIZE = 2

        private const val CAN_SINGLE_FRAME_BYTES = 7
        private const val CAN_FIRST_FRAME_BYTES = 6

        /** ISO-TP frame numbers on the ELM wrap after "F:". */
        private const val CAN_FRAME_INDEX_COUNT = 16

        /** Shortest request with a count digit: a one-byte request such as "03" plus the digit ("031"). */
        private const val MIN_COUNT_REQUEST_LENGTH = 3

        private fun isHex(s: String): Boolean = s.isNotEmpty() && s.all { it in '0'..'9' || it in 'A'..'F' }

        private fun protocolName(number: String): String = when (number) {
            "1" -> "SAE J1850 PWM"
            "2" -> "SAE J1850 VPW"
            "3" -> "ISO 9141-2"
            "4" -> "ISO 14230-4 (KWP 5BAUD)"
            "5" -> "ISO 14230-4 (KWP FAST)"
            "6" -> "ISO 15765-4 (CAN 11/500)"
            "7" -> "ISO 15765-4 (CAN 29/500)"
            "8" -> "ISO 15765-4 (CAN 11/250)"
            "9" -> "ISO 15765-4 (CAN 29/250)"
            "A" -> "SAE J1939 (CAN 29/250)"
            else -> "USER"
        }
    }
}
