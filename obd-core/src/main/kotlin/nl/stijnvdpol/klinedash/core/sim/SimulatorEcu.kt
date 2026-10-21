package nl.stijnvdpol.klinedash.core.sim

import kotlin.math.roundToInt

/**
 * The simulated engine ECU: turns an OBD request (service, PID, ...; no header, no checksum) into
 * the response frames the real car would send. Mode 01 live data comes from [engine]; everything
 * about trouble codes, readiness and vehicle info lives in [SimulatorDiagnosticsEcu].
 */
internal class SimulatorEcu(
    private val config: SimulatorConfig,
    private val engine: EngineModel,
    private val clock: () -> Long,
) {
    private val diagnostics = SimulatorDiagnosticsEcu(config)

    /** Response frames for [request], or null for "NO DATA". */
    fun answer(request: IntArray): List<List<Int>>? = when (request[0]) {
        MODE_CURRENT_DATA -> if (request.size == 2) mode01(request[1])?.let { listOf(it) } else null
        else -> diagnostics.answer(request)
    }

    private fun mode01(pid: Int): List<Int>? {
        val supported = config.supportedPids
        if (pid % PIDS_PER_BLOCK == 0) {
            if (pid != 0 && pid !in supported) return null
            return listOf(RESPONSE_CURRENT_DATA, pid) + supportedPidBitmap(supported, pid)
        }
        if (pid !in supported) return null

        val s = engine.snapshot(clock())
        val data: List<Int> = when (pid) {
            0x01 -> diagnostics.monitorStatusBytes()
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
            0x12 -> listOf(SECONDARY_AIR_OFF)
            0x13 -> listOf(O2_SENSORS_B1S1_B1S2)
            0x14 -> listOf(byteOf(s.o2UpstreamV * 200.0), trimByte(s.shortTrimPct))
            0x15 -> listOf(byteOf(s.o2DownstreamV * 200.0), 0xFF)
            0x1C -> listOf(OBD_STANDARD_EOBD)
            0x21 -> twoBytes(DISTANCE_WITH_MIL_KM)
            else -> return null
        }
        return listOf(RESPONSE_CURRENT_DATA, pid) + data
    }

    /** Fuel trim percent to the OBD byte: 128 = 0 %, 1.28 counts per percent. */
    private fun trimByte(percent: Double): Int = byteOf(128.0 + percent * 1.28)

    private fun byteOf(value: Double): Int = value.roundToInt().coerceIn(0, 255)

    private companion object {
        const val MODE_CURRENT_DATA = 0x01
        const val RESPONSE_CURRENT_DATA = 0x41
        const val PIDS_PER_BLOCK = 0x20

        /** PID 12: air status "off / atmosphere". Fixed: the real value is bit-coded, nothing to chart. */
        const val SECONDARY_AIR_OFF = 0x04

        /** PID 13: oxygen sensors present, bank 1 sensor 1 and 2 (bits 0 and 1). */
        const val O2_SENSORS_B1S1_B1S2 = 0x03

        /** PID 1C: 6 = EOBD, what the real car reports. */
        const val OBD_STANDARD_EOBD = 0x06

        /** PID 21: the real car reports 0 km driven with the MIL on. */
        const val DISTANCE_WITH_MIL_KM = 0
    }
}

/** The four bytes of a supported-PID bitmap for the block starting after [base] (0x00, 0x20, ...). */
internal fun supportedPidBitmap(pids: Set<Int>, base: Int): List<Int> {
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

/** [value] as two big-endian bytes, clamped to 0..0xFFFF. */
internal fun twoBytes(value: Int): List<Int> {
    val v = value.coerceIn(0, 0xFFFF)
    return listOf(v shr 8, v and 0xFF)
}
