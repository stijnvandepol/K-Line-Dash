package nl.stijnvdpol.klinedash.core.elm

import nl.stijnvdpol.klinedash.core.assertIs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser cases built from replies real clones send. Fixtures in /fixtures hold the raw text with
 * \r, \n and \0 written as escape sequences (editors and git mangle literal CRs).
 */
class ElmParserTest {

    private fun fixture(name: String): String {
        val stream = javaClass.getResourceAsStream("/fixtures/$name") ?: error("missing fixture $name")
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return text.trimEnd('\r', '\n')
            .replace("\\r", "\r")
            .replace("\\n", "\n")
            .replace("\\0", "\u0000")
    }

    private fun assertData(expected: List<String>, result: ElmResult) {
        assertEquals(expected, assertIs<ElmResult.Data>(result).lines)
    }

    // ---- OBD data ----

    @Test
    fun `plain single frame with trailing space`() {
        assertData(listOf("410C1AF8"), ElmParser.parse("010C", "41 0C 1A F8 \r\r"))
    }

    @Test
    fun `echo on with spaces on is stripped`() {
        assertData(listOf("410C1AF8"), ElmParser.parse("010C", fixture("echo_spaces_rpm.txt")))
    }

    @Test
    fun `echo is matched ignoring spaces and case`() {
        assertData(listOf("410C1AF8"), ElmParser.parse("01 0c", "010C\r410C1AF8\r\r"))
        assertData(listOf("410C1AF8"), ElmParser.parse("010C", "01 0C\r41 0C 1A F8\r\r"))
    }

    @Test
    fun `echo without spaces and compact data`() {
        assertData(listOf("410D00"), ElmParser.parse("010D", "010D\r410D00\r\r"))
    }

    @Test
    fun `searching prefix is removed`() {
        assertData(listOf("4100BE1FB810"), ElmParser.parse("0100", fixture("searching_0100.txt")))
    }

    @Test
    fun `bus init ok prefix is removed`() {
        assertData(listOf("410C0D40"), ElmParser.parse("010C", fixture("businit_ok_rpm.txt")))
    }

    @Test
    fun `bus init variants without dots or with tight dots`() {
        assertData(listOf("410C0D40"), ElmParser.parse("010C", "BUS INIT: OK\r41 0C 0D 40\r\r"))
        assertData(listOf("410C0D40"), ElmParser.parse("010C", "BUS INIT:...OK\r41 0C 0D 40\r\r"))
        assertData(listOf("410C0D40"), ElmParser.parse("010C", "BUS INIT: ... OK\r41 0C 0D 40\r\r"))
    }

    @Test
    fun `data glued to the bus init line is kept`() {
        assertData(listOf("410C0D40"), ElmParser.parse("010C", "BUS INIT: ...OK41 0C 0D 40\r\r"))
    }

    @Test
    fun `searching then bus init ok then data`() {
        assertData(
            listOf("410C0D40"),
            ElmParser.parse("010C", "SEARCHING...\rBUS INIT: ...OK\r41 0C 0D 40\r\r"),
        )
    }

    @Test
    fun `echo then searching then data`() {
        assertData(
            listOf("4100BE1FB810"),
            ElmParser.parse("0100", "0100\rSEARCHING...\r41 00 BE 1F B8 10 \r\r"),
        )
    }

    @Test
    fun `lower case hex is upper cased`() {
        assertData(listOf("410C1AF8"), ElmParser.parse("010C", "41 0c 1a f8\r\r"))
    }

    @Test
    fun `cr lf in any combination`() {
        val expected = listOf("410C1AF8")
        assertData(expected, ElmParser.parse("010C", "41 0C 1A F8\r\n\r\n"))
        assertData(expected, ElmParser.parse("010C", "\n41 0C 1A F8\n"))
        assertData(expected, ElmParser.parse("010C", "\r\n010C\r\n41 0C 1A F8\r\n\r\n"))
        assertData(expected, ElmParser.parse("010C", "\r\r\r41 0C 1A F8\r\r\r"))
    }

    @Test
    fun `blank lines and trailing spaces`() {
        assertData(listOf("410C1AF8"), ElmParser.parse("010C", "   \r\r41 0C 1A F8    \r  \r\r"))
    }

