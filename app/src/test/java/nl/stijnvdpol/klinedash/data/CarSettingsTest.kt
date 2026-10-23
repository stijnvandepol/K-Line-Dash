package nl.stijnvdpol.klinedash.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nl.stijnvdpol.klinedash.core.car.CarMake
import nl.stijnvdpol.klinedash.core.car.EngineNotes
import nl.stijnvdpol.klinedash.service.FakeSettingsDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class CarSettingsTest {
    private val volkswagenVin = "WVWZZZ1JZXW000001"
    private val toyotaVin = "SB1ZE22E10E123456"
    private val porscheVin = "WP0ZZZ99ZTS392124"
    private val toyota = MakeChoice.Specific(CarMake.TOYOTA_LEXUS)

    // ---- which make applies ----

    @Test
    fun `automatic without a VIN is generic, a guess would show wrong meanings`() {
        assertNull(CarSettings().effectiveMake)
    }

    @Test
    fun `automatic uses the make the VIN points to`() {
        val settings = CarSettings(detectedVin = volkswagenVin)
        assertEquals(CarMake.VW_GROUP, settings.detectedMake)
        assertEquals(CarMake.VW_GROUP, settings.effectiveMake)
    }

    @Test
    fun `automatic with the VIN of a make outside the list is generic`() {
        val settings = CarSettings(detectedVin = porscheVin)
        assertNull(settings.detectedMake)
        assertNull(settings.effectiveMake)
    }

    @Test
    fun `a chosen make wins over the detection`() {
        assertEquals(CarMake.FORD, CarSettings(makeChoice = MakeChoice.Specific(CarMake.FORD), detectedVin = volkswagenVin).effectiveMake)
        assertEquals(CarMake.FORD, CarSettings(makeChoice = MakeChoice.Specific(CarMake.FORD)).effectiveMake)
    }

    @Test
    fun `other make is generic whatever the VIN says`() {
        assertNull(CarSettings(makeChoice = MakeChoice.Other, detectedVin = volkswagenVin).effectiveMake)
    }

    // ---- which engine notes apply ----

    @Test
    fun `engine notes apply while the effective make is theirs`() {
        assertEquals(EngineNotes.TOYOTA_2ZZ_GE, CarSettings(makeChoice = toyota, engineNotes = EngineNotes.TOYOTA_2ZZ_GE).effectiveEngineNotes)
        assertEquals(EngineNotes.TOYOTA_2ZZ_GE, CarSettings(detectedVin = toyotaVin, engineNotes = EngineNotes.TOYOTA_2ZZ_GE).effectiveEngineNotes)
    }

    @Test
    fun `engine notes are ignored for another make or a generic car`() {
        assertNull(CarSettings(makeChoice = MakeChoice.Specific(CarMake.HONDA_ACURA), engineNotes = EngineNotes.TOYOTA_2ZZ_GE).effectiveEngineNotes)
        assertNull(CarSettings(makeChoice = MakeChoice.Other, engineNotes = EngineNotes.TOYOTA_2ZZ_GE).effectiveEngineNotes)
        assertNull(CarSettings(engineNotes = EngineNotes.TOYOTA_2ZZ_GE).effectiveEngineNotes)
    }

    @Test
    fun `the identity pairs the effective make with its notes`() {
        val settings = CarSettings(makeChoice = toyota, engineNotes = EngineNotes.TOYOTA_2ZZ_GE)
        assertEquals(CarIdentity(CarMake.TOYOTA_LEXUS, EngineNotes.TOYOTA_2ZZ_GE), settings.identity)
        assertEquals(CarIdentity(null, null), CarSettings().identity)
    }

    // ---- reading a VIN ----

    @Test
    fun `a first VIN is kept`() {
        assertEquals(volkswagenVin, CarSettings().withVin(volkswagenVin).detectedVin)
    }

    @Test
    fun `the same VIN changes nothing`() {
        val settings = CarSettings(detectedVin = toyotaVin, engineNotes = EngineNotes.TOYOTA_2ZZ_GE)
        assertSame(settings, settings.withVin(toyotaVin))
    }

    @Test
    fun `another VIN replaces the detection, and with automatic the notes of the earlier car go`() {
        val settings = CarSettings(detectedVin = toyotaVin, engineNotes = EngineNotes.TOYOTA_2ZZ_GE).withVin(volkswagenVin)
        assertEquals(CarMake.VW_GROUP, settings.effectiveMake)
        assertNull(settings.engineNotes)
    }

    @Test
    fun `another VIN leaves a chosen make and its notes alone`() {
        val settings = CarSettings(makeChoice = toyota, detectedVin = toyotaVin, engineNotes = EngineNotes.TOYOTA_2ZZ_GE).withVin(volkswagenVin)
        assertEquals(CarMake.TOYOTA_LEXUS, settings.effectiveMake)
        assertEquals(EngineNotes.TOYOTA_2ZZ_GE, settings.engineNotes)
        assertEquals(volkswagenVin, settings.detectedVin)
    }

    @Test
    fun `something that is not a VIN is refused`() {
        assertThrows(IllegalArgumentException::class.java) { CarSettings().withVin("WVWZZZ1J") }
    }

    @Test
    fun `a car without a VIN drops the detection of an earlier car, not a chosen make`() {
        assertNull(CarSettings(detectedVin = toyotaVin).withoutVin().effectiveMake)
        val chosen = CarSettings(makeChoice = toyota, detectedVin = volkswagenVin, engineNotes = EngineNotes.TOYOTA_2ZZ_GE).withoutVin()
        assertEquals(CarMake.TOYOTA_LEXUS, chosen.effectiveMake)
        assertEquals(EngineNotes.TOYOTA_2ZZ_GE, chosen.effectiveEngineNotes)
        assertNull(chosen.detectedVin)
    }

    @Test
    fun `no VIN to drop changes nothing`() {
        val settings = CarSettings(engineNotes = EngineNotes.TOYOTA_2ZZ_GE)
        assertSame(settings, settings.withoutVin())
    }
}

