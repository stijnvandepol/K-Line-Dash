package nl.stijnvdpol.klinedash.core.time

private const val NANOS_PER_MS = 1_000_000L

/**
 * Milliseconds on a clock that only moves forward and never jumps. Use it for every interval, age
 * and deadline; the origin is arbitrary, so the value means nothing outside a subtraction. The wall
 * clock (System.currentTimeMillis()) is only for times that are shown or recorded: a head unit
 * without an RTC steps it by hours when GPS or NTP time arrives.
 */
fun monotonicMillis(): Long = System.nanoTime() / NANOS_PER_MS
