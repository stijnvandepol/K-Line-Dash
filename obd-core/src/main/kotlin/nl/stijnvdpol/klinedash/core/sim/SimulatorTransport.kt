package nl.stijnvdpol.klinedash.core.sim

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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

    /** True while a read is waiting out its latency; a write now would mean overlapping commands. */
    @Volatile
    private var reading = false

    /** Engine on/off for the debug screen: off = ignition on, engine stopped (rpm 0). */
    fun setEngineRunning(running: Boolean) = engine.setRunning(running, clock())

    val isEngineRunning: Boolean get() = engine.isRunning

    override suspend fun connect() {
        if (connected) return
        delay(config.connectLatencyMs)
        if (config.failConnect) throw TransportException("Simulator: connection refused")
        resetAdapterState()
        commandCount = 0
        pending = null
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

    private fun handleObd(cmd: String, echoText: String): Reply {
        if (cmd.length % 2 != 0 || !isHex(cmd)) return textReply(echoText, listOf("?"), config.atLatencyMs)
        val request = IntArray(cmd.length / 2) { cmd.substring(it * 2, it * 2 + 2).toInt(16) }

        var latency = requestLatency()
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
            latency += config.busInitMs
        }

        var frames = answer(request)
        if (frames != null && chance(config.randomNoDataRate)) frames = null

        val lines = ArrayList<String>()
        if (status != null) lines.add(status)
        if (frames == null) {
            lines.add("NO DATA")
            latency += config.noDataLatencyMs
        } else {
            for (frame in frames) lines.add(formatFrame(frame))
        }
        val partial = echoText + if (status != null) status + eol else ""
        return textReply(echoText, lines, latency, partial)
    }

    private fun requestLatency(): Long {
        val lo = config.latencyMinMs
        val hi = max(lo, config.latencyMaxMs)
        return if (hi == lo) lo else random.nextLong(lo, hi + 1)
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

    // ---------------------------------------------------------------- the car

    /** Frames (without header/checksum) for an OBD request, or null for "NO DATA". */
    private fun answer(request: IntArray): List<List<Int>>? = when (request[0]) {
        0x01 -> if (request.size == 2) mode01(request[1])?.let { listOf(it) } else null
        0x02 -> mode02(request)
        0x03 -> if (request.size == 1) dtcFrames(0x43, config.storedDtcs) else null
        0x04 -> if (request.size == 1) {
            config.storedDtcs.clear()
            config.pendingDtcs.clear()
            listOf(listOf(0x44))
        } else null
        0x07 -> if (request.size == 1) dtcFrames(0x47, config.pendingDtcs) else null
        0x09 -> mode09(request)
        else -> null // includes 0x0A (permanent DTCs): not supported on this era
    }

    private fun mode01(pid: Int): List<Int>? {
        val supported = config.supportedPids
        if (pid % 0x20 == 0) {
            if (pid != 0 && pid !in supported) return null
            return listOf(0x41, pid) + bitmapBytes(supported, pid)
        }
        if (pid !in supported) return null

        val s = engine.snapshot(clock())
        val data: List<Int> = when (pid) {
            0x01 -> {
                val n = config.storedDtcs.size
                listOf((if (n > 0) 0x80 else 0) or min(n, 0x7F), 0x07, 0x65, 0x00)
            }
            0x03 -> listOf(s.fuelSystemStatus, 0)
            0x04 -> listOf(byteOf(s.loadPct * 255.0 / 100.0))
            0x05 -> listOf(byteOf(s.coolantC + 40.0))
            0x06 -> listOf(trimByte(s.shortTrimPct))
            0x07 -> listOf(trimByte(s.longTrimPct))
            0x0C -> twoBytes(s.rpm * 4)
            0x0D -> listOf(s.speedKmh.coerceIn(0, 255))
            0x0E -> listOf(byteOf(s.timingDeg * 2.0 + 64.0))
            0x0F -> listOf(byteOf(s.intakeC + 40.0))
            0x10 -> twoBytes((s.mafGs * 100.0).roundToInt())
            0x11 -> listOf(byteOf(s.throttlePct * 255.0 / 100.0))
            0x13 -> listOf(0x03)
            0x14 -> listOf(byteOf(s.o2UpstreamV * 200.0), trimByte(s.shortTrimPct))
            0x15 -> listOf(byteOf(s.o2DownstreamV * 200.0), 0xFF)
            0x1C -> listOf(0x06) // EOBD
            else -> return null
        }
        return listOf(0x41, pid) + data
    }

    /** Freeze frame data; only exists while a DTC is stored, and only frame 0. */
    private fun mode02(request: IntArray): List<List<Int>>? {
        if (request.size !in 2..3) return null
        val pid = request[1]
        val frameNo = if (request.size == 3) request[2] else 0
        if (frameNo != 0) return null
        val dtc = config.storedDtcs.firstOrNull()?.let { encodeDtc(it) } ?: return null
        val data: List<Int> = when (pid) {
            0x00 -> bitmapBytes(setOf(0x02, 0x05, 0x0C, 0x0D), 0)
            0x02 -> listOf(dtc.first, dtc.second)
            0x05 -> listOf(85 + 40)
            0x0C -> twoBytes(2100 * 4)
            0x0D -> listOf(56)
            else -> return null
        }
        return listOf(listOf(0x42, pid, frameNo) + data)
    }

    private fun mode09(request: IntArray): List<List<Int>>? {
        if (request.size != 2) return null
        val vin = config.vin
        val name = config.ecuName
        if (vin == null && name == null) return null
        return when (request[1]) {
            0x00 -> {
                val pids = HashSet<Int>()
                if (vin != null) pids.add(0x02)
                if (name != null) pids.add(0x0A)
                listOf(listOf(0x49, 0x00) + bitmapBytes(pids, 0))
            }
            // ISO 9141-2 sends the VIN as 4-byte pieces behind three padding bytes.
            0x02 -> vin?.let { multiFrame(0x02, List(3) { 0 } + ascii(it)) }
            0x0A -> name?.let {
                val bytes = ascii(it.take(20))
                multiFrame(0x0A, bytes + List(20 - bytes.size) { 0 })
            }
            else -> null
        }
    }

    private fun multiFrame(pid: Int, payload: List<Int>): List<List<Int>> =
        payload.chunked(4).mapIndexed { i, chunk ->
            listOf(0x49, pid, i + 1) + chunk + List(4 - chunk.size) { 0 }
        }

    private fun ascii(s: String): List<Int> = s.map { it.code and 0xFF }

    private fun dtcFrames(responseMode: Int, codes: List<String>): List<List<Int>> {
        val encoded = codes.mapNotNull { encodeDtc(it) }
        // With nothing stored the ECU still answers: one frame of zeros.
        if (encoded.isEmpty()) return listOf(listOf(responseMode, 0, 0, 0, 0, 0, 0))
        return encoded.chunked(3).map { chunk ->
            val frame = ArrayList<Int>(7)
            frame.add(responseMode)
            for ((hi, lo) in chunk) {
                frame.add(hi)
                frame.add(lo)
            }
            repeat(3 - chunk.size) {
                frame.add(0)
                frame.add(0)
            }
            frame
        }
    }

    private fun bitmapBytes(pids: Set<Int>, base: Int): List<Int> {
        var bits = 0L
        for (p in pids) {
            val k = p - base
            if (k in 1..32) bits = bits or (1L shl (32 - k))
        }
        return listOf(
            ((bits shr 24) and 0xFF).toInt(),
            ((bits shr 16) and 0xFF).toInt(),
            ((bits shr 8) and 0xFF).toInt(),
            (bits and 0xFF).toInt(),
        )
    }

    private fun byteOf(value: Double): Int = value.roundToInt().coerceIn(0, 255)

    /** Fuel trim percent to the OBD byte: 128 = 0 %, 1.28 counts per percent. */
    private fun trimByte(percent: Double): Int = byteOf(128.0 + percent * 1.28)

    private fun twoBytes(value: Int): List<Int> {
        val v = value.coerceIn(0, 0xFFFF)
        return listOf(v shr 8, v and 0xFF)
    }

    companion object {
        private const val NEVER = Long.MAX_VALUE
        private const val HEX = "0123456789ABCDEF"
        private const val STRAY_CHARS = "#@%*"
        private val K_LINE_PROTOCOLS = setOf("3", "4", "5")

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

        /**
         * Encodes a DTC such as "P0171" into its two OBD bytes (0x01 0x71): two bits for the
         * letter (P, C, B, U), two for the first digit (0-3), then three hex digits.
         * Returns null for anything that is not a valid code.
         */
        fun encodeDtc(code: String): Pair<Int, Int>? {
            val c = code.trim().uppercase()
            if (c.length != 5) return null
            val type = "PCBU".indexOf(c[0])
            if (type < 0 || c[1] !in '0'..'3') return null
            val d1 = c[1] - '0'
            val d2 = c[2].digitToIntOrNull(16) ?: return null
            val d3 = c[3].digitToIntOrNull(16) ?: return null
            val d4 = c[4].digitToIntOrNull(16) ?: return null
            return Pair((type shl 6) or (d1 shl 4) or d2, (d3 shl 4) or d4)
        }
    }
}
