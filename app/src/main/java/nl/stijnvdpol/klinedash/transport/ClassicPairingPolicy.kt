package nl.stijnvdpol.klinedash.transport

import android.bluetooth.BluetoothDevice

/*
 * The rules of finding and pairing a Bluetooth Classic adapter from inside the app, free of Android
 * classes so they can be tested: which discovered devices are listed and in what order, the PIN to
 * answer a pairing request with, and the steps from "pairing" to "paired" or "failed".
 * ClassicSearch.kt and ClassicPairing.kt are the glue.
 */

/** Longest a pairing may take before it is called failed; the user may need time to type a PIN. */
internal const val PAIRING_TIMEOUT_MS = 30_000L

/** PINs ELM327 adapters come with, in the order they are tried: one per attempt, so a retry is not the same guess. */
internal val COMMON_PINS = listOf("1234", "0000", "6789")

/** The PIN to answer with on the [attempt]th try (0 is the first). */
internal fun pinForAttempt(attempt: Int): String {
    require(attempt >= 0) { "attempt must not be negative: $attempt" }
    return COMMON_PINS[attempt % COMMON_PINS.size]
}

/** Whether discovery results of this type and bond state belong in the pairing list: not LE-only, not paired yet. */
internal fun isPairableClassic(deviceType: Int, bondState: BondState): Boolean =
    deviceType != BluetoothDevice.DEVICE_TYPE_LE && bondState != BondState.BONDED

/** The bond state of a remote device, as far as pairing cares. */
internal enum class BondState { NONE, BONDING, BONDED }

internal fun bondStateOf(androidBondState: Int): BondState = when (androidBondState) {
    BluetoothDevice.BOND_BONDING -> BondState.BONDING
    BluetoothDevice.BOND_BONDED -> BondState.BONDED
    else -> BondState.NONE
}

/**
 * The list with [device] added or replacing the entry of the same address, in the order the user
 * should read it: likely OBD adapters first, then named before unnamed, then by name. A sighting
 * without a name never replaces one that has a name.
 */
internal fun mergeClassicResult(list: List<BtDeviceInfo>, device: BtDeviceInfo): List<BtDeviceInfo> {
    val index = list.indexOfFirst { it.address == device.address }
    val isNameLost = index >= 0 && device.hasNoName() && !list[index].hasNoName()
    if (isNameLost) return list
    val merged = if (index < 0) list + device else list.toMutableList().also { it[index] = device }
    return merged.sortedWith(
        compareByDescending<BtDeviceInfo> { it.looksLikeObd }
            .thenBy { it.hasNoName() }
            .thenBy { it.name.lowercase() }
            .thenBy { it.address },
    )
}

private fun BtDeviceInfo.hasNoName() = name == UNNAMED_DEVICE

/** What a search leaves to say besides the devices: null when it found some and nothing went wrong. */
internal fun classicSearchNotice(isDiscoveryStarted: Boolean, hasDevices: Boolean): BleScanNotice? = when {
    !isDiscoveryStarted -> BleScanNotice.DiscoveryRefused
    !hasDevices -> BleScanNotice.NothingFoundClassic
    else -> null
}

/** One line of the checklist shown when a search found nothing. */
internal enum class ClassicSearchCheck { LOCATION_ON, ADAPTER_POWERED, IN_RANGE, NOT_CONNECTED_ELSEWHERE, BRIDGE_FREE }

/** The checklist for [sdkInt]: the Location line only where Location matters. */
internal fun classicSearchChecks(sdkInt: Int): List<ClassicSearchCheck> = ClassicSearchCheck.entries.filter {
    it != ClassicSearchCheck.LOCATION_ON || needsLocationForScan(sdkInt)
}

/** How a pairing ended badly. */
internal enum class PairingFailure {
    /** Android would not start it (Bluetooth off, permission gone, another pairing busy). */
    NOT_STARTED,

    /** Android started it and the pairing broke off: wrong PIN, or the adapter said no. */
    REJECTED,

    /** Nothing happened within [PAIRING_TIMEOUT_MS]. */
    TIMED_OUT,
}

/** Where the pairing of one adapter stands. [attempt] counts tries of the same adapter, from 0. */
internal sealed interface ClassicPairing {
    data object Idle : ClassicPairing
    data class Pairing(val device: BtDeviceInfo, val attempt: Int) : ClassicPairing
    data class Paired(val device: BtDeviceInfo) : ClassicPairing
    data class Failed(val device: BtDeviceInfo, val attempt: Int, val reason: PairingFailure) : ClassicPairing
}

internal sealed interface PairingEvent {
    data class Start(val device: BtDeviceInfo, val attempt: Int) : PairingEvent

    /** ACTION_BOND_STATE_CHANGED for [address]. */
    data class BondChanged(val address: String, val previous: BondState, val current: BondState) : PairingEvent
    data object Refused : PairingEvent
    data object TimedOut : PairingEvent
    data object Dismiss : PairingEvent
}

/**
 * The state after [event]. A bond change of another device, or one that says nothing yet (a stack
 * may announce NONE before BONDING), leaves the state as it is; so does a Start while pairing runs.
 */
internal fun ClassicPairing.next(event: PairingEvent): ClassicPairing = when (event) {
    is PairingEvent.Start -> if (this is ClassicPairing.Pairing) this else ClassicPairing.Pairing(event.device, event.attempt)
    PairingEvent.Dismiss -> ClassicPairing.Idle
    else -> if (this is ClassicPairing.Pairing) whilePairing(event) else this
}

private fun ClassicPairing.Pairing.whilePairing(event: PairingEvent): ClassicPairing = when (event) {
    is PairingEvent.BondChanged -> when {
        event.address != device.address -> this
        event.current == BondState.BONDED -> ClassicPairing.Paired(device)
        event.current == BondState.NONE && event.previous == BondState.BONDING -> failed(PairingFailure.REJECTED)
        else -> this
    }
    PairingEvent.Refused -> failed(PairingFailure.NOT_STARTED)
    PairingEvent.TimedOut -> failed(PairingFailure.TIMED_OUT)
    is PairingEvent.Start, PairingEvent.Dismiss -> this
}

private fun ClassicPairing.Pairing.failed(reason: PairingFailure) = ClassicPairing.Failed(device, attempt, reason)

/** How to answer ACTION_PAIRING_REQUEST. */
internal sealed interface PairingAnswer {
    data class SetPin(val pin: String) : PairingAnswer

    /** Not ours to answer: Android's own dialog asks the user. */
    data object LetSystemAsk : PairingAnswer
}

/** Only a plain PIN request is answered by the app; confirmations and passkeys stay with the system dialog. */
internal fun answerPairingRequest(variant: Int, pin: String): PairingAnswer =
    if (variant == BluetoothDevice.PAIRING_VARIANT_PIN) PairingAnswer.SetPin(pin) else PairingAnswer.LetSystemAsk
