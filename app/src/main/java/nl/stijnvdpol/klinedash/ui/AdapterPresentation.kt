package nl.stijnvdpol.klinedash.ui

import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.elm.AdapterInfo
import nl.stijnvdpol.klinedash.core.elm.ChipFamily
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.core.elm.FailureKind

/*
 * What the screens say about the adapter, as plain functions: the Adapter section on Debug and the
 * list of adapters that work, which Connection shows for an unsupported one.
 */

/** Rows of the Adapter section on Debug, in display order. */
internal enum class AdapterField(val labelRes: Int) {
    CHIP(R.string.label_chip),
    FIRMWARE(R.string.label_firmware),
    PROTOCOL(R.string.label_protocol),
    CAN(R.string.label_can),
    ADAPTIVE_TIMING(R.string.label_adaptive_timing),
    SAVED_PROTOCOL(R.string.label_saved_protocol),
    UNSUPPORTED_COMMANDS(R.string.label_unsupported),
}

/** The words the Adapter section is written in, passed in so [adapterValues] stays free of Android. */
internal data class AdapterWords(
    val yes: String,
    val no: String,
    val none: String,
    val chipGenuine: String,
    val chipStn: String,
    val chipClone: String,
    val timingAt1: String,
    val timingAt2: String,
)

/**
 * What the Adapter section shows per field. A null value renders as "--": not connected, or the
 * adapter reported nothing conclusive (chip family unknown, no ATI answer).
 */
internal fun adapterValues(info: AdapterInfo?, words: AdapterWords): Map<AdapterField, String?> {
    val profile = info?.profile
    return AdapterField.entries.associateWith { field ->
        when (field) {
            AdapterField.CHIP -> when (profile?.family) {
                ChipFamily.GENUINE_ELM -> words.chipGenuine
                ChipFamily.STN -> words.chipStn
                ChipFamily.CLONE -> words.chipClone
                ChipFamily.UNKNOWN, null -> null
            }
            AdapterField.FIRMWARE -> profile?.firmware
            AdapterField.PROTOCOL -> info?.let { "${it.protocolName} (${it.protocolNumber})" }
            AdapterField.CAN -> info?.let { if (it.isCan) words.yes else words.no }
            AdapterField.ADAPTIVE_TIMING -> profile?.let { if (it.usesAdaptiveTiming2) words.timingAt2 else words.timingAt1 }
            AdapterField.SAVED_PROTOCOL -> info?.let { if (it.usedSavedProtocol) words.yes else words.no }
            AdapterField.UNSUPPORTED_COMMANDS -> info?.let { it.unsupportedCommands.sorted().joinToString().ifEmpty { words.none } }
        }
    }
}

/** One line of the "Adapters that work" list: a brand and the models that were verified to work. */
internal data class SupportedAdapter(val brand: String, val models: List<String> = emptyList()) {
    val label: String get() = if (models.isEmpty()) brand else "$brand ${models.joinToString(", ")}"
}

/**
 * Shown when the link ends in "unsupported adapter". Keep in sync with "Works well" and "Works, with
 * caveats" in the README section "Supported adapters"; SupportedAdaptersTest fails when a name here
 * is missing from the README.
 */
internal val SupportedAdapters = listOf(
    SupportedAdapter("OBDLink", listOf("LX", "MX+", "CX")),
    SupportedAdapter("Vgate vLinker", listOf("MC+", "BM+", "FS", "MS")),
    SupportedAdapter("Vgate iCar Pro 2S"),
    SupportedAdapter("Veepeak", listOf("OBDCheck BLE+", "VP11")),
    SupportedAdapter("Konnwei", listOf("KW902", "KW905")),
    SupportedAdapter("Viecar"),
    SupportedAdapter("BAFX"),
)

/** True when retrying is pointless and the status block should list the adapters that do work. */
internal fun ConnectionState.isUnsupportedAdapter(): Boolean =
    this is ConnectionState.Failed && kind == FailureKind.UNSUPPORTED_ADAPTER
