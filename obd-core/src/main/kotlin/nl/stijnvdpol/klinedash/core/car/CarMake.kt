package nl.stijnvdpol.klinedash.core.car

/**
 * The make families the app knows, grouped the way their engine ECUs share trouble codes and
 * connection habits (see docs/research/car-makes.md). A car of any other make is "generic": the
 * code that takes a make uses null for it.
 *
 * The make follows the engine ECU, not the badge (a Nissan Micra 1.5 dCi runs a Renault ECU), so a
 * make detected from the VIN is a hint the user can override.
 */
enum class CarMake {
    VW_GROUP,
    BMW_MINI,
    MERCEDES_SMART,
    OPEL_VAUXHALL,
    FORD,
    RENAULT_DACIA,
    PSA,
    FIAT_ALFA_LANCIA,
    VOLVO,
    TOYOTA_LEXUS,
    HONDA_ACURA,
    NISSAN_INFINITI,
    MAZDA,
    MITSUBISHI,
    SUBARU,
    SUZUKI,
    HYUNDAI_KIA,
}
