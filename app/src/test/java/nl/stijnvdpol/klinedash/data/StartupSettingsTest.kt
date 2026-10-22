package nl.stijnvdpol.klinedash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupSettingsPersistenceTest {
    private fun roundTrip(settings: StartupSettings) =
        decodeStartupSettings(encodeStartupSettings(settings).mapValues { checkNotNull(it.value) })

    @Test
    fun missingRowsGiveTheDefaults() {
        val defaults = decodeStartupSettings(emptyMap())
        assertEquals(StartupSettings(), defaults)
        assertTrue(defaults.startOnBoot)
        assertTrue(defaults.reconnectOnWake)
        assertEquals(false, defaults.openDashboardOnStart)
    }

    @Test
    fun customSettingsSurviveAnEncodeDecodeRoundTrip() {
        val custom = StartupSettings(
            startOnBoot = false,
            reconnectOnWake = false,
            openDashboardOnStart = true,
            wakeActions = listOf("com.example.ACC_ON", "QUICKBOOT_POWERON"),
            sleepActions = listOf("com.example.ACC_OFF"),
            lastReceived = mapOf("com.example.ACC_ON" to 1_761_100_000_000L, "com.example.ACC_OFF" to 1_761_100_050_000L),
        )
        assertEquals(custom, roundTrip(custom))
    }

    @Test
    fun anEmptyListIsKeptAndNotReplacedByTheDefaults() {
        val none = StartupSettings(wakeActions = emptyList(), sleepActions = emptyList())
        assertEquals(none, roundTrip(none))
    }

    @Test
    fun unreadableRowsFallBackToTheDefaultPerField() {
        val rows = mapOf(
            StartupSettingsKeys.START_ON_BOOT to "maybe",
            StartupSettingsKeys.WAKE_ACTIONS to "good.Action, bad action ,good.Action,;rm -rf,",
            StartupSettingsKeys.LAST_RECEIVED to "good.Action=12,broken,bad action=5,other.Action=soon",
        )
        val decoded = decodeStartupSettings(rows)
        assertTrue(decoded.startOnBoot)
        assertEquals(listOf("good.Action"), decoded.wakeActions)
        assertEquals(mapOf("good.Action" to 12L), decoded.lastReceived)
    }

    @Test
    fun equalSettingsAlwaysEncodeToTheSameRows() {
        val a = StartupSettings(lastReceived = mapOf("b.Two" to 2L, "a.One" to 1L))
        val b = StartupSettings(lastReceived = mapOf("a.One" to 1L, "b.Two" to 2L))
        assertEquals(encodeStartupSettings(a), encodeStartupSettings(b))
    }
}

class StartupActionsTest {
    private val existing = listOf("com.fyt.boot.ACCON", "SOME_ACTION")

    @Test
    fun aNormalActionIsAccepted() {
        assertNull(actionProblem("com.vendor.ACC_ON", existing))
        assertNull(actionProblem("  com.vendor.ACC_ON  ", existing))
        assertNull(actionProblem("BOOT_IPO", existing))
    }

    @Test
    fun emptyAndBlankAreRefused() {
        assertEquals(ActionProblem.EMPTY, actionProblem("", existing))
        assertEquals(ActionProblem.EMPTY, actionProblem("   ", existing))
    }

    @Test
    fun theLengthLimitIsInclusive() {
        assertNull(actionProblem("a".repeat(MAX_ACTION_LENGTH), existing))
        assertEquals(ActionProblem.TOO_LONG, actionProblem("a".repeat(MAX_ACTION_LENGTH + 1), existing))
    }

    @Test
    fun onlyLettersDigitsDotsAndUnderscoresAreAllowed() {
        for (bad in listOf("two words", "a-b", "a,b", "a=b", "a/b", "a;b", "a\nb", "ä", "a:b")) {
            assertEquals(bad, ActionProblem.BAD_CHARACTERS, actionProblem(bad, existing))
        }
    }

    @Test
    fun duplicatesAreRefusedAfterTrimming() {
        assertEquals(ActionProblem.DUPLICATE, actionProblem("SOME_ACTION", existing))
        assertEquals(ActionProblem.DUPLICATE, actionProblem(" com.fyt.boot.ACCON ", existing))
    }

    @Test
    fun actionsAreCaseSensitiveLikeAndroidActions() {
        assertNull(actionProblem("some_action", existing))
    }

    @Test
    fun addingPutsTheTrimmedActionInTheChosenList() {
        val added = StartupSettings(wakeActions = listOf("a.On"), sleepActions = listOf("a.Off"))
            .withAction(WakeDirection.WAKE, " b.On ")
            .withAction(WakeDirection.SLEEP, "b.Off")
        assertEquals(listOf("a.On", "b.On"), added.wakeActions)
        assertEquals(listOf("a.Off", "b.Off"), added.sleepActions)
    }

    @Test
    fun addingAnInvalidOrKnownActionChangesNothing() {
        val settings = StartupSettings(wakeActions = listOf("a.On"), sleepActions = listOf("a.Off"))
        assertEquals(settings, settings.withAction(WakeDirection.WAKE, "a.Off"))
        assertEquals(settings, settings.withAction(WakeDirection.SLEEP, "bad action"))
        assertEquals(settings, settings.withAction(WakeDirection.SLEEP, ""))
    }

    @Test
    fun removingDropsTheActionAndItsTime() {
        val settings = StartupSettings(
            wakeActions = listOf("a.On", "b.On"),
            lastReceived = mapOf("a.On" to 5L, "b.On" to 6L),
        ).withoutAction("a.On")
        assertEquals(listOf("b.On"), settings.wakeActions)
        assertEquals(mapOf("b.On" to 6L), settings.lastReceived)
    }

    @Test
    fun resetRestoresTheListsButKeepsTimesOfActionsThatStay() {
        val custom = StartupSettings(
            startOnBoot = false,
            wakeActions = listOf("my.Own"),
            sleepActions = emptyList(),
            lastReceived = mapOf("my.Own" to 1L, "com.fyt.boot.ACCON" to 2L),
        )
        val reset = StartupSettings(wakeActions = emptyList(), sleepActions = emptyList(), lastReceived = custom.lastReceived)
            .withDefaultActions()
        assertEquals(DEFAULT_WAKE_ACTIONS, reset.wakeActions)
        assertEquals(DEFAULT_SLEEP_ACTIONS, reset.sleepActions)
        assertEquals(mapOf("com.fyt.boot.ACCON" to 2L), reset.lastReceived)
        assertEquals(false, custom.withDefaultActions().startOnBoot)
    }

    @Test
    fun receivedTimesAreOnlyKeptForListedActions() {
        val settings = StartupSettings()
            .withReceived("com.fyt.boot.ACCON", 10L)
            .withReceived("com.fyt.boot.ACCOFF", 20L)
            .withReceived("not.Listed", 30L)
            .withReceived("com.fyt.boot.ACCON", 40L)
        assertEquals(mapOf("com.fyt.boot.ACCON" to 40L, "com.fyt.boot.ACCOFF" to 20L), settings.lastReceived)
    }

    @Test
    fun theDefaultsAreValidAndDistinct() {
        val all = DEFAULT_WAKE_ACTIONS + DEFAULT_SLEEP_ACTIONS
        assertEquals(all.size, all.toSet().size)
        all.forEach { assertNull(it, actionProblem(it, emptyList())) }
        assertTrue("com.fyt.boot.ACCON" in DEFAULT_WAKE_ACTIONS)
        assertTrue("android.intent.action.ACTION_MT_COMMAND_SLEEP_IN" in DEFAULT_SLEEP_ACTIONS)
    }
}
