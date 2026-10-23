package nl.stijnvdpol.klinedash.ui

import nl.stijnvdpol.klinedash.core.car.CarMake
import nl.stijnvdpol.klinedash.core.car.EngineNotes
import nl.stijnvdpol.klinedash.data.CarSettings
import nl.stijnvdpol.klinedash.data.MakeChoice
import org.junit.Assert.assertEquals
import org.junit.Test

class CarPresentationTest {

    @Test
    fun `without a VIN the automatic row says so`() {
        assertEquals(AutomaticDetection.NoVin, automaticDetectionOf(CarSettings()))
    }

    @Test
    fun `a VIN of a known make names the make and only the maker characters of the VIN`() {
        val detection = automaticDetectionOf(CarSettings(detectedVin = "WVWZZZ1JZXW000001"))
        assertEquals(AutomaticDetection.Detected(CarMake.VW_GROUP, "WVW"), detection)
    }

    @Test
    fun `a VIN of another make says so`() {
        val detection = automaticDetectionOf(CarSettings(detectedVin = "WP0ZZZ99ZTS392124"))
        assertEquals(AutomaticDetection.OtherMake("WP0"), detection)
    }

    @Test
    fun `the automatic row keeps reporting the detection while a make is chosen by hand`() {
        val settings = CarSettings(makeChoice = MakeChoice.Specific(CarMake.FORD), detectedVin = "WVWZZZ1JZXW000001")
        assertEquals(AutomaticDetection.Detected(CarMake.VW_GROUP, "WVW"), automaticDetectionOf(settings))
    }

    @Test
    fun `the 2ZZ-GE switch is offered for a Toyota, however it was found`() {
        assertEquals(listOf(EngineNotes.TOYOTA_2ZZ_GE), engineNotesChoices(CarSettings(makeChoice = MakeChoice.Specific(CarMake.TOYOTA_LEXUS))))
        assertEquals(listOf(EngineNotes.TOYOTA_2ZZ_GE), engineNotesChoices(CarSettings(detectedVin = "SB1ZE22E10E123456")))
    }

    @Test
    fun `no engine notes are offered for another make or an unknown one`() {
        assertEquals(emptyList<EngineNotes>(), engineNotesChoices(CarSettings()))
        assertEquals(emptyList<EngineNotes>(), engineNotesChoices(CarSettings(makeChoice = MakeChoice.Other)))
        assertEquals(emptyList<EngineNotes>(), engineNotesChoices(CarSettings(makeChoice = MakeChoice.Specific(CarMake.HONDA_ACURA))))
        assertEquals(emptyList<EngineNotes>(), engineNotesChoices(CarSettings(makeChoice = MakeChoice.Other, detectedVin = "SB1ZE22E10E123456")))
    }
}
