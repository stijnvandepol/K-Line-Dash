package nl.stijnvdpol.klinedash.ui.theme

import androidx.compose.ui.graphics.Color
import nl.stijnvdpol.klinedash.data.ClusterStyle
import nl.stijnvdpol.klinedash.data.DayNightMode
import nl.stijnvdpol.klinedash.data.ThemeSettings
import org.junit.Assert.assertEquals
import org.junit.Test

private fun at(hour: Int, minute: Int = 0) = hour * 60 + minute

class ActiveThemeTest {
    private val schedule = ThemeSettings(
        mode = DayNightMode.SCHEDULE, dayThemeId = "day", nightThemeId = "night",
        dayStartMinute = at(7), nightStartMinute = at(19),
    )

    @Test
    fun manualModeAlwaysUsesTheChosenTheme() {
        val settings = ThemeSettings(mode = DayNightMode.MANUAL, manualThemeId = "retro")
        for (minute in listOf(0, at(12), at(23, 59))) {
            assertEquals("retro", activeThemeId(settings, minute, isSystemNight = false))
            assertEquals("retro", activeThemeId(settings, minute, isSystemNight = true))
        }
    }

    @Test
    fun followHeadUnitUsesTheNightFlagAndIgnoresTheClock() {
        val settings = schedule.copy(mode = DayNightMode.FOLLOW_SYSTEM)
        assertEquals("night", activeThemeId(settings, at(12), isSystemNight = true))
        assertEquals("day", activeThemeId(settings, at(2), isSystemNight = false))
    }

    @Test
    fun scheduleIgnoresTheNightFlag() {
        assertEquals("day", activeThemeId(schedule, at(12), isSystemNight = true))
        assertEquals("night", activeThemeId(schedule, at(23), isSystemNight = false))
    }

    @Test
    fun daytimeStartsAtTheDayTimeAndEndsAtTheNightTime() {
        assertEquals("night", activeThemeId(schedule, at(6, 59), false))
        assertEquals("day", activeThemeId(schedule, at(7, 0), false))
        assertEquals("day", activeThemeId(schedule, at(18, 59), false))
        assertEquals("night", activeThemeId(schedule, at(19, 0), false))
        assertEquals("night", activeThemeId(schedule, 0, false))
        assertEquals("night", activeThemeId(schedule, at(23, 59), false))
    }

    @Test
    fun aScheduleWhoseDayRunsPastMidnightWrapsAround() {
        // Day 14:00 to 02:00 (a night-shift driver): the "night" is 02:00 to 14:00.
        val wrapping = schedule.copy(dayStartMinute = at(14), nightStartMinute = at(2))
        assertEquals("day", activeThemeId(wrapping, at(23, 30), false))
        assertEquals("day", activeThemeId(wrapping, 0, false))
        assertEquals("day", activeThemeId(wrapping, at(1, 59), false))
        assertEquals("night", activeThemeId(wrapping, at(2, 0), false))
        assertEquals("night", activeThemeId(wrapping, at(13, 59), false))
        assertEquals("day", activeThemeId(wrapping, at(14, 0), false))
    }

    @Test
    fun aNightAcrossMidnightAndADayAcrossMidnightAreMirrorImages() {
        // Day 06:00, night 22:00: the night crosses midnight but the day does not...
        val ordinary = schedule.copy(dayStartMinute = at(6), nightStartMinute = at(22))
        assertEquals("night", activeThemeId(ordinary, at(23), false))
        assertEquals("night", activeThemeId(ordinary, at(5, 59), false))
        assertEquals("day", activeThemeId(ordinary, at(6), false))
        // ...day 22:00, night 06:00: the day crosses midnight.
        val wrapping = schedule.copy(dayStartMinute = at(22), nightStartMinute = at(6))
        assertEquals("day", activeThemeId(wrapping, at(23), false))
        assertEquals("day", activeThemeId(wrapping, at(5, 59), false))
        assertEquals("night", activeThemeId(wrapping, at(6), false))
    }

    @Test
    fun aDayStartingAtMidnightIsOrdinary() {
        val fromMidnight = schedule.copy(dayStartMinute = 0, nightStartMinute = at(12))
        assertEquals("day", activeThemeId(fromMidnight, 0, false))
        assertEquals("night", activeThemeId(fromMidnight, at(12), false))
        assertEquals("night", activeThemeId(fromMidnight, at(23, 59), false))
    }

    @Test
    fun equalTimesMeanNoNightAtAll() {
        val same = schedule.copy(dayStartMinute = at(8), nightStartMinute = at(8))
        for (minute in listOf(0, at(8), at(20))) assertEquals("day", activeThemeId(same, minute, false))
    }

    @Test
    fun steppingMovesByFifteenMinutesBothWays() {
        assertEquals(at(7, 15), steppedMinute(at(7), other = at(19), direction = 1))
        assertEquals(at(6, 45), steppedMinute(at(7), other = at(19), direction = -1))
    }

