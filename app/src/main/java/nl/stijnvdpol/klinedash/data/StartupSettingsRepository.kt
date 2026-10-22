package nl.stijnvdpol.klinedash.data

import kotlinx.coroutines.flow.Flow

/** Which way a head-unit broadcast points: the car (and the unit) waking up, or going to sleep. */
enum class WakeDirection { WAKE, SLEEP }

/** Longest broadcast action string the list accepts; real ones are under 60 characters. */
const val MAX_ACTION_LENGTH = 128

private val ACTION_PATTERN = Regex("[A-Za-z0-9._]+")

internal val DEFAULT_WAKE_ACTIONS = listOf(
    "com.fyt.boot.ACCON",
    "com.glsx.boot.ACCON",
    "android.intent.action.ACTION_MT_COMMAND_SLEEP_OUT",
    "com.microntek.startApp",
    "com.cayboy.action.ACC_ON",
    "android.intent.action.QUICKBOOT_POWERON",
    "QUICKBOOT_POWERON",
    "BOOT_IPO",
)

internal val DEFAULT_SLEEP_ACTIONS = listOf(
    "com.fyt.boot.ACCOFF",
    "com.glsx.boot.ACCOFF",
    "android.intent.action.ACTION_MT_COMMAND_SLEEP_IN",
)

/**
 * Startup and wake-up behaviour. The two action lists are the vendor broadcasts the running service
 * listens for (docs/research/headunit-autostart.md, section 6.1); the user can edit them because no
 * vendor documents which string a given unit sends.
 *
 * @property startOnBoot start the service when Android finishes booting (or the app was updated).
 * @property reconnectOnWake rebuild the link when the unit wakes, and close it when the car goes off.
 * @property openDashboardOnStart after a boot, bring the dashboard to the front (needs "display over other apps").
 * @property lastReceived epoch milliseconds of the last time each listed action arrived.
 */
data class StartupSettings(
    val startOnBoot: Boolean = true,
    val reconnectOnWake: Boolean = true,
    val openDashboardOnStart: Boolean = false,
    val wakeActions: List<String> = DEFAULT_WAKE_ACTIONS,
    val sleepActions: List<String> = DEFAULT_SLEEP_ACTIONS,
    val lastReceived: Map<String, Long> = emptyMap(),
) {
    val allActions: List<String> get() = wakeActions + sleepActions

    /** Adds [raw] (trimmed) to the list of [direction]; unchanged when it is not a valid new action. */
    fun withAction(direction: WakeDirection, raw: String): StartupSettings {
        if (actionProblem(raw, allActions) != null) return this
        val name = raw.trim()
        return when (direction) {
            WakeDirection.WAKE -> copy(wakeActions = wakeActions + name)
            WakeDirection.SLEEP -> copy(sleepActions = sleepActions + name)
        }
    }

    /** Removes [action] from both lists together with its last-received time. */
    fun withoutAction(action: String): StartupSettings = copy(
        wakeActions = wakeActions - action,
        sleepActions = sleepActions - action,
        lastReceived = lastReceived - action,
    )

    /** The lists go back to the defaults; times of actions that stay in the lists are kept. */
    fun withDefaultActions(): StartupSettings {
        val defaults = copy(wakeActions = DEFAULT_WAKE_ACTIONS, sleepActions = DEFAULT_SLEEP_ACTIONS)
        return defaults.copy(lastReceived = lastReceived.filterKeys { it in defaults.allActions })
    }

    /** Notes the arrival of [action]; ignored for an action that is not in the lists. */
    fun withReceived(action: String, atMs: Long): StartupSettings =
        if (action in allActions) copy(lastReceived = lastReceived + (action to atMs)) else this
}

enum class ActionProblem { EMPTY, TOO_LONG, BAD_CHARACTERS, DUPLICATE }

