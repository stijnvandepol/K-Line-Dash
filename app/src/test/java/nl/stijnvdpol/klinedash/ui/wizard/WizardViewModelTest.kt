package nl.stijnvdpol.klinedash.ui.wizard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import nl.stijnvdpol.klinedash.core.transport.TransportKind
import nl.stijnvdpol.klinedash.data.ConnectionSettings
import nl.stijnvdpol.klinedash.data.GeneralSettingsRepository
import nl.stijnvdpol.klinedash.data.GpsSettingsRepository
import nl.stijnvdpol.klinedash.data.TripSettingsRepository
import nl.stijnvdpol.klinedash.service.FakeSettingsDao
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WizardViewModelTest {
    private val gpsSettings = GpsSettingsRepository(FakeSettingsDao(), Dispatchers.Unconfined)
    private val activeTrackId = 7L

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun wizard(): WizardViewModel {
        val dao = FakeSettingsDao()
        return WizardViewModel(
            hasPermissions = { true },
            connectionSettings = flowOf(ConnectionSettings()),
            general = GeneralSettingsRepository(dao, Dispatchers.Unconfined),
            tripSettings = TripSettingsRepository(dao, Dispatchers.Unconfined),
            gpsSettings = gpsSettings,
        )
    }

    private fun isSimulatedGpsOn(): Boolean = runBlocking { gpsSettings.settings.first().useSimulatedGps }

    @Test
    fun `a fresh install has the simulated circuit off`() {
        wizard()
        assertFalse(isSimulatedGpsOn())
    }

    @Test
    fun `choosing the simulator turns the simulated circuit on`() {
        wizard().onAdapterChosen(TransportKind.SIMULATOR)
        assertTrue(isSimulatedGpsOn())
    }

    @Test
    fun `choosing a real adapter after the simulator turns the circuit off again`() {
        val wizard = wizard()
        for (kind in listOf(TransportKind.BT_CLASSIC, TransportKind.BLE, TransportKind.WIFI)) {
            wizard.onAdapterChosen(TransportKind.SIMULATOR)
            assertTrue(isSimulatedGpsOn())
            wizard.onAdapterChosen(kind)
            assertFalse(kind.name, isSimulatedGpsOn())
        }
    }

    @Test
    fun `choosing a real adapter keeps the circuit off`() {
        wizard().onAdapterChosen(TransportKind.WIFI)
        assertFalse(isSimulatedGpsOn())
    }

    @Test
    fun `switching the circuit leaves the active track alone`() {
        runBlocking { gpsSettings.update { it.copy(activeTrackId = activeTrackId) } }
        wizard().onAdapterChosen(TransportKind.SIMULATOR)
        assertEquals(activeTrackId, runBlocking { gpsSettings.settings.first().activeTrackId })
    }
}
