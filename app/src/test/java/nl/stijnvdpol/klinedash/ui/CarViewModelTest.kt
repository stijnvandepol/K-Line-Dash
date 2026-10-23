package nl.stijnvdpol.klinedash.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import nl.stijnvdpol.klinedash.data.CarSettings
import nl.stijnvdpol.klinedash.data.CarSettingsRepository
import nl.stijnvdpol.klinedash.data.DEFAULT_REDLINE_RPM
import nl.stijnvdpol.klinedash.data.EngineProfile
import nl.stijnvdpol.klinedash.data.MAX_REDLINE_RPM
import nl.stijnvdpol.klinedash.data.MIN_REDLINE_RPM
import nl.stijnvdpol.klinedash.data.REDLINE_STEP_RPM
import nl.stijnvdpol.klinedash.data.TOYOTA_2ZZ_GE_REDLINE_RPM
import nl.stijnvdpol.klinedash.service.FakeSettingsDao
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CarViewModelTest {
    private val repository = CarSettingsRepository(FakeSettingsDao(), Dispatchers.Unconfined)
    private val customRedline = 7_000

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun stored(): CarSettings = runBlocking { repository.settings.first() }

    private fun storeRedline(rpm: Int) = runBlocking { repository.update { it.copy(redlineRpm = rpm) } }

    // --- the red line stepper ---------------------------------------------------------------------

    @Test
    fun `a step moves the red line by one step and is stored`() {
        val vm = CarViewModel(repository)
        vm.stepRedline(1)
        assertEquals(DEFAULT_REDLINE_RPM + REDLINE_STEP_RPM, stored().redlineRpm)
        vm.stepRedline(-1)
        vm.stepRedline(-1)
        assertEquals(DEFAULT_REDLINE_RPM - REDLINE_STEP_RPM, stored().redlineRpm)
    }

    @Test
    fun `the red line stops at both ends of its range`() {
        val vm = CarViewModel(repository)
        storeRedline(MAX_REDLINE_RPM)
        vm.stepRedline(1)
        assertEquals(MAX_REDLINE_RPM, stored().redlineRpm)
        storeRedline(MIN_REDLINE_RPM)
        vm.stepRedline(-1)
        assertEquals(MIN_REDLINE_RPM, stored().redlineRpm)
    }

    @Test
    fun `the stepper cells go faint exactly at the ends`() {
        assertTrue(canStepRedline(DEFAULT_REDLINE_RPM, 1))
        assertTrue(canStepRedline(DEFAULT_REDLINE_RPM, -1))
        assertFalse(canStepRedline(MAX_REDLINE_RPM, 1))
        assertTrue(canStepRedline(MAX_REDLINE_RPM, -1))
        assertFalse(canStepRedline(MIN_REDLINE_RPM, -1))
        assertTrue(canStepRedline(MIN_REDLINE_RPM, 1))
    }

    @Test
    fun `the range holds a whole number of steps, so the stepper never lands between two values`() {
        assertEquals(0, (MAX_REDLINE_RPM - MIN_REDLINE_RPM) % REDLINE_STEP_RPM)
        assertEquals(0, (DEFAULT_REDLINE_RPM - MIN_REDLINE_RPM) % REDLINE_STEP_RPM)
        assertEquals(0, (TOYOTA_2ZZ_GE_REDLINE_RPM - MIN_REDLINE_RPM) % REDLINE_STEP_RPM)
    }

    // --- the trouble code profile -----------------------------------------------------------------

    @Test
    fun `a fresh install is the generic profile with the default red line`() {
        assertEquals(CarSettings(DEFAULT_REDLINE_RPM, EngineProfile.GENERIC), stored())
    }

    @Test
    fun `choosing Toyota brings the 2ZZ-GE red line while the red line is still the default`() {
        CarViewModel(repository).chooseProfile(EngineProfile.TOYOTA_2ZZ_GE)
        assertEquals(CarSettings(TOYOTA_2ZZ_GE_REDLINE_RPM, EngineProfile.TOYOTA_2ZZ_GE), stored())
    }

    @Test
    fun `choosing Toyota keeps a red line the user has set`() {
        storeRedline(customRedline)
        CarViewModel(repository).chooseProfile(EngineProfile.TOYOTA_2ZZ_GE)
        assertEquals(CarSettings(customRedline, EngineProfile.TOYOTA_2ZZ_GE), stored())
    }

    @Test
    fun `choosing any car again keeps the red line`() {
        val vm = CarViewModel(repository)
        vm.chooseProfile(EngineProfile.TOYOTA_2ZZ_GE)
        vm.chooseProfile(EngineProfile.GENERIC)
        assertEquals(CarSettings(TOYOTA_2ZZ_GE_REDLINE_RPM, EngineProfile.GENERIC), stored())
    }

    @Test
    fun `the 2ZZ-GE red line is not brought a second time over a changed one`() {
        val vm = CarViewModel(repository)
        vm.chooseProfile(EngineProfile.TOYOTA_2ZZ_GE)
        vm.stepRedline(-1)
        vm.chooseProfile(EngineProfile.GENERIC)
        vm.chooseProfile(EngineProfile.TOYOTA_2ZZ_GE)
        assertEquals(TOYOTA_2ZZ_GE_REDLINE_RPM - REDLINE_STEP_RPM, stored().redlineRpm)
    }
}
