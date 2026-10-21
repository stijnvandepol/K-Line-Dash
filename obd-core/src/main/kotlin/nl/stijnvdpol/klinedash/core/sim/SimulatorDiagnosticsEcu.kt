package nl.stijnvdpol.klinedash.core.sim

import kotlin.math.min

/**
 * Diagnostic services of the simulated ECU, in the ISO 9141-2 formats of a pre-CAN car: trouble
 * codes (03, 07), clear (04), freeze frame (02) and vehicle info (09). Service 0A (permanent
 * codes) is not implemented, like on the real car. The readiness bits of PID 01 are also kept
 * here because service 04 resets them together with the codes.
 *
 * State lives in [config] (shared by every transport of a reconnecting manager), so a clear
 * survives a reconnect like it would in the car.
 */
internal class SimulatorDiagnosticsEcu(private val config: SimulatorConfig) {

    /** Response frames for a diagnostic [request]; null for "NO DATA" (also for unknown services). */
    fun answer(request: IntArray): List<List<Int>>? = when (request[0]) {
        SERVICE_FREEZE_FRAME -> freezeFrame(request)
        SERVICE_STORED_CODES -> if (request.size == 1) dtcFrames(RESPONSE_STORED_CODES, config.storedDtcs) else null
        SERVICE_CLEAR -> if (request.size == 1) clear() else null
        SERVICE_PENDING_CODES -> if (request.size == 1) dtcFrames(RESPONSE_PENDING_CODES, config.pendingDtcs) else null
        SERVICE_VEHICLE_INFO -> vehicleInfo(request)
        else -> null
    }

    /**
     * Data bytes A-D of PID 01: MIL and code count, then the spark-ignition monitors. A monitor
     * the ECU has is "available"; it is "incomplete" (not ready) while its bit is set in the
     * incomplete bytes of [config].
     */
    fun monitorStatusBytes(): List<Int> {
        val count = config.storedDtcs.size
        val milAndCount = (if (count > 0) MIL_BIT else 0) or min(count, DTC_COUNT_MASK)
        val incompleteContinuous = (config.incompleteContinuousMonitors and CONTINUOUS_MASK) shl INCOMPLETE_SHIFT
        return listOf(
            milAndCount,
            CONTINUOUS_MASK or incompleteContinuous,
            AVAILABLE_MONITORS,
            config.incompleteMonitors and AVAILABLE_MONITORS,
        )
    }

    /**
     * Service 04: a real ECU wipes stored and pending codes, the freeze frame (derived from the
     * stored codes) and the readiness status, which then counts as "not ready" until the monitors
     * have run again. With [SimulatorConfig.refusesClear] it behaves like an engine that is running.
     */
    private fun clear(): List<List<Int>>? {
        if (config.refusesClear) return null
        config.storedDtcs.clear()
        config.pendingDtcs.clear()
        config.incompleteContinuousMonitors = CONTINUOUS_MASK
        config.incompleteMonitors = AVAILABLE_MONITORS
        return listOf(listOf(RESPONSE_CLEAR))
    }

    /** Freeze frame data; only exists while a DTC is stored, and only frame 0. */
    private fun freezeFrame(request: IntArray): List<List<Int>>? {
        if (request.size !in 2..3) return null
        val pid = request[1]
        val frameNumber = if (request.size == 3) request[2] else 0
        if (frameNumber != 0) return null
        val trigger = config.storedDtcs.firstOrNull()?.let { encodeDtc(it) } ?: return null
        val data: List<Int> = when (pid) {
            PID_SUPPORTED -> supportedPidBitmap(FREEZE_FRAME_DATA.keys + PID_TRIGGER_DTC, 0)
            PID_TRIGGER_DTC -> listOf(trigger.first, trigger.second)
            else -> FREEZE_FRAME_DATA[pid] ?: return null
        }
        return listOf(listOf(RESPONSE_FREEZE_FRAME, pid, frameNumber) + data)
    }

