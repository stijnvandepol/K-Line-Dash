package nl.stijnvdpol.klinedash.core.export

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkAddressTest {
    private fun address(literal: String): InetAddress = InetAddress.getByName(literal)

    @Test
    fun `private Wi-Fi and hotspot ranges are local`() {
        listOf("192.168.43.20", "192.168.1.1", "10.0.0.5", "172.16.0.1", "172.31.255.254").forEach {
            assertTrue(it, isLocalNetworkAddress(address(it)))
        }
    }

    @Test
    fun `loopback is local`() {
        assertTrue(isLocalNetworkAddress(address("127.0.0.1")))
    }

    @Test
    fun `public and carrier-grade NAT addresses are not local`() {
        listOf("8.8.8.8", "100.64.1.1", "172.32.0.1", "11.0.0.1", "169.254.10.10").forEach {
            assertFalse(it, isLocalNetworkAddress(address(it)))
        }
    }

    @Test
    fun `IPv6 addresses are not local, the server listens on IPv4 only`() {
        listOf("2a02:a46f:1234::1", "fe80::1", "fd00::1", "::1").forEach {
            assertFalse(it, isLocalNetworkAddress(address(it)))
        }
    }
}
