package nl.stijnvdpol.klinedash.transport

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleUartTest {

    private fun uuid(text: String): UUID = UUID.fromString(text)

    private fun char(uuid: UUID, notify: Boolean = false, write: Boolean = false) =
        GattCharacteristicInfo(uuid, canNotify = notify, canWrite = write)

    private fun service(uuid: UUID, vararg chars: GattCharacteristicInfo) = GattServiceInfo(uuid, chars.toList())

    private val standardServices = listOf(
        service(uuid16(0x1800), char(uuid16(0x2A00), write = true)),
        service(uuid16(0x1801), char(uuid16(0x2A05), notify = true)),
        service(uuid16(0x180A), char(uuid16(0x2A29))),
    )

    private val fff0 = service(uuid16(0xFFF0), char(uuid16(0xFFF1), notify = true), char(uuid16(0xFFF2), write = true))

    private val vgateService = uuid("E7810A71-73AE-499D-8C15-FAA9AEF0C3F2")
    private val vgateChar = uuid("BEF8D6C9-9C21-4C9E-B632-BD58C1009F9F")

    @Test
    fun `fff0 layout of the konnwei kw905 is found and preferred`() {
        val candidates = uartCandidates(standardServices + fff0)
        assertEquals(1, candidates.size)
        assertEquals(UartCandidate(uuid16(0xFFF0), uuid16(0xFFF1), uuid16(0xFFF2), isKnownProfile = true), candidates[0])
    }

    @Test
    fun `vgate vlinker single characteristic is a known profile`() {
        val services = listOf(service(vgateService, char(vgateChar, notify = true, write = true)))
        assertEquals(
            listOf(UartCandidate(vgateService, vgateChar, vgateChar, isKnownProfile = true)),
            uartCandidates(services),
        )
    }

    @Test
    fun `ffe0 with split notify and write characteristics`() {
        val services = listOf(service(uuid16(0xFFE0), char(uuid16(0xFFE1), notify = true), char(uuid16(0xFFE2), write = true)))
        val candidates = uartCandidates(services)
        assertEquals(UartCandidate(uuid16(0xFFE0), uuid16(0xFFE1), uuid16(0xFFE2), true), candidates.first())
        assertEquals(1, candidates.size)
    }

    @Test
    fun `ffe0 hm10 style uses one characteristic for both`() {
        val services = listOf(service(uuid16(0xFFE0), char(uuid16(0xFFE1), notify = true, write = true)))
        assertEquals(
            listOf(UartCandidate(uuid16(0xFFE0), uuid16(0xFFE1), uuid16(0xFFE1), true)),
            uartCandidates(services),
        )
    }

    @Test
    fun `vgate icar pro layout 18f0`() {
        val services = listOf(service(uuid16(0x18F0), char(uuid16(0x2AF0), notify = true), char(uuid16(0x2AF1), write = true)))
        assertEquals(listOf(UartCandidate(uuid16(0x18F0), uuid16(0x2AF0), uuid16(0x2AF1), true)), uartCandidates(services))
    }

    @Test
    fun `known profiles keep their preference order`() {
        val nordic = service(
            uuid("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
            char(uuid("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"), notify = true),
            char(uuid("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"), write = true),
        )
        val vgate = service(vgateService, char(vgateChar, notify = true, write = true))
        val candidates = uartCandidates(listOf(nordic, vgate, fff0))
        assertEquals(listOf(uuid16(0xFFF0), vgateService, nordic.uuid), candidates.map { it.service })
    }

    @Test
    fun `a known service whose characteristics lack the right properties is not a known profile`() {
        // FFF1 cannot notify, so the FFF0 layout does not hold; the generic search sees no pair either.
        val broken = service(uuid16(0xFFF0), char(uuid16(0xFFF1), write = true), char(uuid16(0xFFF2), write = true))
        assertTrue(uartCandidates(listOf(broken)).isEmpty())
    }

    @Test
    fun `unknown vendor service is found by properties and is not a known profile`() {
        val kiwi = service(
            uuid("E47C8027-CCA1-4E3B-981F-BDC47ABEB5B5"),
            char(uuid("CACC07FF-FFFF-4C48-8FAE-A9EF71B75E26"), notify = true),
            char(uuid("1CCE1EA8-BD34-4813-A00A-C76E028FADCB"), write = true),
        )
        val candidate = uartCandidates(standardServices + kiwi).single()
        assertFalse(candidate.isKnownProfile)
        assertEquals(uuid("CACC07FF-FFFF-4C48-8FAE-A9EF71B75E26"), candidate.notify)
        assertEquals(uuid("1CCE1EA8-BD34-4813-A00A-C76E028FADCB"), candidate.write)
    }

    @Test
    fun `roles come from properties and not from the order of the characteristics`() {
        val reversed = service(uuid("0000AAAA-0000-1000-8000-00805F9B34FB"), char(uuid16(0xAAA2), write = true), char(uuid16(0xAAA1), notify = true))
        val candidate = uartCandidates(listOf(reversed)).single()
        assertEquals(uuid16(0xAAA1), candidate.notify)
        assertEquals(uuid16(0xAAA2), candidate.write)
    }

    @Test
    fun `standard and non uart vendor services are skipped by the generic search`() {
        val battery = service(uuid16(0x180F), char(uuid16(0x2A19), notify = true, write = true))
        val obdlinkInternal = service(uuid16(0xFEF5), char(uuid16(0xAA01), notify = true, write = true))
        val topdon = service(uuid("0783B03E-8535-B5A0-7140-A304D2495CB7"), char(uuid("0783B03E-8535-B5A0-7140-A304D2495CB8"), notify = true, write = true))
        val generic = service(uuid16(0x1800), char(uuid16(0x2A00), notify = true, write = true))
        assertTrue(uartCandidates(listOf(battery, obdlinkInternal, topdon, generic)).isEmpty())
    }

    @Test
    fun `generic services with a vendor short uuid are tried before other ones`() {
        val other = service(uuid("11111111-2222-3333-4444-555555555555"), char(uuid("11111111-2222-3333-4444-555555555556"), notify = true, write = true))
        val vendor = service(uuid16(0xFFB0), char(uuid16(0xFFB1), notify = true, write = true))
        val candidates = uartCandidates(listOf(other, vendor))
        assertEquals(listOf(uuid16(0xFFB0), other.uuid), candidates.map { it.service })
        assertTrue(candidates.none { it.isKnownProfile })
    }

    @Test
    fun `known only mode ignores generic pairs`() {
        val other = service(uuid("11111111-2222-3333-4444-555555555555"), char(uuid("11111111-2222-3333-4444-555555555556"), notify = true, write = true))
        assertTrue(uartCandidates(listOf(other), knownOnly = true).isEmpty())
        assertEquals(1, uartCandidates(listOf(other, fff0), knownOnly = true).size)
    }

    @Test
    fun `a pair that equals a known profile is listed once`() {
        // FFE0 service with FFE1 notify+write: the HM-10 profile; the generic search must not add it again.
        val services = listOf(service(uuid16(0xFFE0), char(uuid16(0xFFE1), notify = true, write = true)))
        assertEquals(1, uartCandidates(services).size)
    }

    @Test
    fun `device without any notify and write pair has no candidates`() {
        assertTrue(uartCandidates(standardServices).isEmpty())
        assertTrue(uartCandidates(emptyList()).isEmpty())
    }
}