    private fun vehicleInfo(request: IntArray): List<List<Int>>? {
        if (request.size != 2) return null
        val vin = config.vin
        val name = config.ecuName
        if (vin == null && name == null) return null
        return when (request[1]) {
            PID_SUPPORTED -> {
                val pids = HashSet<Int>()
                if (vin != null) pids.add(PID_VIN)
                if (name != null) pids.add(PID_ECU_NAME)
                listOf(listOf(RESPONSE_VEHICLE_INFO, PID_SUPPORTED) + supportedPidBitmap(pids, 0))
            }
            // ISO 9141-2 sends the VIN as 4-byte pieces behind three padding bytes.
            PID_VIN -> vin?.let { numberedFrames(PID_VIN, List(VIN_PADDING_BYTES) { 0 } + ascii(it)) }
            PID_ECU_NAME -> name?.let {
                val bytes = ascii(it.take(ECU_NAME_LENGTH))
                numberedFrames(PID_ECU_NAME, bytes + List(ECU_NAME_LENGTH - bytes.size) { 0 })
            }
            else -> null
        }
    }

    private fun numberedFrames(pid: Int, payload: List<Int>): List<List<Int>> =
        payload.chunked(BYTES_PER_VEHICLE_INFO_FRAME).mapIndexed { i, chunk ->
            listOf(RESPONSE_VEHICLE_INFO, pid, i + 1) + chunk + List(BYTES_PER_VEHICLE_INFO_FRAME - chunk.size) { 0 }
        }

    private fun ascii(s: String): List<Int> = s.map { it.code and 0xFF }

    private fun dtcFrames(responseMode: Int, codes: List<String>): List<List<Int>> {
        val encoded = codes.mapNotNull { encodeDtc(it) }
        // With nothing stored the ECU still answers: one frame of zeros.
        if (encoded.isEmpty()) return listOf(listOf(responseMode) + List(CODES_PER_FRAME * 2) { 0 })
        return encoded.chunked(CODES_PER_FRAME).map { chunk ->
            val frame = ArrayList<Int>(1 + CODES_PER_FRAME * 2)
            frame.add(responseMode)
            for ((high, low) in chunk) {
                frame.add(high)
                frame.add(low)
            }
            repeat(CODES_PER_FRAME - chunk.size) {
                frame.add(0)
                frame.add(0)
            }
            frame
        }
    }

    companion object {
        private const val SERVICE_FREEZE_FRAME = 0x02
        private const val SERVICE_STORED_CODES = 0x03
        private const val SERVICE_CLEAR = 0x04
        private const val SERVICE_PENDING_CODES = 0x07
        private const val SERVICE_VEHICLE_INFO = 0x09

        private const val RESPONSE_FREEZE_FRAME = 0x42
        private const val RESPONSE_STORED_CODES = 0x43
        private const val RESPONSE_CLEAR = 0x44
        private const val RESPONSE_PENDING_CODES = 0x47
        private const val RESPONSE_VEHICLE_INFO = 0x49

        private const val PID_SUPPORTED = 0x00
        private const val PID_TRIGGER_DTC = 0x02
        private const val PID_VIN = 0x02
        private const val PID_ECU_NAME = 0x0A

        private const val CODES_PER_FRAME = 3
        private const val VIN_PADDING_BYTES = 3
        private const val ECU_NAME_LENGTH = 20
        private const val BYTES_PER_VEHICLE_INFO_FRAME = 4

        private const val MIL_BIT = 0x80
        private const val DTC_COUNT_MASK = 0x7F

        /** PID 01 byte B: misfire, fuel system and components monitors exist (bits 0-2). */
        private const val CONTINUOUS_MASK = 0x07
        private const val INCOMPLETE_SHIFT = 4

        /**
         * PID 01 byte C: catalyst (bit 0), evaporative system (2), oxygen sensor (5) and oxygen
         * sensor heater (6): what a 2ZZ-GE reports.
         */
        private const val AVAILABLE_MONITORS = 0x65

        /**
         * What the ECU stored with the first code, as the Mode 01 data bytes of each PID. Values
         * suit a lean code (P0171): warm engine at 2100 rpm, long-term trim well positive.
         */
        private val FREEZE_FRAME_DATA: Map<Int, List<Int>> = mapOf(
            0x04 to listOf(0x4D), // load 30 %
            0x05 to listOf(0x7D), // coolant 85 C
            0x06 to listOf(0x84), // short-term trim +3.1 %
            0x07 to listOf(0x90), // long-term trim +12.5 %
            0x0C to listOf(0x20, 0xD0), // 2100 rpm
            0x0D to listOf(0x38), // 56 km/h
            0x0E to listOf(0x98), // timing 12 degrees
            0x0F to listOf(0x37), // intake air 15 C
            0x10 to listOf(0x04, 0xD2), // MAF 12.34 g/s
            0x11 to listOf(0x40), // throttle 25 %
        )

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
