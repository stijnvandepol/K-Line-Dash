package nl.stijnvdpol.klinedash.core.elm

/**
 * What kind of chip answers behind the Bluetooth module. Guessed from behaviour, never from the
 * version text alone: clones print any version they like (docs/research/elm327-clones.md).
 */
enum class ChipFamily {
    /** Claims a real ELM327 release and passes every standard setup command. Heuristic, not proof. */
    GENUINE_ELM,

    /** ScanTool STN chip (OBDLink, probably vLinker): answers the ST command set, fast on CAN. */
    STN,

    /** Prints a version that never existed ("v1.5", "v2.1") or rejects commands a real ELM327 accepts. */
    CLONE,

    /** Nothing conclusive. */
    UNKNOWN,
}

/**
 * Result of the init-time fingerprint of an adapter ([Elm327Session.initialize]). Only facts the
 * adapter itself reported plus the [family] derived from them; the UI can show it in a debug report.
 *
 * @property firmware the ATI answer, e.g. "ELM327 v1.5"; null when ATI gave nothing
 * @property description the AT@1 answer, e.g. "OBDII to RS232 Interpreter"; null when rejected
 * @property stnId the STI answer ("STN1110 v4.2.0"); null on everything that is not an STN chip
 * @property usesAdaptiveTiming2 true when ATAT2 (aggressive adaptive timing) was accepted and is active
 */
data class AdapterProfile(
    val family: ChipFamily = ChipFamily.UNKNOWN,
    val firmware: String? = null,
    val description: String? = null,
    val stnId: String? = null,
    val usesAdaptiveTiming2: Boolean = false,
) {
    companion object {
        val UNKNOWN = AdapterProfile()

        /** Versions a genuine ELM327 reported over its life (ELM Electronics release history). */
        private val REAL_VERSIONS = setOf("1.0", "1.1", "1.2", "1.3", "1.3a", "1.4", "1.4b", "2.0", "2.2", "2.3")

        /** Printed by cheap clones, though ELM Electronics never shipped them in this form. */
        private val FAKE_VERSIONS = setOf("1.5", "2.1")

        /**
         * Setup commands a real ELM327 accepts. A clone answering "?" to one of them is a clone
         * whatever it prints (the "v2.1" ARM boards miss ATAT1, ATS0 and AT@1).
         */
        private val COMMANDS_REAL_CHIPS_ACCEPT = setOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "AT@1")

        private val VERSION = Regex("""ELM327\s+v?(\d+\.\d+[a-z]?)""", RegexOption.IGNORE_CASE)
        private val STN_ID = Regex("""^(STN\d+|OBDLINK)""", RegexOption.IGNORE_CASE)

        /** The family from what the identity probes returned; [unsupported] are the commands answered with "?". */
        fun classify(firmware: String?, stnId: String?, unsupported: Set<String>): ChipFamily {
            if (stnId != null && STN_ID.containsMatchIn(stnId.trim())) return ChipFamily.STN
            val version = firmware?.let { VERSION.find(it)?.groupValues?.get(1)?.lowercase() }
                ?: return ChipFamily.UNKNOWN
            return when {
                version in FAKE_VERSIONS -> ChipFamily.CLONE
                unsupported.any { it in COMMANDS_REAL_CHIPS_ACCEPT } -> ChipFamily.CLONE
                version in REAL_VERSIONS -> ChipFamily.GENUINE_ELM
                else -> ChipFamily.UNKNOWN
            }
        }
    }
}
