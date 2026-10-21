package nl.stijnvdpol.klinedash.core.sim

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine model on its own: time is passed in, so every test walks a virtual clock. */
class EngineModelTest {

    @Test
    fun `drive cycle revs past the vvtl point and stays within the limiter`() {
        val config = SimulatorConfig().apply { initialCoolantC = 90.0 }
        val engine = EngineModel(config, Random(1))
        var maxRpm = 0
        var maxSpeed = 0
        var sawVvtl = false
        var sawStandstill = false
        var maxMaf = 0.0
        var t = 0L
        while (t < 120_000) {
            val s = engine.snapshot(t)
            maxRpm = maxOf(maxRpm, s.rpm)
            maxSpeed = maxOf(maxSpeed, s.speedKmh)
            maxMaf = maxOf(maxMaf, s.mafGs)
            if (s.vvtlActive) sawVvtl = true
            if (s.speedKmh == 0 && s.gear == 0) sawStandstill = true
            assertTrue("rpm ${s.rpm}", s.rpm in 0..8300)
            assertTrue("load ${s.loadPct}", s.loadPct in 0.0..100.0)
            if (s.rpm > 0) assertTrue("maf at ${s.rpm} rpm", s.mafGs > 0.0)
            t += 100
        }
        assertTrue("max rpm $maxRpm", maxRpm >= 8000)
        assertTrue("max speed $maxSpeed", maxSpeed >= 130)
        assertTrue(sawVvtl)
        assertTrue(sawStandstill)
        assertTrue("max maf $maxMaf", maxMaf > 80.0)
    }

    @Test
    fun `speed and rpm are consistent with the gear`() {
        val config = SimulatorConfig().apply { initialCoolantC = 90.0 }
        val engine = EngineModel(config, Random(1))
        // Third-gear pull: ~56.6 rpm per km/h.
        var t = 0L
        while (t < 120_000) {
            val s = engine.snapshot(t)
            if (s.gear == 3 && s.speedKmh in 90..140 && s.rpm < 8190) {
                val expected = s.speedKmh * 56.6
                assertTrue("rpm ${s.rpm} vs speed ${s.speedKmh}", s.rpm in (expected * 0.85).toInt()..(expected * 1.15).toInt())
            }
            t += 100
        }
    }

    @Test
    fun `coolant warms from ambient towards operating temperature`() {
        val config = SimulatorConfig()
        val engine = EngineModel(config, Random(1))
        assertEquals(15.0, engine.snapshot(0).coolantC, 1.0)
        var last = engine.snapshot(0).coolantC
        var t = 0L
        while (t < 900_000) {
            t += 1_000
            val c = engine.snapshot(t).coolantC
            assertTrue("coolant dropped from $last to $c at $t", c > last - 1.0)
            last = c
        }
        assertTrue("final coolant $last", last in 85.0..93.0)
    }

    @Test
    fun `cold engine idles higher and runs open loop`() {
        val config = SimulatorConfig().apply { scenario = Scenario.IDLE }
        val engine = EngineModel(config, Random(1))
        val cold = engine.snapshot(0)
        assertTrue("cold idle ${cold.rpm}", cold.rpm in 1_100..1_300)
        assertEquals(1, cold.fuelSystemStatus)
        var t = 0L
        while (t < 900_000) {
            t += 1_000
            engine.snapshot(t)
        }
        val warm = engine.snapshot(t + 1_000)
        assertTrue("warm idle ${warm.rpm}", warm.rpm in 800..900)
        assertEquals(2, warm.fuelSystemStatus)
    }
}
