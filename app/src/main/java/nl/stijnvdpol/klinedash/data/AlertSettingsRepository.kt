package nl.stijnvdpol.klinedash.data

import android.util.Log
import kotlinx.coroutines.flow.Flow
import nl.stijnvdpol.klinedash.core.alert.AlertKind
import nl.stijnvdpol.klinedash.core.alert.AlertSettings

private const val TAG = "AlertSettings"
private const val SEPARATOR = ","

/** Persists [AlertSettings] under "alerts.*" keys of the settings table. */
class AlertSettingsRepository(dao: SettingsDao) {
    private val store = TypedSettings(dao, ::decodeAlertSettings, ::encodeAlertSettings)

    val settings: Flow<AlertSettings> = store.flow

    suspend fun update(transform: (AlertSettings) -> AlertSettings) = store.update(transform)
}

internal object AlertSettingsKeys {
    const val ENABLED = "alerts.enabled"
    const val WITH_SOUND = "alerts.withSound"
    const val COOLANT_CAUTION = "alerts.coolantCautionC"
    const val COOLANT_DANGER = "alerts.coolantDangerC"
    const val VOLTAGE_RUNNING_LOW = "alerts.voltageRunningLow"
    const val VOLTAGE_RUNNING_HIGH = "alerts.voltageRunningHigh"
    const val VOLTAGE_ENGINE_OFF_LOW = "alerts.voltageEngineOffLow"
}

internal fun decodeAlertSettings(rows: Map<String, String>): AlertSettings {
    val d = AlertSettings()
    return AlertSettings(
        enabled = rows.parsed(AlertSettingsKeys.ENABLED, d.enabled, ::parseKinds),
        withSound = rows.parsed(AlertSettingsKeys.WITH_SOUND, d.withSound, ::parseKinds),
        coolantCautionC = rows.parsed(AlertSettingsKeys.COOLANT_CAUTION, d.coolantCautionC, ::parseFinite),
        coolantDangerC = rows.parsed(AlertSettingsKeys.COOLANT_DANGER, d.coolantDangerC, ::parseFinite),
        voltageRunningLow = rows.parsed(AlertSettingsKeys.VOLTAGE_RUNNING_LOW, d.voltageRunningLow, ::parseFinite),
        voltageRunningHigh = rows.parsed(AlertSettingsKeys.VOLTAGE_RUNNING_HIGH, d.voltageRunningHigh, ::parseFinite),
        voltageEngineOffLow = rows.parsed(AlertSettingsKeys.VOLTAGE_ENGINE_OFF_LOW, d.voltageEngineOffLow, ::parseFinite),
    )
}

internal fun encodeAlertSettings(s: AlertSettings): Map<String, String?> = mapOf(
    AlertSettingsKeys.ENABLED to formatKinds(s.enabled),
    AlertSettingsKeys.WITH_SOUND to formatKinds(s.withSound),
    AlertSettingsKeys.COOLANT_CAUTION to s.coolantCautionC.toString(),
    AlertSettingsKeys.COOLANT_DANGER to s.coolantDangerC.toString(),
    AlertSettingsKeys.VOLTAGE_RUNNING_LOW to s.voltageRunningLow.toString(),
    AlertSettingsKeys.VOLTAGE_RUNNING_HIGH to s.voltageRunningHigh.toString(),
    AlertSettingsKeys.VOLTAGE_ENGINE_OFF_LOW to s.voltageEngineOffLow.toString(),
)

/** Sorted by ordinal so equal sets always give the same row value (the update skips unchanged rows). */
private fun formatKinds(kinds: Set<AlertKind>): String =
    kinds.sortedBy { it.ordinal }.joinToString(SEPARATOR) { it.name }

/** An empty value is the empty set; a name this version does not know (newer app) is logged and dropped. */
private fun parseKinds(raw: String): Set<AlertKind> =
    raw.split(SEPARATOR).filter { it.isNotBlank() }.mapNotNullTo(HashSet()) { name ->
        AlertKind.entries.firstOrNull { it.name == name }
            .also { if (it == null) Log.w(TAG, "Unknown alert kind \"$name\"") }
    }

private fun parseFinite(raw: String): Double? = raw.toDoubleOrNull()?.takeIf { it.isFinite() }
