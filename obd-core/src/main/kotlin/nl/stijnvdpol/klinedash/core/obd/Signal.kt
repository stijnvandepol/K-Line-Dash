package nl.stijnvdpol.klinedash.core.obd

import nl.stijnvdpol.klinedash.core.elm.ElmResult

/**
 * How urgently a signal wants fresh values. On the target car (ISO 9141-2) one request took
 * 0.45-0.5 s with a clone adapter (docs/research/esp32-obd2-lessons.md), so the whole bus budget is
 * about two requests per second; tiers decide who gets it.
 */
enum class PollTier {
    /** As often as possible (RPM, speed, throttle). */
    FAST,

    /** Every few seconds (coolant, intake temp, load, timing). */
    MEDIUM,

    /** Now and then (fuel trims, O2 sensors, battery voltage). */
    SLOW,
}

/**
 * One displayable value. Several signals may share one [command] (e.g. O2 sensor voltage and its
 * trim both come from 0114); the poller sends that command once and decodes every demanded signal.
 *
 * @property id stable key used everywhere (UI layouts, logging, warnings), e.g. "rpm", "coolant".
 * @property command what is sent to the ELM327, e.g. "010C" or "ATRV".
 * @property pid Mode 01 PID number used to check ECU support; null for adapter commands (ATRV),
 *   which are always available.
 * @property name sentence-case name for lists, e.g. "Coolant temperature".
 * @property min lower end of the display range (gauge scale); [max] the upper end.
 * @property decimals digits after the decimal point when shown as a number.
 * @property decode extracts this signal's value from a successful reply; null when the reply does
 *   not contain it (wrong PID echoed back, too few bytes, garbage).
 * @property decodePayload the PID's formula applied to its data bytes only (after service and PID
 *   echo); null for adapter commands. Lets freeze-frame data (Mode 02) reuse the Mode 01 formulas.
 * @property pidDataLength exact number of data bytes the ECU sends for [pid] (SAE J1979), null for
 *   adapter commands. Needed to split a multi-PID reply, where the blocks follow each other without
 *   any separator.
 */
class Signal(
    val id: String,
    val command: String,
    val pid: Int?,
    val name: String,
    /** Short label for small tiles, e.g. "Coolant" for "Coolant temperature". */
    val shortName: String,
    val unit: String,
    val min: Double,
    val max: Double,
    val decimals: Int,
    val defaultTier: PollTier,
    val decodePayload: ((IntArray) -> Double?)? = null,
    val pidDataLength: Int? = null,
    val decode: (ElmResult.Data) -> Double?,
) {
    override fun toString(): String = "Signal($id, $command)"
}

/** One decoded value with the time it was received (System.currentTimeMillis()). */
data class Reading(
    val signalId: String,
    val value: Double,
    val timestampMs: Long,
)
