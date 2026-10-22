package nl.stijnvdpol.klinedash.service

import android.content.Intent
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.data.StartupSettings

/** What the running service learns about the car and the unit from a broadcast. */
internal enum class PowerEvent { ACC_ON, ACC_OFF, SCREEN_ON, USER_PRESENT }

/**
 * Where the adapter link is, for deciding what a wake or sleep signal means. STOPPED is a link the
 * user closed with Disconnect; SUSPENDED is one this service closed because the car went off.
 */
internal enum class LinkPhase { CONNECTED, CONNECTING, WAITING, STOPPED, SUSPENDED }

internal enum class WakeAction { NONE, RECONNECT, SUSPEND }

/** [isSuspended] wins over the state: a suspended service has no link, which reads as Idle. */
internal fun linkPhaseOf(state: ConnectionState, isSuspended: Boolean): LinkPhase = when {
    isSuspended -> LinkPhase.SUSPENDED
    state is ConnectionState.Connected -> LinkPhase.CONNECTED
    state is ConnectionState.Connecting || state is ConnectionState.Initializing -> LinkPhase.CONNECTING
    state is ConnectionState.Reconnecting || state is ConnectionState.Failed -> LinkPhase.WAITING
    else -> LinkPhase.STOPPED
}

/**
 * Maps a received action to an event: the listed wake and sleep actions, plus the system's own
 * screen-on and user-present. Anything else (including an action removed from the list a moment ago)
 * gives null.
 */
internal fun classifyBroadcast(action: String, settings: StartupSettings): PowerEvent? = when (action) {
    Intent.ACTION_SCREEN_ON -> PowerEvent.SCREEN_ON
    Intent.ACTION_USER_PRESENT -> PowerEvent.USER_PRESENT
    in settings.wakeActions -> PowerEvent.ACC_ON
    in settings.sleepActions -> PowerEvent.ACC_OFF
    else -> null
}

/**
 * The wake/sleep rule. Nothing happens unless the user wants reconnecting on wake.
 *
 * - ACC off closes a link that is open or being opened (adapter and car battery); the service stays.
 * - ACC on, screen on and user present all mean "the unit is awake": a link that waits in backoff or
 *   was suspended is rebuilt at once. A link that is up or being set up is left alone, because
 *   these signals arrive together and must not restart one connection several times; a link the
 *   user closed with Disconnect stays closed.
 */
internal fun decideWakeAction(event: PowerEvent, phase: LinkPhase, reconnectOnWake: Boolean): WakeAction {
    if (!reconnectOnWake) return WakeAction.NONE
    return when (event) {
        PowerEvent.ACC_OFF -> when (phase) {
            LinkPhase.CONNECTED, LinkPhase.CONNECTING, LinkPhase.WAITING -> WakeAction.SUSPEND
            LinkPhase.STOPPED, LinkPhase.SUSPENDED -> WakeAction.NONE
        }
        PowerEvent.ACC_ON, PowerEvent.SCREEN_ON, PowerEvent.USER_PRESENT -> when (phase) {
            LinkPhase.WAITING, LinkPhase.SUSPENDED -> WakeAction.RECONNECT
            LinkPhase.CONNECTED, LinkPhase.CONNECTING, LinkPhase.STOPPED -> WakeAction.NONE
        }
    }
}

/** What [BootReceiver] does for one broadcast. */
internal data class BootPlan(val startService: Boolean, val openDashboard: Boolean)

private val NO_BOOT_PLAN = BootPlan(startService = false, openDashboard = false)

/**
 * Only BOOT_COMPLETED and MY_PACKAGE_REPLACED are handled (the two a manifest receiver may start a
 * foreground service from on Android 12+). LOCKED_BOOT_COMPLETED is not: the settings database is
 * credential-encrypted storage and is unreadable until the first unlock. The dashboard opens only
 * after a real boot, not after an app update, and only when the overlay permission allows it.
 */
internal fun planBoot(action: String?, settings: StartupSettings, canDrawOverlays: Boolean): BootPlan {
    val isBoot = action == Intent.ACTION_BOOT_COMPLETED
    if (!isBoot && action != Intent.ACTION_MY_PACKAGE_REPLACED) return NO_BOOT_PLAN
    if (!settings.startOnBoot) return NO_BOOT_PLAN
    return BootPlan(startService = true, openDashboard = isBoot && settings.openDashboardOnStart && canDrawOverlays)
}
