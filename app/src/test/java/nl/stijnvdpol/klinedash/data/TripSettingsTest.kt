package nl.stijnvdpol.klinedash.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nl.stijnvdpol.klinedash.service.FakeSettingsDao
import nl.stijnvdpol.klinedash.ui.canStepKeepTrips
import nl.stijnvdpol.klinedash.ui.steppedKeepTrips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripRetentionSettingTest {
    @Test
    fun theDefaultKeepsWhatTheListShowedBeforeTheLimitExisted() = runBlocking {
        val repository = TripSettingsRepository(FakeSettingsDao(), Dispatchers.Unconfined)
        assertEquals(500, repository.settings.first().keepTrips)
        assertTrue(DEFAULT_KEEP_TRIPS in KEEP_TRIPS_CHOICES)
    }

    @Test
    fun theChoiceIsPersistedAndTheOtherOptionsAreUntouched() = runBlocking {
        val dao = FakeSettingsDao()
        TripSettingsRepository(dao, Dispatchers.Unconfined).update { it.copy(keepTrips = 100) }

        val reopened = TripSettingsRepository(dao, Dispatchers.Unconfined).settings.first()

        assertEquals(100, reopened.keepTrips)
        assertTrue(reopened.autoRecord)
        assertFalse(reopened.autoStartWhenMoving)
    }

    @Test
    fun aStoredValueThatIsNotOneOfTheChoicesFallsBackToTheDefault() = runBlocking {
        for (stored in listOf("3", "0", "-5", "251", "many", "")) {
            val dao = FakeSettingsDao()
            dao.upsert(SettingEntry("trip.keepTrips", stored))
            assertEquals(stored, DEFAULT_KEEP_TRIPS, TripSettingsRepository(dao, Dispatchers.Unconfined).settings.first().keepTrips)
        }
    }

    @Test
    fun theStepperMovesThroughTheChoicesAndStopsAtBothEnds() {
        assertEquals(listOf(50, 100, 250, 500), KEEP_TRIPS_CHOICES)
        assertEquals(250, steppedKeepTrips(100, 1))
        assertEquals(100, steppedKeepTrips(250, -1))
        assertEquals(500, steppedKeepTrips(500, 1))
        assertEquals(50, steppedKeepTrips(50, -1))
        assertFalse(canStepKeepTrips(500, 1))
        assertTrue(canStepKeepTrips(500, -1))
        assertFalse(canStepKeepTrips(50, -1))
        assertTrue(canStepKeepTrips(50, 1))
    }
}
