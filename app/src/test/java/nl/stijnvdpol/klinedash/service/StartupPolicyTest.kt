package nl.stijnvdpol.klinedash.service

import android.content.Intent
import nl.stijnvdpol.klinedash.core.elm.AdapterInfo
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.data.StartupSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class WakeDecisionTest {
    private fun decide(event: PowerEvent, phase: LinkPhase, reconnectOnWake: Boolean = true) =
        decideWakeAction(event, phase, reconnectOnWake)

    @Test
    fun accOffClosesALinkThatIsOpenOrBeingOpened() {
        for (phase in listOf(LinkPhase.CONNECTED, LinkPhase.CONNECTING, LinkPhase.WAITING)) {
            assertEquals(phase.name, WakeAction.SUSPEND, decide(PowerEvent.ACC_OFF, phase))
        }
    }

    @Test
    fun accOffHasNothingToCloseWhenTheLinkIsAlreadyClosed() {
        assertEquals(WakeAction.NONE, decide(PowerEvent.ACC_OFF, LinkPhase.SUSPENDED))
        assertEquals(WakeAction.NONE, decide(PowerEvent.ACC_OFF, LinkPhase.STOPPED))
    }

    @Test
    fun everyWakeSignalRebuildsALinkThatWaitsOrWasSuspended() {
        for (event in listOf(PowerEvent.ACC_ON, PowerEvent.SCREEN_ON, PowerEvent.USER_PRESENT)) {
            assertEquals(event.name, WakeAction.RECONNECT, decide(event, LinkPhase.WAITING))
            assertEquals(event.name, WakeAction.RECONNECT, decide(event, LinkPhase.SUSPENDED))
        }
    }

    @Test
    fun wakeSignalsLeaveAWorkingOrStartingLinkAlone() {
        for (event in listOf(PowerEvent.ACC_ON, PowerEvent.SCREEN_ON, PowerEvent.USER_PRESENT)) {
            assertEquals(event.name, WakeAction.NONE, decide(event, LinkPhase.CONNECTED))
            assertEquals(event.name, WakeAction.NONE, decide(event, LinkPhase.CONNECTING))
        }
    }

    @Test
    fun aLinkTheUserClosedStaysClosed() {
        for (event in PowerEvent.entries) {
            assertEquals(event.name, WakeAction.NONE, decide(event, LinkPhase.STOPPED))
        }
    }

    @Test
    fun nothingHappensWhenReconnectingOnWakeIsOff() {
        for (event in PowerEvent.entries) {
            for (phase in LinkPhase.entries) {
                assertEquals("$event $phase", WakeAction.NONE, decide(event, phase, reconnectOnWake = false))
            }
        }
    }

    @Test
    fun theLinkPhaseFollowsTheConnectionState() {
        val info = AdapterInfo("ELM327 v1.5", "3", "ISO 9141-2", 12.4, false, emptySet())
        assertEquals(LinkPhase.CONNECTED, linkPhaseOf(ConnectionState.Connected(info), isSuspended = false))
        assertEquals(LinkPhase.CONNECTING, linkPhaseOf(ConnectionState.Connecting("KW905"), false))
        assertEquals(LinkPhase.CONNECTING, linkPhaseOf(ConnectionState.Initializing("ATZ"), false))
        assertEquals(LinkPhase.WAITING, linkPhaseOf(ConnectionState.Reconnecting(3, 4_000, "timeout"), false))
        assertEquals(LinkPhase.WAITING, linkPhaseOf(ConnectionState.Failed("no adapter"), false))
        assertEquals(LinkPhase.STOPPED, linkPhaseOf(ConnectionState.Idle, false))
    }

    @Test
    fun aSuspendedServiceIsSuspendedWhateverTheLastStateWas() {
        assertEquals(LinkPhase.SUSPENDED, linkPhaseOf(ConnectionState.Idle, isSuspended = true))
        assertEquals(LinkPhase.SUSPENDED, linkPhaseOf(ConnectionState.Reconnecting(1, 1_000, "x"), isSuspended = true))
    }
}

class ClassifyBroadcastTest {
    private val settings = StartupSettings(wakeActions = listOf("a.On", "b.On"), sleepActions = listOf("a.Off"))

    @Test
    fun listedActionsMapToTheirDirection() {
        assertEquals(PowerEvent.ACC_ON, classifyBroadcast("a.On", settings))
        assertEquals(PowerEvent.ACC_ON, classifyBroadcast("b.On", settings))
        assertEquals(PowerEvent.ACC_OFF, classifyBroadcast("a.Off", settings))
    }

    @Test
    fun theSystemScreenSignalsAreAlwaysKnown() {
        val empty = StartupSettings(wakeActions = emptyList(), sleepActions = emptyList())
        assertEquals(PowerEvent.SCREEN_ON, classifyBroadcast(Intent.ACTION_SCREEN_ON, empty))
        assertEquals(PowerEvent.USER_PRESENT, classifyBroadcast(Intent.ACTION_USER_PRESENT, empty))
    }

    @Test
    fun anythingElseIsIgnored() {
        assertNull(classifyBroadcast("c.Other", settings))
        assertNull(classifyBroadcast(Intent.ACTION_SCREEN_OFF, settings))
    }
}

class BootPlanTest {
    private val on = StartupSettings(startOnBoot = true, openDashboardOnStart = false)

    @Test
    fun bootStartsTheServiceWhenEnabled() {
        assertEquals(BootPlan(startService = true, openDashboard = false), planBoot(Intent.ACTION_BOOT_COMPLETED, on, canDrawOverlays = false))
    }

    @Test
    fun anAppUpdateStartsTheServiceToo() {
        assertEquals(BootPlan(startService = true, openDashboard = false), planBoot(Intent.ACTION_MY_PACKAGE_REPLACED, on, canDrawOverlays = true))
    }

    @Test
    fun nothingStartsWhenTheSwitchIsOff() {
        val off = on.copy(startOnBoot = false, openDashboardOnStart = true)
        assertEquals(BootPlan(false, false), planBoot(Intent.ACTION_BOOT_COMPLETED, off, canDrawOverlays = true))
    }

    @Test
    fun otherBroadcastsAreIgnoredIncludingTheLockedBoot() {
        assertEquals(BootPlan(false, false), planBoot(Intent.ACTION_LOCKED_BOOT_COMPLETED, on, canDrawOverlays = true))
        assertEquals(BootPlan(false, false), planBoot("com.fyt.boot.ACCON", on, canDrawOverlays = true))
        assertEquals(BootPlan(false, false), planBoot(null, on, canDrawOverlays = true))
    }

    @Test
    fun theDashboardOpensOnlyAfterABootWithTheOptionAndThePermission() {
        val wantsDashboard = on.copy(openDashboardOnStart = true)
        assertEquals(true, planBoot(Intent.ACTION_BOOT_COMPLETED, wantsDashboard, canDrawOverlays = true).openDashboard)
        assertFalse(planBoot(Intent.ACTION_BOOT_COMPLETED, wantsDashboard, canDrawOverlays = false).openDashboard)
        assertFalse(planBoot(Intent.ACTION_BOOT_COMPLETED, on, canDrawOverlays = true).openDashboard)
        assertFalse(planBoot(Intent.ACTION_MY_PACKAGE_REPLACED, wantsDashboard, canDrawOverlays = true).openDashboard)
    }
}