    @Test
    fun `leading nul characters are trimmed`() {
        assertData(listOf("410C1AF8"), ElmParser.parse("010C", "\u0000\u000041 0C 1A F8\r\r"))
    }

    @Test
    fun `mode 03 multi frame keeps every frame`() {
        assertData(
            listOf("43017113490000", "43030000000000"),
            ElmParser.parse("03", fixture("mode03_multiframe.txt")),
        )
    }

    @Test
    fun `mode 03 with echo and no codes`() {
        assertData(listOf("43000000000000"), ElmParser.parse("03", "03\r43 00 00 00 00 00 00 \r\r"))
    }

    @Test
    fun `mode 09 vin multi frame`() {
        assertData(
            listOf(
                "490201000000" + "53",
                "490202" + "4231" + "5A45",
                "490203" + "3232" + "4531",
                "490204" + "3045" + "3132",
                "490205" + "3334" + "3536",
            ),
            ElmParser.parse("0902", fixture("mode09_vin.txt")),
        )
    }

    @Test
    fun `with headers on the full frame is kept`() {
        assertData(listOf("486B10410C1AF8" + "1F"), ElmParser.parse("010C", "48 6B 10 41 0C 1A F8 1F \r\r"))
    }

    @Test
    fun `invalid lines are dropped when valid ones remain`() {
        assertData(listOf("410C1AF8"), ElmParser.parse("010C", "41 0C 1A F8\r#@!!\rGARBAGE\r\r"))
    }

    @Test
    fun `odd length hex line is invalid`() {
        assertData(listOf("410C1AF8"), ElmParser.parse("010C", "41 0C 1A F8\r41 0C 1\r\r"))
    }

    @Test
    fun `can multi-frame layout keeps its length line and frame numbers`() {
        val raw = "00A\r0: 43 04 01 71 13 49\r1: 03 00 04 20 00 00 00\r\r"
        assertData(listOf("00A", "0:430401711349", "1:03000420000000"), ElmParser.parse("03", raw))
    }

    @Test
    fun `a can frame line with an odd number of hex digits is dropped`() {
        assertData(listOf("00A", "0:430401711349"), ElmParser.parse("03", "00A\r0: 43 04 01 71 13 49\r1: 03 0\r\r"))
    }

    @Test
    fun `high byte garbage inside a line invalidates only that line`() {
        assertData(listOf("410C1AF8"), ElmParser.parse("010C", "41 0C 1A F8\r41 0C \u00ff1A F8\r\r"))
        assertIs<ElmResult.Garbage>(ElmParser.parse("010C", "41 0C \u00ff1A F8\r\r"))
    }

    @Test
    fun `no valid hex line is garbage`() {
        assertIs<ElmResult.Garbage>(ElmParser.parse("010C", "#@!!\rWHAT\r\r"))
        assertIs<ElmResult.Garbage>(ElmParser.parse("010C", "010C\r\r"))
    }

    @Test
    fun `empty reply is garbage for obd and at`() {
        assertIs<ElmResult.Garbage>(ElmParser.parse("010C", ""))
        assertIs<ElmResult.Garbage>(ElmParser.parse("010C", "\r\r \r"))
        assertIs<ElmResult.Garbage>(ElmParser.parse("ATE0", ""))
        assertIs<ElmResult.Garbage>(ElmParser.parse("ATE0", "ATE0\r\r"))
    }

    @Test
    fun `raw text is passed through untouched`() {
        val raw = "010C\r41 0C 1A F8 \r\r"
        assertEquals(raw, ElmParser.parse("010C", raw).raw)
        assertEquals("NO DATA\r\r", ElmParser.parse("010C", "NO DATA\r\r").raw)
    }

    // ---- status and error texts ----

    @Test
    fun `no data`() {
        assertIs<ElmResult.NoData>(ElmParser.parse("010B", "NO DATA\r\r"))
        assertIs<ElmResult.NoData>(ElmParser.parse("010B", "010B\rNO DATA\r\r"))
        assertIs<ElmResult.NoData>(ElmParser.parse("010B", "NO DATA   \r\r"))
    }

    @Test
    fun `no data after searching or bus init ok`() {
        assertIs<ElmResult.NoData>(ElmParser.parse("0100", fixture("searching_no_data.txt")))
        assertIs<ElmResult.NoData>(ElmParser.parse("0100", fixture("businit_ok_no_data.txt")))
    }

