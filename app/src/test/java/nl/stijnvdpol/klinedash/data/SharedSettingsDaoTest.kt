package nl.stijnvdpol.klinedash.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.stijnvdpol.klinedash.core.car.CarMake
import nl.stijnvdpol.klinedash.core.car.EngineNotes
import nl.stijnvdpol.klinedash.service.FakeSettingsDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Counts how often the table is observed, i.e. how many `SELECT * FROM settings` flows are started. */
private class CountingSettingsDao(private val inner: SettingsDao) : SettingsDao by inner {
    var observers = 0
        private set

    override fun observeAll(): Flow<List<SettingEntry>> = inner.observeAll().onStart { observers++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SharedSettingsDaoTest {
    private val fake = FakeSettingsDao()
    private val counting = CountingSettingsDao(fake)

    @Test
    fun `every repository reads the table through one query`() = runTest {
        val shared = SharedSettingsDao(counting, backgroundScope)
        val general = GeneralSettingsRepository(shared, Dispatchers.Unconfined)
        val theme = ThemeSettingsRepository(shared, Dispatchers.Unconfined)
        val gps = GpsSettingsRepository(shared, Dispatchers.Unconfined)
        val connection = SettingsRepository(shared, Dispatchers.Unconfined)
        val car = CarSettingsRepository(shared, Dispatchers.Unconfined)
        val layout = DashboardLayoutRepository(shared, Dispatchers.Unconfined)

        assertEquals(GeneralSettings(), general.settings.first())
        assertEquals(ThemeSettings(), theme.settings.first())
        assertEquals(GpsSettings(), gps.settings.first())
        assertEquals(ConnectionSettings(), connection.connectionSettings.first())
        assertEquals(CarSettings(), car.settings.first())
        assertEquals(DashboardLayout.Default, layout.layout.first())

        assertEquals(1, counting.observers)
    }

    @Test
    fun `a repository that starts late gets the rows that were already read`() = runTest {
        fake.upsert(SettingEntry(GeneralSettingsKeys.UNITS, UnitSystem.IMPERIAL.name))
        val shared = SharedSettingsDao(counting, backgroundScope)
        runCurrent()

        val general = GeneralSettingsRepository(shared, Dispatchers.Unconfined)

        assertEquals(UnitSystem.IMPERIAL, general.settings.first().unitSystem)
        assertEquals(1, counting.observers)
    }

    @Test
    fun `each repository sees its own updates, the others stay as they were`() = runTest {
        val shared = SharedSettingsDao(counting, backgroundScope)
        val general = GeneralSettingsRepository(shared, Dispatchers.Unconfined)
        val theme = ThemeSettingsRepository(shared, Dispatchers.Unconfined)
        val generalSeen = mutableListOf<GeneralSettings>()
        val themeSeen = mutableListOf<ThemeSettings>()
        backgroundScope.launch { general.settings.collect { generalSeen += it } }
        backgroundScope.launch { theme.settings.collect { themeSeen += it } }
        runCurrent()

        general.update { it.copy(unitSystem = UnitSystem.IMPERIAL) }
        runCurrent()
        theme.update { it.copy(manualThemeId = "rosso") }
        runCurrent()

        assertEquals(
            listOf(GeneralSettings(), GeneralSettings(unitSystem = UnitSystem.IMPERIAL)),
            generalSeen,
        )
        assertEquals(listOf(ThemeSettings(), ThemeSettings(manualThemeId = "rosso")), themeSeen)
        assertEquals(1, counting.observers)
    }

    @Test
    fun `the 1_1_0 car profile is migrated before anybody reads the table`() = runTest {
        fake.upsert(SettingEntry("car.engineProfile", "TOYOTA_2ZZ_GE"))
        lateinit var car: CarSettingsRepository
        val shared = SharedSettingsDao(counting, backgroundScope) { car.migrateEngineProfile() }
        car = CarSettingsRepository(shared, Dispatchers.Unconfined)
        val general = GeneralSettingsRepository(shared, Dispatchers.Unconfined)
        val tablesSeen = mutableListOf<List<SettingEntry>>()
        backgroundScope.launch { shared.observeAll().collect { tablesSeen += it } }
        backgroundScope.launch { general.settings.first() }
        runCurrent()

        assertTrue("The table was read", tablesSeen.isNotEmpty())
        assertFalse("No reader saw the old row", tablesSeen.any { rows -> rows.any { it.key == "car.engineProfile" } })
        val settings = car.settings.first()
        assertEquals(MakeChoice.Specific(CarMake.TOYOTA_LEXUS), settings.makeChoice)
        assertEquals(EngineNotes.TOYOTA_2ZZ_GE, settings.engineNotes)
    }
}
