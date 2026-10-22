package nl.stijnvdpol.klinedash.service

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportAddressesTest {
    private class FixedRandom(private val value: Int) : Random() {
        override fun nextInt(bound: Int): Int = value
    }

    @Test
    fun pinIsFourDigitsWithLeadingZeros() {
        assertEquals("0042", generatePin(FixedRandom(42)))
        assertEquals("0000", generatePin(FixedRandom(0)))
        assertEquals("9999", generatePin(FixedRandom(9999)))
    }

    @Test
    fun everyGeneratedPinHasTheRightFormat() {
        val format = Regex("[0-9]{4}")
        repeat(2_000) { assertTrue(format.matches(generatePin())) }
    }

    @Test
    fun addressesAreFormattedWithThePort() {
        val formatted = formatAddresses(listOf(LocalAddress("wlan0", "192.168.43.50")), 8080)
        assertEquals(listOf("http://192.168.43.50:8080"), formatted)
    }

    @Test
    fun mobileDataTunnelsAndDuplicatesAreLeftOut() {
        val candidates = listOf(
            LocalAddress("rmnet_data0", "10.20.30.40"),
            LocalAddress("tun0", "10.8.0.2"),
            LocalAddress("wlan0", "192.168.1.20"),
            LocalAddress("wlan0", "192.168.1.20"),
            LocalAddress("ap0", "192.168.43.1"),
        )
        assertEquals(listOf("http://192.168.1.20:8081", "http://192.168.43.1:8081"), formatAddresses(candidates, 8081))
    }

    @Test
    fun noCandidatesGiveNoAddresses() {
        assertEquals(emptyList<String>(), formatAddresses(emptyList(), 8080))
    }
}
