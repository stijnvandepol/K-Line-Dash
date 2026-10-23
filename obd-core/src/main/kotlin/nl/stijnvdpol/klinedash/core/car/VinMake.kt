package nl.stijnvdpol.klinedash.core.car

import nl.stijnvdpol.klinedash.core.diag.VehicleInfoParser

private const val WMI_LENGTH = 3
private const val PREFIX_LENGTH = 2

/**
 * World manufacturer identifiers (the first three VIN characters) per make, from the KBA and
 * Wikipedia lists in docs/research/car-makes.md section 3.2. A make here covers its sister brands
 * (Audi and Seat under VW Group, Lexus under Toyota, Infiniti under Nissan, Acura under Honda).
 */
private val WmiMakes: Map<String, CarMake> = wmiTable(
    CarMake.VW_GROUP to "WVW WVG WV1 WV2 AAV 3VW 1VW 9BW WAU WUA TRU VSS TMB TMP",
    // WAP is Alpina: BMW engines and ECUs, although its prefix is Audi's.
    CarMake.BMW_MINI to "WBA WBS WBX WBY WB5 WB6 WMW WMZ WAP",
    CarMake.MERCEDES_SMART to "WDB WDC WDD WDF WMX VSC NMB WME",
    CarMake.OPEL_VAUXHALL to "W0L W0V W0S VXK VXE VSX",
    CarMake.FORD to "WF0 VS6 NM0 $NORTH_AMERICAN_FORD",
    CarMake.RENAULT_DACIA to "VF1 VS5 NM1 KNM UU1",
    CarMake.PSA to "VF3 VR3 VS8 VF7 VR7 VS7 VR1",
    CarMake.FIAT_ALFA_LANCIA to "ZFA ZFB ZAR ZAS ZLA",
    CarMake.VOLVO to "YV1 YV4 XLB",
    CarMake.TOYOTA_LEXUS to "JT2 JT3 JT4 JTD JTE JTF JTG JTK JTL JTM JTN SB1 VNK NMT AHT MR0 TW1 $NORTH_AMERICAN_TOYOTA",
    CarMake.HONDA_ACURA to "JHM JHL JH4 SHH SHS NLA MRH $NORTH_AMERICAN_HONDA",
    CarMake.NISSAN_INFINITI to "JN1 JN6 JN8 JNK JNR JNX SJN SJK VSK VNV $NORTH_AMERICAN_NISSAN",
    CarMake.MAZDA to "JM1 JM3 JM6 JM7 JMZ JM0",
    CarMake.MITSUBISHI to "JA3 JA4 JA7 JMA JMB JMF JMY XMC",
    CarMake.SUBARU to "JF1 JF2 JF3 4S3 4S4",
    CarMake.SUZUKI to "JS2 JS3 TSM MA3",
    CarMake.HYUNDAI_KIA to "KMH KM8 KMT KMU TMA TMC NLH NLJ MAL KNA KNC KNE KND U5Y U6Y 5NP 5XY",
)

/*
 * Cars built in the US, Canada and Mexico, common as imports: Ford cars, SUVs and trucks; Toyota
 * (Kentucky, Indiana, Mississippi, Canada); Honda and Acura (Ohio, Alabama, Canada); Nissan
 * (Tennessee, Mexico).
 */
private const val NORTH_AMERICAN_FORD = "1FA 1FM 1FT 2FA 2FM 3FA"
private const val NORTH_AMERICAN_TOYOTA = "4T1 4T3 5TD 2T1 5YF"
private const val NORTH_AMERICAN_HONDA = "1HG 2HG 5FN 19X"
private const val NORTH_AMERICAN_NISSAN = "1N4 1N6 3N1 5N1"

/**
 * Identifiers that sit under a two-character prefix below but are not a make this app knows:
 * motorcycles and quads (BMW Motorrad, Suzuki, Honda), Isuzu, vans and trucks (Mercedes Sprinter and
 * Freightliner, Volvo trucks and buses), Ferrari, and the Ford Festiva that Kia built. They are
 * "other make", never the make of their prefix.
 */
private val OtherWmis: Set<String> = wmiList(
    "WB1 WB3 WB4 JS1 JH2 JH3 JAA JAB JAC JAE JAL JAM KM4 KM9 KNJ YV2 YV3 ZFF " +
        "WD0 WD1 WD2 WD3 WD4 WD5 WD6 WD7 WD8 WD9",
)

/** The make of every identifier that starts with these two characters and is neither listed above nor in [OtherWmis]. */
private val PrefixMakes: Map<String, CarMake> = mapOf(
    "WV" to CarMake.VW_GROUP,
    "WA" to CarMake.VW_GROUP,
    "WB" to CarMake.BMW_MINI,
    "WD" to CarMake.MERCEDES_SMART,
    "ZF" to CarMake.FIAT_ALFA_LANCIA,
    "YV" to CarMake.VOLVO,
    "JT" to CarMake.TOYOTA_LEXUS,
    "JH" to CarMake.HONDA_ACURA,
    "JN" to CarMake.NISSAN_INFINITI,
    "JM" to CarMake.MAZDA,
    "JA" to CarMake.MITSUBISHI,
    "JF" to CarMake.SUBARU,
    "JS" to CarMake.SUZUKI,
    "KM" to CarMake.HYUNDAI_KIA,
    "KN" to CarMake.HYUNDAI_KIA,
)

/**
 * The make family of a car from its VIN, or null when the VIN is not a valid VIN or its maker is not
 * one of [CarMake]. Null is not an error: Porsche, Bentley, Lamborghini, Jaguar, Land Rover,
 * Chevrolet, Chrysler, motorcycles and unknown identifiers are all "other make".
 *
 * The three-character identifier decides first; only an identifier the table does not know falls
 * back to its two-character prefix (docs/research/car-makes.md section 3.2). The make follows the
 * engine ECU, not the badge, so the result is a hint the user can override.
 */
fun carMakeFromVin(vin: String): CarMake? {
    if (!VehicleInfoParser.isVin(vin)) return null
    val wmi = vin.take(WMI_LENGTH)
    WmiMakes[wmi]?.let { return it }
    if (wmi in OtherWmis) return null
    return PrefixMakes[wmi.take(PREFIX_LENGTH)]
}

private fun wmiList(wmis: String): Set<String> = wmis.split(' ').toSet()

/** Fails fast on a typo in the table: an identifier listed under two makes would silently keep the last. */
private fun wmiTable(vararg groups: Pair<CarMake, String>): Map<String, CarMake> {
    val entries = groups.flatMap { (make, wmis) -> wmis.split(' ').map { it to make } }
    val table = entries.toMap()
    check(table.size == entries.size) { "A WMI is listed twice in the make table" }
    return table
}
