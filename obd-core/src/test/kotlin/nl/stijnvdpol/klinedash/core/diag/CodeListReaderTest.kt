package nl.stijnvdpol.klinedash.core.diag

import kotlinx.coroutines.test.runTest
import nl.stijnvdpol.klinedash.core.assertFails
import nl.stijnvdpol.klinedash.core.elm.ElmResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeListReaderTest {

    private fun reader(
        link: ScriptedLink,
        reportedDtcCount: Int = 0,
        format: DtcFormat = DtcFormat.KLINE,
    ) = CodeListReader(link, format, reportedDtcCount)

    private fun link(result: ElmResult) = ScriptedLink(responder = { result })

    @Test
    fun `answer with codes is supported`() = runTest {
        val list = reader(link(data("43017113490000")), reportedDtcCount = 2).read(DtcKind.STORED)

        assertEquals(DtcList(DtcKind.STORED, true, listOf(Dtc("P0171"), Dtc("P1349"))), list)
    }

    @Test
    fun `all-zero answer is supported and empty`() = runTest {
        val list = reader(link(data("47000000000000"))).read(DtcKind.PENDING)

        assertEquals(DtcList(DtcKind.PENDING, true, emptyList()), list)
    }

    @Test
    fun `can answers are read with the can layout`() = runTest {
        val list = reader(link(data("43020171042000")), format = DtcFormat.CAN).read(DtcKind.STORED)

        assertEquals(listOf("P0171", "P0420"), list.codes.map { it.code })
    }

    @Test
    fun `each kind sends its own service`() = runTest {
        val link = ScriptedLink(
            responder = answering(
                mapOf(
                    "03" to data("43000000000000"),
                    "07" to data("47000000000000"),
                    "0A" to data("4A000000000000"),
                ),
            ),
        )
        val reader = reader(link)
        for (kind in DtcKind.entries) reader.read(kind)

        assertEquals(listOf("03", "07", "0A"), link.sent)
    }

    @Test
    fun `no data on stored and pending means none when pid 01 counted zero`() = runTest {
        val reader = reader(link(noData), reportedDtcCount = 0)

        assertEquals(DtcList(DtcKind.STORED, true, emptyList()), reader.read(DtcKind.STORED))
        assertEquals(DtcList(DtcKind.PENDING, true, emptyList()), reader.read(DtcKind.PENDING))
    }

    @Test
    fun `no data on stored while pid 01 counted codes is unsupported`() = runTest {
        val list = reader(link(noData), reportedDtcCount = 2).read(DtcKind.STORED)

        assertEquals(DtcList(DtcKind.STORED, false, emptyList()), list)
    }

    @Test
    fun `no data on permanent is unsupported even with zero codes reported`() = runTest {
        val list = reader(link(noData), reportedDtcCount = 0).read(DtcKind.PERMANENT)

        assertEquals(DtcList(DtcKind.PERMANENT, false, emptyList()), list)
    }

    @Test
    fun `question mark is unsupported`() = runTest {
        val list = reader(link(ElmResult.Unknown("?"))).read(DtcKind.STORED)

        assertFalse(list.isSupported)
    }

    @Test
    fun `unreadable stored list is an error not an empty list`() = runTest {
        val link = link(ElmResult.Timeout(""))

        val error = assertFails<IllegalStateException> { reader(link).read(DtcKind.STORED) }

        assertTrue(error.message!!.contains("Try again"))
        assertEquals("one retry", listOf("03", "03"), link.sent)
    }

    @Test
    fun `negative response on stored is an error and on permanent unsupported`() = runTest {
        val negative = data("7F0311")

        assertFails<IllegalStateException> { reader(link(negative)).read(DtcKind.STORED) }
        assertFalse(reader(link(negative)).read(DtcKind.PERMANENT).isSupported)
    }

    @Test
    fun `unreadable permanent list is unsupported`() = runTest {
        assertFalse(reader(link(ElmResult.BusError("BUS ERROR"))).read(DtcKind.PERMANENT).isSupported)
    }
}
