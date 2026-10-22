package nl.stijnvdpol.klinedash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeSettingsPersistenceTest {
    @Test
    fun missingRowsGiveTheDefaults() {
        assertEquals(ThemeSettings(), decodeThemeSettings(emptyMap()))
    }

    @Test
    fun settingsSurviveAnEncodeDecodeRoundTrip() {
        val custom = ThemeSettings(
            mode = DayNightMode.SCHEDULE,
            manualThemeId = "user:mine.json",
            dayThemeId = "minimal",
            nightThemeId = "retro",
            dayStartMinute = 6 * 60 + 45,
            nightStartMinute = 22 * 60 + 15,
            accentOverride = 0x0D9488,
            clusterOverride = ClusterStyle.ARC,
        )
        val rows = encodeThemeSettings(custom).filterValues { it != null }.mapValues { it.value!! }
        assertEquals(custom, decodeThemeSettings(rows))
    }

    @Test
    fun noAccentOverrideRemovesItsRow() {
        assertNull(encodeThemeSettings(ThemeSettings(accentOverride = null))[ThemeSettingsKeys.ACCENT])
        assertEquals("000000", encodeThemeSettings(ThemeSettings(accentOverride = 0))[ThemeSettingsKeys.ACCENT])
    }

    @Test
    fun theClusterOverrideIsStoredByIdAndFollowsTheThemeWhenAbsent() {
        assertNull(encodeThemeSettings(ThemeSettings())[ThemeSettingsKeys.CLUSTER])
        assertEquals("virtual", encodeThemeSettings(ThemeSettings(clusterOverride = ClusterStyle.VIRTUAL))[ThemeSettingsKeys.CLUSTER])
        assertNull(decodeThemeSettings(emptyMap()).clusterOverride)
    }

    @Test
    fun aClusterIdThisVersionDoesNotKnowFallsBackToTheThemesOwn() {
        assertNull(decodeThemeSettings(mapOf(ThemeSettingsKeys.CLUSTER to "hologram")).clusterOverride)
    }

    @Test
    fun unreadableRowsFallBackToTheDefaultOfThatRowOnly() {
        val rows = mapOf(
            ThemeSettingsKeys.MODE to "SUNSET",
            ThemeSettingsKeys.DAY_START to "1440",
            ThemeSettingsKeys.NIGHT_START to "-5",
            ThemeSettingsKeys.ACCENT to "red",
            ThemeSettingsKeys.MANUAL to "  ",
            ThemeSettingsKeys.DAY to "minimal",
        )
        assertEquals(ThemeSettings(dayThemeId = "minimal"), decodeThemeSettings(rows))
    }
}
