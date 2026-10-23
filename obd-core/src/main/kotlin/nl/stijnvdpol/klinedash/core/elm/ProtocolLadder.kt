package nl.stijnvdpol.klinedash.core.elm

import nl.stijnvdpol.klinedash.core.car.CarMake

/*
 * The explicit protocol ladder [Elm327Session] climbs when the saved protocol and the ATSP0 search
 * both found nothing (clone auto-search is flaky, see docs/research/elm327-clones.md). Each rung is
 * an ELM protocol number, or [Elm327Session.TOYOTA_PROFILE] for the Toyota K-line init.
 *
 * Which protocols exist and in which order they are likely comes from docs/research/car-makes.md
 * section 4. The make is only a hint for the ORDER: the same model can need another protocol per
 * engine and year, so every make still gets every bus it can plausibly use (the one exception is
 * VAG, which has no J1850 at all).
 */
private const val PWM = "1"
private const val VPW = "2"
private const val ISO_9141 = "3"
private const val KWP_SLOW = "4"
private const val KWP_FAST = "5"
private const val CAN_11_500 = "6"
private const val CAN_29_500 = "7"
private const val CAN_11_250 = "8"
private const val CAN_29_250 = "9"
private const val TOYOTA = Elm327Session.TOYOTA_PROFILE

private val K_LINE_RUNGS = setOf(ISO_9141, KWP_SLOW, KWP_FAST, TOYOTA)

/** The two 250 kbaud variants go last: only a few Asian and Ford models use them. */
private val ALL_CAN = listOf(CAN_11_500, CAN_29_500, CAN_11_250, CAN_29_250)
private val ALL_J1850 = listOf(PWM, VPW)

/** Today's order for a car of unknown make, with the new CAN and J1850 rungs behind the K-line ones. */
private val UNKNOWN_MAKE_ORDER = listOf(ISO_9141, KWP_FAST, KWP_SLOW, TOYOTA) + ALL_CAN + ALL_J1850

/** ISO 9141, KWP slow, KWP fast: the usual order for German, Japanese and Korean cars. */
private val K_LINE_SLOW_FIRST = listOf(ISO_9141, KWP_SLOW, KWP_FAST)

/** KWP fast first: French cars and the small Opel/Suzuki/Fiat cars are mostly fast-init. */
private val K_LINE_FAST_FIRST = listOf(KWP_FAST, KWP_SLOW, ISO_9141)

/**
 * Ladder for a car of [make] (null = unknown or generic), every protocol at most once.
 *
 * - VAG: K-line, then CAN; never J1850 (Ross-Tech: generic OBD on VAG is 9141, 14230 and 15765 only).
 * - BMW, Mercedes, Toyota, Honda, Nissan, Mitsubishi, Subaru, Hyundai/Kia: K-line 3, 4, 5 first
 *   (klavkarr: ISO 9141 on most 1999-2006 rows), then CAN, then J1850. Toyota adds its profile
 *   after the plain K-line rungs, as it always did.
 * - Renault, PSA, Opel, Suzuki: KWP fast first (Clio II, 206, Astra G, Ignis), then the rest.
 * - Mazda: CAN 11/500 first, because a Mazda 6 is CAN on every engine since 2003 and a failed
 *   CAN rung is cheap (see [rungTimeoutMs]); K-line second for the older 323/626/Mazda 2.
 * - Fiat/Alfa/Lancia and Volvo: 29-bit/500k first (Punto II from 2006, S40 from 2004, both ELM
 *   protocol 7). Fiat then tries 11-bit CAN and fast-init K-line; Volvo, whose older cars are
 *   ISO 9141, goes to K-line.
 * - Ford: CAN 11/500 first (Fiesta VI 2002-08 all CAN, everything from 2008 on), then J1850 PWM
 *   (Focus I 1998-2004 and the American Fords), then K-line, then the other CAN buses, VPW last.
 * - Unknown make: ISO 9141, KWP fast, KWP slow, Toyota profile, the four CAN buses, PWM, VPW.
 *
 * Without the model year (unknown without a VIN) the order stays a guess; it only changes how
 * long a connection takes, never whether one is possible.
 */
