package nl.stijnvdpol.klinedash.core.obd

import nl.stijnvdpol.klinedash.core.elm.ElmParser

/**
 * Catalog of everything the dashboard can show: Mode 01 PIDs per SAE J1979 (formulas as in the
 * J1979 "data parameter" tables) plus the adapter's own battery voltage reading.
 *
 * Not every ECU supports every PID; [SupportedPidDetector] tells which ones exist. The 2ZZ-GE
 * target car reports 01 03-07 0C-15 1C 21 (docs/research/esp32-obd2-lessons.md).
 */
object Signals {

    private const val MODE_CURRENT_DATA = 1
    private const val BYTE_RANGE = 256
    private const val BYTE_MAX = 255.0
    private const val TEMPERATURE_OFFSET = 40
    private const val PERCENT_FULL_SCALE = 100.0
    private const val TRIM_ZERO_POINT = 128.0
    private const val TRIM_NOT_IN_USE = 0xFF
    private const val O2_STEPS_PER_VOLT = 200.0
    private const val RPM_STEPS_PER_RPM = 4.0
    private const val MAF_STEPS_PER_GRAM_SECOND = 100.0
    private const val FUEL_PRESSURE_KPA_PER_STEP = 3.0
    private const val MILLIVOLTS_PER_VOLT = 1000.0
    private const val FUEL_RATE_STEPS_PER_LITER_HOUR = 20.0
    private const val MIL_BIT = 0x80
    private const val DTC_COUNT_MASK = 0x7F

    private const val PID_MONITOR_STATUS = 0x01
    private const val PID_FIRST_O2_SENSOR = 0x14
    private const val SENSORS_PER_BANK = 4
    private const val BANK_COUNT = 2

    private const val BATTERY_COMMAND = "ATRV"

    /** Texts and display range of a signal. */
    private class Display(
        val name: String,
        val shortName: String,
        val unit: String,
        val min: Double,
        val max: Double,
        val decimals: Int,
    )

    val all: List<Signal> = buildList {
        addAll(engineSignals())
        addAll(fuelTrimSignals())
        addAll(oxygenSensorSignals())
        addAll(statusAndEnvironmentSignals())
        add(batterySignal())
    }

    private val signalsById: Map<String, Signal> = all.associateBy { it.id }

    fun byId(id: String): Signal? = signalsById[id]

    private fun engineSignals(): List<Signal> = listOf(
        pidSignal(
            "rpm", 0x0C, PollTier.FAST, 2,
            Display("Engine speed", "RPM", "rpm", 0.0, 9000.0, 0),
        ) { word(it[0], it[1]) / RPM_STEPS_PER_RPM },
        pidSignal(
            "speed", 0x0D, PollTier.FAST, 1,
            Display("Vehicle speed", "Speed", "km/h", 0.0, 240.0, 0),
        ) { it[0].toDouble() },
        pidSignal(
            "throttle", 0x11, PollTier.FAST, 1,
            Display("Throttle position", "Throttle", "%", 0.0, 100.0, 0),
        ) { percentOfByte(it[0]) },
        pidSignal(
            "coolant", 0x05, PollTier.MEDIUM, 1,
            Display("Coolant temperature", "Coolant", "°C", 40.0, 130.0, 0),
        ) { celsius(it[0]) },
        pidSignal(
            "intake_temp", 0x0F, PollTier.MEDIUM, 1,
            Display("Intake air temperature", "Intake air", "°C", -20.0, 80.0, 0),
        ) { celsius(it[0]) },
        pidSignal(
            "load", 0x04, PollTier.MEDIUM, 1,
            Display("Calculated engine load", "Load", "%", 0.0, 100.0, 0),
        ) { percentOfByte(it[0]) },
        pidSignal(
            "timing", 0x0E, PollTier.MEDIUM, 1,
            Display("Timing advance", "Timing", "°", -20.0, 60.0, 1),
        ) { it[0] / 2.0 - 64.0 },
        pidSignal(
            "maf", 0x10, PollTier.MEDIUM, 2,
            Display("Mass air flow", "MAF", "g/s", 0.0, 200.0, 1),
        ) { word(it[0], it[1]) / MAF_STEPS_PER_GRAM_SECOND },
        pidSignal(
            "map", 0x0B, PollTier.SLOW, 1,
            Display("Intake manifold pressure", "MAP", "kPa", 0.0, 255.0, 0),
        ) { it[0].toDouble() },
        pidSignal(
            "fuel_pressure", 0x0A, PollTier.SLOW, 1,
            Display("Fuel pressure", "Fuel pressure", "kPa", 0.0, 765.0, 0),
        ) { it[0] * FUEL_PRESSURE_KPA_PER_STEP },
    )

    private fun fuelTrimSignals(): List<Signal> = listOf(
        fuelTrim("stft_b1", 0x06, "STFT B1", "Short term fuel trim bank 1"),
        fuelTrim("ltft_b1", 0x07, "LTFT B1", "Long term fuel trim bank 1"),
        fuelTrim("stft_b2", 0x08, "STFT B2", "Short term fuel trim bank 2"),
        fuelTrim("ltft_b2", 0x09, "LTFT B2", "Long term fuel trim bank 2"),
    )

    private fun fuelTrim(id: String, pid: Int, shortName: String, name: String): Signal =
        pidSignal(id, pid, PollTier.SLOW, 1, Display(name, shortName, "%", -25.0, 25.0, 1)) {
            trimPercent(it[0])
        }

