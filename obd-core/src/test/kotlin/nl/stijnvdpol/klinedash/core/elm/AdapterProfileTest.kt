package nl.stijnvdpol.klinedash.core.elm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdapterProfileTest {

    private fun classify(firmware: String?, stnId: String? = null, unsupported: Set<String> = emptySet()) =
        AdapterProfile.classify(firmware, stnId, unsupported)

    @Test
    fun `an sti answer from an stn chip wins over whatever ati says`() {
        assertEquals(ChipFamily.STN, classify("ELM327 v1.5", stnId = "STN1110 v4.2.0"))
        assertEquals(ChipFamily.STN, classify("ELM327 v1.3a", stnId = "OBDLink MX+ r1.0"))
    }

    @Test
    fun `a sti answer that is not an stn identity is ignored`() {
        assertEquals(ChipFamily.CLONE, classify("ELM327 v1.5", stnId = "BANANA"))
    }

    @Test
    fun `versions that never shipped are clones`() {
        assertEquals(ChipFamily.CLONE, classify("ELM327 v1.5"))
        assertEquals(ChipFamily.CLONE, classify("ELM327 v2.1"))
    }

    @Test
    fun `a real looking version that passes every setup command is genuine`() {
        for (version in listOf("ELM327 v1.3a", "ELM327 v1.4b", "ELM327 v2.2", "ELM327 v2.3")) {
            assertEquals(version, ChipFamily.GENUINE_ELM, classify(version))
        }
    }

    @Test
    fun `rejecting a command a real chip accepts makes it a clone whatever it prints`() {
        assertEquals(ChipFamily.CLONE, classify("ELM327 v1.4b", unsupported = setOf("ATAT1")))
        assertEquals(ChipFamily.CLONE, classify("ELM327 v2.2", unsupported = setOf("AT@1")))
    }

    @Test
    fun `an unrelated rejected command does not matter`() {
        assertEquals(ChipFamily.GENUINE_ELM, classify("ELM327 v1.4b", unsupported = setOf("ATLP", "ATATX")))
    }

    @Test
    fun `no usable ati answer leaves the family unknown`() {
        assertEquals(ChipFamily.UNKNOWN, classify(null))
        assertEquals(ChipFamily.UNKNOWN, classify("OBDII Scanner"))
        assertEquals(ChipFamily.UNKNOWN, classify("ELM327 v9.9"))
    }

    @Test
    fun `isCan covers protocols 6 to 9 in both spellings and nothing else`() {
        fun isCan(protocol: String) = AdapterInfo("ELM327 v1.5", protocol, "x", null, false, emptySet()).isCan
        for (protocol in listOf("6", "7", "8", "9", "A6", "A9")) assertTrue(protocol, isCan(protocol))
        for (protocol in listOf("0", "1", "2", "3", "4", "5", "A", "T", "A3", "")) assertFalse(protocol, isCan(protocol))
    }
}
