package nl.stijnvdpol.klinedash.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

/** Minutes in a day; schedule times are minutes after midnight, 0 until this. */
const val MINUTES_PER_DAY = 24 * 60

private const val DEFAULT_DAY_START = 7 * 60
private const val DEFAULT_NIGHT_START = 19 * 60
private const val RGB_MASK = 0xFFFFFF
private const val HEX_RADIX = 16
private const val HEX_DIGITS = 6

/** How the theme follows the time of day. */
enum class DayNightMode { MANUAL, SCHEDULE, FOLLOW_SYSTEM }

/**
 * How the Main page draws engine speed, road speed and coolant (docs/cluster-styles.md). [id] is
 * what a theme file and the settings store; the names the user sees are string resources.
 */
enum class ClusterStyle(val id: String) {
    BAR("bar"),
    TWIN("twin"),
    CENTER("center"),
    ARC("arc"),
    VIRTUAL("virtual"),
    ;

    companion object {
        fun fromId(id: String): ClusterStyle? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What the user chose under SETUP > Display. Theme ids are those of the theme catalogue
 * ("race", "user:my-theme.json"). Used as is in [DayNightMode.MANUAL] ([manualThemeId]); the two
 * automatic modes switch between [dayThemeId] and [nightThemeId]. In SCHEDULE the day theme runs
 * from [dayStartMinute] up to [nightStartMinute], across midnight when the night starts earlier in
 * the clock than the day. [accentOverride] is an RGB value (0xRRGGBB) or null for the theme's own;
 * [clusterOverride] is one cluster style for every theme, or null to follow the active theme's own.
 */
data class ThemeSettings(
    val mode: DayNightMode = DayNightMode.MANUAL,
    val manualThemeId: String = "race",
    val dayThemeId: String = "day",
    val nightThemeId: String = "race",
    val dayStartMinute: Int = DEFAULT_DAY_START,
    val nightStartMinute: Int = DEFAULT_NIGHT_START,
    val accentOverride: Int? = null,
    val clusterOverride: ClusterStyle? = null,
)

/** Persists [ThemeSettings] under "theme.*" keys of the settings table. */
class ThemeSettingsRepository(dao: SettingsDao, decodeDispatcher: CoroutineDispatcher = Dispatchers.Default) {
    private val store = TypedSettings(dao, ::decodeThemeSettings, ::encodeThemeSettings, decodeDispatcher)

    val settings: Flow<ThemeSettings> = store.flow

    suspend fun update(transform: (ThemeSettings) -> ThemeSettings) = store.update(transform)
}

internal object ThemeSettingsKeys {
    const val MODE = "theme.mode"
    const val MANUAL = "theme.manual"
    const val DAY = "theme.day"
    const val NIGHT = "theme.night"
    const val DAY_START = "theme.dayStart"
    const val NIGHT_START = "theme.nightStart"
    const val ACCENT = "theme.accent"
    const val CLUSTER = "theme.cluster"
}

internal fun decodeThemeSettings(rows: Map<String, String>): ThemeSettings {
    val d = ThemeSettings()
    return ThemeSettings(
        mode = rows.parsed(ThemeSettingsKeys.MODE, d.mode) { name -> DayNightMode.entries.firstOrNull { it.name == name } },
        manualThemeId = rows.parsed(ThemeSettingsKeys.MANUAL, d.manualThemeId) { it.takeIf(String::isNotBlank) },
        dayThemeId = rows.parsed(ThemeSettingsKeys.DAY, d.dayThemeId) { it.takeIf(String::isNotBlank) },
        nightThemeId = rows.parsed(ThemeSettingsKeys.NIGHT, d.nightThemeId) { it.takeIf(String::isNotBlank) },
        dayStartMinute = rows.parsed(ThemeSettingsKeys.DAY_START, d.dayStartMinute, ::parseMinuteOfDay),
        nightStartMinute = rows.parsed(ThemeSettingsKeys.NIGHT_START, d.nightStartMinute, ::parseMinuteOfDay),
        accentOverride = rows.parsed<Int?>(ThemeSettingsKeys.ACCENT, null, ::parseRgb),
        clusterOverride = rows.parsed<ClusterStyle?>(ThemeSettingsKeys.CLUSTER, null, ClusterStyle::fromId),
    )
}

internal fun encodeThemeSettings(s: ThemeSettings): Map<String, String?> = mapOf(
    ThemeSettingsKeys.MODE to s.mode.name,
    ThemeSettingsKeys.MANUAL to s.manualThemeId,
    ThemeSettingsKeys.DAY to s.dayThemeId,
    ThemeSettingsKeys.NIGHT to s.nightThemeId,
    ThemeSettingsKeys.DAY_START to s.dayStartMinute.toString(),
    ThemeSettingsKeys.NIGHT_START to s.nightStartMinute.toString(),
    ThemeSettingsKeys.ACCENT to s.accentOverride?.let(::formatRgb),
    ThemeSettingsKeys.CLUSTER to s.clusterOverride?.id,
)

private fun parseMinuteOfDay(raw: String): Int? = raw.toIntOrNull()?.takeIf { it in 0 until MINUTES_PER_DAY }

private fun parseRgb(raw: String): Int? =
    raw.takeIf { it.length == HEX_DIGITS }?.toIntOrNull(HEX_RADIX)?.takeIf { it in 0..RGB_MASK }

private fun formatRgb(rgb: Int): String = (rgb and RGB_MASK).toString(HEX_RADIX).padStart(HEX_DIGITS, '0')
