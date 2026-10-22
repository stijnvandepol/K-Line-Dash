package nl.stijnvdpol.klinedash.core.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PollPlanTest {

    private val rpm = Signals.all.first { it.id == SignalIds.RPM }
    private val coolant = Signals.all.first { it.id == SignalIds.COOLANT }

    private fun planCommand(id: String, intervalMs: Long) = PollCommand(id, intervalMs, listOf(rpm))

    @Test
    fun `plan has all fast commands and the most overdue due command`() {
        val config = PollerConfig()
        val fast = planCommand("F", PollCommand.EVERY_ROUND)
        val medium = planCommand("M", config.mediumIntervalMs)
        val slow = planCommand("S", config.slowIntervalMs)
        val last = mapOf("M" to 0L, "S" to 0L)

        assertEquals(listOf(fast), planRound(listOf(fast, medium, slow), last, nowMs = 2_999))
        assertEquals(listOf(fast, medium), planRound(listOf(fast, medium, slow), last, nowMs = 3_000))
        // at 45 s medium is 42 s past due and slow 30 s: the more overdue one wins
        assertEquals(listOf(fast, medium), planRound(listOf(fast, medium, slow), last, nowMs = 45_000))
        assertEquals(listOf(fast, slow), planRound(listOf(fast, medium, slow), mapOf("M" to 44_000L, "S" to 0L), 45_000))
    }

    @Test
    fun `a command that was never sent is the most overdue`() {
        val medium = planCommand("M", 3_000)
        val slow = planCommand("S", 15_000)
        assertEquals(slow, pickRotating(listOf(medium, slow), mapOf("M" to 0L), nowMs = 100_000))
        assertNull(pickRotating(listOf(planCommand("F", PollCommand.EVERY_ROUND)), emptyMap(), 0))
    }

    @Test
    fun `next due time is the smallest remaining wait`() {
        val medium = planCommand("M", 3_000)
        val slow = planCommand("S", 15_000)
        assertEquals(1_500, nextDueInMs(listOf(medium, slow), mapOf("M" to 1_500L, "S" to 1_500L), nowMs = 3_000))
    }

    @Test
    fun `next due time is never zero or negative`() {
        val medium = planCommand("M", 3_000)
        assertEquals(1, nextDueInMs(listOf(medium), mapOf("M" to 0L), nowMs = 60_000))
    }

    @Test
    fun `commands are grouped with the fastest tier and the rpm probe gets the check interval`() {
        val config = PollerConfig()
        val o2 = Signals.all.filter { it.command == "0114" }

        val commands = buildPollCommands(o2 + coolant, rpm, config)

        assertEquals(listOf("0114", "0105", "010C"), commands.map { it.command })
        assertEquals(2, commands.first().signals.size)
        assertEquals(listOf(config.slowIntervalMs, config.mediumIntervalMs, config.engineOffCheckIntervalMs), commands.map { it.intervalMs })

        val demanded = buildPollCommands(listOf(rpm, coolant), rpm, config)
        assertEquals(PollCommand.EVERY_ROUND, demanded.first { it.command == "010C" }.intervalMs)
    }

    @Test
    fun `only demanded signals the ecu supports and that were not given up on are pollable`() {
        val speed = Signals.all.first { it.id == SignalIds.SPEED }
        val battery = Signals.all.first { it.id == SignalIds.BATTERY }
        val pollable = selectPollable(
            demandedIds = setOf(SignalIds.RPM, SignalIds.SPEED, SignalIds.COOLANT, SignalIds.BATTERY),
            catalog = listOf(rpm, speed, coolant, battery),
            supportedPids = setOf(0x0C, 0x0D),
            deadCommands = setOf(speed.command),
        )
        // coolant (0x05) is not supported, speed is dead, the adapter's own battery reading always works
        assertEquals(listOf(rpm, battery), pollable)
    }
}
