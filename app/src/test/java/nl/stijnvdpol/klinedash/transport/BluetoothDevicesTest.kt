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
    fun `a paired adapter is offered to pair again, an unpaired one is not`() {
        val message = classicConnectFailedMessage(isBonded = true, isAuthFailure = true)
        assertTrue(message.contains(PAIR_AGAIN_HINT))
        assertTrue(suggestsPairingAgain(message))
        // Android usually reports a stale key only as "read failed", so every paired adapter is offered it.
        assertTrue(suggestsPairingAgain(classicConnectFailedMessage(isBonded = true)))
        assertFalse(suggestsPairingAgain(classicConnectFailedMessage(isBonded = false, isAuthFailure = true)))
        assertFalse(suggestsPairingAgain(null))
    }

    @Test
    fun `authentication failures are recognised in the error or its causes`() {
        assertTrue(isAuthenticationFailure(java.io.IOException("Authentication failed")))
        assertTrue(isAuthenticationFailure(java.io.IOException("connect failed", java.io.IOException("PIN or Key Missing"))))
        assertFalse(isAuthenticationFailure(java.io.IOException("read failed, socket might closed or timeout, read ret: -1")))
        assertFalse(isAuthenticationFailure(java.io.IOException()))
        assertFalse(isAuthenticationFailure(null))
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

    @Test
    fun `the esp32 bridge looks like an obd adapter`() {
        assertTrue(nameLooksLikeObd("OBDII-Bridge"))
    }
}
