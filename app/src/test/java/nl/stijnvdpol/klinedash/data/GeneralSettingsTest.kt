package nl.stijnvdpol.klinedash.data

import nl.stijnvdpol.klinedash.core.transport.TransportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneralSettingsPersistenceTest {
    @Test
    fun `a fresh install is metric and has not seen the setup`() {
        val defaults = decodeGeneralSettings(emptyMap())
        assertEquals(UnitSystem.METRIC, defaults.unitSystem)
        assertFalse(defaults.setupCompleted)
    }

    @Test
    fun `the choices survive an encode and decode round trip`() {
        val custom = GeneralSettings(unitSystem = UnitSystem.IMPERIAL, setupCompleted = true)
        val rows = encodeGeneralSettings(custom).filterValues { it != null }.mapValues { it.value!! }
        assertEquals(custom, decodeGeneralSettings(rows))
    }

    @Test
    fun `an unreadable row falls back to the default of that row only`() {
        val rows = mapOf(GeneralSettingsKeys.UNITS to "FURLONGS", GeneralSettingsKeys.SETUP_COMPLETED to "true")
        assertEquals(GeneralSettings(unitSystem = UnitSystem.METRIC, setupCompleted = true), decodeGeneralSettings(rows))
        val other = mapOf(GeneralSettingsKeys.UNITS to "IMPERIAL", GeneralSettingsKeys.SETUP_COMPLETED to "maybe")
        assertEquals(GeneralSettings(unitSystem = UnitSystem.IMPERIAL, setupCompleted = false), decodeGeneralSettings(other))
    }
}

class ConnectionSettingsAdapterTest {
    @Test
    fun `a fresh install has no adapter, so the dashboard shows its invitation`() {
        assertFalse(ConnectionSettings().hasAdapter)
    }

    @Test
    fun `the simulator is always an adapter`() {
        assertTrue(ConnectionSettings(kind = TransportKind.SIMULATOR).hasAdapter)
    }

    @Test
    fun `a Bluetooth adapter needs a chosen device of its own kind`() {
        assertFalse(ConnectionSettings(kind = TransportKind.BT_CLASSIC, bleAddress = "AA:BB").hasAdapter)
        assertTrue(ConnectionSettings(kind = TransportKind.BT_CLASSIC, btAddress = "AA:BB").hasAdapter)
        assertFalse(ConnectionSettings(kind = TransportKind.BLE, btAddress = "AA:BB").hasAdapter)
        assertTrue(ConnectionSettings(kind = TransportKind.BLE, bleAddress = "AA:BB").hasAdapter)
    }

    @Test
    fun `Wi-Fi needs an address, and starts with the usual one`() {
        assertTrue(ConnectionSettings(kind = TransportKind.WIFI).hasAdapter)
        assertFalse(ConnectionSettings(kind = TransportKind.WIFI, wifiHost = "  ").hasAdapter)
    }
}