class CarSettingsRepositoryTest {
    private val dao = FakeSettingsDao()
    private val repository = CarSettingsRepository(dao, Dispatchers.Unconfined)
    private val vin = "WVWZZZ1JZXW000001"

    private fun stored(): CarSettings = runBlocking { repository.settings.first() }

    private fun seed(key: String, value: String) = runBlocking { dao.upsert(SettingEntry(key, value)) }

    private fun row(key: String): String? = runBlocking { dao.get(key)?.value }

    @Test
    fun `a fresh install has the defaults`() {
        assertEquals(CarSettings(), stored())
    }

    @Test
    fun `every choice survives a restart`() {
        runBlocking {
            repository.update {
                it.copy(redlineRpm = 8_000, makeChoice = MakeChoice.Specific(CarMake.MAZDA), detectedVin = vin, engineNotes = EngineNotes.TOYOTA_2ZZ_GE)
            }
        }
        val afterRestart = CarSettingsRepository(dao, Dispatchers.Unconfined)
        assertEquals(
            CarSettings(8_000, MakeChoice.Specific(CarMake.MAZDA), vin, EngineNotes.TOYOTA_2ZZ_GE),
            runBlocking { afterRestart.settings.first() },
        )
    }

    @Test
    fun `automatic and other make are stored as such`() {
        runBlocking { repository.update { it.copy(makeChoice = MakeChoice.Other) } }
        assertEquals(MakeChoice.Other, stored().makeChoice)
        runBlocking { repository.update { it.copy(makeChoice = MakeChoice.Automatic) } }
        assertEquals(MakeChoice.Automatic, stored().makeChoice)
    }

    @Test
    fun `recording a VIN stores it, recording no VIN removes it`() {
        runBlocking { repository.recordVin(vin) }
        assertEquals(vin, stored().detectedVin)
        runBlocking { repository.recordNoVin() }
        assertNull(stored().detectedVin)
        assertNull(row("car.detectedVin"))
    }

    @Test
    fun `an unreadable row falls back to the default of that row only`() {
        seed("car.redlineRpm", "7500")
        seed("car.makeChoice", "DELOREAN")
        seed("car.engineNotes", "TOYOTA_2ZZ_GE")
        assertEquals(CarSettings(7_500, MakeChoice.Automatic, null, EngineNotes.TOYOTA_2ZZ_GE), stored())
    }

    @Test
    fun `a stored value that is not a VIN is dropped`() {
        seed("car.detectedVin", "not a vin")
        assertNull(stored().detectedVin)
    }

    // ---- the profile of release 1.1.0 ----

    @Test
    fun `the Toyota 2ZZ-GE profile of 1_1_0 becomes the Toyota make with the 2ZZ-GE notes`() {
        seed("car.redlineRpm", "8000")
        seed("car.engineProfile", "TOYOTA_2ZZ_GE")
        assertEquals(
            CarSettings(8_000, MakeChoice.Specific(CarMake.TOYOTA_LEXUS), null, EngineNotes.TOYOTA_2ZZ_GE),
            stored(),
        )
        assertNull("The old row is gone", row("car.engineProfile"))
    }

    @Test
    fun `the generic profile of 1_1_0 becomes automatic`() {
        seed("car.engineProfile", "GENERIC")
        assertEquals(CarSettings(), stored())
        assertNull(row("car.engineProfile"))
    }

    @Test
    fun `an old profile does not override a make that was chosen since`() {
        seed("car.engineProfile", "TOYOTA_2ZZ_GE")
        seed("car.makeChoice", "OTHER")
        assertEquals(MakeChoice.Other, stored().makeChoice)
        assertNull(stored().engineNotes)
    }

    @Test
    fun `switching the migrated notes off sticks`() {
        seed("car.engineProfile", "TOYOTA_2ZZ_GE")
        runBlocking { repository.update { it.copy(engineNotes = null) } }
        assertNull(stored().engineNotes)
        assertEquals(MakeChoice.Specific(CarMake.TOYOTA_LEXUS), stored().makeChoice)
    }

    @Test
    fun `a change made before the settings were ever read migrates first`() {
        seed("car.engineProfile", "TOYOTA_2ZZ_GE")
        runBlocking { repository.recordVin("SB1ZE22E10E123456") }
        val settings = stored()
        assertEquals(MakeChoice.Specific(CarMake.TOYOTA_LEXUS), settings.makeChoice)
        assertEquals(EngineNotes.TOYOTA_2ZZ_GE, settings.engineNotes)
        assertEquals("SB1ZE22E10E123456", settings.detectedVin)
    }

    @Test
    fun `reading the migrated settings again changes nothing`() {
        seed("car.engineProfile", "TOYOTA_2ZZ_GE")
        val first = stored()
        assertEquals(first, stored())
        assertEquals(first, runBlocking { CarSettingsRepository(dao, Dispatchers.Unconfined).settings.first() })
    }
}