internal fun fallbackOrder(make: CarMake?): List<String> = when (make) {
    null -> UNKNOWN_MAKE_ORDER
    CarMake.VW_GROUP -> K_LINE_SLOW_FIRST + ALL_CAN
    CarMake.TOYOTA_LEXUS -> K_LINE_SLOW_FIRST + TOYOTA + ALL_CAN + ALL_J1850
    CarMake.BMW_MINI,
    CarMake.MERCEDES_SMART,
    CarMake.HONDA_ACURA,
    CarMake.NISSAN_INFINITI,
    CarMake.MITSUBISHI,
    CarMake.SUBARU,
    CarMake.HYUNDAI_KIA,
    -> K_LINE_SLOW_FIRST + ALL_CAN + ALL_J1850
    CarMake.RENAULT_DACIA,
    CarMake.PSA,
    CarMake.OPEL_VAUXHALL,
    CarMake.SUZUKI,
    -> K_LINE_FAST_FIRST + ALL_CAN + ALL_J1850
    CarMake.MAZDA ->
        listOf(CAN_11_500) + K_LINE_SLOW_FIRST + listOf(CAN_29_500, CAN_11_250, CAN_29_250) + ALL_J1850
    CarMake.FIAT_ALFA_LANCIA ->
        listOf(CAN_29_500, CAN_11_500) + K_LINE_FAST_FIRST + listOf(CAN_11_250, CAN_29_250) + ALL_J1850
    CarMake.VOLVO ->
        listOf(CAN_29_500) + K_LINE_SLOW_FIRST + listOf(CAN_11_500, CAN_11_250, CAN_29_250) + ALL_J1850
    CarMake.FORD ->
        listOf(CAN_11_500, PWM, ISO_9141, KWP_FAST, KWP_SLOW, CAN_29_500, CAN_11_250, CAN_29_250, VPW)
}

/** True for the rungs that use a K-line 5-baud or fast init (ISO 9141, both KWP variants, the Toyota profile). */
internal fun isKLineRung(protocol: String): Boolean = protocol in K_LINE_RUNGS

/**
 * How long a rung may take to answer 0100. This is only the cap for an adapter that hangs: a
 * working adapter reports a dead bus much sooner.
 *
 * K-line: the 5-baud address byte alone takes 2 s (10 bits at 200 ms), then come the key bytes and
 * the ECU's response (docs/research/2zz-ge-obd.md), so 10 s as before.
 *
 * CAN and J1850: there is no wake-up sequence. A bus without traffic (or at the wrong speed) is
 * answered with UNABLE TO CONNECT / CAN ERROR in well under a second (docs/research/car-makes.md
 * 4.3, a reasoned estimate, not yet measured on a car), and a live ECU answers in tens of
 * milliseconds. Six seconds leaves headroom for a slow clone or an ECU that wakes late and keeps
 * the whole ladder short.
 */
internal fun rungTimeoutMs(protocol: String): Long =
    if (isKLineRung(protocol)) K_LINE_RUNG_TIMEOUT_MS else BUS_RUNG_TIMEOUT_MS

/**
 * Pause before trying a rung: ISO 9141-2 wants the bus idle for a while before the next 5-baud
 * init, a CAN or J1850 probe does not.
 */
internal fun pauseBeforeRungMs(protocol: String): Long =
    if (isKLineRung(protocol)) K_LINE_REINIT_PAUSE_MS else BUS_REINIT_PAUSE_MS

internal const val K_LINE_RUNG_TIMEOUT_MS = 10_000L
internal const val BUS_RUNG_TIMEOUT_MS = 6_000L
internal const val K_LINE_REINIT_PAUSE_MS = 1_500L
internal const val BUS_REINIT_PAUSE_MS = 300L

/** Plain-words name of a rung for the progress text ("Trying CAN 11-bit 500 kbaud"). */
internal fun protocolLabel(protocol: String): String = when (protocol) {
    PWM -> "J1850 PWM"
    VPW -> "J1850 VPW"
    ISO_9141 -> "ISO 9141-2"
    KWP_SLOW -> "KWP2000 (5-baud init)"
    KWP_FAST -> "KWP2000 (fast init)"
    CAN_11_500 -> "CAN 11-bit 500 kbaud"
    CAN_29_500 -> "CAN 29-bit 500 kbaud"
    CAN_11_250 -> "CAN 11-bit 250 kbaud"
    CAN_29_250 -> "CAN 29-bit 250 kbaud"
    TOYOTA -> "Toyota K-line profile"
    else -> "protocol $protocol"
}
