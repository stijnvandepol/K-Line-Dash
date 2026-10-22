package nl.stijnvdpol.klinedash.service

import org.junit.Assert.assertEquals
import org.junit.Test

class HeadUnitPlatformTest {
    private fun detect(
        hardware: String = "",
        board: String = "",
        manufacturer: String = "",
        vararg packages: String,
    ) = detectPlatform(DeviceHints(hardware, board, manufacturer, packages.toSet()))

    @Test
    fun theFytSystemAppMeansFyt() {
        assertEquals(HeadUnitPlatform.FYT, detect(packages = arrayOf("com.syu.ms")))
        assertEquals(HeadUnitPlatform.FYT, detect(hardware = "something", packages = arrayOf("com.syu.bt")))
    }

    @Test
    fun theMicrontekBootControlAppMeansMtc() {
        assertEquals(HeadUnitPlatform.MTC, detect(packages = arrayOf("com.microntek.bootcontrol")))
    }

    @Test
    fun anInstalledVendorAppBeatsTheBuildStrings() {
        assertEquals(HeadUnitPlatform.FYT, detect(board = "mtcd_px5", packages = arrayOf("com.syu.ms")))
    }

    @Test
    fun unisocChipNamesPointToFyt() {
        assertEquals(HeadUnitPlatform.FYT, detect(hardware = "ums512_1h10"))
        assertEquals(HeadUnitPlatform.FYT, detect(board = "UIS7862"))
        assertEquals(HeadUnitPlatform.FYT, detect(manufacturer = "FYT"))
    }

    @Test
    fun microntekNamesPointToMtc() {
        assertEquals(HeadUnitPlatform.MTC, detect(manufacturer = "Microntek"))
        assertEquals(HeadUnitPlatform.MTC, detect(hardware = "mt8163", board = "MTCE_PX6"))
    }

    @Test
    fun anythingElseIsGeneric() {
        assertEquals(HeadUnitPlatform.GENERIC, detect())
        assertEquals(HeadUnitPlatform.GENERIC, detect("ranchu", "goldfish_x86", "Google"))
        assertEquals(HeadUnitPlatform.GENERIC, detect("mt8168", "rk3399", "Allwinner", "com.example.app"))
    }
}
