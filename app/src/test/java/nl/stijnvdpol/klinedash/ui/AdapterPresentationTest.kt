package nl.stijnvdpol.klinedash.ui

import nl.stijnvdpol.klinedash.core.elm.AdapterInfo
import nl.stijnvdpol.klinedash.core.elm.AdapterProfile
import nl.stijnvdpol.klinedash.core.elm.ChipFamily
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.core.elm.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdapterPresentationTest {
    private val words = AdapterWords(
        yes = "Yes", no = "No", none = "None",
        chipGenuine = "ELM327", chipStn = "STN", chipClone = "ELM327 clone",
        timingAt1 = "AT1", timingAt2 = "AT2",
    )

    private fun info(
        protocolNumber: String = "6",
        profile: AdapterProfile = AdapterProfile(),
        unsupported: Set<String> = emptySet(),
        usedSavedProtocol: Boolean = false,
    ) = AdapterInfo(
        elmVersion = "ELM327 v1.5",
        protocolNumber = protocolNumber,
        protocolName = "ISO 15765-4 (CAN 11/500)",
        voltage = 12.4,
        usedSavedProtocol = usedSavedProtocol,
        unsupportedCommands = unsupported,
        profile = profile,
    )

    // --- Debug > Adapter ----------------------------------------------------------------------

    @Test
    fun `every field is empty while not connected`() {
        val values = adapterValues(null, words)
        assertEquals(AdapterField.entries.toSet(), values.keys)
        assertTrue(values.values.all { it == null })
    }

    @Test
    fun `an STN adapter on CAN with fast timing shows its chip, firmware and AT2`() {
        val profile = AdapterProfile(ChipFamily.STN, firmware = "ELM327 v1.4b", stnId = "STN1110 v4.2.0", usesAdaptiveTiming2 = true)
        val values = adapterValues(info(profile = profile), words)

        assertEquals("STN", values[AdapterField.CHIP])
        assertEquals("ELM327 v1.4b", values[AdapterField.FIRMWARE])
        assertEquals("Yes", values[AdapterField.CAN])
        assertEquals("AT2", values[AdapterField.ADAPTIVE_TIMING])
    }

    @Test
    fun `a clone on K-line shows AT1 and CAN no`() {
        val profile = AdapterProfile(ChipFamily.CLONE, firmware = "ELM327 v1.5")
        val values = adapterValues(info(protocolNumber = "3", profile = profile), words)

        assertEquals("ELM327 clone", values[AdapterField.CHIP])
        assertEquals("No", values[AdapterField.CAN])
        assertEquals("AT1", values[AdapterField.ADAPTIVE_TIMING])
    }

    @Test
    fun `a genuine chip is named ELM327`() {
        val values = adapterValues(info(profile = AdapterProfile(ChipFamily.GENUINE_ELM)), words)
        assertEquals("ELM327", values[AdapterField.CHIP])
    }

    @Test
    fun `an unknown chip and a missing firmware stay empty while connected`() {
        val values = adapterValues(info(), words)

        assertNull(values[AdapterField.CHIP])
        assertNull(values[AdapterField.FIRMWARE])
        assertEquals("Yes", values[AdapterField.CAN])
    }

    @Test
    fun `protocol, saved protocol and unsupported commands keep their old wording`() {
        val connected = info(unsupported = setOf("ATAT1", "AT@1"), usedSavedProtocol = true)
        val values = adapterValues(connected, words)

        assertEquals("ISO 15765-4 (CAN 11/500) (6)", values[AdapterField.PROTOCOL])
        assertEquals("Yes", values[AdapterField.SAVED_PROTOCOL])
        assertEquals("AT@1, ATAT1", values[AdapterField.UNSUPPORTED_COMMANDS])
        assertEquals("None", adapterValues(info(), words)[AdapterField.UNSUPPORTED_COMMANDS])
    }

    // --- Connection > unsupported adapter -----------------------------------------------------

    @Test
    fun `only an unsupported-adapter failure shows the list of adapters that work`() {
        assertTrue(ConnectionState.Failed("No answer", FailureKind.UNSUPPORTED_ADAPTER).isUnsupportedAdapter())
        assertFalse(ConnectionState.Failed("No permission").isUnsupportedAdapter())
        assertFalse(ConnectionState.Idle.isUnsupportedAdapter())
        assertFalse(ConnectionState.Reconnecting(attempt = 1, delayMs = 1_000, reason = "Lost").isUnsupportedAdapter())
    }

    @Test
    fun `a supported adapter reads as brand followed by its models`() {
        assertEquals("OBDLink LX, MX+, CX", SupportedAdapter("OBDLink", listOf("LX", "MX+", "CX")).label)
        assertEquals("Viecar", SupportedAdapter("Viecar").label)
    }

    @Test
    fun `the list is short enough to read at a glance`() {
        assertTrue(SupportedAdapters.size in 5..7)
    }

    /** The in-app list is a copy of the README's; this fails as soon as a name drifts away from it. */
    @Test
    fun `every adapter in the list is named in the README supported adapters section`() {
        val readme = File("../README.md").readText()
        val section = readme.substringAfter("## Supported adapters").substringBefore("\n## ")
        val text = section.replace("`", "").replace("*", "").replace(Regex("""\s+"""), " ")

        SupportedAdapters.forEach { adapter ->
            (listOf(adapter.brand) + adapter.models).forEach { name ->
                assertTrue("README does not mention '$name'", text.contains(name))
            }
        }
    }
}
