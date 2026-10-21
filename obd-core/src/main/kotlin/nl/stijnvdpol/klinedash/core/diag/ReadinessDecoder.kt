package nl.stijnvdpol.klinedash.core.diag

/** What service 01 PID 01 says about the engine: MIL, code count and the readiness monitors. */
data class EngineStatus(
    val isMilOn: Boolean,
    val dtcCount: Int,
    val isSparkIgnition: Boolean,
    val monitors: List<ReadinessMonitor>,
)

/**
 * Decodes the four data bytes A-D of service 01 PID 01 (SAE J1979).
 *
 * A: bit 7 = MIL, bits 0-6 = number of stored codes.
 * B: bits 0-2 = continuous monitors available (misfire, fuel system, components), bits 4-6 = the
 *    same monitors incomplete, bit 3 = compression ignition (0 = spark).
 * C: non-continuous monitors available; D: the same monitors incomplete. The bit meaning of C and
 *    D depends on the ignition type, see [SPARK_MONITORS] and [COMPRESSION_MONITORS].
 */
object ReadinessDecoder {

    private const val STATUS_BYTES = 4
    private const val MIL_BIT = 0x80
    private const val DTC_COUNT_MASK = 0x7F
    private const val COMPRESSION_IGNITION_BIT = 0x08
    private const val CONTINUOUS_INCOMPLETE_SHIFT = 4

    /** A monitor and its bit position (0-7) in bytes C and D. */
    private class MonitorBit(val id: String, val bit: Int)

    private val CONTINUOUS_MONITORS = listOf(
        MonitorBit("misfire", 0),
        MonitorBit("fuel_system", 1),
        MonitorBit("components", 2),
    )

    private val SPARK_MONITORS = listOf(
        MonitorBit("catalyst", 0),
        MonitorBit("heated_catalyst", 1),
        MonitorBit("evap", 2),
        MonitorBit("secondary_air", 3),
        MonitorBit("ac_refrigerant", 4),
        MonitorBit("o2_sensor", 5),
        MonitorBit("o2_heater", 6),
        MonitorBit("egr", 7),
    )

    // Bits 2 and 4 are reserved for compression ignition.
    private val COMPRESSION_MONITORS = listOf(
        MonitorBit("nmhc_catalyst", 0),
        MonitorBit("nox_scr", 1),
        MonitorBit("boost_pressure", 3),
        MonitorBit("exhaust_gas_sensor", 5),
        MonitorBit("pm_filter", 6),
        MonitorBit("egr_vvt", 7),
    )

    /** [bytes] are the data bytes of the reply (after "41 01"); null when there are fewer than four. */
    fun decode(bytes: IntArray): EngineStatus? {
        if (bytes.size < STATUS_BYTES) return null
        val (statusA, statusB, available, incomplete) = bytes
        val isCompression = statusB and COMPRESSION_IGNITION_BIT != 0

        val continuous = CONTINUOUS_MONITORS.map {
            val isIncompleteBit = 1 shl (it.bit + CONTINUOUS_INCOMPLETE_SHIFT)
            monitor(it.id, statusB and (1 shl it.bit) != 0, statusB and isIncompleteBit != 0)
        }
        val others = (if (isCompression) COMPRESSION_MONITORS else SPARK_MONITORS).map {
            monitor(it.id, available and (1 shl it.bit) != 0, incomplete and (1 shl it.bit) != 0)
        }
        return EngineStatus(
            isMilOn = statusA and MIL_BIT != 0,
            dtcCount = statusA and DTC_COUNT_MASK,
            isSparkIgnition = !isCompression,
            monitors = continuous + others,
        )
    }

    private fun monitor(id: String, isAvailable: Boolean, isIncomplete: Boolean): ReadinessMonitor {
        val status = when {
            !isAvailable -> MonitorStatus.NOT_APPLICABLE
            isIncomplete -> MonitorStatus.NOT_READY
            else -> MonitorStatus.READY
        }
        return ReadinessMonitor(id, status)
    }
}
