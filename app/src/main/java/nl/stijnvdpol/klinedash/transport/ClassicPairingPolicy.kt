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

/** How long to wait for discovery to really stop before bonding: cancelDiscovery() returns before the stack has stopped. */
internal const val DISCOVERY_STOP_WAIT_MS = 1_500L

/**
 * How long to wait, after a fresh bond, for Android's own SDP lookup to report the device's services
 * (ACTION_UUID) before the first connect, so the app's lookup does not collide with it.
 */
internal const val UUID_WAIT_MS = 1_500L

/** How long a removed bond may take to show as gone, and how often it is looked at meanwhile. */
internal const val BOND_REMOVE_WAIT_MS = 3_000L
internal const val BOND_POLL_MS = 100L

/** PINs ELM327 adapters come with, in the order they are tried: one per attempt, so a retry is not the same guess. */
internal val COMMON_PINS = listOf("1234", "0000", "6789")

/** The PIN to answer with on the [attempt]th try (0 is the first). */
internal fun pinForAttempt(attempt: Int): String {
    require(attempt >= 0) { "attempt must not be negative: $attempt" }
    return COMMON_PINS[attempt % COMMON_PINS.size]
}

/**
 * Whether a discovery result of this type belongs in the pairing list: anything but LE-only. Paired
 * devices are listed too: a stale pairing (the adapter was re-flashed and forgot its keys) would
 * otherwise hide the very adapter the user is looking for.
 */
internal fun isClassicCapable(deviceType: Int): Boolean = deviceType != BluetoothDevice.DEVICE_TYPE_LE

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
    /** Android would not start it (another pairing busy). */
    NOT_STARTED,

    /** There is no Bluetooth adapter to pair with. */
    NO_ADAPTER,

    /** Bluetooth is off. */
    BLUETOOTH_OFF,

    /** The Bluetooth permission is not granted. */
    PERMISSION_MISSING,

    /** An earlier pairing with the device could not be removed. */
    UNPAIR_FAILED,

    /** Android started it and the pairing broke off on authentication: wrong PIN, or the adapter said no. */
    REJECTED,

    /** Android started it and the pairing broke off for another reason: the device was out of range or not discoverable. */
    UNREACHABLE,

    /** Nothing happened within [PAIRING_TIMEOUT_MS]. */
    TIMED_OUT,
}

/** The reason to give up before bonding, or null when pairing may start. Pairing needs no Location, unlike searching. */
internal fun decidePairingBlock(environment: ScanEnvironment): PairingFailure? = when {
    !environment.hasAdapter -> PairingFailure.NO_ADAPTER
    !environment.isBluetoothOn -> PairingFailure.BLUETOOTH_OFF
    else -> null
}

/**
 * The failure for a bond that went from BONDING back to NONE, from the hidden
 * `android.bluetooth.device.extra.REASON` of ACTION_BOND_STATE_CHANGED (BluetoothDevice.UNBOND_REASON_*).
 * Relied on: 1 = AUTH_FAILED (wrong PIN or key) and 2 = AUTH_REJECTED (the remote said no) are
 * [PairingFailure.REJECTED]; every other value (3 AUTH_CANCELED, 4 REMOTE_DEVICE_DOWN, 5 DISCOVERY_IN_PROGRESS,
 * 6 AUTH_TIMEOUT, 7 REPEATED_ATTEMPTS, 8 REMOTE_AUTH_CANCELED, ...) is [PairingFailure.UNREACHABLE].
 * A missing reason (a stack that does not send it) counts as REJECTED, so a retry still tries another PIN.
 */
internal fun failureForUnbondReason(reason: Int?): PairingFailure = when (reason) {
    null, UNBOND_REASON_AUTH_FAILED, UNBOND_REASON_AUTH_REJECTED -> PairingFailure.REJECTED
    else -> PairingFailure.UNREACHABLE
}

/** Extra of ACTION_BOND_STATE_CHANGED that carries the UNBOND_REASON_*; hidden in the SDK. */
internal const val EXTRA_UNBOND_REASON = "android.bluetooth.device.extra.REASON"
private const val UNBOND_REASON_AUTH_FAILED = 1
private const val UNBOND_REASON_AUTH_REJECTED = 2

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
    data class BondChanged(
        val address: String,
        val previous: BondState,
        val current: BondState,
        /** UNBOND_REASON_* when the bond fell back to NONE, null when the broadcast carried none. */
        val unbondReason: Int? = null,
    ) : PairingEvent

    /** createBond() returned false. */
    data object Refused : PairingEvent

    /** Pairing could not start, for [reason]. */
    data class Blocked(val reason: PairingFailure) : PairingEvent

    /** The time ran out; [bondState] is the bond as it stands then (a bond made without a broadcast reaching us still counts). */
    data class TimedOut(val bondState: BondState) : PairingEvent
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
        event.current == BondState.NONE && event.previous == BondState.BONDING -> failed(failureForUnbondReason(event.unbondReason))
        else -> this
    }
    PairingEvent.Refused -> failed(PairingFailure.NOT_STARTED)
    is PairingEvent.Blocked -> failed(event.reason)
    is PairingEvent.TimedOut -> if (event.bondState == BondState.BONDED) ClassicPairing.Paired(device) else failed(PairingFailure.TIMED_OUT)
    is PairingEvent.Start, PairingEvent.Dismiss -> this
}

/** The attempt to retry a failure with: the next PIN only when the adapter turned this one down. */
internal fun ClassicPairing.Failed.nextAttempt(): Int = if (reason == PairingFailure.REJECTED) attempt + 1 else attempt

private fun ClassicPairing.Pairing.failed(reason: PairingFailure) = ClassicPairing.Failed(device, attempt, reason)

/** How to answer ACTION_PAIRING_REQUEST. */
internal sealed interface PairingAnswer {
    data class SetPin(val pin: String) : PairingAnswer

    /** Not ours to answer: Android's own dialog asks the user. */
    data object LetSystemAsk : PairingAnswer
}

/** The name of an EXTRA_PAIRING_VARIANT for the log. */
internal fun pairingVariantName(variant: Int): String = when (variant) {
    BluetoothDevice.PAIRING_VARIANT_PIN -> "PIN"
    BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION -> "PASSKEY_CONFIRMATION"
    else -> "OTHER($variant)"
}

/** Only a plain PIN request is answered by the app; confirmations and passkeys stay with the system dialog. */
internal fun answerPairingRequest(variant: Int, pin: String): PairingAnswer =
    if (variant == BluetoothDevice.PAIRING_VARIANT_PIN) PairingAnswer.SetPin(pin) else PairingAnswer.LetSystemAsk
