package nl.stijnvdpol.klinedash.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothDevicesTest {

    @Test
    fun `failure message names the pin hints and the button hint for an unpaired adapter`() {
        val message = classicConnectFailedMessage(isBonded = false)
        assertTrue(message.contains("Pair the adapter first (PIN 1234, 0000 or 6789)"))
        assertTrue(message.contains("Press the adapter's button if it has one"))
        assertTrue(message.contains("ignition"))
    }

    @Test
    fun `failure message leaves out the pairing hint for a paired adapter`() {
        val message = classicConnectFailedMessage(isBonded = true)
        assertFalse(message.contains("Pair the adapter first"))
        assertTrue(message.contains("Press the adapter's button"))
    }

    @Test
    fun `app locked dongles are recognised by name`() {
        for (name in listOf("BlueDriver", "TOPDON_1A2B3C", "ThinkDiag-12345", "Golo-X431", "ANCEL BD200", "MaxiAP200")) {
            assertTrue(name, isKnownAppLocked(name))
        }
    }

    @Test
    fun `open adapters and unverified ones are not flagged`() {
        for (name in listOf("OBDII", "V-LINK", "vLinker MC-Android", "KONNWEI", "VEEPEAK", "FIXD", "Carista", "")) {
            assertFalse(name, isKnownAppLocked(name))
        }
        assertFalse(isKnownAppLocked(null))
    }

    @Test
    fun `newly listed adapter names look like obd`() {
        for (name in listOf("LELink2", "Kiwi 3", "V-checker", "OBDBLE")) assertTrue(name, nameLooksLikeObd(name))
    }
}