    /**
     * PIDs 0x14-0x1B are oxygen sensors 1-8 in the J1979 bank layout (the one PID 0x13 reports):
     * 0x14-0x17 are bank 1 sensors 1-4, 0x18-0x1B bank 2 sensors 1-4. Each carries the sensor
     * voltage (byte A) and its short-term fuel trim (byte B, 0xFF when not used for trim).
     */
    private fun oxygenSensorSignals(): List<Signal> =
        (0 until BANK_COUNT * SENSORS_PER_BANK).flatMap { index ->
            val pid = PID_FIRST_O2_SENSOR + index
            val position = "B${index / SENSORS_PER_BANK + 1}S${index % SENSORS_PER_BANK + 1}"
            val idPrefix = "o2_${position.lowercase()}"
            listOf(
                pidSignal(
                    "${idPrefix}_v", pid, PollTier.SLOW, 1,
                    Display("O2 sensor $position voltage", "O2 $position", "V", 0.0, 1.0, 2),
                ) { it[0] / O2_STEPS_PER_VOLT },
                pidSignal(
                    "${idPrefix}_trim", pid, PollTier.SLOW, 2,
                    Display("O2 sensor $position fuel trim", "O2 $position trim", "%", -25.0, 25.0, 1),
                ) { if (it[1] == TRIM_NOT_IN_USE) null else trimPercent(it[1]) },
            )
        }

    private fun statusAndEnvironmentSignals(): List<Signal> = listOf(
        pidSignal(
            "mil_on", PID_MONITOR_STATUS, PollTier.SLOW, 1,
            Display("Malfunction indicator lamp", "MIL", "", 0.0, 1.0, 0),
        ) { if (it[0] and MIL_BIT != 0) 1.0 else 0.0 },
        pidSignal(
            "dtc_count", PID_MONITOR_STATUS, PollTier.SLOW, 1,
            Display("Stored fault code count", "Fault codes", "", 0.0, 127.0, 0),
        ) { (it[0] and DTC_COUNT_MASK).toDouble() },
        pidSignal(
            "run_time", 0x1F, PollTier.SLOW, 2,
            Display("Engine run time", "Run time", "s", 0.0, 3600.0, 0),
        ) { word(it[0], it[1]).toDouble() },
        pidSignal(
            "distance_mil", 0x21, PollTier.SLOW, 2,
            Display("Distance with MIL on", "MIL distance", "km", 0.0, 65535.0, 0),
        ) { word(it[0], it[1]).toDouble() },
        pidSignal(
            "fuel_level", 0x2F, PollTier.SLOW, 1,
            Display("Fuel level", "Fuel level", "%", 0.0, 100.0, 0),
        ) { percentOfByte(it[0]) },
        pidSignal(
            "baro", 0x33, PollTier.SLOW, 1,
            Display("Barometric pressure", "Baro", "kPa", 80.0, 110.0, 0),
        ) { it[0].toDouble() },
        pidSignal(
            "ecu_voltage", 0x42, PollTier.SLOW, 2,
            Display("Control module voltage", "ECU voltage", "V", 8.0, 16.0, 1),
        ) { word(it[0], it[1]) / MILLIVOLTS_PER_VOLT },
        pidSignal(
            "ambient_temp", 0x46, PollTier.SLOW, 1,
            Display("Ambient air temperature", "Outside", "°C", -40.0, 60.0, 0),
        ) { celsius(it[0]) },
        pidSignal(
            "oil_temp", 0x5C, PollTier.SLOW, 1,
            Display("Engine oil temperature", "Oil", "°C", 40.0, 150.0, 0),
        ) { celsius(it[0]) },
        pidSignal(
            "fuel_rate", 0x5E, PollTier.SLOW, 2,
            Display("Engine fuel rate", "Fuel rate", "l/h", 0.0, 50.0, 1),
        ) { word(it[0], it[1]) / FUEL_RATE_STEPS_PER_LITER_HOUR },
    )

    /** Adapter-side battery voltage (ATRV): always available, not an ECU PID. */
    private fun batterySignal(): Signal = Signal(
        id = "battery",
        command = BATTERY_COMMAND,
        pid = null,
        name = "Battery voltage",
        shortName = "Battery",
        unit = "V",
        min = 8.0,
        max = 16.0,
        decimals = 1,
        defaultTier = PollTier.SLOW,
        decode = { data -> data.lines.firstOrNull()?.let(ElmParser::parseVoltage) },
    )

    /**
     * A Mode 01 signal read from [pid]. [formula] receives the data bytes and can rely on at
     * least [minBytes] of them; shorter or foreign replies decode to null.
     */
    private fun pidSignal(
        id: String,
        pid: Int,
        tier: PollTier,
        minBytes: Int,
        display: Display,
        formula: (IntArray) -> Double?,
    ): Signal {
        val decodePayload: (IntArray) -> Double? = { bytes -> bytes.takeIf { it.size >= minBytes }?.let(formula) }
        return Signal(
            id = id,
            command = "%02X%02X".format(MODE_CURRENT_DATA, pid),
            pid = pid,
            name = display.name,
            shortName = display.shortName,
            unit = display.unit,
            min = display.min,
            max = display.max,
            decimals = display.decimals,
            defaultTier = tier,
            decode = { data -> ObdReply.payload(data, MODE_CURRENT_DATA, pid)?.let(decodePayload) },
            decodePayload = decodePayload,
        )
    }

    private fun word(high: Int, low: Int): Int = BYTE_RANGE * high + low

    private fun celsius(byte: Int): Double = (byte - TEMPERATURE_OFFSET).toDouble()

    private fun percentOfByte(byte: Int): Double = byte * PERCENT_FULL_SCALE / BYTE_MAX

    private fun trimPercent(byte: Int): Double = (byte - TRIM_ZERO_POINT) * PERCENT_FULL_SCALE / TRIM_ZERO_POINT
}
