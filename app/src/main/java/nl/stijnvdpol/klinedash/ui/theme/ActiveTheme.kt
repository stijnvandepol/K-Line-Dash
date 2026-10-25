package nl.stijnvdpol.klinedash.ui.theme

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import nl.stijnvdpol.klinedash.appGraph
import nl.stijnvdpol.klinedash.data.DayNightMode
import nl.stijnvdpol.klinedash.data.MINUTES_PER_DAY
import nl.stijnvdpol.klinedash.data.ThemeSettings
import java.time.LocalTime
import java.util.Locale

/** Schedule times move in steps of this many minutes. */
const val SCHEDULE_STEP_MINUTES = 15
private const val MINUTES_PER_HOUR = 60
private const val MILLIS_PER_MINUTE = 60_000L

/** Which theme applies right now: the pure rule behind day/night switching. */
fun activeThemeId(settings: ThemeSettings, minuteOfDay: Int, isSystemNight: Boolean): String = when (settings.mode) {
    DayNightMode.MANUAL -> settings.manualThemeId
    DayNightMode.FOLLOW_SYSTEM -> if (isSystemNight) settings.nightThemeId else settings.dayThemeId
    DayNightMode.SCHEDULE -> if (isDaytime(settings, minuteOfDay)) settings.dayThemeId else settings.nightThemeId
}

/**
 * Day runs from the day start (inclusive) to the night start (exclusive). When the night starts
 * earlier on the clock than the day, the night runs across midnight, so day is the time outside
 * [night start, day start). Equal times leave no night: it is day all the time.
 */
private fun isDaytime(settings: ThemeSettings, minuteOfDay: Int): Boolean {
    val dayStart = settings.dayStartMinute
    val nightStart = settings.nightStartMinute
    return when {
        dayStart < nightStart -> minuteOfDay in dayStart until nightStart
        dayStart > nightStart -> minuteOfDay >= dayStart || minuteOfDay < nightStart
        else -> true
    }
}

/**
 * [current] one schedule step further ([direction] 1) or back (-1), wrapping around midnight and
 * jumping over [other], because equal start times would leave no night.
 */
fun steppedMinute(current: Int, other: Int, direction: Int): Int {
    var next = Math.floorMod(current + direction * SCHEDULE_STEP_MINUTES, MINUTES_PER_DAY)
    if (next == other) next = Math.floorMod(next + direction * SCHEDULE_STEP_MINUTES, MINUTES_PER_DAY)
    return next
}

/** "07:00": a time of day for the schedule steppers, 24-hour whatever the system locale. */
fun formatMinuteOfDay(minuteOfDay: Int): String =
    String.format(Locale.US, "%02d:%02d", minuteOfDay / MINUTES_PER_HOUR, minuteOfDay % MINUTES_PER_HOUR)

/** How long until the clock shows the next whole minute, so the schedule is checked once a minute and on time. */
fun millisUntilNextMinute(nowMs: Long): Long = MILLIS_PER_MINUTE - Math.floorMod(nowMs, MILLIS_PER_MINUTE)

/**
 * The theme in effect: the one [activeThemeId] names (the default theme when that one no longer
 * exists, e.g. a deleted file) with the user's accent and cluster overrides applied. The needle
 * follows an accent override unless the theme gave it a colour of its own.
 */
fun resolveTheme(settings: ThemeSettings, catalog: ThemeCatalog, minuteOfDay: Int, isSystemNight: Boolean): ThemeDefinition {
    val base = catalog.find(activeThemeId(settings, minuteOfDay, isSystemNight))
        ?: catalog.find(DEFAULT_THEME_ID)
        ?: ThemeDefinition(DEFAULT_THEME_ID, "Race", RaceColors, isBuiltIn = true)
    val withCluster = settings.clusterOverride?.let { base.copy(look = base.look.copy(style = it)) } ?: base
    val accent = settings.accentOverride?.let(::rgbColor) ?: return withCluster
    val colors = withCluster.colors
    val needle = if (colors.needle == colors.accent) accent else colors.needle
    return withCluster.copy(colors = colors.copy(accent = accent, needle = needle))
}

/** Name of the theme in effect, for the Display section ("Now showing"). */
val LocalActiveThemeName = compositionLocalOf { "" }

private fun currentMinuteOfDay(): Int = LocalTime.now().let { it.hour * MINUTES_PER_HOUR + it.minute }

/**
 * The minute of the day, updated exactly when the minute changes while [isTicking]; not ticking
 * costs nothing. Sleeping until the next minute keeps this from being a polling loop.
 */
@Composable
private fun rememberMinuteOfDay(isTicking: Boolean): State<Int> {
    val minuteOfDay = remember { mutableIntStateOf(currentMinuteOfDay()) }
    LaunchedEffect(isTicking) {
        minuteOfDay.intValue = currentMinuteOfDay()
        while (isTicking) {
            delay(millisUntilNextMinute(System.currentTimeMillis()))
            minuteOfDay.intValue = currentMinuteOfDay()
        }
    }
    return minuteOfDay
}

/**
 * Applies the theme in effect to [content] as soon as the settings and the theme files are
 * loaded (nothing is drawn before that, so no wrong theme flashes by). Both are read ahead from
 * KLineDashApp.onCreate (see AppGraph.warmUp), so they are normally there in the first composition. Follows the settings, the
 * clock (schedule mode) and the system night flag (the activity handles `uiMode` itself, so the
 * configuration changes under it without a restart).
 */
@Composable
fun ActiveThemeHost(content: @Composable () -> Unit) {
    val graph = LocalContext.current.appGraph
    val settings by graph.themeSettings.collectAsStateWithLifecycle()
    val catalog by graph.themeCatalog.catalog.collectAsStateWithLifecycle()
    val isSystemNight = LocalConfiguration.current.isNight()
    val minuteOfDay by rememberMinuteOfDay(settings?.mode == DayNightMode.SCHEDULE)
    val loadedSettings = settings ?: return
    val loadedCatalog = catalog ?: return
    val theme = remember(loadedSettings, loadedCatalog, minuteOfDay, isSystemNight) {
        resolveTheme(loadedSettings, loadedCatalog, minuteOfDay, isSystemNight)
    }
    CompositionLocalProvider(LocalActiveThemeName provides theme.name, LocalClusterLook provides theme.look) {
        KLineDashTheme(theme.colors, content)
    }
}

private fun Configuration.isNight(): Boolean = (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
