package nl.stijnvdpol.klinedash.core.diag

import nl.stijnvdpol.klinedash.core.elm.ElmParser
import nl.stijnvdpol.klinedash.core.elm.ElmResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DtcParserTest {

    private fun parse(
        vararg lines: String,
        kind: DtcKind = DtcKind.STORED,
        format: DtcFormat = DtcFormat.KLINE,
    ): List<String>? = DtcParser.parse(data(*lines), kind, format)?.map { it.code }

    // ---- K-line ----

    @Test
    fun `k-line without codes answers with an empty list`() {
        assertEquals(emptyList<String>(), parse("43000000000000"))
    }

    @Test
    fun `k-line one code`() {
        assertEquals(listOf("P0171"), parse("43017100000000"))
    }

    @Test
    fun `k-line three codes in one frame`() {
        assertEquals(listOf("P0171", "P1349", "P1656"), parse("43017113491656"))
    }

    @Test
    fun `k-line four codes over two frames`() {
        assertEquals(
            listOf("P0171", "P1349", "P1656", "P0420"),
            parse("43017113491656", "43042000000000"),
        )
    }

    @Test
    fun `k-line seven codes over three frames`() {
        assertEquals(
            listOf("P0171", "P1349", "P1656", "P0420", "P0300", "P0301", "P0302"),
            parse("43017113491656", "43042003000301", "43030200000000"),
        )
    }

    @Test
    fun `k-line zero padding between codes is ignored`() {
        assertEquals(listOf("P0171", "P1349"), parse("43017100001349"))
    }

    @Test
    fun `codes reported by several ecus are listed once in first-seen order`() {
        assertEquals(
            listOf("P0171", "P1349", "P0420"),
            parse("43017113490000", "43134904200000"),
        )
    }

    @Test
    fun `all four code letters`() {
        // P0171 = 01 71, C0300 = 43 00, B1ABC = 9A BC, U1234 = D2 34
        assertEquals(listOf("P0171", "C0300", "B1ABC"), parse("430171" + "4300" + "9ABC"))
        assertEquals(listOf("U1234"), parse("43D23400000000"))
    }

    @Test
    fun `pending and permanent use their own response byte`() {
        assertEquals(listOf("P0300"), parse("47030000000000", kind = DtcKind.PENDING))
        assertEquals(listOf("P0420"), parse("4A042000000000", kind = DtcKind.PERMANENT))
    }

    @Test
    fun `lines answering another service are ignored`() {
        assertNull(parse("43017100000000", kind = DtcKind.PENDING))
        assertEquals(listOf("P0171"), parse("410C1AF8", "43017100000000"))
    }

    @Test
    fun `malformed and foreign lines are ignored`() {
        // odd number of code bytes, non-hex, negative response
        assertEquals(listOf("P1349"), parse("43017100", "ZZ", "7F0311", "43134900000000"))
    }

    @Test
    fun `no usable line gives null instead of an empty list`() {
        assertNull(parse("7F0311"))
        assertNull(parse("410C1AF8"))
    }

    // ---- CAN ----

    @Test
    fun `can single frame uses the count byte`() {
        assertEquals(listOf("P0171", "P0420"), parse("43020171042000", format = DtcFormat.CAN))
    }

    @Test
    fun `can single frame ignores padding behind the counted codes`() {
        assertEquals(listOf("P0171"), parse("43010171042000", format = DtcFormat.CAN))
    }

    @Test
    fun `can without codes answers with an empty list`() {
        assertEquals(emptyList<String>(), parse("43000000000000", format = DtcFormat.CAN))
    }

    @Test
    fun `can multi frame with length line and frame prefixes`() {
        // 43 03 0171 1349 1656 = 8 bytes: six in frame 0, the rest in frame 1.
        assertEquals(
            listOf("P0171", "P1349", "P1656"),
            parse("008", "0:430301711349", "1:16560000000000", format = DtcFormat.CAN),
        )
    }

    @Test
    fun `can multi frame straight from the adapter text`() {
        val raw = "008\r0: 43 03 01 71 13 49\r1: 16 56 00 00 00 00 00\r\r"
        val data = ElmParser.parse("03", raw) as ElmResult.Data
        assertEquals(
            listOf("P0171", "P1349", "P1656"),
            DtcParser.parse(data, DtcKind.STORED, DtcFormat.CAN)?.map { it.code },
        )
    }

    @Test
    fun `can multi frame without length line`() {
        assertEquals(
            listOf("P0171", "P1349", "P1656"),
            parse("0:430301711349", "1:16560000000000", format = DtcFormat.CAN),
        )
    }

    @Test
    fun `can multi frame answers of two ecus are merged without duplicates`() {
        assertEquals(
            listOf("P0171", "P1349", "P1656", "P0420"),
            parse(
                "008", "0:430301711349", "1:1656",
                "008", "0:430301711656", "1:0420",
                format = DtcFormat.CAN,
            ),
        )
    }

    @Test
    fun `can message shorter than its count yields the codes it has`() {
        assertEquals(listOf("P0171"), parse("43050171", format = DtcFormat.CAN))
    }

    // ---- helpers ----

    @Test
    fun `protocol number picks the format`() {
        for (can in listOf("6", "7", "8", "9")) assertEquals(can, DtcFormat.CAN, DtcParser.formatFor(can))
        for (other in listOf("1", "2", "3", "4", "5", "T", "")) {
            assertEquals(other, DtcFormat.KLINE, DtcParser.formatFor(other))
        }
    }

    @Test
    fun `decode returns null for padding and the code otherwise`() {
        assertNull(DtcParser.decode(0, 0))
        assertEquals("P0171", DtcParser.decode(0x01, 0x71)?.code)
        assertEquals("U3FFF", DtcParser.decode(0xFF, 0xFF)?.code)
    }
}