    @Test
    fun `question mark is unknown for obd and at`() {
        assertIs<ElmResult.Unknown>(ElmParser.parse("010C", "?\r\r"))
        assertIs<ElmResult.Unknown>(ElmParser.parse("ATAT1", fixture("echo_atat1_unknown.txt")))
        assertIs<ElmResult.Unknown>(ElmParser.parse("AT@1", "?\r\r"))
    }

    @Test
    fun `bus init error is an init error`() {
        assertIs<ElmResult.InitError>(ElmParser.parse("0100", fixture("businit_error.txt")))
        assertIs<ElmResult.InitError>(ElmParser.parse("0100", "BUS INIT: ERROR\r\r"))
        assertIs<ElmResult.InitError>(ElmParser.parse("0100", "BUS INIT:...ERROR\r\r"))
    }

    @Test
    fun `unable to connect is an init error`() {
        assertIs<ElmResult.InitError>(ElmParser.parse("0100", "UNABLE TO CONNECT\r\r"))
        assertIs<ElmResult.InitError>(ElmParser.parse("0100", fixture("unable_to_connect.txt")))
    }

    @Test
    fun `clone variant not connected is an init error`() {
        assertIs<ElmResult.InitError>(ElmParser.parse("0100", "SEARCHING...\rNOT CONNECTED\r\r"))
    }

    @Test
    fun `stopped can error and buffer full`() {
        assertIs<ElmResult.Stopped>(ElmParser.parse("010C", "STOPPED\r\r"))
        assertIs<ElmResult.CanError>(ElmParser.parse("0100", "SEARCHING...\rCAN ERROR\r\r"))
        assertIs<ElmResult.BufferFull>(ElmParser.parse("010C", "BUFFER FULL\r\r"))
    }

    @Test
    fun `all bus error texts`() {
        for (text in listOf("BUS ERROR", "FB ERROR", "DATA ERROR", "RX ERROR", "BUS BUSY", "LV RESET", "ERR94", "ERR05")) {
            assertIs<ElmResult.BusError>(ElmParser.parse("010C", "$text\r\r"), text)
        }
    }

    @Test
    fun `data error marker appended to a data line is a bus error`() {
        assertIs<ElmResult.BusError>(ElmParser.parse("010C", fixture("data_error_marker.txt")))
        assertIs<ElmResult.BusError>(ElmParser.parse("010C", "41 0C 1A F8<RX ERROR\r\r"))
    }

    @Test
    fun `error text wins over data lines`() {
        assertIs<ElmResult.BusError>(ElmParser.parse("03", "43 01 71 13 49 00 00\rBUS BUSY\r\r"))
    }

    // ---- reset banner ----

    @Test
    fun `reset banner as reply to an obd command is garbage`() {
        assertIs<ElmResult.Garbage>(ElmParser.parse("010C", fixture("reset_banner.txt")))
        assertIs<ElmResult.Garbage>(ElmParser.parse("010C", "?\rELM327 v1.5\r\r"))
    }

    @Test
    fun `looks like reset detects the banner`() {
        assertTrue(ElmParser.looksLikeReset(fixture("reset_banner.txt")))
        assertTrue(ElmParser.looksLikeReset("elm327 v2.1"))
        assertTrue(ElmParser.looksLikeReset("\u0000\r\rELM327 v1.5\r\r"))
        assertFalse(ElmParser.looksLikeReset("41 0C 1A F8\r\r"))
        assertFalse(ElmParser.looksLikeReset("NO DATA"))
        assertFalse(ElmParser.looksLikeReset(""))
    }

    // ---- AT commands ----

    @Test
    fun `atz banner is data`() {
        assertData(listOf("ELM327 v1.5"), ElmParser.parse("ATZ", fixture("reset_banner.txt")))
        assertData(listOf("ELM327 v1.5"), ElmParser.parse("ATZ", "ATZ\r\r\rELM327 v1.5\r\r"))
    }

    @Test
    fun `atz with stray question mark before the banner is still data`() {
        assertData(listOf("ELM327 v1.5"), ElmParser.parse("ATZ", fixture("clone_garbage_after_reset.txt")))
    }

