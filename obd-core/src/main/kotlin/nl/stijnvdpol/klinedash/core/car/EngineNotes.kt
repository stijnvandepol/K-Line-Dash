package nl.stijnvdpol.klinedash.core.car

/**
 * Troubleshooting notes for one engine, shown with its trouble codes on top of the make's meanings.
 * Only engines someone has researched are here; the user picks one, it cannot be detected.
 */
enum class EngineNotes(val make: CarMake) {
    /** Toyota 2ZZ-GE: Corolla T-Sport, Celica 1.8 VVTL-i (docs/research/2zz-ge-obd.md). */
    TOYOTA_2ZZ_GE(CarMake.TOYOTA_LEXUS),
}
