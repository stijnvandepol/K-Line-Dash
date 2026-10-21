package nl.stijnvdpol.klinedash.core.obd

import nl.stijnvdpol.klinedash.core.elm.ElmResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalsTest {

    private val delta = 1e-9

    private fun data(vararg lines: String) = ElmResult.Data(lines.toList(), lines.joinToString("\r"))

    private fun decode(id: String, vararg lines: String): Double? {
        val signal = Signals.byId(id) ?: error("unknown signal $id")
        return signal.decode(data(*lines))
    }

    private fun assertDecodes(expected: Double, id: String, reply: String) {
        val value = decode(id, reply)
        assertNotNull("$id from $reply", value)
        assertEquals("$id from $reply", expected, value!!, delta)
    }

    // ---- catalog ----

    @Test
    fun `ids are unique and byId finds every signal`() {
        val ids = Signals.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (signal in Signals.all) assertSame(signal, Signals.byId(signal.id))
    }

    @Test
    fun `byId is null for an unknown id`() {
        assertNull(Signals.byId("does_not_exist"))
    }

    @Test
    fun `every pid signal sends mode 01 and its pid as two hex digits`() {
        val pidSignals = Signals.all.filter { it.pid != null }
        assertTrue(pidSignals.isNotEmpty())
        for (signal in pidSignals) assertEquals(signal.id, "01%02X".format(signal.pid), signal.command)
    }

    @Test
    fun `the only signal without a pid is the adapter battery reading`() {
        val adapterSignals = Signals.all.filter { it.pid == null }
        assertEquals(listOf("battery"), adapterSignals.map { it.id })
        assertEquals("ATRV", adapterSignals.single().command)
    }

    @Test
    fun `display ranges and names are filled in`() {
        for (signal in Signals.all) {
            assertTrue("${signal.id} range", signal.min < signal.max)
            assertTrue("${signal.id} names", signal.name.isNotBlank() && signal.shortName.isNotBlank())
            assertTrue("${signal.id} decimals", signal.decimals >= 0)
        }
    }

    @Test
    fun `default tiers follow the polling budget`() {
        val fast = setOf("rpm", "speed", "throttle")
        val medium = setOf("coolant", "intake_temp", "load", "timing", "maf")
        for (signal in Signals.all) {
            val expected = when (signal.id) {
                in fast -> PollTier.FAST
                in medium -> PollTier.MEDIUM
                else -> PollTier.SLOW
            }
            assertEquals(signal.id, expected, signal.defaultTier)
        }
        assertEquals(fast, Signals.all.filter { it.defaultTier == PollTier.FAST }.map { it.id }.toSet())
        assertEquals(medium, Signals.all.filter { it.defaultTier == PollTier.MEDIUM }.map { it.id }.toSet())
    }

    @Test
    fun `signals of one pid share a command`() {
        assertEquals(Signals.byId("mil_on")!!.command, Signals.byId("dtc_count")!!.command)
        assertEquals(Signals.byId("o2_b1s1_v")!!.command, Signals.byId("o2_b1s1_trim")!!.command)
    }

    // ---- decoding against J1979 formulas ----

    @Test
    fun `rpm is a quarter of the 16 bit value`() {
        assertDecodes(1726.0, "rpm", "410C1AF8")
        assertDecodes(0.0, "rpm", "410C0000")
        assertDecodes(0.25, "rpm", "410C0001")
        assertDecodes(16383.75, "rpm", "410CFFFF")
    }

    @Test
    fun `speed is the byte in km per hour`() {
        assertDecodes(50.0, "speed", "410D32")
        assertDecodes(0.0, "speed", "410D00")
        assertDecodes(255.0, "speed", "410DFF")
    }

    @Test
    fun `throttle load and fuel level scale a byte to percent`() {
        for ((id, pid) in listOf("throttle" to "11", "load" to "04", "fuel_level" to "2F")) {
            assertDecodes(0.0, id, "41${pid}00")
            assertDecodes(100.0, id, "41${pid}FF")
            assertDecodes(128 * 100.0 / 255, id, "41${pid}80")
        }
    }

    @Test
    fun `temperatures are the byte minus 40`() {
        for ((id, pid) in listOf("coolant" to "05", "intake_temp" to "0F", "ambient_temp" to "46", "oil_temp" to "5C")) {
            assertDecodes(-40.0, id, "41${pid}00")
            assertDecodes(30.0, id, "41${pid}46")
            assertDecodes(215.0, id, "41${pid}FF")
        }
    }

    @Test
    fun `timing advance is half the byte minus 64`() {
        assertDecodes(-64.0, "timing", "410E00")
        assertDecodes(0.0, "timing", "410E80")
        assertDecodes(12.5, "timing", "410E99")
        assertDecodes(63.5, "timing", "410EFF")
    }

    @Test
    fun `maf is the 16 bit value in hundredths of gram per second`() {
        assertDecodes(0.0, "maf", "41100000")
        assertDecodes(2.56, "maf", "41100100")
        assertDecodes(655.35, "maf", "4110FFFF")
    }

    @Test
    fun `map is the byte in kilopascal and fuel pressure is three times the byte`() {
        assertDecodes(100.0, "map", "410B64")
        assertDecodes(255.0, "map", "410BFF")
        assertDecodes(0.0, "fuel_pressure", "410A00")
        assertDecodes(300.0, "fuel_pressure", "410A64")
        assertDecodes(765.0, "fuel_pressure", "410AFF")
    }

    @Test
    fun `fuel trims of both banks are centred on 128`() {
        for ((id, pid) in listOf("stft_b1" to "06", "ltft_b1" to "07", "stft_b2" to "08", "ltft_b2" to "09")) {
            assertDecodes(0.0, id, "41${pid}80")
            assertDecodes(-100.0, id, "41${pid}00")
            assertDecodes(127 * 100.0 / 128, id, "41${pid}FF")
            assertDecodes(-25.0, id, "41${pid}60")
        }
    }

    @Test
    fun `oxygen sensor voltage is the first byte over 200`() {
        assertDecodes(0.9, "o2_b1s1_v", "4114B4FF")
        assertDecodes(0.0, "o2_b1s1_v", "41140000")
        assertDecodes(1.275, "o2_b1s1_v", "4114FFFF")
    }

    @Test
    fun `oxygen sensor trim comes from the second byte and is null when unused`() {
        assertDecodes(0.0, "o2_b1s1_trim", "4114B480")
        assertDecodes(-100.0, "o2_b1s1_trim", "4114B400")
        assertDecodes(126 * 100.0 / 128, "o2_b1s1_trim", "4114B4FE")
        assertNull(decode("o2_b1s1_trim", "4114B4FF"))
    }

    @Test
    fun `oxygen sensor voltage survives a reply that only carries the voltage byte`() {
        assertDecodes(0.9, "o2_b1s1_v", "4114B4")
        assertNull(decode("o2_b1s1_trim", "4114B4"))
    }

    @Test
    fun `all eight oxygen sensor positions map to pids 14 to 1B`() {
        val positions = listOf("b1s1", "b1s2", "b1s3", "b1s4", "b2s1", "b2s2", "b2s3", "b2s4")
        positions.forEachIndexed { index, position ->
            val reply = "41%02XB440".format(0x14 + index)
            assertDecodes(0.9, "o2_${position}_v", reply)
            assertDecodes(-50.0, "o2_${position}_trim", reply)
            assertEquals("01%02X".format(0x14 + index), Signals.byId("o2_${position}_v")!!.command)
        }
    }

    @Test
    fun `monitor status gives the lamp and the code count from byte A`() {
        assertDecodes(1.0, "mil_on", "410183000000")
        assertDecodes(3.0, "dtc_count", "410183000000")
        assertDecodes(0.0, "mil_on", "410103000000")
        assertDecodes(3.0, "dtc_count", "410103000000")
        assertDecodes(0.0, "mil_on", "410100000000")
        assertDecodes(0.0, "dtc_count", "410100000000")
        assertDecodes(1.0, "mil_on", "4101FF000000")
        assertDecodes(127.0, "dtc_count", "4101FF000000")
    }

    @Test
    fun `run time and distance with mil are plain 16 bit values`() {
        assertDecodes(258.0, "run_time", "411F0102")
        assertDecodes(0.0, "run_time", "411F0000")
        assertDecodes(65535.0, "run_time", "411FFFFF")
        assertDecodes(255.0, "distance_mil", "412100FF")
        assertDecodes(65535.0, "distance_mil", "4121FFFF")
    }

    @Test
    fun `barometric pressure is the byte in kilopascal`() {
        assertDecodes(101.0, "baro", "413365")
    }

    @Test
    fun `control module voltage is millivolts`() {
        assertDecodes(14.5, "ecu_voltage", "414238A4")
        assertDecodes(0.0, "ecu_voltage", "41420000")
        assertDecodes(65.535, "ecu_voltage", "4142FFFF")
    }

    @Test
    fun `fuel rate is twentieths of liter per hour`() {
        assertDecodes(5.0, "fuel_rate", "415E0064")
        assertDecodes(0.0, "fuel_rate", "415E0000")
        assertDecodes(3276.75, "fuel_rate", "415EFFFF")
    }

    @Test
    fun `battery reads the first adapter line`() {
        assertEquals(12.4, decode("battery", "12.4V")!!, delta)
        assertEquals(13.9, decode("battery", "13.9", "ignored")!!, delta)
        assertNull(decode("battery", "?"))
        assertNull(Signals.byId("battery")!!.decode(ElmResult.Data(emptyList(), "")))
    }

    // ---- foreign and short replies ----

    @Test
    fun `a line for another pid decodes to null for every pid signal`() {
        for (signal in Signals.all.filter { it.pid != null }) {
            val otherPid = (signal.pid!! + 1) % 0x100
            assertNull(signal.id, signal.decode(data("41%02XFFFFFFFF".format(otherPid))))
        }
    }

    @Test
    fun `a reply of another service decodes to null for every pid signal`() {
        for (signal in Signals.all.filter { it.pid != null }) {
            assertNull(signal.id, signal.decode(data("42%02XFFFFFFFF".format(signal.pid))))
        }
    }

    @Test
    fun `a reply without data bytes decodes to null for every pid signal`() {
        for (signal in Signals.all.filter { it.pid != null }) {
            assertNull(signal.id, signal.decode(data("41%02X".format(signal.pid))))
        }
    }

    @Test
    fun `two byte signals reject a one byte reply`() {
        assertNull(decode("rpm", "410C1A"))
        assertNull(decode("maf", "411001"))
        assertNull(decode("ecu_voltage", "414238"))
    }

    @Test
    fun `the matching line is found among several ecu lines`() {
        assertEquals(50.0, decode("speed", "410C1AF8", "410D32")!!, delta)
    }
}
