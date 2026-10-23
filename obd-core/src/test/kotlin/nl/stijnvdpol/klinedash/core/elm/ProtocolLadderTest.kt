package nl.stijnvdpol.klinedash.core.elm

import nl.stijnvdpol.klinedash.core.car.CarMake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolLadderTest {

    private val toyota = Elm327Session.TOYOTA_PROFILE
    private val everyProtocol = (1..9).map { it.toString() }
    private val allMakes: List<CarMake?> = CarMake.entries + listOf<CarMake?>(null)

    @Test
    fun `unknown make keeps the old k-line order and appends can then j1850`() {
        assertEquals(
            listOf("3", "5", "4", toyota, "6", "7", "8", "9", "1", "2"),
            fallbackOrder(null),
        )
    }

    @Test
    fun `german japanese and korean makes try 3 4 5 then can then j1850`() {
        val plain = listOf("3", "4", "5", "6", "7", "8", "9", "1", "2")
        for (make in listOf(
            CarMake.BMW_MINI, CarMake.MERCEDES_SMART, CarMake.HONDA_ACURA, CarMake.NISSAN_INFINITI,
            CarMake.MITSUBISHI, CarMake.SUBARU, CarMake.HYUNDAI_KIA,
        )) {
            assertEquals(make.name, plain, fallbackOrder(make))
        }
    }

    @Test
    fun `vag never tries j1850`() {
        assertEquals(listOf("3", "4", "5", "6", "7", "8", "9"), fallbackOrder(CarMake.VW_GROUP))
    }

    @Test
    fun `toyota adds its profile after the plain k-line rungs`() {
        assertEquals(
            listOf("3", "4", "5", toyota, "6", "7", "8", "9", "1", "2"),
            fallbackOrder(CarMake.TOYOTA_LEXUS),
        )
    }

    @Test
    fun `french opel and suzuki cars try fast init first`() {
        for (make in listOf(CarMake.RENAULT_DACIA, CarMake.PSA, CarMake.OPEL_VAUXHALL, CarMake.SUZUKI)) {
            assertEquals(make.name, listOf("5", "4", "3", "6", "7", "8", "9", "1", "2"), fallbackOrder(make))
        }
    }

    @Test
    fun `mazda tries can 11-bit first and fiat and volvo try 29-bit first`() {
        assertEquals(listOf("6", "3", "4", "5", "7", "8", "9", "1", "2"), fallbackOrder(CarMake.MAZDA))
        assertEquals(listOf("7", "6", "5", "4", "3", "8", "9", "1", "2"), fallbackOrder(CarMake.FIAT_ALFA_LANCIA))
        assertEquals(listOf("7", "3", "4", "5", "6", "8", "9", "1", "2"), fallbackOrder(CarMake.VOLVO))
    }

    @Test
    fun `ford tries can then pwm then k-line`() {
        assertEquals(listOf("6", "1", "3", "5", "4", "7", "8", "9", "2"), fallbackOrder(CarMake.FORD))
    }

    @Test
    fun `every order lists a protocol at most once and only toyota and unknown get the toyota profile`() {
        for (make in allMakes) {
            val order = fallbackOrder(make)
            assertEquals("$make has duplicates", order.size, order.toSet().size)
            assertTrue("$make has an unknown rung", order.all { it == toyota || it in everyProtocol })
            assertEquals("$make", make == null || make == CarMake.TOYOTA_LEXUS, toyota in order)
        }
    }

    @Test
    fun `every make reaches all nine protocols except vag which has no j1850`() {
        for (make in allMakes) {
            val reached = fallbackOrder(make).filter { it in everyProtocol }.toSet()
            val expected = if (make == CarMake.VW_GROUP) everyProtocol - setOf("1", "2") else everyProtocol
            assertEquals("$make", expected.toSet(), reached)
        }
    }

    @Test
    fun `k-line rungs get the long timeout and pause and bus rungs the short ones`() {
        for (rung in listOf("3", "4", "5", toyota)) {
            assertTrue(isKLineRung(rung))
            assertEquals(10_000L, rungTimeoutMs(rung))
            assertEquals(1_500L, pauseBeforeRungMs(rung))
        }
        for (rung in listOf("1", "2", "6", "7", "8", "9")) {
            assertFalse(isKLineRung(rung))
            assertEquals(6_000L, rungTimeoutMs(rung))
            assertEquals(300L, pauseBeforeRungMs(rung))
        }
    }

    @Test
    fun `labels say the protocol in plain words`() {
        assertEquals("ISO 9141-2", protocolLabel("3"))
        assertEquals("KWP2000 (5-baud init)", protocolLabel("4"))
        assertEquals("KWP2000 (fast init)", protocolLabel("5"))
        assertEquals("CAN 11-bit 500 kbaud", protocolLabel("6"))
        assertEquals("CAN 29-bit 500 kbaud", protocolLabel("7"))
        assertEquals("CAN 11-bit 250 kbaud", protocolLabel("8"))
        assertEquals("CAN 29-bit 250 kbaud", protocolLabel("9"))
        assertEquals("J1850 PWM", protocolLabel("1"))
        assertEquals("J1850 VPW", protocolLabel("2"))
        assertEquals("Toyota K-line profile", protocolLabel(toyota))
    }
}
