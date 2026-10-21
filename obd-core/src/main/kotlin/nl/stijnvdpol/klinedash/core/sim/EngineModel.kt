package nl.stijnvdpol.klinedash.core.sim

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Physically plausible values for a Toyota 2ZZ-GE (1.8 L, 8200 rpm limiter, VVTL-i cam switch
 * around 6200 rpm, 6-speed C64 gearbox). Not a simulation of combustion: just enough
 * consistency (speed vs rpm vs gear, MAF vs rpm and load, coolant warm-up) for dashboards.
 *
 * Time is passed in by the caller, so the model is deterministic for a given seed and call
 * sequence. Calls must use a non-decreasing clock.
 */
class EngineModel(
    private val config: SimulatorConfig,
    random: Random,
) {
    /** One consistent set of readings. */
    data class Snapshot(
        val running: Boolean,
        val rpm: Int,
        val speedKmh: Int,
        val gear: Int,
        val coolantC: Double,
        val intakeC: Double,
        val loadPct: Double,
        val throttlePct: Double,
        val mafGs: Double,
        val timingDeg: Double,
        val shortTrimPct: Double,
        val longTrimPct: Double,
        /** O2 sensor bank 1 sensor 1 (upstream), volts. */
        val o2UpstreamV: Double,
        /** O2 sensor bank 1 sensor 2 (downstream), volts. */
        val o2DownstreamV: Double,
        /** PID 03 bit value: 1 open (cold), 2 closed loop, 4 open (load / fuel cut). */
        val fuelSystemStatus: Int,
        /** True while the high-lift VVTL-i cam profile would be engaged. */
        val vvtlActive: Boolean,
    )

    private class Segment(
        val durationS: Double,
        val speedStart: Double,
        val speedEnd: Double,
        val gear: Int,
        val throttleStart: Double,
        val throttleEnd: Double,
    )

    private val phases = DoubleArray(PHASE_COUNT) { random.nextDouble() * 2.0 * PI }

    private var running = true
    private var scriptStartMs = UNSET
    private var lastMs = -1L
    private var coolant = config.initialCoolantC ?: config.ambientC
    private var rpmSmoothed = 0.0

    val isRunning: Boolean
        @Synchronized get() = running

    /**
     * Starts or stops the engine (stopped = ignition on, engine off). Restarts the drive script.
     * A new model starts with the engine running.
     */
    @Synchronized
    fun setRunning(value: Boolean, nowMs: Long) {
        if (value && !running) scriptStartMs = nowMs
        running = value
    }

    @Synchronized
    fun snapshot(nowMs: Long): Snapshot {
        val dt = if (lastMs < 0) 0.0 else (nowMs - lastMs).coerceAtLeast(0L) / 1000.0
        val first = lastMs < 0
        lastMs = nowMs
        val t = nowMs / 1000.0
        if (scriptStartMs == UNSET) scriptStartMs = nowMs

        val ambient = config.ambientC
        val tau = config.warmupTimeConstantS.coerceAtLeast(1.0)
        if (running) {
            coolant += (COOLANT_TARGET_C - coolant) * (1.0 - exp(-dt / tau))
        } else {
            coolant += (ambient - coolant) * (1.0 - exp(-dt / (tau * COOL_DOWN_FACTOR)))
        }

        // --- Driver inputs from the script ---
        var speed = 0.0
        var gear = 0
        var throttle = IDLE_THROTTLE
        if (running && config.scenario == Scenario.DRIVE_CYCLE) {
            val scriptT = (((nowMs - scriptStartMs) / 1000.0) % TOTAL_S).coerceAtLeast(0.0)
            var start = 0.0
            for (seg in SCRIPT) {
                if (scriptT < start + seg.durationS) {
                    val f = (scriptT - start) / seg.durationS
                    speed = seg.speedStart + (seg.speedEnd - seg.speedStart) * f
                    gear = seg.gear
                    throttle = seg.throttleStart + (seg.throttleEnd - seg.throttleStart) * f
                    break
                }
                start += seg.durationS
            }
        }

        // --- rpm ---
        val idleRpm = IDLE_RPM_WARM + ((COOLANT_TARGET_C - coolant) / (COOLANT_TARGET_C - ambient).coerceAtLeast(10.0))
            .coerceIn(0.0, 1.0) * IDLE_RPM_COLD_EXTRA
        val targetRpm = when {
            !running -> 0.0
            gear == 0 -> idleRpm + noise(t, 0) * 12.0
            else -> {
                var r = max(idleRpm, speed * GEAR_RPM_PER_KMH[gear - 1])
                if (gear == 1 && throttle > 15.0) r = max(r, LAUNCH_RPM) // clutch slip
                min(r, REV_LIMIT_RPM)
            }
        }
        if (first) rpmSmoothed = targetRpm
        rpmSmoothed += (targetRpm - rpmSmoothed) * (1.0 - exp(-dt / RPM_TAU_S))
        if (!running && rpmSmoothed < 5.0) rpmSmoothed = 0.0
        val rpm = rpmSmoothed.coerceAtLeast(0.0)

        // --- Derived readings ---
        val overrun = running && gear > 0 && throttle <= IDLE_THROTTLE + 1.0 && speed > 15.0
        val thr = if (running) (throttle + noise(t, 2) * 0.6).coerceIn(0.0, 100.0) else IDLE_THROTTLE
        val rel = ((thr - IDLE_THROTTLE) / (100.0 - IDLE_THROTTLE)).coerceIn(0.0, 1.0)
        val load = when {
            !running -> 0.0
            overrun -> 8.0 + noise(t, 3)
            else -> 22.0 + 66.0 * rel.pow(0.85) + 8.0 * rel * (rpm / REV_LIMIT_RPM) + noise(t, 3) * 1.2
        }.coerceIn(0.0, 100.0)
        val vvtl = running && rpm >= VVTL_RPM && thr > 70.0

        // 1.796 L, 4-stroke: intake events per second = rpm / 120; air density ~1.184 g/L.
        var maf = if (running) rpm / 120.0 * DISPLACEMENT_L * AIR_G_PER_L * load / 100.0 else 0.0
        if (vvtl) maf *= 1.06
        if (overrun) maf *= 0.8

        val fuelStatus = when {
            !running -> 1
            coolant < CLOSED_LOOP_MIN_C -> 1
            overrun || thr > 85.0 -> 4
            else -> 2
        }
        val closedLoop = fuelStatus == 2

        val timing = if (running) {
            10.0 + 0.002 * rpm + 0.06 * (100.0 - load) + noise(t, 4) * 0.5
        } else 0.0

        val stft = if (closedLoop) 3.0 * sin(2.0 * PI * t / 1.3 + phases[5]) + noise(t, 6) * 0.5 else 0.0
        val o2Up = when {
            !running -> 0.0
            closedLoop -> 0.45 + 0.42 * tanh(4.0 * sin(2.0 * PI * t / 1.1 + phases[7]))
            overrun -> 0.05
            else -> 0.88
        }
        val o2Down = when {
            !running -> 0.0
            overrun -> 0.2
            else -> 0.65 + 0.04 * noise(t, 1)
        }

        val intake = ambient + (coolant - ambient) * 0.25 - speed * 0.03

        return Snapshot(
            running = running,
            rpm = rpm.toInt(),
            speedKmh = if (running) (speed + noise(t, 1) * 0.3).coerceAtLeast(0.0).toInt() else 0,
            gear = gear,
            coolantC = coolant + if (running) noise(t, 0) * 0.3 else 0.0,
            intakeC = intake,
            loadPct = load,
            throttlePct = thr,
            mafGs = maf,
            timingDeg = timing,
            shortTrimPct = stft,
            longTrimPct = LONG_TERM_TRIM_PCT,
            o2UpstreamV = o2Up,
            o2DownstreamV = o2Down,
            fuelSystemStatus = fuelStatus,
            vvtlActive = vvtl,
        )
    }

    /** Smooth pseudo-noise in [-1, 1], deterministic in time per channel. */
    private fun noise(tSeconds: Double, channel: Int): Double {
        val a = phases[(channel * 2) % PHASE_COUNT]
        val b = phases[(channel * 2 + 1) % PHASE_COUNT]
        return 0.65 * sin(2.0 * PI * tSeconds / 1.7 + a) + 0.35 * sin(2.0 * PI * tSeconds / 0.53 + b)
    }

    private companion object {
        const val UNSET = Long.MIN_VALUE
        const val PHASE_COUNT = 10
        const val COOLANT_TARGET_C = 90.0
        const val COOL_DOWN_FACTOR = 20.0
        const val CLOSED_LOOP_MIN_C = 55.0
        const val IDLE_THROTTLE = 12.0
        const val IDLE_RPM_WARM = 850.0
        const val IDLE_RPM_COLD_EXTRA = 350.0
        const val LAUNCH_RPM = 1500.0
        const val REV_LIMIT_RPM = 8200.0
        const val VVTL_RPM = 6200.0
        const val RPM_TAU_S = 0.12
        const val DISPLACEMENT_L = 1.796
        const val AIR_G_PER_L = 1.184
        const val LONG_TERM_TRIM_PCT = 3.1

        /** rpm per km/h in gears 1..6 (final drive and 195/55R15 tyres folded in). */
        val GEAR_RPM_PER_KMH = doubleArrayOf(135.0, 78.0, 56.6, 44.8, 37.2, 31.3)

        private val SCRIPT = listOf(
            Segment(6.0, 0.0, 0.0, 0, 12.0, 12.0), // idle
            Segment(3.0, 0.0, 28.0, 1, 25.0, 60.0), // launch
            Segment(3.0, 28.0, 52.0, 2, 60.0, 60.0),
            Segment(3.0, 52.0, 70.0, 3, 50.0, 50.0),
            Segment(8.0, 70.0, 70.0, 5, 18.0, 18.0), // cruise
            Segment(12.0, 70.0, 145.0, 3, 100.0, 100.0), // kick-down pull, crosses VVTL-i at ~110 km/h
            Segment(6.0, 145.0, 80.0, 3, 12.0, 12.0), // overrun from the limiter
            Segment(5.0, 80.0, 45.0, 4, 12.0, 12.0),
            Segment(3.0, 45.0, 20.0, 2, 12.0, 12.0),
            Segment(2.0, 20.0, 0.0, 1, 12.0, 12.0),
            Segment(5.0, 0.0, 0.0, 0, 12.0, 12.0), // idle
        )
        val TOTAL_S = SCRIPT.sumOf { it.durationS }
    }
}
