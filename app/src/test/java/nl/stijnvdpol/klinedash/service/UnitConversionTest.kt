package nl.stijnvdpol.klinedash.service

import nl.stijnvdpol.klinedash.core.obd.Signals
import nl.stijnvdpol.klinedash.data.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitConversionTest {
    private val metric = UnitSystem.METRIC
    private val imperial = UnitSystem.IMPERIAL

    @Test
    fun `metric values pass through untouched`() {
        Quantity.entries.forEach { quantity ->
            assertEquals("$quantity", 123.456, metric.fromMetric(quantity, 123.456), 0.0)
            assertEquals("$quantity", 123.456, metric.toMetric(quantity, 123.456), 0.0)
        }
    }

    @Test
    fun `speed and distance convert with the international mile`() {
        assertEquals(62.1371, imperial.fromMetric(Quantity.SPEED, 100.0), 1e-4)
        assertEquals(1.0, imperial.fromMetric(Quantity.DISTANCE, 1.609344), 1e-9)
        assertEquals(160.9344, imperial.toMetric(Quantity.SPEED, 100.0), 1e-9)
    }

    @Test
    fun `temperature converts with the offset, not only the factor`() {
        assertEquals(32.0, imperial.fromMetric(Quantity.TEMPERATURE, 0.0), 1e-9)
        assertEquals(194.0, imperial.fromMetric(Quantity.TEMPERATURE, 90.0), 1e-9)
        assertEquals(-40.0, imperial.fromMetric(Quantity.TEMPERATURE, -40.0), 1e-9)
        assertEquals(100.0, imperial.toMetric(Quantity.TEMPERATURE, 212.0), 1e-9)
    }

    @Test
    fun `pressure and fuel use psi and US gallons`() {
        assertEquals(14.696, imperial.fromMetric(Quantity.PRESSURE, 101.325), 1e-3)
        assertEquals(2.6417, imperial.fromMetric(Quantity.FUEL_VOLUME, 10.0), 1e-4)
        assertEquals(0.2642, imperial.fromMetric(Quantity.FUEL_RATE, 1.0), 1e-4)
    }

    @Test
    fun `consumption turns into miles per gallon and back`() {
        assertEquals(29.4, imperial.fromMetric(Quantity.FUEL_ECONOMY, 8.0), 0.01)
        assertEquals(235.215, imperial.fromMetric(Quantity.FUEL_ECONOMY, 1.0), 0.001)
        assertEquals(8.0, imperial.toMetric(Quantity.FUEL_ECONOMY, imperial.fromMetric(Quantity.FUEL_ECONOMY, 8.0)), 1e-9)
    }

    @Test
    fun `no consumption stays zero instead of becoming infinite`() {
        assertEquals(0.0, imperial.fromMetric(Quantity.FUEL_ECONOMY, 0.0), 0.0)
    }

    @Test
    fun `every quantity survives a round trip`() {
        Quantity.entries.forEach { quantity ->
            listOf(0.5, 7.0, 93.0, 250.0).forEach { value ->
                val shown = imperial.fromMetric(quantity, value)
                assertEquals("$quantity $value", value, imperial.toMetric(quantity, shown), 1e-9)
            }
        }
    }

    @Test
    fun `labels name the unit of the system`() {
        assertEquals(listOf("km/h", "km", "°C", "kPa", "L", "L/h", "L/100 km"), Quantity.entries.map { metric.label(it) })
        assertEquals(listOf("mph", "mi", "°F", "psi", "gal", "gal/h", "mpg"), Quantity.entries.map { imperial.label(it) })
    }

    @Test
    fun `smaller imperial numbers get a decimal more`() {
        assertEquals(0, metric.decimals(Quantity.PRESSURE, 0))
        assertEquals(1, imperial.decimals(Quantity.PRESSURE, 0))
        assertEquals(2, imperial.decimals(Quantity.FUEL_RATE, 1))
        assertEquals(0, imperial.decimals(Quantity.SPEED, 0))
        assertEquals(1, imperial.decimals(Quantity.FUEL_ECONOMY, 1))
    }

    @Test
    fun `format is the one path from a metric value to text`() {
        assertEquals("100", metric.format(Quantity.SPEED, 100.0, 0))
        assertEquals("62", imperial.format(Quantity.SPEED, 100.0, 0))
        assertEquals("194", imperial.format(Quantity.TEMPERATURE, 90.0, 0))
        assertEquals("14.6", imperial.format(Quantity.PRESSURE, 101.0, 0))
        assertEquals("0.26", imperial.format(Quantity.FUEL_RATE, 1.0, 1))
    }

    @Test
    fun `a signal knows its quantity from its unit`() {
        assertEquals(Quantity.SPEED, Signals.byId("speed")?.quantity())
        assertEquals(Quantity.TEMPERATURE, Signals.byId("coolant")?.quantity())
        assertEquals(Quantity.TEMPERATURE, Signals.byId("oil_temp")?.quantity())
        assertEquals(Quantity.PRESSURE, Signals.byId("map")?.quantity())
        assertEquals(Quantity.FUEL_RATE, Signals.byId("fuel_rate")?.quantity())
        assertEquals(Quantity.DISTANCE, Signals.byId("distance_mil")?.quantity())
    }

    @Test
    fun `rpm, percentages, volts, airflow and degrees never change`() {
        listOf("rpm", "throttle", "load", "battery", "maf", "timing", "stft_b1", "run_time").forEach { id ->
            val signal = checkNotNull(Signals.byId(id))
            assertNull(id, signal.quantity())
            val metricDisplay = signal.display(metric)
            val imperialDisplay = signal.display(imperial)
            assertEquals(id, signal.unit, imperialDisplay.unit)
            assertEquals(id, metricDisplay.min, imperialDisplay.min, 0.0)
            assertEquals(id, metricDisplay.max, imperialDisplay.max, 0.0)
            assertEquals(id, metricDisplay.decimals, imperialDisplay.decimals)
            assertEquals(id, metricDisplay.format(13.94), imperialDisplay.format(13.94))
        }
    }

    @Test
    fun `a temperature signal shows its range and readings in Fahrenheit`() {
        val coolant = checkNotNull(Signals.byId("coolant")).display(imperial)
        assertEquals("°F", coolant.unit)
        assertEquals(104.0, coolant.min, 1e-9)
        assertEquals(266.0, coolant.max, 1e-9)
        assertEquals(0, coolant.decimals)
        assertEquals("190", coolant.format(88.0))
    }

    @Test
    fun `a speed signal shows mph and the fuel rate is spelled with a capital L in metric`() {
        val speed = checkNotNull(Signals.byId("speed")).display(imperial)
        assertEquals("mph", speed.unit)
        assertEquals(149.13, speed.max, 0.01)
        assertEquals("L/h", checkNotNull(Signals.byId("fuel_rate")).display(metric).unit)
        assertEquals("gal/h", checkNotNull(Signals.byId("fuel_rate")).display(imperial).unit)
    }

    @Test
    fun `a pressure signal gets the extra decimal in psi`() {
        val map = checkNotNull(Signals.byId("map")).display(imperial)
        assertEquals("psi", map.unit)
        assertEquals(1, map.decimals)
        assertEquals("14.6", map.format(101.0))
        assertTrue(map.max > map.min)
    }
}