/** Why [raw] cannot be added next to the [existing] actions, or null when it can. [raw] is trimmed first. */
fun actionProblem(raw: String, existing: Collection<String>): ActionProblem? {
    val name = raw.trim()
    return when {
        name.isEmpty() -> ActionProblem.EMPTY
        name.length > MAX_ACTION_LENGTH -> ActionProblem.TOO_LONG
        !ACTION_PATTERN.matches(name) -> ActionProblem.BAD_CHARACTERS
        name in existing -> ActionProblem.DUPLICATE
        else -> null
    }
}

/** Persists [StartupSettings] under "startup.*" keys of the settings table. */
class StartupSettingsRepository(dao: SettingsDao) {
    private val store = TypedSettings(dao, ::decodeStartupSettings, ::encodeStartupSettings)

    val settings: Flow<StartupSettings> = store.flow

    suspend fun update(transform: (StartupSettings) -> StartupSettings) = store.update(transform)

    suspend fun recordReceived(action: String, atMs: Long) = update { it.withReceived(action, atMs) }
}

internal object StartupSettingsKeys {
    const val START_ON_BOOT = "startup.startOnBoot"
    const val RECONNECT_ON_WAKE = "startup.reconnectOnWake"
    const val OPEN_DASHBOARD = "startup.openDashboard"
    const val WAKE_ACTIONS = "startup.wakeActions"
    const val SLEEP_ACTIONS = "startup.sleepActions"
    const val LAST_RECEIVED = "startup.lastReceived"
}

private const val LIST_SEPARATOR = ","
private const val TIME_SEPARATOR = "="

internal fun decodeStartupSettings(rows: Map<String, String>): StartupSettings {
    val d = StartupSettings()
    return StartupSettings(
        startOnBoot = rows.parsed(StartupSettingsKeys.START_ON_BOOT, d.startOnBoot) { it.toBooleanStrictOrNull() },
        reconnectOnWake = rows.parsed(StartupSettingsKeys.RECONNECT_ON_WAKE, d.reconnectOnWake) { it.toBooleanStrictOrNull() },
        openDashboardOnStart = rows.parsed(StartupSettingsKeys.OPEN_DASHBOARD, d.openDashboardOnStart) { it.toBooleanStrictOrNull() },
        wakeActions = rows.parsed(StartupSettingsKeys.WAKE_ACTIONS, d.wakeActions, ::parseActions),
        sleepActions = rows.parsed(StartupSettingsKeys.SLEEP_ACTIONS, d.sleepActions, ::parseActions),
        lastReceived = rows.parsed(StartupSettingsKeys.LAST_RECEIVED, d.lastReceived, ::parseTimes),
    )
}

internal fun encodeStartupSettings(s: StartupSettings): Map<String, String?> = mapOf(
    StartupSettingsKeys.START_ON_BOOT to s.startOnBoot.toString(),
    StartupSettingsKeys.RECONNECT_ON_WAKE to s.reconnectOnWake.toString(),
    StartupSettingsKeys.OPEN_DASHBOARD to s.openDashboardOnStart.toString(),
    // An empty list is stored as an empty value, which differs from a missing row (the defaults).
    StartupSettingsKeys.WAKE_ACTIONS to s.wakeActions.joinToString(LIST_SEPARATOR),
    StartupSettingsKeys.SLEEP_ACTIONS to s.sleepActions.joinToString(LIST_SEPARATOR),
    StartupSettingsKeys.LAST_RECEIVED to s.lastReceived.entries
        .sortedBy { it.key }
        .joinToString(LIST_SEPARATOR) { "${it.key}$TIME_SEPARATOR${it.value}" },
)

/** Entries that are not valid actions (corrupted or written by another version) are dropped. */
private fun parseActions(raw: String): List<String> =
    raw.split(LIST_SEPARATOR).map { it.trim() }.filter { actionProblem(it, emptyList()) == null }.distinct()

private fun parseTimes(raw: String): Map<String, Long> = buildMap {
    for (entry in raw.split(LIST_SEPARATOR)) {
        val (action, time) = entry.split(TIME_SEPARATOR, limit = 2).takeIf { it.size == 2 } ?: continue
        val millis = time.toLongOrNull() ?: continue
        if (actionProblem(action, emptyList()) == null) put(action, millis)
    }
}
