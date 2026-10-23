package nl.stijnvdpol.klinedash.core.car

import nl.stijnvdpol.klinedash.core.car.CarMake.BMW_MINI
import nl.stijnvdpol.klinedash.core.car.CarMake.FIAT_ALFA_LANCIA
import nl.stijnvdpol.klinedash.core.car.CarMake.FORD
import nl.stijnvdpol.klinedash.core.car.CarMake.HONDA_ACURA
import nl.stijnvdpol.klinedash.core.car.CarMake.HYUNDAI_KIA
import nl.stijnvdpol.klinedash.core.car.CarMake.MAZDA
import nl.stijnvdpol.klinedash.core.car.CarMake.MERCEDES_SMART
import nl.stijnvdpol.klinedash.core.car.CarMake.MITSUBISHI
import nl.stijnvdpol.klinedash.core.car.CarMake.NISSAN_INFINITI
import nl.stijnvdpol.klinedash.core.car.CarMake.OPEL_VAUXHALL
import nl.stijnvdpol.klinedash.core.car.CarMake.PSA
import nl.stijnvdpol.klinedash.core.car.CarMake.RENAULT_DACIA
import nl.stijnvdpol.klinedash.core.car.CarMake.SUBARU
import nl.stijnvdpol.klinedash.core.car.CarMake.SUZUKI
import nl.stijnvdpol.klinedash.core.car.CarMake.TOYOTA_LEXUS
import nl.stijnvdpol.klinedash.core.car.CarMake.VOLVO
import nl.stijnvdpol.klinedash.core.car.CarMake.VW_GROUP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VinMakeTest {

    /** A well-formed VIN that starts with [prefix]; the rest is filler without I, O or Q. */
    private fun vin(prefix: String): String = prefix + "ZZZ1JZXW000001".take(17 - prefix.length)

    private fun assertMakes(expected: CarMake?, vararg wmis: String) {
        for (wmi in wmis) assertEquals("WMI $wmi", expected, carMakeFromVin(vin(wmi)))
    }

    // ---- the three-character table, family by family ----

    @Test
    fun `volkswagen group identifiers`() = assertMakes(
        VW_GROUP,
        "WVW", "WVG", "WV1", "WV2", "AAV", "3VW", "1VW", "9BW", // VW
        "WAU", "WUA", "TRU", // Audi
        "VSS", // Seat
        "TMB", "TMP", // Skoda
    )

    @Test
    fun `bmw and mini identifiers`() = assertMakes(BMW_MINI, "WBA", "WBS", "WBX", "WBY", "WB5", "WB6", "WMW", "WMZ")

    @Test
    fun `mercedes and smart identifiers`() = assertMakes(MERCEDES_SMART, "WDB", "WDC", "WDD", "WDF", "WMX", "VSC", "NMB", "WME")

    @Test
    fun `opel and vauxhall identifiers`() = assertMakes(OPEL_VAUXHALL, "W0L", "W0V", "W0S", "VXK", "VXE", "VSX")

    @Test
    fun `ford identifiers`() = assertMakes(FORD, "WF0", "VS6", "NM0")

    @Test
    fun `cars built in north america`() {
        assertMakes(FORD, "1FA", "1FM", "1FT", "2FA", "2FM", "3FA")
        assertMakes(TOYOTA_LEXUS, "4T1", "4T3", "5TD", "2T1", "5YF")
        assertMakes(HONDA_ACURA, "1HG", "2HG", "5FN", "19X")
        assertMakes(NISSAN_INFINITI, "1N4", "1N6", "3N1", "5N1")
        assertMakes(SUBARU, "4S3", "4S4")
        assertMakes(HYUNDAI_KIA, "5NP", "5XY")
    }

    @Test
    fun `alpina is a bmw although its prefix is audi's`() = assertMakes(BMW_MINI, "WAP")

    @Test
    fun `renault and dacia identifiers`() = assertMakes(RENAULT_DACIA, "VF1", "VS5", "NM1", "KNM", "UU1")

    @Test
    fun `peugeot citroen and ds identifiers`() = assertMakes(PSA, "VF3", "VR3", "VS8", "VF7", "VR7", "VS7", "VR1")

    @Test
    fun `fiat alfa romeo and lancia identifiers`() = assertMakes(FIAT_ALFA_LANCIA, "ZFA", "ZFB", "ZAR", "ZAS", "ZLA")

    @Test
    fun `volvo identifiers`() = assertMakes(VOLVO, "YV1", "YV4", "XLB")

    @Test
    fun `toyota and lexus identifiers`() = assertMakes(
        TOYOTA_LEXUS,
        "JT2", "JT3", "JT4", "JTD", "JTE", "JTF", "JTG", "JTK", "JTL", "JTM", "JTN", "SB1", "VNK", "NMT", "AHT", "MR0", "TW1",
        "JTH", "JTJ", "JT6", "JT8", // Lexus, found by the JT prefix
    )

    @Test
    fun `honda and acura identifiers`() = assertMakes(HONDA_ACURA, "JHM", "JHL", "JH4", "SHH", "SHS", "NLA", "MRH")

    @Test
    fun `nissan and infiniti identifiers`() =
        assertMakes(NISSAN_INFINITI, "JN1", "JN6", "JN8", "JNK", "JNR", "JNX", "SJN", "SJK", "VSK", "VNV")

    @Test
    fun `mazda identifiers`() = assertMakes(MAZDA, "JM1", "JM3", "JM6", "JM7", "JMZ", "JM0")

    @Test
    fun `mitsubishi identifiers`() = assertMakes(MITSUBISHI, "JA3", "JA4", "JA7", "JMA", "JMB", "JMF", "JMY", "XMC")

    @Test
    fun `subaru identifiers`() = assertMakes(SUBARU, "JF1", "JF2", "JF3")

    @Test
    fun `suzuki identifiers`() = assertMakes(SUZUKI, "JS2", "JS3", "TSM", "MA3")

    @Test
    fun `hyundai genesis and kia identifiers`() = assertMakes(
        HYUNDAI_KIA,
        "KMH", "KM8", "KMT", "KMU", "TMA", "TMC", "NLH", "NLJ", "MAL", // Hyundai, Genesis
        "KNA", "KNC", "KNE", "KND", "U5Y", "U6Y", // Kia
    )

    // ---- the exceptions that decide between two makes ----

    @Test
    fun `tma is a hyundai built in the czech republic, not a skoda`() {
        assertMakes(HYUNDAI_KIA, "TMA")
        assertMakes(VW_GROUP, "TMB")
    }

    @Test
    fun `jmb is a mitsubishi, not a mazda`() {
        assertMakes(MITSUBISHI, "JMA", "JMB", "JMF", "JMY")
        assertMakes(MAZDA, "JMC")
    }

    @Test
    fun `knm is a renault samsung, not a kia`() {
        assertMakes(RENAULT_DACIA, "KNM")
        assertMakes(HYUNDAI_KIA, "KNA")
    }

    @Test
    fun `isuzu identifiers under the mitsubishi prefix are another make`() {
        assertMakes(null, "JAA", "JAB", "JAC", "JAE", "JAL", "JAM")
        assertMakes(MITSUBISHI, "JA3", "JAD")
    }

    // ---- the two-character fallback for identifiers the table does not know ----

    @Test
    fun `an unknown identifier falls back to its two-character prefix`() {
        assertMakes(VW_GROUP, "WVX", "WAX")
        assertMakes(BMW_MINI, "WBZ", "WB2")
        assertMakes(MERCEDES_SMART, "WDA", "WDE", "WDZ")
        assertMakes(FIAT_ALFA_LANCIA, "ZFC")
        assertMakes(VOLVO, "YV5", "YVA")
        assertMakes(TOYOTA_LEXUS, "JT1", "JTZ")
        assertMakes(HONDA_ACURA, "JHA", "JH5")
        assertMakes(NISSAN_INFINITI, "JN2", "JNZ")
        assertMakes(MAZDA, "JM2", "JMC")
        assertMakes(MITSUBISHI, "JA5", "JAD", "JAN")
        assertMakes(SUBARU, "JF4", "JFZ")
        assertMakes(SUZUKI, "JS4", "JSZ")
        assertMakes(HYUNDAI_KIA, "KM1", "KMJ", "KN1", "KNZ")
    }

    @Test
    fun `prefixes that mix several makes have no fallback`() {
        assertMakes(null, "WMA", "WMB", "VFA", "VF2", "VF6", "VFZ")
    }

    @Test
    fun `vans, trucks and buses are another make`() {
        assertMakes(null, "WD0", "WD1", "WD2", "WD3", "WD4", "WD5", "WD6", "WD7", "WD8", "WD9", "YV2", "YV3")
    }

    @Test
    fun `motorcycles are another make`() {
        assertMakes(null, "WB1", "WB3", "WB4", "JS1", "JH2", "JH3")
    }

    @Test
    fun `other prefix exceptions are another make`() {
        assertMakes(null, "KM4", "KM9", "KNJ", "ZFF")
    }

    // ---- makes the app has no table for ----

    @Test
    fun `makes outside the list are another make`() {
        assertMakes(null, "WP0", "WP1", "SCB", "ZHW", "SAJ", "SAL", "KL1", "KL7", "KL8", "YS3", "ZAM", "ZAC", "1G1", "1C4", "5YJ")
    }

    @Test
    fun `an identifier nobody knows is another make`() {
        assertMakes(null, "XXX", "999", "A00")
    }

    // ---- the shape of the VIN ----

    @Test
    fun `a real corolla vin is a toyota`() {
        assertEquals(TOYOTA_LEXUS, carMakeFromVin("SB1ZE22E10E123456"))
    }

    @Test
    fun `a vin with the wrong length is not decoded`() {
        assertNull(carMakeFromVin(""))
        assertNull(carMakeFromVin("WVW"))
        assertNull(carMakeFromVin(vin("WVW").dropLast(1)))
        assertNull(carMakeFromVin(vin("WVW") + "1"))
    }

    @Test
    fun `a vin with a forbidden letter is not decoded`() {
        for (letter in listOf('I', 'O', 'Q')) {
            assertNull("letter $letter", carMakeFromVin(vin("WVW").dropLast(1) + letter))
        }
    }

    @Test
    fun `a vin in lower case or with other characters is not decoded`() {
        assertNull(carMakeFromVin(vin("WVW").lowercase()))
        assertNull(carMakeFromVin(vin("WVW").dropLast(1) + "-"))
        assertNull(carMakeFromVin(vin("WVW").dropLast(1) + " "))
    }

    @Test
    fun `an all-zero or fragment vin is not decoded`() {
        assertNull(carMakeFromVin("00000000000000000"))
        assertNull(carMakeFromVin("WVWZZZ1J"))
    }
}
