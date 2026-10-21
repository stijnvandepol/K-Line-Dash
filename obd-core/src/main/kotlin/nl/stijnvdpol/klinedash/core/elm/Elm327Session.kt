package nl.stijnvdpol.klinedash.core.elm

import kotlinx.coroutines.delay
import nl.stijnvdpol.klinedash.core.log.CommLog
import nl.stijnvdpol.klinedash.core.log.LogKind
import nl.stijnvdpol.klinedash.core.transport.Transport
import nl.stijnvdpol.klinedash.core.transport.TransportException
import nl.stijnvdpol.klinedash.core.transport.TransportTimeoutException

/**
 * The adapter initialised fine but the ECU never answered the 0100 probe, with the saved
 * protocol (if any) and with full auto-detection. Usually ignition off or the wrong car/port.
 */
class EcuNotRespondingException(message: String) : TransportException(message)

/**
 * One connected [Transport] plus a [CommLog]: sends single commands and runs the ELM327
 * init / protocol-detection sequence. Not thread-safe; [ElmConnectionManager] serialises calls.
 */
class Elm327Session(
    private val transport: Transport,
    private val log: CommLog,
) {

    /**
     * Sends [cmd] (without CR) and returns the classified reply. A missing prompt yields
     * [ElmResult.Timeout] with whatever text did arrive; a dropped link throws
     * [TransportException].
     *
     * After a timeout the adapter is resynchronised before this returns (see [resynchronise]), so
     * the next command never lands in a still-busy ELM327.
     *
     * @throws IllegalArgumentException when [cmd] is blank or contains a control character. A bare
     * CR makes an ELM327 repeat the previous command, which could silently re-send a mode 04
     * (clear codes) or restart a bus init, so such a command is never put on the wire.
     */
    suspend fun execute(cmd: String, timeoutMs: Long): ElmResult {
        require(cmd.isNotBlank() && cmd.none { it.isISOControl() }) {
            "Not a sendable ELM327 command: \"${cmd.take(MAX_LOGGED_COMMAND_LENGTH)}\""
        }
        // Late replies to a previous (timed-out) command would otherwise be taken for this answer.
        transport.discardPending()
        log.record(LogKind.TX, cmd)
        transport.write(cmd + "\r")
        val raw = try {
            transport.readUntilPrompt(timeoutMs)
        } catch (e: TransportTimeoutException) {
            log.record(LogKind.ERROR, "Timeout after ${timeoutMs}ms on \"$cmd\", partial: \"${e.partial}\"")
            resynchronise()
            return ElmResult.Timeout(e.partial)
        }
        log.record(LogKind.RX, raw)
        return ElmParser.parse(cmd, raw)
    }

    /**
     * Aborts whatever the adapter is still doing after a timeout: any single character does that
     * (it answers "STOPPED" or just the prompt). A space is used, never a bare CR, because a CR
     * would repeat the previous command. Waits briefly for the prompt and drops what arrives; an
     * adapter that stays silent is left to the caller's next command (and the manager's
     * consecutive-timeout reconnect).
     */
    private suspend fun resynchronise() {
        transport.discardPending()
        log.record(LogKind.TX, RESYNC_CHAR)
        transport.write(RESYNC_CHAR)
        try {
            log.record(LogKind.RX, transport.readUntilPrompt(RESYNC_TIMEOUT_MS))
        } catch (e: TransportTimeoutException) {
            log.record(LogKind.INFO, "No prompt ${RESYNC_TIMEOUT_MS}ms after the resync character")
        }
        transport.discardPending()
    }

    /**
     * Resets and configures the adapter, then makes sure the ECU answers on a protocol.
     *
     * With [savedProtocol] it first tries that protocol (skipping the slow ATSP0 search); if the
     * ECU does not answer it falls back to auto-detection. [onStep] receives short progress texts.
     * If the adapter resets itself mid-init (clone brown-out) the whole sequence is restarted once.
     *
     * @throws EcuNotRespondingException when the adapter works but no protocol gets an answer.
     * @throws TransportException when the adapter stops answering or keeps resetting.
     */
    suspend fun initialize(savedProtocol: String?, onStep: (String) -> Unit): AdapterInfo {
        var restarted = false
        while (true) {
            try {
                return runInit(savedProtocol, onStep)
            } catch (e: AdapterResetException) {
                if (restarted) {
                    throw TransportException(
                        "Adapter keeps resetting itself during init (weak power or faulty clone)",
                    )
                }
                restarted = true
                step(onStep, "Adapter reset itself, restarting init")
            }
        }
    }

    /** Thrown inside init when an unexpected "ELM327" banner shows up; never leaves this class. */
    private class AdapterResetException : Exception("Adapter reset during init")

    private suspend fun runInit(savedProtocol: String?, onStep: (String) -> Unit): AdapterInfo {
        val unsupported = LinkedHashSet<String>()

        step(onStep, "Resetting adapter")
        resetAdapter()
        configureAdapter(onStep, unsupported)
        val elmVersion = readAdapterIdentity(onStep, unsupported)
        val protocol = connectToEcu(savedProtocol, onStep, unsupported)

        val dp = send("ATDP")
        val reportedName = (dp as? ElmResult.Data)?.lines?.firstOrNull()
            ?.let { ElmParser.parseProtocolName(it) } ?: "unknown"
        val protocolName = if (protocol.number == TOYOTA_PROFILE) "$reportedName (Toyota)" else reportedName

        val rv = send("ATRV")
        if (rv is ElmResult.Unknown) unsupported.add("ATRV")
        val voltage = (rv as? ElmResult.Data)?.lines?.firstOrNull()?.let { ElmParser.parseVoltage(it) }

        return AdapterInfo(
            elmVersion = elmVersion,
            protocolNumber = protocol.number,
            protocolName = protocolName,
            voltage = voltage,
            usedSavedProtocol = protocol.isSaved,
            unsupportedCommands = unsupported,
        )
    }

    /** Echo, linefeeds, spaces and headers off, adaptive timing on. Rejected optional commands are recorded. */
    private suspend fun configureAdapter(onStep: (String) -> Unit, unsupported: MutableSet<String>) {
        for (cmd in SETUP_COMMANDS) {
            step(onStep, "Configuring adapter ($cmd)")
            when (send(cmd)) {
                is ElmResult.Unknown -> {
                    // Typical for clones (ATAT1 and friends). Nothing depends on them.
                    unsupported.add(cmd)
                    log.record(LogKind.INFO, "$cmd not supported by this adapter, continuing")
                }
                is ElmResult.Timeout ->
                    throw TransportException("Adapter stopped answering during setup ($cmd)")
                else -> Unit
            }
        }
    }

    /** Returns the ATI version text ("unknown" if absent; informational only, clones lie anyway). */
    private suspend fun readAdapterIdentity(onStep: (String) -> Unit, unsupported: MutableSet<String>): String {
        step(onStep, "Reading adapter info")
        val ati = send("ATI")
        if (ati is ElmResult.Unknown) unsupported.add("ATI")
        val elmVersion = (ati as? ElmResult.Data)?.lines
            ?.let { lines -> lines.firstOrNull { it.contains("ELM", ignoreCase = true) } ?: lines.firstOrNull() }
            ?: "unknown"

        when (val description = send("AT@1")) {
            is ElmResult.Unknown -> unsupported.add("AT@1")
            is ElmResult.Data -> log.record(LogKind.INFO, "Adapter description: ${description.lines.joinToString(" ")}")
            else -> Unit
        }
        return elmVersion
    }

    /** The protocol the ECU answered on, and whether it was the saved one. */
    private class EcuProtocol(val number: String, val isSaved: Boolean)

    /**
     * Saved protocol first, then ATSP0 auto-detection, then the explicit K-line ladder.
     *
     * @throws EcuNotRespondingException when no protocol gets an answer to 0100.
     */
    private suspend fun connectToEcu(
        savedProtocol: String?,
        onStep: (String) -> Unit,
        unsupported: MutableSet<String>,
    ): EcuProtocol {
        if (savedProtocol != null) {
            step(onStep, "Connecting to ECU (saved protocol $savedProtocol)")
            val probe = tryProtocol(savedProtocol, unsupported, SAVED_PROBE_TIMEOUT_MS)
            if (probe is ElmResult.Data) return EcuProtocol(savedProtocol, isSaved = true)
            log.record(LogKind.INFO, "Saved protocol $savedProtocol failed (${describe(probe)}), falling back to auto-detect")
            // ISO 9141-2 wants the bus idle for a while before the next 5-baud init.
            delay(KLINE_REINIT_PAUSE_MS)
        }

        step(onStep, "Detecting protocol (ECU init takes a few seconds)")
        send("ATSP0")
        var lastProbe = probeEcu(DETECT_PROBE_TIMEOUT_MS)
        if (lastProbe is ElmResult.Data) return EcuProtocol(readDetectedProtocol(), isSaved = false)

        // Clone auto-search is unreliable on K-line, and some Toyota ECUs only answer a 5-baud
        // init to address 0x13. Try the K-line protocols explicitly before giving up.
        for (candidate in FALLBACK_PROTOCOLS) {
            if (candidate == savedProtocol) continue
            delay(KLINE_REINIT_PAUSE_MS)
            step(onStep, "Trying ${protocolLabel(candidate)}")
            lastProbe = tryProtocol(candidate, unsupported, SAVED_PROBE_TIMEOUT_MS)
            if (lastProbe is ElmResult.Data) return EcuProtocol(candidate, isSaved = false)
        }

        throw EcuNotRespondingException(
            "ECU did not respond (${describe(lastProbe)}). Is the ignition on, " +
                "is the adapter plugged into the car and is this the right vehicle?",
        )
    }

    /**
     * Protocol number the auto-search settled on (ATDPN). An unreadable answer keeps
     * [AUTO_PROTOCOL]: the link still works, and the connection manager does not save it, so the
     * next start simply searches again.
     */
    private suspend fun readDetectedProtocol(): String {
        val dpn = send("ATDPN")
        val number = (dpn as? ElmResult.Data)?.lines?.firstOrNull()?.let { ElmParser.parseProtocolNumber(it) }
        if (number == null) {
            log.record(LogKind.INFO, "ATDPN unreadable (${describe(dpn)}), staying on auto-detect")
        }
        return number ?: AUTO_PROTOCOL
    }

    /** ATZ, retried once: many clones swallow the first one right after power-up. */
    private suspend fun resetAdapter() {
        var last: ElmResult? = null
        repeat(RESET_ATTEMPTS) { attempt ->
            val r = execute("ATZ", RESET_TIMEOUT_MS)
            last = r
            if (r is ElmResult.Data) return
            log.record(LogKind.INFO, "ATZ attempt ${attempt + 1} failed (${describe(r)})")
            delay(RETRY_PAUSE_MS)
        }
        if (last is ElmResult.Timeout) {
            throw TransportException("Adapter did not answer ATZ; wrong device or adapter asleep?")
        }
        // Garbage/"?" on both tries: carry on, the next command tells whether the adapter is alive.
    }

    /**
     * Selects [protocol] (an ELM protocol number, or [TOYOTA_PROFILE]) and probes the ECU.
     * Optional tuning commands answered with "?" are recorded and skipped.
     */
    private suspend fun tryProtocol(protocol: String, unsupported: MutableSet<String>, timeoutMs: Long): ElmResult {
        for (cmd in protocolCommands(protocol)) {
            val r = send(cmd)
            if (r is ElmResult.Unknown) {
                unsupported.add(cmd)
                // Without the protocol selection itself there is nothing to probe.
                if (cmd.startsWith("ATSP")) return r
            }
        }
        return probeEcu(timeoutMs)
    }

    private fun protocolCommands(protocol: String): List<String> =
        if (protocol == TOYOTA_PROFILE) TOYOTA_COMMANDS else listOf("ATSP$protocol")

    private fun protocolLabel(protocol: String): String = when (protocol) {
        "3" -> "ISO 9141-2"
        "4" -> "KWP2000 (5-baud init)"
        "5" -> "KWP2000 (fast init)"
        TOYOTA_PROFILE -> "Toyota K-line profile"
        else -> "protocol $protocol"
    }

    /** 0100 with a long timeout (K-line init). Garbage and bus errors get one retry. */
    private suspend fun probeEcu(timeoutMs: Long): ElmResult {
        var result = send("0100", timeoutMs)
        if (result is ElmResult.Garbage || result is ElmResult.BusError || result is ElmResult.Stopped) {
            delay(RETRY_PAUSE_MS)
            result = send("0100", timeoutMs)
        }
        return result
    }

    /** [execute] for init steps: a banner in reply to anything but ATZ/ATI means the adapter reset. */
    private suspend fun send(cmd: String, timeoutMs: Long = AT_TIMEOUT_MS): ElmResult {
        val result = execute(cmd, timeoutMs)
        if (result !is ElmResult.Timeout &&
            cmd.uppercase() !in BANNER_EXPECTED &&
            ElmParser.looksLikeReset(result.raw)
        ) {
            log.record(LogKind.ERROR, "Unexpected adapter banner in reply to \"$cmd\"")
            throw AdapterResetException()
        }
        return result
    }

    private fun step(onStep: (String) -> Unit, text: String) {
        log.record(LogKind.INFO, text)
        onStep(text)
    }

    private fun describe(result: ElmResult): String = when (result) {
        is ElmResult.Data -> "data"
        is ElmResult.NoData -> "NO DATA"
        is ElmResult.Unknown -> "?"
        is ElmResult.InitError -> "bus init failed"
        is ElmResult.BusError -> "bus error"
        is ElmResult.CanError -> "CAN error"
        is ElmResult.Stopped -> "stopped"
        is ElmResult.BufferFull -> "buffer full"
        is ElmResult.Timeout -> "timeout"
        is ElmResult.Garbage -> "garbled reply"
    }

    companion object {
        /**
         * Pseudo protocol id for the Toyota K-line init that owners of ZZE12x Corollas needed
         * (see docs/research/2zz-ge-obd.md): 9600 baud, 5-baud init to ECU address 0x13,
         * KWP header 81 13 F1, no ELM wakeup messages. Stored like a protocol number.
         */
        const val TOYOTA_PROFILE = "T"

        /** ATSP0: let the adapter search. Reported when detection worked but ATDPN was unreadable. */
        const val AUTO_PROTOCOL = "0"

        private val TOYOTA_COMMANDS = listOf("ATIB96", "ATIIA13", "ATSH8113F1", "ATSW00", "ATSPA4")

        /** Tried in order when ATSP0 finds nothing: ISO 9141-2 first, it is the likeliest for a 2ZZ-GE. */
        private val FALLBACK_PROTOCOLS = listOf("3", "5", "4", TOYOTA_PROFILE)

        private val SETUP_COMMANDS = listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1")
        private val BANNER_EXPECTED = setOf("ATZ", "ATWS", "ATI")

        private const val AT_TIMEOUT_MS = 2_000L
        private const val RESET_TIMEOUT_MS = 4_000L

        /**
         * A saved protocol skips the search, so only the 5-baud init (a few seconds) has to fit.
         * Also used per rung of the explicit fallback ladder. Not yet measured in the car.
         */
        private const val SAVED_PROBE_TIMEOUT_MS = 10_000L

        /**
         * 0100 after ATSP0: the adapter walks its protocol list (a clone searches slowly) and then
         * does the slow 5-baud init. The real car's search took longer than the 15 s we first
         * allowed (docs/research/esp32-obd2-lessons.md), the reference firmware allows 20 s.
         */
        private const val DETECT_PROBE_TIMEOUT_MS = 25_000L
        private const val RESET_ATTEMPTS = 2
        private const val RETRY_PAUSE_MS = 300L
        private const val KLINE_REINIT_PAUSE_MS = 1_500L
        private const val RESYNC_CHAR = " "
        private const val RESYNC_TIMEOUT_MS = 1_500L
        private const val MAX_LOGGED_COMMAND_LENGTH = 20
    }
}