    @Test
    fun steppingWrapsAroundMidnight() {
        assertEquals(0, steppedMinute(at(23, 45), other = at(7), direction = 1))
        assertEquals(at(23, 45), steppedMinute(0, other = at(7), direction = -1))
    }

    @Test
    fun steppingJumpsOverTheOtherTime() {
        assertEquals(at(19, 30), steppedMinute(at(19, 0), other = at(19, 15), direction = 1))
        assertEquals(at(18, 30), steppedMinute(at(19, 0), other = at(18, 45), direction = -1))
    }

    @Test
    fun timesAreShownAs24HourClock() {
        assertEquals("00:00", formatMinuteOfDay(0))
        assertEquals("07:05", formatMinuteOfDay(at(7, 5)))
        assertEquals("23:45", formatMinuteOfDay(at(23, 45)))
    }

    @Test
    fun theNextCheckIsExactlyAtTheNextWholeMinute() {
        assertEquals(60_000L, millisUntilNextMinute(0L))
        assertEquals(1L, millisUntilNextMinute(59_999L))
        assertEquals(30_000L, millisUntilNextMinute(120_030_000L))
    }
}

class ResolveThemeTest {
    private fun theme(id: String, accent: Color = Color.White) =
        ThemeDefinition(id, id.uppercase(), RaceColors.copy(accent = accent), isBuiltIn = true)

    private val catalog = ThemeCatalog(listOf(theme("race"), theme("day", Color.Black)), emptyList(), "")

    @Test
    fun theActiveThemeOfTheCatalogIsReturned() {
        val settings = ThemeSettings(mode = DayNightMode.FOLLOW_SYSTEM, dayThemeId = "day", nightThemeId = "race")
        assertEquals("day", resolveTheme(settings, catalog, 0, isSystemNight = false).id)
        assertEquals("race", resolveTheme(settings, catalog, 0, isSystemNight = true).id)
    }

    @Test
    fun aThemeThatNoLongerExistsFallsBackToRace() {
        val settings = ThemeSettings(manualThemeId = "user:deleted.json")
        assertEquals("race", resolveTheme(settings, catalog, 0, false).id)
    }

    @Test
    fun withoutAnyThemeLoadedTheCompiledRaceColoursApply() {
        val resolved = resolveTheme(ThemeSettings(), ThemeCatalog(emptyList(), emptyList(), ""), 0, false)
        assertEquals(RaceColors, resolved.colors)
    }

    @Test
    fun theAccentOverrideReplacesOnlyTheAccent() {
        val settings = ThemeSettings(manualThemeId = "day", accentOverride = 0x3B82F6)
        val resolved = resolveTheme(settings, catalog, 0, false)
        assertEquals(Color(0xFF3B82F6), resolved.colors.accent)
        assertEquals(RaceColors.ink, resolved.colors.ink)
    }

    @Test
    fun withoutAnOverrideTheThemeKeepsItsOwnAccent() {
        assertEquals(Color.Black, resolveTheme(ThemeSettings(manualThemeId = "day"), catalog, 0, false).colors.accent)
    }

    @Test
    fun theClusterOverrideReplacesTheClusterOfTheThemeAndNothingElse() {
        val own = ThemeDefinition("rosso", "Rosso", RaceColors, isBuiltIn = true, look = ClusterLook(ClusterStyle.CENTER, NumeralFace.LIGHT))
        val settings = ThemeSettings(manualThemeId = "rosso", clusterOverride = ClusterStyle.ARC)
        val catalog = ThemeCatalog(listOf(own), emptyList(), "")
        assertEquals(ClusterLook(ClusterStyle.ARC, NumeralFace.LIGHT), resolveTheme(settings, catalog, 0, false).look)
        assertEquals(ClusterStyle.CENTER, resolveTheme(settings.copy(clusterOverride = null), catalog, 0, false).look.style)
    }

    @Test
    fun theNeedleFollowsAnAccentOverrideUnlessTheThemeColouredItItself() {
        val following = ThemeDefinition("a", "A", RaceColors.copy(accent = Color.Red, needle = Color.Red), isBuiltIn = true)
        val own = ThemeDefinition("b", "B", RaceColors.copy(accent = Color.Red, needle = Color.Green), isBuiltIn = true)
        val catalog = ThemeCatalog(listOf(following, own), emptyList(), "")
        val blue = Color(0xFF3B82F6)
        assertEquals(blue, resolveTheme(ThemeSettings(manualThemeId = "a", accentOverride = 0x3B82F6), catalog, 0, false).colors.needle)
        assertEquals(Color.Green, resolveTheme(ThemeSettings(manualThemeId = "b", accentOverride = 0x3B82F6), catalog, 0, false).colors.needle)
    }
}
