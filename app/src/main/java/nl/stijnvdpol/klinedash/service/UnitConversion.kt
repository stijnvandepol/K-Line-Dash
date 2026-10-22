package nl.stijnvdpol.klinedash.service

import nl.stijnvdpol.klinedash.core.obd.Signal
import nl.stijnvdpol.klinedash.data.UnitSystem

/*
 * The one place that knows how a metric value becomes an imperial one: constants, labels, decimals
 * and the way back. Everything the app stores, computes and exports is metric; a value is converted
 * only when it is shown, and converted back only when the user edits it (warning limits).
 */

private const val KM_PER_MILE = 1.609344
private const val LITERS_PER_US_GALLON = 3.785411784
private const val PSI_PER_KPA = 0.1450377377
private const val FAHRENHEIT_PER_CELSIUS = 1.8
private const val FAHRENHEIT_OFFSET = 32.0
private const val KM_PER_100_KM = 100.0

/** Quantities whose unit depends on the [UnitSystem]; RPM, %, V, g/s, degrees and seconds never change. */
enum class Quantity(val metricLabel: String, val imperialLabel: String, val extraImperialDecimals: Int = 0) {
    SPEED("km/h", "mph"),
    DISTANCE("km", "mi"),
    TEMPERATURE("°C", "°F"),

    /** An imperial pressure is a smaller number, so it gets one more decimal: 101 kPa is 14.7 psi. */
    PRESSURE("kPa", "psi", extraImperialDecimals = 1),
    FUEL_VOLUME("L", "gal", extraImperialDecimals = 1),
    FUEL_RATE("L/h", "gal/h", extraImperialDecimals = 1),

    /** Consumption is litres per 100 km in metric and miles per gallon in imperial: one is the inverse of the other. */
    FUEL_ECONOMY("L/100 km", "mpg"),
}

/** The unit text a [Quantity] carries in [this] system. */
fun UnitSystem.label(quantity: Quantity): String =
    if (this == UnitSystem.METRIC) quantity.metricLabel else quantity.imperialLabel

/** Digits after the decimal point for a value that has [metricDecimals] in metric. */
fun UnitSystem.decimals(quantity: Quantity, metricDecimals: Int): Int =
    if (this == UnitSystem.METRIC) metricDecimals else metricDecimals + quantity.extraImperialDecimals

/** [metric] as shown in [this] system. Linear for every quantity but [Quantity.FUEL_ECONOMY]. */
fun UnitSystem.fromMetric(quantity: Quantity, metric: Double): Double {
    if (this == UnitSystem.METRIC) return metric
    return when (quantity) {
        Quantity.SPEED, Quantity.DISTANCE -> metric / KM_PER_MILE
        Quantity.TEMPERATURE -> metric * FAHRENHEIT_PER_CELSIUS + FAHRENHEIT_OFFSET
        Quantity.PRESSURE -> metric * PSI_PER_KPA
        Quantity.FUEL_VOLUME, Quantity.FUEL_RATE -> metric / LITERS_PER_US_GALLON
        Quantity.FUEL_ECONOMY -> invertConsumption(metric)
    }
}

/** The inverse of [fromMetric]: what a value the user typed or stepped to in [this] system is in metric. */
fun UnitSystem.toMetric(quantity: Quantity, shown: Double): Double {
    if (this == UnitSystem.METRIC) return shown
    return when (quantity) {
        Quantity.SPEED, Quantity.DISTANCE -> shown * KM_PER_MILE
        Quantity.TEMPERATURE -> (shown - FAHRENHEIT_OFFSET) / FAHRENHEIT_PER_CELSIUS
        Quantity.PRESSURE -> shown / PSI_PER_KPA
        Quantity.FUEL_VOLUME, Quantity.FUEL_RATE -> shown * LITERS_PER_US_GALLON
        Quantity.FUEL_ECONOMY -> invertConsumption(shown)
    }
}

/** L/100 km to mpg and back (the same formula): zero stays zero, there is no consumption to invert. */
private fun invertConsumption(value: Double): Double =
    if (value <= 0.0) 0.0 else KM_PER_100_KM * LITERS_PER_US_GALLON / KM_PER_MILE / value

/** A metric [value] with [metricDecimals] as the text of [this] system; the one formatting path of every converted number. */
fun UnitSystem.format(quantity: Quantity, value: Double, metricDecimals: Int): String =
    formatNumber(fromMetric(quantity, value), decimals(quantity, metricDecimals))

/** Signal units (as the catalog spells them) that change with the unit system. */
private val SignalQuantities = mapOf(
    "km/h" to Quantity.SPEED,
    "km" to Quantity.DISTANCE,
    "°C" to Quantity.TEMPERATURE,
    "kPa" to Quantity.PRESSURE,
    "l/h" to Quantity.FUEL_RATE,
)

/** The quantity of this signal's unit, or null when the unit is the same everywhere (rpm, %, V, g/s). */
fun Signal.quantity(): Quantity? = SignalQuantities[unit]

/**
 * A [Signal] as shown in one unit system: its unit, display range and decimals converted, and the
 * conversion of a reading. The reading itself stays metric everywhere else.
 */
class SignalDisplay(signal: Signal, private val units: UnitSystem) {
    private val quantity: Quantity? = signal.quantity()

    val unit: String = quantity?.let(units::label) ?: signal.unit
    val min: Double = value(signal.min)
    val max: Double = value(signal.max)
    val decimals: Int = quantity?.let { units.decimals(it, signal.decimals) } ?: signal.decimals

    /** [metric] in the shown unit. */
    fun value(metric: Double): Double = quantity?.let { units.fromMetric(it, metric) } ?: metric

    fun format(metric: Double): String = formatNumber(value(metric), decimals)
}

fun Signal.display(units: UnitSystem): SignalDisplay = SignalDisplay(this, units)