    @Test
    fun `at ok with echo`() {
        assertData(listOf("OK"), ElmParser.parse("ATE0", "ATE0\rOK\r\r"))
        assertData(listOf("OK"), ElmParser.parse("ATS0", "OK\r\r"))
    }

    @Test
    fun `at command match is case insensitive`() {
        assertData(listOf("ELM327 v1.5"), ElmParser.parse("atz", "ATZ\r\rELM327 v1.5\r\r"))
        assertData(listOf("OK"), ElmParser.parse("atsp0", "atsp0\rOK\r\r"))
    }

    @Test
    fun `ati and voltage lines`() {
        assertData(listOf("ELM327 v1.5"), ElmParser.parse("ATI", "ATI\rELM327 v1.5\r\r"))
        assertData(listOf("12.4V"), ElmParser.parse("ATRV", "12.4V\r\r"))
        assertData(listOf("13.9V"), ElmParser.parse("ATRV", "ATRV\r13.9V\r\r"))
    }

    @Test
    fun `at text drops non printable garbage`() {
        assertData(listOf("ELM327 v1.5"), ElmParser.parse("ATI", "\u00ffELM327 v1.5\u0000\r\r"))
    }

    @Test
    fun `atdp and atdpn`() {
        assertData(listOf("AUTO, ISO 9141-2"), ElmParser.parse("ATDP", fixture("atdp_auto.txt")))
        assertData(listOf("A3"), ElmParser.parse("ATDPN", "A3\r\r"))
        assertData(listOf("3"), ElmParser.parse("ATDPN", "3\r\r"))
        assertData(listOf("A5"), ElmParser.parse("ATDPN", "ATDPN\rA5\r\r"))
    }

    @Test
    fun `at multi line answer keeps every line`() {
        assertData(listOf("LINE1", "LINE2"), ElmParser.parse("ATFOO", "LINE1\r\nLINE2\r\n\r\n"))
    }

    // ---- value helpers ----

    @Test
    fun `voltage variants`() {
        assertEquals(12.4, ElmParser.parseVoltage("12.4V")!!, 1e-9)
        assertEquals(12.4, ElmParser.parseVoltage("12.4 V")!!, 1e-9)
        assertEquals(12.4, ElmParser.parseVoltage("12,4V")!!, 1e-9)
        assertEquals(13.9, ElmParser.parseVoltage("13.9v")!!, 1e-9)
        assertEquals(13.9, ElmParser.parseVoltage(" 13.9 ")!!, 1e-9)
        assertEquals(12.0, ElmParser.parseVoltage("12V")!!, 1e-9)
        assertEquals(9.55, ElmParser.parseVoltage("9.55V")!!, 1e-9)
    }

    @Test
    fun `voltage rejects garbage`() {
        assertNull(ElmParser.parseVoltage("?"))
        assertNull(ElmParser.parseVoltage(""))
        assertNull(ElmParser.parseVoltage("ABC"))
        assertNull(ElmParser.parseVoltage("12.4V extra"))
        assertNull(ElmParser.parseVoltage("NO DATA"))
    }

    @Test
    fun `protocol number variants`() {
        assertEquals("3", ElmParser.parseProtocolNumber("A3"))
        assertEquals("3", ElmParser.parseProtocolNumber("3"))
        assertEquals("5", ElmParser.parseProtocolNumber("A5"))
        assertEquals("4", ElmParser.parseProtocolNumber(" a4 "))
        assertEquals("0", ElmParser.parseProtocolNumber("A0"))
        assertNull(ElmParser.parseProtocolNumber("?"))
        assertNull(ElmParser.parseProtocolNumber(""))
        assertNull(ElmParser.parseProtocolNumber("AUTO"))
    }

    @Test
    fun `protocol name strips the auto prefix`() {
        assertEquals("ISO 9141-2", ElmParser.parseProtocolName("AUTO, ISO 9141-2"))
        assertEquals("ISO 9141-2", ElmParser.parseProtocolName("ISO 9141-2"))
        assertEquals("ISO 14230-4 (KWP FAST)", ElmParser.parseProtocolName("auto, ISO 14230-4 (KWP FAST)"))
    }

    @Test
    fun `data results expose parsed lines through the sealed type`() {
        val result = ElmParser.parse("010C", "41 0C 1A F8\r\r")
        val data = assertIs<ElmResult.Data>(result)
        assertEquals(1, data.lines.size)
    }
}
