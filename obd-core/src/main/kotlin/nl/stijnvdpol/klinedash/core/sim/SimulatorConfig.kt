package nl.stijnvdpol.klinedash.core.sim

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

/** What the simulated engine does while running. */
enum class Scenario {
    /** Stationary at idle. */
    IDLE,

    /** Looping scripted drive: launch, shift through the gears, cruise, VVTL-i pull, decel, stop. */
    DRIVE_CYCLE,
}

/** How a simulated clone treats a request that carries more than one PID ("010C0D"). */
enum class LongRequestQuirk {
    /** Passes the request on unchanged. */
    NONE,

    /** Cuts the request to its first PID, so the reply holds only that block (ARM clones, see popular-obd-adapters.md). */
    TRUNCATES,

    /** Answers "?" (reported by users of SwiftOBD2 with some clones). */
    REJECTS,
}

/**
 * Tunable behaviour of [SimulatorTransport]: the simulated car (a 2ZZ-GE Corolla T-Sport on
 * ISO 9141-2 by default, or a CAN car with [carProtocol] "6"-"9"), timing, and fault injection for
 * exercising reconnect and clone-quirk handling.
 *
 * All properties are @Volatile and the collections are copy-on-write, so a debug screen on the
 * UI thread can flip them while the OBD thread is talking to the simulator. Every fault is off
 * by default. The object is shared by the transports a reconnecting manager creates, so
 * one-shot faults (see [dropConnectionAfterCommands]) clear themselves once they fire.
 */
class SimulatorConfig {
    /** Seed for the fault dice and engine noise; same seed + same traffic = same replies. */
    @Volatile var seed: Long = 20_240_607L

    /**
     * ELM protocol number the simulated car really speaks ("3" = ISO 9141-2). "6" to "9" make it a
     * CAN car (ISO 15765-4): fast replies ([canLatencyMinMs]), up to [maxPidsPerRequest] PIDs per
     * Mode 01 request, "0:"/"1:" multi-frame lines for replies over seven bytes (VIN, long code
     * lists), CAN-style Mode 03/07 (count byte) and Mode 09 (one message). Replies carry no
     * headers on CAN, whatever ATH says.
     */
    @Volatile var carProtocol: String = "3"

    /** True when [carProtocol] is one of the ISO 15765-4 CAN variants (6-9). */
    val isCanCar: Boolean get() = carProtocol in CAN_PROTOCOLS

    /** What ATI answers. Real clones claim "ELM327 v1.5" or "v2.1" regardless of hardware. */
    @Volatile var elmVersion: String = "ELM327 v1.5"

    /** What STI answers, like an STN chip ("STN1110 v4.2.0"); null = "?", like every other adapter. */
    @Volatile var stnDeviceId: String? = null

    // --- Timing (all in simulated milliseconds, realised with coroutine delay()) ---

    @Volatile var connectLatencyMs: Long = 150
    @Volatile var atLatencyMs: Long = 15
    @Volatile var resetLatencyMs: Long = 400

    /**
     * K-line request/response latency is drawn uniformly from this range per OBD request. The
     * defaults are the ~0.45-0.5 s per request measured on the real car (docs/research/esp32-obd2-lessons.md).
     */
    @Volatile var latencyMinMs: Long = KLINE_LATENCY_MIN_MS
    @Volatile var latencyMaxMs: Long = KLINE_LATENCY_MAX_MS

    /**
     * Same, for a request that carries the ELM response-count suffix ("010C1"). ASSUMPTION, not yet
     * measured in the car: the adapter answers as soon as the expected number of replies has
     * arrived instead of waiting out its bus timeout.
     */
    @Volatile var responseCountLatencyMinMs: Long = RESPONSE_COUNT_LATENCY_MIN_MS
    @Volatile var responseCountLatencyMaxMs: Long = RESPONSE_COUNT_LATENCY_MAX_MS

    /** CAN request/response latency range per OBD request (a single ECU on a 500 kbit bus is fast). */
    @Volatile var canLatencyMinMs: Long = CAN_LATENCY_MIN_MS
    @Volatile var canLatencyMaxMs: Long = CAN_LATENCY_MAX_MS

    /** Extra wait before the adapter gives up on an unanswered request ("NO DATA"). */
    @Volatile var noDataLatencyMs: Long = 250

    /** Duration of the 5-baud / fast bus init triggered by the first OBD request. */
    @Volatile var busInitMs: Long = 2_500

    /** Same on CAN, where there is no wake-up sequence, just the protocol search. */
    @Volatile var canBusInitMs: Long = 600

    // --- Car ---

    /**
     * Stored (Mode 03) DTCs such as "P0171". Clearing via Mode 04 empties this list. The freeze
     * frame (Mode 02) is the snapshot of the first entry and disappears with it.
     */
    val storedDtcs: MutableList<String> = CopyOnWriteArrayList(listOf("P0171", "P1349"))

    /** Pending (Mode 07) DTCs. */
    val pendingDtcs: MutableList<String> = CopyOnWriteArrayList(listOf("P0300"))

    /**
     * Continuous monitors that have not completed yet, as bits 0-2 of PID 01 byte B: misfire (1),
     * fuel system (2), comprehensive components (4). Service 04 sets all three, like a real ECU.
     */
    @Volatile var incompleteContinuousMonitors: Int = 0

    /**
     * Non-continuous monitors that have not completed yet, as PID 01 byte D (bit positions as in
     * byte C): catalyst 0x01, evaporative system 0x04, oxygen sensor 0x20, oxygen sensor heater
     * 0x40. The default has only the evaporative monitor pending, which needs a special drive
     * cycle and is the usual straggler. Service 04 sets every monitor the ECU has.
     */
    @Volatile var incompleteMonitors: Int = EVAP_MONITOR_BIT

