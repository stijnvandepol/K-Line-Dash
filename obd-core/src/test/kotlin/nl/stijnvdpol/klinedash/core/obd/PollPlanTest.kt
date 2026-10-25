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

    private val fastRpm = planCommand("R", PollCommand.EVERY_ROUND)
    private val fastSpeed = planCommand("S1", PollCommand.EVERY_ROUND)
    private val fastThrottle = planCommand("T", PollCommand.EVERY_ROUND)
    private val coolantCommand = planCommand("C", 3_000)

    private fun names(plan: List<PollCommand>) = plan.map { it.command }

    @Test
    fun `without batching rpm takes two places in the round and the other fast commands share the slot`() {
        val commands = listOf(fastRpm, fastSpeed, fastThrottle)

        // Nothing else wants the slot: throttle takes it every round.
        assertEquals(listOf("R", "S1", "R", "T"), names(planRound(commands, emptyMap(), 0, repeatedCommand = "R")))
    }

    @Test
    fun `a due medium command and the demoted fast command take the slot in turns`() {
        val commands = listOf(fastRpm, fastSpeed, fastThrottle, coolantCommand)
        // Never sent: the demoted command goes first, ahead of the rotating ones.
        assertEquals("T", planRound(commands, emptyMap(), 0, "R").last().command)
        // Throttle had the last slot and coolant is due: coolant now.
        assertEquals("C", planRound(commands, mapOf("T" to 10_000L, "C" to 5_000L), 10_500, "R").last().command)
        // Coolant had the last slot: throttle again.
        assertEquals("T", planRound(commands, mapOf("T" to 7_000L, "C" to 10_000L), 14_000, "R").last().command)
        // Coolant is not due yet, so throttle keeps the slot.
        assertEquals("T", planRound(commands, mapOf("T" to 10_000L, "C" to 10_500L), 11_000, "R").last().command)
    }

    @Test
    fun `several demoted fast commands take the slot oldest first`() {
        val second = planCommand("T2", PollCommand.EVERY_ROUND)
        val commands = listOf(fastRpm, fastSpeed, fastThrottle, second)

        assertEquals("T2", planRound(commands, mapOf("T" to 9_000L, "T2" to 5_000L), 10_000, "R").last().command)
        assertEquals("T", planRound(commands, mapOf("T" to 4_000L, "T2" to 5_000L), 10_000, "R").last().command)
    }

    @Test
    fun `rpm is repeated only when it is a fast command and something else is fast too`() {
        val detector = planCommand("R", 5_000)
        val withoutFastRpm = listOf(fastSpeed, fastThrottle, detector)
        assertEquals(listOf("S1", "T"), names(planRound(withoutFastRpm, mapOf("R" to 0L), 1_000, "R")))
        assertEquals(listOf("R"), names(planRound(listOf(fastRpm), emptyMap(), 0, "R")))
        assertEquals(listOf("R", "C"), names(planRound(listOf(fastRpm, coolantCommand), emptyMap(), 0, "R")))
        // rpm and one other: no demoted command, the slot stays the rotating command's.
        assertEquals(listOf("R", "S1", "R", "C"), names(planRound(listOf(fastRpm, fastSpeed, coolantCommand), emptyMap(), 0, "R")))
        assertEquals(listOf("R", "S1", "R"), names(planRound(listOf(fastRpm, fastSpeed), emptyMap(), 0, "R")))
    }

    @Test
    fun `without a repeated command the round is every fast command once`() {
        val commands = listOf(fastRpm, fastSpeed, fastThrottle, coolantCommand)
        assertEquals(listOf("R", "S1", "T", "C"), names(planRound(commands, emptyMap(), 0)))
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
