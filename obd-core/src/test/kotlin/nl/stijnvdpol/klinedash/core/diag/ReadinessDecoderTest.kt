package nl.stijnvdpol.klinedash.core.diag

import nl.stijnvdpol.klinedash.core.diag.MonitorStatus.NOT_APPLICABLE
import nl.stijnvdpol.klinedash.core.diag.MonitorStatus.NOT_READY
import nl.stijnvdpol.klinedash.core.diag.MonitorStatus.READY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessDecoderTest {

    private fun decode(vararg bytes: Int): EngineStatus = requireNotNull(ReadinessDecoder.decode(bytes))

    private fun EngineStatus.statusOf(id: String): MonitorStatus =
        monitors.first { it.id == id }.status

    @Test
    fun `simulator default two codes mil on evap pending`() {
        // A=0x82: MIL + 2 codes, B=0x07 continuous available and complete, C=0x65, D=0x04.
        val status = decode(0x82, 0x07, 0x65, 0x04)

        assertTrue(status.isMilOn)
        assertEquals(2, status.dtcCount)
        assertTrue(status.isSparkIgnition)
        assertEquals(
            mapOf(
                "misfire" to READY, "fuel_system" to READY, "components" to READY,
                "catalyst" to READY, "heated_catalyst" to NOT_APPLICABLE, "evap" to NOT_READY,
                "secondary_air" to NOT_APPLICABLE, "ac_refrigerant" to NOT_APPLICABLE,
                "o2_sensor" to READY, "o2_heater" to READY, "egr" to NOT_APPLICABLE,
            ),
            status.monitors.associate { it.id to it.status },
        )
    }

    @Test
    fun `monitors keep their order continuous first`() {
        assertEquals(
            listOf(
                "misfire", "fuel_system", "components", "catalyst", "heated_catalyst", "evap",
                "secondary_air", "ac_refrigerant", "o2_sensor", "o2_heater", "egr",
            ),
            decode(0, 0, 0, 0).monitors.map { it.id },
        )
    }

    @Test
    fun `right after clearing everything the ecu has is not ready and the mil is off`() {
        val status = decode(0x00, 0x77, 0x65, 0x65)

        assertFalse(status.isMilOn)
        assertEquals(0, status.dtcCount)
        for (id in listOf("misfire", "fuel_system", "components", "catalyst", "evap", "o2_sensor", "o2_heater")) {
            assertEquals(id, NOT_READY, status.statusOf(id))
        }
        assertEquals(NOT_APPLICABLE, status.statusOf("egr"))
    }

    @Test
    fun `mil bit and count are separate`() {
        val status = decode(0xFF, 0x00, 0x00, 0x00)
        assertTrue(status.isMilOn)
        assertEquals(127, status.dtcCount)
        assertFalse(decode(0x7F, 0, 0, 0).isMilOn)
        assertEquals(127, decode(0x7F, 0, 0, 0).dtcCount)
    }

    @Test
    fun `incomplete bit of a monitor that is not available stays not applicable`() {
        // B: misfire "incomplete" (bit 4) but not available (bit 0 clear); D: egr incomplete, C: not available.
        val status = decode(0x00, 0x10, 0x00, 0x80)
        assertEquals(NOT_APPLICABLE, status.statusOf("misfire"))
        assertEquals(NOT_APPLICABLE, status.statusOf("egr"))
    }

    @Test
    fun `each spark monitor has its own bit`() {
        val ids = listOf(
            "catalyst", "heated_catalyst", "evap", "secondary_air", "ac_refrigerant", "o2_sensor", "o2_heater", "egr",
        )
        for ((bit, id) in ids.withIndex()) {
            val available = decode(0, 0, 1 shl bit, 0)
            assertEquals(id, READY, available.statusOf(id))
            assertEquals(id, 1, available.monitors.count { it.status == READY })

            val incomplete = decode(0, 0, 1 shl bit, 1 shl bit)
            assertEquals(id, NOT_READY, incomplete.statusOf(id))
        }
    }

    @Test
    fun `compression ignition uses its own monitor set`() {
        // B bit 3 set. C: nox_scr (bit 1) and exhaust_gas_sensor (bit 5); D: exhaust gas sensor incomplete.
        val status = decode(0x00, 0x08, 0x22, 0x20)

        assertFalse(status.isSparkIgnition)
        assertEquals(
            listOf(
                "misfire", "fuel_system", "components",
                "nmhc_catalyst", "nox_scr", "boost_pressure", "exhaust_gas_sensor", "pm_filter", "egr_vvt",
            ),
            status.monitors.map { it.id },
        )
        assertEquals(READY, status.statusOf("nox_scr"))
        assertEquals(NOT_READY, status.statusOf("exhaust_gas_sensor"))
        assertEquals(NOT_APPLICABLE, status.statusOf("pm_filter"))
    }

    @Test
    fun `fewer than four bytes cannot be decoded`() {
        assertNull(ReadinessDecoder.decode(intArrayOf(0x82, 0x07, 0x65)))
        assertNull(ReadinessDecoder.decode(IntArray(0)))
    }
}