    /**
     * Service 04 is answered with "NO DATA" and changes nothing, like an ECU that refuses to clear
     * codes while the engine runs.
     */
    @Volatile var refusesClear: Boolean = false

    /** Mode 01 PIDs the ECU supports; the default is the list read from the real car. */
    @Volatile var supportedPids: Set<Int> = DEFAULT_SUPPORTED_PIDS

    /** PIDs the ECU accepts in one Mode 01 request; CAN only, a K-line ECU takes exactly one. SAE J1979 allows six. */
    @Volatile var maxPidsPerRequest: Int = MAX_CAN_PIDS_PER_REQUEST

    /** How the simulated adapter treats a multi-PID request before the ECU sees it. */
    @Volatile var longRequestQuirk: LongRequestQuirk = LongRequestQuirk.NONE

    /** 17-character VIN; Mode 09 answers "NO DATA" while both this and [ecuName] are null. */
    @Volatile var vin: String? = null

    /** ECU name for Mode 09 PID 0A (max 20 characters). */
    @Volatile var ecuName: String? = null

    @Volatile var scenario: Scenario = Scenario.DRIVE_CYCLE
    @Volatile var ambientC: Double = 15.0

    /**
     * Coolant start temperature; null = ambient (cold start). The default is the 46 C the real car
     * reported with the ignition on (esp32-obd2-lessons.md).
     */
    @Volatile var initialCoolantC: Double? = REAL_CAR_COOLANT_C

    /** Warm-up time constant in seconds (real cars ~300+; short default so demos get warm). */
    @Volatile var warmupTimeConstantS: Double = 90.0

    @Volatile var batteryVoltageEngineOn: Double = 13.9
    @Volatile var batteryVoltageEngineOff: Double = 12.4

    // --- Fault injection (all off by default) ---

    /** Connection attempts fail with a TransportException. */
    @Volatile var failConnect: Boolean = false

    /** AT commands (upper case, no spaces, e.g. "ATAT1", "ATLP", "AT@1") answered with "?". */
    val cloneRejectsAt: MutableSet<String> = CopyOnWriteArraySet()

    /** Probability that an OBD request that would be answered gets "NO DATA" instead. */
    @Volatile var randomNoDataRate: Double = 0.0

    /** Probability that a reply is corrupted (NUL/0xFF bytes and a stray printable character). */
    @Volatile var garbageRate: Double = 0.0

    /** Probability that a command never gets a prompt (readUntilPrompt times out). */
    @Volatile var timeoutRate: Double = 0.0

    /** Probability that a command is answered with the power-up banner and the adapter resets. */
    @Volatile var spontaneousResetRate: Double = 0.0

    /**
     * One-shot: the Nth command written on a connection (1-based) is answered with the banner
     * and the adapter resets. Cleared when it fires. Deterministic alternative to the rate.
     */
    @Volatile var spontaneousResetAtCommand: Int? = null

    /**
     * The simulated clone answers "?" to a request with the response-count suffix ("010C1"), as
     * some clones do. Lets a poller's fallback to the plain request be tested end to end.
     */
    @Volatile var cloneRejectsResponseCount: Boolean = false

    /** Bus init always fails ("BUS INIT: ...ERROR" / "UNABLE TO CONNECT"). */
    @Volatile var initFails: Boolean = false

    /** ATSP0 auto-search never finds the ECU, but selecting [carProtocol] explicitly works (flaky clone search). */
    @Volatile var autoDetectFails: Boolean = false

    /**
     * One-shot: after this many commands on a connection the next write throws a
     * TransportException and the connection closes. Cleared when it fires, so the reconnect works.
     */
    @Volatile var dropConnectionAfterCommands: Int? = null

    companion object {
        /** Measured on the real K-line car: 0.45-0.5 s per request (5 PIDs took 2.2-2.5 s). */
        const val KLINE_LATENCY_MIN_MS = 450L
        const val KLINE_LATENCY_MAX_MS = 500L

        /** A CAN car answers a request in tens of milliseconds. */
        const val CAN_LATENCY_MIN_MS = 40L
        const val CAN_LATENCY_MAX_MS = 60L

        /** ELM protocol numbers of ISO 15765-4 CAN. */
        val CAN_PROTOCOLS = setOf("6", "7", "8", "9")

        /** SAE J1979: at most six PIDs in one Mode 01 request. */
        const val MAX_CAN_PIDS_PER_REQUEST = 6

        /** Assumed (to verify in the car) latency of a request with the response-count suffix. */
        const val RESPONSE_COUNT_LATENCY_MIN_MS = 120L
        const val RESPONSE_COUNT_LATENCY_MAX_MS = 160L

        /** Evaporative system bit in PID 01 bytes C and D. */
        const val EVAP_MONITOR_BIT = 0x04

        /** Coolant temperature the real car showed with the ignition on and the engine cold. */
        const val REAL_CAR_COOLANT_C = 46.0

        /**
         * Mode 01 PIDs the real Corolla E12 2ZZ-GE reported (docs/research/esp32-obd2-lessons.md);
         * bitmap PIDs 00 and 20 are derived. 0x0B (MAP) is absent on purpose: the 2ZZ-GE uses a MAF.
         */
        val DEFAULT_SUPPORTED_PIDS: Set<Int> = setOf(
            0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0E, 0x0F,
            0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x1C, 0x20, 0x21,
        )
    }
}
